package com.musicplayer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerCollapseMotionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var motion: PlayerCollapseMotion
    private var closeCount = 0
    private var childDrag = 0f
    private var clicks = 0
    private var releasedVelocity = 0f
    private val travel = 600f

    private fun showPlayer(excluded: Boolean = false, horizontalChild: Boolean = false) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            motion = remember { PlayerCollapseMotion(scope) }
            LaunchedEffect(Unit) { motion.settle(close = false) }
            Box(Modifier.fillMaxSize().testTag("player")
                .playerCollapseGestures(
                    canStart = { !excluded },
                    canDragUp = { motion.fraction < 1f },
                    onStart = { motion.grab() },
                    onDrag = { motion.dragBy(it, travel) },
                    onEnd = { velocity ->
                        releasedVelocity = velocity
                        motion.settle(
                            shouldCollapsePlayer(motion.fraction, velocity, travel, 1f),
                            velocity, travel,
                        ) { closeCount++ }
                    },
                    onCancel = { motion.settle(close = false) },
                )) {
                // A consuming child reproduces the original gesture arbitration bug.
                Box(Modifier.fillMaxSize()
                    .clickable { clicks++ }
                    .pointerInput(horizontalChild) {
                        if (horizontalChild) {
                            detectHorizontalDragGestures { change, dx ->
                                change.consume()
                                childDrag += dx
                            }
                        } else {
                            detectVerticalDragGestures { change, dy ->
                                change.consume()
                                childDrag += dy
                            }
                        }
                    })
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    @Test fun downwardDragFollowsFingerAndCanReverseOverConsumingChild() {
        showPlayer()
        val player = compose.onNodeWithTag("player")
        player.performTouchInput { down(Offset(100f, 100f)); moveBy(Offset(0f, 100f)) }
        var halfway = 0f
        compose.runOnIdle {
            halfway = motion.fraction
            assertTrue(halfway < 1f)
            assertEquals(0f, childDrag)
        }
        player.performTouchInput { moveBy(Offset(0f, -40f)) }
        compose.runOnIdle { assertEquals(halfway + 40f / travel, motion.fraction, 0.001f) }
        player.performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(1500)
        compose.runOnIdle {
            assertEquals(1f, motion.fraction, 0.001f)
            assertEquals(0, closeCount)
        }
    }

    @Test fun horizontalSwipeStaysWithCoverCarousel() {
        showPlayer(horizontalChild = true)
        compose.onNodeWithTag("player").performTouchInput {
            down(Offset(100f, 100f)); moveBy(Offset(120f, 5f)); up()
        }
        compose.runOnIdle {
            assertTrue(childDrag > 0f)
            assertEquals(1f, motion.fraction, 0.001f)
            assertEquals(0, closeCount)
        }
    }

    @Test fun queueAndLyricsAreasKeepTheirVerticalScroll() {
        showPlayer(excluded = true)
        compose.onNodeWithTag("player").performTouchInput {
            down(Offset(100f, 100f)); moveBy(Offset(0f, 120f)); up()
        }
        compose.runOnIdle {
            assertTrue(childDrag > 0f)
            assertEquals(1f, motion.fraction, 0.001f)
        }
    }

    @Test fun tapStillReachesControls() {
        showPlayer()
        compose.onNodeWithTag("player").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun shortSlowDragReturnsWithoutDismissing() {
        showPlayer()
        compose.onNodeWithTag("player").performTouchInput {
            down(Offset(100f, 100f))
            repeat(5) { moveBy(Offset(0f, 10f), delayMillis = 100) }
            up()
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.runOnIdle {
            assertEquals(1f, motion.fraction, 0.001f)
            assertEquals(0, closeCount)
        }
    }

    @Test fun releasedDragClosesOnceAndOnlyAfterTheAnimation() {
        showPlayer()
        compose.onNodeWithTag("player").performTouchInput {
            down(Offset(100f, 100f))
            repeat(5) { moveBy(Offset(0f, 40f), delayMillis = 100) }
            up()
        }
        compose.runOnIdle {
            assertTrue(motion.fraction > 0f)
            assertEquals(0, closeCount)
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.runOnIdle {
            assertEquals(0f, motion.fraction, 0.001f)
            assertEquals(1, closeCount)
            motion.settle(close = true) { closeCount++ }
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.runOnIdle { assertEquals(1, closeCount) }
    }

    @Test fun settlingCanBeGrabbedAndReopenedWithoutPoppingTheScreen() {
        showPlayer()
        compose.runOnIdle { motion.settle(close = true) { closeCount++ } }
        compose.mainClock.advanceTimeBy(80)
        val player = compose.onNodeWithTag("player")
        player.performTouchInput {
            down(Offset(100f, 200f)); moveBy(Offset(0f, -70f))
        }
        var heldFraction = 0f
        compose.runOnIdle { heldFraction = motion.fraction }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle {
            assertEquals(heldFraction, motion.fraction, 0.001f)
            assertEquals(0, closeCount)
        }
        player.performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(1500)
        compose.runOnIdle {
            assertEquals(1f, motion.fraction, 0.001f)
            assertEquals(0, closeCount)
        }
    }

    @Test fun fastFlickUsesDirectionAndMinimumTravel() {
        assertTrue(shouldCollapsePlayer(0.9f, 1400f, travel, 1f))
        assertFalse(shouldCollapsePlayer(0.99f, 1400f, travel, 1f))
        assertFalse(shouldCollapsePlayer(0.5f, -1400f, travel, 1f))
        assertTrue(shouldCollapsePlayer(0.5f, 0f, travel, 1f))
    }

    @Test fun fastShortSwipeClosesUsingMeasuredFingerVelocity() {
        showPlayer()
        compose.onNodeWithTag("player").performTouchInput {
            down(Offset(100f, 100f))
            repeat(5) { moveBy(Offset(0f, 15f), delayMillis = 10) }
            up()
        }
        compose.runOnIdle {
            assertTrue(releasedVelocity > 900f)
            assertTrue(motion.fraction > 0.8f)
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.runOnIdle { assertEquals(1, closeCount) }
    }
}
