package com.fluxx.android.ui.timeline

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag

import androidx.compose.ui.unit.dp
import com.fluxx.android.editor.*
import com.fluxx.android.model.CompositionLayer
import com.fluxx.android.model.ProjectDocument
import com.fluxx.android.ui.common.colorpicker.ColourPickerVariant
import com.fluxx.android.ui.common.colorpicker.FluxxColourPicker
import com.fluxx.android.ui.inspector.AnchoredPopover
import com.fluxx.android.ui.inspector.GraphValues
import com.fluxx.android.ui.inspector.InspectorHaptics
import com.fluxx.android.ui.inspector.InspectorValueField
import com.fluxx.android.ui.theme.TextSecondary
import kotlin.math.roundToLong

/** TL3 B3: the editor's side of twirl-row key interactions (selection changes and value edits; one undo each). */
class TwirlKeyHost(
    /** SELECT (enter key mode with this key) or TOGGLE (in key mode). */
    val onEffect: (layerId: Long, target: GraphTarget, time: Long, effect: KeyGestureEffect) -> Unit,
    /** The selection after a group move, set just before the commit (the selection follows its keys). */
    val onMoved: (KeySelectionSet?) -> Unit,
    /** A typed value on one axis for every key of the scope ([MultiKeyEdits.setValue]). */
    val onValue: (scope: List<KeyframeSelection>, axis: Int, stored: Float) -> Unit,
    /** B4: add a colour key at [localUs] on a colour row, keeping the bar's look. */
    val onAddColourKey: (layerId: Long, target: GraphTarget, localUs: Long) -> Unit = { _, _, _ -> },
)

/** TL3 B3 / B4: the ONE twirl popover open at a time (opening another replaces it). UI state only. */
internal sealed interface TwirlPopover {
    val layerId: Long; val target: GraphTarget
    /** A key's value popover; [single]: only this key (a hold release on an unselected key in key mode). */
    data class Value(override val layerId: Long, override val target: GraphTarget, val time: Long, val single: Boolean) : TwirlPopover
    /** B4: the colour bar between two keys, at composition time [compositionUs]. */
    data class Bar(override val layerId: Long, override val target: GraphTarget, val compositionUs: Long) : TwirlPopover
}

/** The timeline's gesture protocol for twirl rows (begin / preview / commit / cancel: unchanged). */
internal class TwirlGestures(val begin: () -> Unit, val preview: (EditorAction) -> Unit, val commit: () -> Unit, val cancel: () -> Unit)

/**
 * TL3 B3: a keyed property row with its key gestures (REPLACING the double-tap rules):
 * - outside key mode: a TAP opens the value popover at once; a HOLD released without moving selects the key and enters
 *   key mode; a HOLD and DRAG moves the key alone (inside its clip);
 * - in key mode: a TAP toggles the key at once; a HOLD released without moving opens the value popover (on an unselected
 *   key: for that key alone); a HOLD and DRAG on a selected key moves the whole selection (every track, every layer).
 * Drags snap ([DragSnap], per-class [SnapZones]; every moving key is a candidate, in composition time) and report the
 * readouts. Nothing seeks.
 */
@Composable internal fun TwirlKeyRow(project: ProjectDocument, layer: CompositionLayer, row: TwirlRow.Property, pxPerUs: Double, playheadUs: Long,
    selection: KeySelectionSet?, visible: VisibleRange, popover: TwirlPopover?, onPopover: (TwirlPopover?) -> Unit,
    host: TwirlKeyHost, gestures: TwirlGestures, snapLevel: RulerLevel?, snapStartFrame: Long,
    onDragInfo: (TimelineDragInfo?) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current
    val latest by rememberUpdatedState(Triple(project, layer, selection))
    val latestPlayhead by rememberUpdatedState(playheadUs)
    val latestLevel by rememberUpdatedState(snapLevel)
    val latestHost by rememberUpdatedState(host)
    val latestGestures by rememberUpdatedState(gestures)
    val latestDragInfo by rememberUpdatedState(onDragInfo)
    val latestPopover by rememberUpdatedState(onPopover)
    val halfHit = with(density) { (LayerKeyMarkers.HIT_WIDTH_DP / 2f).dp.toPx() }
    val selectedTimes = selection?.timesOf(row.layerId, row.target).orEmpty()
    TwirlTrackRow(layer, row, pxPerUs, playheadUs, selectedTimes, visible, modifier.pointerInput(row.key, pxPerUs) {
        var hit: Long? = null
        var base: Pair<ProjectDocument, KeySelectionSet>? = null
        var clip: LongRange? = null
        var last: MultiKeyEdits.Moved? = null
        var locked: SnapTarget? = null
        var groupDrag = false
        fun keyMode() = latest.third != null
        fun selected(t: Long) = latest.third?.contains(row.layerId, row.target, t) == true
        detectKeyGestures(
            onDown = { p -> val (_, l, _) = latest; hit = nearestKey(TwirlRows.keyTimes(l, row.target), l, pxPerUs, p.x, halfHit); hit != null },
            onTap = { val t = hit ?: return@detectKeyGestures
                when (KeyGestureRules.tap(KeyPlace.TWIRL, keyMode())) {
                    KeyGestureEffect.OPEN_POPOVER -> latestPopover(TwirlPopover.Value(row.layerId, row.target, t, single = false))
                    KeyGestureEffect.TOGGLE -> latestHost.onEffect(row.layerId, row.target, t, KeyGestureEffect.TOGGLE)
                    else -> {}
                } },
            onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
            onHoldRelease = { val t = hit ?: return@detectKeyGestures
                when (val e = KeyGestureRules.holdRelease(KeyPlace.TWIRL, keyMode(), selected(t))) {
                    KeyGestureEffect.SELECT -> latestHost.onEffect(row.layerId, row.target, t, e)
                    KeyGestureEffect.OPEN_POPOVER -> latestPopover(TwirlPopover.Value(row.layerId, row.target, t, single = false))
                    KeyGestureEffect.OPEN_POPOVER_SINGLE -> latestPopover(TwirlPopover.Value(row.layerId, row.target, t, single = true))
                    else -> {}
                } },
            onDragStart = { val t = hit ?: return@detectKeyGestures
                val (p0, l0, sel) = latest
                // A selected key in key mode carries the whole selection; any other key moves alone, inside its clip.
                val group = sel != null && sel.contains(row.layerId, row.target, t)
                groupDrag = group
                base = p0 to (if (group) sel!! else KeySelectionSet(listOf(KeyframeSelection(row.layerId, row.target, listOf(t)))))
                clip = if (group) null else l0.timing.startUs..(l0.timing.endUs ?: l0.timing.startUs)
                last = null; locked = null
                latestPopover(null)
                latestGestures.begin()
            },
            onDrag = { dx ->
                val (p0, sel0) = base ?: return@detectKeyGestures
                val raw = (dx / pxPerUs).roundToLong()
                val layers = sel0.tracks.associate { it.layerId to p0.layer(it.layerId)!! }
                val candidates = sel0.tracks.flatMap { t -> t.times.map { Math.addExact(layers.getValue(t.layerId).resolvedKeyframeAnchorUs, it) } }
                val dragged = p0.composition.layers.map { it.id }.toSet() - sel0.layerIds.toSet()
                val targets = DragSnap.withTicks(DragSnap.targets(p0, latestPlayhead, dragged, clipEdges = false), candidates, raw,
                    p0.composition.frameRate, latestLevel, snapStartFrame)
                val snap = DragSnap.snap(raw, candidates, targets, SnapZones.of(pxPerUs / density.density, p0.composition.frameRate))
                if (snap.target != null && snap.target != locked) InspectorHaptics.tick(view)
                locked = snap.target
                val moved = MultiKeyEdits.moveTogether(p0, sel0, snap.deltaUs, clip)
                last = moved
                latestGestures.preview(moved?.action ?: EditorAction.Batch(emptyList()))
                val now = moved?.selection?.tracks?.flatMap { t -> t.times.map { Math.addExact(layers.getValue(t.layerId).resolvedKeyframeAnchorUs, it) } } ?: candidates
                latestDragInfo(TimelineDragInfo(now.min(), candidates.min(), now.min(), now.max()))
            },
            onDragEnd = {
                val moved = last
                if (moved != null) {
                    // The selection follows its keys (a group); a lone key keeps the selection as it is.
                    if (groupDrag) latestHost.onMoved(moved.selection)
                    latestGestures.commit()
                } else latestGestures.cancel()
                base = null; last = null; locked = null; latestDragInfo(null)
                moved != null
            },
            onDragCancel = { latestGestures.cancel(); base = null; last = null; locked = null; latestDragInfo(null) })
    }) {
        val open = popover as? TwirlPopover.Value
        if (open != null && open.layerId == row.layerId && open.target == row.target) {
            // The key went away (moved, deleted, undone): the popover closes.
            if (open.time !in TwirlRows.keyTimes(layer, row.target)) LaunchedEffect(open) { onPopover(null) }
            val x = with(density) { ((layer.resolvedKeyframeAnchorUs + open.time) * pxPerUs).toFloat().toDp() }
            Box(Modifier.offset(x = x).size(1.dp, row.heightDp.dp)) {
                KeyValuePopover(project, layer, row, open, selection, host, gestures, onDismiss = { onPopover(null) })
            }
        }
    }
}

/**
 * TL3 B3: the value popover of one key (twirl rows only), non-modal, anchored at the key. The key's value in its
 * inspector page's units with the typed-entry field: one field for a scalar, X and Y for 2-D (per axis); colour keys use
 * the animator colour popover. Source Text and hold fields have none. Typing writes through the existing key-set actions at
 * the key's own time with its own easing (no seek); one Batch, one undo. When the key is in the selection the SAME value
 * goes to every selected key of the SAME property (on any layer in the selection); other properties never change.
 */
@Composable internal fun KeyValuePopover(project: ProjectDocument, layer: CompositionLayer, row: TwirlRow.Property, open: TwirlPopover.Value,
    selection: KeySelectionSet?, host: TwirlKeyHost, gestures: TwirlGestures, onDismiss: () -> Unit) {
    val scope = MultiKeyEdits.valueScope(if (open.single) null else selection, open.layerId, open.target, open.time)
    val count = scope.sumOf { it.size }; val layers = scope.map { it.layerId }.distinct().size
    val note = (if (count == 1) "Changes 1 keyframe" else "Changes $count keyframes") + if (layers > 1) " on $layers layers" else ""
    when (row.kind) {
        TwirlValueKind.SCALAR, TwirlValueKind.VECTOR -> AnchoredPopover(true, onDismiss, "twirl-value-popover", modal = false) {
            val comp = project.composition
            val axes = GraphValues.axes(layer, open.target, comp.width, comp.height)
            val key = GraphValues.keys(layer, open.target).firstOrNull { it.timeUs == open.time }
            Text(note, color = TextSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(4.dp).testTag("twirl-value-count"))
            if (key != null) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                for ((ch, axis) in axes.withIndex()) key("${open.time}-$ch-${key.value(ch)}") {
                    InspectorValueField(axis.text(axis.toDisplay(key.value(ch))), axis.name, axis.unit, onCommit = { text ->
                        text.trim().replace(',', '.').toFloatOrNull()?.let { d -> host.onValue(scope, ch, GraphValues.clampValue(open.target, axis, axis.typed(d))) }
                    }, onCancel = {}, modifier = Modifier.padding(2.dp).testTag("twirl-value-$ch"), autoFocus = false)
                }
            }
        }
        TwirlValueKind.COLOUR -> {
            val argb = TwirlColour.track(layer, open.target)?.keyframes?.firstOrNull { it.timeUs == open.time }?.argb ?: return
            var base by remember { mutableStateOf<ProjectDocument?>(null) }
            AnchoredPopover(true, onDismiss, "twirl-value-popover") {
                Text(note, color = TextSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(4.dp).testTag("twirl-value-count"))
                FluxxColourPicker(ColourPickerVariant.COMPACT, argb,
                    onBegin = { base = project; gestures.begin() },
                    onPreview = { c -> gestures.preview(MultiKeyEdits.setColour(base ?: project, scope, c) ?: EditorAction.Batch(emptyList())) },
                    onFinish = { base = null; gestures.commit() }, onCancel = { base = null; gestures.cancel() },
                    modifier = Modifier.testTag("twirl-colour-picker"), name = row.name.lowercase(),
                    onSwatch = { c -> gestures.begin(); gestures.preview(MultiKeyEdits.setColour(project, scope, c) ?: EditorAction.Batch(emptyList())); gestures.commit() })
            }
        }
        else -> LaunchedEffect(Unit) { onDismiss() }
    }
}
