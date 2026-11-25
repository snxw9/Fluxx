package com.fluxx.android.ui.inspector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxx.android.ui.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.*

/**
 * Log-scale scrub ruler arithmetic. Values are layer scale factors (1 = 100%). The ruler covers
 * 0.05%..10000%; typed values outside it simply pin the needle to the nearest end.
 */
internal object ScaleRulerMath {
    const val MIN_PERCENT = .05f
    const val MAX_PERCENT = 10000f
    const val MIN = MIN_PERCENT / 100f
    const val MAX = MAX_PERCENT / 100f
    /** Drag distance for one doubling of the value. */
    const val DP_PER_DOUBLING = 96f
    /** Soft snap zone around 100%, in doublings (±0.03 ≈ ±2%), dragged through by continuing to move. */
    const val SNAP_DOUBLINGS = .03f
    /** Velocity gain: none below [GAIN_START] dp/s, up to ×[GAIN_MAX] at [GAIN_FULL] dp/s. */
    const val GAIN_START = 400f
    const val GAIN_FULL = 2400f
    const val GAIN_MAX = 2.5f
    /** Inertia: exponential decay time constant and the fling speed that starts it. */
    const val INERTIA_TAU_MS = 300f
    const val INERTIA_MIN_VELOCITY = 600f
    val RANGE_DOUBLINGS = log2(MAX / MIN)
    /** Fixed layout (dp): readout row (scales with font), gap, ruler strip. No weights: the strip never measures 0. */
    const val READOUT_DP = 24f
    const val GAP_DP = 4f
    const val STRIP_DP = 48f
    fun rulerHeight(fontScale: Float) = READOUT_DP * fontScale.coerceAtLeast(1f) + GAP_DP + STRIP_DP
    /** Linked: one ruler; unlinked: Width and Height rulers with a gap. */
    fun controllerHeight(linked: Boolean, fontScale: Float) = if (linked) rulerHeight(fontScale) else 2 * rulerHeight(fontScale) + GAP_DP

    fun gain(velocityDpPerSecond: Float): Float {
        val t = ((abs(velocityDpPerSecond) - GAIN_START) / (GAIN_FULL - GAIN_START)).coerceIn(0f, 1f)
        return 1f + (GAIN_MAX - 1f) * t
    }
    /** Ruler position (doublings from 100%) for a value; pins to the ruler ends, magnitude only. */
    fun position(scale: Float): Float = log2(abs(scale).coerceIn(MIN, MAX))
    fun valueAt(position: Float): Float = 2f.pow(position.coerceIn(log2(MIN), log2(MAX)))
    /**
     * `scale = base * 2^(dx / dpPerDoubling)`, relative to the value at drag start. [travelDoublings] is the
     * accumulated (velocity-weighted) drag in doublings. The sign of a negative (typed) base is preserved.
     */
    fun drag(base: Float, travelDoublings: Float): Float {
        val sign = if (base < 0f) -1f else 1f
        val raw = position(if (base == 0f) MIN else base) + travelDoublings
        val snapped = if (abs(raw) <= SNAP_DOUBLINGS) 0f else raw
        return sign * valueAt(snapped)
    }
    /** The tape follows the finger: dragging right moves the ticks right, so the value under the needle decreases. */
    fun travel(dxDp: Float, velocityDpPerSecond: Float): Float = -dxDp * gain(velocityDpPerSecond) / DP_PER_DOUBLING
    /** Haptic detents at 100%·2^k (…25, 50, 100, 200, 400…). Returns 0 none, 1 light, 2 strong (crossing/landing on 100%). */
    fun detent(old: Float, new: Float): Int {
        val a = position(old); val b = position(new)
        if (a == b) return 0
        if ((a < 0f && b >= 0f) || (a > 0f && b <= 0f)) return 2
        return if (floor(a) != floor(b)) 1 else 0
    }
    /** Inertia travel after a fling, in doublings (same direction rule as [travel]); decays, so it always ends. */
    fun inertiaStep(velocityDpPerSecond: Float, elapsedMs: Float): Float =
        -velocityDpPerSecond * gain(velocityDpPerSecond) / DP_PER_DOUBLING * (INERTIA_TAU_MS / 1000f) * (1f - exp(-elapsedMs / INERTIA_TAU_MS))

    /** Linked (uniform) scaling keeps the X:Y ratio captured at drag start; result within the layer scale hard cap. */
    fun linkedY(initialX: Float, initialY: Float, x: Float): Float {
        val cap = PropertyLimits.LAYER_SCALE.hardMax / 100f
        val y = if (initialX == 0f || !initialX.isFinite()) x else initialY * x / initialX
        return if (y.isFinite()) y.coerceIn(-cap, cap) else x
    }
    data class Tick(val percent: Float, val position: Float, val major: Boolean, val label: String?)
    /** Majors at 1/2/5 × 10^n %, minors at the other integer multiples; built once. */
    val TICKS: List<Tick> = buildList {
        for (exponent in -2..4) {
            val decade = 10.0.pow(exponent)
            for (m in 1..9) {
                val p = (m * decade).toFloat()
                if (p < MIN_PERCENT * .999f || p > MAX_PERCENT * 1.001f) continue
                val major = m == 1 || m == 2 || m == 5
                add(Tick(p, log2(p / 100f), major, if (major) label(p) else null))
            }
        }
    }
    fun label(percent: Float): String = when {
        percent >= 1000f -> "${(percent / 1000f).let { if (it == it.toInt().toFloat()) it.toInt().toString() else it.toString() }}k"
        percent >= 1f -> percent.roundToInt().toString()
        else -> String.format(java.util.Locale.ROOT, "%.2f", percent).trimEnd('0').trimEnd('.')
    }
    fun readout(scale: Float): String {
        val p = scale * 100f
        return when {
            abs(p) >= 1000f -> "${p.roundToInt()}%"
            abs(p) >= 10f -> "${String.format(java.util.Locale.ROOT, "%.1f", p).removeSuffix(".0")}%"
            else -> "${String.format(java.util.Locale.ROOT, "%.2f", p).trimEnd('0').trimEnd('.')}%"
        }
    }
    /** Tick labels clear the needle: fully faded when a label touches it, fully shown from [NEEDLE_CLEAR_DP] away. */
    const val NEEDLE_CLEAR_DP = 12f
    /** Smooth (smoothstep) label fade from the gap between the label's nearest edge and the needle, in dp. */
    fun needleFade(edgeGapDp: Float): Float {
        val t = (edgeGapDp / NEEDLE_CLEAR_DP).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
    /** Edge fade: 1 at the needle, 0 at the strip ends. */
    fun fade(offsetFraction: Float): Float = (1f - offsetFraction.coerceIn(0f, 1f).pow(2)).coerceIn(0f, 1f)
}

/**
 * Polished log-scale scrub ruler: fixed centre needle, ticks scroll underneath, edge fade, large readout
 * that can be typed (double-tap resets to 100%). One undo per drag (including any inertia). Single Canvas,
 * tick geometry and labels prepared once; nothing is allocated per frame.
 */
@Composable internal fun LogScaleRuler(label: String, value: Float, onBegin: () -> Unit, onPreview: (Float) -> Unit,
    onCommit: () -> Unit, onCancel: () -> Unit, onTyped: (Float) -> Unit, onReset: () -> Unit, modifier: Modifier = Modifier,
    readoutDp: Float? = null, stripDp: Float = ScaleRulerMath.STRIP_DP,
    /** GRAPH G2.1 §9: magnetic 25% detents while dragging (and during inertia); typed values are never snapped. */
    magnetic: Boolean = false) {
    val view = LocalView.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, color = TextSecondary)
    val labels: List<TextLayoutResult?> = remember(measurer, labelStyle) { ScaleRulerMath.TICKS.map { t -> t.label?.let { measurer.measure(it, labelStyle) } } }
    var shown by remember { mutableFloatStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var inertia by remember { mutableStateOf<Job?>(null) }
    val begin by rememberUpdatedState(onBegin); val preview by rememberUpdatedState(onPreview)
    val commit by rememberUpdatedState(onCommit); val cancel by rememberUpdatedState(onCancel)
    val current by rememberUpdatedState(value)
    LaunchedEffect(value) { if (!dragging && inertia?.isActive != true) shown = value }
    DisposableEffect(Unit) { onDispose { if (dragging || inertia?.isActive == true) { inertia?.cancel(); cancel() } } }
    val magnet = remember(magnetic, density) { if (magnetic) with(density) {
        TransformDetents.scale(ScaleRulerMath.DP_PER_DOUBLING.dp.toPx(), TransformDetents.ZONE_DP.dp.toPx()) } else null }
    fun publish(raw: Float) {
        val next = magnet?.apply(raw) { InspectorHaptics.tick(view) } ?: raw
        when (ScaleRulerMath.detent(shown, next)) { 2 -> InspectorHaptics.strong(view); 1 -> InspectorHaptics.tick(view) }
        shown = next; preview(next)
    }
    val fontScale = density.fontScale.coerceAtLeast(1f)
    Column(modifier.testTag("scale-ruler-$label"), verticalArrangement = Arrangement.spacedBy(ScaleRulerMath.GAP_DP.dp)) {
        // [readoutDp] is the final (already font-scaled) readout height from SectionLayout; null = the legacy default.
        Box(Modifier.fillMaxWidth().height((readoutDp ?: (ScaleRulerMath.READOUT_DP * fontScale)).dp), contentAlignment = Alignment.Center) {
            Row(Modifier.pointerInput(Unit) { detectTapGestures(onTap = { editing = true }, onDoubleTap = { onReset() }) }
                .semantics(mergeDescendants = true) {
                    contentDescription = "$label ${ScaleRulerMath.readout(shown)}. Tap to type, double-tap to reset to 100 percent"
                    onClick("Type $label") { editing = true; true }
                }, verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = TextSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 6.dp))
                Text(ScaleRulerMath.readout(shown), color = TextPrimary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium, lineHeight = 22.sp, maxLines = 1)
                AnchoredPopover(editing, { editing = false }, "scale-readout-entry") {
                    InspectorValueField(PropertyLimits.format(PropertyLimits.LAYER_SCALE, shown * 100f), label, "%",
                        { text -> editing = false; PropertyLimits.parseTyped(PropertyLimits.LAYER_SCALE, text)?.let { onTyped(it / 100f) } }, { editing = false })
                }
            }
        }
        Canvas(Modifier.fillMaxWidth().height(stripDp.dp).testTag("scale-ruler-strip").clip(RoundedCornerShape(10.dp)).background(SurfaceHigh)
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .semantics {
                contentDescription = "$label ruler, ${ScaleRulerMath.readout(shown)}"
                stateDescription = ScaleRulerMath.readout(shown)
                setProgress("Set $label") { target -> val v = target.coerceIn(ScaleRulerMath.MIN, ScaleRulerMath.MAX); onTyped(v); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // A touch stops any inertia; the stopped fling commits its value as its own undo entry.
                    inertia?.let { job -> if (job.isActive) { job.cancel(); commit() } }; inertia = null
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    val base = current
                    magnet?.reset()
                    var travel = 0f
                    var started = false
                    val pxPerDp = density.density
                    val completed = horizontalDrag(down.id) { change ->
                        val dxDp = (change.position.x - change.previousPosition.x) / pxPerDp
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!started) { started = true; dragging = true; begin() }
                        val v = tracker.calculateVelocity().x / pxPerDp
                        travel += ScaleRulerMath.travel(dxDp, v)
                        change.consume()
                        publish(ScaleRulerMath.drag(base, travel))
                    }
                    if (!started) return@awaitEachGesture
                    dragging = false
                    if (!completed) { cancel(); shown = current; return@awaitEachGesture }
                    val velocity = tracker.calculateVelocity().x / pxPerDp
                    if (abs(velocity) < ScaleRulerMath.INERTIA_MIN_VELOCITY) { commit(); return@awaitEachGesture }
                    val startTravel = travel
                    inertia = scope.launch {
                        val start = withFrameNanos { it }
                        var elapsed = 0f
                        while (elapsed < ScaleRulerMath.INERTIA_TAU_MS * 4) {
                            elapsed = (withFrameNanos { it } - start) / 1_000_000f
                            publish(ScaleRulerMath.drag(base, startTravel + ScaleRulerMath.inertiaStep(velocity, elapsed)))
                        }
                        inertia = null
                        commit()
                    }
                }
            }) {
            val pxPerDoubling = ScaleRulerMath.DP_PER_DOUBLING.dp.toPx()
            val half = size.width / 2f
            val here = ScaleRulerMath.position(shown)
            val baseY = size.height - 6.dp.toPx()
            val labelTop = 3.dp.toPx()
            val ticks = ScaleRulerMath.TICKS
            for (i in ticks.indices) {
                val t = ticks[i]
                val x = half + (t.position - here) * pxPerDoubling
                if (x < -1f || x > size.width + 1f) continue
                val fade = ScaleRulerMath.fade(abs(x - half) / half)
                if (fade <= .02f) continue
                val hundred = abs(t.percent - 100f) < .001f
                val length = (if (hundred) 22.dp else if (t.major) 14.dp else 7.dp).toPx() * (.55f + .45f * fade)
                val ink = if (hundred) TextPrimary else if (t.major) TextPrimary.copy(alpha = .75f) else TextSecondary
                drawLine(ink.copy(alpha = ink.alpha * fade), Offset(x, baseY - length), Offset(x, baseY), (if (hundred) 1.6.dp else 1.dp).toPx(), StrokeCap.Round)
                labels[i]?.let { l ->
                    // Labels never collide with the needle: fade smoothly as the label nears it.
                    val clear = ScaleRulerMath.needleFade((abs(x - half) - l.size.width / 2f) / this.density)
                    val alpha = fade * clear
                    if (fade > .25f && alpha > .01f) drawText(l, topLeft = Offset(x - l.size.width / 2f, labelTop), alpha = alpha)
                }
            }
            // Fixed needle: brighter than any tick, with a notch at the top edge.
            drawLine(TextPrimary, Offset(half, 2.dp.toPx()), Offset(half, size.height - 2.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
            drawCircle(TextPrimary, 2.5.dp.toPx(), Offset(half, 3.dp.toPx()))
        }
    }
}

@Preview(widthDp = 320, heightDp = 120, backgroundColor = 0xFF1C1C1C, showBackground = true)
@Composable private fun LogScaleRulerPreview() {
    var v by remember { mutableFloatStateOf(1.75f) }
    LogScaleRuler("Scale", v, {}, { v = it }, {}, {}, { v = it }, { v = 1f }, Modifier.padding(8.dp).fillMaxWidth())
}

/** Unlinked Width + Height rulers (TEXT UI 8: fixed 2 x 80dp + 4, centred) in the 208dp content of a 268dp sheet. */
@Preview(widthDp = 260, heightDp = 208, backgroundColor = 0xFF1C1C1C, showBackground = true)
@Composable private fun LogScaleRulerCompactPreview() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(ScaleRulerMath.GAP_DP.dp, Alignment.CenterVertically)) {
        LogScaleRuler("Width", 1f, {}, {}, {}, {}, {}, {}, Modifier.fillMaxWidth(), SectionLayout.ruler(1f).readout, SectionLayout.STRIP)
        LogScaleRuler("Height", 2.5f, {}, {}, {}, {}, {}, {}, Modifier.fillMaxWidth(), SectionLayout.ruler(1f).readout, SectionLayout.STRIP)
    }
}
