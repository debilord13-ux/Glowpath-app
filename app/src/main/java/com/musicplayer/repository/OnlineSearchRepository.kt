package com.musicplayer.repository

import android.util.Log
import com.musicplayer.data.OnlineSearchPayload
import com.musicplayer.data.OnlineSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Мульти-источниковый поиск музыки.
 *
 * Источники (опрос параллельно):
 *  • Deezer  — проверенный каталог (артист/название/обложка/длительность) + 30-сек preview;
 *  • iTunes  — каталог Apple Music + 30-сек preview (m4a);
 *  • Hitmos  — прямые MP3-файлы для полного прослушивания и загрузки.
 *
 * Результаты разных источников склеиваются в одну карточку по совпадению
 * «исполнитель + название» (нормализация + мягкое нечёткое сравнение).
 * Одинаковая песня от разных сайтов больше не дублируется в списке —
 * она одна карточка с выбором источника ( song.alternatives ).
 *
 * Карточки без проигрываемого файла отбрасываются. Ранжирование — по
 * релевантности запросу, полные треки (Hitmos) поднимаются выше 30-сек фрагментов.
 */
object OnlineSearchRepository {

    private const val TAG = "OnlineSearchRepository"

    /** Порог нечёткого совпадения «та же песня» (0..1). */
    private const val MATCH_THRESHOLD = 0.82

    /** Кластер одинаковых песен от разных источников (вне функции: нужен extension-ам). */
    private data class Cluster(
        var displayArtist: String = "",
        var displayTitle: String = "",
        val variants: MutableList<OnlineSong> = mutableListOf()
    ) {
        fun clusterKey(): String = normalize("$displayArtist $displayTitle")

        fun addVariant(candidate: Candidate) {
            val variant = candidate.toVariantSong()
            if (variants.any { it.downloadUrl == variant.downloadUrl }) return
            // Первый вариант задаёт отображение карточки, если метаданные пустые.
            if (displayArtist.isBlank() || displayTitle.isBlank()) {
                displayArtist = variant.artist
                displayTitle = variant.title
            }
            variants.add(variant)
        }
    }

    suspend fun search(query: String): OnlineSearchPayload = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext OnlineSearchPayload(emptyList(), emptyList())

        val deezer = mutableListOf<Candidate>()
        val itunes = mutableListOf<Candidate>()
        val hitmos = mutableListOf<OnlineSong>()

        coroutineScope {
            val deezerJob = async {
                runCatching { DeezerApi.search(cleanQuery) }
                    .onFailure { Log.w(TAG, "Deezer search failed", it) }
                    .getOrDefault(emptyList())
            }
            val itunesJob = async {
                runCatching { ITunesApi.search(cleanQuery) }
                    .onFailure { Log.w(TAG, "iTunes search failed", it) }
                    .getOrDefault(emptyList())
            }
            val hitmosJob = async {
                runCatching { HitmosRepository.search(cleanQuery).songs }
                    .onFailure { Log.w(TAG, "Hitmos search failed", it) }
                    .getOrDefault(emptyList())
            }

            deezer += deezerJob.await().map { it.toCandidate() }
            itunes += itunesJob.await().map { it.toCandidate() }
            hitmos += hitmosJob.await()
        }

        val merged = mergeResults(cleanQuery, deezer, itunes, hitmos)
        OnlineSearchPayload(songs = merged, albums = emptyList())
    }

    // ── Склейка и ранжирование ───────────────────────────────────────────────

    private data class Candidate(
        val artist: String,
        val title: String,
        val durationSeconds: Int,
        val coverUrl: String,
        val sourceLabel: String,
        val downloadUrl: String
    )

    private fun mergeResults(
        query: String,
        deezer: List<Candidate>,
        itunes: List<Candidate>,
        hitmos: List<OnlineSong>
    ): List<OnlineSong> {
        val clusters = mutableListOf<Cluster>()

        fun addToCluster(candidate: Candidate) {
            val key = normalize("${candidate.artist} ${candidate.title}")
            if (key.isBlank()) return
            val existing = clusters.firstOrNull { isSameTrack(key, it.clusterKey()) }
            if (existing != null) {
                existing.addVariant(candidate)
            } else {
                clusters.add(
                    Cluster(
                        displayArtist = candidate.artist,
                        displayTitle = candidate.title
                    ).also { it.addVariant(candidate) }
                )
            }
        }

        // Порядок добавления = приоритет метаданных: Deezer (каталог) → iTunes → Hitmos.
        deezer.forEach(::addToCluster)
        itunes.forEach(::addToCluster)

        // Hitmos имеет собственные (иногда «грязные») названия, поэтому добавляем
        // его последним: метаданные уже заняты из каталогов, а варианты файлов
        // приклеиваются к уже существующим карточкам по совпадению.
        hitmos.forEach { song ->
            addToCluster(
                Candidate(
                    artist = song.artist,
                    title = song.title,
                    durationSeconds = parseDurationSeconds(song.duration),
                    coverUrl = song.coverUrl,
                    sourceLabel = SOURCE_HITMOS,
                    downloadUrl = song.downloadUrl
                )
            )
        }

        // Собираем карточки: только с проигрываемым файлом; полные треки (Hitmos) — первыми.
        val result = clusters.mapNotNull { cluster ->
            if (cluster.variants.isEmpty()) return@mapNotNull null

            val playable = cluster.variants
                .filter { it.downloadUrl.isNotBlank() }
                .distinctBy { it.downloadUrl }
            val ordered = playable.sortedBy { it.sourceLabel != SOURCE_HITMOS }
            if (ordered.isEmpty()) return@mapNotNull null

            val primary = ordered.first()
            primary.copy(
                alternatives = ordered.drop(1).map { it.copy(alternatives = emptyList()) }
            )
        }

        // Ранжирование: релевантность запросу, полные треки выше фрагментов.
        return result
            .sortedWith(
                compareByDescending<OnlineSong> { song ->
                    relevance(normalize("${song.artist} ${song.title}"), query)
                }.thenByDescending { it.sourceLabel == SOURCE_HITMOS }
            )
            .take(20)
    }

    private fun Candidate.toVariantSong(): OnlineSong = OnlineSong(
        title = title,
        artist = artist,
        duration = formatDuration(durationSeconds),
        downloadUrl = downloadUrl,
        coverUrl = coverUrl,
        sourceLabel = sourceLabel
    )

    private const val SOURCE_DEEZER = "Deezer · фрагмент"
    private const val SOURCE_ITUNES = "iTunes · фрагмент"
    private const val SOURCE_HITMOS = "Hitmos"

    private fun DeezerApiResult.toCandidate(): Candidate = Candidate(
        artist = artist,
        title = title,
        durationSeconds = durationSeconds,
        coverUrl = coverUrl,
        sourceLabel = SOURCE_DEEZER,
        downloadUrl = previewUrl
    )

    private fun ITunesApiResult.toCandidate(): Candidate = Candidate(
        artist = artist,
        title = title,
        durationSeconds = durationSeconds,
        coverUrl = coverUrl,
        sourceLabel = SOURCE_ITUNES,
        downloadUrl = previewUrl
    )

    private data class DeezerApiResult(
        val artist: String,
        val title: String,
        val durationSeconds: Int,
        val coverUrl: String,
        val previewUrl: String
    )

    private data class ITunesApiResult(
        val artist: String,
        val title: String,
        val durationSeconds: Int,
        val coverUrl: String,
        val previewUrl: String
    )

    // ── Сравнение и ранжирование ─────────────────────────────────────────────

    /** Та же ли это песня: точное совпадение ключа, вхождение или нечёткое сходство. */
    private fun isSameTrack(keyA: String, keyB: String): Boolean {
        if (keyA == keyB) return true
        if (keyA.isBlank() || keyB.isBlank()) return false
        if (keyA.contains(keyB) || keyB.contains(keyA)) {
            // Вхождение короткого ключа в длинный — только если короткий достаточно длинный,
            // чтобы не склеить "hallelujah" с "hallelujah cover version remix".
            val shorter = minOf(keyA.length, keyB.length)
            if (shorter >= 12) return true
        }
        return similarity(keyA, keyB) >= MATCH_THRESHOLD
    }

    /** Релевантность ключа запросу (0..1): доля совпадения слов + общее сходство. */
    private fun relevance(key: String, query: String): Double {
        if (key.isBlank() || query.isBlank()) return 0.0
        val q = normalize(query)
        val queryWords = q.split(' ').filter { it.isNotBlank() }.toSet()
        val keyWords = key.split(' ').filter { it.isNotBlank() }.toSet()
        if (queryWords.isEmpty() || keyWords.isEmpty()) return 0.0
        val overlap = queryWords.intersect(keyWords).size.toDouble() / queryWords.size
        return (overlap * 0.75 + similarity(key, q) * 0.25).coerceIn(0.0, 1.0)
    }

    /** Нормализованное расстояние Левенштейна (1 — идентичны). */
    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val s = a.toCharArray()
        val t = b.toCharArray()
        val prev = DoubleArray(t.size + 1) { it.toDouble() }
        val curr = DoubleArray(t.size + 1)
        for (i in 1..s.size) {
            curr[0] = i.toDouble()
            for (j in 1..t.size) {
                val cost = if (s[i - 1] == t[j - 1]) 0.0 else 1.0
                curr[j] = minOf(curr[j - 1] + 1.0, prev[j] + 1.0, prev[j - 1] + cost)
            }
            System.arraycopy(curr, 0, prev, 0, curr.size)
        }
        val distance = prev[t.size]
        return 1.0 - distance / maxOf(s.size, t.size)
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{Nd}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun formatDuration(seconds: Int): String {
        if (seconds <= 0) return ""
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    private fun parseDurationSeconds(raw: String): Int {
        val parts = raw.trim().split(":")
        return when (parts.size) {
            2 -> (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
            3 -> (parts[0].toIntOrNull() ?: 0) * 3600 + (parts[1].toIntOrNull() ?: 0) * 60 + (parts[2].toIntOrNull() ?: 0)
            else -> 0
        }
    }

    // ── Deezer API ───────────────────────────────────────────────────────────

    private object DeezerApi {
        private const val ENDPOINT = "https://api.deezer.com/search"
        private const val LIMIT = 10

        fun search(query: String): List<DeezerApiResult> {
            val url = "$ENDPOINT?q=${encode(query)}&limit=$LIMIT"
            val root = JSONObject(
                Jsoup.connect(url)
                    .ignoreContentType(true)
                    .followRedirects(true)
                    .userAgent("MusicPlayer/1.0 (Android)")
                    .header("Accept", "application/json")
                    .timeout(8_000)
                    .execute()
                    .body()
            )
            if (root.optInt("error", 0) != 0) return emptyList()

            val data = root.optJSONArray("data") ?: return emptyList()
            return buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val title = item.optString("title").trim()
                    val artist = item.optJSONObject("artist")?.optString("name")?.trim().orEmpty()
                    if (title.isBlank() || artist.isBlank()) continue

                    val album = item.optJSONObject("album")
                    add(
                        DeezerApiResult(
                            artist = artist,
                            title = title,
                            durationSeconds = item.optInt("duration", 0),
                            coverUrl = album?.optString("cover_medium")?.trim().orEmpty(),
                            previewUrl = item.optString("preview").trim()
                        )
                    )
                }
            }
        }

        private fun encode(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    // ── iTunes Search API ────────────────────────────────────────────────────

    private object ITunesApi {
        private const val ENDPOINT = "https://itunes.apple.com/search"
        private const val LIMIT = 10

        fun search(query: String): List<ITunesApiResult> {
            val url = "$ENDPOINT?term=${encode(query)}&media=music&entity=song&limit=$LIMIT"
            val body = Jsoup.connect(url)
                .ignoreContentType(true)
                .followRedirects(true)
                .userAgent("MusicPlayer/1.0 (Android)")
                .header("Accept", "application/json")
                .timeout(8_000)
                .execute()
                .body()
            val root = JSONObject(body)
            val results = root.optJSONArray("results") ?: return emptyList()
            return buildList {
                for (index in 0 until results.length()) {
                    val item = results.optJSONObject(index) ?: continue
                    val title = item.optString("trackName").trim()
                    val artist = item.optString("artistName").trim()
                    if (title.isBlank() || artist.isBlank()) continue
                    add(
                        ITunesApiResult(
                            artist = artist,
                            title = title,
                            durationSeconds = item.optLong("trackTimeMillis", 0L).let { (it / 1000L).toInt() },
                            coverUrl = item.optString("artworkUrl100").trim()
                                .replace("100x100", "300x300"),
                            previewUrl = item.optString("previewUrl").trim()
                        )
                    )
                }
            }
        }

        private fun encode(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}
