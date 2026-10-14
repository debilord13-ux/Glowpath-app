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
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.musicplayer.R

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val build = remember(context) { readAppBuildInfo(context) }
    AboutScreenContent(
        build = build,
        onBack = onBack,
        onOpenUrl = { url ->
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                .onFailure { Toast.makeText(context, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show() }
        },
        onCopyInfo = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Сведения о Glowpath", build.diagnostics()))
            Toast.makeText(context, "Сведения скопированы", Toast.LENGTH_SHORT).show()
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutScreenContent(
    build: AppBuildInfo,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onCopyInfo: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var deviceExpanded by rememberSaveable { mutableStateOf(false) }
    var creditsExpanded by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = colors.surface,
        topBar = {
            LargeTopAppBar(
                title = { Text("О приложении") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = colors.surface),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("about_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(key = "identity") {
                Surface(color = colors.primaryContainer, shape = RoundedCornerShape(32.dp)) {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Image(
                            painterResource(R.drawable.welcome_app_icon),
                            contentDescription = "Значок Glowpath",
                            modifier = Modifier.size(88.dp).clip(RoundedCornerShape(24.dp)),
                        )
                        Text("Glowpath", style = MaterialTheme.typography.headlineLarge)
                        Text(
                            "Твоя музыка, всегда с тобой",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        Surface(
                            color = colors.surface.copy(alpha = 0.72f),
                            contentColor = colors.onSurface,
                            shape = MaterialTheme.shapes.extraLarge,
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text(
                                "${build.versionName} · ${build.variantLabel}",
                                style = MaterialTheme.typography.labelLarge,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
            item(key = "build") {
                AboutSection("Установленная версия") {
                    AboutInfoRow(Icons.Rounded.Tag, "Код версии", build.versionCode.toString())
                    AboutDivider()
                    AboutInfoRow(Icons.Rounded.Build, "Сборка", build.buildLabel)
                    AboutDivider()
                    AboutInfoRow(Icons.Rounded.Code, "Ревизия кода", build.revisionLabel)
                    FilledTonalButton(
                        onClick = onCopyInfo,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Скопировать сведения", textAlign = TextAlign.Center)
                    }
                }
            }
            item(key = "device") {
                AboutExpandableCard(
                    title = "Устройство и совместимость",
                    subtitle = "Android ${build.androidVersion} · API ${build.androidSdk}",
                    icon = Icons.Rounded.PhoneAndroid,
                    expanded = deviceExpanded,
                    onClick = { deviceExpanded = !deviceExpanded },
                ) {
                    AboutInfoRow(Icons.Rounded.Smartphone, "Устройство", build.device)
                    AboutInfoRow(Icons.Rounded.Memory, "Архитектуры устройства", build.deviceAbis)
                    AboutInfoRow(Icons.Rounded.Android, "Минимальный уровень Android API", build.minSdk.toString())
                    AboutInfoRow(Icons.Rounded.DeveloperMode, "Целевой уровень Android API", build.targetSdk.toString())
                    AboutInfoRow(Icons.Rounded.Apps, "Имя пакета", build.packageName)
                    Spacer(Modifier.height(8.dp))
                }
            }
            item(key = "project") {
                AboutSection("Проект") {
                    aboutActions.forEachIndexed { index, action ->
                        AboutLinkRow(action.icon, action.title, action.description) { onOpenUrl(action.url) }
                        if (index < aboutActions.lastIndex) AboutDivider()
                    }
                }
            }
            item(key = "credits") {
                AboutExpandableCard(
                    title = "Благодарности",
                    subtitle = "Авторы и источники вдохновения",
                    icon = Icons.Rounded.FavoriteBorder,
                    expanded = creditsExpanded,
                    onClick = { creditsExpanded = !creditsExpanded },
                ) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Первоначальный экран «О приложении» основан на Rhythm (GPL-3.0-or-later). Спасибо его авторам:",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        rhythmCredits.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
                    }
                    AboutLinkRow(Icons.Rounded.Code, "Проект Rhythm", "Исходный проект и его участники") {
                        onOpenUrl("https://github.com/cromaguy/Rhythm")
                    }
                    AboutLinkRow(Icons.Rounded.Album, "Проект Booming Music", "Вдохновение для раздела альбомов") {
                        onOpenUrl("https://github.com/mardous/BoomingMusic")
                    }
                }
            }
            item(key = "footer") {
                Text(
                    "Сделано для музыки. И для тебя.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun AboutSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp).semantics { heading() },
        )
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
private fun AboutInfoRow(icon: ImageVector, label: String, value: String) {
    ListItem(
        headlineContent = { Text(value, style = MaterialTheme.typography.bodyLarge) },
        overlineContent = { Text(label, style = MaterialTheme.typography.labelMedium) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}

@Composable
private fun AboutLinkRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodyMedium) },
        leadingContent = { AboutLeadingIcon(icon) },
        trailingContent = { Icon(Icons.Rounded.ArrowOutward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}

@Composable
private fun AboutExpandableCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    expanded: Boolean,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "about_expand")
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            ListItem(
                modifier = Modifier.clickable(onClick = onClick).semantics {
                    stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто"
                },
                headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
                supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodyMedium) },
                leadingContent = { AboutLeadingIcon(icon) },
                trailingContent = { Icon(Icons.Rounded.ExpandMore, null, Modifier.rotate(rotation)) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            )
            AnimatedVisibility(expanded) { Column(content = content) }
        }
    }
}

@Composable
private fun AboutLeadingIcon(icon: ImageVector) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(14.dp)) {
        Icon(icon, null, Modifier.padding(10.dp).size(22.dp))
    }
}

@Composable
private fun AboutDivider() {
    HorizontalDivider(Modifier.padding(start = 56.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}
