package com.fluxx.android.ui.inspector

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/**
 * TEXT F3.3: swipe down from the top of the inspector sheet closes it, on every page and sub-page. It is one sheet-level
 * behaviour (ElementInspectorSheet), so no page can miss it: before F3.3 each header carried its own detector and Edit
 * Text (which draws its own header: Character / Paragraph / Animate, and the Fonts sub-page) had none.
 *
 * The gesture only starts inside the top [band] (the sheet's 8dp frame plus the header row / landing handle), so it
 * never fights the Animate list's vertical scrolling, sliders or the body's controls. It is off only while the source
 * text is being typed (the top of the sheet is then the text field, and the keyboard is up).
 */
internal object InspectorSwipe {
    /** Distance the sheet must follow the finger to close (unchanged from the per-header detectors). */
    const val THRESHOLD_DP = 48f
    /** Sheet frame (top padding) above the header row. */
    const val FRAME_TOP_DP = 8f

    /** Every page has it. The `when` is exhaustive, so a new page must be listed here (and the tests cover each). */
    fun enabled(section: InspectorSection?, textTyping: Boolean): Boolean {
        if (textTyping) return false
        return when (section) {
            null, // landing grid (handle + layer name)
            InspectorSection.TRANSFORM, InspectorSection.BLENDING_OPACITY, InspectorSection.TIMING, InspectorSection.AUDIO,
            InspectorSection.CONTEXTUAL_EDIT, // Edit Text (all tabs and sub-pages) and Edit Solid
            InspectorSection.EFFECTS, InspectorSection.LAYER_STYLES, InspectorSection.TRACK_MATTES, InspectorSection.MOTION_BLUR,
            InspectorSection.GRAPH -> true // GRAPH G1: the Graph page closes the sheet like every other page.
        }
    }

    /** Height of the touch band from the sheet's top edge, in dp: the frame plus one header row at [fontScale]. */
    fun band(fontScale: Float): Float = FRAME_TOP_DP + InspectorChromeBudget.header(fontScale)

    /** Whether a drag of ([dx], [dy]) that has just passed the touch slop is a swipe down (not sideways / upwards). */
    fun isSwipeDown(dx: Float, dy: Float): Boolean = dy > 0f && abs(dy) > abs(dx)
}

/**
 * Sheet-level swipe down. Observes on the Initial pass so it sees the drag before the header's tabs and buttons; it
 * consumes only once a downward vertical drag passes the touch slop (children's taps then cancel), and lets every
 * other gesture (taps, sideways drags, anything below the band) through untouched.
 */
internal fun Modifier.inspectorSwipeDown(enabled: Boolean, band: Dp, onStart: () -> Unit, onDrag: (Float) -> Unit,
    onEnd: (thresholdPx: Float) -> Unit, onCancel: () -> Unit, onDown: () -> Unit = {}, blocked: () -> Boolean = { false }): Modifier =
    if (!enabled) this else pointerInput(band) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            onDown()
            if (down.position.y > band.toPx()) return@awaitEachGesture
            val slop = viewConfiguration.touchSlop
            var total = Offset.Zero
            var dragging = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null) { if (dragging) onCancel(); return@awaitEachGesture }
                    if (!change.pressed) { if (dragging) onEnd(InspectorSwipe.THRESHOLD_DP.dp.toPx()); return@awaitEachGesture }
                    val delta = change.positionChange()
                    if (!dragging) {
                        if (change.isConsumed) return@awaitEachGesture
                        total += delta
                        if (abs(total.x) > slop || abs(total.y) > slop) {
                            if (blocked() || !InspectorSwipe.isSwipeDown(total.x, total.y)) return@awaitEachGesture
                            dragging = true; onStart(); onDrag(total.y - slop); change.consume()
                        }
                    } else { onDrag(delta.y); change.consume() }
                }
            } catch (e: CancellationException) {
                if (dragging) onCancel()
                throw e
            }
        }
    }
