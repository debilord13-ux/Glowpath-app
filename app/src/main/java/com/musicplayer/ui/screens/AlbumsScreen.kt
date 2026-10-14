package com.musicplayer.ui.screens

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.musicplayer.viewmodel.MusicViewModel

@Composable
fun AlbumsScreen(
    viewModel: MusicViewModel,
    onPlaybackStarted: () -> Unit,
    isActive: Boolean = true,
) {
    val songs by viewModel.songs.collectAsState()
    val userAlbums by viewModel.userAlbums.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val customArtMap by viewModel.customArtMap.collectAsState()
    val deviceAlbums = remember(songs, customArtMap) { buildDeviceAlbums(songs, customArtMap) }
    val playlists = remember(userAlbums, songs, customArtMap) { buildUserPlaylists(userAlbums, songs, customArtMap) }
    var openAlbumKey by rememberSaveable { mutableStateOf<String?>(null) }
    var editingAlbumId by rememberSaveable { mutableStateOf<String?>(null) }
    var isCreateDialogVisible by rememberSaveable { mutableStateOf(false) }
    var deleteAlbumId by rememberSaveable { mutableStateOf<String?>(null) }

    AlbumLibraryContent(
        state = AlbumLibraryState(deviceAlbums, playlists, currentSong?.id, isPlaying),
        openAlbumKey = openAlbumKey,
        isActive = isActive,
        actions = AlbumLibraryActions(
            onOpen = { openAlbumKey = it },
            onPlay = { queue, start ->
                if (queue.isNotEmpty()) {
                    viewModel.playAlbum(queue, start)
                    onPlaybackStarted()
                }
            },
            onShuffle = { queue ->
                if (queue.isNotEmpty()) {
                    viewModel.playQueue(queue.shuffled())
                    onPlaybackStarted()
                }
            },
            onCreate = { isCreateDialogVisible = true },
            onEdit = { editingAlbumId = it.id },
            onDelete = { deleteAlbumId = it.id },
        ),
    )

    if (isCreateDialogVisible) {
        PlaylistEditorDialog(
            title = "Новый плейлист",
            confirmLabel = "Создать",
            songs = songs,
            viewModel = viewModel,
            initialName = "",
            initialDescription = "",
            initialSelection = emptyList(),
            initialCoverSongId = null,
            initialCustomCoverUri = null,
            initialMotionCoverUri = null,
            initialPinned = false,
            onDismiss = { isCreateDialogVisible = false },
            onConfirm = { name, description, songIds, coverSongId, customCoverUri, motionCoverUri, isPinned ->
                val createdAlbum = viewModel.createUserAlbum(
                    name = name,
                    songIds = songIds,
                    coverSongId = coverSongId,
                    customCoverUri = customCoverUri,
                    motionCoverUri = null,
                    description = description,
                    isPinned = isPinned
                )
                isCreateDialogVisible = false
                createdAlbum?.let { openAlbumKey = "user:${it.id}" }
            }
        )
    }

    val editingAlbum = remember(editingAlbumId, userAlbums) {
        userAlbums.firstOrNull { it.id == editingAlbumId }
    }
    if (editingAlbum != null) {
        PlaylistEditorDialog(
            title = "Редактировать плейлист",
            confirmLabel = "Сохранить",
            songs = songs,
            viewModel = viewModel,
            initialName = editingAlbum.name,
            initialDescription = editingAlbum.description,
            initialSelection = editingAlbum.songIds,
            initialCoverSongId = editingAlbum.coverSongId,
            initialCustomCoverUri = editingAlbum.customCoverUri,
            initialMotionCoverUri = null,
            initialPinned = editingAlbum.isPinned,
            onDismiss = { editingAlbumId = null },
            onConfirm = { name, description, songIds, coverSongId, customCoverUri, motionCoverUri, isPinned ->
                viewModel.updateUserAlbumDetails(
                    albumId = editingAlbum.id,
                    name = name,
                    description = description,
                    songIds = songIds,
                    coverSongId = coverSongId,
                    customCoverUri = customCoverUri,
                    motionCoverUri = null,
                    isPinned = isPinned
                )
                editingAlbumId = null
                openAlbumKey = "user:${editingAlbum.id}"
            }
        )
    }

    val deleteAlbum = remember(deleteAlbumId, userAlbums) {
        userAlbums.firstOrNull { it.id == deleteAlbumId }
    }
    if (deleteAlbum != null) {
        AlertDialog(
            onDismissRequest = { deleteAlbumId = null },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteUserAlbum(deleteAlbum.id)
                        if (openAlbumKey == "user:${deleteAlbum.id}") {
                            openAlbumKey = null
                        }
                        deleteAlbumId = null
                    }
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteAlbumId = null }) {
                    Text("Отмена")
                }
            },
            title = { Text("Удалить плейлист?") },
            text = { Text("Плейлист «${deleteAlbum.name}» будет удалён. Музыкальные файлы останутся на устройстве.") }
        )
    }
}
