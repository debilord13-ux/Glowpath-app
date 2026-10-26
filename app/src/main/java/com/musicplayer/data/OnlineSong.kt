package com.musicplayer.data

/**
 * Represents a track found by online search (Hitmos, Deezer, iTunes и т.д.).
 * [downloadUrl] is the direct link ready for streaming or downloading.
 * [sourceLabel] — человекочитаемое имя источника («Hitmos», «Deezer · фрагмент»…).
 * [alternatives] — варианты той же песни с других сайтов: та же карточка,
 * но можно переключить источник воспроизведения/загрузки.
 */
data class OnlineSong(
    val title: String,
    val artist: String,
    val duration: String,       // e.g. "3:45"
    val downloadUrl: String,    // direct https://... mp3/m4a link
    val sizeLabel: String = "", // e.g. "7.2 MB"
    val coverUrl: String = "",
    val sourceLabel: String = "",
    val alternatives: List<OnlineSong> = emptyList()
) {
    /** Synthesise a display string similar to local Song. */
    val displayTitle: String get() = if (artist.isNotBlank() && artist != "Unknown") "$artist – $title" else title

    /** Все проигрываемые варианты трека, включая выбранный по умолчанию. */
    val allVariants: List<OnlineSong>
        get() = listOf(this) + alternatives
}

data class OnlineAlbumSummary(
    val title: String,
    val artist: String,
    val albumUrl: String,
    val coverUrl: String = "",
    val subtitle: String = "",
    val badge: String = ""
)

data class OnlineAlbumSection(
    val title: String,
    val subtitle: String = "",
    val tracks: List<OnlineSong>
)

data class OnlineAlbumDetail(
    val albumUrl: String,
    val title: String,
    val artist: String,
    val coverUrl: String = "",
    val description: String = "",
    val chips: List<String> = emptyList(),
    val sections: List<OnlineAlbumSection> = emptyList()
)

data class OnlineSearchPayload(
    val songs: List<OnlineSong>,
    val albums: List<OnlineAlbumSummary>
)

sealed class OnlineSearchState {
    object Idle    : OnlineSearchState()
    object Loading : OnlineSearchState()
    data class Success(
        val songs: List<OnlineSong>,
        val albums: List<OnlineAlbumSummary>
    ) : OnlineSearchState()
    data class Empty(val query: String) : OnlineSearchState()
    data class Error(val message: String) : OnlineSearchState()
}

sealed class OnlineAlbumSheetState {
    object Hidden : OnlineAlbumSheetState()
    data class Loading(val album: OnlineAlbumSummary) : OnlineAlbumSheetState()
    data class Success(
        val album: OnlineAlbumSummary,
        val detail: OnlineAlbumDetail
    ) : OnlineAlbumSheetState()
    data class Error(
        val album: OnlineAlbumSummary,
        val message: String
    ) : OnlineAlbumSheetState()
}

sealed class DownloadState {
    object Idle      : DownloadState()
    object Pending   : DownloadState()
    data class Progress(val percent: Int) : DownloadState()
    data class Done(val filePath: String) : DownloadState()
    data class Err(val message: String)  : DownloadState()
}
