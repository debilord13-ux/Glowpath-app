package com.musicplayer.server

import android.content.Context
import android.util.Log
import com.musicplayer.bridge.ExteraGramBridgeContract
import com.musicplayer.bridge.ExteraGramBridgeCommand
import com.musicplayer.bridge.ExteraGramBridgeLibrary
import com.musicplayer.bridge.ExteraGramBridgeRuntime
import com.musicplayer.bridge.ExteraGramBridgeStateStore
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Локальный HTTP-сервер для отдачи данных плеера (ExteraGram / Hikka / внешние клиенты).
 * Порт: 8765
 *
 * GET  /now                    → JSON текущего трека (расширенный)
 * GET  /status                 → алиас /now
 * GET  /cover                  → обложка текущего трека
 * GET  /playlists              → список всех плейлистов
 * GET  /playlists/{id}         → плейлист с полным списком песен
 * GET  /playlists/{id}/cover   → обложка плейлиста
 * GET  /playlist?id={id}       → алиас /playlists/{id}
 * GET  /search?q={query}       → поиск по библиотеке
 * GET  /info                   → информация о сервере
 * POST /control/play_pause
 * POST /control/next
 * POST /control/prev
 * POST /control/stop
 */
class NowPlayingHttpServer(
    private val context: Context,
    port: Int = DEFAULT_PORT,
) : NanoHTTPD(port) {

    companion object {
        const val DEFAULT_PORT = 8765
        private const val TAG = "NowPlayingHttpServer"
        const val API_VERSION = "1.2"

        @Volatile
        private var instance: NowPlayingHttpServer? = null

        fun start(context: Context, port: Int = DEFAULT_PORT): NowPlayingHttpServer {
            synchronized(this) {
                instance?.stop()
                val server = NowPlayingHttpServer(context.applicationContext, port)
                try {
                    server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                    Log.i(TAG, "NowPlaying HTTP server started on port $port")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start HTTP server on port $port", e)
                }
                instance = server
                return server
            }
        }

        fun stop() {
            synchronized(this) {
                instance?.stop()
                instance = null
            }
        }

        fun isRunning(): Boolean = instance?.isAlive == true
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri?.trimEnd('/') ?: "/"
        val method = session.method

        // CORS preflight
        if (method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "").apply {
                addHeader("Access-Control-Allow-Origin", "*")
                addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                addHeader("Access-Control-Allow-Headers", "Content-Type")
            }
        }

        return when {
            uri == "" || uri == "/" -> serveHelp()
            uri == "/now" || uri == "/status" -> serveNow()
            uri == "/cover" || uri == "/cover/current" -> serveCover()
            uri == "/info" -> serveInfo()
            uri == "/playlists" -> servePlaylists()
            uri == "/playlist" -> servePlaylistById(session.parameters["id"].orEmpty().firstOrNull().orEmpty())
            uri == "/search" -> serveSearch(session.parameters["q"].orEmpty().firstOrNull().orEmpty())
            uri.startsWith("/playlists/") -> {
                val rest = uri.removePrefix("/playlists/")
                when {
                    rest.endsWith("/cover") -> servePlaylistCover(
                        java.net.URLDecoder.decode(rest.removeSuffix("/cover"), "UTF-8")
                    )
                    rest.contains('/') -> servePlaylistCover(
                        java.net.URLDecoder.decode(rest.substringBefore('/'), "UTF-8")
                    )
                    else -> servePlaylistById(java.net.URLDecoder.decode(rest, "UTF-8"))
                }
            }
            uri == "/playlist_cover" -> servePlaylistCover(session.parameters["id"].orEmpty().firstOrNull().orEmpty())

            // Управление
            method == Method.POST && uri == "/control/play_pause" -> handleControl(ExteraGramBridgeContract.TYPE_PLAY_PAUSE)
            method == Method.POST && uri == "/control/next" -> handleControl(ExteraGramBridgeContract.TYPE_NEXT)
            method == Method.POST && uri == "/control/prev" -> handleControl(ExteraGramBridgeContract.TYPE_PREV)
            method == Method.POST && uri == "/control/stop" -> handleControl(ExteraGramBridgeContract.TYPE_STOP)
            uri == "/control/play_playlist" -> {
                // Принимаем и id, и имя плейлиста: если параметр не совпал ни с одним id —
                // считаем его именем и берём первое совпадение (точное, потом частичное)
                var target = session.parameters["id"].orEmpty().firstOrNull().orEmpty()
                    .ifBlank { session.parameters["name"].orEmpty().firstOrNull().orEmpty() }
                val playlists = ExteraGramBridgeLibrary.loadPlaylists(context)
                if (target.isNotBlank() && playlists.none { it.id == target }) {
                    val needle = target.lowercase().trim()
                    target = playlists.firstOrNull { it.name.lowercase() == needle }?.id
                        ?: playlists.firstOrNull { it.name.lowercase().contains(needle) }?.id
                        ?: target
                }
                handleControl(ExteraGramBridgeContract.TYPE_PLAY_PLAYLIST, payload = target)
            }

            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "Not found. See GET / for available endpoints"
            ).withCors()
        }
    }

    private fun serveHelp(): Response {
        val text = """
            GlowPath Now Playing API v$API_VERSION
            ------------------------------------
            GET  /now                  - current track as JSON (extended)
            GET  /status               - alias of /now
            GET  /cover                - current cover image (JPEG)
            GET  /playlists            - all user playlists as JSON
            GET  /playlists/{id}       - one playlist with its songs
            GET  /playlists/{id}/cover - playlist cover image
            GET  /playlist?id={id}     - alias of /playlists/{id}
            GET  /playlist_cover?id=   - alias of /playlists/{id}/cover
            GET  /search?q={query}     - library search (top 12)
            GET  /info                 - server info

            POST /control/play_pause
            POST /control/next
            POST /control/prev
            POST /control/stop
            POST /control/play_playlist?id={id}  - start a playlist (GET also allowed)
            POST /control/play_playlist?name=... - by name (first match)
            POST /control/...  also accepts GET for play_playlist
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, text).withCors()
    }

    private fun serveInfo(): Response {
        return try {
            val json = JSONObject().apply {
                put("api_version", API_VERSION)
                put("server", "GlowPath NowPlayingHttpServer")
                put("port", listeningPort)
                put("running", isAlive)
                put("timestamp", System.currentTimeMillis())
            }
            jsonResponse(json)
        } catch (e: Exception) {
            Log.e(TAG, "serveInfo error", e)
            errorResponse("Error: ${e.message}")
        }
    }

    private fun serveNow(): Response {
        return try {
            val snapshot = ExteraGramBridgeStateStore.loadSnapshot(context)
            val now = System.currentTimeMillis()
            val effectivePos = snapshot.effectivePositionMs(now)
            val duration = snapshot.durationMs.coerceAtLeast(0L)
            val progress = if (duration > 0) {
                (effectivePos.toDouble() / duration).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val remaining = (duration - effectivePos).coerceAtLeast(0L)

            val json = JSONObject().apply {
                put("title", snapshot.title)
                put("artist", snapshot.artist)
                put("album", snapshot.album)
                put("is_playing", snapshot.isPlaying)
                put("has_song", snapshot.hasSong)

                // Позиция
                put("duration_ms", duration)
                put("position_ms", effectivePos)                    // эффективная
                put("position_raw_ms", snapshot.positionMs)         // сырая из снапшота
                put("remaining_ms", remaining)
                put("progress", progress)                           // 0.0 .. 1.0

                // Красивые строки для карточек
                put("position_str", formatMs(effectivePos))
                put("duration_str", formatMs(duration))
                put("remaining_str", formatMs(remaining))

                put("current_uri", snapshot.currentUri)
                put("library_size", snapshot.librarySize)
                put("bridge_queue_size", snapshot.bridgeQueueSize)
                put("last_command", snapshot.lastCommandLabel)
                put("last_query", snapshot.lastQuery)
                put("last_import_uri", snapshot.lastImportUri)
                put("last_batch_summary", snapshot.lastBatchSummary)

                put("updated_at", snapshot.updatedAt)
                put("server_time", now)
                put("cover_url", "http://127.0.0.1:$listeningPort/cover")
            }
            jsonResponse(json)
        } catch (e: Exception) {
            Log.e(TAG, "serveNow error", e)
            errorResponse("Error: ${e.message}")
        }
    }

    private fun serveCover(): Response {
        return try {
            val coverUri = android.net.Uri.parse("content://com.musicplayer.bridge/cover/current")
            val input = context.contentResolver.openInputStream(coverUri)
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No cover").withCors()

            val bytes = input.use { it.readBytes() }
            if (bytes.isEmpty()) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Empty cover").withCors()
            }

            newFixedLengthResponse(
                Response.Status.OK,
                "image/jpeg",
                ByteArrayInputStream(bytes),
                bytes.size.toLong()
            ).apply {
                addHeader("Access-Control-Allow-Origin", "*")
                addHeader("Cache-Control", "no-cache")
            }
        } catch (e: Exception) {
            Log.e(TAG, "serveCover error", e)
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Cover not available").withCors()
        }
    }

    // ----------------- playlists API (v1.2) -----------------

    /** GET /playlists — список всех пользовательских плейлистов. */
    private fun servePlaylists(): Response {
        return try {
            val playlists = ExteraGramBridgeLibrary.loadPlaylists(context)
            val songsById = ExteraGramBridgeLibrary.loadSongs(context).associateBy { it.id }
            val array = org.json.JSONArray()
            playlists
                .sortedWith(
                    compareByDescending<com.musicplayer.data.UserAlbum> { it.isPinned }
                        .thenBy { it.name.lowercase() }
                )
                .forEach { playlist ->
                    val tracks = playlist.songIds.mapNotNull { songsById[it] }
                    array.put(playlistSummaryJson(playlist, tracks.size, tracks.sumOf { it.duration }))
                }
            val json = JSONObject().apply {
                put("api_version", API_VERSION)
                put("count", array.length())
                put("playlists", array)
                put("server_time", System.currentTimeMillis())
            }
            jsonResponse(json)
        } catch (e: Exception) {
            Log.e(TAG, "servePlaylists error", e)
            errorResponse("Error: ${e.message}")
        }
    }

    /** GET /playlists/{id} (алиас /playlist?id=) — плейлист с полным списком песен. */
    private fun servePlaylistById(playlistId: String): Response {
        if (playlistId.isBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                MIME_PLAINTEXT,
                "Missing playlist id. Use /playlists to list all, /playlists/{id} for one"
            ).withCors()
        }
        return try {
            val resolved = ExteraGramBridgeLibrary.resolvePlaylist(context, playlistId)
                ?: return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "Playlist not found: $playlistId"
                ).withCors()
            val (playlist, tracks) = resolved
            val currentUri = ExteraGramBridgeStateStore.loadSnapshot(context).currentUri
            val songsArray = org.json.JSONArray()
            tracks.forEachIndexed { index, song ->
                songsArray.put(songJson(song, index, song.uri.toString() == currentUri))
            }
            val json = playlistSummaryJson(playlist, tracks.size, tracks.sumOf { it.duration })
                .put("songs", songsArray)
            jsonResponse(json)
        } catch (e: Exception) {
            Log.e(TAG, "servePlaylistById error", e)
            errorResponse("Error: ${e.message}")
        }
    }

    /** GET /playlists/{id}/cover — обложка плейлиста (кастомная или обложка главного трека). */
    private fun servePlaylistCover(playlistId: String): Response {
        if (playlistId.isBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                MIME_PLAINTEXT,
                "Missing playlist id"
            ).withCors()
        }
        return try {
            val resolved = ExteraGramBridgeLibrary.resolvePlaylist(context, playlistId)
                ?: return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "Playlist not found: $playlistId"
                ).withCors()
            val (playlist, tracks) = resolved
            val artworkUri = ExteraGramBridgeLibrary.resolvePlaylistCoverUri(context, playlist, tracks)
                ?: return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "Playlist has no cover"
                ).withCors()
            val bytes = context.contentResolver.openInputStream(artworkUri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "Cover not available"
                ).withCors()
            }
            newFixedLengthResponse(
                Response.Status.OK,
                "image/jpeg",
                ByteArrayInputStream(bytes),
                bytes.size.toLong()
            ).apply {
                addHeader("Access-Control-Allow-Origin", "*")
                addHeader("Cache-Control", "no-cache")
            }
        } catch (e: Exception) {
            Log.e(TAG, "servePlaylistCover error", e)
            newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "Cover not available"
            ).withCors()
        }
    }

    /** GET /search?q= — поиск по библиотеке (топ-12, тот же скоринг, что и в bridge-провайдере). */
    private fun serveSearch(query: String): Response {
        return try {
            val needle = query.trim()
            if (needle.isEmpty()) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    MIME_PLAINTEXT,
                    "Missing query. Use /search?q=..."
                ).withCors()
            }
            val currentUri = ExteraGramBridgeStateStore.loadSnapshot(context).currentUri
            val array = org.json.JSONArray()
            scoreSongs(ExteraGramBridgeLibrary.loadSongs(context), needle)
                .forEach { (song, score) ->
                    array.put(
                        songJson(song, -1, song.uri.toString() == currentUri)
                            .put("score", score)
                    )
                }
            val json = JSONObject().apply {
                put("api_version", API_VERSION)
                put("query", needle)
                put("count", array.length())
                put("results", array)
            }
            jsonResponse(json)
        } catch (e: Exception) {
            Log.e(TAG, "serveSearch error", e)
            errorResponse("Error: ${e.message}")
        }
    }

    private fun scoreSongs(songs: List<com.musicplayer.data.Song>, query: String): List<Pair<com.musicplayer.data.Song, Int>> {
        val needle = query.lowercase()
        if (needle.isEmpty()) return emptyList()
        return songs.mapNotNull { song ->
            val title = song.title.lowercase()
            val artist = song.artist.lowercase()
            val album = song.album.lowercase()
            val full = "$title $artist $album"
            val score = when {
                title == needle -> 320
                "$artist - $title" == needle -> 300
                title.startsWith(needle) -> 260
                artist.startsWith(needle) -> 220
                title.contains(needle) -> 180
                artist.contains(needle) -> 160
                album.contains(needle) -> 120
                full.contains(needle) -> 90
                else -> 0
            }
            if (score > 0) song to score else null
        }
            .sortedWith(compareByDescending<Pair<com.musicplayer.data.Song, Int>> { it.second }.thenBy { it.first.title.lowercase() })
            .take(12)
    }

    private fun playlistSummaryJson(
        playlist: com.musicplayer.data.UserAlbum,
        songCount: Int,
        totalDurationMs: Long,
    ): JSONObject {
        val encodedId = java.net.URLEncoder.encode(playlist.id, "UTF-8")
        return JSONObject().apply {
            put("id", playlist.id)
            put("name", playlist.name)
            put("description", playlist.description)
            put("song_count", songCount)
            put("total_duration_ms", totalDurationMs)
            put("total_duration_str", formatMs(totalDurationMs))
            put("is_pinned", playlist.isPinned)
            put("created_at", playlist.createdAt)
            put("updated_at", playlist.updatedAt)
            put("cover_url", "http://127.0.0.1:$listeningPort/playlists/$encodedId/cover")
            put("songs_url", "http://127.0.0.1:$listeningPort/playlists/$encodedId")
        }
    }

    private fun songJson(
        song: com.musicplayer.data.Song,
        index: Int,
        isCurrent: Boolean,
    ): JSONObject {
        return JSONObject().apply {
            if (index >= 0) put("index", index)
            put("song_id", song.id)
            put("title", song.displayTitle())
            put("artist", song.displayArtist())
            put("album", song.album)
            put("duration_ms", song.duration)
            put("duration_str", formatMs(song.duration))
            put("uri", song.uri.toString())
            put("is_current", isCurrent)
        }
    }

    private fun handleControl(type: String, payload: String = ""): Response {
        return try {
            val command = ExteraGramBridgeCommand(
                type = type,
                payload = payload,
                source = "http_api"
            )
            ExteraGramBridgeRuntime.dispatch(context, command)

            val json = JSONObject().apply {
                put("ok", true)
                put("command", type)
                if (payload.isNotBlank()) put("payload", payload)
                put("message", "Command dispatched")
            }
            jsonResponse(json)
        } catch (e: Exception) {
            Log.e(TAG, "handleControl error ($type)", e)
            errorResponse("Failed to dispatch command: ${e.message}")
        }
    }

    // ----------------- helpers -----------------

    private fun formatMs(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    private fun jsonResponse(json: JSONObject): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            json.toString()
        ).withCors()
    }

    private fun errorResponse(message: String): Response {
        return newFixedLengthResponse(
            Response.Status.INTERNAL_ERROR,
            MIME_PLAINTEXT,
            message
        ).withCors()
    }

    private fun Response.withCors(): Response {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        addHeader("Access-Control-Allow-Headers", "Content-Type")
        return this
    }
}
