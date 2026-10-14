@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)

package com.musicplayer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
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
import com.musicplayer.ui.components.BottomChromeExtraSpace
import com.musicplayer.ui.components.LocalBottomChromeHeight
import com.musicplayer.ui.theme.LocalAppFontFamily

/** The overview remains the return destination, with its original scroll position and filters. */
@Composable
internal fun AlbumLibraryContent(
    state: AlbumLibraryState,
    openAlbumKey: String?,
    actions: AlbumLibraryActions,
    isActive: Boolean = true,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var listMode by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(AlbumSort.Title) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    val albumsGrid = rememberLazyGridState()
    val playlistsGrid = rememberLazyGridState()
    val openAlbum = (state.albums + state.playlists).firstOrNull { it.key == openAlbumKey }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val navigationActions = actions.copy(onOpen = {
        focusManager.clearFocus()
        keyboard?.hide()
        actions.onOpen(it)
    })

    LaunchedEffect(openAlbumKey, openAlbum) {
        if (openAlbumKey != null && openAlbum == null) actions.onOpen(null)
        if (openAlbum?.playlist != null) tab = 1
    }
    // This tab is precomposed by the pager; it must not intercept Back in another tab.
    BackHandler(enabled = isActive && openAlbum != null) { navigationActions.onOpen(null) }

    SharedTransitionLayout(Modifier.fillMaxSize().testTag("album_library")) {
        AnimatedContent(
            targetState = openAlbum,
            contentKey = { it?.key ?: "overview" },
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val direction = if (targetState == null) -1 else 1
                (fadeIn(tween(220, 90)) + slideInHorizontally(tween(380, easing = FastOutSlowInEasing)) { direction * it / 12 })
                    .togetherWith(fadeOut(tween(120)) +
                        slideOutHorizontally(tween(380, easing = FastOutSlowInEasing)) { -direction * it / 12 })
                    .using(SizeTransform(clip = false))
            },
            label = "albumNavigation",
        ) { album ->
            if (album == null) {
                AlbumOverview(
                    state, navigationActions, tab, listMode, sort, query, searching, isActive && openAlbum == null,
                    gridState = if (tab == 0) albumsGrid else playlistsGrid,
                    onTab = {
                        tab = it; query = ""
                        if (it == 1 && sort == AlbumSort.Artist) sort = AlbumSort.Title
                        focusManager.clearFocus(); keyboard?.hide()
                    },
                    onListMode = { listMode = !listMode }, onSort = { sort = it },
                    onQuery = { query = it },
                    onSearching = {
                        searching = it
                        if (!it) { query = ""; focusManager.clearFocus(); keyboard?.hide() }
                    },
                    animatedScope = this@AnimatedContent,
                )
            } else {
                AlbumDetailPage(album, state, navigationActions, isActive && openAlbum?.key == album.key, this@AnimatedContent)
            }
        }
    }
}

@Composable
private fun SharedTransitionScope.AlbumOverview(
    state: AlbumLibraryState,
    actions: AlbumLibraryActions,
    tab: Int,
    listMode: Boolean,
    sort: AlbumSort,
    query: String,
    searching: Boolean,
    isActive: Boolean,
    gridState: LazyGridState,
    onTab: (Int) -> Unit,
    onListMode: () -> Unit,
    onSort: (AlbumSort) -> Unit,
    onQuery: (String) -> Unit,
    onSearching: (Boolean) -> Unit,
    animatedScope: AnimatedVisibilityScope,
) {
    val colors = MaterialTheme.colorScheme
    val font = LocalAppFontFamily.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val entries = if (tab == 0) state.albums else state.playlists
    val visible = remember(entries, query, sort) { entries.filteredAndSorted(query, sort) }
    var showSort by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val bottomSpace = LocalBottomChromeHeight.current + BottomChromeExtraSpace
    BackHandler(enabled = isActive && searching) {
        onSearching(false); focus.clearFocus(); keyboard?.hide()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = colors.surface,
        topBar = {
            Column {
                val title: @Composable () -> Unit = { Text("Альбомы", fontFamily = font) }
                val toolbarActions: @Composable RowScope.() -> Unit = {
                    IconButton(onClick = { onSearching(!searching) }) {
                        Icon(if (searching) Icons.Rounded.Close else Icons.Rounded.Search,
                            if (searching) "Закрыть поиск альбомов" else "Найти альбом или плейлист")
                    }
                    IconButton(onClick = actions.onCreate, modifier = Modifier.padding(end = 4.dp)) {
                        Icon(Icons.Rounded.Add, "Создать плейлист")
                    }
                }
                if (LocalConfiguration.current.screenHeightDp < 500 || searching) {
                    TopAppBar(title = title, actions = toolbarActions,
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface))
                } else {
                    LargeTopAppBar(title = title, actions = toolbarActions, scrollBehavior = scrollBehavior,
                        colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = colors.surface,
                            scrolledContainerColor = colors.surface))
                }
                AnimatedVisibility(searching, enter = expandVertically(tween(220)) + fadeIn(),
                    exit = shrinkVertically(tween(180)) + fadeOut()) {
                    AlbumQueryField(query, onQuery, "Название или исполнитель", isActive,
                        Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp), "album_query")
                }
                SecondaryTabRow(selectedTabIndex = tab, containerColor = colors.surface,
                    divider = { HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f)) }) {
                    listOf("Альбомы", "Плейлисты").forEachIndexed { index, label ->
                        Tab(selected = tab == index, onClick = { onTab(index) },
                            modifier = Modifier.testTag("album_tab_$index"),
                            text = { Text(label, fontFamily = font, maxLines = 1) })
                    }
                }
            }
        },
        floatingActionButton = {
            if (tab == 1 && entries.isNotEmpty()) {
                ExtendedFloatingActionButton(onClick = actions.onCreate,
                    modifier = Modifier.padding(bottom = bottomSpace),
                    icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text("Новый плейлист", fontFamily = font) })
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentAlignment = Alignment.TopCenter) {
            LazyVerticalGrid(
                columns = if (listMode) GridCells.Fixed(1) else GridCells.Adaptive(152.dp),
                state = gridState,
                modifier = Modifier.widthIn(max = 1000.dp).fillMaxSize().testTag("album_grid"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp,
                    bottom = bottomSpace + if (tab == 1) 100.dp else 20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(if (listMode) 4.dp else 16.dp),
            ) {
                item(key = "controls", span = { GridItemSpan(maxLineSpan) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(if (query.isNotBlank()) "Найдено: ${visible.size}" else
                            if (tab == 0) "Альбомов: ${entries.size}" else "Плейлистов: ${entries.size}",
                            style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant,
                            fontFamily = font, modifier = Modifier.weight(1f))
                        IconButton(onClick = { actions.onShuffle(visible.flatMap { it.songs }.distinctBy { it.uri }) },
                            enabled = visible.any { it.songs.isNotEmpty() }) {
                            Icon(Icons.Rounded.Shuffle, "Перемешать все")
                        }
                        Box {
                            IconButton(onClick = { showSort = true }) { Icon(Icons.Rounded.Sort, "Сортировка альбомов") }
                            DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                                AlbumSort.entries.filter { tab == 0 || it != AlbumSort.Artist }.forEach { option ->
                                    DropdownMenuItem(text = { Text(option.label, fontFamily = font) },
                                        leadingIcon = { if (sort == option) Icon(Icons.Rounded.Check, null) },
                                        onClick = { onSort(option); showSort = false })
                                }
                            }
                        }
                        IconButton(onClick = onListMode) {
                            Icon(if (listMode) Icons.Rounded.GridView else Icons.Rounded.ViewList,
                                if (listMode) "Показать сеткой" else "Показать списком")
                        }
                    }
                }
                if (visible.isEmpty()) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        AlbumEmptyState(
                            title = when { query.isNotBlank() -> "Ничего не найдено"; tab == 1 -> "Музыка под ваше настроение"; else -> "Здесь будут ваши альбомы" },
                            message = when {
                                query.isNotBlank() -> "Попробуйте другое название или имя исполнителя."
                                tab == 1 -> "Соберите любимые треки в плейлист — для дороги, отдыха или нового дня."
                                else -> "Добавьте музыку в медиатеку. Треки с названием альбома появятся здесь автоматически."
                            },
                            icon = if (tab == 1) Icons.Rounded.QueueMusic else Icons.Rounded.Album,
                            actionLabel = if (query.isNotBlank()) "Сбросить поиск" else if (tab == 1) "Создать плейлист" else null,
                            onAction = { if (query.isNotBlank()) onQuery("") else actions.onCreate() },
                        )
                    }
                }
                items(visible, key = { it.key }) { album ->
                    AlbumTile(album, listMode, actions, animatedScope, Modifier.animateItem())
                }
            }
        }
    }
}

@Composable
internal fun AlbumQueryField(
    query: String, onQuery: (String) -> Unit, placeholder: String, isActive: Boolean,
    modifier: Modifier = Modifier, tag: String,
) {
    val requester = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (isActive) requester.requestFocus() }
    LaunchedEffect(isActive, focused) {
        if (!isActive && focused) { focus.clearFocus(); keyboard?.hide() }
    }
    SearchBar(
        modifier = modifier.fillMaxWidth(),
        inputField = {
            SearchBarDefaults.InputField(query = query, onQueryChange = onQuery,
                onSearch = { focus.clearFocus(); keyboard?.hide() }, expanded = false, onExpandedChange = {},
                modifier = Modifier.focusRequester(requester).onFocusChanged { focused = it.hasFocus }.testTag(tag),
                placeholder = { Text(placeholder, fontFamily = LocalAppFontFamily.current, maxLines = 1,
                    overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Rounded.Close, "Очистить фильтр")
                } })
        }, expanded = false, onExpandedChange = {}, windowInsets = WindowInsets(0, 0, 0, 0),
        colors = SearchBarDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        tonalElevation = 0.dp, shadowElevation = 0.dp,
    ) {}
}
