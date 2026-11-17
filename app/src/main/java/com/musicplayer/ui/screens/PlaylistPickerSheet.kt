package com.musicplayer.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.musicplayer.data.UserAlbum
import com.musicplayer.ui.theme.LocalAppFontFamily
import kotlinx.coroutines.launch

private fun pluralTracksForPicker(count: Int): String = when {
    count % 100 in 11..19 -> "треков"
    count % 10 == 1       -> "трек"
    count % 10 in 2..4    -> "трека"
    else                  -> "треков"
}

/**
 * Шторка «Добавить в плейлисты» из режима выделения в медиатеке.
 * Между «Выбрать все» и «Удалить» появилась кнопка с иконкой папки — она
 * открывает эту шторку: отмечаешь любые свои плейлисты (мультивыбор),
 * жмёшь «Сохранить» — выбранные песни добавляются во все отмеченные
 * плейлисты (без дубликатов). Структура — Column + verticalScroll, как в
 * LibrarySortSheet: все строки всегда присутствуют в дереве семантики,
 * что важно и для доступности, и для UI-тестов на маленьких экранах.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistPickerSheet(
    playlists: List<UserAlbum>,
    songCount: Int,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val font = LocalAppFontFamily.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<String>() }

    fun closeSheet() {
        if (closing) return
        closing = true
        scope.launch {
            try {
                sheetState.hide()
                if (!sheetState.isVisible) onDismiss()
            } finally {
                closing = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Добавить в плейлисты", style = MaterialTheme.typography.headlineSmall, fontFamily = font)
                    Text(
                        "Выбрано песен: $songCount",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = font,
                        color = colors.onSurfaceVariant,
                    )
                }
                IconButton(onClick = ::closeSheet) { Icon(Icons.Rounded.Close, "Закрыть выбор плейлистов") }
            }

            if (playlists.isEmpty()) {
                Text(
                    "Пока нет ни одного плейлиста. Создайте его во вкладке «Альбомы» — «Создать плейлист».",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = font,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .selectableGroup()
                        .testTag("playlist_picker_options")
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    playlists.forEach { playlist ->
                        val checked = playlist.id in picked
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = checked,
                                    role = Role.Checkbox,
                                    onClick = {
                                        if (checked) picked.remove(playlist.id) else picked.add(playlist.id)
                                    },
                                )
                                .testTag("playlist_picker_${playlist.id}")
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    playlist.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontFamily = font,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${playlist.songIds.size} ${pluralTracksForPicker(playlist.songIds.size)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = font,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                            if (checked) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }

            Button(
                onClick = { onSave(picked.toList()) },
                enabled = picked.isNotEmpty() && songCount > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                    .testTag("playlist_picker_save"),
                shape = MaterialTheme.shapes.large,
            ) {
                Text(
                    if (picked.isEmpty()) "Выберите плейлист" else "Сохранить",
                    fontFamily = font,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
