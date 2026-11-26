package com.fluxx.android.ui.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fluxx.android.editor.EditorAction
import com.fluxx.android.model.CompositionLayer
import com.fluxx.android.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt

internal fun reorderTargetIndex(origin: Int, movement: Float, pitch: Float, lastIndex: Int): Int =
    (origin + (movement / pitch).roundToInt()).coerceIn(0, lastIndex)

internal fun reorderVisualOffset(viewportTop: Float, itemTop: Float, beforeContentPadding: Float = 0f): Float =
    viewportTop - itemTop - beforeContentPadding

/** Use logical slots in the CURRENT viewport, never animated sibling positions or accumulated scroll. */
internal fun reorderViewportTarget(viewportTop: Float, referenceIndex: Int, referenceTop: Float,
    pitch: Float, lastIndex: Int): Int =
    reorderTargetIndex(referenceIndex, viewportTop - referenceTop, pitch, lastIndex)

/** Desired viewport position stays continuous across list placement and edge scrolling. */
internal class TimelineReorderState {
    var draggingId by mutableStateOf<Long?>(null)
    var viewportTop by mutableFloatStateOf(0f)
    val placedTops = mutableStateMapOf<Long, Float>()
    fun offsetFor(id: Long, list: LazyListState): Float =
        list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.let {
            reorderVisualOffset(viewportTop, it.offset.toFloat(), list.layoutInfo.beforeContentPadding.toFloat())
        } ?: 0f
}

/** One stationary gesture gutter survives reordering and virtualization of individual rows. */
@Composable
internal fun TimelineSideControls(layers: List<CompositionLayer>, selection: com.fluxx.android.editor.LayerSelection?, listState: LazyListState,
    drag: TimelineReorderState, onDTap: (Long, com.fluxx.android.editor.DZone) -> Unit, onDLongPress: (Long) -> Unit, onDragSelect: (Long) -> Unit,
    onBegin: () -> Unit, onPreview: (EditorAction) -> Unit, onCommit: () -> Unit, onCancel: () -> Unit,
    onScrollStart: () -> Unit,
    /** TL3 B1: the timeline items (layers and expanded rows), the expanded layers and the name-column gestures. */
    items: List<com.fluxx.android.editor.TwirlItem> = emptyList(), expanded: Set<Long> = emptySet(),
    onNameTap: (Long, com.fluxx.android.editor.GraphTarget) -> Unit = { _, _ -> },
    onNameLongPress: (Long, com.fluxx.android.editor.GraphTarget) -> Unit = { _, _ -> },
    onNameGraph: (Long, com.fluxx.android.editor.GraphTarget) -> Unit = { _, _ -> },
    /** TL3.1 B7: whether a property row's add-key button is available at the playhead, and its action. */
    addKeyState: ((com.fluxx.android.editor.TwirlRow.Property) -> com.fluxx.android.editor.KeyAdd.Why)? = null,
    onAddKey: (com.fluxx.android.editor.TwirlRow.Property) -> Unit = {},
    /** TL3.1 A3: rows are animating (twirl open / close): follow their animated (recorded) tops; otherwise the list's offsets. */
    animating: Boolean = false) {
    val density = LocalDensity.current
    val rowsByKey = remember(items) { items.withIndex().filter { it.value is com.fluxx.android.editor.TwirlItem.Row }
        .associate { (i, it) -> (it as com.fluxx.android.editor.TwirlItem.Row).row.key to (it.row to (items.getOrNull(i + 1) !is com.fluxx.android.editor.TwirlItem.Row)) } }
    val expandedWithRows = remember(items) { items.filterIsInstance<com.fluxx.android.editor.TwirlItem.Row>().map { it.row.layerId }.toSet() }
    val twirlOpen by rememberUpdatedState(expanded.isNotEmpty())
    val rowPx = with(density) { (TRACK_ROW_HEIGHT_DP.dp.roundToPx() + TRACK_GAP_DP.dp.roundToPx()).toFloat() }
    val rulerPx = with(density) { TimelineTracksOrigin.roundToPx().toFloat() }
    val currentLayers by rememberUpdatedState(layers)
    val begin by rememberUpdatedState(onBegin)
    val preview by rememberUpdatedState(onPreview)
    val commit by rememberUpdatedState(onCommit)
    val cancel by rememberUpdatedState(onCancel)
    val select by rememberUpdatedState(onDragSelect)
    val scrollStart by rememberUpdatedState(onScrollStart)
    val gutterInteractions = remember { MutableInteractionSource() }
    val scrollingGutter by gutterInteractions.collectIsDraggedAsState()
    LaunchedEffect(scrollingGutter) { if (scrollingGutter && drag.draggingId == null) scrollStart() }
    val scope = rememberCoroutineScope()
    var edgeJob by remember { mutableStateOf<Job?>(null) }
    var order by remember { mutableStateOf<List<Long>>(emptyList()) }
    var grabOffset by remember { mutableFloatStateOf(0f) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    var lastTarget by remember { mutableIntStateOf(-1) }
    fun previewOrder() {
        val id = drag.draggingId ?: return
        if (order.isEmpty()) return
        val layout = listState.layoutInfo
        val reference = layout.visibleItemsInfo.firstOrNull() ?: return
        // Keyed LazyColumn placement may move the viewport during a reorder without scrollBy().
        // Measuring against its current slots accounts for that as well as edge scrolling.
        val target = reorderViewportTarget(drag.viewportTop, reference.index,
            (reference.offset + layout.beforeContentPadding).toFloat(), rowPx, order.lastIndex)
        if (target != lastTarget) {
            lastTarget = target
            preview(EditorAction.ReorderLayer(id, order[target]))
        }
    }
    fun startEdgeScroll(id: Long) {
        edgeJob?.cancel()
        edgeJob = scope.launch {
            while (drag.draggingId == id) {
                val step = when {
                    pointerY < rulerPx + rowPx -> -rowPx / 6
                    pointerY > viewportHeight - rowPx -> rowPx / 6
                    else -> 0f
                }
                if (step != 0f) {
                    listState.scrollBy(step)
                    // scrollBy suspends: release/cancel may have ended this drag in the meantime.
                    ensureActive()
                    if (drag.draggingId == id) previewOrder()
                }
                delay(16)
            }
        }
    }
    DisposableEffect(Unit) { onDispose {
        edgeJob?.cancel()
        if (drag.draggingId != null) { drag.draggingId = null; cancel() }
    } }
    Box(Modifier.fillMaxSize().clipToBounds()) {
        Box(Modifier.fillMaxSize().padding(top = TimelineTracksOrigin).clipToBounds()) {
            for (item in listState.layoutInfo.visibleItemsInfo) {
                // TL3 B1: an expanded row: the D's name-column cell beside it (the bracket continues down).
                (item.key as? String)?.let { k -> rowsByKey[k]?.let { (row, last) ->
                    key(k) {
                        val owner = layers.firstOrNull { it.id == row.layerId }
                        TwirlNameCell(row, selected = owner != null && selection?.let { owner.id in it } == true, last = last,
                            // TL3.1 A2: in layer selection mode the name column is the D's body (select / deselect the layer).
                            onTap = { if (selection != null) onDTap(row.layerId, com.fluxx.android.editor.DZone.BODY)
                                else (row as? com.fluxx.android.editor.TwirlRow.Property)?.let { onNameTap(it.layerId, it.target) } },
                            onLongPress = { (row as? com.fluxx.android.editor.TwirlRow.Property)?.let { onNameLongPress(it.layerId, it.target) } },
                            modifier = Modifier.offset { IntOffset(0, item.offset + listState.layoutInfo.beforeContentPadding) },
                            onGraph = (row as? com.fluxx.android.editor.TwirlRow.Property)?.takeIf { p -> selection == null && owner != null &&
                                p.kind != com.fluxx.android.editor.TwirlValueKind.COLOUR && com.fluxx.android.editor.GraphModel.enabled(owner, p.target) }
                                ?.let { p -> { onNameGraph(p.layerId, p.target) } })
                        // TL3.1 B7: under the reorder grip, an add-keyframe button on every property row (not the audio row).
                        val prop = row as? com.fluxx.android.editor.TwirlRow.Property
                        if (prop != null && addKeyState != null) {
                            val why = addKeyState(prop)
                            AddKeyButton(prop, why, { onAddKey(prop) }, Modifier.align(Alignment.TopEnd)
                                .offset { IntOffset(0, item.offset + listState.layoutInfo.beforeContentPadding) })
                        }
                    }
                } }
                val layer = layers.firstOrNull { it.id == item.key } ?: continue
                key(layer.id) {
                    val isDragging = layer.id == drag.draggingId
                    val y = if (isDragging) drag.viewportTop else drag.placedTops[layer.id]?.takeIf { animating || drag.draggingId != null }
                        ?: (item.offset + listState.layoutInfo.beforeContentPadding).toFloat()
                    // TLP T1: the row's eye is the D-button (eye + arrow), always there; no extra column.
                    val inSelection = selection?.let { layer.id in it } ?: false
                    DButton(layer, selected = inSelection, selectionMode = selection != null,
                        onTap = { zone -> onDTap(layer.id, zone) }, onLongPress = { onDLongPress(layer.id) },
                        expanded = layer.id in expandedWithRows,
                        modifier = Modifier.offset { IntOffset(0, y.roundToInt()) }.height(TRACK_ROW_HEIGHT_DP.dp)
                            .zIndex(if (isDragging) 1f else 0f))
                    Box(Modifier.align(Alignment.TopEnd)
                        .offset { IntOffset(0, y.roundToInt()) }.size(48.dp, TRACK_ROW_HEIGHT_DP.dp)
                        .zIndex(if (isDragging) 1f else 0f)
                        .semantics { contentDescription = "Hold and drag to reorder ${layer.name}" },
                        contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(14.dp, 8.dp)) {
                            val color = if (isDragging) Highlight else TextSecondary.copy(alpha = .5f)
                            for (line in -1..1) drawLine(color, Offset(0f, center.y + line * 3.dp.toPx()),
                                Offset(size.width, center.y + line * 3.dp.toPx()), 1.dp.toPx(), StrokeCap.Round)
                        }
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.TopEnd).width(48.dp).fillMaxHeight()
            .scrollable(listState, Orientation.Vertical, reverseDirection = true,
                interactionSource = gutterInteractions, enabled = drag.draggingId == null)
            .pointerInput(rowPx) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { point ->
                        // TL3: reordering assumes one 28dp row per layer; it is paused while any twirl-down is open.
                        if (twirlOpen) return@detectDragGesturesAfterLongPress
                        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                            val top = (it.key as? Long)?.let { id -> drag.placedTops[id] }
                                ?: (it.offset + listState.layoutInfo.beforeContentPadding).toFloat()
                            point.y >= rulerPx && point.y - rulerPx >= top && point.y - rulerPx < top + it.size
                        }
                        val id = item?.key as? Long
                        if (item != null && id != null && currentLayers.any { it.id == id }) {
                            order = currentLayers.map { it.id }
                            pointerY = point.y; viewportHeight = size.height
                            lastTarget = order.indexOf(id)
                            drag.viewportTop = drag.placedTops[id]
                                ?: (item.offset + listState.layoutInfo.beforeContentPadding).toFloat()
                            grabOffset = point.y - rulerPx - drag.viewportTop
                            drag.draggingId = id
                            select(id); begin()
                            startEdgeScroll(id)
                        }
                    },
                    onDrag = { change, _ ->
                        if (drag.draggingId != null) {
                            change.consume()
                            pointerY = change.position.y
                            drag.viewportTop = pointerY - rulerPx - grabOffset
                            previewOrder()
                        }
                    },
                    onDragEnd = {
                        edgeJob?.cancel(); edgeJob = null
                        if (drag.draggingId != null) {
                            previewOrder()
                            drag.draggingId = null
                            commit()
                        }
                    },
                    onDragCancel = {
                        edgeJob?.cancel(); edgeJob = null
                        if (drag.draggingId != null) { drag.draggingId = null; cancel() }
                    }
                )
            })
    }
}


/**
 * TL3.1 B7: the add-keyframe button of a twirl property row (the family's KEY_ADD: a diamond with a plus). Available: a
 * key at the playhead frame that keeps the motion (KeyAdd). Otherwise dimmed; a tap then says why (a key is already on
 * this frame, the playhead is outside the clip, or a Bounce / Elastic segment).
 */
@Composable internal fun AddKeyButton(row: com.fluxx.android.editor.TwirlRow.Property, why: com.fluxx.android.editor.KeyAdd.Why, onAdd: () -> Unit,
    modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ok = why == com.fluxx.android.editor.KeyAdd.Why.OK
    val note = when (why) {
        com.fluxx.android.editor.KeyAdd.Why.OK -> null
        com.fluxx.android.editor.KeyAdd.Why.ON_KEY -> "A keyframe is already on this frame"
        com.fluxx.android.editor.KeyAdd.Why.OUTSIDE_CLIP -> "The playhead is outside this layer"
        com.fluxx.android.editor.KeyAdd.Why.BOUNCE_ELASTIC -> "Not available for Bounce / Elastic segments"
        com.fluxx.android.editor.KeyAdd.Why.UNSUPPORTED -> "Not available for this property"
    }
    val ink = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier.size(48.dp, row.heightDp.dp).testTag("twirl-add-key-${row.key}")
        .clickable(role = androidx.compose.ui.semantics.Role.Button) {
            if (ok) onAdd() else note?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show() } }
        .semantics { contentDescription = "Add a keyframe at the playhead to ${row.name}"
            if (!ok) stateDescription = note ?: "Unavailable" },
        contentAlignment = Alignment.Center) {
        androidx.compose.material3.Icon(com.fluxx.android.ui.inspector.InspectorIcons.icon("KEY_ADD"), null, Modifier.size(16.dp),
            tint = if (ok) ink else ink.copy(alpha = .3f))
    }
}
