package com.musicplayer.data.player

import com.musicplayer.data.Song
import com.musicplayer.data.TransitionMode
import com.musicplayer.data.TransitionSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Планирует ПОСЛЕДОВАТЕЛЬНЫЙ переход между треками — без наложения двух плееров.
 *
 * Контроллер — только «будильник»: он ждёт точку (длительность − фейд) и один раз
 * вызывает onTransitionPoint(fadeMs). Всю механику выполняет MusicViewModel, ровно
 * та же, что и при ручном переключении: линейный фейд огибающей мастера гасит
 * текущий трек, затем он останавливается, и ТОЛЬКО после этого стартует следующий
 * с плавным нарастанием. У этой схемы нет «мини-зависаний» кроссфейда, потому что
 * для каждого перехода создаётся своя анимация на уже прогретом кэше.
 *
 * Если до конца трека меньше, чем выбранный фейд — фейд ужимается, а не отменяется.
 * При durationMs == 0 («Встык») расписание не строится вовсе: смена происходит по
 * факту окончания трека (onSongComplete → playNext), файловый кэш следующего трека
 * уже прогрет — это исключает гонку «комплит против перехода».
 */
class TransitionController {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var schedulerJob: Job? = null

    fun scheduleTransition(
        currentSong: Song,
        nextSong: Song?,
        getPositionMs: () -> Long,
        getDurationMs: () -> Long,
        settings: TransitionSettings,
        onTransitionPoint: (fadeMs: Long) -> Unit
    ) {
        cancelScheduler()

        if (settings.mode == TransitionMode.NONE || nextSong == null) {
            return
        }

        schedulerJob = scope.launch {
            var duration = getDurationMs()
            var waitCycles = 0
            while ((duration <= 0L) && isActive && waitCycles < 60) {
                delay(200)
                duration = getDurationMs()
                waitCycles++
            }
            if (!isActive || duration <= 0L) return@launch

            val requestedFade = settings.durationMs.toLong().coerceAtLeast(0L)
            // Оставляем 80 мс запаса до onCompletion исходящего трека.
            val maxFade = (duration - 80L).coerceAtLeast(0L)
            val fadeDur = requestedFade.coerceAtMost(maxFade)
            val transitionPoint = (duration - fadeDur).coerceAtLeast(0L)

            while (isActive) {
                val pos = getPositionMs().coerceAtLeast(0L)

                if (pos >= transitionPoint) {
                    onTransitionPoint(fadeDur)
                    break
                }

                val remaining = transitionPoint - pos
                delay(
                    when {
                        remaining > 5_000L -> 400L
                        remaining > 1_000L -> 80L
                        else -> 16L
                    }
                )
            }
        }
    }

    fun cancelPendingTransition() {
        cancelScheduler()
    }

    private fun cancelScheduler() {
        schedulerJob?.cancel()
        schedulerJob = null
    }

    fun release() {
        cancelPendingTransition()
        scope.cancel()
    }
}
