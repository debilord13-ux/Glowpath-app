package com.musicplayer.ui.screens

import android.net.Uri
import com.musicplayer.data.Song
import com.musicplayer.data.UserAlbum
import com.musicplayer.utils.SongCoverUri
import java.util.Locale

internal data class LibraryAlbum(
    val key: String,
    val title: String,
    val artist: String,
    val songs: List<Song>,
    val artwork: List<Uri>,
    val playlist: UserAlbum? = null,
) {
    val duration: Long get() = songs.sumOf { it.duration.coerceAtLeast(0L) }
}

internal data class AlbumLibraryState(
    val albums: List<LibraryAlbum> = emptyList(),
    val playlists: List<LibraryAlbum> = emptyList(),
    val currentSongId: Long? = null,
    val isPlaying: Boolean = false,
)

internal data class AlbumLibraryActions(
    val onOpen: (String?) -> Unit = {},
    val onPlay: (List<Song>, Song?) -> Unit = { _, _ -> },
    val onShuffle: (List<Song>) -> Unit = {},
    val onCreate: () -> Unit = {},
    val onEdit: (UserAlbum) -> Unit = {},
    val onDelete: (UserAlbum) -> Unit = {},
)

private fun String.albumSortKey() = trim().lowercase(Locale.ROOT)
private fun String.knownMetadata() = isNotBlank() && this != "<unknown>"

internal fun buildDeviceAlbums(songs: List<Song>, customArt: Map<Long, Uri>): List<LibraryAlbum> =
    songs.filter { it.album.knownMetadata() && !it.isHidden }.groupBy { song ->
        // The cover URI carries MediaStore's album ID even when no image is embedded.
        // Strip the per-song query; two unrelated albums may have the same title.
        val albumUri = song.albumArtUri?.takeIf(SongCoverUri::isMediaStoreAlbumArt)
            ?.takeIf { (it.lastPathSegment?.toLongOrNull() ?: 0L) > 0L }
        if (albumUri != null) "device:${SongCoverUri.mediaStoreUri(albumUri)}"
        else "device:${song.album.albumSortKey()}|${song.artist.albumSortKey()}"
    }.map { (key, tracks) ->
        val ordered = tracks.sortedWith(compareBy<Song> { it.trackNumber.takeIf { n -> n > 0 } ?: Int.MAX_VALUE }
            .thenBy { it.displayTitle().albumSortKey() })
        val artists = tracks.map { it.displayArtist().trim() }.filter { it.knownMetadata() }.distinct()
        LibraryAlbum(key, tracks.first().album.trim(),
            when (artists.size) { 0 -> "Неизвестный исполнитель"; 1 -> artists.first(); else -> "Разные исполнители" },
            ordered, tracks.mapNotNull { customArt[it.id] ?: it.displayAlbumArt() }.take(1))
    }.sortedBy { it.title.albumSortKey() }

internal fun buildUserPlaylists(
    playlists: List<UserAlbum>, songs: List<Song>, customArt: Map<Long, Uri>,
): List<LibraryAlbum> {
    val songMap = songs.filterNot { it.isHidden }.associateBy { it.id }
    return playlists.map { playlist ->
        val tracks = playlist.songIds.distinct().mapNotNull(songMap::get)
        val customCover = playlist.customCoverUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        val preferred = playlist.coverSongId?.let(songMap::get)
        val covers = if (customCover != null) listOf(customCover) else
            (listOfNotNull(preferred) + tracks).mapNotNull { customArt[it.id] ?: it.displayAlbumArt() }
                .distinct().take(4)
        LibraryAlbum("user:${playlist.id}", playlist.name, "", tracks, covers, playlist)
    }
}

internal enum class AlbumSort(val label: String) {
    Title("По названию"), Artist("По исполнителю"), Tracks("По количеству треков")
}

internal fun List<LibraryAlbum>.filteredAndSorted(query: String, sort: AlbumSort): List<LibraryAlbum> {
    val needle = query.trim()
    val order = when (sort) {
        AlbumSort.Title -> compareBy<LibraryAlbum> { it.title.albumSortKey() }
        AlbumSort.Artist -> compareBy<LibraryAlbum> { it.artist.albumSortKey() }.thenBy { it.title.albumSortKey() }
        AlbumSort.Tracks -> compareByDescending<LibraryAlbum> { it.songs.size }.thenBy { it.title.albumSortKey() }
    }
    return filter { needle.isBlank() || it.title.contains(needle, true) || it.artist.contains(needle, true) }
        .sortedWith(compareByDescending<LibraryAlbum> { it.playlist?.isPinned == true }.then(order))
}

internal fun collectionTrackCount(count: Int): String = "$count " + when {
    count % 100 in 11..14 -> "треков"
    count % 10 == 1 -> "трек"
    count % 10 in 2..4 -> "трека"
    else -> "треков"
}

internal fun collectionDuration(duration: Long): String {
    val minutes = duration.coerceAtLeast(0L) / 60_000L
    return if (minutes >= 60) "${minutes / 60} ч ${minutes % 60} мин" else "$minutes мин"
}
