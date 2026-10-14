package com.musicplayer.ui.screens

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.musicplayer.data.AppTheme
import com.musicplayer.data.SortOrder
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
class SettingsDetailsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var selected by mutableStateOf(SortOrder.TITLE)
    private var sheetVisible by mutableStateOf(false)
    private val changes = mutableListOf<SortOrder>()
    private var openedUrl: String? = null
    private var backClicks = 0
    private var renderedColors: ColorScheme? = null

    private fun themed(light: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            MusicPlayerTheme(appTheme = if (light) AppTheme.LIGHT else AppTheme.BLOOMEE) {
                MaterialTheme(typography = Typography()) {
                    val colors = MaterialTheme.colorScheme
                    SideEffect { renderedColors = colors }
                    CompositionLocalProvider(
                        LocalAppFontFamily provides FontFamily.SansSerif,
                        LocalDensity provides Density(LocalDensity.current.density, fontScale),
                        content = content,
                    )
                }
            }
        }
    }

    private fun showAbout(light: Boolean = false, fontScale: Float = 1f) {
        val build = readAppBuildInfo(compose.activity)
        themed(light, fontScale) {
            AboutScreenContent(build, { backClicks++ }, { openedUrl = it; true }, { true })
        }
    }

    private fun showSort(fontScale: Float = 1f) {
        themed(fontScale = fontScale) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(16.dp)) {
                    SettingsSortItem(selected) { sheetVisible = true }
                }
                if (sheetVisible) {
                    LibrarySortSheet(selected, { selected = it; changes += it }, { sheetVisible = false })
                }
            }
        }
    }

    @Test fun everySortChoiceIsAppliedOnceAndSurvivesReopening() {
        showSort()
        SortOrder.entries.forEach { order ->
            compose.onNodeWithText("Сортировка").performClick()
            compose.onNodeWithTag("library_sort_${order.name}").performScrollTo().performClick()
            compose.onNodeWithTag("library_sort_options").assertDoesNotExist()
            compose.runOnIdle { assertEquals(order, selected) }
            compose.onNodeWithText("Сортировка").performClick()
            compose.onNodeWithTag("library_sort_${order.name}").performScrollTo().assertIsSelected()
            compose.onNodeWithContentDescription("Закрыть сортировку").performClick()
        }
        compose.runOnIdle { assertEquals(SortOrder.entries, changes) }
    }

    @Test fun closingSortWithoutChoosingKeepsTheCurrentOrder() {
        selected = SortOrder.ALBUM
        showSort()
        compose.onNodeWithText("Сортировка").performClick()
        compose.onNodeWithTag("library_sort_ALBUM").assertIsSelected()
        compose.onNodeWithContentDescription("Закрыть сортировку").performClick()
        compose.onNodeWithTag("library_sort_options").assertDoesNotExist()
        compose.runOnIdle { assertEquals(SortOrder.ALBUM, selected); assertTrue(changes.isEmpty()) }
    }

    @Config(qualifiers = "w320dp-h568dp")
    @Test fun lastSortOptionIsReachableOnSmallScreensWithLargeText() {
        showSort(fontScale = 1.5f)
        compose.onNodeWithText("Сортировка").performClick()
        compose.onNodeWithTag("library_sort_DATE_ADDED").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(SortOrder.DATE_ADDED, selected) }
    }

    @Test fun copyIncludesTheInstalledBuildAndDeviceInformation() {
        themed { AboutScreen(onBack = {}) }
        compose.onNodeWithText("Скопировать сведения").performScrollTo().performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
            assertEquals(readAppBuildInfo(compose.activity).diagnostics(), copied)
        }
    }

    @Test fun projectLinkOpensGlowpathAndCreditsIdentifyTheUpstreamProject() {
        showAbout()
        compose.onNodeWithTag("about_list").performScrollToNode(hasText("Исходный код"))
        compose.onNodeWithText("Исходный код").performClick()
        compose.runOnIdle { assertEquals(GLOWPATH_REPOSITORY, openedUrl) }
        compose.onNodeWithTag("about_list").performScrollToNode(hasText("Благодарности"))
        compose.onNodeWithText("Благодарности").performClick()
        compose.onNodeWithTag("about_list").performScrollToNode(hasText("Проект Rhythm"))
        compose.onNodeWithText("Проект Rhythm").performClick()
        compose.runOnIdle { assertEquals("https://github.com/cromaguy/Rhythm", openedUrl) }
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.runOnIdle { assertEquals(1, backClicks) }
    }

    private fun assertAboutTextContrast() {
        compose.runOnIdle {
            val colors = requireNotNull(renderedColors)
            val pairs = listOf(
                colors.onSurface to colors.surface,
                colors.onSurfaceVariant to colors.surface,
                colors.onSecondaryContainer to colors.secondaryContainer,
                colors.inverseOnSurface to colors.inverseSurface,
            )
            pairs.forEach { (foreground, background) ->
                val contrast = ColorUtils.calculateContrast(foreground.toArgb(), background.toArgb())
                assertTrue("Text contrast is $contrast", contrast >= 4.5)
            }
        }
    }

    @Test fun renderAboutDark() { showAbout(); assertAboutTextContrast(); capture("about-dark") }

    @Test fun renderAboutLight() { showAbout(light = true); assertAboutTextContrast(); capture("about-light") }

    @Test fun failedLinkCanBeCopiedFromTheSnackbar() {
        val build = readAppBuildInfo(compose.activity)
        var copied: String? = null
        themed {
            AboutScreenContent(build, {}, { false }, { copied = it; true })
        }
        compose.onNodeWithTag("about_list").performScrollToNode(hasText("Исходный код"))
        compose.onNodeWithText("Исходный код").performClick()
        compose.onNodeWithText("Не удалось открыть ссылку").assertIsDisplayed()
        compose.onNodeWithText("Скопировать", substring = false).performClick()
        compose.runOnIdle { assertEquals(GLOWPATH_REPOSITORY, copied) }
        compose.onNodeWithText("Ссылка скопирована").assertIsDisplayed()
    }

    @Test fun copyFailureAllowsRetryAndReportsSuccess() {
        val build = readAppBuildInfo(compose.activity)
        var canCopy by mutableStateOf(false)
        themed { AboutScreenContent(build, {}, { true }, { canCopy }) }
        compose.onNodeWithText("Скопировать сведения").performScrollTo().performClick()
        compose.onNodeWithText("Не удалось скопировать. Попробуй ещё раз.").assertIsDisplayed()
        compose.runOnIdle { canCopy = true }
        compose.onNodeWithText("Скопировать сведения").performClick()
        compose.onNodeWithText("Сведения скопированы").assertIsDisplayed()
    }

    @Test fun projectLinkSupportsKeyboardAndControlsHaveTouchTargets() {
        showAbout()
        compose.onNodeWithContentDescription("Назад").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithText("Сборки приложения").performScrollTo().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Скопировать сведения").performScrollTo().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("about_list").performScrollToNode(hasText("Исходный код"))
        compose.onNodeWithText("Исходный код").assertHeightIsAtLeast(48.dp)
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText("Исходный код").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(GLOWPATH_REPOSITORY, openedUrl) }
    }

    @Test fun expandedDeviceInformationSurvivesStateRestoration() {
        val build = readAppBuildInfo(compose.activity)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { AboutScreenContent(build, {}, { true }, { true }) } }
        compose.onNodeWithTag("about_list").performScrollToNode(hasText("Устройство и совместимость"))
        compose.onNodeWithText("Устройство и совместимость").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("about_list").performScrollToNode(hasText("Устройство и совместимость"))
        compose.onNodeWithText("Устройство и совместимость")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Развёрнуто"))
        compose.onNodeWithTag("about_list").performScrollToNode(hasText("Имя пакета"))
        compose.onNodeWithText(build.packageName).assertIsDisplayed()
    }

    @Config(qualifiers = "w1000dp-h800dp")
    @Test fun wideLayoutKeepsVersionAndProjectActionsTogether() {
        showAbout(light = true)
        compose.onNodeWithTag("about_summary_pane").assertIsDisplayed()
        compose.onNodeWithText("Сборки приложения").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(aboutBuildsAction.url, openedUrl) }
        compose.onNodeWithText("Исходный код").assertIsDisplayed()
        capture("about-wide")
    }

    @Config(qualifiers = "w839dp-h900dp")
    @Test fun mediumWindowUsesOneReadingFlow() {
        showAbout()
        compose.onNodeWithTag("about_summary_pane").assertDoesNotExist()
        compose.onNodeWithText("Сборки приложения").performScrollTo().assertIsDisplayed()
        capture("about-medium")
    }

    @Config(qualifiers = "w840dp-h900dp")
    @Test fun expandedWidthStartsTheSupportingPane() {
        showAbout()
        compose.onNodeWithTag("about_summary_pane").assertIsDisplayed()
        compose.onNodeWithText("Сборки приложения").assertIsDisplayed()
        compose.onNodeWithText("Исходный код").assertIsDisplayed()
    }

    @Config(qualifiers = "w900dp-h400dp")
    @Test fun shortLandscapeWindowKeepsEverythingScrollable() {
        showAbout(light = true)
        compose.onNodeWithTag("about_summary_pane").assertDoesNotExist()
        compose.onNodeWithText("Скопировать сведения").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("about_list").performScrollToNode(hasText("Устройство и совместимость"))
        compose.onNodeWithText("Устройство и совместимость").assertIsDisplayed()
        capture("about-short-landscape")
    }

    @Config(qualifiers = "w320dp-h568dp")
    @Test fun doubledTextStillAllowsCopyingAndOpeningAllDeviceDetails() {
        showAbout(light = true, fontScale = 2f)
        compose.onNodeWithText("Скопировать сведения").performScrollTo().assertIsDisplayed()
        capture("about-text-200-percent")
        compose.onNodeWithTag("about_list").performScrollToNode(hasText("Устройство и совместимость"))
        compose.onNodeWithText("Устройство и совместимость").performClick()
        compose.onNodeWithTag("about_list").performScrollToNode(hasText("Имя пакета"))
        compose.onNodeWithText("com.musicplayer").assertIsDisplayed()
    }

    @Test fun renderAboutLightWithDeviceExpanded() {
        showAbout(light = true)
        compose.onNodeWithTag("about_list").performScrollToNode(hasText("Устройство и совместимость"))
        compose.onNodeWithText("Устройство и совместимость").performClick()
        compose.onNodeWithTag("about_list").performScrollToNode(hasText("Целевой уровень Android API"))
        capture("about-light-device")
    }

    @Config(qualifiers = "w320dp-h568dp")
    @Test fun renderAboutSmallScreenWithLargeText() {
        showAbout(light = true, fontScale = 1.5f)
        compose.onNodeWithTag("about_list").performScrollToNode(hasText("Скопировать сведения"))
        compose.onNodeWithText("Скопировать сведения").assertIsDisplayed()
        capture("about-light-large-text")
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun renderSort(light: Boolean) {
        themed(light = light) {
            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            BottomSheetDefaults.DragHandle()
                            LibrarySortSheetContent(SortOrder.DATE_ADDED, {}, {})
                        }
                    }
                }
            }
        }
        capture(if (light) "sort-light" else "sort-dark")
    }

    @Test fun renderSortDark() = renderSort(light = false)
    @Test fun renderSortLight() = renderSort(light = true)

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.findViewById<View>(android.R.id.content)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val directory = File("build/reports/settings-preview").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
