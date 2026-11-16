package com.musicplayer.data

/**
 * Режим перехода между треками.
 */
enum class TransitionMode {
    NONE,    // Жёсткая смена по onCompletion — возможна короткая пауза
    OVERLAP  // Кроссфейд / gapless: следующий трек стартует заранее
}

/**
 * Кривая изменения громкости при переходе.
 */
enum class Curve {
    LINEAR,       // Линейная
    EASE_IN,      // Нарастающая
    EASE_OUT,     // Затухающая
    EASE_IN_OUT,  // S-образная
    EQUAL_POWER   // Равная мощность (без провала громкости)
}

/**
 * Настройки перехода между треками.
 *
 * @param mode режим перехода
 * @param durationMs длительность перекрытия в мс (0..12000). 0 = мгновенная
 *   смена без фейда, но со стартом следующего трека встык (без паузы).
 * @param curveIn кривая нарастания нового трека
 * @param curveOut кривая затухания текущего трека
 *
 * Кривые по умолчанию — LINEAR: автопереход идёт линейно и заканчивается резко,
 * без «затухающей» асимметрии EQUAL_POWER (у него сумма громкостей двух разных
 * треков в середине кроссфейда даёт слышимый горб, а хвост тянется вяло).
 */
data class TransitionSettings(
    val mode: TransitionMode = TransitionMode.NONE,
    val durationMs: Int = 2000,
    val curveIn: Curve = Curve.LINEAR,
    val curveOut: Curve = Curve.LINEAR
) {
    val isEnabled: Boolean get() = mode == TransitionMode.OVERLAP

    val durationSeconds: Float
        get() = (durationMs.coerceAtLeast(0)) / 1000f

    fun formattedDuration(): String {
        val seconds = durationSeconds
        val rounded = (seconds * 10f).toInt() / 10f
        return if (rounded == rounded.toInt().toFloat()) {
            "${rounded.toInt()} с"
        } else {
            String.format("%.1f с", rounded)
        }
    }
}
