package com.musicplayer.ui.screens

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
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
import com.musicplayer.data.DownloadState
import com.musicplayer.data.OnlineSearchState
import com.musicplayer.data.OnlineSong
import com.musicplayer.ui.theme.LocalAppFontFamily
import com.musicplayer.ui.theme.MusicPlayerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
class OnlineSearchScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var ui by mutableStateOf(OnlineSearchUiState())
    private var searchActive by mutableStateOf(true)
    private val searches = mutableListOf<String>()
    private val plays = mutableListOf<String>()
    private val downloads = mutableListOf<String>()
    private val cancelled = mutableListOf<String>()
    private val first = OnlineSong("Тёплый свет", "Линия горизонта", "3:42", "https://example.com/one.mp3")
    private val second = OnlineSong("Тише города", "Северный берег", "4:08", "https://example.com/two.mp3")
    private val third = OnlineSong("После полуночи", "Спутник", "3:17", "https://example.com/three.mp3")

    private fun results() = OnlineSearchUiState(
        query = "Линия горизонта", submittedQuery = "Линия горизонта",
        result = OnlineSearchState.Success(listOf(first, second, third), emptyList()),
        downloads = mapOf(first.downloadUrl to DownloadState.Done("/music/one.mp3")),
        savedUrls = setOf(first.downloadUrl),
    )

    private fun show(initial: OnlineSearchUiState, light: Boolean = false, fontScale: Float = 1f) {
        ui = initial
        compose.setContent {
            MusicPlayerTheme(appTheme = if (light) AppTheme.LIGHT else AppTheme.BLOOMEE) {
                // System font avoids network font-provider requests in deterministic tests.
                MaterialTheme(typography = Typography()) {
                    CompositionLocalProvider(
                        LocalAppFontFamily provides FontFamily.SansSerif,
                        LocalDensity provides Density(LocalDensity.current.density, fontScale),
                    ) {
                        OnlineSearchContent(ui, OnlineSearchActions(
                            onQueryChange = { ui = ui.copy(query = it) },
                            onSearch = {
                                searches += it
                                ui = ui.copy(query = it, submittedQuery = it, result = OnlineSearchState.Loading)
                            },
                            onClearQuery = { ui = ui.copy(query = "", submittedQuery = "", result = OnlineSearchState.Idle) },
                            onPlay = { plays += it.downloadUrl },
                            onDownload = { downloads += it.downloadUrl },
                            onCancelDownload = { cancelled += it },
                            onRemoveHistory = { query -> ui = ui.copy(history = ui.history - query) },
                            onClearHistory = { ui = ui.copy(history = emptyList()) },
                        ), isActive = searchActive)
                    }
                }
            }
        }
    }

    private fun card(song: OnlineSong) = compose.onNodeWithTag("online_track_${song.downloadUrl}")

    @Test fun leavingSearchReleasesInputFocus() {
        show(OnlineSearchUiState())
        compose.onNodeWithTag("online_query").performClick().assertIsFocused()
        compose.runOnIdle { searchActive = false }
        compose.onNodeWithTag("online_query").assertIsNotFocused()
        compose.runOnIdle { searchActive = true }
        compose.onNodeWithTag("online_query").assertIsNotFocused()
        compose.onNodeWithTag("online_query").performClick().assertIsFocused()
    }

    @Test fun keyboardSearchTrimsQueryAndIgnoresWhitespace() {
        show(OnlineSearchUiState())
        compose.onNodeWithTag("online_query").performTextInput("   ")
        compose.onNodeWithTag("online_query").performImeAction()
        compose.runOnIdle { assertTrue(searches.isEmpty()) }
        compose.onNodeWithTag("online_query").performTextReplacement("  Спутник  ")
        compose.onNodeWithTag("online_query").performImeAction()
        compose.runOnIdle { assertEquals(listOf("Спутник"), searches) }
    }

    @Test fun clearQueryReturnsToHistoryAndKeepsDownloads() {
        show(results().copy(history = listOf("Спутник")))
        compose.onNodeWithContentDescription("Очистить запрос").performClick()
        compose.onNodeWithText("Недавние запросы").assertExists()
        card(second).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("", ui.query)
            assertTrue(ui.downloads[first.downloadUrl] is DownloadState.Done)
            assertTrue(searches.isEmpty())
        }
    }

    @Test fun selectedTrackCanBeDeselectedWithoutStartingPlayback() {
        show(results())
        card(second).performTouchInput { longClick() }
        compose.onNodeWithText("Выбрано: 1").assertExists()
        card(second).performClick()
        compose.onNodeWithContentDescription("Отменить выбор").assertDoesNotExist()
        compose.runOnIdle { assertTrue(plays.isEmpty()) }
        card(second).performClick()
        compose.runOnIdle { assertEquals(listOf(second.downloadUrl), plays) }
    }

    @Test fun bulkDownloadSkipsCompletedAndActiveDownloads() {
        show(results().copy(downloads = mapOf(first.downloadUrl to DownloadState.Done("/music/one.mp3"),
            third.downloadUrl to DownloadState.Progress(42))))
        card(second).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Выбрать все").performClick()
        compose.onNodeWithText("Выбрано: 3").assertExists()
        compose.onNodeWithContentDescription("Скачать выбранные").performClick()
        compose.runOnIdle { assertEquals(listOf(second.downloadUrl), downloads) }
    }

    @Test fun downloadedFilterUsesActualDownloadState() {
        show(results())
        compose.onNodeWithText("Скачано").performClick()
        card(first).assertExists()
        card(second).assertDoesNotExist()
        compose.onNodeWithText("Не скачано").performClick()
        card(first).assertDoesNotExist()
        card(second).assertExists()
    }

    @Test fun removingHistoryDoesNotRunSearch() {
        show(OnlineSearchUiState(history = listOf("Спутник", "Северный берег")))
        compose.onNodeWithContentDescription("Удалить запрос: Спутник").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("Северный берег"), ui.history)
            assertTrue(searches.isEmpty())
        }
    }

    @Test fun downloadsCanBeRetriedOrCancelledAfterResultsAreCleared() {
        show(OnlineSearchUiState(
            downloadSongs = mapOf(first.downloadUrl to first, second.downloadUrl to second),
            downloads = mapOf(first.downloadUrl to DownloadState.Progress(42),
                second.downloadUrl to DownloadState.Err("Не удалось загрузить трек")),
        ))
        compose.onNodeWithContentDescription("Загрузки").performClick()
        compose.onNodeWithContentDescription("Повторить загрузку: ${second.title}").performClick()
        compose.onNodeWithContentDescription("Отменить загрузку: ${first.title}").performClick()
        compose.runOnIdle {
            assertEquals(listOf(second.downloadUrl), downloads)
            assertEquals(listOf(first.downloadUrl), cancelled)
        }
    }

    @Test fun renderDarkLanding() {
        show(OnlineSearchUiState(history = listOf("Линия горизонта", "Спутник", "Северный берег")))
        capture("dark-landing")
    }

    @Test fun renderDarkResults() {
        show(results().copy(currentUrl = second.downloadUrl, isPlaying = true,
            downloads = mapOf(first.downloadUrl to DownloadState.Done("/music/one.mp3"),
                third.downloadUrl to DownloadState.Progress(42))))
        capture("dark-results")
    }

    @Test fun renderLightResultsWithLargeText() {
        show(results(), light = true, fontScale = 1.3f)
        card(first).assertIsDisplayed()
        compose.onNodeWithContentDescription("Загрузки").assertIsDisplayed()
        capture("light-results-large-text")
    }

    @Test fun renderEmptyResults() {
        show(OnlineSearchUiState(query = "Новый трек", submittedQuery = "Новый трек",
            result = OnlineSearchState.Empty("Новый трек")))
        compose.onNodeWithText("Изменить запрос").assertExists()
        capture("empty-results")
    }

    /** Real Compose rendering for human review in the CI artifact, not a mockup. */
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.findViewById<View>(android.R.id.content)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val directory = File("build/reports/search-preview").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
