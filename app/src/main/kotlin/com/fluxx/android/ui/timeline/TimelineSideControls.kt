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
internal fun TimelineSideControls(layers: List<CompositionLayer>, selectedId: Long?, listState: LazyListState,
    drag: TimelineReorderState, onVisibility: (Long, Boolean) -> Unit, onDragSelect: (Long) -> Unit,
    onBegin: () -> Unit, onPreview: (EditorAction) -> Unit, onCommit: () -> Unit, onCancel: () -> Unit,
    onScrollStart: () -> Unit) {
    val density = LocalDensity.current
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
                val layer = layers.firstOrNull { it.id == item.key } ?: continue
                key(layer.id) {
                    val isDragging = layer.id == drag.draggingId
                    val y = if (isDragging) drag.viewportTop else drag.placedTops[layer.id]
                        ?: (item.offset + listState.layoutInfo.beforeContentPadding).toFloat()
                    Box(Modifier.offset { IntOffset(0, y.roundToInt()) }.size(36.dp, TRACK_ROW_HEIGHT_DP.dp)
                        .zIndex(if (isDragging) 1f else 0f)
                        .clickable { onVisibility(layer.id, !layer.visible) }
                        .semantics { contentDescription = if (layer.visible) "Hide ${layer.name}" else "Show ${layer.name}" },
                        contentAlignment = Alignment.Center) {
                        EyeIcon(layer.visible, if (layer.id == selectedId) Highlight else TextSecondary)
                    }
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
