package com.musicplayer.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.musicplayer.R

internal object AboutLayout {
    val ExpandedWidth = 840.dp
    val MaxContentWidth = 1120.dp
    val ReadingWidth = 720.dp
    val SummaryWidth = 320.dp
    val SectionGap = 24.dp
}

@Composable
internal fun AboutIdentity(horizontal: Boolean = false) {
    val icon: @Composable () -> Unit = {
        Image(
            painterResource(R.mipmap.welcome_app_icon),
            contentDescription = null, // The adjacent app name already identifies the decorative brand image.
            modifier = Modifier.size(72.dp).clip(MaterialTheme.shapes.large),
        )
    }
    val name: @Composable () -> Unit = {
        Text("Glowpath", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
        Text("Музыкальный плеер", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (horizontal) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            icon()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { name() }
        }
    } else {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            icon()
            Spacer(Modifier.height(4.dp))
            name()
        }
    }
}

@Composable
internal fun AboutVersionCard(build: AppBuildInfo, onCopy: () -> Unit, onBuilds: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val stackMetadata = LocalDensity.current.fontScale > 1.3f
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Версия ${build.versionName}", style = MaterialTheme.typography.titleLarge)
                Text("${build.variantLabel} · код версии ${build.versionCode}", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            if (stackMetadata) {
                AboutBuildValue("Сборка", build.buildLabel)
                AboutBuildValue("Ревизия кода", build.revisionLabel)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AboutBuildValue("Сборка", build.buildLabel, Modifier.weight(1f))
                    AboutBuildValue("Ревизия кода", build.revisionLabel, Modifier.weight(1f))
                }
            }
            Column {
                FilledTonalButton(onClick = onBuilds, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Download, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Сборки приложения", textAlign = TextAlign.Center)
                }
                TextButton(
                    onClick = onCopy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface),
                ) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Скопировать сведения", textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun AboutBuildValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun AboutSectionHeading(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
    )
}

@Composable
internal fun AboutLinkRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Открыть в браузере", onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null) },
        trailingContent = { Icon(Icons.Rounded.ArrowOutward, null, Modifier.size(20.dp)) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
internal fun AboutExpandRow(title: String, subtitle: String, icon: ImageVector, expanded: Boolean, onClick: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "about_disclosure")
    ListItem(
        modifier = Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = if (expanded) "Свернуть" else "Развернуть", onClick = onClick)
            .semantics { stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто" },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null) },
        trailingContent = { Icon(Icons.Rounded.ExpandMore, null, Modifier.rotate(rotation)) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
internal fun AboutDeviceValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun AboutParagraph(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
