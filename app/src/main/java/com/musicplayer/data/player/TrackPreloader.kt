package com.musicplayer.data.player

import android.content.Context
import android.net.Uri
import com.musicplayer.data.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Кусочная предзагрузка следующего трека в файловый кэш.
 *
 * Зачем: подготовка MediaPlayer напрямую по content:// (MediaStore или провайдер
 * плагина) может занимать секунды — в момент кроссфейда это слышно как стоп-кадр.
 * Здесь трек забирается потоково, маленькими кусками: начало файла появляется в
 * кэше первым, остальное докачивается в фоне с паузами между кусками, без пиков
 * нагрузки на диск и процессор. Готовый файл помечается маркером .ok, и плеер
 * готовится уже по локальному файлу — мгновенно.
 *
 * Работает только для content:// (file:// локален и быстр, http(s) буферизует
 * сам MediaPlayer). Кэш ограничен по количеству файлов, устаревшее стирается.
 */
object TrackPreloader {

    private const val CHUNK_BYTES = 256 * 1024
    private const val CHUNK_PAUSE_MS = 20L
    private const val MAX_CACHE_FILES = 4
    private const val AWAIT_POLL_MS = 80L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, Job>()

    private fun cacheDir(context: Context): File =
        File(context.cacheDir, "preload_tracks").apply { mkdirs() }

    private fun cacheKey(uri: Uri): String =
        Integer.toHexString(uri.toString().hashCode()) + "_" +
            Integer.toHexString((uri.lastPathSegment ?: "").hashCode())

    private fun extensionFor(uri: Uri): String {
        val ext = (uri.lastPathSegment ?: "").substringAfterLast('.', "")
        return if (ext.length in 1..5 && ext.all { it.isLetterOrDigit() }) ext.lowercase() else "audio"
    }

    private fun targetFile(context: Context, uri: Uri): File =
        File(cacheDir(context), "${cacheKey(uri)}.${extensionFor(uri)}")

    private fun markerFor(file: File): File = File(file.parentFile, file.name + ".ok")

    /** Файл готов к использованию (полностью скопирован)? */
    fun readyCachedFile(context: Context, uri: Uri): Uri? {
        if (uri.scheme != "content") return null
        val file = targetFile(context, uri)
        return if (markerFor(file).exists() && file.length() > 0L) Uri.fromFile(file) else null
    }

    /**
     * Готовый файл, а если копирование уже идёт — короткое ожидание его завершения.
     * Таймаут возвращает null: вызывающий играет по исходному URI, как раньше.
     */
    suspend fun readyOrAwait(context: Context, uri: Uri, timeoutMs: Long): Uri? {
        readyCachedFile(context, uri)?.let { return it }
        val started = android.os.SystemClock.uptimeMillis()
        while (android.os.SystemClock.uptimeMillis() - started < timeoutMs) {
            if (uri.scheme != "content") return null
            inFlight[cacheKey(uri)]?.let { job -> if (!job.isActive) break } ?: break
            delay(AWAIT_POLL_MS)
            readyCachedFile(context, uri)?.let { return it }
        }
        return readyCachedFile(context, uri)
    }

    /** Запускает фоновое копирование (если ещё не идёт и не готово). Не блокирует. */
    fun request(context: Context, song: Song) {
        val uri = song.uri
        if (uri.scheme != "content") return
        val key = cacheKey(uri)
        if (inFlight[key]?.isActive == true) return
        val file = targetFile(context, uri)
        if (markerFor(file).exists() && file.length() > 0L) return

        inFlight[key] = scope.launch {
            try {
                copyChunked(context, uri, file)
                markerFor(file).createNewFile()
                cleanup(context, keep = setOf(file.name))
            } catch (_: Exception) {
                try { file.delete() } catch (_: Exception) {}
            } finally {
                inFlight.remove(key)
            }
        }
    }

    private suspend fun copyChunked(context: Context, uri: Uri, file: File) {
        val part = File(file.parentFile, file.name + ".part")
        try {
            val input: InputStream = context.contentResolver.openInputStream(uri) ?: return
            input.use { source ->
                val output: OutputStream = part.outputStream()
                output.use { sink ->
                    val buffer = ByteArray(CHUNK_BYTES)
                    while (true) {
                        val read = source.read(buffer)
                        if (read <= 0) break
                        sink.write(buffer, 0, read)
                        sink.flush()
                        // Пауза между кусками: диск и процессор не работают рывком,
                        // а начало файла к этому моменту уже лежит в кэше.
                        delay(CHUNK_PAUSE_MS)
                    }
                }
            }
            if (!part.renameTo(file)) {
                part.copyTo(file, overwrite = true)
                part.delete()
            }
        } catch (e: Exception) {
            try { part.delete() } catch (_: Exception) {}
            throw e
        }
    }

    /** Держим в кэше последние MAX_CACHE_FILES файлов (текущий + пара соседних). */
    private fun cleanup(context: Context, keep: Set<String>) {
        val dir = cacheDir(context)
        val files = dir.listFiles() ?: return
        val deletable = files.filter { it.name !in keep }
        if (deletable.size <= MAX_CACHE_FILES) return
        deletable.sortedByDescending { it.lastModified() }
            .drop(MAX_CACHE_FILES)
            .forEach { try { it.delete() } catch (_: Exception) {} }
    }
}
