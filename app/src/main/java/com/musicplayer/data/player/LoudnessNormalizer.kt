package com.musicplayer.data.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.musicplayer.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * Нормализация громкости — собственная реализация по мотивам ITU-R BS.1770-4 / EBU R128,
 * той же цепочки, которой пользуются ReplayGain и стриминговые сервисы.
 *
 * Как это работает (кратко):
 *  1. Декодируем файл в PCM через MediaCodec (без перекодирования, только анализ).
 *  2. Прогоняем каждый канал через K-взвешивание — два каскадных биквад-фильтра:
 *     high-shelf (+4 дБ выше ~1.68 кГц, имитация чувствительности уха) и
 *     high-pass 38 Гц (отсекаем неслышимый гул). Коэффициенты считаются по
 *     формулам BS.1770-4 для произвольной частоты дискретизации.
 *  3. Нарезаем энергию на блоки 400 мс с шагом 100 мс (перекрытие 75 %).
 *  4. Интегрированная громкость с двойным гейтированием:
 *       • абсолютный порог −70 LUFS (отсекаем тишину);
 *       • относительный порог −10 LU от среднего негейтированного уровня
 *         (отсекаем редкие тихие пассажи, чтобы они не занижали оценку).
 *  5. Итог: LUFS = −0.691 + 10·log10(средняя энергия гейтированных блоков).
 *  6. gain_dB = TARGET_LUFS − LUFS. Применяем только ОСЛАБЛЕНИЕ (множитель ≤ 1):
 *     MediaPlayer.setVolume не поднимает громкость выше 1.0, а ослаблять громкие
 *     мастеринги — ровно то, что нужно, чтобы все треки звучали одинаково громко.
 *
 * Результат кэшируется на диск (filesDir/loudness/cache.json) по ключу
 * «id:длительность», поэтому анализ для трека выполняется один раз.
 */
object LoudnessNormalizer {

    private const val TAG = "LoudnessNormalizer"

    /** Целевой уровень громкости — как у Spotify/YouTube (−14 LUFS). */
    const val TARGET_LUFS = -14.0

    /** Максимальное ослабление, чтобы совсем тихие треки не вычитались в ноль. */
    private const val MAX_ATTENUATION_DB = -24.0

    /** Ограничение времени анализа на трек (страховка от битых/гигантских файлов). */
    private const val MAX_ANALYSIS_SECONDS = 60 * 12L

    private const val BLOCK_MS = 400L
    private const val HOP_MS = 100L

    data class Loudness(val integratedLufs: Double, val gainDb: Double) {
        /** Множитель громкости ≤ 1 для MediaPlayer.setVolume. */
        val factor: Float
            get() = 10.0.pow(gainDb / 20.0).coerceIn(0f.toDouble(), 1.0).toFloat()
    }

    // ── Кэш ──────────────────────────────────────────────────────────────────

    private val cache = ConcurrentHashMap<String, Loudness>()
    private val cacheMutex = Mutex()
    private var cacheFile: File? = null
    private var cacheDirty = false

    fun cacheKey(song: Song): String = "${song.id}:${song.duration}"

    /** Загружает кэш с диска один раз за процесс. */
    fun primeCache(context: Context) {
        if (cacheFile != null) return
        synchronized(this) {
            if (cacheFile != null) return
            val file = File(File(context.filesDir, "loudness"), "cache.json")
            cacheFile = file
            if (!file.exists()) return
            runCatching {
                val root = JSONObject(file.readText())
                val keys = root.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val obj = root.optJSONObject(key) ?: continue
                    val lufs = obj.optDouble("lufs", Double.NaN)
                    val gain = obj.optDouble("gain", Double.NaN)
                    if (lufs.isNaN() || gain.isNaN()) continue
                    cache[key] = Loudness(lufs, gain)
                }
            }.onFailure { Log.w(TAG, "Не удалось прочитать кэш нормализации", it) }
        }
    }

    /** Возвращает кэшированный множитель без запуска анализа. */
    fun cachedFactor(context: Context, song: Song): Float {
        primeCache(context)
        return cache[cacheKey(song)]?.factor ?: 1f
    }

    fun cachedLoudness(context: Context, song: Song): Loudness? {
        primeCache(context)
        return cache[cacheKey(song)]
    }

    /**
     * Гарантирует, что трек проанализирован. Возвращает true, если анализ был
     * выполнен сейчас (значит громкость надо переприменить).
     */
    suspend fun ensureAnalyzed(context: Context, song: Song): Boolean = withContext(Dispatchers.IO) {
        primeCache(context)
        val key = cacheKey(song)
        if (cache.containsKey(key)) return@withContext false
        val loudness = analyze(context, song) ?: return@withContext false
        cacheMutex.withLock {
            cache[key] = loudness
            cacheDirty = true
            persistIfNeeded()
        }
        true
    }

    /** Удаляет запись из кэша (и с диска). Suspend: запись на диск только из корутины. */
    suspend fun forget(song: Song) {
        cache.remove(cacheKey(song))
        cacheMutex.withLock {
            cacheDirty = true
            persistIfNeeded()
        }
    }

    private suspend fun persistIfNeeded() {
        val file = cacheFile ?: return
        if (!cacheDirty) return
        runCatching {
            file.parentFile?.mkdirs()
            val root = JSONObject()
            for ((key, value) in cache) {
                root.put(
                    key,
                    JSONObject().put("lufs", value.integratedLufs).put("gain", value.gainDb)
                )
            }
            file.writeText(root.toString())
            cacheDirty = false
        }.onFailure { Log.w(TAG, "Не удалось сохранить кэш нормализации", it) }
    }

    // ── Анализ ───────────────────────────────────────────────────────────────

    /** Анализирует трек и возвращает громкость/усиление; null — если не удалось. */
    private fun analyze(context: Context, song: Song): Loudness? {
        val extractor = MediaExtractor()
        try {
            // Трёхаргументная перегрузка (API 16+): двухаргументная появилась только в API 29,
            // а minSdk проекта — 26. NoSuchMethodError не ловится catch (e: Exception).
            extractor.setDataSource(context, song.uri, null)
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }
            if (audioTrackIndex < 0 || format == null) return null

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(audioTrackIndex)

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
            if (sampleRate <= 0) return null

            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            var decoder: MediaCodec? = null

            val kFilterL = KWeightingFilter(sampleRate)
            val kFilterR = KWeightingFilter(sampleRate)
            val hopSamples = (sampleRate * HOP_MS / 1000.0).toLong().coerceAtLeast(1L)
            val hopCollector = HopCollector(channels = channelCount).apply { setHopTarget(hopSamples) }

            var totalSamples = 0L
            val maxSamples = sampleRate.toLong() * MAX_ANALYSIS_SECONDS

            try {
                decoder = MediaCodec.createDecoderByType(mime)
                decoder.configure(format, null, null, 0)
                decoder.start()

                while (!sawOutputEos && totalSamples < maxSamples) {
                    if (!sawInputEos) {
                        val inIndex = decoder.dequeueInputBuffer(10_000L)
                        if (inIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(inIndex) ?: continue
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                sawInputEos = true
                            } else {
                                decoder.queueInputBuffer(
                                    inIndex, 0, sampleSize, extractor.sampleTime, 0
                                )
                                extractor.advance()
                            }
                        }
                    }

                    val outIndex = decoder.dequeueOutputBuffer(info, 10_000L)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            // Формат мог уточниться после запуска — читаем реальные параметры.
                        }
                        outIndex >= 0 -> {
                            val outBuffer = decoder.getOutputBuffer(outIndex) ?: continue
                            val pcmFormat = decoder.getOutputFormat()
                            val actualChannels =
                                if (pcmFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                                    pcmFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                        .takeIf { it > 0 } ?: channelCount
                                else channelCount
                            // Ключ может отсутствовать на части прошивок — декодеры по умолчанию
                            // выдают 16-бит PCM.
                            val pcmEncoding = if (pcmFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                pcmFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            else
                                android.media.AudioFormat.ENCODING_PCM_16BIT

                            outBuffer.order(ByteOrder.nativeOrder())
                            var frames = 0L
                            when (pcmEncoding) {
                                android.media.AudioFormat.ENCODING_PCM_FLOAT ->
                                    frames = hopCollector.consumeFloat(
                                        outBuffer, actualChannels, kFilterL, kFilterR
                                    )
                                else ->
                                    frames = hopCollector.consumeShort(
                                        outBuffer, actualChannels, kFilterL, kFilterR
                                    )
                            }
                            totalSamples += frames
                            decoder.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawOutputEos = true
                            }
                        }
                    }
                }
            } finally {
                runCatching { decoder?.stop() }
                runCatching { decoder?.release() }
            }

            val blockEnergy = hopCollector.buildBlockEnergies()
            if (blockEnergy.isEmpty()) return null
            val integrated = integratedLoudness(blockEnergy)
            if (integrated.isNaN()) return null

            val gainDb = (TARGET_LUFS - integrated).coerceIn(MAX_ATTENUATION_DB, 12.0)
            Log.d(TAG, "${song.title}: ${"%.1f".format(integrated)} LUFS → ${"%.1f".format(gainDb)} дБ")
            return Loudness(integrated, gainDb)
        } catch (e: Exception) {
            Log.w(TAG, "Анализ громкости не удался для ${song.title}", e)
            return null
        } catch (e: Error) {
            // NoSuchMethodError и т.п. — не Exception, но анализ не должен ронять приложение.
            Log.w(TAG, "Анализ громкости не удался (Error) для ${song.title}", e)
            return null
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Интегрированная громкость по ITU-R BS.1770: −0.691 + 10·log10(mean),
     * абсолютное гейтирование −70 LUFS + относительное −10 LU.
     *
     * @param blockEnergies суммарная (по всем каналам с весами) энергия блоков 400 мс
     */
    private fun integratedLoudness(blockEnergies: List<Double>): Double {
        if (blockEnergies.isEmpty()) return Double.NaN
        val loudOf: (Double) -> Double = { e -> -0.691 + 10.0 * log10(e.coerceAtLeast(1e-12)) }

        val aboveAbsolute = blockEnergies.filter { loudOf(it) > -70.0 }
        if (aboveAbsolute.isEmpty()) return Double.NaN

        val ungatedMean = aboveAbsolute.sum() / aboveAbsolute.size
        val ungatedLoudness = loudOf(ungatedMean)
        val relativeThreshold = ungatedLoudness - 10.0

        val gated = aboveAbsolute.filter { loudOf(it) > relativeThreshold }
        val usable = if (gated.isEmpty()) aboveAbsolute else gated
        return loudOf(usable.sum() / usable.size)
    }

    // ── K-взвешивание (ITU-R BS.1770-4, коэффициенты для произвольного fs) ──

    /**
     * Два каскадных биквад-фильтра: high-shelf 1681.97 Гц (+4 дБ) и high-pass 38.14 Гц.
     * Формулы — из справочного кода BS.1770-4.
     */
    internal class KWeightingFilter(sampleRate: Int) {
        private val shelfB: DoubleArray
        private val shelfA: DoubleArray
        private val hpB: DoubleArray
        private val hpA: DoubleArray

        // Состояние DF1
        private var x1 = 0.0; private var x2 = 0.0
        private var y1 = 0.0; private var y2 = 0.0
        private var x1b = 0.0; private var x2b = 0.0
        private var y1b = 0.0; private var y2b = 0.0

        init {
            val fs = sampleRate.toDouble()

            // Стадия 1: high-shelf (предыскажение чувствительности уха)
            val shelfF0 = 1681.9744509555319
            val shelfG = 3.99984385397
            val shelfQ = 0.7071752369554196
            val kShelf = tan(PI * shelfF0 / fs)
            val vh = 10.0.pow(shelfG / 20.0)
            val vb = vh.pow(0.4996667741545416)
            val shelfA0 = 1.0 + kShelf / shelfQ + kShelf * kShelf
            shelfB = doubleArrayOf(
                (vh + vb * kShelf / shelfQ + kShelf * kShelf) / shelfA0,
                2.0 * (kShelf * kShelf - vh) / shelfA0,
                (vh - vb * kShelf / shelfQ + kShelf * kShelf) / shelfA0
            )
            shelfA = doubleArrayOf(
                1.0,
                2.0 * (kShelf * kShelf - 1.0) / shelfA0,
                (1.0 - kShelf / shelfQ + kShelf * kShelf) / shelfA0
            )

            // Стадия 2: high-pass RLB (отсечение инфранизких частот)
            val hpF0 = 38.13547087602444
            val hpQ = 0.5003270373238773
            val kHp = tan(PI * hpF0 / fs)
            val hpA0 = 1.0 + kHp / hpQ + kHp * kHp
            hpB = doubleArrayOf(1.0, -2.0, 1.0)
            hpA = doubleArrayOf(
                1.0,
                2.0 * (kHp * kHp - 1.0) / hpA0,
                (1.0 - kHp / hpQ + kHp * kHp) / hpA0
            )
        }

        /** Прогоняет один сэмпл через обе стадии, возвращает взвешенное значение. */
        fun process(x: Double): Double {
            // Стадия 1 (shelf)
            val y = shelfB[0] * x + shelfB[1] * x1 + shelfB[2] * x2 -
                shelfA[1] * y1 - shelfA[2] * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y

            // Стадия 2 (high-pass)
            val yb = hpB[0] * y + hpB[1] * x1b + hpB[2] * x2b -
                hpA[1] * y1b - hpA[2] * y2b
            x2b = x1b; x1b = y
            y2b = y1b; y1b = yb
            return yb
        }
    }

    /**
     * Копит энергию каналов по 100-мс «порциям» (hop), затем склеивает их в
     * блоки 400 мс с перекрытием 75 % — ровно как в BS.1770.
     */
    private class HopCollector(val channels: Int) {
        private var currentSumL = 0.0
        private var currentSumR = 0.0
        private var samplesInHop = 0L
        private val hopL = mutableListOf<Double>()
        private val hopR = mutableListOf<Double>()
        private val hopFilled = mutableListOf<Boolean>()
        private var hopTarget = Long.MAX_VALUE

        fun consumeShort(
            buffer: java.nio.ByteBuffer,
            fileChannels: Int,
            filterL: KWeightingFilter,
            filterR: KWeightingFilter
        ): Long {
            val shortBuffer = buffer.asShortBuffer()
            val frames = shortBuffer.remaining() / fileChannels
            var frame = 0L
            while (frame < frames) {
                val base = (frame * fileChannels).toInt()
                val l = shortBuffer.get(base).toInt() / 32768.0
                val r = if (fileChannels > 1) shortBuffer.get(base + 1).toInt() / 32768.0 else l
                push(l, r, filterL, filterR)
                frame++
            }
            return frames
        }

        fun consumeFloat(
            buffer: java.nio.ByteBuffer,
            fileChannels: Int,
            filterL: KWeightingFilter,
            filterR: KWeightingFilter
        ): Long {
            val floatBuffer = buffer.asFloatBuffer()
            val frames = floatBuffer.remaining() / fileChannels
            var frame = 0L
            while (frame < frames) {
                val base = frame * fileChannels
                val l = floatBuffer.get(base).toDouble()
                val r = if (fileChannels > 1) floatBuffer.get(base + 1).toDouble() else l
                push(l, r, filterL, filterR)
                frame++
            }
            return frames
        }

        private fun push(l: Double, r: Double, filterL: KWeightingFilter, filterR: KWeightingFilter) {
            if (samplesInHop == 0L) {
                // Новый hop: помечаем его достаточно заполненным только после проверки.
                hopL.add(0.0); hopR.add(0.0); hopFilled.add(true)
            }
            currentSumL += square(filterL.process(l))
            if (channels > 1) {
                currentSumR += square(filterR.process(r))
            }
            samplesInHop++
            if (samplesInHop >= hopTarget) {
                finalizeHop()
            }
        }

        /** Задаёт ожидаемое число сэмплов в 100-мс hop (зависит от sample rate). */
        fun setHopTarget(target: Long) {
            hopTarget = target.coerceAtLeast(1L)
        }

        private fun finalizeHop() {
            if (samplesInHop > 0L) {
                val idx = hopL.lastIndex
                if (idx >= 0) {
                    hopL[idx] = currentSumL / samplesInHop
                    hopR[idx] = currentSumR / samplesInHop
                    // Hop заполнен меньше чем наполовину — почти конец файла,
                    // такой блок дал бы смещённую оценку энергии.
                    hopFilled[idx] = samplesInHop * 2 >= hopTarget
                }
            }
            currentSumL = 0.0; currentSumR = 0.0; samplesInHop = 0
        }

        /** Формирует энергии блоков 400 мс; вызывается после конца потока. */
        fun buildBlockEnergies(): List<Double> {
            finalizeHop()
            if (hopL.isEmpty()) return emptyList()

            val hopsPerBlock = (BLOCK_MS / HOP_MS).toInt() // 4
            val blocks = mutableListOf<Double>()
            var start = 0
            while (start + hopsPerBlock <= hopL.size) {
                var energy = 0.0
                var valid = true
                for (i in start until start + hopsPerBlock) {
                    if (!hopFilled[i]) { valid = false; break }
                    // BS.1770: моно — центральный канал с весом 1.0, удваивать энергию нельзя,
                    // иначе моно-треки получат лишнее −3 дБ ослабления.
                    energy += hopL[i] + if (channels > 1) hopR[i] else 0.0
                }
                if (valid) blocks.add(energy / hopsPerBlock)
                start += 1 // шаг 100 мс → перекрытие 75 %
            }
            return blocks
        }

        private fun square(v: Double): Double = v * v
    }
}
