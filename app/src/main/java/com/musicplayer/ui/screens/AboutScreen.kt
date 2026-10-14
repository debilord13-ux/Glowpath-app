/*
 * Originally adapted from Rhythm: https://github.com/cromaguy/Rhythm
 * Original author: Anjishnu Nandi (cromaguy), GPL-3.0-or-later.
 * Glowpath layout and project information have since been redesigned.
 */
package com.musicplayer.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val build = remember(context) { readAppBuildInfo(context) }
    AboutScreenContent(
        build = build,
        onBack = onBack,
        onOpenUrl = { url ->
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isSuccess
        },
        onCopyInfo = { value ->
            runCatching {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Glowpath", value))
            }.isSuccess
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutScreenContent(
    build: AppBuildInfo,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Boolean,
    onCopyInfo: (String) -> Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val listState = rememberLazyListState()
    val summaryScroll = rememberScrollState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var deviceExpanded by rememberSaveable { mutableStateOf(false) }
    var creditsExpanded by rememberSaveable { mutableStateOf(false) }
    val fontScale = LocalDensity.current.fontScale

    fun copyInformation() {
        val success = onCopyInfo(build.diagnostics())
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(if (success) "Сведения скопированы" else "Не удалось скопировать. Попробуй ещё раз.")
        }
    }
    fun openLink(url: String) {
        snackbar.currentSnackbarData?.dismiss()
        if (!onOpenUrl(url)) scope.launch {
            val result = snackbar.showSnackbar(
                message = "Не удалось открыть ссылку",
                actionLabel = "Скопировать",
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                val success = onCopyInfo(url)
                snackbar.showSnackbar(if (success) "Ссылка скопирована" else "Не удалось скопировать. Попробуй ещё раз.")
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = colors.surface,
        snackbarHost = { SnackbarHost(snackbar, Modifier.widthIn(max = 560.dp)) },
        topBar = {
            TopAppBar(
                title = { Text("О приложении") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            // A supporting pane is useful only when both panes have enough reading space.
            // At large font scales or short landscape heights retain the single reading flow.
            val twoPane = maxWidth >= AboutLayout.ExpandedWidth && maxHeight >= 480.dp && fontScale <= 1.3f
            val pageInset = if (maxWidth >= 600.dp) 24.dp else 16.dp
            Row(
                Modifier.widthIn(max = if (twoPane) AboutLayout.MaxContentWidth else AboutLayout.ReadingWidth).fillMaxSize()
                    .padding(horizontal = pageInset),
                horizontalArrangement = Arrangement.spacedBy(AboutLayout.SectionGap),
            ) {
                if (twoPane) {
                    Column(
                        Modifier.width(AboutLayout.SummaryWidth).fillMaxHeight()
                            .verticalScroll(summaryScroll).testTag("about_summary_pane")
                            .padding(top = 24.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(AboutLayout.SectionGap),
                    ) {
                        AboutIdentity()
                        AboutVersionCard(build, ::copyInformation) { openLink(aboutBuildsAction.url) }
                    }
                    VerticalDivider(color = colors.outlineVariant)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxHeight().testTag("about_list"),
                    contentPadding = PaddingValues(top = if (twoPane) 24.dp else 8.dp, bottom = 32.dp),
                ) {
                    if (!twoPane) {
                        item(key = "identity") {
                            AboutIdentity(horizontal = maxWidth >= 600.dp && fontScale <= 1.3f)
                        }
                        item(key = "build") {
                            AboutVersionCard(build, ::copyInformation) { openLink(aboutBuildsAction.url) }
                            Spacer(Modifier.height(AboutLayout.SectionGap))
                        }
                    }
                    item(key = "project_heading") { AboutSectionHeading("Поддержка и проект") }
                    items(aboutActions, key = { it.url }) { action ->
                        AboutLinkRow(action.icon, action.title, action.description) { openLink(action.url) }
                    }
                    item(key = "credits") {
                        AboutExpandRow(
                            title = "Благодарности",
                            subtitle = "Авторы и источники вдохновения",
                            icon = Icons.Rounded.FavoriteBorder,
                            expanded = creditsExpanded,
                            onClick = { creditsExpanded = !creditsExpanded },
                        )
                    }
                    if (creditsExpanded) {
                        item(key = "credits_origin") {
                            AboutParagraph(
                                "Первоначальный экран «О приложении» основан на Rhythm (GPL-3.0-or-later). Спасибо его авторам:",
                                Modifier.animateItem(),
                            )
                        }
                        items(rhythmCredits, key = { it }) { AboutParagraph(it, Modifier.animateItem()) }
                        item(key = "rhythm") {
                            AboutLinkRow(Icons.Rounded.Code, "Проект Rhythm", "Исходный проект и его участники", Modifier.animateItem()) {
                                openLink("https://github.com/cromaguy/Rhythm")
                            }
                        }
                        item(key = "booming") {
                            AboutLinkRow(Icons.Rounded.Album, "Проект Booming Music", "Вдохновение для раздела альбомов", Modifier.animateItem()) {
                                openLink("https://github.com/mardous/BoomingMusic")
                            }
                        }
                    }
                    item(key = "device_heading") {
                        HorizontalDivider(Modifier.padding(vertical = 16.dp), color = colors.outlineVariant)
                        AboutSectionHeading("Техническая информация")
                    }
                    item(key = "device") {
                        AboutExpandRow(
                            title = "Устройство и совместимость",
                            subtitle = "Android ${build.androidVersion} · API ${build.androidSdk}",
                            icon = Icons.Rounded.PhoneAndroid,
                            expanded = deviceExpanded,
                            onClick = { deviceExpanded = !deviceExpanded },
                        )
                    }
                    if (deviceExpanded) {
                        val details = listOf(
                            "Устройство" to build.device,
                            "Архитектуры устройства" to build.deviceAbis,
                            "Минимальный уровень Android API" to build.minSdk.toString(),
                            "Целевой уровень Android API" to build.targetSdk.toString(),
                            "Имя пакета" to build.packageName,
                        )
                        items(details, key = { it.first }) { (label, value) ->
                            AboutDeviceValue(label, value, Modifier.animateItem())
                        }
                    }
                }
            }
        }
    }
}
