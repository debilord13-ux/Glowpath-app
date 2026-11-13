package com.musicplayer.utils

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.content.RecoverableSecurityException
import com.musicplayer.data.Song
import java.io.File

/**
 * Удаление музыкальных файлов с устройства.
 *
 * API 30+: система сама показывает диалог подтверждения
 * (MediaStore.createDeleteRequest) — отдельные разрешения не нужны.
 * API 29: прямое удаление; чужие файлы кидают RecoverableSecurityException,
 * чей intentSender можно запустить для подтверждения.
 * API 28 и ниже: нужно WRITE_EXTERNAL_STORAGE, затем прямое удаление.
 *
 * Файлы, недоступные для удаления (потоковые, чужие провайдеры), помечаются
 * как сбой — вызывающая сторона решает, удалять ли их из библиотеки.
 */
object DeviceSongDeleter {

    data class Plan(
        val mediaUris: List<Uri>,
        val fileUris: List<Uri>,
        val docUris: List<Uri>,
        val skipped: Int
    ) {
        val total: Int get() = mediaUris.size + fileUris.size + docUris.size
    }

    fun planFor(songs: List<Song>): Plan {
        val media = mutableListOf<Uri>()
        val files = mutableListOf<Uri>()
        val docs = mutableListOf<Uri>()
        var skipped = 0
        for (song in songs) {
            val uri = song.uri
            when (uri.scheme) {
                "content" -> {
                    if (song.id > 0L) {
                        media += ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
                    } else {
                        docs += uri
                    }
                }
                "file" -> files += uri
                else -> skipped++
            }
        }
        return Plan(media, files, docs, skipped)
    }

    /** Системный диалог удаления для набора MediaStore-записей (API 30+). */
    fun createDeletePendingIntent(context: Context, uris: List<Uri>): PendingIntent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || uris.isEmpty()) return null
        return try {
            MediaStore.createDeleteRequest(context.contentResolver, uris)
        } catch (_: Exception) {
            null
        }
    }

    sealed class DirectResult {
        object Done : DirectResult()
        object Failed : DirectResult()
        data class NeedsConfirmation(val sender: android.content.IntentSender) : DirectResult()
    }

    /** Прямое удаление одной записи MediaStore (без системного диалога). */
    fun deleteDirectly(context: Context, uri: Uri): DirectResult {
        return try {
            val rows = context.contentResolver.delete(uri, null, null)
            if (rows > 0) DirectResult.Done else DirectResult.Failed
        } catch (e: Exception) {
            // API 29: чужие медиафайлы подтверждает пользователь через свой intentSender.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                DirectResult.NeedsConfirmation(e.userAction.actionIntent.intentSender)
            } else {
                DirectResult.Failed
            }
        }
    }

    fun deleteFile(uri: Uri): Boolean {
        val path = uri.path ?: return false
        return try {
            File(path).delete()
        } catch (_: Exception) {
            false
        }
    }

    fun deleteDocument(context: Context, uri: Uri): Boolean {
        return try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            } else false
        } catch (_: Exception) {
            false
        }
    }
}
