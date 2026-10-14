package com.musicplayer.ui.screens

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.musicplayer.data.PreferencesManager
import com.musicplayer.data.Song
import com.musicplayer.data.UserAlbum
import com.musicplayer.utils.SongsFileStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AlbumLibraryModelsTest {
    private fun song(id: Long, albumId: Long = 42, track: Int = 0, artist: String = "Исполнитель") = Song(
        id, "Трек $id", artist, "Одинаковое название", 180_000L,
        Uri.parse("content://media/external/audio/media/$id"),
        Uri.parse("content://media/external/audio/albumart/$albumId?song=$id"), trackNumber = track,
    )

    @Test fun albumIdentityUsesMediaStoreInsteadOfTitleOrSongCoverQuery() {
        val albums = buildDeviceAlbums(listOf(song(1), song(2, artist = "Приглашённый артист"), song(3, albumId = 99)), emptyMap())
        assertEquals(2, albums.size)
        val compilation = albums.single { it.songs.size == 2 }
        assertEquals(listOf(1L, 2L), compilation.songs.map { it.id })
        assertEquals("Разные исполнители", compilation.artist)
    }

    @Test fun albumTrackNumbersTakePriorityOverAlphabeticalTitles() {
        val album = buildDeviceAlbums(listOf(song(1, track = 3), song(2, track = 1001), song(3, track = 1), song(4)), emptyMap()).single()
        assertEquals(listOf(3L, 1L, 2L, 4L), album.songs.map { it.id })
    }

    @Test fun hiddenSongsAreExcludedAndCustomCoversAreUsed() {
        val cover = Uri.parse("file:///custom.png")
        val album = buildDeviceAlbums(listOf(song(1), song(2).copy(isHidden = true)), mapOf(1L to cover)).single()
        assertEquals(listOf(1L), album.songs.map { it.id })
        assertEquals(listOf(cover), album.artwork)
    }

    @Test fun playlistKeepsUserOrderAndCustomArtwork() {
        val playlist = UserAlbum("mix", "Для дороги", listOf(3, 1, 3, 99), customCoverUri = "file:///playlist.png")
        val entry = buildUserPlaylists(listOf(playlist), listOf(song(1), song(2), song(3)), emptyMap()).single()
        assertEquals(listOf(3L, 1L), entry.songs.map { it.id })
        assertEquals(listOf(Uri.parse("file:///playlist.png")), entry.artwork)
    }

    @Test fun pinnedPlaylistsStayFirstWhileFilteringAndSorting() {
        val playlists = listOf(UserAlbum("a", "Альфа", listOf(1)), UserAlbum("z", "Зима", emptyList(), isPinned = true))
        val entries = buildUserPlaylists(playlists, listOf(song(1)), emptyMap())
        assertEquals(listOf("Зима", "Альфа"), entries.filteredAndSorted("", AlbumSort.Title).map { it.title })
        assertEquals(listOf("Альфа"), entries.filteredAndSorted("  альф  ", AlbumSort.Title).map { it.title })
    }

    @Test fun trackNumbersSurviveBothLibraryCaches() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tracks = listOf(song(1, track = 2003))
        SongsFileStorage.save(context, tracks)
        assertEquals(2003, SongsFileStorage.load(context)!!.single().trackNumber)
        val preferences = PreferencesManager(context)
        preferences.saveSongs(tracks)
        assertEquals(2003, preferences.loadSongs().single().trackNumber)
    }
}
