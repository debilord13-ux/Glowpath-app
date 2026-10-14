package com.musicplayer.ui.screens

import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.musicplayer.data.AppTheme
import com.musicplayer.data.PlayerSettings
import com.musicplayer.data.RepeatMode
import com.musicplayer.ui.theme.*
import com.musicplayer.viewmodel.MusicViewModel
import com.musicplayer.viewmodel.SleepTimerState
import androidx.compose.ui.platform.LocalContext
import java.io.File

private data class ThemeGridEntry(
    val theme: AppTheme,
    val label: String,
    val previewStart: Color,
    val previewEnd: Color,
    val swatches: List<Color>
)

private fun themeGridEntry(
    theme: AppTheme,
    label: String
): ThemeGridEntry {
    val palette = appColorsForTheme(theme)
    return ThemeGridEntry(
        theme = theme,
        label = label,
        previewStart = palette.accent,
        previewEnd = palette.accentVar,
        swatches = listOf(
            palette.bgDeep,
            palette.bgElevated,
            palette.accent,
            palette.accentVar
        ).distinct().take(4)
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MusicViewModel,
    onBack: () -> Unit,
    onTransitionsClick: () -> Unit = {},
    onAnimationsClick: () -> Unit = {},
    onSleepTimerClick: () -> Unit = {},
    onStatsClick: () -> Unit = {},
    onEqualizerClick: () -> Unit = {},
    onTopBarClick: () -> Unit = {},
    onFontClick: () -> Unit = {},
    onAboutClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val sleepTimerState by viewModel.sleepTimerState.collectAsState()
    var showThemeDialog by remember { mutableStateOf(false) }
    var showSortSheet by rememberSaveable { mutableStateOf(false) }
    var settingsQuery by rememberSaveable { mutableStateOf("") }
    val selectedBuiltInFont = remember(settings.selectedFontId) {
        AppFontList.firstOrNull { it.id == settings.selectedFontId } ?: DefaultAppFont
    }

    // Displayed font label
    val fontLabel = when {
        settings.customFontUri.isNotBlank() ->
            File(settings.customFontUri).name.substringBeforeLast('.')
        settings.selectedFontId == DefaultAppFontId -> "По умолчанию (${DefaultAppFont.displayName})"
        else -> selectedBuiltInFont.displayName
    }
    val themeLabel = settings.theme.settingsLabel()
    val sleepTimerLabel = when {
        sleepTimerState.isRunning && sleepTimerState.stopAfterTrack && sleepTimerState.remainingSeconds <= 0 ->
            "После текущего трека"
        sleepTimerState.isRunning ->
            "Осталось ${formatSleepTimerDuration(sleepTimerState.remainingSeconds)}"
        else -> "Таймер сна, затухание и действие по завершении"
    }
    val appVersion = remember(context) { readAppBuildInfo(context).versionName }
    val openFontPicker = onFontClick
    val openAbout = onAboutClick
    val appearanceSwatches = remember(settings.theme) {
        appColorsForTheme(settings.theme).let {
            listOf(it.bgElevated, it.accent, it.accentVar, it.accentMuted)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = {
                    Text(
                        "Настройки",
                        fontFamily = LocalAppFontFamily.current,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 22.sp
                    )
                },
                actions = {
                    IconButton(
                        onClick = { showThemeDialog = true },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Rounded.Palette, contentDescription = "Открыть темы")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            OutlinedTextField(
                value = settingsQuery,
                onValueChange = { settingsQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Поиск настроек", fontFamily = LocalAppFontFamily.current) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (settingsQuery.isNotEmpty()) {
                        IconButton(onClick = { settingsQuery = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Очистить поиск")
                        }
                    }
                },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = MaterialTheme.colorScheme.primary
                )
            )

            if (settingsQuery.isNotBlank()) {
                SettingsSearchResults(
                    query = settingsQuery,
                    onTheme = { showThemeDialog = true },
                    onFont = openFontPicker,
                    onTopBar = onTopBarClick,
                    onAnimations = onAnimationsClick,
                    onTransitions = onTransitionsClick,
                    onSleepTimer = onSleepTimerClick,
                    onEqualizer = onEqualizerClick,
                    onStats = onStatsClick,
                    onAbout = openAbout,
                    onSort = { showSortSheet = true },
                    onShuffle = { viewModel.updateSettings(settings.copy(shuffleEnabled = !settings.shuffleEnabled)) },
                    onWavySeekBar = { viewModel.updateSettings(settings.copy(useWavySeekBar = !settings.useWavySeekBar)) },
                    onColoredButtons = { viewModel.updateSettings(settings.copy(coloredPlayerButtons = !settings.coloredPlayerButtons)) }
                )
            }

            SettingsSectionBlock(
                title = "Персонализация",
                subtitle = "Тема, шрифт и верхняя панель",
                visible = settingsQuery.isBlank()
            ) {
                SettingsNavigationItem(
                    icon = Icons.Rounded.Palette,
                    iconBg = MaterialTheme.colorScheme.primary,
                    title = "Внешний вид",
                    subtitle = "Текущая тема: $themeLabel",
                    onClick = { showThemeDialog = true },
                    trailingContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                appearanceSwatches.forEach { swatch ->
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(swatch)
                                            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                    )
                                }
                            }
                            Icon(
                                Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                )
                SettingsGroupDivider()
                SettingsNavigationItem(
                    icon = Icons.Rounded.TextFields,
                    iconBg = MaterialTheme.colorScheme.secondary,
                    title = "Шрифт",
                    subtitle = fontLabel,
                    onClick = openFontPicker
                )
                SettingsGroupDivider()
                SettingsNavigationItem(
                    icon = Icons.Rounded.Tune,
                    iconBg = MaterialTheme.colorScheme.primary,
                    title = "Верхняя панель",
                    subtitle = "Стиль, фон и поведение шапки",
                    onClick = onTopBarClick
                )
            }

            SettingsSectionBlock(
                title = "Движение и атмосфера",
                subtitle = "Анимации, переходы и фоновые эффекты",
                visible = settingsQuery.isBlank()
            ) {
                SettingsNavigationItem(
                    icon = Icons.Rounded.AutoAwesome,
                    iconBg = MaterialTheme.colorScheme.primary,
                    title = "Анимации",
                    subtitle = "Плавность, ритм и поведение элементов",
                    onClick = onAnimationsClick
                )
                SettingsGroupDivider()
                SettingsNavigationItem(
                    icon = Icons.Rounded.SwapHoriz,
                    iconBg = MaterialTheme.colorScheme.secondary,
                    title = "Переходы",
                    subtitle = "Кроссфейд и длительность смены треков",
                    onClick = onTransitionsClick
                )
            }

            SettingsSectionBlock(
                title = "Воспроизведение",
                subtitle = "Поведение плеера, сортировка и звук",
                visible = settingsQuery.isBlank()
            ) {
                SettingsSwitchItem(
                    icon = Icons.Rounded.Shuffle,
                    iconBg = MaterialTheme.colorScheme.secondary,
                    title = "Перемешивание",
                    subtitle = "Случайный порядок треков",
                    checked = settings.shuffleEnabled,
                    onCheckedChange = {
                        viewModel.updateSettings(settings.copy(shuffleEnabled = it))
                    }
                )
                SettingsGroupDivider()
                SettingsSwitchItem(
                    icon = Icons.Rounded.ShowChart,
                    iconBg = MaterialTheme.colorScheme.primary,
                    title = "Волнистый ползунок",
                    subtitle = if (settings.useWavySeekBar) {
                        "В плеере используется волнистый анимированный ползунок"
                    } else {
                        "В плеере используется обычный ползунок"
                    },
                    checked = settings.useWavySeekBar,
                    onCheckedChange = {
                        viewModel.updateSettings(settings.copy(useWavySeekBar = it))
                    }
                )
                SettingsGroupDivider()
                SettingsSwitchItem(
                    icon = Icons.Rounded.Colorize,
                    iconBg = MaterialTheme.colorScheme.tertiary,
                    title = "Кнопки в цвет трека",
                    subtitle = if (settings.coloredPlayerButtons) {
                        "Кнопки плеера перекрашиваются в цвета текущей обложки"
                    } else {
                        "Кнопки плеера используют цвета темы"
                    },
                    checked = settings.coloredPlayerButtons,
                    onCheckedChange = {
                        viewModel.updateSettings(settings.copy(coloredPlayerButtons = it))
                    }
                )
                SettingsGroupDivider()
                SettingsNavigationItem(
                    icon = Icons.Rounded.Bedtime,
                    iconBg = MaterialTheme.colorScheme.primary,
                    title = "Таймер сна",
                    subtitle = sleepTimerLabel,
                    onClick = onSleepTimerClick
                )
                SettingsGroupDivider()
                SettingsSortItem(
                    sortOrder = settings.sortOrder,
                    onClick = { showSortSheet = true }
                )
                SettingsGroupDivider()
                SettingsNavigationItem(
                    icon = Icons.Rounded.GraphicEq,
                    iconBg = MaterialTheme.colorScheme.tertiary,
                    title = "Эквалайзер",
                    subtitle = "Пресеты и настройка звучания",
                    onClick = onEqualizerClick
                )
            }

            SettingsSectionBlock(
                title = "Сервис и информация",
                subtitle = "Статистика и сведения о приложении",
                visible = settingsQuery.isBlank()
            ) {
                SettingsNavigationItem(
                    icon = Icons.Rounded.BarChart,
                    iconBg = MaterialTheme.colorScheme.secondary,
                    title = "Статистика",
                    subtitle = "История прослушивания и показатели библиотеки",
                    onClick = onStatsClick
                )
                SettingsGroupDivider()
                SettingsNavigationItem(
                    icon = Icons.Rounded.Info,
                    iconBg = MaterialTheme.colorScheme.tertiary,
                    title = "О приложении",
                    subtitle = "Glowpath · v$appVersion",
                    onClick = openAbout
                )
                if (settings.customFontUri.isNotBlank()) {
                    SettingsGroupDivider()
                    SettingsNavigationItem(
                        icon = Icons.Rounded.RestartAlt,
                        iconBg = MaterialTheme.colorScheme.error,
                        title = "Сбросить шрифт",
                        subtitle = "Вернуть ${DefaultAppFont.displayName} по умолчанию",
                        onClick = {
                            viewModel.updateSettings(
                                settings.copy(
                                    customFontUri = "",
                                    selectedFontId = DefaultAppFontId
                                )
                            )
                        }
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showSortSheet) {
        LibrarySortSheet(
            sortOrder = settings.sortOrder,
            onSortChange = { viewModel.updateSettings(settings.copy(sortOrder = it)) },
            onDismiss = { showSortSheet = false },
        )
    }

    if (showThemeDialog) {
        ThemePickerDialog(
            currentTheme    = settings.theme,
            onThemeSelected = {
                viewModel.updateSettings(
                    settings.copy(
                        theme = it,
                        useDynamicColors = it == AppTheme.MATERIAL_YOU
                    )
                )
            },
            onDismiss = { showThemeDialog = false }
        )
    }



}

@Composable
private fun SettingsSearchResults(
    query: String,
    onTheme: () -> Unit,
    onFont: () -> Unit,
    onTopBar: () -> Unit,
    onAnimations: () -> Unit,
    onTransitions: () -> Unit,
    onSleepTimer: () -> Unit,
    onEqualizer: () -> Unit,
    onStats: () -> Unit,
    onAbout: () -> Unit,
    onSort: () -> Unit,
    onShuffle: () -> Unit,
    onWavySeekBar: () -> Unit,
    onColoredButtons: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val entries = listOf(
        Triple("Внешний вид", "Тема и цветовая схема", Icons.Rounded.Palette) to onTheme,
        Triple("Шрифт", "Выбор шрифта приложения", Icons.Rounded.TextFields) to onFont,
        Triple("Верхняя панель", "Стиль, фон и поведение шапки", Icons.Rounded.Tune) to onTopBar,
        Triple("Анимации", "Плавность и поведение элементов", Icons.Rounded.AutoAwesome) to onAnimations,
        Triple("Переходы", "Кроссфейд и смена треков", Icons.Rounded.SwapHoriz) to onTransitions,
        Triple("Таймер сна", "Остановка после трека и затухание", Icons.Rounded.Bedtime) to onSleepTimer,
        Triple("Эквалайзер", "Пресеты и настройка звучания", Icons.Rounded.GraphicEq) to onEqualizer,
        Triple("Сортировка", "Название, исполнитель, альбом, длительность и дата добавления", Icons.Rounded.Sort) to onSort,
        Triple("Перемешивание", "Случайный порядок треков", Icons.Rounded.Shuffle) to onShuffle,
        Triple("Волнистый ползунок", "Анимированный seek bar в плеере", Icons.Rounded.ShowChart) to onWavySeekBar,
        Triple("Кнопки в цвет трека", "Раскраска кнопок плеера под обложку", Icons.Rounded.Colorize) to onColoredButtons,
        Triple("Статистика", "История прослушивания", Icons.Rounded.BarChart) to onStats,
        Triple("О приложении", "Glowpath и версия приложения", Icons.Rounded.Info) to onAbout
    )
    val needle = query.trim().lowercase()
    val matches = entries.filter { (entry, _) ->
        entry.first.lowercase().contains(needle) || entry.second.lowercase().contains(needle)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Результаты поиска",
            color = colors.primary,
            fontFamily = LocalAppFontFamily.current,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        if (matches.isEmpty()) {
            Text(
                text = "Ничего не найдено",
                color = colors.onSurfaceVariant,
                fontFamily = LocalAppFontFamily.current,
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        } else {
            matches.forEach { (entry, action) ->
                SettingsNavigationItem(
                    icon = entry.third,
                    iconBg = colors.primary,
                    title = entry.first,
                    subtitle = entry.second,
                    onClick = action
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionBlock(
    title: String,
    subtitle: String,
    visible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val font = LocalAppFontFamily.current
    if (!visible) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                color = colors.primary,
                fontFamily = font,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                color = colors.onSurfaceVariant,
                fontFamily = font,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
        SettingsSection(content = content)
    }
}

@Composable
private fun SettingsGroupDivider() {
    Spacer(Modifier.height(2.dp))
}

@Composable
fun ThemePickerDialog(
    currentTheme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val font = LocalAppFontFamily.current

    val sections = remember {
        listOf(
            "Классика" to listOf(
                AppTheme.BLOOMEE, AppTheme.DARK_BROWN, AppTheme.DARK_BLACK, AppTheme.LIGHT,
                AppTheme.MIDNIGHT, AppTheme.GRAPHITE, AppTheme.AMOLED, AppTheme.STEEL
            ),
            "Тёплые" to listOf(
                AppTheme.SUNSET, AppTheme.SUNRISE, AppTheme.AMBER, AppTheme.EMBER,
                AppTheme.ROSE_GOLD, AppTheme.RUBY, AppTheme.WINE, AppTheme.CHERRY,
                AppTheme.MOCHA, AppTheme.BRONZE, AppTheme.DESERT, AppTheme.VOLCANO, AppTheme.IVORY
            ),
            "Свежие" to listOf(
                AppTheme.OCEAN, AppTheme.ARCTIC, AppTheme.FROST, AppTheme.LAGOON,
                AppTheme.TURQUOISE, AppTheme.MINT, AppTheme.MATCHA, AppTheme.EMERALD,
                AppTheme.FOREST, AppTheme.OLIVE, AppTheme.CITRUS, AppTheme.AURORA
            ),
            "Яркие" to listOf(
                AppTheme.PURPLE, AppTheme.PINK, AppTheme.SAKURA, AppTheme.LAVENDER,
                AppTheme.PLUM, AppTheme.COSMOS, AppTheme.NEON, AppTheme.COBALT,
                AppTheme.SAPPHIRE, AppTheme.PEARL
            )
        ).map { (title, list) -> title to list.map { themeGridEntry(it, it.settingsLabel()) } }
    }
    val previewEntry = remember(currentTheme, sections) {
        sections.flatMap { it.second }.firstOrNull { it.theme == currentTheme }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .systemBarsPadding()
                .padding(horizontal = 10.dp, vertical = 20.dp),
            shape = RoundedCornerShape(28.dp),
            color = colors.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(46.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = colors.primaryContainer
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.Palette,
                                contentDescription = null,
                                tint = colors.onPrimaryContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Внешний вид",
                            color = colors.onSurface,
                            fontFamily = font,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Text(
                            "Готовые палитры и системные цвета Android",
                            color = colors.onSurfaceVariant,
                            fontFamily = font,
                            fontSize = 12.sp
                        )
                    }
                    FilledIconButton(
                        onClick = onDismiss,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = colors.surfaceContainerHigh,
                            contentColor = colors.onSurface
                        )
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "Закрыть", modifier = Modifier.size(18.dp))
                    }
                }

                ThemePickerSummaryCard(
                    currentTheme = currentTheme,
                    previewEntry = previewEntry,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )

                MaterialYouHeroCard(
                    selected = currentTheme == AppTheme.MATERIAL_YOU,
                    supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                    modifier = Modifier.padding(horizontal = 20.dp),
                    onClick = { onThemeSelected(AppTheme.MATERIAL_YOU) }
                )

                HorizontalDivider(
                    color = colors.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 20.dp)
                )

                Text(
                    "Все палитры",
                    color = colors.onSurfaceVariant,
                    fontFamily = font,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    sections.forEach { (title, entries) ->
                        item(key = title) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        title,
                                        color = colors.primary,
                                        fontFamily = font,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "тем: ${entries.size}",
                                        color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                                        fontFamily = font,
                                        fontSize = 11.sp
                                    )
                                }
                                entries.chunked(2).forEach { rowEntries ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        rowEntries.forEach { entry ->
                                            ThemeGridCard(
                                                modifier = Modifier.weight(1f),
                                                entry = entry,
                                                selected = currentTheme == entry.theme,
                                                onClick = { onThemeSelected(entry.theme) }
                                            )
                                        }
                                        if (rowEntries.size == 1) {
                                            Spacer(Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ThemePickerSummaryCard(
    currentTheme: AppTheme,
    previewEntry: ThemeGridEntry?,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val font = LocalAppFontFamily.current
    val previewStart by animateColorAsState(
        targetValue = when (currentTheme) {
            AppTheme.MATERIAL_YOU -> Color(0xFF8CC8FF)
            else -> previewEntry?.previewStart ?: colors.primary
        },
        animationSpec = tween(460),
        label = "themePreviewStart"
    )
    val previewEnd by animateColorAsState(
        targetValue = when (currentTheme) {
            AppTheme.MATERIAL_YOU -> Color(0xFFFFB5D8)
            else -> previewEntry?.previewEnd ?: colors.tertiary
        },
        animationSpec = tween(520),
        label = "themePreviewEnd"
    )
    val previewAccent by animateColorAsState(
        targetValue = lerp(previewStart, previewEnd, 0.38f),
        animationSpec = tween(520),
        label = "themePreviewAccent"
    )
    val previewSurface by animateColorAsState(
        targetValue = lerp(colors.surfaceContainerLow, previewStart, 0.14f),
        animationSpec = tween(420),
        label = "themePreviewSurface"
    )
    val previewSurfaceHigh by animateColorAsState(
        targetValue = lerp(colors.surfaceContainerHigh, previewEnd, 0.12f),
        animationSpec = tween(500),
        label = "themePreviewSurfaceHigh"
    )
    val badgeLabel = when (currentTheme) {
        AppTheme.MATERIAL_YOU -> "Система"
        else -> "Готовая"
    }
    val subtitle = when (currentTheme) {
        AppTheme.MATERIAL_YOU -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "Палитра берётся из обоев и системных тонов Android, поэтому ощущается как родная часть системы"
        } else {
            "На Android 12+ эта тема станет динамической и будет меняться от обоев, а ниже мягко откатится к нейтральной палитре"
        }
        else -> "Готовая палитра с мягкой анимацией смены цветов, поверхностей и акцентов"
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.64f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            previewSurface.copy(alpha = 0.98f),
                            previewSurfaceHigh.copy(alpha = 0.98f),
                            colors.surfaceContainerLow
                        )
                    )
                )
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = previewAccent.copy(alpha = 0.18f)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            if (currentTheme == AppTheme.MATERIAL_YOU) Icons.Rounded.Wallpaper else Icons.Rounded.AutoAwesome,
                            null,
                            tint = previewAccent,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Активная тема",
                        color = colors.onSurfaceVariant,
                        fontFamily = font,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp
                    )
                    AnimatedContent(
                        targetState = currentTheme.settingsLabel(),
                        transitionSpec = {
                            (fadeIn(tween(220)) + slideInVertically { it / 4 }) togetherWith
                                (fadeOut(tween(160)) + slideOutVertically { -it / 4 })
                        },
                        label = "themeTitle"
                    ) { label ->
                        Text(
                            label,
                            color = colors.onSurface,
                            fontFamily = font,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = previewAccent.copy(alpha = 0.16f)
                ) {
                    Text(
                        badgeLabel,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = previewAccent,
                        fontFamily = font,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp
                    )
                }
            }

            Text(
                subtitle,
                color = colors.onSurfaceVariant,
                fontFamily = font,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = previewSurfaceHigh.copy(alpha = 0.92f),
                border = androidx.compose.foundation.BorderStroke(1.dp, previewAccent.copy(alpha = 0.24f))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Preview Player",
                                color = colors.onSurface,
                                fontFamily = font,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Показывает, как тема ведёт себя на поверхности, кнопке и прогрессе",
                                color = colors.onSurfaceVariant,
                                fontFamily = font,
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(previewStart, previewAccent, previewEnd).forEach { swatch ->
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(swatch)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(54.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = previewStart.copy(alpha = 0.18f)
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Album, null, tint = previewStart, modifier = Modifier.size(24.dp))
                            }
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "Название трека",
                                color = colors.onSurface,
                                fontFamily = font,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(previewEnd.copy(alpha = 0.14f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.46f)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(previewStart, previewAccent, previewEnd)
                                            )
                                        )
                                )
                            }
                        }
                        Surface(
                            modifier = Modifier.size(42.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = previewAccent
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Rounded.PlayArrow,
                                    null,
                                    tint = if (previewAccent.luminance() > 0.52f) Color.Black else Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemePickerSectionLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = LocalAppFontFamily.current,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun MaterialYouHeroCard(
    selected: Boolean,
    supported: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val font = LocalAppFontFamily.current
    val containerColor by animateColorAsState(
        targetValue = if (selected) colors.secondaryContainer else colors.surfaceContainerHigh,
        animationSpec = tween(220),
        label = "materialYouCard"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) colors.secondary else colors.outlineVariant,
        animationSpec = tween(220),
        label = "materialYouBorder"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Box(
            modifier = Modifier.background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF7DCBFF).copy(alpha = 0.14f),
                        Color(0xFFFFB5D8).copy(alpha = 0.12f),
                        Color(0xFFA8F0CC).copy(alpha = 0.12f)
                    )
                )
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF8CC8FF).copy(alpha = 0.16f)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Wallpaper, null, tint = Color(0xFF5B8DFF), modifier = Modifier.size(22.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Material You",
                            color = if (selected) colors.onSecondaryContainer else colors.onSurface,
                            fontFamily = font,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (selected) colors.secondary else colors.surfaceVariant
                        ) {
                            Text(
                                if (supported) "Adaptive" else "Android 12+",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                color = if (selected) colors.onSecondary else colors.onSurfaceVariant,
                                fontFamily = font,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 10.sp
                            )
                        }
                    }
                    Text(
                        if (supported) {
                            "Цвета подтягиваются из обоев и системной палитры, поэтому приложение выглядит по-настоящему системно"
                        } else {
                            "На Android 12+ эта тема станет динамической; ниже мягко откатится к спокойной нейтральной палитре"
                        },
                        color = if (selected) colors.onSecondaryContainer.copy(alpha = 0.82f) else colors.onSurfaceVariant,
                        fontFamily = font,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeGridCard(
    modifier: Modifier = Modifier,
    entry: ThemeGridEntry,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val font = LocalAppFontFamily.current
    val previewStart by animateColorAsState(
        targetValue = entry.previewStart,
        animationSpec = tween(220),
        label = "themeCardPreviewStart"
    )
    val previewEnd by animateColorAsState(
        targetValue = entry.previewEnd,
        animationSpec = tween(220),
        label = "themeCardPreviewEnd"
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.02f else 1f,
        animationSpec = tween(220),
        label = "themeGridScale"
    )
    val containerColor by animateColorAsState(
        targetValue = if (selected) colors.secondaryContainer else colors.surfaceContainerHigh,
        animationSpec = tween(220),
        label = "themeGridContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) colors.secondary else colors.outlineVariant,
        animationSpec = tween(220),
        label = "themeGridBorder"
    )
    Card(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            borderColor
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                previewStart.copy(alpha = 0.92f),
                                lerp(previewStart, previewEnd, 0.45f).copy(alpha = 0.82f),
                                previewEnd.copy(alpha = 0.94f)
                            )
                        )
                    )
            ) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .size(22.dp),
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.18f)
                ) {}
                if (selected) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        null,
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .size(18.dp)
                    )
                }
            }
            Text(
                entry.label,
                color = if (selected) colors.onSecondaryContainer else colors.onSurface,
                fontFamily = font,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                entry.swatches.take(4).forEach { swatch ->
                    Surface(
                        modifier = Modifier.size(14.dp),
                        shape = CircleShape,
                        color = swatch,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            colors.onSurface.copy(alpha = 0.08f)
                        )
                    ) {}
                }
            }
            Text(
                if (selected) "Активная тема" else "Основные цвета палитры",
                color = if (selected) colors.onSecondaryContainer.copy(alpha = 0.76f) else colors.onSurfaceVariant,
                fontFamily = font,
                fontSize = 11.sp
            )
        }
    }
}

private fun AppTheme.settingsLabel(): String = when (this) {
    AppTheme.BLOOMEE -> "Малиновый"
    AppTheme.MATERIAL_YOU -> "Системные цвета"
    AppTheme.DARK_BROWN -> "Шоколадный"
    AppTheme.DARK_BLACK -> "Антрацитовый"
    AppTheme.LIGHT -> "Молочный"
    AppTheme.PURPLE -> "Аметистовый"
    AppTheme.PINK -> "Пудровый"
    AppTheme.OCEAN -> "Лазурный"
    AppTheme.FOREST -> "Хвойно-зелёный"
    AppTheme.SUNSET -> "Коралловый"
    AppTheme.MIDNIGHT -> "Индиго"
    AppTheme.NEON -> "Мятно-неоновый"
    AppTheme.ROSE_GOLD -> "Розовое золото"
    AppTheme.ARCTIC -> "Ледяной голубой"
    AppTheme.AMBER -> "Янтарный"
    AppTheme.EMERALD -> "Изумрудный"
    AppTheme.AMOLED -> "Угольный"
    AppTheme.LAVENDER -> "Лавандовый"
    AppTheme.RUBY -> "Рубиновый"
    AppTheme.STEEL -> "Стальной"
    AppTheme.MATCHA -> "Фисташковый"
    AppTheme.DESERT -> "Песочный"
    AppTheme.COBALT -> "Кобальтовый"
    AppTheme.CHERRY -> "Вишнёвый"
    AppTheme.MOCHA -> "Кофейный"
    AppTheme.AURORA -> "Мятно-бирюзовый"
    AppTheme.COSMOS -> "Орхидейный"
    AppTheme.SUNRISE -> "Абрикосовый"
    AppTheme.GRAPHITE -> "Графитовый"
    AppTheme.SAKURA -> "Нежно-розовый"
    AppTheme.LAGOON -> "Аквамариновый"
    AppTheme.VOLCANO -> "Терракотовый"
    AppTheme.IVORY -> "Слоновая кость"
    AppTheme.CITRUS -> "Лимонный"
    AppTheme.FROST -> "Морозный циан"
    AppTheme.EMBER -> "Медный"
    AppTheme.TURQUOISE -> "Бирюзовый"
    AppTheme.PLUM -> "Сливовый"
    AppTheme.PEARL -> "Жемчужный"
    AppTheme.OLIVE -> "Оливковый"
    AppTheme.SAPPHIRE -> "Сапфировый"
    AppTheme.MINT -> "Мятный"
    AppTheme.BRONZE -> "Бронзовый"
    AppTheme.WINE -> "Винный"
}

@Composable
private fun SettingsHeroBanner(
    themeLabel: String,
    fontLabel: String,
    interfaceLabel: String
) {
    val colors = MaterialTheme.colorScheme
    val font = LocalAppFontFamily.current

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = colors.surfaceContainerLow,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    modifier = Modifier.size(56.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = colors.secondaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Rounded.Tune,
                            contentDescription = null,
                            tint = colors.onSecondaryContainer,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Настройки", color = colors.onSurface, fontFamily = font, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                    Text(
                        "Тема, шрифт, анимации и звук в едином Material 3 интерфейсе",
                        color = colors.onSurfaceVariant,
                        fontFamily = font,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SettingsInfoPill(
                    icon = Icons.Rounded.Palette,
                    label = "Тема",
                    value = themeLabel,
                    modifier = Modifier.weight(1f)
                )
                SettingsInfoPill(
                    icon = Icons.Rounded.DashboardCustomize,
                    label = "Стиль UI",
                    value = interfaceLabel,
                    modifier = Modifier.weight(1f)
                )
            }
            SettingsInfoPill(
                icon = Icons.Rounded.TextFields,
                label = "Шрифт",
                value = fontLabel,
                modifier = Modifier.fillMaxWidth()
            )

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = colors.surfaceContainerHighest
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        "Интерфейс приложения собран вокруг Material 3, а тема и шрифт настраиваются отдельно",
                        color = colors.onSurfaceVariant,
                        fontFamily = font,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsInfoPill(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val font = LocalAppFontFamily.current

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = colors.surfaceContainerHighest
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(15.dp))
                Text(label, color = colors.onSurfaceVariant, fontFamily = font, fontSize = 11.sp)
            }
            Text(value, color = colors.onSurface, fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
private fun SettingsQuickLinkCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    accent: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val font = LocalAppFontFamily.current

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainerLow,
        tonalElevation = 1.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = colors.onSurface, fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(subtitle, color = colors.onSurfaceVariant, fontFamily = font, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
    }
}

@Composable
fun SettingsSection(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content
    )
}

@Composable
private fun SettingsLeadingIcon(
    icon: ImageVector,
    tint: Color
) {
    Surface(
        modifier = Modifier.size(56.dp),
        shape = RoundedCornerShape(18.dp),
        color = tint.copy(alpha = 0.18f)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun SettingsLeadingIcon(
    iconPainter: Painter,
    tint: Color
) {
    Surface(
        modifier = Modifier.size(56.dp),
        shape = RoundedCornerShape(18.dp),
        color = tint.copy(alpha = 0.18f)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(iconPainter, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun SettingsChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val font = LocalAppFontFamily.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) colors.secondaryContainer else colors.surfaceContainerHighest
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = colors.primary,
                    unselectedColor = colors.onSurfaceVariant
                )
            )
            Text(
                text = label,
                color = if (selected) colors.onSecondaryContainer else colors.onSurface,
                fontFamily = font,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
    }
}

@Composable
fun SettingsNavigationItem(
    icon: ImageVector,
    iconBg: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val font = LocalAppFontFamily.current
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        headlineContent = {
            Text(title, color = colors.onSurface, fontFamily = font, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        },
        supportingContent = {
            Text(subtitle, color = colors.onSurfaceVariant, fontFamily = font, fontSize = 12.sp, lineHeight = 16.sp)
        },
        leadingContent = { SettingsLeadingIcon(icon = icon, tint = iconBg) },
        trailingContent = {
            if (trailingContent != null) {
                trailingContent()
            } else {
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    )
}

@Composable
fun SettingsNavigationItem(
    iconPainter: Painter,
    iconBg: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val font = LocalAppFontFamily.current
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        headlineContent = {
            Text(title, color = colors.onSurface, fontFamily = font, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        },
        supportingContent = {
            Text(subtitle, color = colors.onSurfaceVariant, fontFamily = font, fontSize = 12.sp, lineHeight = 16.sp)
        },
        leadingContent = { SettingsLeadingIcon(iconPainter = iconPainter, tint = iconBg) },
        trailingContent = {
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    )
}

@Composable
fun SettingsSwitchItem(
    icon: ImageVector,
    iconBg: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val font = LocalAppFontFamily.current
    val headlineColor = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.5f)
    val supportingColor = if (enabled) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(alpha = 0.55f)
    val iconTint = if (enabled) iconBg else iconBg.copy(alpha = 0.48f)
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        headlineContent = {
            Text(title, color = headlineColor, fontFamily = font, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        },
        supportingContent = {
            Text(subtitle, color = supportingColor, fontFamily = font, fontSize = 12.sp, lineHeight = 16.sp)
        },
        leadingContent = { SettingsLeadingIcon(icon = icon, tint = iconTint) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onPrimary,
                    checkedTrackColor = colors.primary,
                    uncheckedThumbColor = colors.surface,
                    uncheckedTrackColor = colors.outlineVariant,
                    uncheckedBorderColor = colors.outline
                )
            )
        }
    )
}

@Composable
fun SettingsRepeatItem(settings: PlayerSettings, onRepeatChange: (RepeatMode) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val font = LocalAppFontFamily.current
    val options = listOf(
        RepeatMode.NONE to "Без повтора",
        RepeatMode.ALL  to "Повторять всё",
        RepeatMode.ONE  to "Повторять один трек"
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Режим повтора", color = colors.onSurface, fontFamily = font, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        options.forEach { (mode, label) ->
            SettingsChoiceRow(label = label, selected = settings.repeatMode == mode) {
                onRepeatChange(mode)
            }
        }
    }
}
