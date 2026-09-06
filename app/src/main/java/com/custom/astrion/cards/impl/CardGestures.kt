package com.custom.astrion.cards.impl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A held DPAD_CENTER/Enter key doesn't naturally produce a "long click" in
 * Compose Foundation — combinedClickable's onLongClick only ever fires from
 * a touch gesture's press duration, never from a hardware key held down
 * (which just sends repeated KeyEvents, no built-in "long key press"
 * concept). This starts its own hold timer on KeyDown via
 * onPreviewKeyEvent — which runs before combinedClickable sees the event —
 * and, if the hold lasted long enough, consumes the eventual KeyUp so a
 * paired combinedClickable's onClick doesn't also fire for the same press.
 *
 * Shared by every card with a long-press-to-open-detail-dialog gesture
 * (LightCard, CoverCard) — chain it before .combinedClickable(...):
 *   rememberLongPressKeyModifier(entityId) { showDetail = true }
 *       .combinedClickable(onClick = ..., onLongClick = { showDetail = true })
 */
@Composable
internal fun rememberLongPressKeyModifier(key: Any?, onLongPress: () -> Unit): Modifier {
    val scope = rememberCoroutineScope()
    var longPressJob by remember(key) { mutableStateOf<Job?>(null) }
    var longPressFired by remember(key) { mutableStateOf(false) }
    return Modifier.onPreviewKeyEvent { event ->
        if (event.key != Key.DirectionCenter && event.key != Key.Enter && event.key != Key.NumPadEnter) {
            return@onPreviewKeyEvent false
        }
        when (event.type) {
            KeyEventType.KeyDown -> {
                if (longPressJob == null) {
                    longPressFired = false
                    longPressJob =
                        scope.launch {
                            delay(500L)
                            longPressFired = true
                            onLongPress()
                        }
                }
                false
            }
            KeyEventType.KeyUp -> {
                longPressJob?.cancel()
                longPressJob = null
                val consumed = longPressFired
                longPressFired = false
                consumed
            }
            else -> false
        }
    }
}

/**
 * Sentinel value for [rememberDragFraction]'s state when no drag is in
 * progress. A real drag fraction is always in `0f..1f`, so -1f avoids
 * colliding with a legitimate value and skips Float autoboxing on the
 * recompose path (a primitive `MutableFloatState` is used).
 */
internal const val DRAG_FRACTION_INACTIVE = -1f

/**
 * Mutable drag-override state for a card's horizontal slider bar (LightCard
 * brightness/color-temperature, CoverCard position/tilt). While the value is
 * `>= 0f` the slider shows it instead of the live entity fraction, giving
 * immediate finger-tracking feedback until Home Assistant confirms the
 * committed value.
 *
 * Create one per slider with this function. Hoist the call into the parent
 * card — and reuse the returned state there — when the tile also needs to
 * mirror the in-progress value (e.g. LightCard's state label); otherwise
 * call it inside the slider composable.
 *
 * Pair with [TrackDragFraction] to release the override back to the live
 * value at the right moment.
 */
@Composable
internal fun rememberDragFraction(key: Any?): MutableFloatState = remember(key) { mutableFloatStateOf(DRAG_FRACTION_INACTIVE) }

/**
 * Clears the [rememberDragFraction] override once it's safe to follow the
 * live entity value again. Either condition drops it:
 *
 *  - The live fraction catches up to the committed drag value (within ~2%),
 *    so subsequent external changes are reflected. Without this the bar
 *    would snap back to the stale pre-drag value between onDragEnd and HA's
 *    state_changed event arriving.
 *
 *  - [active] goes false (the light is turned off out-of-band while a
 *    pending override is still set). The catch-up above can never fire when
 *    the live value reads 0 while off, so the override has to be dropped
 *    explicitly. Defaults to true for sliders with no "off" state (covers).
 */
@Composable
internal fun TrackDragFraction(drag: MutableFloatState, liveFraction: Float, active: Boolean = true) {
    var dragFraction by drag
    LaunchedEffect(liveFraction, dragFraction) {
        if (dragFraction >= 0f && abs(liveFraction - dragFraction) < 0.02f) {
            dragFraction = DRAG_FRACTION_INACTIVE
        }
    }
    LaunchedEffect(active) {
        if (!active && dragFraction >= 0f) {
            dragFraction = DRAG_FRACTION_INACTIVE
        }
    }
}
