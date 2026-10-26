package com.musicplayer.data.player

import com.musicplayer.data.Curve
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

private const val HALF_PI = (Math.PI / 2.0).toFloat()

/**
 * Значение нарастания входящего трека (0..1) для прогресса перехода (0..1).
 *
 * Для EQUAL_POWER перед синусом применяется S-деформация (smootherstep):
 *  • фейд стартует и заканчивается с нулевым наклоном — переход перестаёт
 *    «щёлкать» на границах и звучит мягче;
 *  • сумма мощностей sin²+cos² по-прежнему равна 1, то есть сохраняется
 *    постоянная громкость посреди кроссфейда (equal-power).
 */
fun fadeIn(progress: Float, curve: Curve): Float {
    val t = progress.coerceIn(0f, 1f)
    return when (curve) {
        Curve.LINEAR -> t
        Curve.EASE_IN -> t.pow(2f)
        Curve.EASE_OUT -> 1f - (1f - t).pow(2f)
        Curve.EASE_IN_OUT -> smoothstep(t)
        Curve.EQUAL_POWER -> sin(smootherstep(t) * HALF_PI)
    }
}

/**
 * Значение затухания исходящего трека (1..0) для прогресса перехода (0..1).
 *
 * Для [Curve.EQUAL_POWER] это cos от деформированного прогресса, а не (1 - sin):
 * иначе в середине кроссфейда появляется заметный провал громкости.
 */
fun fadeOut(progress: Float, curve: Curve): Float {
    val t = progress.coerceIn(0f, 1f)
    return when (curve) {
        Curve.EQUAL_POWER -> cos(smootherstep(t) * HALF_PI)
        else -> 1f - fadeIn(t, curve)
    }
}

/** Старое имя — оставлено для совместимости. */
fun envelope(progress: Float, curve: Curve): Float = fadeIn(progress, curve)

private fun smoothstep(t: Float): Float = if (t < 0.5f) {
    2f * t * t
} else {
    1f - (-2f * t + 2f).pow(2f) / 2f
}

/**
 * Smootherstep (средство сглаживания 5-го порядка): нулевые производные
 * в 0 и 1, монотонность сохранена. Диапазон результата — 0..1.
 */
private fun smootherstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * x * (x * (x * 6f - 15f) + 10f)
}
