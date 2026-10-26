package com.musicplayer.bridge

import android.content.Context
import android.net.Uri
import com.musicplayer.data.PreferencesManager
import com.musicplayer.data.Song
import com.musicplayer.data.UserAlbum
import com.musicplayer.utils.SongsFileStorage
import kotlinx.coroutines.runBlocking

/**
 * Общая библиотечная прослойка для bridge-интерфейсов (ContentProvider и локальный HTTP-сервер).
 *
 * Загружает треки и пользовательские плейлисты синхронно из постоянного хранилища,
 * чтобы любой клиент (ExteraGram-плагин, внешний HTTP-клиент) мог получить:
 *  — список всех плейлистов:      [loadPlaylists]
 *  — песни конкретного плейлиста: [resolvePlaylist]
 *  — обложку плейлиста:           [resolvePlaylistCoverUri]
 */
object ExteraGramBridgeLibrary {

    fun loadSongs(context: Context): List<Song> {
        val stored = runCatching {
            runBlocking { SongsFileStorage.load(context) }
        }.getOrNull()
        return stored ?: PreferencesManager(context).loadSongs()
    }

    /** Все пользовательские плейлисты в сохранённом порядке (закреплённые — первыми при выдаче UI). */
    fun loadPlaylists(context: Context): List<UserAlbum> {
        return runCatching { PreferencesManager(context).loadUserAlbums() }.getOrElse { emptyList() }
    }

    /**
     * Плейлист по id + его треки в порядке добавления в плейлист.
     * Треки, удалённые из библиотеки, молча пропускаются.
     */
    fun resolvePlaylist(context: Context, playlistId: String): Pair<UserAlbum, List<Song>>? {
        if (playlistId.isBlank()) return null
        val songs = loadSongs(context)
        val songsById = songs.associateBy { it.id }
        val playlist = loadPlaylists(context).firstOrNull { it.id == playlistId } ?: return null
        val tracks = playlist.songIds.mapNotNull { songsById[it] }
        return playlist to tracks
    }

    /**
     * URI обложки плейлиста: кастомная обложка → обложка выбранного/первого трека.
     * Возвращает null, если обложки нет вовсе.
     */
    fun resolvePlaylistCoverUri(context: Context, playlist: UserAlbum, tracks: List<Song>): Uri? {
        playlist.customCoverUri?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { Uri.parse(raw) }.getOrNull()?.let { return it }
        }
        if (tracks.isEmpty()) return null
        val coverSongId = playlist.coverSongId ?: playlist.songIds.firstOrNull()
        val song = tracks.firstOrNull { it.id == coverSongId } ?: tracks.first()
        return PreferencesManager(context).loadCustomArt(song.id) ?: song.albumArtUri
    }
}
