package com.musicplayer.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.musicplayer.bridge.ExteraGramBridgeBatchItem
import com.musicplayer.bridge.ExteraGramBridgeContract
import com.musicplayer.bridge.ExteraGramBridgeStateStore
import com.musicplayer.data.PlayerSettings
import com.musicplayer.data.PreferencesManager
import com.musicplayer.data.RepeatMode
import com.musicplayer.data.Song
import com.musicplayer.data.TransitionMode
import com.musicplayer.data.TransitionSettings
import com.musicplayer.data.UserAlbum
import com.musicplayer.data.lyrics.LyricsRepository
import com.musicplayer.data.lyrics.LyricsState
import com.musicplayer.data.lyrics.SyncedLine
import com.musicplayer.data.player.DualPlayerEngine
import com.musicplayer.data.player.TrackPreloader
import com.musicplayer.data.player.TransitionController
import com.musicplayer.data.stats.PlaybackStatsRepository
import com.musicplayer.repository.MusicRepository
import com.musicplayer.service.MusicService
import com.musicplayer.utils.SongsFileStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val repository   = MusicRepository(application)
    private val prefs        = PreferencesManager(application)
    val statsRepository      = PlaybackStatsRepository(application)

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    // Debounced save job — batches rapid song-list changes into a single disk write
    private var saveSongsJob: Job? = null
    private fun debouncedSaveSongs(songs: List<Song>) {
        saveSongsJob?.cancel()
        saveSongsJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(300L)  // wait 300ms for burst to settle
            // Save to fast file storage (primary) + legacy SharedPrefs (backup compatibility)
            SongsFileStorage.save(getApplication(), songs)
            prefs.saveSongs(songs)
        }
    }

    private var saveSettingsJob: Job? = null
    private fun debouncedSaveSettings(settings: PlayerSettings) {
        saveSettingsJob?.cancel()
        saveSettingsJob = viewModelScope.launch(Dispatchers.IO) {
            delay(220L)
            prefs.saveSettings(settings)
        }
    }

    // ── Splash / startup state ────────────────────────────────────────────────
    /** true когда первичная загрузка завершена и можно показывать UI */
    private val _isAppReady = MutableStateFlow(false)
    val isAppReady: StateFlow<Boolean> = _isAppReady.asStateFlow()

    /** Сколько песен уже загружено (для прогресс-бара сплеша) */
    private val _splashLoadedCount = MutableStateFlow(0)
    val splashLoadedCount: StateFlow<Int> = _splashLoadedCount.asStateFlow()

    private val _splashTotalCount = MutableStateFlow(0)
    val splashTotalCount: StateFlow<Int> = _splashTotalCount.asStateFlow()

    // ── Background lyrics prefetch ────────────────────────────────────────────
    /** ID песен для которых тексты уже скачаны в фоне */
    private val _prefetchedLyricIds = MutableStateFlow<Set<Long>>(emptySet())

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _volume = MutableStateFlow(prefs.loadVolume())
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(prefs.loadSpeed())
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    // ── Lyrics (auto-search via LRCLIB) ──────────────────────────────────────
    private val lyricsRepository = LyricsRepository(application)

    private val _lyricsState = MutableStateFlow<LyricsState>(LyricsState.Idle)
    val lyricsState: StateFlow<LyricsState> = _lyricsState.asStateFlow()

    private var lyricsSearchJob: Job? = null

    private val _favourites = MutableStateFlow(prefs.loadFavourites())
    val favourites: StateFlow<Set<Long>> = _favourites.asStateFlow()

    private val _userAlbums = MutableStateFlow(prefs.loadUserAlbums())
    val userAlbums: StateFlow<List<UserAlbum>> = _userAlbums.asStateFlow()

    private val _settings = MutableStateFlow(prefs.loadSettings())
    val settings: StateFlow<PlayerSettings> = _settings.asStateFlow()

    // ── Custom artwork per song ───────────────────────────────────────────────
    private val _customArtMap = MutableStateFlow(prefs.loadAllCustomArts())
    val customArtMap: StateFlow<Map<Long, android.net.Uri>> = _customArtMap.asStateFlow()

    // ── Centralised palette colour cache ──────────────────────────────────────
    // Pre-loaded from artwork URIs so SongRow never does disk I/O on-screen.
    private val _songColors = MutableStateFlow<Map<Long, androidx.compose.ui.graphics.Color>>(emptyMap())
    val songColors: StateFlow<Map<Long, androidx.compose.ui.graphics.Color>> = _songColors.asStateFlow()

    /** Load palette colours for all songs that have artwork. Idempotent — skips already-cached IDs.
     *  Processes songs in batches to avoid N recompositions for large libraries. */
    fun warmPaletteCache(songs: List<com.musicplayer.data.Song>) {
        val ctx = getApplication<android.app.Application>()
        // Filter to only uncached songs with art
        val toLoad = songs.filter { song ->
            val uri = _customArtMap.value[song.id] ?: song.albumArtUri
            uri != null && !_songColors.value.containsKey(song.id)
        }
        if (toLoad.isEmpty()) return

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Process in batches of 8; pause between batches so main thread stays fluid
            toLoad.chunked(8).forEach { batch ->
                val batchColors = mutableMapOf<Long, androidx.compose.ui.graphics.Color>()
                batch.forEach { song ->
                    val uri = _customArtMap.value[song.id] ?: song.albumArtUri ?: return@forEach
                    if (_songColors.value.containsKey(song.id)) return@forEach
                    val color = com.musicplayer.data.SongColorCache.getColor(ctx, uri) ?: return@forEach
                    batchColors[song.id] = color
                }
                if (batchColors.isNotEmpty()) {
                    _songColors.value = _songColors.value + batchColors  // single emit per batch
                }
                // Yield between batches to prevent IO starvation on large libraries
                kotlinx.coroutines.delay(40L)
            }
        }
    }

    fun setCustomArt(songId: Long, uri: android.net.Uri?) {
        val updated = _customArtMap.value.toMutableMap()
        if (uri == null) {
            updated.remove(songId)
            // Invalidate cached colour so it gets re-derived from album art
            _songColors.value = _songColors.value - songId
        } else {
            try {
                getApplication<Application>().contentResolver
                    .takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            updated[songId] = uri
            com.musicplayer.data.SongColorCache.invalidate(uri)
            _songColors.value = _songColors.value - songId
            // Re-load colour for new artwork
            val song = _songs.value.firstOrNull { it.id == songId }
            if (song != null) warmPaletteCache(listOf(song))
        }
        _customArtMap.value = updated
        prefs.saveCustomArt(songId, uri?.toString())
    }

    fun getCustomArt(songId: Long): android.net.Uri? = _customArtMap.value[songId]

    fun setCustomArtForAll(uri: android.net.Uri) {
        try {
            getApplication<Application>().contentResolver
                .takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        val updated = _customArtMap.value.toMutableMap()
        _songs.value.forEach { song ->
            updated[song.id] = uri
            prefs.saveCustomArt(song.id, uri.toString())
        }
        _customArtMap.value = updated
    }

    fun resetAllCustomArt() {
        _customArtMap.value.keys.forEach { songId -> prefs.saveCustomArt(songId, null) }
        _customArtMap.value = emptyMap()
    }

    // ── Custom Title / Artist overrides ──────────────────────────────────────
    private val _customTitleMap  = MutableStateFlow(prefs.loadAllCustomTitles())
    val customTitleMap: StateFlow<Map<Long, String>> = _customTitleMap.asStateFlow()

    private val _customArtistMap = MutableStateFlow(prefs.loadAllCustomArtists())
    val customArtistMap: StateFlow<Map<Long, String>> = _customArtistMap.asStateFlow()

    fun setCustomTitle(songId: Long, title: String?) {
        val updated = _customTitleMap.value.toMutableMap()
        if (title.isNullOrBlank()) updated.remove(songId) else updated[songId] = title
        _customTitleMap.value = updated
        prefs.saveCustomTitle(songId, title)
    }

    fun setCustomArtist(songId: Long, artist: String?) {
        val updated = _customArtistMap.value.toMutableMap()
        if (artist.isNullOrBlank()) updated.remove(songId) else updated[songId] = artist
        _customArtistMap.value = updated
        prefs.saveCustomArtist(songId, artist)
    }

    // ── Orb settings ─────────────────────────────────────────────────────────
    // ── Top Bar Settings ──────────────────────────────────────────────────────
    private val _topBarSettings = MutableStateFlow(prefs.loadTopBarSettings())
    val topBarSettings: StateFlow<com.musicplayer.data.TopBarSettings> = _topBarSettings.asStateFlow()

    fun updateTopBarSettings(t: com.musicplayer.data.TopBarSettings) {
        _topBarSettings.value = t
        prefs.saveTopBarSettings(t)
    }

    // ── EQ state (saved / restored) ───────────────────────────────────────────
    private val _eqGains = MutableStateFlow(prefs.loadEqGains())
    val eqGains: StateFlow<List<Float>> = _eqGains.asStateFlow()

    private val _eqEnabled = MutableStateFlow(prefs.loadEqEnabled())
    val eqEnabled: StateFlow<Boolean> = _eqEnabled.asStateFlow()

    private val _eqPresetName = MutableStateFlow(prefs.loadEqPresetName())
    val eqPresetName: StateFlow<String> = _eqPresetName.asStateFlow()

    private val _audioOutput = MutableStateFlow(prefs.loadAudioOutput())  // "STEREO", "EARPIECE", "SPEAKER"
    val audioOutput: StateFlow<String> = _audioOutput.asStateFlow()

    private val _spatialAudio = MutableStateFlow(prefs.loadSpatialAudio())
    val spatialAudio: StateFlow<Boolean> = _spatialAudio.asStateFlow()

    // ── Pre-Amp gain (-12..+12 dB, applied as volume multiplier) ─────────────
    private val _preampGain = MutableStateFlow(prefs.loadPreAmpGain())
    val preampGain: StateFlow<Float> = _preampGain.asStateFlow()

    // ── L/R Balance (-1 = full left, 0 = center, +1 = full right) ────────────
    private val _audioBalance = MutableStateFlow(prefs.loadAudioBalance())
    val audioBalance: StateFlow<Float> = _audioBalance.asStateFlow()

    /** Equalizer Android effect (null if device doesn't support it) */
    private var androidEqualizer: android.media.audiofx.Equalizer? = null
    private var androidVirtualizer: android.media.audiofx.Virtualizer? = null
    /** Сессия, к которой привязаны эффекты. Все плееры (мастер и переходный)
     *  работают в одной сессии — эффекты не пересоздаются посреди кроссфейда
     *  и действуют на оба трека одновременно. */
    private var eqSessionId: Int = -1

    fun updateEqGains(gains: List<Float>) {
        _eqGains.value = gains
        prefs.saveEqGains(gains)
        applyEqToPlayer()
    }

    fun setEqEnabled(enabled: Boolean) {
        _eqEnabled.value = enabled
        prefs.saveEqEnabled(enabled)
        androidEqualizer?.enabled = enabled
    }

    fun setEqPresetName(name: String) {
        _eqPresetName.value = name
        prefs.saveEqPresetName(name)
    }

    fun setAudioOutput(output: String) {
        _audioOutput.value = output
        prefs.saveAudioOutput(output)
    }

    fun setSpatialAudio(enabled: Boolean) {
        _spatialAudio.value = enabled
        prefs.saveSpatialAudio(enabled)
        androidVirtualizer?.enabled = enabled
    }

    fun setPreAmpGain(gain: Float) {
        _preampGain.value = gain.coerceIn(-12f, 12f)
        prefs.savePreAmpGain(_preampGain.value)
        applyVolumeWithBalance()
    }

    fun setAudioBalance(balance: Float) {
        _audioBalance.value = balance.coerceIn(-1f, 1f)
        prefs.saveAudioBalance(_audioBalance.value)
        applyVolumeWithBalance()
    }

    /** Apply current volume + preamp + balance to the player. */
    private fun applyVolumeWithBalance() {
        val (left, right) = currentUserVolumePair()
        // Громкость применяет DualPlayerEngine: userVolume * duck * masterEnvelope.
        // Прямой setVolume на mediaPlayer убран: он игнорировал duck и огибающую
        // ручного фейда и давал «щелчок» на полный объём в середине плавного старта.
        dualEngine.setUserVolume(left, right)
    }

    /** Прямая установка громкости активному плееру (для фейдов сна/утки). */
    private fun applyPlaybackVolume(fraction: Float) {
        val (left, right) = currentUserVolumePair()
        // Плеер может быть ещё не подготовлен (prepareAsync идёт) — setVolume на
        // таком плеере кидает IllegalStateException. Например: таймер сна с
        // затуханием тикает, а пользователь в этот момент переключил трек.
        try {
            mediaPlayer?.setVolume(
                (left * fraction).coerceIn(0f, 1f),
                (right * fraction).coerceIn(0f, 1f)
            )
        } catch (_: Exception) {}
    }

    private fun applyEqToPlayer() {
        val eq = androidEqualizer ?: return
        val gains = _eqGains.value
        val numBands = eq.numberOfBands.toInt()
        gains.take(numBands).forEachIndexed { i, gain ->
            try {
                // Android EQ uses millibels (100 * dB)
                eq.setBandLevel(i.toShort(), (gain * 100).toInt().toShort())
            } catch (_: Exception) {}
        }
    }

    private fun attachEqualizerToSession(sessionId: Int) {
        // Эффекты уже висят на этой сессии — просто переприменяем уровни:
        // пересоздание посреди кроссфейда звучало бы как «сброс на базовые настройки».
        if (androidEqualizer != null && eqSessionId == sessionId) {
            applyEqToPlayer()
            try { androidEqualizer?.enabled = _eqEnabled.value } catch (_: Exception) {}
            try { androidVirtualizer?.enabled = _spatialAudio.value } catch (_: Exception) {}
            return
        }

        // Release old instances
        androidEqualizer?.release()
        androidVirtualizer?.release()
        androidEqualizer = null
        androidVirtualizer = null

        try {
            androidEqualizer = android.media.audiofx.Equalizer(0, sessionId).apply {
                enabled = _eqEnabled.value
            }
            applyEqToPlayer()
        } catch (_: Exception) {}

        try {
            androidVirtualizer = android.media.audiofx.Virtualizer(0, sessionId).apply {
                enabled = _spatialAudio.value
            }
        } catch (_: Exception) {}

        eqSessionId = sessionId
    }

    private val _bridgeQueueUris = MutableStateFlow<List<String>>(emptyList())
    val bridgeQueueUris: StateFlow<List<String>> = _bridgeQueueUris.asStateFlow()
    private var bridgeLastBatchSummary: String = ""

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private var musicService: MusicService? = null

    // Поколение воспроизведения — растёт на каждой команде переключения трека.
    // Все асинхронные колбэки (onPrepared/onError) и фейды проверяют его, чтобы
    // отработать только для актуального плеера (защита от двойного звука).
    private var playbackGeneration = 0
    /** Активный фейд переключения (затухание старой / нарастание новой). */
    private var switchFadeJob: Job? = null
    /**
     * Трек, к которому УЖЕ началось, но ещё не завершилось переключение
     * (идёт фейд/готовится плеер). Очередь показывает его как якорь вместо
     * текущего трека — иначе новая песня на время анимации исчезала из
     * списка, а через секунду появлялась снова.
     */
    private var pendingQueueSongId: Long? = null

    // ── Transition Engine ────────────────────────────────────────────────────
    // Движок кроссфейда (два MediaPlayer) + контроллер расписания переходов.
    // Адаптировано из DualPlayerEngine / TransitionController (PixelPlay).
    // АВТОпереход — наложение двух плееров (кроссфейд) с длительностью ИЗ
    // НАСТРОЕК переходов — главная фишка приложения. Ручное переключение —
    // последовательный фейд огибающей (0.2–1.0 с, свой слайдер).
    private val dualEngine         = DualPlayerEngine(application)
    private val transitionCtrl     = TransitionController(dualEngine)

    private val _transitionSettings = MutableStateFlow(prefs.loadTransitionSettings())
    val transitionSettings: StateFlow<TransitionSettings> = _transitionSettings.asStateFlow()

    // ── Playback stats tracking ───────────────────────────────────────────────
    private var trackingStartMs: Long = 0L
    private var trackingSongId: Long  = -1L

    /** Суммарное время прослушивания по каждой песне (мс) — для строк
     *  «прослушано» на страницах альбомов/плейлистов. */
    private val _listenedBySong = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val listenedBySong: StateFlow<Map<Long, Long>> = _listenedBySong.asStateFlow()

    private fun refreshListenedStats() {
        viewModelScope.launch(Dispatchers.IO) {
            _listenedBySong.value = statsRepository.listenedMsBySong()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            musicService = (service as MusicService.MusicBinder).getService().also { svc ->
                svc.onPlayPause = { togglePlayPause() }
                // Семантические команды из MediaSession (медиа-кнопки, шторка TG):
                // НЕ тогглы — «пауза» глушит только играющего, «плей» будит только спящего.
                svc.onPlayCommand  = { externalPlayCommand() }
                svc.onPauseCommand = { externalPauseCommand() }
                svc.onPrev      = { playPrevious() }
                svc.onNext      = { playNext() }
                svc.onStop      = { stopPlaybackFromBridge(clearSong = true) }
                svc.onSeekTo    = { seekTo(it) }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
        }
    }

    init {
        // Launch service immediately so it's ready
        val intent = Intent(application, MusicService::class.java)
        application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        application.startService(intent)

        refreshListenedStats()

        // Load songs in background — use fast file storage when available,
        // fall back to legacy SharedPreferences on first run (migration)
        viewModelScope.launch(Dispatchers.IO) {
            val saved: List<Song> = run {
                // Fast path: file-based JSON (10–20× faster than SharedPrefs for big libraries)
                val fromFile = SongsFileStorage.load(application)
                if (fromFile != null) {
                    fromFile
                } else {
                    // First run or old install — migrate from SharedPreferences
                    val legacy = prefs.loadSongs()
                    if (legacy.isNotEmpty()) {
                        // Persist to fast storage so next launch is instant
                        SongsFileStorage.save(application, legacy)
                    }
                    legacy
                }
            }

            _splashTotalCount.value = saved.size.coerceAtLeast(1)

            if (saved.isNotEmpty()) {
                // Load all songs at once — no per-chunk delay needed, IO thread is non-blocking
                _songs.value = saved
                _splashLoadedCount.value = saved.size
            }

            if (_songs.value.isNotEmpty()) {
                normalizeUserAlbums(_songs.value)
            }

            _splashLoadedCount.value = _splashTotalCount.value
            syncBridgeState()
            _isAppReady.value = true

            // Defer heavy work: let user interact with the app first
            kotlinx.coroutines.delay(3000L)
            prefetchLyricsForLibrary()
        }
    }

    // ── Song management ──────────────────────────────────────────────────────

    fun addSongFromUri(uri: Uri, autoPlay: Boolean = false, onComplete: ((Song?) -> Unit)? = null) {
        viewModelScope.launch {
            val song = kotlinx.coroutines.withContext(Dispatchers.IO) {
                repository.getSongFromUri(getApplication(), uri)
            }

            var targetSong: Song? = null
            if (song != null) {
                val resolvedSong = _songs.value.firstOrNull { existing ->
                    existing.uri == uri || (song.id != 0L && existing.id == song.id)
                } ?: song
                targetSong = resolvedSong

                if (_songs.value.none { existing -> existing.uri == resolvedSong.uri || existing.id == resolvedSong.id }) {
                    val newList = _songs.value + resolvedSong
                    _songs.value = newList
                    debouncedSaveSongs(newList)
                    normalizeUserAlbums(newList)
                    syncBridgeState()
                }

                if (autoPlay) {
                    playSong(resolvedSong)
                }
            }

            onComplete?.invoke(targetSong)
        }
    }

    fun scanAndAddAllMusic(onComplete: (() -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val found    = repository.scanAllMusic(30_000L)
            val existingUris = _songs.value.map { it.uri }.toSet()
            // Also track file names of existing file:// URIs to block MediaStore duplicates
            val existingFileNames = _songs.value
                .mapNotNull { song ->
                    song.uri.scheme?.let { scheme ->
                        if (scheme == "file") song.uri.lastPathSegment else null
                    }
                }.toSet()
            val newSongs = found.filter { s ->
                s.uri !in existingUris &&
                s.uri.lastPathSegment !in existingFileNames
            }.map { s ->
                // Strip albumArtUri for songs from the MusicPlayer download folder
                if (s.folderPath?.contains("MusicPlayer") == true) s.copy(albumArtUri = null)
                else s
            }
            // Refresh album ordering for tracks already present in pre-track-number caches,
            // retaining their custom titles, covers and hidden state.
            val scannedByUri = found.associateBy { it.uri }
            val refreshedSongs = _songs.value.map { existing ->
                scannedByUri[existing.uri]?.let { scanned ->
                    existing.copy(trackNumber = scanned.trackNumber)
                } ?: existing
            }
            val newList = (refreshedSongs + newSongs).sortedBy { it.title }
            _songs.value = newList
            debouncedSaveSongs(newList)
            normalizeUserAlbums(newList)
            syncBridgeState()
            onComplete?.invoke()
        }
    }

    /**
     * Adds a song downloaded from online search to the local library.
     * Avoids duplicates by checking the URI.
     */
    fun addDownloadedSong(song: Song) {
        if (_songs.value.any { it.uri == song.uri }) return
        val newList = (_songs.value + song).sortedBy { it.title }
        _songs.value = newList
        debouncedSaveSongs(newList)
        normalizeUserAlbums(newList)
        syncBridgeState()
        // Warm palette cache for the new song
        warmPaletteCache(listOf(song))
    }

    fun removeSong(song: Song) {
        val newList = _songs.value.toMutableList()
        val idx     = newList.indexOf(song)
        newList.remove(song)
        _songs.value = newList
        debouncedSaveSongs(newList)
        normalizeUserAlbums(newList)

        if (_currentSong.value?.id == song.id) {
            stopPlayback()
            _currentSong.value  = null
            _currentIndex.value = -1
        } else if (_currentIndex.value > idx) {
            _currentIndex.value = _currentIndex.value - 1
        }
        syncBridgeState()
    }

    fun searchLibrary(query: String, limit: Int = 8): List<Song> {
        val needle = query.trim().lowercase()
        if (needle.isBlank()) return emptyList()

        return _songs.value.mapNotNull { song ->
            val title = song.title.lowercase()
            val artist = song.artist.lowercase()
            val album = song.album.lowercase()
            val combined = "$title $artist $album"
            val score = when {
                title == needle -> 320
                "$artist - $title" == needle -> 300
                title.startsWith(needle) -> 260
                artist.startsWith(needle) -> 220
                title.contains(needle) -> 180
                artist.contains(needle) -> 160
                album.contains(needle) -> 120
                combined.contains(needle) -> 90
                else -> 0
            }
            if (score > 0) song to score else null
        }
            .sortedWith(compareByDescending<Pair<Song, Int>> { it.second }.thenBy { it.first.title.lowercase() })
            .take(limit)
            .map { it.first }
    }

    fun playByQuery(query: String): Song? {
        val match = searchLibrary(query, limit = 1).firstOrNull() ?: return null
        playSong(match)
        return match
    }

    private fun setBridgeQueueFromSongs(songs: List<Song>) {
        _bridgeQueueUris.value = songs
            .distinctBy { it.uri.toString() }
            .map { it.uri.toString() }
    }

    private fun resolveBridgeQueueSongs(): List<Song> {
        val uris = _bridgeQueueUris.value
        if (uris.isEmpty()) return emptyList()
        val songsByUri = _songs.value.associateBy { it.uri.toString() }
        return uris.mapNotNull { songsByUri[it] }
    }

    /**
     * Активный список воспроизведения.
     *
     * Если запущена явная очередь (плейлист / альбом / собранная очередь) — возвращается
     * именно она в своём порядке, иначе вся отсортированная библиотека.
     * ВСЕ навигационные пути (next / prev / shuffle / кроссфейд / свайп обложки / лист очереди)
     * обязаны использовать один и тот же список, чтобы shuffle и repeat не выпадали
     * из плейлиста в общую библиотеку.
     */
    private fun activePlaybackSongs(): List<Song> {
        val explicit = resolveBridgeQueueSongs()
        return if (explicit.isNotEmpty()) explicit else sortedVisibleSongs(getVisibleSongs())
    }

    private fun bridgeQueueRelative(step: Int): Song? {
        val current = _currentSong.value ?: return null
        val queue = resolveBridgeQueueSongs()
        if (queue.isEmpty()) return null
        val currentIdx = queue.indexOfFirst { it.uri == current.uri }
        if (currentIdx < 0) return null
        val targetIdx = currentIdx + step
        if (targetIdx !in queue.indices) return null
        return queue[targetIdx]
    }

    private fun clearBridgeQueue() {
        _bridgeQueueUris.value = emptyList()
        // Очередь сброшена — индексы shuffle, построенные по прежней очереди, больше невалидны
        resetShuffleState()
    }

    fun buildActiveQueueSnapshot(): List<Song> {
        return resolveBridgeQueueSongs().takeIf { it.isNotEmpty() } ?: getSortedSongs()
    }

    fun playFromActiveQueue(song: Song) {
        val preserveBridgeQueue = resolveBridgeQueueSongs().isNotEmpty()
        playSong(song, preserveBridgeQueue = preserveBridgeQueue)
    }

    data class PlayerQueue(val songs: List<Song>, val currentIndex: Int, val shuffled: Boolean)

    private val _queueRevision = MutableStateFlow(0)
    val queueRevision: StateFlow<Int> = _queueRevision.asStateFlow()

    private fun normalizeShuffleUpcoming(sorted: List<Song>, current: Song) {
        val valid = shuffleRemaining.filter { it in sorted.indices && sorted[it].id != current.id }
        shuffleRemaining = if (valid.isNotEmpty()) {
            valid.toMutableList()
        } else {
            sorted.indices.filter { sorted[it].id != current.id }.shuffled().toMutableList()
        }
    }

    fun playerQueue(): PlayerQueue {
        val current = _currentSong.value
        val sorted = activePlaybackSongs()
        if (_settings.value.shuffleEnabled && current != null && sorted.size > 1) {
            normalizeShuffleUpcoming(sorted, current)
            // Прошлые треки НЕ выбрасываем из списка: очередь показывает историю
            // (прослушанное помечается как played), затем текущий трек и будущие.
            // Раньше при переходе на следующую песню она исчезала из очереди, и
            // после возврата назад «Следующий» выкидывал на посторонний трек.
            // Пока переключение «в полёте», якорем служит ЦЕЛЕВОЙ трек — тогда он
            // не исчезает из списка на время анимации и не появляется повторно.
            val pendingSong = pendingQueueSongId?.let { pid -> sorted.firstOrNull { it.id == pid } }
            val anchor = pendingSong ?: current
            val historySongs = previousTrackHistory
                .mapNotNull { id -> sorted.firstOrNull { it.id == id } }
                .distinctBy { it.id }
                .filter { it.id != anchor.id }
            return PlayerQueue(
                historySongs + listOf(anchor) + shuffleRemaining.map { sorted[it] },
                historySongs.size,
                true
            )
        }
        val list = sorted
        // В явной очереди тот же принцип: посреди переключения якорь — целевой трек,
        // чтобы строка не мигала «пропала/вернулась».
        val anchorSong = pendingQueueSongId
            ?.let { pid -> list.firstOrNull { it.id == pid } }
            ?: current
        return PlayerQueue(list, list.indexOfFirst { it.uri == anchorSong?.uri }, false)
    }

    fun playQueueItem(index: Int) {
        val queue = playerQueue()
        val target = queue.songs.getOrNull(index) ?: return
        if (index == queue.currentIndex) {
            if (!_isPlaying.value) togglePlayPause()
            return
        }
        clearRedoStack()
        if (queue.shuffled) {
            // Тот же индексный пространство, на котором построен playerQueue():
            // явная очередь (плейлист) или вся библиотека — но никогда не смешивать их.
            val sorted = activePlaybackSongs()
            val targetIndex = sorted.indexOfFirst { it.id == target.id }
            if (targetIndex < 0) return
            val position = shuffleRemaining.indexOf(targetIndex)
            if (position >= 0) {
                shuffleRemaining = shuffleRemaining.drop(position + 1).toMutableList()
            } else if (index < queue.currentIndex) {
                // Тап по прошлому треку: запоминаем, откуда ушли — «Следующий»
                // вернёт нас на прежний путь, а не бросит кубик заново.
                rememberRedoTarget()
            }
            _currentIndex.value = targetIndex
            startPlayback(target)
        } else {
            playFromActiveQueue(target)
        }
    }

    fun moveQueueItem(from: Int, to: Int) {
        if (from == to) return
        val queue = playerQueue()
        if (from !in queue.songs.indices || to !in queue.songs.indices) return
        if (queue.shuffled) {
            // Двигать можно только будущие треки: история и текущий фиксированы.
            if (from <= queue.currentIndex || to <= queue.currentIndex) return
            val moved = shuffleRemaining.removeAt(from - queue.currentIndex - 1)
            shuffleRemaining.add(to - queue.currentIndex - 1, moved)
            _queueRevision.value += 1
            return
        }
        val reordered = queue.songs.toMutableList()
        reordered.add(to, reordered.removeAt(from))
        setBridgeQueueFromSongs(reordered)
    }

    fun moveQueueItemNext(index: Int) {
        val queue = playerQueue()
        if (index !in queue.songs.indices || index == queue.currentIndex) return
        if (queue.shuffled) {
            if (index <= queue.currentIndex) return
            val moved = shuffleRemaining.removeAt(index - queue.currentIndex - 1)
            shuffleRemaining.add(0, moved)
            _queueRevision.value += 1
            return
        }
        val reordered = queue.songs.toMutableList()
        val moved = reordered.removeAt(index)
        val currentPosition = reordered.indexOfFirst { it.uri == _currentSong.value?.uri }
        reordered.add(currentPosition + 1, moved)
        setBridgeQueueFromSongs(reordered)
    }

    fun removeQueueItem(index: Int) {
        val queue = playerQueue()
        if (queue.songs.size <= 1 || index !in queue.songs.indices) return
        if (queue.shuffled) {
            when {
                index == queue.currentIndex -> {
                    val removedId = queue.songs[index].id
                    playNext()
                    // Песню УДАЛИЛИ из очереди — playNext успел запомнить её как
                    // «предыдущую». Убираем её из истории и из будущих shuffle-треков,
                    // иначе удалённая песня оставалась висеть в списке очереди.
                    val historyIdx = previousTrackHistory.lastIndexOf(removedId)
                    if (historyIdx >= 0) previousTrackHistory.removeAt(historyIdx)
                    if (_settings.value.shuffleEnabled) {
                        val removedSortedIdx = activePlaybackSongs().indexOfFirst { it.id == removedId }
                        if (removedSortedIdx >= 0) shuffleRemaining.remove(removedSortedIdx)
                    }
                    _queueRevision.value += 1
                }
                index < queue.currentIndex -> {
                    // Удаление из истории прослушивания: прошлые треки больше
                    // не выбрасываются из очереди автоматически — только вручную.
                    val removedId = queue.songs[index].id
                    val historyIdx = previousTrackHistory.lastIndexOf(removedId)
                    if (historyIdx >= 0) previousTrackHistory.removeAt(historyIdx)
                    _queueRevision.value += 1
                }
                else -> {
                    shuffleRemaining.removeAt(index - queue.currentIndex - 1)
                    _queueRevision.value += 1
                }
            }
            return
        }
        val remaining = queue.songs.toMutableList()
        remaining.removeAt(index)
        setBridgeQueueFromSongs(remaining)
        if (index == queue.currentIndex) {
            playSong(remaining[index.coerceAtMost(remaining.lastIndex)], preserveBridgeQueue = true)
        }
    }

    fun playQueue(songs: List<Song>, startIndex: Int = 0) {
        val queue = songs.distinctBy { it.uri.toString() }
        if (queue.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, queue.lastIndex)
        // Новая очередь (плейлист/альбом): старое shuffle-состояние построено по
        // прежнему списку и невалидно — сбрасываем, чтобы shuffle работал внутри новой очереди
        resetShuffleState()
        setBridgeQueueFromSongs(queue)
        playSong(queue[safeIndex], preserveBridgeQueue = true)
    }

    fun playQueueFrom(song: Song, songs: List<Song>) {
        val queue = songs.distinctBy { it.uri.toString() }
        if (queue.isEmpty()) return
        val startIndex = queue.indexOfFirst { it.uri == song.uri }
            .takeIf { it >= 0 }
            ?: 0
        playQueue(queue, startIndex)
    }

    fun playAlbum(albumSongs: List<Song>, startSong: Song? = null) {
        if (startSong == null) {
            playQueue(albumSongs, 0)
            return
        }
        playQueueFrom(startSong, albumSongs)
    }

    fun resolveSongsByIds(songIds: Collection<Long>): List<Song> {
        if (songIds.isEmpty()) return emptyList()
        val songMap = _songs.value.associateBy { it.id }
        return songIds.mapNotNull(songMap::get)
    }

    fun resolveUserAlbumSongs(albumId: String): List<Song> {
        val album = _userAlbums.value.firstOrNull { it.id == albumId } ?: return emptyList()
        return resolveSongsByIds(album.songIds)
    }

    /**
     * Запуск плейлиста по id (для bridge-команд и HTTP API).
     * Очередь = ровно треки плейлиста, shuffle/repeat работают внутри неё.
     * @return true, если плейлист найден и запуск произошёл.
     */
    fun playPlaylistById(playlistId: String): Boolean {
        if (playlistId.isBlank()) return false
        val album = _userAlbums.value.firstOrNull { it.id == playlistId } ?: return false
        val songs = resolveSongsByIds(album.songIds)
        if (songs.isEmpty()) return false
        playQueue(songs, 0)
        return true
    }

    fun createUserAlbum(
        name: String,
        songIds: Collection<Long> = emptyList(),
        coverSongId: Long? = null,
        customCoverUri: String? = null,
        motionCoverUri: String? = null,
        description: String = "",
        isPinned: Boolean = false
    ): UserAlbum? {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) return null
        val validSongIds = normalizeSongIds(songIds)
        val resolvedCoverSongId = resolveAlbumCover(validSongIds, coverSongId)
        val normalizedCustomCoverUri = normalizeOptionalUri(customCoverUri)
        val normalizedMotionCoverUri: String? = null
        persistReadableUri(normalizedCustomCoverUri)
        persistReadableUri(normalizedMotionCoverUri)
        val now = System.currentTimeMillis()
        val album = UserAlbum(
            id = UUID.randomUUID().toString(),
            name = trimmedName,
            songIds = validSongIds,
            coverSongId = resolvedCoverSongId,
            customCoverUri = normalizedCustomCoverUri,
            motionCoverUri = normalizedMotionCoverUri,
            description = description.trim(),
            isPinned = isPinned,
            updatedAt = now,
            createdAt = now
        )
        val updated = (_userAlbums.value + album).sortedBy { it.name.lowercase() }
        persistUserAlbums(updated)
        return album
    }

    fun renameUserAlbum(albumId: String, name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) return
        val now = System.currentTimeMillis()
        val updated = _userAlbums.value.map { album ->
            if (album.id == albumId) album.copy(name = trimmedName, updatedAt = now) else album
        }
        persistUserAlbums(updated)
    }

    fun deleteUserAlbum(albumId: String) {
        persistUserAlbums(_userAlbums.value.filterNot { it.id == albumId })
    }

    fun replaceUserAlbumSongs(albumId: String, songIds: Collection<Long>, coverSongId: Long? = null) {
        val normalizedSongIds = normalizeSongIds(songIds)
        val now = System.currentTimeMillis()
        val updated = _userAlbums.value.map { album ->
            if (album.id != albumId) return@map album
            album.copy(
                songIds = normalizedSongIds,
                coverSongId = resolveAlbumCover(normalizedSongIds, coverSongId ?: album.coverSongId),
                updatedAt = now
            )
        }
        persistUserAlbums(updated)
    }

    fun addSongsToUserAlbum(albumId: String, songIds: Collection<Long>) {
        if (songIds.isEmpty()) return
        val now = System.currentTimeMillis()
        val updated = _userAlbums.value.map { album ->
            if (album.id != albumId) return@map album
            val mergedSongIds = normalizeSongIds(album.songIds + songIds)
            album.copy(
                songIds = mergedSongIds,
                coverSongId = resolveAlbumCover(mergedSongIds, album.coverSongId),
                updatedAt = now
            )
        }
        persistUserAlbums(updated)
    }

    fun removeSongsFromUserAlbum(albumId: String, songIds: Collection<Long>) {
        if (songIds.isEmpty()) return
        val removedIds = songIds.toSet()
        val now = System.currentTimeMillis()
        val updated = _userAlbums.value.map { album ->
            if (album.id != albumId) return@map album
            val remainingSongIds = album.songIds.filterNot { it in removedIds }
            album.copy(
                songIds = remainingSongIds,
                coverSongId = resolveAlbumCover(remainingSongIds, album.coverSongId),
                updatedAt = now
            )
        }
        persistUserAlbums(updated)
    }

    fun setUserAlbumCover(albumId: String, songId: Long?) {
        val now = System.currentTimeMillis()
        val updated = _userAlbums.value.map { album ->
            if (album.id != albumId) return@map album
            album.copy(
                coverSongId = resolveAlbumCover(album.songIds, songId),
                updatedAt = now
            )
        }
        persistUserAlbums(updated)
    }

    fun updateUserAlbumDetails(
        albumId: String,
        name: String,
        description: String,
        songIds: Collection<Long>,
        coverSongId: Long? = null,
        customCoverUri: String? = null,
        motionCoverUri: String? = null,
        isPinned: Boolean = false
    ) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) return
        val normalizedSongIds = normalizeSongIds(songIds)
        val normalizedCustomCoverUri = normalizeOptionalUri(customCoverUri)
        val normalizedMotionCoverUri: String? = null
        persistReadableUri(normalizedCustomCoverUri)
        persistReadableUri(normalizedMotionCoverUri)
        val now = System.currentTimeMillis()
        val updated = _userAlbums.value.map { album ->
            if (album.id != albumId) return@map album
            album.copy(
                name = trimmedName,
                description = description.trim(),
                songIds = normalizedSongIds,
                coverSongId = resolveAlbumCover(normalizedSongIds, coverSongId ?: album.coverSongId),
                customCoverUri = normalizedCustomCoverUri,
                motionCoverUri = normalizedMotionCoverUri,
                isPinned = isPinned,
                updatedAt = now
            )
        }
        persistUserAlbums(updated)
    }

    private fun rememberBridgeBatchSummary(summary: String) {
        bridgeLastBatchSummary = summary
    }

    fun importBridgeBatch(
        items: List<ExteraGramBridgeBatchItem>,
        mode: String = ExteraGramBridgeContract.TYPE_IMPORT_BATCH,
        onComplete: ((List<Song>) -> Unit)? = null,
    ) {
        viewModelScope.launch {
            if (items.isEmpty()) {
                onComplete?.invoke(emptyList())
                return@launch
            }

            var library = _songs.value
            var changed = false
            val resolved = mutableListOf<Song>()

            items.forEach { item ->
                var targetSong: Song? = null
                val parsedUri = item.uri.takeIf { it.isNotBlank() }?.let { raw ->
                    runCatching { Uri.parse(raw) }.getOrNull()
                }

                if (parsedUri != null) {
                    val importedSong = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        repository.getSongFromUri(getApplication(), parsedUri)
                    }
                    if (importedSong != null) {
                        val existing = library.firstOrNull { existingSong ->
                            existingSong.uri == parsedUri || (importedSong.id != 0L && existingSong.id == importedSong.id)
                        }
                        targetSong = existing ?: importedSong
                        if (existing == null) {
                            library = library + importedSong
                            changed = true
                        }
                    }
                }

                if (targetSong == null) {
                    val query = listOf(item.artist, item.title, item.query)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                        .trim()
                    if (query.isNotBlank()) {
                        targetSong = searchLibrary(query, limit = 1).firstOrNull()
                    }
                }

                val resolvedSong = targetSong
                if (resolvedSong != null && resolved.none { it.uri == resolvedSong.uri }) {
                    resolved += resolvedSong
                }
            }

            if (changed) {
                _songs.value = library
                debouncedSaveSongs(library)
            }

            val unique = resolved.distinctBy { it.uri.toString() }
            if (unique.isNotEmpty()) {
                rememberBridgeBatchSummary("Telegram batch: ${unique.size} трек(ов)")
                when (mode) {
                    ExteraGramBridgeContract.TYPE_IMPORT_BATCH -> {
                        setBridgeQueueFromSongs(unique)
                    }

                    ExteraGramBridgeContract.TYPE_IMPORT_BATCH_AND_PLAY -> {
                        setBridgeQueueFromSongs(unique)
                        playSong(unique.first(), preserveBridgeQueue = true)
                    }

                    ExteraGramBridgeContract.TYPE_QUEUE_BATCH_APPEND -> {
                        val merged = (resolveBridgeQueueSongs() + unique)
                            .distinctBy { it.uri.toString() }
                        setBridgeQueueFromSongs(merged)
                        if (_currentSong.value == null) {
                            playSong(merged.first(), preserveBridgeQueue = true)
                        }
                    }
                }
            }

            syncBridgeState()
            onComplete?.invoke(unique)
        }
    }

    fun stopPlaybackFromBridge(clearSong: Boolean = true) {
        clearRedoStack()
        stopPlayback()
        if (clearSong) {
            _currentSong.value = null
            _currentIndex.value = -1
            _duration.value = 0L
            clearBridgeQueue()
            // Музыки больше нет — убираем уведомление и гасим сессию, иначе
            // в шторке останется «мёртвая» карточка с нерабочими кнопками.
            musicService?.stopForegroundPlayback()
        }
        syncBridgeState()
    }

    // ── Playback ─────────────────────────────────────────────────────────────

    /** Returns the song list sorted according to current settings (mirrors HomeScreen logic). */
    private fun getSortedSongs(): List<Song> {
        val raw = _songs.value
        return when (_settings.value.sortOrder) {
            com.musicplayer.data.SortOrder.TITLE      -> raw.sortedBy { it.title.lowercase() }
            com.musicplayer.data.SortOrder.ARTIST     -> raw.sortedBy { it.artist.lowercase() }
            com.musicplayer.data.SortOrder.ALBUM      -> raw.sortedBy { it.album.lowercase() }
            com.musicplayer.data.SortOrder.DURATION   -> raw.sortedBy { it.duration }
            com.musicplayer.data.SortOrder.DATE_ADDED -> raw.sortedByDescending { it.id }
        }
    }

    fun playSong(song: Song, preserveBridgeQueue: Boolean = false, animateSwitch: Boolean? = null) {
        clearRedoStack()
        if (!preserveBridgeQueue) {
            clearBridgeQueue()
        }
        val sorted = sortedVisibleSongs(getVisibleSongs())
        _currentIndex.value = sorted.indexOf(song).takeIf { it >= 0 } ?: -1
        startPlayback(song, animateSwitch = animateSwitch)
    }

    /**
     * Remembers tracks that were actually played so the Previous button is meaningful even
     * while shuffle is enabled. This is a back-stack, not a second randomizer.
     */
    private fun rememberPreviousTrack(nextSong: Song) {
        val current = _currentSong.value ?: return
        if (current.id == nextSong.id) return
        if (previousTrackHistory.lastOrNull() != current.id) {
            previousTrackHistory += current.id
        }
        if (previousTrackHistory.size > MAX_PREVIOUS_TRACKS) {
            previousTrackHistory.removeAt(0)
        }
    }

    /** Запоминаем трек, с которого ушли НАЗАД: «Следующий» вернёт нас по этому пути (redo). */
    private fun rememberRedoTarget() {
        val current = _currentSong.value ?: return
        if (redoTrackHistory.lastOrNull() == current.id) return
        redoTrackHistory += current.id
        if (redoTrackHistory.size > MAX_PREVIOUS_TRACKS) {
            redoTrackHistory.removeAt(0)
        }
    }

    private fun clearRedoStack() {
        redoTrackHistory.clear()
    }

    /** Длительность затухания/нарастания при ПЛАВНОМ ручном переключении (мс). */
    private fun manualSwitchFadeMs(): Long =
        _settings.value.manualSwitchFadeMs
            .coerceIn(MANUAL_SWITCH_FADE_MIN_MS, MANUAL_SWITCH_FADE_MAX_MS)
            .toLong()

    private fun notificationArtUri(song: Song): Uri? =
        _customArtMap.value[song.id] ?: song.albumArtUri

    private fun pushNotificationState(forceMetadata: Boolean) {
        val song = _currentSong.value ?: return
        val player = mediaPlayer
        // Плеер мог быть ещё не подготовлен (prepareAsync) или уже освобождён —
        // currentPosition/duration на таком плеере кидают IllegalStateException.
        val positionMs = try {
            player?.currentPosition?.toLong()?.coerceAtLeast(0L)
        } catch (_: Exception) { null } ?: _currentPosition.value
        val durationMs = try {
            player?.duration?.toLong()?.coerceAtLeast(0L)
        } catch (_: Exception) { null } ?: _duration.value
        val isPlaying = _isPlaying.value
        val playbackSpeed = if (isPlaying) _playbackSpeed.value else 0f

        if (forceMetadata) {
            musicService?.updateNotification(
                title = song.title,
                artist = song.artist,
                albumArtUri = notificationArtUri(song),
                isPlaying = isPlaying,
                durationMs = durationMs,
                positionMs = positionMs,
                playbackSpeed = playbackSpeed
            )
        } else {
            musicService?.updatePlaybackState(
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
                playbackSpeed = playbackSpeed
            )
        }
    }

    private fun startPlayback(song: Song, rememberHistory: Boolean = true, animateSwitch: Boolean? = null) {
        if (rememberHistory) rememberPreviousTrack(song)
        // Переключение началось: целевой трек становится «якорем» очереди,
        // чтобы он не пропадал из списка до момента фактического старта.
        pendingQueueSongId = song.id
        flushStatsIfNeeded()  // record previous song before switching
        // Поколение воспроизведения: каждая новая команда переключения инвалидирует
        // «старые» колбэки (onPrepared/onError/фейды) — защита от гонок и двойного
        // звука при быстром листании треков.
        playbackGeneration += 1
        val gen = playbackGeneration
        switchFadeJob?.cancel()

        val animate = (animateSwitch ?: _settings.value.manualSwitchAnimation) &&
            _isPlaying.value && mediaPlayer != null
        if (animate) {
            // Плавный РУЧНОЙ переход, последовательно: старая песня быстро и линейно
            // затухает, затем останавливается, и только ПОСЛЕ этого стартует новая —
            // с таким же линейным нарастанием (длительность — из настроек, 0.2–1.0 с).
            // Никакого кроссфейда.
            transitionCtrl.cancelPendingTransition()
            switchFadeJob = viewModelScope.launch {
                if (!runLinearMasterFade(toZero = true, gen = gen, fadeMs = manualSwitchFadeMs())) return@launch
                stopPlayback()
                beginPlayback(song, gen, animateIn = true)
            }
        } else {
            // Резкая смена (как раньше): глушим текущий плеер, отменяем запланированный
            // переход и готовим новый. Порядок «сначала stop, потом cancel» сохранён:
            // notifyMasterReleased внутри stopPlayback обнуляет playerA в движке.
            stopPlayback()
            transitionCtrl.cancelPendingTransition()
            beginPlayback(song, gen, animateIn = false)
        }
    }

    /**
     * Линейный фейд огибающей мастера (ручное переключение и автопереход).
     * Двигает только masterEnvelope движка — пользовательская громкость, preamp
     * и duck остаются на месте. Возвращает false, если во время фейда пришла новая
     * команда переключения (поколение сменилось) — тогда фейд бросаем.
     */
    private suspend fun runLinearMasterFade(toZero: Boolean, gen: Int, fadeMs: Long): Boolean {
        // Начинаем от ФАКТИЧЕСКОЙ огибающей: если предыдущий плавный переход был
        // прерван на середине, фейд не будет делать скачок громкости.
        val from = dualEngine.currentMasterEnvelope()
        val target = if (toZero) 0f else 1f
        if (fadeMs <= 0L) {
            dualEngine.setMasterEnvelope(target)
            return gen == playbackGeneration
        }
        val start = SystemClock.uptimeMillis()
        while (gen == playbackGeneration) {
            val progress = ((SystemClock.uptimeMillis() - start).toFloat() / fadeMs)
                .coerceIn(0f, 1f)
            val envelope = from + (target - from) * progress
            dualEngine.setMasterEnvelope(envelope)
            if (progress >= 1f) return true
            delay(16)
        }
        return false
    }

    /**
     * Создаёт и ГОТОВИТ новый плеер асинхронно (prepareAsync). Синхронный prepare()
     * на главном потоке блокировал UI на 50–500 мс на каждом переключении — это и
     * был «мини-фриз» между песнями. Все обновления состояния происходят в onPrepared.
     */
    private fun beginPlayback(song: Song, gen: Int, animateIn: Boolean, fadeMs: Long = manualSwitchFadeMs()) {
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // Трек, докопированный TrackPreloader'ом в файловый кэш, играем оттуда —
            // подготовка по локальному файлу занимает миллисекунды, а по content://
            // может тянуться секунды.
            val playUri = TrackPreloader.readyCachedFile(getApplication(), song.uri) ?: song.uri
            player.setDataSource(getApplication(), playUri)
        } catch (e: Exception) {
            e.printStackTrace()
            try { player.release() } catch (_: Exception) {}
            pendingQueueSongId = null   // трек не запустится — снимаем «якорь» очереди
            showPlaybackErrorToast(e)
            return
        }

        player.setOnPreparedListener { prepared ->
            // Пока готовился этот плеер, пришла новая команда (next/prev) — он уже
            // не актуален: молча освобождаем и не трогаем состояние.
            if (gen != playbackGeneration) {
                try { prepared.release() } catch (_: Exception) {}
                return@setOnPreparedListener
            }
            try {
                // Фокус запрашиваем до старта звука: если его уже забрал гс/навигатор,
                // система сразу даст LOSS и мы не начнём играть поверх чужого звука.
                requestAudioFocusIfNeeded()
                prepared.isLooping = (_settings.value.repeatMode == RepeatMode.ONE)
                prepared.setOnCompletionListener { onSongComplete() }
                if (animateIn) {
                    // Громкость ноль ДО start(): иначе был бы слышен щелчок на полном объёме.
                    prepared.setVolume(0f, 0f)
                }
                prepared.start()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && _playbackSpeed.value != 1f) {
                    try {
                        prepared.playbackParams = PlaybackParams().apply { speed = _playbackSpeed.value }
                    } catch (_: Exception) {}
                }

                mediaPlayer        = prepared
                // Огибающая мастера: 0 для плавного нарастания, 1 для обычного старта
                // (предыдущий фейд мог оборваться на середине — сбрасываем «хвост»).
                dualEngine.setMasterEnvelope(if (animateIn) 0f else 1f)
                dualEngine.setMasterPlayer(prepared)
                dualEngine.setPlaybackSpeed(_playbackSpeed.value)
                if (animateIn) {
                    switchFadeJob = viewModelScope.launch {
                        if (runLinearMasterFade(toZero = false, gen = gen, fadeMs = fadeMs)) {
                            applyVolumeWithBalance()
                        }
                    }
                } else {
                    applyVolumeWithBalance()   // volume + preamp + L/R balance
                }
                _currentSong.value = song
                pendingQueueSongId = null   // переключение завершилось — якорь больше не нужен
                _isPlaying.value   = true
                _duration.value    = try {
                    prepared.duration.toLong().coerceAtLeast(0L)
                } catch (_: Exception) { song.duration }
                trackingStartMs    = System.currentTimeMillis()
                trackingSongId     = song.id
                startProgressTracking()
                loadLyricsForSong(song)
                pushNotificationState(forceMetadata = true)
                syncBridgeState()

                dualEngine.onPlayerSwapped = { newMaster ->
                    // Вызывается когда кроссфейд поменял плееры местами —
                    // обновляем ссылку на активный MediaPlayer и вешаем completion.
                    mediaPlayer = newMaster
                    try { newMaster.setOnCompletionListener { onSongComplete() } } catch (_: Exception) {}
                    try { newMaster.isLooping = (_settings.value.repeatMode == RepeatMode.ONE) } catch (_: Exception) {}
                    try { attachEqualizerToSession(newMaster.audioSessionId) } catch (_: Exception) {}
                }
                // Attach equalizer effects to the new MediaPlayer's audio session
                try { attachEqualizerToSession(prepared.audioSessionId) } catch (_: Exception) {}
                scheduleNextTransition(song)
            } catch (e: Exception) {
                e.printStackTrace()
                showPlaybackErrorToast(e)
            }
        }
        player.setOnErrorListener { _, what, extra ->
            if (gen != playbackGeneration) return@setOnErrorListener true
            pendingQueueSongId = null   // трек не запустится — снимаем «якорь» очереди
            mediaPlayer = null
            _isPlaying.value = false
            stopProgressTracking()
            showPlaybackErrorToast(RuntimeException("ошибка декодера (what=$what, extra=$extra)"))
            true
        }
        mediaPlayer = player
        player.prepareAsync()
    }

    /** Ошибка запуска трека не должна проходить молча: трек «не включался». */
    private fun showPlaybackErrorToast(e: Exception) {
        val reason = if (e is SecurityException) {
            "нет доступа к файлу. Разреши «Музыка и аудио» в настройках приложения"
        } else {
            "${e.javaClass.simpleName}: ${e.message ?: "неизвестная ошибка"}"
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                getApplication(),
                "Не удалось включить трек: $reason",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Пользовательская громкость (после preamp и баланса) — база для затухания старого плеера. */
    private fun currentUserVolumePair(): Pair<Float, Float> {
        val vol = _volume.value
        val preampFactor = Math.pow(10.0, _preampGain.value / 20.0).toFloat().coerceIn(0f, 4f)
        val effective = (vol * preampFactor).coerceIn(0f, 1f)
        val balance = _audioBalance.value
        val left = if (balance > 0) effective * (1f - balance) else effective
        val right = if (balance < 0) effective * (1f + balance) else effective
        return left to right
    }

    // ── Планирование кроссфейда ──────────────────────────────────────────────

    /** Планирует автопереход-кроссфейд к следующему треку (если включено). */
    private fun scheduleNextTransition(currentSong: Song) {
        val settings = _transitionSettings.value
        if (settings.mode == TransitionMode.NONE || _settings.value.repeatMode == RepeatMode.ONE) {
            // Повтор одного трека играется сам собой (isLooping) — переход «в самого себя»
            // дал бы эхо начала трека на протяжении всего фейда. При смене режима посреди
            // трека обязательно гасим уже запланированный переход.
            transitionCtrl.cancelPendingTransition()
            return
        }

        val nextSong = peekNextSong(currentSong) ?: return

        // Заранее докачиваем следующий трек в файловый кэш: подготовка MediaPlayer
        // напрямую по content:// может занимать секунды — это и есть «мини-зависание»
        // в момент перехода. Кэш ограничен, копирование идёт в фоне кусками.
        TrackPreloader.request(getApplication(), nextSong)

        if (settings.durationMs <= 0) {
            // «Встык»: анимации нет — смена произойдёт по факту окончания трека
            // (onSongComplete → playNext), кэш следующего трека уже прогрет выше.
            // Отдельное расписание не строим, чтобы не гонять его с onCompletion.
            transitionCtrl.cancelPendingTransition()
            return
        }

        // Поколение на момент планирования: если посреди перехода пришла ручная
        // смена (generation растёт), «поздние» колбэки кроссфейда глушим — иначе
        // завершение прерванного кроссфейда перепланировало бы переход к уже
        // неактуальному треку.
        val scheduledGen = playbackGeneration
        transitionCtrl.scheduleTransition(
            currentSong          = currentSong,
            nextSong             = nextSong,
            getPositionMs        = {
                try { mediaPlayer?.currentPosition?.toLong() ?: 0L } catch (_: Exception) { 0L }
            },
            getDurationMs        = {
                try { mediaPlayer?.duration?.toLong()?.coerceAtLeast(0L) ?: 0L } catch (_: Exception) { 0L }
            },
            settings             = settings,
            onTransitionComplete = {
                if (playbackGeneration == scheduledGen) finishEngineTransition(nextSong)
            },
            onSwapReady          = { swappedToSong ->
                if (playbackGeneration == scheduledGen) swapUiToSong(swappedToSong, recordHistory = true)
            }
        )
    }

    /** Финализация после кроссфейда (метаданные уже обновлены при onPlayerSwapped). */
    private fun finishEngineTransition(nextSong: Song) {
        flushStatsIfNeeded()
        _duration.value = try {
            mediaPlayer?.duration?.toLong() ?: nextSong.duration
        } catch (_: Exception) {
            nextSong.duration
        }
        applyVolumeWithBalance()
        // Планируем следующий переход
        scheduleNextTransition(nextSong)
    }

    /**
     * Обновление UI в начале кроссфейда: название/обложка соответствуют уже играющему
     * треку. Вызывается планировщиком через onSwapReady в момент смены мастеров.
     */
    private fun swapUiToSong(swappedToSong: Song, recordHistory: Boolean) {
        // Сначала сбрасываем статистику ИСХОДЯЩЕГО трека: после смены
        // trackingSongId накопленное время прослушивания потерялось бы.
        flushStatsIfNeeded()
        if (recordHistory) {
            rememberPreviousTrack(swappedToSong)
        }
        _currentSong.value  = swappedToSong
        pendingQueueSongId  = null
        // Индекс и «съедание» shuffle-очереди считаем в активном списке
        // (плейлист, если он запущен, иначе библиотека) — иначе кроссфейд
        // рассинхронизирует shuffle с плейлистом.
        val list = activePlaybackSongs()
        val idx = list.indexOfFirst { it.id == swappedToSong.id }
        if (idx >= 0) {
            if (resolveBridgeQueueSongs().isEmpty()) {
                _currentIndex.value = idx
            }
            // Кроссфейд не проходит через playNext(), поэтому «съедаем» трек из
            // shuffle-очереди здесь. Иначе он оставался первым в очереди, и
            // кнопка «Следующий» запускала ту же самую песню заново.
            if (_settings.value.shuffleEnabled && shuffleRemaining.remove(idx)) {
                _queueRevision.value += 1
            }
        }
        trackingStartMs     = System.currentTimeMillis()
        trackingSongId      = swappedToSong.id
        _currentPosition.value = 0L
        _duration.value = try {
            mediaPlayer?.duration?.toLong() ?: swappedToSong.duration
        } catch (_: Exception) {
            swappedToSong.duration
        }
        _isPlaying.value = true
        startProgressTracking()
        loadLyricsForSong(swappedToSong)
        pushNotificationState(forceMetadata = true)
        syncBridgeState()
    }

    /** Следующий трек без запуска playback — для планирования кроссфейда. */
    private fun peekNextSong(currentSong: Song): Song? {
        // Работаем строго в активном списке (плейлист или библиотека),
        // чтобы кроссфейд не уводил воспроизведение за пределы плейлиста.
        val active = activePlaybackSongs()
        if (active.isEmpty()) return null

        if (_settings.value.shuffleEnabled) {
            if (active.size < 2) return currentSong
            // Используем ту же очередь, что и playNext()/playerQueue(): трек, к которому
            // готовится кроссфейд, и трек, который выберет кнопка «Следующий», совпадают.
            normalizeShuffleUpcoming(active, currentSong)
            return shuffleRemaining.firstOrNull()?.let { active.getOrNull(it) } ?: currentSong
        }

        val idx = active.indexOfFirst { it.id == currentSong.id }.takeIf { it >= 0 }
            ?: _currentIndex.value.coerceIn(0, (active.size - 1).coerceAtLeast(0))
        return when (_settings.value.repeatMode) {
            RepeatMode.ONE -> currentSong
            RepeatMode.ALL -> active.getOrNull((idx + 1) % active.size)
            RepeatMode.NONE -> active.getOrNull(idx + 1)
        }
    }

    private fun sortedVisibleSongs(visible: List<Song>): List<Song> {
        return when (_settings.value.sortOrder) {
            com.musicplayer.data.SortOrder.TITLE      -> visible.sortedBy { it.displayTitle().lowercase() }
            com.musicplayer.data.SortOrder.ARTIST     -> visible.sortedBy { it.displayArtist().lowercase() }
            com.musicplayer.data.SortOrder.ALBUM      -> visible.sortedBy { it.album.lowercase() }
            com.musicplayer.data.SortOrder.DURATION   -> visible.sortedBy { it.duration }
            com.musicplayer.data.SortOrder.DATE_ADDED -> visible.sortedByDescending { it.id }
        }
    }

    /** Обновляет настройки кроссфейда (вызывается из EditTransitionScreen). */
    fun updateTransitionSettings(settings: TransitionSettings) {
        _transitionSettings.value = settings
        prefs.saveTransitionSettings(settings)
        // Переплановываем для текущего трека
        _currentSong.value?.let { scheduleNextTransition(it) }
    }

    /** Records elapsed time for the current song to the stats repository. */
    private fun flushStatsIfNeeded() {
        val songId = trackingSongId
        val start  = trackingStartMs
        if (songId < 0 || start <= 0L) return
        val elapsed = System.currentTimeMillis() - start
        val song    = _currentSong.value
        val maxDur  = song?.duration ?: Long.MAX_VALUE
        // Only record if listened at least 10 seconds
        val toRecord = elapsed.coerceAtMost(maxDur)
        if (toRecord >= 10_000L) {
            viewModelScope.launch(Dispatchers.IO) {
                statsRepository.recordPlayback(songId, toRecord)
                // Инкрементально обновляем карту «прослушано», чтобы строки
                // на страницах коллекций обновлялись без перечитывания файла.
                _listenedBySong.value = _listenedBySong.value.toMutableMap().apply {
                    merge(songId, toRecord, Long::plus)
                }
            }
        }
        trackingStartMs = 0L
        trackingSongId  = -1L
    }

    // ── Audio focus: гс в Telegram, навигатор, звонки ─────────────────────────
    //
    // Правила ровно как просил пользователь:
    //  • играли → гс начался → пауза; гс закончился → играем дальше;
    //  • уже стояли на паузе → гс ничего не меняет (музыка НЕ включается);
    //  • фокус забрал другой музыкальный плеер → останавливаемся без автопродолжения.
    private val audioManager by lazy {
        getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private var focusHeld = false
    /** Играли до временной потери фокуса — вернуть воспроизведение после него. */
    private var resumeOnFocusGain = false
    private var duckedByFocus = false
    /** Внешняя «пауза» пришла, когда мы уже молчали (гс в TG): её «плей» после
     *  гса один раз проглатываем — пользователь музыку сам не включал.
     *  TTL — если ожидаемый «плей» так и не пришёл, флаг устаревает. */
    private var swallowNextExternalPlay = false
    private var swallowNextExternalPlayAt = 0L

    /** Современный API фокуса (API 26+). */
    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (_isPlaying.value) {
                    resumeOnFocusGain = true
                    pausePlaybackInternal(abandonFocus = false)
                } else {
                    // И так молчали — после гса ничего не включаем.
                    resumeOnFocusGain = false
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Короткий фон (звук уведомления): приглушаемся, не останавливаясь.
                // Только через движок: мастер и предзагруженный плеер там оба,
                // и двойного умножения громкости не будет.
                if (_isPlaying.value) {
                    duckedByFocus = true
                    dualEngine.setDuckFactor(DUCK_VOLUME)
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (duckedByFocus) {
                    duckedByFocus = false
                    if (_isPlaying.value) applyPlaybackVolume(1f)
                    dualEngine.setDuckFactor(1f)
                } else if (resumeOnFocusGain && !_isPlaying.value) {
                    // Возвращаем ТОЛЬКО то, что сами поставили на паузу из-за фокуса.
                    resumeOnFocusGain = false
                    resumePlaybackInternal()
                } else {
                    resumeOnFocusGain = false
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Фокус надолго забрал другой плеер — встаём без автопродолжения.
                resumeOnFocusGain = false
                if (_isPlaying.value) pausePlaybackInternal(abandonFocus = false)
                abandonAudioFocus()
            }
        }
    }

    private fun requestAudioFocusIfNeeded(): Boolean {
        if (focusHeld) return true
        // AudioFocusRequest — с API 26 (наш minSdk), немой requestAudioFocus(...) устарел.
        val result = runCatching {
            audioManager.requestAudioFocus(focusRequest)
        }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return focusHeld
    }

    private fun abandonAudioFocus() {
        if (focusHeld) {
            runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
            focusHeld = false
        }
        duckedByFocus = false
        resumeOnFocusGain = false
        dualEngine.setDuckFactor(1f)
    }

    /** Внутренняя пауза без тоггла. [abandonFocus] — отдавать ли фокус системе. */
    private fun pausePlaybackInternal(abandonFocus: Boolean) {
        val player = mediaPlayer ?: return
        flushStatsIfNeeded()
        dualEngine.pause()
        try { if (player.isPlaying) player.pause() } catch (_: Exception) {}
        _isPlaying.value = false
        stopProgressTracking()
        pushNotificationState(forceMetadata = true)
        syncBridgeState()
        if (abandonFocus) abandonAudioFocus()
    }

    /** Внутренний старт/возобновление без тоггла. */
    private fun resumePlaybackInternal() {
        val player = mediaPlayer ?: return
        requestAudioFocusIfNeeded()
        dualEngine.resume()
        try { player.start() } catch (_: Exception) {}
        val song = _currentSong.value
        if (song != null) {
            trackingStartMs = System.currentTimeMillis()
            trackingSongId  = song.id
        }
        _isPlaying.value = true
        dualEngine.setDuckFactor(1f)
        applyPlaybackVolume(1f)
        startProgressTracking()
        pushNotificationState(forceMetadata = true)
        syncBridgeState()
    }

    fun togglePlayPause() {
        if (_isPlaying.value) {
            pausePlaybackInternal(abandonFocus = true)
        } else {
            // Пользователь сам нажал играть — внешний «плей» больше не глотаем.
            swallowNextExternalPlay = false
            resumePlaybackInternal()
        }
    }

    /** Семантическое «ИГРАТЬ» извне (медиа-кнопки, шторка Telegram): не тоггл. */
    fun externalPlayCommand() {
        if (swallowNextExternalPlay) {
            swallowNextExternalPlay = false
            val fresh = SystemClock.uptimeMillis() - swallowNextExternalPlayAt <= SWALLOW_TTL_MS
            if (fresh) {
                // Нам посылали «паузу», когда мы уже молчали (гс в TG): его «плей» —
                // не команда пользователя, музыку самовольно не включаем.
                return
            }
            // Флаг устарел (гс давно кончился, «плей» не пришёл) — считаем команду настоящей.
        }
        if (!_isPlaying.value) {
            resumePlaybackInternal()
        }
    }

    /** Семантическая «ПАУЗА» извне: глушим только если реально играем. */
    fun externalPauseCommand() {
        if (_isPlaying.value) {
            pausePlaybackInternal(abandonFocus = false)
        } else {
            // Уже молчали — значит «плей» после гса надо проглотить.
            swallowNextExternalPlay = true
            swallowNextExternalPlayAt = SystemClock.uptimeMillis()
        }
    }

    fun seekTo(position: Long) {
        // Перемотка прерывает запланированный переход
        transitionCtrl.cancelPendingTransition()
        // Перемотка посреди фейда переключения отменяет сам фейд: пользователь
        // решил остаться/разобраться на этом треке — громкость возвращаем сразу.
        if (switchFadeJob?.isActive == true) {
            switchFadeJob?.cancel()
            switchFadeJob = null
            dualEngine.setMasterEnvelope(1f)
        }
        // Плеер мог быть ещё не подготовлен (prepareAsync идёт) — seekTo на таком
        // плеере кидает IllegalStateException, глушим это молча.
        try { mediaPlayer?.seekTo(position.toInt()) } catch (_: Exception) {}
        _currentPosition.value = position
        // Фейд мог успеть снять completion у мастера (performOverlapTransition делает
        // это в момент старта) — возвращаем его, иначе трек после перемотки доиграет
        // до конца и не перейдёт к следующему.
        mediaPlayer?.let { mp ->
            try {
                mp.setOnCompletionListener { p -> if (mediaPlayer === p) onSongComplete() }
            } catch (_: Exception) {}
        }
        // Перепланируем переход с новой позиции
        _currentSong.value?.let { scheduleNextTransition(it) }
        pushNotificationState(forceMetadata = false)
        syncBridgeState(position)
    }

    fun setVolume(volume: Float) {
        _volume.value = volume
        prefs.saveVolume(volume)
        applyVolumeWithBalance()
    }

    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        prefs.saveSpeed(speed)
        dualEngine.setPlaybackSpeed(speed)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mediaPlayer?.let { player ->
                try {
                    player.playbackParams = PlaybackParams().apply { this.speed = speed }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
        pushNotificationState(forceMetadata = false)
    }

    // ── Lyrics ────────────────────────────────────────────────────────────────

    /**
     * Auto-search lyrics for a song. Called when song starts or user opens LyricsScreen.
     * Searches in order: memory cache → disk cache → local .lrc → LRCLIB API
     */
    fun loadLyricsForSong(song: Song) {
        lyricsSearchJob?.cancel()
        // Показываем Loading только если нет кэша (чтобы не мелькало)
        _lyricsState.value = LyricsState.Loading
        lyricsSearchJob = viewModelScope.launch {
            try {
                // getLyrics уже проверяет memory cache → disk cache → API
                // Если текст уже скачан в фоне — вернётся мгновенно
                val result = lyricsRepository.getLyrics(song)
                _lyricsState.value = if (result != null && result.isValid()) {
                    LyricsState.Found(result)
                } else {
                    LyricsState.NotFound
                }
            } catch (e: Exception) {
                _lyricsState.value = LyricsState.Error(e.message ?: "Ошибка поиска")
            }
        }
    }

    /**
     * Фоновая загрузка текстов для всей библиотеки.
     * Работает тихо, не влияет на текущее воспроизведение.
     * Запускается один раз при старте, пропускает уже скачанные.
     */
    fun prefetchLyricsForLibrary() {
        viewModelScope.launch(Dispatchers.IO) {
            val songs = _songs.value
            if (songs.isEmpty()) return@launch

            // Проверяем какие уже есть на диске
            val toFetch = songs.filter { song ->
                val cacheFile = java.io.File(
                    getApplication<android.app.Application>().filesDir,
                    "lyrics/${song.id}.json"
                )
                !cacheFile.exists()
            }

            if (toFetch.isEmpty()) return@launch

            // Грузим по одной с задержкой чтобы не нагружать сеть
            toFetch.forEach { song ->
                try {
                    val result = lyricsRepository.getLyrics(song)
                    if (result != null && result.isValid()) {
                        _prefetchedLyricIds.value = _prefetchedLyricIds.value + song.id
                    }
                } catch (_: Exception) {}
                // Задержка между запросами — уважаем rate limit API
                kotlinx.coroutines.delay(600L)
            }
        }
    }

    /**
     * Force refresh lyrics from API (ignore all caches).
     */
    fun refreshLyrics() {
        val song = _currentSong.value ?: return
        lyricsSearchJob?.cancel()
        _lyricsState.value = LyricsState.Loading
        lyricsSearchJob = viewModelScope.launch {
            try {
                lyricsRepository.clearCache(song.id)
                val result = lyricsRepository.getLyrics(song, forceRefresh = true)
                _lyricsState.value = if (result != null && result.isValid()) {
                    LyricsState.Found(result)
                } else {
                    LyricsState.NotFound
                }
            } catch (e: Exception) {
                _lyricsState.value = LyricsState.Error(e.message ?: "Ошибка поиска")
            }
        }
    }

    // ── Ручное редактирование текста (тайминги строк и слов) ─────────────────

    /** Есть ли у трека текст, отредактированный пользователем. */
    fun hasCustomLyrics(songId: Long): Boolean = lyricsRepository.hasCustomLyrics(songId)

    /** Точная позиция воспроизведения (для тап-синхронизации; _currentPosition обновляется реже). */
    fun getExactPositionMs(): Long {
        val exact = try { mediaPlayer?.currentPosition?.toLong() } catch (_: Exception) { null }
        return exact ?: _currentPosition.value
    }

    /**
     * Сохраняет отредактированный текст. Если это текущий трек — плеер сразу
     * показывает новый текст (с пословной анимацией, если у строк есть тайминги слов).
     */
    fun saveCustomLyrics(song: Song, lines: List<SyncedLine>, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val saved = try {
                lyricsRepository.saveCustomLyrics(song.id, lines)
            } catch (_: Exception) {
                null
            }
            if (saved != null && _currentSong.value?.id == song.id) {
                _lyricsState.value = LyricsState.Found(saved)
            }
            onDone(saved != null)
        }
    }

    /** Удаляет ручную правку и заново загружает текст из обычных источников. */
    fun resetCustomLyrics(song: Song) {
        lyricsRepository.deleteCustomLyrics(song.id)
        if (_currentSong.value?.id == song.id) refreshLyrics()
    }

    // ── Favourites ────────────────────────────────────────────────────────────

    fun toggleFavourite(songId: Long) {
        val current = _favourites.value.toMutableSet()
        if (songId in current) current.remove(songId) else current.add(songId)
        _favourites.value = current
        prefs.saveFavourites(current)
    }

    fun isFavourite(songId: Long) = songId in _favourites.value

    private fun normalizeSongIds(songIds: Collection<Long>): List<Long> {
        val validSongIds = _songs.value.asSequence().map { it.id }.toHashSet()
        return songIds
            .distinct()
            .filter { it in validSongIds }
    }

    private fun resolveAlbumCover(songIds: List<Long>, preferredCoverSongId: Long?): Long? {
        return preferredCoverSongId
            ?.takeIf { it in songIds }
            ?: songIds.firstOrNull()
    }

    private fun normalizeOptionalUri(uriString: String?): String? =
        uriString?.trim()?.takeIf { it.isNotBlank() }

    private fun persistReadableUri(uriString: String?) {
        val uri = uriString?.let(android.net.Uri::parse) ?: return
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}
    }

    private fun normalizeUserAlbums(library: List<Song>) {
        val validSongIds = library.asSequence().map { it.id }.toHashSet()
        val normalized = _userAlbums.value.map { album ->
            val normalizedSongIds = album.songIds
                .distinct()
                .filter { it in validSongIds }
            album.copy(
                songIds = normalizedSongIds,
                coverSongId = album.coverSongId?.takeIf { it in normalizedSongIds } ?: normalizedSongIds.firstOrNull(),
                motionCoverUri = null
            )
        }
        if (normalized != _userAlbums.value) {
            persistUserAlbums(normalized)
        }
    }

    private fun persistUserAlbums(albums: List<UserAlbum>) {
        val normalized = albums
            .distinctBy { it.id }
            .map { album ->
                val normalizedSongIds = normalizeSongIds(album.songIds)
                val normalizedCustomCoverUri = normalizeOptionalUri(album.customCoverUri)
                val normalizedMotionCoverUri: String? = null
                persistReadableUri(normalizedCustomCoverUri)
                persistReadableUri(normalizedMotionCoverUri)
                album.copy(
                    name = album.name.trim().ifBlank { "Мой плейлист" },
                    description = album.description.trim(),
                    songIds = normalizedSongIds,
                    coverSongId = resolveAlbumCover(normalizedSongIds, album.coverSongId),
                    customCoverUri = normalizedCustomCoverUri,
                    motionCoverUri = normalizedMotionCoverUri,
                    updatedAt = album.updatedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
                )
            }
            .sortedWith(
                compareByDescending<UserAlbum> { it.isPinned }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.createdAt }
            )
        _userAlbums.value = normalized
        prefs.saveUserAlbums(normalized)
    }

    /** Returns the track that a cover swipe would reveal without mutating playback state. */
    fun playerNeighbour(forward: Boolean): Song? {
        val current = _currentSong.value ?: return null
        if (!forward && _currentPosition.value > 3000L) return null
        // Свайп обложки листает тот же активный список, что и кнопки next/prev:
        // плейлист, если он запущен, иначе библиотеку.
        val sorted = activePlaybackSongs()
        if (sorted.size < 2) return null
        val index = sorted.indexOfFirst { it.id == current.id }.takeIf { it >= 0 }
            ?: _currentIndex.value.coerceIn(0, sorted.lastIndex)
        if (_settings.value.shuffleEnabled && forward) {
            normalizeShuffleUpcoming(sorted, current)
            shuffleRemaining.firstOrNull()?.let { return sorted.getOrNull(it) }
        }
        val raw = if (forward) index + 1 else index - 1
        val target = when {
            raw in sorted.indices -> raw
            _settings.value.repeatMode == RepeatMode.ALL -> (raw + sorted.size) % sorted.size
            else -> return null
        }
        return sorted.getOrNull(target)
    }

    /**
     * Следующий трек. [manual] = false для автопродолжения по окончании трека:
     * тогда плавная ручная анимация НЕ применяется, смена идёт как обычно.
     */
    fun playNext(manual: Boolean = true) {
        // null = брать настройку manualSwitchAnimation; false = точно резко.
        val animateSwitch = if (manual) null else false
        // Если только что ушли назад по истории — «Следующий» сначала проходит ПРЯМО
        // по тому же пути (redo), а не бросает кубик заново. Иначе после «Назад»
        // следующей играла посторонняя песня, а не та, что была до отхода назад.
        if (manual && redoTrackHistory.isNotEmpty()) {
            val songsById = _songs.value.associateBy { it.id }
            while (redoTrackHistory.isNotEmpty()) {
                val redoId = redoTrackHistory.removeAt(redoTrackHistory.lastIndex)
                val redoSong = songsById[redoId]
                if (redoSong != null) {
                    val sorted = sortedVisibleSongs(getVisibleSongs())
                    _currentIndex.value = sorted.indexOfFirst { it.id == redoSong.id }
                        .takeIf { it >= 0 } ?: -1
                    startPlayback(redoSong, animateSwitch = animateSwitch)
                    return
                }
            }
        } else {
            clearRedoStack()
        }
        val queueActive = resolveBridgeQueueSongs().isNotEmpty()

        // Без shuffle: явная очередь (плейлист) листается по порядку — по кругу, доиграв до конца.
        if (!_settings.value.shuffleEnabled) bridgeQueueRelative(1)?.let { nextSong ->
            val sorted = getSortedSongs()
            _currentIndex.value = sorted.indexOf(nextSong).takeIf { it >= 0 } ?: _songs.value.indexOf(nextSong)
            playSong(nextSong, preserveBridgeQueue = true, animateSwitch = animateSwitch)
            return
        }

        // Явная очередь (плейлист) активна — навигация строго внутри неё,
        // и в shuffle, и в обычном режиме. Библиотека не подмешивается.
        if (queueActive) {
            val queue = resolveBridgeQueueSongs()
            if (queue.isEmpty()) return
            val currentIdx = queue.indexOfFirst { it.uri == _currentSong.value?.uri }
            val nextIdx = if (_settings.value.shuffleEnabled) {
                getNextShuffleIndex(currentIdx.coerceAtLeast(0), queue.size)
            } else {
                when {
                    currentIdx < 0 -> 0
                    currentIdx + 1 < queue.size -> currentIdx + 1
                    else -> 0 // конец плейлиста — идём по кругу (для repeat ALL и ручного next)
                }
            }
            val nextSong = queue[nextIdx]
            val libSorted = getSortedSongs()
            _currentIndex.value = libSorted.indexOf(nextSong).takeIf { it >= 0 } ?: _songs.value.indexOf(nextSong)
            startPlayback(nextSong, animateSwitch = animateSwitch)
            return
        }

        val visibleSongs = getVisibleSongs()
        val sorted = sortedVisibleSongs(visibleSongs)

        if (sorted.isEmpty()) return

        val nextIdx = if (_settings.value.shuffleEnabled) {
            val currentIdx = sorted.indexOfFirst { it.id == _currentSong.value?.id }
                .takeIf { it >= 0 }
                ?: _currentIndex.value
            getNextShuffleIndex(currentIdx, sorted.size)
        } else {
            (_currentIndex.value + 1) % sorted.size
        }

        _currentIndex.value = nextIdx
        startPlayback(sorted[nextIdx], animateSwitch = animateSwitch)
    }

    fun playPrevious() {
        // Keep the familiar player behaviour: pressing Previous near the start goes back
        // through listening history; while a track is already playing, restart it first.
        if (_currentPosition.value > 3000L) {
            clearRedoStack()
            seekTo(0L)
            return
        }

        val songsById = _songs.value.associateBy { it.id }
        while (previousTrackHistory.isNotEmpty()) {
            val previousId = previousTrackHistory.removeAt(previousTrackHistory.lastIndex)
            val previousSong = songsById[previousId] ?: continue
            // Запоминаем, откуда ушли: «Следующий» вернёт нас по этому пути.
            rememberRedoTarget()
            val sorted = sortedVisibleSongs(getVisibleSongs())
            _currentIndex.value = sorted.indexOfFirst { it.id == previousSong.id }
                .takeIf { it >= 0 }
                ?: -1
            val preserveQueue = resolveBridgeQueueSongs().isNotEmpty()
            startPlayback(previousSong, rememberHistory = false)
            if (!preserveQueue) clearBridgeQueue()
            return
        }

        // No history yet (for example after app relaunch): use the deterministic previous
        // item in the active queue. Never generate a random track for the Previous button.
        clearRedoStack()
        if (!_settings.value.shuffleEnabled) bridgeQueueRelative(-1)?.let { previousSong ->
            val sorted = getSortedSongs()
            _currentIndex.value = sorted.indexOf(previousSong).takeIf { it >= 0 }
                ?: _songs.value.indexOf(previousSong)
            playSong(previousSong, preserveBridgeQueue = true)
            return
        }

        // В том числе при включённом shuffle предыдущий трек берётся из активного
        // списка (плейлиста, если он запущен), а не из всей библиотеки.
        val sorted = activePlaybackSongs()
        if (sorted.isEmpty()) return
        val currentIndex = sorted.indexOfFirst { it.id == _currentSong.value?.id }
            .takeIf { it >= 0 }
            ?: _currentIndex.value.coerceIn(0, sorted.lastIndex)
        // На ПЕРВОМ треке «Назад» всегда просто перематывает на начало —
        // никаких перескоков на последний/следующий трек.
        if (currentIndex <= 0) {
            seekTo(0L)
            return
        }
        val previousIndex = currentIndex - 1
        if (resolveBridgeQueueSongs().isEmpty()) {
            _currentIndex.value = previousIndex
        }
        startPlayback(sorted[previousIndex])
    }

    fun toggleShuffle() {
        val updated = _settings.value.copy(shuffleEnabled = !_settings.value.shuffleEnabled)
        _settings.value = updated
        prefs.saveSettings(updated)
        // Сбросить состояние shuffle при переключении
        if (updated.shuffleEnabled) {
            resetShuffleState()
        }
    }

    fun toggleRepeat() {
        val next = when (_settings.value.repeatMode) {
            RepeatMode.NONE -> RepeatMode.ALL
            RepeatMode.ALL  -> RepeatMode.ONE
            RepeatMode.ONE  -> RepeatMode.NONE
        }
        val updated = _settings.value.copy(repeatMode = next)
        _settings.value = updated
        prefs.saveSettings(updated)
        mediaPlayer?.isLooping = (next == RepeatMode.ONE)
    }

    fun updateSettings(settings: PlayerSettings) {
        _settings.value = settings
        debouncedSaveSettings(settings)
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun onSongComplete() {
        // Трек мог доиграть ПОСРЕДИ последовательного перехода (фейд ещё идёт) —
        // в этом случае смену уже ведёт switchFadeJob, playNext() запускать нельзя.
        if (switchFadeJob?.isActive == true) return
        if (dualEngine.isTransitionRunning()) return
        flushStatsIfNeeded()
        when (_settings.value.repeatMode) {
            RepeatMode.ONE  -> { mediaPlayer?.seekTo(0); mediaPlayer?.start(); startProgressTracking() }
            RepeatMode.ALL  -> playNext(manual = false)
            RepeatMode.NONE -> {
                // При активной очереди (плейлисте) доигрывание останавливается на её последнем
                // треке, а не улетает играть дальше всю библиотеку.
                // В плейлисте с shuffle учитываем ещё и не догрызенную shuffle-очередь:
                // последовательный «следующий» мог уже закончиться, а случайный — нет.
                val queueActive = resolveBridgeQueueSongs().isNotEmpty()
                val hasMore = if (queueActive) {
                    bridgeQueueRelative(1) != null ||
                        (_settings.value.shuffleEnabled && shuffleRemaining.isNotEmpty())
                } else {
                    // Библиотека — прежняя логика остановки по индексу
                    _currentIndex.value < getVisibleSongs().size - 1
                }
                if (hasMore) {
                    playNext(manual = false)
                } else {
                    _isPlaying.value       = false
                    _currentPosition.value = 0
                    stopProgressTracking()
                    abandonAudioFocus()
                    pushNotificationState(forceMetadata = true)
                }
            }
        }
        syncBridgeState()
    }

    private fun startProgressTracking() {
        stopProgressTracking()
        progressJob = viewModelScope.launch {
            var lastBridgeSyncMs = 0L
            while (true) {
                // Плеер мог быть ещё не подготовлен (prepareAsync) или уже освобождён —
                // isPlaying/currentPosition на таком плеере кидают IllegalStateException.
                val player = mediaPlayer
                val playing = try { player?.isPlaying == true } catch (_: Exception) { false }
                if (player != null && playing) {
                    val current = try {
                        player.currentPosition.toLong()
                    } catch (_: Exception) { _currentPosition.value }
                    _currentPosition.value = current
                    val now = System.currentTimeMillis()
                    if (now - lastBridgeSyncMs >= 1000L) {
                        pushNotificationState(forceMetadata = false)
                        syncBridgeState(current)
                        lastBridgeSyncMs = now
                    }
                }
                delay(200)
            }
        }
    }

    private fun stopProgressTracking() { progressJob?.cancel(); progressJob = null }

    private fun stopPlayback() {
        flushStatsIfNeeded()
        stopProgressTracking()           // ← сначала останавливаем корутину,
        val player = mediaPlayer         //   чтобы она не обратилась к уже
        mediaPlayer = null               //   освобождённому MediaPlayer
        _isPlaying.value = false
        _currentPosition.value = 0
        abandonAudioFocus()
        // Уведомляем DualEngine ПЕРЕД release(), пока объект ещё технически жив,
        // но ПОСЛЕ того как обнулили mediaPlayer — чтобы cancelNext() не пытался
        // вызывать setVolume() на уже мёртвом плеере в следующем startPlayback()
        dualEngine.notifyMasterReleased()
        player?.let {
            // Обнуляем слушатель ДО release() — предотвращает ложное срабатывание
            // onCompletion после освобождения плеера (был краш при удалении трека)
            try { it.setOnCompletionListener(null) } catch (_: Exception) {}
            try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        _duration.value = 0L
        syncBridgeState()
    }

    private fun syncBridgeState(positionOverride: Long? = null) {
        val song = _currentSong.value
        ExteraGramBridgeStateStore.syncPlayback(
            context = getApplication(),
            title = song?.title.orEmpty(),
            artist = song?.artist.orEmpty(),
            album = song?.album.orEmpty(),
            isPlaying = _isPlaying.value,
            hasSong = song != null,
            durationMs = _duration.value,
            positionMs = positionOverride ?: _currentPosition.value,
            currentUri = song?.uri?.toString().orEmpty(),
            librarySize = _songs.value.size,
            bridgeQueueSize = resolveBridgeQueueSongs().size,
            lastBatchSummary = bridgeLastBatchSummary,
        )
    }

    // ── Sleep Timer ───────────────────────────────────────────────────────────
    private val _sleepTimerState = MutableStateFlow(SleepTimerState())
    val sleepTimerState: StateFlow<SleepTimerState> = _sleepTimerState.asStateFlow()

    private var sleepTimerJob: Job? = null

    fun startSleepTimer(
        hours: Int,
        minutes: Int,
        fadeOut: Boolean,
        stopAfterTrack: Boolean,
        action: SleepTimerAction
    ) {
        sleepTimerJob?.cancel()
        val totalSeconds = hours * 3600 + minutes * 60
        if (totalSeconds <= 0 && !stopAfterTrack) return

        _sleepTimerState.value = SleepTimerState(
            isRunning = true,
            remainingSeconds = totalSeconds,
            fadeOut = fadeOut,
            stopAfterTrack = stopAfterTrack,
            action = action
        )

        sleepTimerJob = viewModelScope.launch {
            var remaining = totalSeconds
            while (remaining > 0) {
                delay(1000L)
                remaining--
                // Fade out: gradually lower volume in the last 30 seconds
                if (fadeOut && remaining in 1..30) {
                    val volumeFraction = remaining / 30f
                    applyPlaybackVolume(volumeFraction)
                }
                _sleepTimerState.value = _sleepTimerState.value.copy(remainingSeconds = remaining)
            }

            if (stopAfterTrack) {
                // Wait until the current track ends (poll isPlaying)
                while (_isPlaying.value) {
                    delay(500L)
                }
            }

            // Execute the action
            when (action) {
                SleepTimerAction.PAUSE -> {
                    if (_isPlaying.value) togglePlayPause()
                }
                SleepTimerAction.STOP -> {
                    stopPlayback()
                }
            }
            // Restore volume
            if (fadeOut) {
                applyPlaybackVolume(1f)
            }
            _sleepTimerState.value = SleepTimerState()
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        // Restore volume in case fade-out was active
        applyPlaybackVolume(1f)
        _sleepTimerState.value = SleepTimerState()
    }

    // ── Управление скрытыми треками ───────────────────────────────────────────

    /** Скрыть трек из библиотеки (без удаления файла) */
    fun hideSong(song: Song) {
        val updated = _songs.value.map {
            if (it.id == song.id) it.copy(isHidden = true) else it
        }
        _songs.value = updated
        debouncedSaveSongs(updated)
    }

    /** Показать скрытый трек */
    fun unhideSong(song: Song) {
        val updated = _songs.value.map {
            if (it.id == song.id) it.copy(isHidden = false) else it
        }
        _songs.value = updated
        debouncedSaveSongs(updated)
    }

    /** Получить список скрытых треков */
    fun getHiddenSongs(): List<Song> = _songs.value.filter { it.isHidden }

    /** Получить видимые треки (с учетом настроек showHiddenTracks) */
    fun getVisibleSongs(): List<Song> {
        val allSongs = _songs.value
        return if (_settings.value.showHiddenTracks) {
            allSongs
        } else {
            allSongs.filter { !it.isHidden }
        }
    }

    // ── Редактирование метаданных ─────────────────────────────────────────────

    /** Обновить локальные метаданные трека */
    fun updateSongMetadata(
        song: Song,
        newTitle: String? = null,
        newArtist: String? = null,
        newAlbumArtUri: Uri? = null
    ) {
        val updated = _songs.value.map {
            if (it.id == song.id) {
                it.copy(
                    customTitle = newTitle?.ifBlank { null },
                    customArtist = newArtist?.ifBlank { null },
                    customAlbumArtUri = newAlbumArtUri
                )
            } else {
                it
            }
        }
        _songs.value = updated
        debouncedSaveSongs(updated)

        // Обновить UI если это текущий трек
        if (_currentSong.value?.id == song.id) {
            _currentSong.value = updated.find { it.id == song.id }
            pushNotificationState(forceMetadata = true)
        }
    }

    /** Сбросить кастомные метаданные трека */
    fun resetSongMetadata(song: Song) {
        updateSongMetadata(song, newTitle = "", newArtist = "", newAlbumArtUri = null)
    }

    // ── Улучшенный shuffle ────────────────────────────────────────────────────

    private var shuffleHistory = mutableListOf<Int>()  // история воспроизведенных индексов
    private var shuffleRemaining = mutableListOf<Int>()  // оставшиеся треки для shuffle
    private val previousTrackHistory = mutableListOf<Long>()
    /** Путь «вперёд» после отхода назад: «Следующий» сначала идёт по нему (redo). */
    private val redoTrackHistory = mutableListOf<Long>()
    private companion object {
        const val MAX_PREVIOUS_TRACKS = 100
        /** Громкость при «утином» приглушении (короткие звуки поверх музыки). */
        const val DUCK_VOLUME = 0.25f
        /** Сколько времени «глотатель» внешнего плея остаётся заряженным. */
        const val SWALLOW_TTL_MS = 30_000L
        /** Границы слайдера длительности анимации ручного переключения (мс). */
        const val MANUAL_SWITCH_FADE_MIN_MS = 200
        const val MANUAL_SWITCH_FADE_MAX_MS = 1000
    }

    /** Генерирует следующий случайный индекс с учетом истории */
    private fun getNextShuffleIndex(currentIndex: Int, listSize: Int): Int {
        if (listSize <= 1) return 0

        // Выкидываем текущий трек и индексы, которых уже нет в списке
        // (список мог измениться, а кроссфейд мог перейти на трек из очереди).
        shuffleRemaining = shuffleRemaining
            .filter { it in 0 until listSize && it != currentIndex }
            .toMutableList()

        // Если список закончился, перезапускаем с новой перемешкой
        if (shuffleRemaining.isEmpty()) {
            shuffleRemaining = (0 until listSize)
                .filter { it != currentIndex }
                .shuffled()
                .toMutableList()
        }

        return shuffleRemaining.removeAt(0)
    }

    /** Сброс состояния shuffle (вызывается при изменении библиотеки) */
    fun resetShuffleState() {
        shuffleHistory.clear()
        shuffleRemaining.clear()
        redoTrackHistory.clear()
    }

    override fun onCleared() {
        saveSettingsJob?.cancel()
        sleepTimerJob?.cancel()
        prefs.saveSettings(_settings.value)
        super.onCleared()
        stopPlayback()
        // Активность уничтожена (выход из приложения) — плеер всё равно молчит:
        // убираем уведомление и гасим сессию, чтобы в шторке не осталась мёртвая
        // карточка с нерабочими кнопками и нулевой длительностью.
        musicService?.stopForegroundPlayback()
        transitionCtrl.release()
        dualEngine.release()
        try { androidEqualizer?.release() } catch (_: Exception) {}
        try { androidVirtualizer?.release() } catch (_: Exception) {}
        try { getApplication<Application>().unbindService(serviceConnection) } catch (e: Exception) {}
    }
}
