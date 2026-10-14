package com.musicplayer.ui.screens

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import com.musicplayer.data.AppTheme
import com.musicplayer.data.Song
import com.musicplayer.data.UserAlbum
import com.musicplayer.ui.theme.LocalAppFontFamily
import com.musicplayer.ui.theme.MusicPlayerTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlbumLibraryScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var opened by mutableStateOf<String?>(null)
    private var active by mutableStateOf(true)
    private var state by mutableStateOf(AlbumLibraryState())
    private var playedQueue = emptyList<Song>()
    private var playedSong: Song? = null
    private var shuffledQueue = emptyList<Song>()
    private var creates = 0
    private var edited: UserAlbum? = null
    private val titles = listOf("Линия горизонта", "После полуночи", "Тёплый свет", "Тише города", "По пути домой", "Новая волна")
    private val artists = listOf("Северный берег", "Спутник", "Линия", "Океан", "Северный берег", "Ветер")

    private fun album(index: Int) = LibraryAlbum(
        key = "test:$index", title = titles[index % titles.size], artist = artists[index % artists.size],
        songs = (1..6).map { number -> Song(id = index * 10L + number,
            title = if (number == 1) "Первый свет" else "Композиция $number", artist = artists[index % artists.size],
            album = titles[index % titles.size], duration = 180_000L + number * 7000L,
            uri = Uri.parse("file:///music/$index-$number.mp3"), albumArtUri = null, trackNumber = number) },
        artwork = emptyList(),
    )
    private val first get() = state.albums.first()

    private fun show(initial: AlbumLibraryState = AlbumLibraryState(albums = (0..5).map(::album)),
                     light: Boolean = false, fontScale: Float = 1f) {
        state = initial
        compose.setContent {
            MusicPlayerTheme(appTheme = if (light) AppTheme.LIGHT else AppTheme.BLOOMEE) {
                MaterialTheme(typography = Typography()) {
                    CompositionLocalProvider(LocalAppFontFamily provides FontFamily.SansSerif,
                        LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                        AlbumLibraryContent(state, opened, AlbumLibraryActions(
                            onOpen = { opened = it },
                            onPlay = { queue, start -> playedQueue = queue; playedSong = start },
                            onShuffle = { shuffledQueue = it },
                            onCreate = { creates++ },
                            onEdit = { edited = it },
                        ), isActive = active)
                    }
                }
            }
        }
    }

    private fun openFirst() = compose.onNodeWithTag("album_tile_${first.key}").performClick()

    @Test fun openAndBackKeepTheScrolledGridPosition() {
        show(AlbumLibraryState(albums = (0..23).map(::album)))
        val key = "album_tile_test:20"
        compose.onNodeWithTag("album_grid").performScrollToNode(hasTestTag(key))
        val position = compose.onNodeWithTag(key).fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag(key).performClick()
        compose.onNodeWithTag("album_detail_test:20").assertIsDisplayed()
        compose.onNodeWithContentDescription("К альбомам").performClick()
        compose.onNodeWithTag(key).assertIsDisplayed()
        assertEquals(position, compose.onNodeWithTag(key).fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test fun backDuringOpeningSettlesOnTheGrid() {
        show()
        compose.mainClock.autoAdvance = false
        openFirst()
        compose.mainClock.advanceTimeBy(128)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(600)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("album_grid").assertIsDisplayed()
        compose.onNodeWithTag("album_detail_test:0").assertDoesNotExist()
        openFirst()
        compose.onNodeWithTag("album_detail_test:0").assertIsDisplayed()
    }

    @Test fun searchAndListModeOpenTheSameAlbum() {
        show()
        compose.onNodeWithContentDescription("Показать списком").performClick()
        compose.onNodeWithContentDescription("Найти альбом или плейлист").performClick()
        compose.onNodeWithTag("album_query").performTextInput("Спутник")
        compose.onNodeWithTag("album_tile_test:0").assertDoesNotExist()
        compose.onNodeWithTag("album_tile_test:1").performClick()
        compose.onNodeWithTag("album_detail_test:1").assertIsDisplayed()
        compose.onNodeWithContentDescription("К альбомам").performClick()
        compose.onNodeWithTag("album_query").assertTextContains("Спутник")
        compose.onNodeWithContentDescription("Показать сеткой").assertExists()
    }

    @Test fun trackSearchPlaysTheSelectedSongInTheFullAlbumQueue() {
        show()
        openFirst()
        compose.onNodeWithContentDescription("Найти трек в альбоме").performClick()
        compose.onNodeWithTag("album_track_query").performTextInput("Композиция 4")
        compose.onNodeWithTag("album_song_1").assertDoesNotExist()
        compose.onNodeWithTag("album_song_4").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(first.songs, playedQueue)
            assertEquals(first.songs[3], playedSong)
        }
    }

    @Test fun albumShuffleDoesNotIncludeOtherAlbums() {
        show()
        openFirst()
        compose.onNodeWithContentDescription("Перемешать альбом").performClick()
        compose.runOnIdle { assertEquals(first.songs, shuffledQueue) }
    }

    @Test fun emptyPlaylistOffersEditingAndDisablesPlayback() {
        val playlist = UserAlbum("empty", "Для дороги", emptyList())
        val entry = LibraryAlbum("user:empty", playlist.name, "", emptyList(), emptyList(), playlist)
        show(AlbumLibraryState(playlists = listOf(entry)))
        compose.onNodeWithTag("album_tab_1").performClick()
        compose.onNodeWithTag("album_tile_user:empty").performClick()
        compose.onNodeWithContentDescription("Воспроизвести альбом").assertIsNotEnabled()
        compose.onNodeWithText("Добавить треки").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(playlist, edited) }
    }

    @Test fun emptyPlaylistsCanBeCreated() {
        show(AlbumLibraryState())
        compose.onNodeWithTag("album_tab_1").performClick()
        compose.onNodeWithText("Создать плейлист").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, creates) }
    }

    @Test fun removedPlaylistReturnsToOverviewWithoutAStaleDetailPage() {
        val playlist = UserAlbum("one", "Для дороги", emptyList())
        show(AlbumLibraryState(playlists = listOf(LibraryAlbum("user:one", playlist.name, "", emptyList(), emptyList(), playlist))))
        compose.onNodeWithTag("album_tab_1").performClick()
        compose.onNodeWithTag("album_tile_user:one").performClick()
        compose.runOnIdle { state = state.copy(playlists = emptyList()) }
        compose.onNodeWithTag("album_grid").assertIsDisplayed()
        compose.runOnIdle { assertNull(opened) }
    }

    @Test fun hiddenTabDoesNotConsumeBack() {
        var handled = false
        val callback = object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { handled = true }
        }
        // Register before composition, beneath the screen's own handlers.
        compose.activity.runOnUiThread { compose.activity.onBackPressedDispatcher.addCallback(callback) }
        show()
        openFirst()
        compose.runOnIdle { active = false }
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.onBackPressed()
            assertTrue(handled)
            assertEquals(first.key, opened)
            handled = false
            active = true
        }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("album_grid").assertIsDisplayed()
        compose.runOnIdle { assertFalse(handled); callback.remove() }
    }

    @Test fun renderGridDark() { show(); capture("albums-dark-grid") }

    @Test fun renderDetailDark() { show(); openFirst(); capture("album-dark-detail") }

    @Test fun renderGridWithLargeText() { show(light = true, fontScale = 1.3f); capture("albums-light-large-text") }

    @Test fun renderEmptyPlaylists() {
        show(AlbumLibraryState(), light = true)
        compose.onNodeWithTag("album_tab_1").performClick()
        capture("playlists-empty")
    }

    @Test fun renderOpeningAndReturnMidpoints() {
        show()
        compose.mainClock.autoAdvance = false
        openFirst()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(192)
        capture("album-opening-midpoint")
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription("К альбомам").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(192)
        capture("album-return-midpoint")
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("album_grid").assertIsDisplayed()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.findViewById<View>(android.R.id.content)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            if (name.contains("midpoint")) {
                // Sliding pages must never expose an empty strip at either screen edge.
                assertEquals(255, android.graphics.Color.alpha(bitmap.getPixel(0, view.height / 2)))
                assertEquals(255, android.graphics.Color.alpha(bitmap.getPixel(view.width - 1, view.height / 2)))
            }
            val directory = File("build/reports/album-preview").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
