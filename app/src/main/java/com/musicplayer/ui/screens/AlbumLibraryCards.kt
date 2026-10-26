@file:OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class,
    ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)

package com.musicplayer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musicplayer.data.Song
import com.musicplayer.ui.components.BottomChromeExtraSpace
import com.musicplayer.ui.components.LocalBottomChromeHeight
import com.musicplayer.ui.components.OptimizedAlbumArt
import com.musicplayer.ui.theme.LocalAppFontFamily
import kotlinx.coroutines.launch
import coil.size.Size as CoilSize

private val AlbumCoverMotion = BoundsTransform { start, end ->
    keyframes {
        durationMillis = 400
        start at 0 using (if (end.top < start.top) ArcMode.ArcAbove else ArcMode.ArcBelow) using FastOutSlowInEasing
        end at 400
    }
}

/** A single shared image travels between its real positions, including the reverse transition. */
@Composable
internal fun SharedTransitionScope.LibraryAlbumCover(
    album: LibraryAlbum, animatedScope: AnimatedVisibilityScope, modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.sharedElement(rememberSharedContentState("cover:${album.key}"),
        animatedVisibilityScope = animatedScope, boundsTransform = AlbumCoverMotion)
        .clip(RoundedCornerShape(24.dp)).background(colors.secondaryContainer),
        contentAlignment = Alignment.Center) {
        when {
            album.artwork.isEmpty() -> Icon(
                if (album.playlist != null) Icons.Rounded.QueueMusic else Icons.Rounded.Album,
                null, tint = colors.onSecondaryContainer, modifier = Modifier.fillMaxSize(0.32f))
            album.artwork.size == 1 -> OptimizedAlbumArt(album.artwork.first(), album.title,
                Modifier.fillMaxSize(), CoilSize(720, 720))
            else -> Column(Modifier.fillMaxSize()) {
                repeat(2) { row ->
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        repeat(2) { column ->
                            val art = album.artwork[(row * 2 + column) % album.artwork.size]
                            OptimizedAlbumArt(art, album.title,
                                Modifier.weight(1f).fillMaxHeight(), CoilSize(360, 360))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SharedTransitionScope.AlbumTile(
    album: LibraryAlbum, listMode: Boolean, actions: AlbumLibraryActions,
    animatedScope: AnimatedVisibilityScope, modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val font = LocalAppFontFamily.current
    var menu by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f,
        spring(dampingRatio = 0.85f, stiffness = 500f), label = "albumPress")
    val tileModifier = modifier.fillMaxWidth().testTag("album_tile_${album.key}")
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(MaterialTheme.shapes.large)
        .combinedClickable(interactionSource = interaction, indication = ripple(), role = Role.Button,
            onClickLabel = "Открыть ${album.title}", onClick = { actions.onOpen(album.key) },
            onLongClickLabel = "Действия с альбомом", onLongClick = { menu = true })

    val options: @Composable () -> Unit = {
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Действия: ${album.title}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Воспроизвести", fontFamily = font) },
                    leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) }, enabled = album.songs.isNotEmpty(),
                    onClick = { menu = false; actions.onPlay(album.songs, null) })
                DropdownMenuItem(text = { Text("Перемешать", fontFamily = font) },
                    leadingIcon = { Icon(Icons.Rounded.Shuffle, null) }, enabled = album.songs.isNotEmpty(),
                    onClick = { menu = false; actions.onShuffle(album.songs) })
                album.playlist?.let { playlist ->
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Редактировать", fontFamily = font) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = { menu = false; actions.onEdit(playlist) })
                    DropdownMenuItem(text = { Text("Удалить плейлист", fontFamily = font) },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                        onClick = { menu = false; actions.onDelete(playlist) })
                }
            }
        }
    }
    val labels: @Composable ColumnScope.() -> Unit = {
        if (album.playlist?.isPinned == true) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Rounded.PushPin, null, tint = colors.primary, modifier = Modifier.size(12.dp))
                Text("Закреплён", fontFamily = font, style = MaterialTheme.typography.labelSmall, color = colors.primary)
            }
        }
        Text(album.title, style = MaterialTheme.typography.titleMedium, fontFamily = font,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(if (album.playlist == null) album.artist else collectionTrackCount(album.songs.size),
            style = MaterialTheme.typography.bodyMedium, fontFamily = font, color = colors.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (listMode) {
        Row(tileModifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LibraryAlbumCover(album, animatedScope, Modifier.size(72.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), content = labels)
            options()
        }
    } else {
        Column(tileModifier) {
            Box {
                LibraryAlbumCover(album, animatedScope, Modifier.fillMaxWidth().aspectRatio(1f))
                FilledTonalIconButton(onClick = { actions.onPlay(album.songs, null) },
                    enabled = album.songs.isNotEmpty(),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = colors.surfaceContainerHigh)) {
                    Icon(Icons.Rounded.PlayArrow, "Воспроизвести: ${album.title}")
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), content = labels)
                options()
            }
        }
    }
}

private enum class AlbumSongSort(val label: String) {
    Original("Порядок альбома"), Title("По названию трека"), Duration("По длительности")
}

@Composable
internal fun SharedTransitionScope.AlbumDetailPage(
    album: LibraryAlbum, state: AlbumLibraryState, actions: AlbumLibraryActions,
    isActive: Boolean, animatedScope: AnimatedVisibilityScope,
) {
    val font = LocalAppFontFamily.current
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var searching by rememberSaveable(album.key) { mutableStateOf(false) }
    var query by rememberSaveable(album.key) { mutableStateOf("") }
    var sort by rememberSaveable(album.key) { mutableStateOf(AlbumSongSort.Original) }
    var sortMenu by remember { mutableStateOf(false) }
    var optionsMenu by remember { mutableStateOf(false) }
    val tracks = remember(album.songs, sort) {
        when (sort) {
            AlbumSongSort.Original -> album.songs
            AlbumSongSort.Title -> album.songs.sortedBy { it.displayTitle().lowercase() }
            AlbumSongSort.Duration -> album.songs.sortedBy { it.duration }
        }
    }
    val visibleTracks = remember(tracks, query) {
        tracks.filter { query.isBlank() || it.displayTitle().contains(query.trim(), true) || it.displayArtist().contains(query.trim(), true) }
    }
    BackHandler(enabled = isActive && searching) { searching = false; query = ""; focus.clearFocus(); keyboard?.hide() }
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val sameArtist = if (album.playlist == null && album.artist != "Разные исполнители")
        state.albums.filter { it.key != album.key && it.artist == album.artist }.take(6) else emptyList()

    Scaffold(containerColor = colors.surface,
        modifier = Modifier.fillMaxSize().testTag("album_detail_${album.key}"),
        topBar = {
            TopAppBar(
                title = { Text(if (scrolled) album.title else if (album.playlist != null) "Плейлист" else "Альбом",
                    fontFamily = font, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { actions.onOpen(null) }) { Icon(Icons.Rounded.ArrowBack, "К альбомам") } },
                actions = {
                    album.playlist?.let { playlist ->
                        Box {
                            IconButton(onClick = { optionsMenu = true }) { Icon(Icons.Rounded.MoreVert, "Действия с плейлистом") }
                            DropdownMenu(expanded = optionsMenu, onDismissRequest = { optionsMenu = false }) {
                                DropdownMenuItem(text = { Text("Редактировать", fontFamily = font) },
                                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                    onClick = { optionsMenu = false; actions.onEdit(playlist) })
                                DropdownMenuItem(text = { Text("Удалить плейлист", fontFamily = font) },
                                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                                    onClick = { optionsMenu = false; actions.onDelete(playlist) })
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentAlignment = Alignment.TopCenter) {
            LazyColumn(state = listState, modifier = Modifier.widthIn(max = 720.dp).fillMaxSize().testTag("album_tracks"),
                contentPadding = PaddingValues(bottom = LocalBottomChromeHeight.current + BottomChromeExtraSpace + 24.dp)) {
                item(key = "hero") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        LibraryAlbumCover(album, animatedScope,
                            Modifier.widthIn(max = 300.dp).fillMaxWidth(0.82f).aspectRatio(1f).testTag("album_hero_cover"))
                        Spacer(Modifier.height(20.dp))
                        Text(album.title, style = MaterialTheme.typography.headlineSmall, fontFamily = font,
                            textAlign = TextAlign.Center)
                        if (album.artist.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(album.artist, style = MaterialTheme.typography.titleMedium, fontFamily = font,
                                color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("${collectionTrackCount(album.songs.size)} · ${collectionDuration(album.duration)}",
                            style = MaterialTheme.typography.bodyMedium, fontFamily = font, color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center)
                        album.playlist?.description?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(12.dp))
                            Text(it, style = MaterialTheme.typography.bodyMedium, fontFamily = font,
                                color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                        Spacer(Modifier.height(20.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            FilledTonalIconButton(onClick = { actions.onShuffle(tracks) }, enabled = tracks.isNotEmpty(),
                                modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.Shuffle, "Перемешать альбом") }
                            AlbumPlayButton(enabled = tracks.isNotEmpty(), onClick = { actions.onPlay(tracks, null) })
                            FilledTonalIconButton(onClick = {
                                searching = !searching
                                if (searching) scope.launch { listState.animateScrollToItem(1) }
                                else { query = ""; focus.clearFocus(); keyboard?.hide() }
                            }, enabled = tracks.isNotEmpty(), modifier = Modifier.size(56.dp)) {
                                Icon(Icons.Rounded.Search, "Найти трек в альбоме")
                            }
                        }
                    }
                }
                item(key = "track_header") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (query.isBlank()) "Треки" else "Найдено: ${visibleTracks.size}",
                                style = MaterialTheme.typography.titleMedium, fontFamily = font,
                                modifier = Modifier.weight(1f))
                            Box {
                                IconButton(onClick = { sortMenu = true }) { Icon(Icons.Rounded.Sort, "Порядок треков") }
                                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                    AlbumSongSort.entries.forEach { option ->
                                        DropdownMenuItem(text = { Text(if (option == AlbumSongSort.Original && album.playlist != null)
                                            "Порядок плейлиста" else option.label, fontFamily = font) },
                                            leadingIcon = { if (sort == option) Icon(Icons.Rounded.Check, null) },
                                            onClick = { sort = option; sortMenu = false })
                                    }
                                }
                            }
                            if (searching) IconButton(onClick = { searching = false; query = ""; focus.clearFocus(); keyboard?.hide() }) {
                                Icon(Icons.Rounded.Close, "Закрыть поиск треков")
                            }
                        }
                        AnimatedVisibility(searching, enter = expandVertically(tween(220)) + fadeIn(),
                            exit = shrinkVertically(tween(180)) + fadeOut()) {
                            AlbumQueryField(query, { query = it }, "Название трека", isActive,
                                Modifier.padding(bottom = 12.dp), "album_track_query")
                        }
                    }
                }
                itemsIndexed(visibleTracks, key = { _, song -> "song:${song.id}" }) { _, song ->
                    AlbumTrackRow(song,
                        state.currentSongId == song.id, state.isPlaying,
                        onClick = { actions.onPlay(tracks, song) }, modifier = Modifier.animateItem())
                }
                if (visibleTracks.isEmpty()) {
                    item(key = "empty_tracks") {
                        AlbumEmptyState(
                            if (query.isNotBlank()) "Такого трека здесь нет" else "В плейлисте пока тихо",
                            if (query.isNotBlank()) "Измените запрос или вернитесь ко всем трекам."
                            else "Добавьте музыку из медиатеки, чтобы слушать её вместе.",
                            Icons.Rounded.QueueMusic,
                            if (query.isNotBlank()) "Все треки" else if (album.playlist != null) "Добавить треки" else null,
                            onAction = { if (query.isNotBlank()) query = "" else album.playlist?.let(actions.onEdit) },
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                if (sameArtist.isNotEmpty() && query.isBlank()) {
                    item(key = "related") {
                        Text("Ещё у исполнителя", fontFamily = font, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 12.dp))
                        // Related covers have no shared key, so they cannot steal the return
                        // match from the overview grid.
                        androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(sameArtist.size) { index ->
                                val related = sameArtist[index]
                                Card(onClick = { actions.onOpen(related.key) }, modifier = Modifier.width(148.dp),
                                    colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow)) {
                                    OptimizedAlbumArt(related.artwork.firstOrNull(), related.title,
                                        Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.large), CoilSize(360, 360))
                                    Text(related.title, fontFamily = font, style = MaterialTheme.typography.titleSmall,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumPlayButton(enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val radius by animateDpAsState(if (pressed) 40.dp else 28.dp, spring(stiffness = 500f), label = "albumPlayShape")
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, spring(stiffness = 500f), label = "albumPlayScale")
    FilledIconButton(onClick = onClick, enabled = enabled, interactionSource = interaction,
        shape = RoundedCornerShape(radius), modifier = Modifier.size(80.dp).graphicsLayer { scaleX = scale; scaleY = scale }) {
        Icon(Icons.Rounded.PlayArrow, "Воспроизвести альбом", Modifier.size(36.dp))
    }
}

@Composable
private fun AlbumTrackRow(song: Song, current: Boolean, playing: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val font = LocalAppFontFamily.current
    val color by animateColorAsState(if (current) colors.secondaryContainer else colors.surface, tween(180), label = "albumTrack")
    Surface(onClick = onClick, color = color, shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("album_song_${song.id}")
            .semantics { if (current) stateDescription = if (playing) "Сейчас играет" else "На паузе" }) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Обложка трека вместо порядкового номера; у играющего трека — индикатор поверх обложки.
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                OptimizedAlbumArt(
                    uri = song.displayAlbumArt(),
                    title = song.title,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                    targetSize = CoilSize(156, 156)
                )
                if (current) {
                    Surface(
                        color = colors.primary,
                        contentColor = colors.onPrimary,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.align(Alignment.BottomEnd)
                    ) {
                        Icon(
                            if (playing) Icons.Rounded.GraphicEq else Icons.Rounded.Pause,
                            contentDescription = null,
                            modifier = Modifier.padding(3.dp).size(14.dp)
                        )
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(song.displayTitle(), style = MaterialTheme.typography.bodyLarge, fontFamily = font,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(song.displayArtist(), style = MaterialTheme.typography.bodyMedium, fontFamily = font,
                    color = if (current) colors.onSecondaryContainer else colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(song.formattedDuration(), style = MaterialTheme.typography.labelMedium, fontFamily = font,
                color = if (current) colors.onSecondaryContainer else colors.onSurfaceVariant)
        }
    }
}

@Composable
internal fun AlbumEmptyState(
    title: String, message: String, icon: ImageVector, actionLabel: String?, onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val font = LocalAppFontFamily.current
    Surface(color = colors.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = colors.secondaryContainer) {
                Icon(icon, null, modifier = Modifier.padding(20.dp).size(40.dp), tint = colors.onSecondaryContainer)
            }
            Text(title, fontFamily = font, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(message, fontFamily = font, style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            if (actionLabel != null) FilledTonalButton(onClick = onAction) { Text(actionLabel, fontFamily = font) }
        }
    }
}
