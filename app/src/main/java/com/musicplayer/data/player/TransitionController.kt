package com.musicplayer.data.player

import android.content.Context
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
 * Планирует кроссфейд: ждёт точку (duration - fadeMs) и запускает DualPlayerEngine.
 *
 * Длительность из настроек применяется как есть. Если до конца трека меньше,
 * чем выбранный фейд — фейд ужимается, а не отменяется. Раньше короткие
 * треки / перемотка к концу полностью глушили переход, из‑за чего слайдер
 * «длительность» выглядел мёртвым.
 */
class TransitionController(
    private val context: Context,
    private val engine: DualPlayerEngine
) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var schedulerJob: Job? = null

    fun scheduleTransition(
        currentSong: Song,
        nextSong: Song?,
        getPositionMs: () -> Long,
        getDurationMs: () -> Long,
        settings: TransitionSettings,
        onTransitionComplete: () -> Unit,
        onSwapReady: ((Song) -> Unit)? = null,
        onTransitionFailed: (() -> Unit)? = null
    ) {
        cancelScheduler()

        if (settings.mode == TransitionMode.NONE || nextSong == null) {
            if (!engine.isTransitionRunning()) engine.cancelNext()
            return
        }

        if (!engine.isTransitionRunning()) {
            engine.cancelNext()
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
            // Запас до конца исходящего трека увеличен: при обрезке впритык фейд
            // догонял конец трека и обрывался в тишину на последних миллисекундах.
            val maxFade = (duration - 250L).coerceAtLeast(0L)
            val fadeDur = requestedFade.coerceAtMost(maxFade)
            val appliedSettings = if (fadeDur.toInt() == settings.durationMs) {
                settings
            } else {
                settings.copy(durationMs = fadeDur.toInt())
            }

            val transitionPoint = (duration - fadeDur).coerceAtLeast(0L)
            // Упреждающий запас увеличен: за это время успевает докачаться кэш
            // следующего трека и подготовиться плеер B — переход стартует без паузы.
            val prebufferLead = 10_000L.coerceAtLeast(fadeDur + 1_500L)
            val prebufferPoint = (transitionPoint - prebufferLead).coerceAtLeast(0L)
            // Тихий старт B за 400 мс до окна фейда: раскрутка второго декодера
            // происходит до перехода и не слышна как спотыкание играющего трека.
            val silentStartPoint = (transitionPoint - 400L).coerceAtLeast(0L)

            var prebuffered = false

            while (isActive) {
                val pos = getPositionMs().coerceAtLeast(0L)

                if (!prebuffered && pos >= prebufferPoint) {
                    // Кусочная докачка запускается первой: начало трека попадает в
                    // кэш сразу, остальное дописывается фоном во время звучания.
                    TrackPreloader.request(context, nextSong)
                    engine.prepareNext(nextSong.uri)
                    prebuffered = true
                }

                if (pos >= silentStartPoint && pos < transitionPoint) {
                    engine.startNextSilently()
                }

                if (pos >= transitionPoint) {
                    if (!engine.isTransitionRunning()) {
                        // Конец исходящего посреди ожидания готовности B не должен
                        // запускать playNext() и пересоздавать плеер: снимаем completion.
                        engine.holdOutgoingCompletion()
                        if (prebuffered) {
                            val ready = engine.waitUntilNextPrepared()
                            if (!ready) {
                                engine.cancelNext()
                                // Исходящий к этому моменту дошёл до конца молча:
                                // иначе очередь остановится на треке без звука.
                                onTransitionFailed?.invoke()
                                return@launch
                            }
                        } else {
                            TrackPreloader.request(context, nextSong)
                            engine.prepareNext(nextSong.uri)
                            val ready = engine.waitUntilNextPrepared()
                            if (!ready) {
                                engine.cancelNext()
                                onTransitionFailed?.invoke()
                                return@launch
                            }
                        }
                        if (!isActive) return@launch
                        // Фейд мог запуститься с опозданием (B готовилась дольше плана):
                        // ужимаем его до реального остатка исходящего трека, иначе
                        // затухание продолжается в тишине, а входящий звучит позже.
                        val posNow = getPositionMs().coerceAtLeast(0L)
                        val remainingMs = (duration - posNow).coerceAtLeast(0L)
                        val lateSettings = if (remainingMs < appliedSettings.durationMs) {
                            appliedSettings.copy(durationMs = remainingMs.toInt())
                        } else {
                            appliedSettings
                        }
                        engine.performTransition(lateSettings, onTransitionComplete) {
                            onSwapReady?.invoke(nextSong)
                        }
                    }
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
        engine.cancelNext()
    }

    /**
     * Гасит только таймер планировщика, не трогая предзагруженный плеер.
     * Нужен при ручном переходе на уже подготовленный трек: engine.cancelNext()
     * здесь убил бы playerB, который в этот момент становится мастером.
     */
    fun cancelSchedulerOnly() {
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
