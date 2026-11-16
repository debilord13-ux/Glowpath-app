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
 * Планирует кроссфейд: ждёт точку (duration - fadeMs) и запускает DualPlayerEngine.
 *
 * Автопереход — это НАЛОЖЕНИЕ двух плееров (кроссфейд) с длительностью ИЗ НАСТРОЕК
 * пользователя: главная фишка приложения. Для каждого перехода готовится свой плеер
 * по прогретому файловому кэшу (TrackPreloader) — поэтому анимация работает без лагов.
 *
 * Длительность из настроек применяется как есть. Если до конца трека меньше,
 * чем выбранный фейд — фейд ужимается, а не отменяется. Раньше короткие
 * треки / перемотка к концу полностью глушили переход, из‑за чего слайдер
 * «длительность» выглядел мёртвым.
 */
class TransitionController(private val engine: DualPlayerEngine) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var schedulerJob: Job? = null

    fun scheduleTransition(
        currentSong: Song,
        nextSong: Song?,
        getPositionMs: () -> Long,
        getDurationMs: () -> Long,
        settings: TransitionSettings,
        onTransitionComplete: () -> Unit,
        onSwapReady: ((Song) -> Unit)? = null
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
            // Оставляем 80 мс запаса до onCompletion исходящего трека.
            val maxFade = (duration - 80L).coerceAtLeast(0L)
            val fadeDur = requestedFade.coerceAtMost(maxFade)
            val appliedSettings = if (fadeDur.toInt() == settings.durationMs) {
                settings
            } else {
                settings.copy(durationMs = fadeDur.toInt())
            }

            val transitionPoint = (duration - fadeDur).coerceAtLeast(0L)
            // Готовим следующий плеер заранее и с запасом: чем раньше — тем выше
            // шанс, что файловый кэш уже докопирован и prepareAsync мгновенен.
            val prebufferLead = 10_000L.coerceAtLeast(fadeDur + 2_000L)
            val prebufferPoint = (transitionPoint - prebufferLead).coerceAtLeast(0L)

            var prebuffered = false

            while (isActive) {
                val pos = getPositionMs().coerceAtLeast(0L)

                if (!prebuffered && pos >= prebufferPoint) {
                    engine.prepareNextAwaitCache(nextSong.uri)
                    prebuffered = true
                }

                if (pos >= transitionPoint) {
                    if (!engine.isTransitionRunning()) {
                        if (prebuffered) {
                            val ready = engine.waitUntilNextPrepared(4_000L)
                            if (!ready) {
                                engine.cancelNext()
                                return@launch
                            }
                        } else {
                            engine.prepareNextAwaitCache(nextSong.uri)
                            val ready = engine.waitUntilNextPrepared(4_000L)
                            if (!ready) {
                                engine.cancelNext()
                                return@launch
                            }
                        }
                        if (!isActive) return@launch
                        // Фейд ужинается под ФАКТИЧЕСКИЙ остаток трека: позиция
                        // обновляется рывками, и срабатывание могло случиться позже
                        // точки перехода. Полный фейд тогда вышел бы за конец трека:
                        // исходящий закончился бы посреди фейда, и на секунду
                        // повисла тишина. Ужимаем — но не отменяем.
                        val remainingMs = (duration - pos).coerceAtMost(fadeDur)
                        if (remainingMs <= 0L) {
                            // До конца трека нет времени даже на укороченный фейд —
                            // отдаём смену onCompletion: кэш следующего трека прогрет,
                            // там разрыв минимальный.
                            engine.cancelNext()
                            return@launch
                        }
                        val transitionSettings = if (remainingMs < fadeDur) {
                            appliedSettings.copy(durationMs = remainingMs.toInt())
                        } else {
                            appliedSettings
                        }
                        engine.performTransition(transitionSettings, onTransitionComplete) {
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

    private fun cancelScheduler() {
        schedulerJob?.cancel()
        schedulerJob = null
    }

    fun release() {
        cancelPendingTransition()
        scope.cancel()
    }
}
