package com.musicplayer.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/** A single, interruptible position for opening, dragging, Back and settling. */
internal class PlayerCollapseMotion(private val scope: CoroutineScope) {
    private val animation = Animatable(0f, visibilityThreshold = 0.001f)
    private var draggedFraction by mutableStateOf<Float?>(null)
    private var job: Job? = null
    private var dismissed = false
    val fraction: Float get() = (draggedFraction ?: animation.value).coerceIn(0f, 1f)

    fun grab() {
        val current = fraction
        job?.cancel()
        draggedFraction = current
    }

    fun dragBy(pixels: Float, travel: Float) {
        draggedFraction = (fraction - pixels / travel.coerceAtLeast(1f)).coerceIn(0f, 1f)
    }

    fun settle(close: Boolean, velocity: Float = 0f, travel: Float = 1f, onClosed: () -> Unit = {}) {
        if (dismissed) return
        val current = fraction
        job?.cancel()
        // Preserve the current frame until the coroutine has synchronised the Animatable.
        draggedFraction = current
        job = scope.launch {
            animation.snapTo(current)
            draggedFraction = null
            animation.animateTo(
                if (close) 0f else 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = if (close) 320f else 400f,
                    visibilityThreshold = 0.001f,
                ),
                initialVelocity = (-velocity / travel.coerceAtLeast(1f)).coerceIn(-6f, 6f),
            )
            if (close && !dismissed) {
                dismissed = true
                onClosed()
            }
        }
    }
}

internal fun shouldCollapsePlayer(fraction: Float, velocity: Float, travel: Float, density: Float): Boolean {
    val distance = (1f - fraction) * travel
    // An intentional flick back up reopens even after crossing the distance threshold.
    if (velocity < -900f * density) return false
    return distance >= minOf(140f * density, travel * 0.25f) ||
        (distance > 24f * density && velocity > 900f * density)
}

/**
 * Claim only a downward vertical drag, before child click/scroll detectors consume it.
 * Coordinates belong to the stationary screen, never to the moving player surface.
 * Once claimed, the same gesture can freely reverse direction. Horizontal gestures,
 * taps and excluded areas (queue/lyrics) continue through the normal event passes.
 */
internal fun Modifier.playerCollapseGestures(
    enabled: Boolean = true,
    canStart: (Offset) -> Boolean = { true },
    canDragUp: () -> Boolean = { false },
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onEnd: (Float) -> Unit,
    onCancel: () -> Unit,
): Modifier = composed {
    val latestCanStart by rememberUpdatedState(canStart)
    val latestCanDragUp by rememberUpdatedState(canDragUp)
    val latestStart by rememberUpdatedState(onStart)
    val latestDrag by rememberUpdatedState(onDrag)
    val latestEnd by rememberUpdatedState(onEnd)
    val latestCancel by rememberUpdatedState(onCancel)
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!latestCanStart(down.position)) return@awaitEachGesture
            val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
            var claimed = false
            var released = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    // A second finger cancels rather than jumping to a new pointer.
                    if (event.changes.count { it.pressed } > 1) break
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    // Compose represents ACTION_CANCEL as an already-consumed up.
                    // Check consumption before release so cancellation always reopens.
                    if (change.isConsumed) break
                    tracker.addPosition(change.uptimeMillis, change.position)
                    if (!change.pressed) {
                        if (claimed) {
                            change.consume()
                            released = true
                            latestEnd(tracker.calculateVelocity().y)
                        }
                        break
                    }
                    if (!claimed) {
                        val total = change.position - down.position
                        if (maxOf(abs(total.x), abs(total.y)) <= viewConfiguration.touchSlop) continue
                        if (abs(total.x) >= abs(total.y)) break
                        if (total.y < 0f && !latestCanDragUp()) break
                        claimed = true
                        latestStart()
                        latestDrag(total.y - sign(total.y) * viewConfiguration.touchSlop)
                    } else {
                        latestDrag(change.position.y - change.previousPosition.y)
                    }
                    change.consume()
                }
            } finally {
                if (claimed && !released) latestCancel()
            }
        }
    }
}
