@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.musicplayer.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.musicplayer.data.*
import com.musicplayer.ui.components.*
import com.musicplayer.ui.theme.LocalAppFontFamily
import com.musicplayer.viewmodel.MusicViewModel
import com.musicplayer.viewmodel.OnlineSearchViewModel

internal data class OnlineSearchUiState(
    val query: String = "",
    val submittedQuery: String = "",
    val result: OnlineSearchState = OnlineSearchState.Idle,
    val history: List<String> = emptyList(),
    val downloads: Map<String, DownloadState> = emptyMap(),
    val downloadSongs: Map<String, OnlineSong> = emptyMap(),
    val savedUrls: Set<String> = emptySet(),
    val currentUrl: String? = null,
    val isPlaying: Boolean = false,
) {
    fun isDownloaded(url: String) = url in savedUrls || downloads[url] is DownloadState.Done
    fun isDownloading(url: String) = downloads[url] is DownloadState.Pending || downloads[url] is DownloadState.Progress
}

internal data class OnlineSearchActions(
    val onQueryChange: (String) -> Unit = {},
    val onSearch: (String) -> Unit = {},
    val onClearQuery: () -> Unit = {},
    val onPlay: (OnlineSong) -> Unit = {},
    val onDownload: (OnlineSong) -> Unit = {},
    val onAdd: (OnlineSong) -> Unit = {},
    val onCancelDownload: (String) -> Unit = {},
    val onRemoveHistory: (String) -> Unit = {},
    val onClearHistory: () -> Unit = {},
    val onOpenAlbum: (OnlineAlbumSummary) -> Unit = {},
)

@Composable
fun OnlineSearchScreen(
    musicViewModel: MusicViewModel,
    searchViewModel: OnlineSearchViewModel = viewModel(),
    isActive: Boolean = true,
) {
    val bridgeQuery by searchViewModel.bridgeQuery.collectAsState()
    val submittedQuery by searchViewModel.submittedQuery.collectAsState()
    var query by rememberSaveable { mutableStateOf(searchViewModel.submittedQuery.value) }
    val result by searchViewModel.searchState.collectAsState()
    val albumSheet by searchViewModel.albumSheetState.collectAsState()
    val downloads by searchViewModel.downloadStates.collectAsState()
    val downloadSongs by searchViewModel.downloadSongs.collectAsState()
    val readySongs by searchViewModel.readySongs.collectAsState()
    val history by searchViewModel.searchHistory.collectAsState()
    val library by musicViewModel.songs.collectAsState()
    val currentSong by musicViewModel.currentSong.collectAsState()
    val isPlaying by musicViewModel.isPlaying.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val processedDownloads = remember { mutableSetOf<String>() }
    val libraryUris = remember(library) { library.mapTo(hashSetOf()) { it.uri } }

    LaunchedEffect(bridgeQuery) {
        if (bridgeQuery.isNotBlank()) {
            query = bridgeQuery
            keyboard?.hide()
        }
    }
    LaunchedEffect(readySongs) {
        readySongs.forEach { (url, song) ->
            if (processedDownloads.add(url) && song.uri !in libraryUris) {
                musicViewModel.addDownloadedSong(song)
            }
        }
    }
    val savedUrls = remember(readySongs, libraryUris) {
        readySongs.filterValues { it.uri in libraryUris }.keys
    }
    val currentUrl = readySongs.entries.firstOrNull { it.value.uri == currentSong?.uri }?.key
        ?: currentSong?.uri?.toString()
    val uiState = OnlineSearchUiState(query, submittedQuery, result, history, downloads,
        downloadSongs, savedUrls, currentUrl, isPlaying)
    val actions = OnlineSearchActions(
        onQueryChange = { query = it },
        onSearch = { value ->
            query = value.trim()
            searchViewModel.search(query)
        },
        onClearQuery = { query = ""; searchViewModel.search("") },
        onPlay = { online ->
            if (currentUrl == online.downloadUrl) {
                musicViewModel.togglePlayPause()
            } else {
                // A completed download should play from the device, even without a network.
                val song = readySongs[online.downloadUrl] ?: Song(
                    id = online.downloadUrl.hashCode().toLong() and 0xFFFFFFFFL,
                    title = online.title.ifBlank { "Без названия" },
                    artist = online.artist,
                    album = "Онлайн",
                    duration = 0L,
                    uri = Uri.parse(online.downloadUrl),
                    albumArtUri = online.coverUrl.takeIf { it.isNotBlank() }?.let(Uri::parse),
                )
                musicViewModel.playSong(song)
            }
        },
        onDownload = searchViewModel::startDownload,
        onAdd = { online -> readySongs[online.downloadUrl]?.let(musicViewModel::addDownloadedSong) },
        onCancelDownload = searchViewModel::cancelDownload,
        onRemoveHistory = searchViewModel::removeHistoryQuery,
        onClearHistory = searchViewModel::clearSearchHistory,
        onOpenAlbum = searchViewModel::openAlbum,
    )
    OnlineSearchContent(uiState, actions, isActive)

    if (albumSheet !is OnlineAlbumSheetState.Hidden) {
        OnlineAlbumSheet(albumSheet, uiState, actions,
            onDismiss = searchViewModel::closeAlbum, onRetry = searchViewModel::retryAlbum)
    }
}

private enum class SearchFilter(val label: String) {
    All("Все"), NotDownloaded("Не скачано"), Downloaded("Скачано")
}

/** Stateless data boundary keeps the real screen previewable and interaction-testable. */
@Composable
internal fun OnlineSearchContent(
    state: OnlineSearchUiState,
    actions: OnlineSearchActions,
    isActive: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val font = LocalAppFontFamily.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var fieldFocused by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    var selectedUrls by remember { mutableStateOf(setOf<String>()) }
    var filter by rememberSaveable { mutableStateOf(SearchFilter.All) }
    var showDownloads by rememberSaveable { mutableStateOf(false) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    val selectionMode = selectedUrls.isNotEmpty()
    val songs = (state.result as? OnlineSearchState.Success)?.songs.orEmpty()
    val activeDownloads = state.downloads.values.count { it is DownloadState.Pending || it is DownloadState.Progress }
    val bottomSpace = LocalBottomChromeHeight.current + BottomChromeExtraSpace
    val compactHeight = LocalConfiguration.current.screenHeightDp < 500
    val imeVisible = WindowInsets.isImeVisible

    LaunchedEffect(state.submittedQuery, state.result is OnlineSearchState.Loading) {
        selectedUrls = emptySet()
        filter = SearchFilter.All
        listState.scrollToItem(0)
    }
    BackHandler(enabled = isActive && selectionMode) { selectedUrls = emptySet() }
    LaunchedEffect(isActive, fieldFocused) {
        if (!isActive && fieldFocused) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }

    fun submit(value: String) {
        if (value.isBlank()) return
        selectedUrls = emptySet()
        filter = SearchFilter.All
        focusManager.clearFocus()
        keyboard?.hide()
        actions.onSearch(value.trim())
    }
    fun toggleSelection(url: String) {
        selectedUrls = if (url in selectedUrls) selectedUrls - url else selectedUrls + url
    }
    val filteredSongs = songs.filter { song ->
        when (filter) {
            SearchFilter.All -> true
            SearchFilter.NotDownloaded -> !state.isDownloaded(song.downloadUrl)
            SearchFilter.Downloaded -> state.isDownloaded(song.downloadUrl)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("online_search")
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = colors.surface,
        topBar = {
            Column {
                AnimatedContent(
                    targetState = selectionMode,
                    transitionSpec = { fadeIn(tween(180)).togetherWith(fadeOut(tween(100))) },
                    label = "searchToolbar",
                ) { selecting ->
                    if (selecting) {
                        TopAppBar(
                            title = { Text("Выбрано: ${selectedUrls.size}", fontFamily = font,
                                maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            navigationIcon = {
                                IconButton(onClick = { selectedUrls = emptySet() }) {
                                    Icon(Icons.Rounded.Close, "Отменить выбор")
                                }
                            },
                            actions = {
                                IconButton(onClick = {
                                    val visibleUrls = filteredSongs.map { it.downloadUrl }.toSet()
                                    selectedUrls = if (selectedUrls.containsAll(visibleUrls)) emptySet() else visibleUrls
                                }) { Icon(Icons.Rounded.SelectAll, "Выбрать все") }
                                FilledTonalIconButton(
                                    enabled = songs.any { it.downloadUrl in selectedUrls &&
                                        !state.isDownloaded(it.downloadUrl) && !state.isDownloading(it.downloadUrl) },
                                    onClick = {
                                        songs.filter { it.downloadUrl in selectedUrls &&
                                            !state.isDownloaded(it.downloadUrl) && !state.isDownloading(it.downloadUrl) }
                                            .forEach(actions.onDownload)
                                        selectedUrls = emptySet()
                                    },
                                    modifier = Modifier.padding(end = 12.dp),
                                ) { Icon(Icons.Rounded.Download, "Скачать выбранные") }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.secondaryContainer),
                        )
                    } else {
                        val title: @Composable () -> Unit = { Text("Поиск", fontFamily = font) }
                        val toolbarActions: @Composable RowScope.() -> Unit = {
                            IconButton(onClick = {
                                focusManager.clearFocus()
                                keyboard?.hide()
                                showDownloads = true
                            }, modifier = Modifier.padding(end = 8.dp)) {
                                BadgedBox(badge = {
                                    if (activeDownloads > 0) Badge { Text(activeDownloads.toString()) }
                                }) { Icon(Icons.Rounded.DownloadForOffline, "Загрузки") }
                            }
                        }
                        if (compactHeight || imeVisible) {
                            TopAppBar(title = title, actions = toolbarActions,
                                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface))
                        } else {
                            LargeTopAppBar(title = title, actions = toolbarActions,
                                scrollBehavior = scrollBehavior,
                                colors = TopAppBarDefaults.largeTopAppBarColors(
                                    containerColor = colors.surface, scrolledContainerColor = colors.surface))
                        }
                    }
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    SearchBar(
                        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()
                            .padding(horizontal = 16.dp).padding(bottom = 12.dp),
                        inputField = {
                            SearchBarDefaults.InputField(
                                query = state.query,
                                onQueryChange = { value ->
                                    if (value.isEmpty()) actions.onClearQuery() else actions.onQueryChange(value)
                                },
                                onSearch = { submit(it) },
                                expanded = false,
                                onExpandedChange = {},
                                modifier = Modifier.focusRequester(focusRequester)
                                    .onFocusChanged { fieldFocused = it.hasFocus }.testTag("online_query"),
                                placeholder = { Text("Трек или исполнитель", fontFamily = font,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = {
                                    IconButton(onClick = { submit(state.query) }, enabled = state.query.isNotBlank()) {
                                        Icon(Icons.Rounded.Search, "Найти музыку")
                                    }
                                },
                                trailingIcon = {
                                    if (state.query.isNotEmpty()) {
                                        IconButton(onClick = {
                                            selectedUrls = emptySet()
                                            actions.onClearQuery()
                                            focusRequester.requestFocus()
                                        }) { Icon(Icons.Rounded.Close, "Очистить запрос") }
                                    }
                                },
                            )
                        },
                        expanded = false,
                        onExpandedChange = {},
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        colors = SearchBarDefaults.colors(containerColor = colors.surfaceContainerHigh),
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                    ) {}
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .imePadding(), contentAlignment = Alignment.TopCenter) {
            AnimatedContent(
                targetState = state.result,
                modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
                contentKey = { it::class },
                transitionSpec = {
                    (fadeIn(tween(220, delayMillis = 70)) + slideInVertically(tween(260)) { it / 40 })
                        .togetherWith(fadeOut(tween(100)))
                },
                label = "searchContent",
            ) { result ->
                val contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp,
                    bottom = if (imeVisible) 24.dp else bottomSpace + 16.dp)
                when (result) {
                    OnlineSearchState.Idle -> SearchLanding(
                        query = state.query, history = state.history, contentPadding = contentPadding,
                        onSearch = ::submit, onFocusSearch = { focusRequester.requestFocus() },
                        onRemove = actions.onRemoveHistory, onClearHistory = { confirmClearHistory = true },
                    )
                    OnlineSearchState.Loading -> SearchLoading(contentPadding)
                    is OnlineSearchState.Empty -> SearchMessage(
                        icon = Icons.Rounded.SearchOff,
                        title = "Пока ничего не нашлось",
                        message = "По запросу «${result.query}» нет доступных треков. Попробуйте уточнить название или исполнителя.",
                        contentPadding = contentPadding, actionLabel = "Изменить запрос",
                        onAction = { focusRequester.requestFocus(); keyboard?.show() },
                    )
                    is OnlineSearchState.Error -> SearchMessage(
                        icon = Icons.Rounded.CloudOff, title = "Не удалось выполнить поиск",
                        message = result.message, contentPadding = contentPadding,
                        actionLabel = "Попробовать снова", onAction = { submit(state.submittedQuery) },
                    )
                    is OnlineSearchState.Success -> {
                        // Use the target result in AnimatedContent; outgoing content keeps its own data.
                        val visibleSongs = result.songs.filter { song ->
                            when (filter) {
                                SearchFilter.All -> true
                                SearchFilter.NotDownloaded -> !state.isDownloaded(song.downloadUrl)
                                SearchFilter.Downloaded -> state.isDownloaded(song.downloadUrl)
                            }
                        }
                        LazyColumn(state = listState, contentPadding = contentPadding,
                            verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                            item(key = "summary") {
                                Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                                    Text("Результаты", style = MaterialTheme.typography.headlineSmall, fontFamily = font)
                                    Spacer(Modifier.height(4.dp))
                                    Text("${onlineTrackCount(result.songs.size)} по запросу «${state.submittedQuery}»",
                                        style = MaterialTheme.typography.bodyMedium, fontFamily = font,
                                        color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (result.songs.isNotEmpty()) {
                                item(key = "filters") {
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(SearchFilter.entries) { item ->
                                            FilterChip(selected = filter == item,
                                                onClick = { filter = item; selectedUrls = emptySet() },
                                                label = { Text(item.label, fontFamily = font) },
                                                leadingIcon = if (filter == item) {
                                                    { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }
                                                } else null)
                                        }
                                    }
                                }
                            }
                            if (result.albums.isNotEmpty() && filter == SearchFilter.All) {
                                item(key = "albums") {
                                    OnlineAlbumResults(result.albums, actions.onOpenAlbum)
                                }
                            }
                            items(visibleSongs, key = { it.downloadUrl }, contentType = { "track" }) { song ->
                                OnlineTrackCard(
                                    song = song, download = state.downloads[song.downloadUrl] ?: DownloadState.Idle,
                                    isSaved = song.downloadUrl in state.savedUrls,
                                    isCurrent = state.currentUrl == song.downloadUrl, isPlaying = state.isPlaying,
                                    selectionMode = selectionMode, selected = song.downloadUrl in selectedUrls,
                                    onClick = { if (selectionMode) toggleSelection(song.downloadUrl) else actions.onPlay(song) },
                                    onLongClick = { toggleSelection(song.downloadUrl) },
                                    onDownload = { actions.onDownload(song) }, onAdd = { actions.onAdd(song) },
                                )
                            }
                            if (visibleSongs.isEmpty() && filter != SearchFilter.All) {
                                item(key = "empty_filter") {
                                    SearchEmptyFilter(downloaded = filter == SearchFilter.Downloaded,
                                        onShowAll = { filter = SearchFilter.All })
                                }
                            }
                            if (visibleSongs.isNotEmpty() && !selectionMode) {
                                item(key = "selection_hint") {
                                    Text("Удерживайте трек, чтобы выбрать несколько для загрузки",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                                        style = MaterialTheme.typography.bodySmall, fontFamily = font,
                                        color = colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showDownloads) {
        OnlineDownloadsSheet(state, actions, onDismiss = { showDownloads = false })
    }
    if (confirmClearHistory) {
        AlertDialog(onDismissRequest = { confirmClearHistory = false },
            icon = { Icon(Icons.Rounded.History, null) },
            title = { Text("Очистить историю?", fontFamily = font) },
            text = { Text("Недавние запросы исчезнут. Скачанная музыка останется в медиатеке.", fontFamily = font) },
            confirmButton = {
                TextButton(onClick = { actions.onClearHistory(); confirmClearHistory = false }) {
                    Text("Очистить", fontFamily = font)
                }
            }, dismissButton = {
                TextButton(onClick = { confirmClearHistory = false }) { Text("Отмена", fontFamily = font) }
            })
    }
}

@Composable
private fun SearchLanding(
    query: String, history: List<String>, contentPadding: PaddingValues,
    onSearch: (String) -> Unit, onFocusSearch: () -> Unit,
    onRemove: (String) -> Unit, onClearHistory: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val font = LocalAppFontFamily.current
    val matchingHistory = history.filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }
    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()) {
        item {
            Surface(color = colors.secondaryContainer, shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(color = colors.surface.copy(alpha = 0.65f), shape = MaterialTheme.shapes.large) {
                        Icon(Icons.Rounded.TravelExplore, null, tint = colors.onSecondaryContainer,
                            modifier = Modifier.padding(16.dp).size(32.dp))
                    }
                    Text(if (query.isBlank()) "Как звучит\nваш день?" else "Найдём вашу музыку",
                        style = MaterialTheme.typography.headlineMedium, fontFamily = font,
                        color = colors.onSecondaryContainer)
                    Text("Найдите любимый трек, включите его сразу или сохраните для прослушивания без сети.",
                        style = MaterialTheme.typography.bodyLarge, fontFamily = font,
                        color = colors.onSecondaryContainer)
                    FilledTonalButton(onClick = { if (query.isBlank()) onFocusSearch() else onSearch(query) },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.surface,
                            contentColor = colors.onSurface)) {
                        Icon(Icons.Rounded.Search, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (query.isBlank()) "Найти музыку" else "Искать", fontFamily = font)
                    }
                }
            }
        }
        if (matchingHistory.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Недавние запросы", style = MaterialTheme.typography.titleMedium,
                        fontFamily = font, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClearHistory) { Text("Очистить", fontFamily = font) }
                }
            }
            items(matchingHistory, key = { it }) { item ->
                SearchHistoryRow(item, onClick = { onSearch(item) }, onRemove = { onRemove(item) })
            }
        } else if (query.isBlank()) {
            item {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.History, null, tint = colors.onSurfaceVariant)
                    Text("Ваши недавние запросы появятся здесь",
                        style = MaterialTheme.typography.bodyMedium, fontFamily = font,
                        color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SearchLoading(contentPadding: PaddingValues) {
    val colors = MaterialTheme.colorScheme
    val font = LocalAppFontFamily.current
    LazyColumn(contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Ищем вашу музыку", style = MaterialTheme.typography.titleLarge, fontFamily = font)
                Text("Проверяем доступные треки", style = MaterialTheme.typography.bodyMedium,
                    fontFamily = font, color = colors.onSurfaceVariant)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        items(4) { SearchPlaceholderCard() }
    }
}

@Composable
private fun SearchEmptyFilter(downloaded: Boolean, onShowAll: () -> Unit) {
    val font = LocalAppFontFamily.current
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(if (downloaded) Icons.Rounded.DownloadForOffline else Icons.Rounded.LibraryMusic,
            null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        Text(if (downloaded) "Здесь будут скачанные треки" else "Все найденные треки уже скачаны",
            style = MaterialTheme.typography.titleMedium, fontFamily = font)
        Text(if (downloaded) "Сохраните трек кнопкой загрузки рядом с ним."
            else "Их можно слушать без подключения к сети.",
            style = MaterialTheme.typography.bodyMedium, fontFamily = font,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onShowAll) { Text("Показать все", fontFamily = font) }
    }
}
