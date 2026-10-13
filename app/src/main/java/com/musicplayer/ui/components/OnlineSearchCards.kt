@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.musicplayer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.musicplayer.data.*
import com.musicplayer.ui.screens.OnlineSearchActions
import com.musicplayer.ui.screens.OnlineSearchUiState
import com.musicplayer.ui.theme.LocalAppFontFamily

internal fun onlineTrackCount(count: Int): String {
    val word = when {
        count % 100 in 11..14 -> "треков"
        count % 10 == 1 -> "трек"
        count % 10 in 2..4 -> "трека"
        else -> "треков"
    }
    return "$count $word"
}

@Composable
internal fun OnlineArtwork(url: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.clip(MaterialTheme.shapes.medium).background(colors.surfaceContainerHighest),
        contentAlignment = Alignment.Center) {
        // Keep a tonal placeholder underneath loading and failed image requests.
        Icon(Icons.Rounded.MusicNote, null, tint = colors.onSurfaceVariant,
            modifier = Modifier.size(28.dp))
        if (url.isNotBlank()) AsyncImage(model = url, contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
internal fun OnlineTrackCard(
    song: OnlineSong,
    download: DownloadState,
    isSaved: Boolean,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onDownload: () -> Unit,
    onAdd: () -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val font = LocalAppFontFamily.current
    val busy = download is DownloadState.Pending || download is DownloadState.Progress
    val complete = isSaved || download is DownloadState.Done
    val title = song.title.ifBlank { "Без названия" }
    val background by animateColorAsState(
        when {
            selected -> colors.secondaryContainer
            isCurrent -> colors.primaryContainer
            else -> colors.surfaceContainerLow
        }, tween(180), label = "onlineTrackColor")
    val contentColor = when {
        selected -> colors.onSecondaryContainer
        isCurrent -> colors.onPrimaryContainer
        else -> colors.onSurface
    }
    val progress by animateFloatAsState(
        ((download as? DownloadState.Progress)?.percent?.div(100f) ?: 0f).coerceIn(0f, 1f),
        tween(350), label = "onlineDownloadProgress")

    Surface(color = background, contentColor = contentColor,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().testTag("online_track_${song.downloadUrl}")
            .animateContentSize(tween(200))
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(
                role = if (selectionMode) Role.Checkbox else Role.Button,
                onClickLabel = if (selectionMode) "Изменить выбор" else if (isCurrent && isPlaying) "Приостановить" else "Воспроизвести",
                onClick = onClick, onLongClickLabel = "Выбрать для загрузки", onLongClick = onLongClick,
            )
            .semantics {
                if (selectionMode) this.selected = selected
                if (isCurrent) stateDescription = if (isPlaying) "Сейчас играет" else "На паузе"
            }
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                    OnlineArtwork(song.coverUrl, Modifier.size(56.dp))
                    if (isCurrent) {
                        Surface(color = colors.primary, contentColor = colors.onPrimary,
                            shape = RoundedCornerShape(8.dp), modifier = Modifier.align(Alignment.BottomEnd)) {
                            Icon(if (isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.Pause,
                                null, Modifier.padding(4.dp).size(16.dp))
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontFamily = font,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOf(song.artist.ifBlank { "Неизвестный исполнитель" }, song.duration)
                        .filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, fontFamily = font,
                        color = if (isCurrent || selected) contentColor else colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val status = when {
                        download is DownloadState.Err -> download.message
                        download is DownloadState.Pending -> "Ожидание загрузки"
                        download is DownloadState.Progress -> if (download.percent > 0) "Загрузка · ${download.percent}%" else "Загрузка…"
                        isSaved -> "В медиатеке"
                        download is DownloadState.Done -> "Скачано"
                        download is DownloadState.Idle && onCancel != null -> "Не скачано"
                        else -> null
                    }
                    if (status != null) Text(status, style = MaterialTheme.typography.labelMedium,
                        fontFamily = font, color = if (download is DownloadState.Err) colors.error else
                            if (isCurrent || selected) contentColor else colors.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                when {
                    selectionMode -> Checkbox(checked = selected, onCheckedChange = { onClick() })
                    busy -> {
                        if (onCancel != null) {
                            IconButton(onClick = onCancel) {
                                Icon(Icons.Rounded.Close, "Отменить загрузку: $title")
                            }
                        } else {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                if (download is DownloadState.Progress && download.percent > 0) {
                                    CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(26.dp),
                                        strokeWidth = 3.dp, trackColor = colors.surfaceContainerHighest)
                                } else CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 3.dp)
                            }
                        }
                    }
                    complete && isSaved -> {
                        Icon(Icons.Rounded.CheckCircle, "В медиатеке: $title", tint = colors.primary,
                            modifier = Modifier.size(48.dp).padding(12.dp))
                    }
                    complete -> IconButton(onClick = onAdd) {
                        Icon(Icons.Rounded.LibraryAdd, "Добавить в медиатеку: $title")
                    }
                    else -> FilledTonalIconButton(onClick = onDownload) {
                        Icon(if (download is DownloadState.Err) Icons.Rounded.Refresh else Icons.Rounded.Download,
                            if (download is DownloadState.Err) "Повторить загрузку: $title" else "Скачать: $title")
                    }
                }
            }
            if (busy) {
                if (download is DownloadState.Progress && download.percent > 0) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
internal fun SearchHistoryRow(query: String, onClick: () -> Unit, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, color = colors.surfaceContainerLow, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.History, null, tint = colors.onSurfaceVariant)
            Text(query, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                fontFamily = LocalAppFontFamily.current, maxLines = 2, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Удалить запрос: $query", tint = colors.onSurfaceVariant) }
        }
    }
}

@Composable
internal fun SearchPlaceholderCard() {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(colors.surfaceContainerLow).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(56.dp).clip(MaterialTheme.shapes.medium).background(colors.surfaceContainerHigh))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Spacer(Modifier.fillMaxWidth(0.75f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(colors.surfaceContainerHigh))
            Spacer(Modifier.fillMaxWidth(0.5f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(colors.surfaceContainerHigh))
        }
    }
}

@Composable
internal fun SearchMessage(
    icon: ImageVector, title: String, message: String, contentPadding: PaddingValues,
    actionLabel: String? = null, onAction: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val font = LocalAppFontFamily.current
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item {
            Surface(color = colors.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.fillMaxWidth().padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(color = colors.secondaryContainer, shape = MaterialTheme.shapes.extraLarge) {
                        Icon(icon, null, tint = colors.onSecondaryContainer, modifier = Modifier.padding(22.dp).size(40.dp))
                    }
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontFamily = font, textAlign = TextAlign.Center)
                    Text(message, style = MaterialTheme.typography.bodyLarge, fontFamily = font,
                        color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                    if (actionLabel != null) FilledTonalButton(onClick = onAction) { Text(actionLabel, fontFamily = font) }
                }
            }
        }
    }
}

@Composable
internal fun OnlineDownloadsSheet(state: OnlineSearchUiState, actions: OnlineSearchActions, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val font = LocalAppFontFamily.current
    val songs = state.downloadSongs.values.toList().asReversed()
    val active = songs.count { state.isDownloading(it.downloadUrl) }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.8f).dp)) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Загрузки", style = MaterialTheme.typography.headlineSmall, fontFamily = font)
                Text(if (active > 0) "Загружается: $active" else "Слушайте сохранённую музыку без сети",
                    style = MaterialTheme.typography.bodyMedium, fontFamily = font, color = colors.onSurfaceVariant)
            }
            if (songs.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 36.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.DownloadForOffline, null, tint = colors.primary, modifier = Modifier.size(40.dp))
                    Text("Музыка для любого места", style = MaterialTheme.typography.titleLarge, fontFamily = font)
                    Text("Нажмите кнопку загрузки рядом с найденным треком. Прогресс появится здесь, а музыка — в медиатеке.",
                        style = MaterialTheme.typography.bodyLarge, fontFamily = font, color = colors.onSurfaceVariant)
                    FilledTonalButton(onClick = onDismiss) { Text("К поиску", fontFamily = font) }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(songs, key = { it.downloadUrl }) { song ->
                        OnlineTrackCard(song, state.downloads[song.downloadUrl] ?: DownloadState.Idle,
                            isSaved = song.downloadUrl in state.savedUrls,
                            isCurrent = song.downloadUrl == state.currentUrl, isPlaying = state.isPlaying,
                            onClick = { actions.onPlay(song) }, onDownload = { actions.onDownload(song) },
                            onAdd = { actions.onAdd(song) }, onCancel = { actions.onCancelDownload(song.downloadUrl) })
                    }
                }
            }
        }
    }
}

@Composable
internal fun OnlineAlbumResults(albums: List<OnlineAlbumSummary>, onOpen: (OnlineAlbumSummary) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val font = LocalAppFontFamily.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        Text("Альбомы", style = MaterialTheme.typography.titleMedium, fontFamily = font)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(albums, key = { it.albumUrl }) { album ->
                Card(onClick = { onOpen(album) }, modifier = Modifier.width(176.dp), shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OnlineArtwork(album.coverUrl, Modifier.fillMaxWidth().aspectRatio(1f))
                        Text(album.title, style = MaterialTheme.typography.titleMedium, fontFamily = font,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (album.artist.isNotBlank()) Text(album.artist, style = MaterialTheme.typography.bodyMedium,
                            fontFamily = font, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
internal fun OnlineAlbumSheet(
    albumState: OnlineAlbumSheetState, state: OnlineSearchUiState, actions: OnlineSearchActions,
    onDismiss: () -> Unit, onRetry: () -> Unit,
) {
    val font = LocalAppFontFamily.current
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.surface) {
        Box(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.8f).dp)) {
            when (albumState) {
                OnlineAlbumSheetState.Hidden -> Unit
                is OnlineAlbumSheetState.Loading -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(albumState.album.title, style = MaterialTheme.typography.headlineSmall, fontFamily = font)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Загружаем треки альбома…", fontFamily = font)
                }
                is OnlineAlbumSheetState.Error -> SearchMessage(Icons.Rounded.CloudOff, "Не удалось открыть альбом",
                    albumState.message, PaddingValues(16.dp), "Повторить", onRetry)
                is OnlineAlbumSheetState.Success -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val detail = albumState.detail
                    item {
                        Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OnlineArtwork(detail.coverUrl.ifBlank { albumState.album.coverUrl }, Modifier.size(180.dp))
                            Text(detail.title.ifBlank { albumState.album.title }, style = MaterialTheme.typography.headlineSmall,
                                fontFamily = font, textAlign = TextAlign.Center)
                            Text(detail.artist.ifBlank { albumState.album.artist }, style = MaterialTheme.typography.bodyLarge,
                                fontFamily = font, color = colors.onSurfaceVariant)
                            Text(onlineTrackCount(detail.sections.sumOf { it.tracks.size }),
                                style = MaterialTheme.typography.labelLarge, fontFamily = font, color = colors.primary)
                        }
                    }
                    if (detail.description.isNotBlank()) item {
                        Text(detail.description, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodyMedium,
                            fontFamily = font, color = colors.onSurfaceVariant)
                    }
                    detail.sections.forEachIndexed { sectionIndex, section ->
                        item(key = "section_$sectionIndex") {
                            Text(section.title.ifBlank { "Треки" }, modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                                style = MaterialTheme.typography.titleMedium, fontFamily = font)
                        }
                        itemsIndexed(section.tracks, key = { index, song -> "$sectionIndex-$index-${song.downloadUrl}" }) { _, song ->
                            OnlineTrackCard(song, state.downloads[song.downloadUrl] ?: DownloadState.Idle,
                                isSaved = song.downloadUrl in state.savedUrls, isCurrent = song.downloadUrl == state.currentUrl,
                                isPlaying = state.isPlaying, onClick = { actions.onPlay(song) },
                                onDownload = { actions.onDownload(song) }, onAdd = { actions.onAdd(song) })
                        }
                    }
                }
            }
        }
    }
}
