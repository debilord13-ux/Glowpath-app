package com.musicplayer.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.musicplayer.data.Song
import com.musicplayer.data.UserAlbum
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

class ExteraGramBridgeProvider : ContentProvider() {

    companion object {
        private const val STATUS = 1
        private const val SEARCH = 2
        private const val COVER_CURRENT = 3
        private const val PLAYLISTS = 4
        private const val PLAYLIST_SONGS = 5
        private const val PLAYLIST_COVER = 6
        private const val PLAYLIST_DETAIL = 7

        private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(ExteraGramBridgeContract.AUTHORITY, "status", STATUS)
            addURI(ExteraGramBridgeContract.AUTHORITY, "search", SEARCH)
            addURI(ExteraGramBridgeContract.AUTHORITY, "cover/current", COVER_CURRENT)
            addURI(ExteraGramBridgeContract.AUTHORITY, "playlists", PLAYLISTS)
            addURI(ExteraGramBridgeContract.AUTHORITY, "playlists/*", PLAYLIST_DETAIL)
            addURI(ExteraGramBridgeContract.AUTHORITY, "playlists/*/songs", PLAYLIST_SONGS)
            addURI(ExteraGramBridgeContract.AUTHORITY, "playlists/*/cover", PLAYLIST_COVER)
        }

        private val statusColumns = arrayOf(
            "title",
            "artist",
            "album",
            "is_playing",
            "has_song",
            "duration_ms",
            "position_ms",
            "effective_position_ms",
            "current_uri",
            "library_size",
            "last_command_label",
            "last_query",
            "last_import_uri",
            "bridge_queue_size",
            "last_batch_summary",
            "cover_uri",
            "updated_at",
        )

        private val searchColumns = arrayOf(
            "song_id",
            "title",
            "artist",
            "album",
            "duration_ms",
            "uri",
            "score",
        )

        private val playlistsColumns = arrayOf(
            "playlist_id",
            "name",
            "description",
            "song_count",
            "total_duration_ms",
            "is_pinned",
            "created_at",
            "updated_at",
            "cover_uri",
        )

        private val playlistSongsColumns = arrayOf(
            "track_index",
            "song_id",
            "title",
            "artist",
            "album",
            "duration_ms",
            "uri",
            "cover_uri",
        )

    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val context = context ?: return null
        return when (matcher.match(uri)) {
            STATUS -> {
                val snapshot = ExteraGramBridgeStateStore.loadSnapshot(context)
                val coverUri = resolveCurrentArtworkUri(context, snapshot)
                MatrixCursor(statusColumns).apply {
                    addRow(
                        arrayOf(
                            snapshot.title,
                            snapshot.artist,
                            snapshot.album,
                            if (snapshot.isPlaying) 1 else 0,
                            if (snapshot.hasSong) 1 else 0,
                            snapshot.durationMs,
                            snapshot.positionMs,
                            snapshot.effectivePositionMs(System.currentTimeMillis()),
                            snapshot.currentUri,
                            snapshot.librarySize,
                            snapshot.lastCommandLabel,
                            snapshot.lastQuery,
                            snapshot.lastImportUri,
                            snapshot.bridgeQueueSize,
                            snapshot.lastBatchSummary,
                            if (coverUri != null) ExteraGramBridgeContract.coverUri().toString() else "",
                            snapshot.updatedAt,
                        )
                    )
                }
            }

            SEARCH -> {
                val query = uri.getQueryParameter("q").orEmpty().trim()
                MatrixCursor(searchColumns).apply {
                    if (query.isNotBlank()) {
                        findSongs(context, query).forEach { (song, score) ->
                            addRow(
                                arrayOf(
                                    song.id,
                                    song.title,
                                    song.artist,
                                    song.album,
                                    song.duration,
                                    song.uri.toString(),
                                    score,
                                )
                            )
                        }
                    }
                }
            }

            PLAYLISTS -> {
                val songsById = ExteraGramBridgeLibrary.loadSongs(context).associateBy { it.id }
                MatrixCursor(playlistsColumns).apply {
                    ExteraGramBridgeLibrary.loadPlaylists(context)
                        .sortedWith(
                            compareByDescending<UserAlbum> { it.isPinned }
                                .thenBy { it.name.lowercase() }
                        )
                        .forEach { playlist ->
                            val tracks = playlist.songIds.mapNotNull { songsById[it] }
                            val coverUri = ExteraGramBridgeLibrary
                                .resolvePlaylistCoverUri(context, playlist, tracks)
                            addRow(
                                arrayOf(
                                    playlist.id,
                                    playlist.name,
                                    playlist.description,
                                    tracks.size,
                                    tracks.sumOf { it.duration },
                                    if (playlist.isPinned) 1 else 0,
                                    playlist.createdAt,
                                    playlist.updatedAt,
                                    coverUri?.toString().orEmpty(),
                                )
                            )
                        }
                }
            }

            PLAYLIST_SONGS, PLAYLIST_DETAIL -> {
                val playlistId = extractPlaylistId(uri)
                val resolved = playlistId?.let { ExteraGramBridgeLibrary.resolvePlaylist(context, it) }
                when {
                    resolved == null -> MatrixCursor(playlistsColumns)
                    matcher.match(uri) == PLAYLIST_SONGS -> {
                        val tracks = resolved.second
                        val prefs = com.musicplayer.data.PreferencesManager(context)
                        MatrixCursor(playlistSongsColumns).apply {
                            tracks.forEachIndexed { index, song ->
                                val cover = prefs.loadCustomArt(song.id) ?: song.albumArtUri
                                addRow(
                                    arrayOf(
                                        index,
                                        song.id,
                                        song.title,
                                        song.artist,
                                        song.album,
                                        song.duration,
                                        song.uri.toString(),
                                        cover?.toString().orEmpty(),
                                    )
                                )
                            }
                        }
                    }
                    else -> {
                        // Метаданные конкретного плейлиста — одна строка с колонками списка плейлистов
                        val (playlist, tracks) = resolved
                        val coverUri = ExteraGramBridgeLibrary
                            .resolvePlaylistCoverUri(context, playlist, tracks)
                        MatrixCursor(playlistsColumns).apply {
                            addRow(
                                arrayOf(
                                    playlist.id,
                                    playlist.name,
                                    playlist.description,
                                    tracks.size,
                                    tracks.sumOf { it.duration },
                                    if (playlist.isPinned) 1 else 0,
                                    playlist.createdAt,
                                    playlist.updatedAt,
                                    coverUri?.toString().orEmpty(),
                                )
                            )
                        }
                    }
                }
            }

            else -> null
        }
    }

    override fun getType(uri: Uri): String? {
        return when (matcher.match(uri)) {
            STATUS -> "vnd.android.cursor.item/vnd.${ExteraGramBridgeContract.AUTHORITY}.status"
            SEARCH -> "vnd.android.cursor.dir/vnd.${ExteraGramBridgeContract.AUTHORITY}.search"
            PLAYLISTS, PLAYLIST_DETAIL -> "vnd.android.cursor.dir/vnd.${ExteraGramBridgeContract.AUTHORITY}.playlist"
            PLAYLIST_SONGS -> "vnd.android.cursor.dir/vnd.${ExteraGramBridgeContract.AUTHORITY}.playlist_song"
            COVER_CURRENT, PLAYLIST_COVER -> "image/*"
            else -> null
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: return null
        return when (matcher.match(uri)) {
            COVER_CURRENT -> {
                val snapshot = ExteraGramBridgeStateStore.loadSnapshot(context)
                val artworkUri = resolveCurrentArtworkUri(context, snapshot)
                    ?: throw FileNotFoundException("No current artwork available")
                val artworkFile = exposeArtworkFile(context, artworkUri, "current_cover.bin")
                    ?: throw FileNotFoundException("Failed to expose current artwork")
                ParcelFileDescriptor.open(artworkFile, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            PLAYLIST_COVER -> {
                val playlistId = extractPlaylistId(uri)
                    ?: throw FileNotFoundException("No playlist id in uri")
                val resolved = ExteraGramBridgeLibrary.resolvePlaylist(context, playlistId)
                    ?: throw FileNotFoundException("Playlist not found")
                val (playlist, tracks) = resolved
                val artworkUri = ExteraGramBridgeLibrary.resolvePlaylistCoverUri(context, playlist, tracks)
                    ?: throw FileNotFoundException("Playlist has no cover")
                val artworkFile = exposeArtworkFile(context, artworkUri, "playlist_${playlistId.hashCode()}_cover.bin")
                    ?: throw FileNotFoundException("Failed to expose playlist artwork")
                ParcelFileDescriptor.open(artworkFile, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            else -> null
        }
    }

    private fun extractPlaylistId(uri: Uri): String? {
        val segments = uri.pathSegments
        val index = segments.indexOf("playlists")
        if (index >= 0 && index + 1 < segments.size) {
            val next = segments[index + 1]
            if (next != "songs" && next != "cover") return next
        }
        return uri.getQueryParameter("id")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun findSongs(context: android.content.Context, query: String): List<Pair<Song, Int>> {
        val allSongs = ExteraGramBridgeLibrary.loadSongs(context)

        if (allSongs.isEmpty()) return emptyList()
        val needle = query.lowercase()
        return allSongs.mapNotNull { song ->
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
            .sortedWith(compareByDescending<Pair<Song, Int>> { it.second }.thenBy { it.first.title.lowercase() })
            .take(12)
    }

    private fun resolveCurrentSong(context: android.content.Context, snapshot: ExteraGramBridgeSnapshot): Song? {
        val allSongs = ExteraGramBridgeLibrary.loadSongs(context)
        if (allSongs.isEmpty()) return null
        return allSongs.firstOrNull { it.uri.toString() == snapshot.currentUri }
            ?: allSongs.firstOrNull {
                it.title.equals(snapshot.title, ignoreCase = true) &&
                    it.artist.equals(snapshot.artist, ignoreCase = true)
            }
    }

    private fun resolveCurrentArtworkUri(context: android.content.Context, snapshot: ExteraGramBridgeSnapshot): Uri? {
        val song = resolveCurrentSong(context, snapshot) ?: return null
        return com.musicplayer.data.PreferencesManager(context).loadCustomArt(song.id) ?: song.albumArtUri
    }

    private fun exposeArtworkFile(context: android.content.Context, artworkUri: Uri, fileName: String): File? {
        return runCatching {
            val outDir = File(context.cacheDir, "bridge_cover").apply { mkdirs() }
            val outFile = File(outDir, fileName)
            context.contentResolver.openInputStream(artworkUri)?.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            outFile
        }.getOrNull()
    }
}
