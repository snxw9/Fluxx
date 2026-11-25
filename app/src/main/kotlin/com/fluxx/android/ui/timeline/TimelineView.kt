package com.fluxx.android.ui.timeline
import com.fluxx.android.editor.propertyKeyTimes

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.delay
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.fluxx.android.editor.timelineTickSteps
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.fluxx.android.editor.AnimPropertyType
import com.fluxx.android.editor.AnimatorEdit
import com.fluxx.android.editor.AnimatorKeyOperation
import com.fluxx.android.editor.AnimatorTrackAddress
import com.fluxx.android.editor.EditorAction
import com.fluxx.android.editor.TimelineEdits
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.CompositionLayer
import com.fluxx.android.model.FrameRate
import com.fluxx.android.model.LayerType
import com.fluxx.android.model.ProjectDocument
import com.fluxx.android.model.Marker
import com.fluxx.android.model.Markers
import androidx.compose.runtime.CompositionLocalProvider
import com.fluxx.android.ui.inspector.AnchoredPopover
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import com.fluxx.android.ui.common.TimeFormat
import com.fluxx.android.ui.theme.Background
import com.fluxx.android.ui.theme.Border
import com.fluxx.android.ui.theme.ClipGold
import com.fluxx.android.ui.theme.ClipGoldText
import com.fluxx.android.ui.theme.ClipTeal
import com.fluxx.android.ui.theme.ClipTealText
import com.fluxx.android.ui.theme.ClipVideoBorder
import com.fluxx.android.ui.theme.ClipVideoDark
import com.fluxx.android.ui.theme.FluxxTheme
import com.fluxx.android.ui.theme.Highlight
import com.fluxx.android.ui.theme.Surface
import com.fluxx.android.ui.theme.SurfaceElevated
import com.fluxx.android.ui.theme.SurfaceHigh
import com.fluxx.android.ui.theme.TextDisabled
import com.fluxx.android.ui.theme.TextPrimary
import com.fluxx.android.ui.theme.TextSecondary
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal const val TRACK_ROW_HEIGHT_DP = 28
internal const val TRACK_GAP_DP = 0
internal const val RULER_HEIGHT_DP = 32
internal const val RULER_TRACK_GAP_DP = 8
internal const val INSPECTOR_TIMELINE_GAP_DP = 8
internal val TimelineTracksOrigin = (RULER_HEIGHT_DP + RULER_TRACK_GAP_DP).dp
internal val TimelineTracksBottom = TimelineTracksOrigin + TRACK_ROW_HEIGHT_DP.dp
// Shared by preview budgeting, the clipped track viewport, and inspector docking.
internal val TimelineInspectorAnchorHeight = TimelineTracksBottom + INSPECTOR_TIMELINE_GAP_DP.dp

/** Pointer hit slop must never bridge ruler/row or marker/keyframe vertical partitions. */
@Composable
private fun ExactTimelineTargets(content: @Composable () -> Unit) {
    val configuration = LocalViewConfiguration.current
    val exact = remember(configuration) { object : ViewConfiguration by configuration {
        override val minimumTouchTargetSize = DpSize.Zero
    } }
    CompositionLocalProvider(LocalViewConfiguration provides exact, content = content)
}


@Composable
fun TimelineView(
    project: ProjectDocument,
    mediaRepository: MediaRepository,
    playheadUs: Long,
    selectedLayerId: Long?,
    onSelectLayer: (Long?) -> Unit,
    onSeek: (Long, Boolean) -> Unit,
    onToggleVisibility: (Long, Boolean) -> Unit,
    onBeginGesture: () -> Unit,
    onPreviewGesture: (EditorAction) -> Unit,
    onCommitGesture: () -> Unit,
    onCancelGesture: () -> Unit,
    onOpenClipOptions: (Long) -> Unit,
    inspectorVisible: Boolean = false,
    activeProperty: AnimPropertyType? = null,
    /** Animate tab (TEXT F3): the focused animator field; its keys are the editable ones, like [activeProperty]. */
    activeAnimatorTrack: AnimatorTrackAddress? = null,
    onDragSelect: (Long) -> Unit = { onSelectLayer(it) },
    onDismissInspector: () -> Unit = {},
    onMarkerAction: (EditorAction) -> Unit = {},
    onMarkerSelect: (Long?, Marker) -> Unit = { _, _ -> },
    onMarkerConfigure: () -> Unit = {},
    /** TLP: selection mode (null = off); the D-button routes taps / long presses through [onDTap] / [onDLongPress]. */
    selection: com.fluxx.android.editor.LayerSelection? = null,
    onDTap: (Long, com.fluxx.android.editor.DZone) -> Unit = { id, zone ->
        if (zone == com.fluxx.android.editor.DZone.EYE) project.composition.layers.firstOrNull { it.id == id }?.let { onToggleVisibility(id, !it.visible) } },
    onDLongPress: (Long) -> Unit = {},
    /** TLP T3: extra bottom scroll padding under the last row (the selection bar overlays the timeline, never resizing it). */
    extraBottomPadding: Dp = 0.dp,
    /** TLP T5: key selection mode on the selected layer's focused property (local key times), and its key taps. */
    selectedKeys: Set<Long>? = null,
    onKeyToggle: (Long) -> Unit = {},
    onKeyDoubleTap: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val comp = project.composition
    val context = LocalContext.current
    val selectedLayer = comp.layers.firstOrNull { it.id == selectedLayerId }
    // TEXT UI 8: one anchored marker popover (description + COMPACT picker), no dialog, no buttons.
    val popoverConfiguration = LocalViewConfiguration.current
    var markerSession by remember { mutableStateOf<MarkerEditSession?>(null) }
    var markerPopoverOpen by remember { mutableStateOf(false) }
    val gestureBegin by rememberUpdatedState(onBeginGesture)
    val gesturePreview by rememberUpdatedState(onPreviewGesture)
    val gestureCommit by rememberUpdatedState(onCommitGesture)
    val gestureCancel by rememberUpdatedState(onCancelGesture)
    val markerSink = remember { object : MarkerGestureSink {
        override fun begin() = gestureBegin()
        override fun preview(action: EditorAction) = gesturePreview(action)
        override fun commit() = gestureCommit()
        override fun cancel() = gestureCancel()
    } }
    fun closeMarkerPopover() { markerSession?.close(); markerPopoverOpen = false }
    fun openMarkerPopover(ref: MarkerRef, marker: Marker) {
        markerSession?.close()
        markerSession = MarkerEditSession(ref, marker, markerSink)
        markerPopoverOpen = true
    }
    // Removing the marker (playhead tap, undo, layer deletion) closes its popover.
    val editedMarkerExists = markerSession?.let { MarkerEditing.find(project, it.ref) != null } ?: false
    LaunchedEffect(editedMarkerExists) {
        if (!editedMarkerExists) { markerSession?.detach(); markerSession = null; markerPopoverOpen = false }
    }
    val markerPopover: @Composable () -> Unit = {
        markerSession?.let { session ->
            AnchoredPopover(markerPopoverOpen, { closeMarkerPopover() }, "marker-popover") {
                // The timeline zeroes touch-target padding; the popover's controls get the normal minimum back.
                CompositionLocalProvider(LocalViewConfiguration provides popoverConfiguration) { MarkerPopoverContent(session) }
            }
        }
    }
    // A retained layer marker outside the trimmed clip has no glyph on the bar: its popover anchors to the playhead target.
    val markerOnBar = markerSession?.ref?.let { MarkerEditing.drawnOnBar(project, it) } ?: true
    fun tapMarker(owner: Long?, marker: Marker) {
        val anchor = comp.layers.firstOrNull { it.id == owner }?.resolvedKeyframeAnchorUs ?: 0L
        if (comp.frameRate.nearestFrame(playheadUs) == comp.frameRate.nearestFrame(Math.addExact(anchor, marker.timeUs))) {
            onMarkerAction(if (owner == null) EditorAction.DeleteCompositionMarker(marker.id)
                else EditorAction.DeleteLayerMarker(owner, marker.id))
        } else onMarkerSelect(owner, marker)
    }
    fun playheadMarker(hold: Boolean) {
        val defaultColor = when {
            selectedLayer == null -> MarkerEditing.COMPOSITION_DEFAULT
            selectedLayer.type == LayerType.SOLID -> {
                val background = Color(selectedLayer.solidColorArgb).compositeOver(Background)
                (if (background.luminance() > .179f) Color(0xFF191919) else Color(0xFFFFC107)).toArgb()
            }
            else -> MarkerEditing.LAYER_DEFAULT
        }
        when (val result = MarkerEditing.playhead(project, selectedLayer, playheadUs, hold, defaultColor)) {
            MarkerEditing.PlayheadResult.OutsideLayer ->
                android.widget.Toast.makeText(context, "Playhead outside selected layer", android.widget.Toast.LENGTH_SHORT).show()
            is MarkerEditing.PlayheadResult.Remove -> onMarkerAction(result.action)
            is MarkerEditing.PlayheadResult.Create -> {
                onMarkerConfigure()
                onMarkerAction(result.action)
                onMarkerSelect(result.ref.layerId, result.marker)
                openMarkerPopover(result.ref, result.marker)
            }
            is MarkerEditing.PlayheadResult.Edit -> {
                onMarkerConfigure()
                openMarkerPopover(result.ref, result.marker)
            }
        }
    }
    val durationUs = comp.resolvedDurationUs

    val density = LocalDensity.current
    var pxPerSecond by remember { mutableFloatStateOf(com.fluxx.android.editor.TIMELINE_DEFAULT_DP_PER_SECOND) }
    val pxPerUs = (pxPerSecond * density.density) / 1_000_000.0
    // TL1: the one time <-> x mapping (frame x = round(frame x pxPerFrame)); ticks and the playhead's scroll both use it.
    val scale = remember(comp.frameRate, pxPerUs) { com.fluxx.android.editor.TimelineScale(comp.frameRate, pxPerUs) }

    // Top to bottom in list = front to back in draw order
    val sortedLayers = remember(comp.layers) {
        comp.layers.filter { (it.timing.durationUs ?: 1L) > 0L }
            .sortedWith(compareByDescending<CompositionLayer> { it.zOrder }.thenByDescending { it.id })
    }

    val verticalScrollState = rememberLazyListState()
    val draggingTimeline by verticalScrollState.interactionSource.collectIsDraggedAsState()
    val horizontalScrollState = rememberScrollState()
    val reorder = remember { TimelineReorderState() }
    var preInspectorViewport by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var skipInspectorRestore by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var pinching by remember { mutableStateOf(false) }
    var edgeScroll by remember { mutableFloatStateOf(0f) }
    var viewportLeft by remember { mutableFloatStateOf(0f) }
    var viewportTop by remember { mutableFloatStateOf(0f) }
    var editScrollOrigin by remember { mutableStateOf(0) }
    val beginEdit = {
        editScrollOrigin = horizontalScrollState.value
        editing = true
        onBeginGesture()
    }
    val finishScroll = {
        edgeScroll = 0f
        editing = false
        if (horizontalScrollState.value != editScrollOrigin) {
            // TL1: an edit's edge scroll also lands the playhead on a whole frame.
            onSeek(scale.snappedTime(horizontalScrollState.value.toDouble(), durationUs), true)
        }
    }
    val commitEdit = { onCommitGesture(); finishScroll() }
    val cancelEdit = { onCancelGesture(); finishScroll() }
    LaunchedEffect(edgeScroll) {
        if (edgeScroll != 0f) while (true) {
            horizontalScrollState.scrollBy(edgeScroll)
            delay(16)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Background)
            .pointerInput(Unit) {
                // Observe in the initial pass so a second finger takes ownership before clip/ruler taps.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2 && !editing) {
                                pinching = true
                                val zoom = event.calculateZoom()
                                if (zoom.isFinite()) pxPerSecond = com.fluxx.android.editor.TimelineZoom.clamp(pxPerSecond * zoom)
                            }
                            // Keep ownership until both fingers lift; don't finish with a one-finger seek.
                            if (pinching) event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    } finally { pinching = false }
                }
            }
            .onGloballyPositioned {
                viewportLeft = it.positionInRoot().x
                viewportTop = it.positionInRoot().y
            }
    ) {
        BoxWithConstraints(modifier = (if (inspectorVisible) Modifier.fillMaxWidth().height(TimelineTracksBottom)
            else Modifier.fillMaxSize()).clipToBounds()) {
            // Allow even the last layer to align above the inspector.
            val bottomPadding = (maxHeight - TimelineTracksBottom).coerceAtLeast(0.dp)
            val totalViewportWidthPx = constraints.maxWidth.toFloat()
            // TL1: the leading spacer (and so the playhead column) is a whole number of pixels.
            val centerPx = (totalViewportWidthPx / 2f).roundToInt()
            val centerXPx = centerPx.toFloat()
            val centerXDp = with(density) { centerPx.toDp() }

            val contentWidthPx = (durationUs * pxPerUs).toFloat()
            val contentWidthDp = with(density) { contentWidthPx.toDp() }

            // Synchronize playheadUs -> horizontalScrollState when not user-scrolling
            // TL1: keyed on the scroll ending too, so a released scrub settles exactly on its snapped frame's x.
            LaunchedEffect(playheadUs, pxPerUs, durationUs, pinching, horizontalScrollState.maxValue, horizontalScrollState.isScrollInProgress) {
                if (!editing && (pinching || !horizontalScrollState.isScrollInProgress)) {
                    val targetScroll = scale.timeX(playheadUs.coerceIn(0L, durationUs)).toInt()
                    if (horizontalScrollState.value != targetScroll) {
                        horizontalScrollState.scrollTo(targetScroll)
                    }
                }
            }

            // Synchronize horizontalScrollState -> playheadUs when user is actively scrolling/scrubbing
            LaunchedEffect(horizontalScrollState.value, horizontalScrollState.isScrollInProgress) {
                // TL1 scrub: the scroll position maps to the NEAREST whole frame; a Seek is sent only when that frame
                // changes (at most one per frame crossed), and the preview receives the same snapped time.
                if (!editing && !pinching && horizontalScrollState.isScrollInProgress &&
                    horizontalScrollState.value.toLong() != scale.timeX(playheadUs)) {
                    val newTimeUs = scale.snappedTime(horizontalScrollState.value.toDouble(), durationUs)
                    if (newTimeUs != playheadUs) {
                        onSeek(newTimeUs, true)
                    }
                }
            }

            // Capture once per inspector session, including selection changes while it is open.
            LaunchedEffect(selectedLayerId, inspectorVisible, sortedLayers.map { it.id }) {
                if (inspectorVisible && selectedLayerId != null) {
                    if (preInspectorViewport == null) {
                        preInspectorViewport = verticalScrollState.firstVisibleItemIndex to verticalScrollState.firstVisibleItemScrollOffset
                        skipInspectorRestore = false
                    }
                    val index = sortedLayers.indexOfFirst { it.id == selectedLayerId }
                    if (index >= 0 && !draggingTimeline && reorder.draggingId == null) verticalScrollState.scrollToItem(index)
                } else if (!inspectorVisible) {
                    val saved = preInspectorViewport
                    preInspectorViewport = null
                    if (saved != null && !skipInspectorRestore && !draggingTimeline && reorder.draggingId == null && sortedLayers.isNotEmpty()) {
                        verticalScrollState.scrollToItem(saved.first.coerceAtMost(sortedLayers.lastIndex), saved.second)
                    }
                }
            }
            LaunchedEffect(draggingTimeline) {
                if (inspectorVisible && draggingTimeline) {
                    skipInspectorRestore = true
                    onDismissInspector()
                }
            }

            // 1. Horizontally scrollable content (ruler + clip tracks)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(horizontalScrollState, enabled = !pinching)
                    .pointerInput(Unit) {
                        detectTapGestures { onSelectLayer(null) }
                    }
            ) {
                Row(
                    modifier = Modifier
                        .width(contentWidthDp + centerXDp * 2)
                        .fillMaxHeight()
                ) {
                    // Leading spacer equal to half screen width: keeps time 0 aligned under 50% center playhead
                    Spacer(
                        modifier = Modifier
                            .width(centerXDp)
                            .fillMaxHeight()
                            .pointerInput(Unit) {
                                detectTapGestures { onSelectLayer(null) }
                            }
                    )

                    Column(
                        modifier = Modifier
                            .width(contentWidthDp)
                            .fillMaxHeight()
                    ) {
                        // Timeline Ruler
                        TimelineRuler(
                            durationUs = durationUs,
                            frameRate = comp.frameRate,
                            pxPerUs = pxPerUs,
                            scale = scale,
                            startTimecodeUs = comp.startTimecodeUs,
                            markers = comp.markers,
                            visibleStartUs = ((horizontalScrollState.value - centerXPx) / pxPerUs).toLong().coerceAtLeast(0),
                            visibleEndUs = ((horizontalScrollState.value + centerXPx) / pxPerUs).toLong().coerceAtMost(durationUs),
                            popoverMarkerId = markerSession?.ref?.takeIf { it.layerId == null }?.id,
                            popover = markerPopover,
                            onMarkerSelect = { tapMarker(null, it) },
                            onSeek = { onMarkerAction(EditorAction.SelectMarker(null)); onSeek(it, false) }
                        )

                        Spacer(Modifier.height(RULER_TRACK_GAP_DP.dp))
                        // Track viewport is separate from the ruler and its spacer.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .pointerInput(Unit) {
                                    detectTapGestures { onSelectLayer(null) }
                                }
                        ) {
                            LazyColumn(
                                state = verticalScrollState,
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(TRACK_GAP_DP.dp),
                                contentPadding = PaddingValues(bottom = bottomPadding + extraBottomPadding)
                            ) {
                                items(sortedLayers, key = { it.id }) { layer ->
                                    val isDragging = layer.id == reorder.draggingId
                                    DisposableEffect(layer.id) {
                                        onDispose { reorder.placedTops.remove(layer.id) }
                                    }
                                    TimelineClipRow(
                                        modifier = (if (isDragging) Modifier else Modifier.animateItem(
                                            placementSpec = androidx.compose.animation.core.tween(durationMillis = 100)))
                                            .zIndex(if (isDragging) 1f else 0f)
                                            .graphicsLayer {
                                                translationY = if (isDragging) reorder.offsetFor(layer.id, verticalScrollState) else 0f
                                            }
                                            .onGloballyPositioned {
                                                // Side controls follow the same placement animation as the clip.
                                                reorder.placedTops[layer.id] = it.positionInRoot().y - viewportTop - with(density) { TimelineTracksOrigin.roundToPx().toFloat() }
                                            },
                                        project = project,
                                        mediaRepository = mediaRepository,
                                        layer = layer,
                                        // TLP T1: in selection mode the D-button shows selection; the row is not highlighted.
                                        isSelected = layer.id == selectedLayerId && selection == null,
                                        activeProperty = activeProperty,
                                        activeAnimatorTrack = activeAnimatorTrack,
                                        onMarkerSelect = { tapMarker(layer.id, it) },
                                        popoverMarkerId = markerSession?.ref?.takeIf { it.layerId == layer.id && markerOnBar }?.id,
                                        popover = markerPopover,
                                        onDragSelect = { onDragSelect(layer.id) },
                                        pxPerUs = pxPerUs,
                                        playheadUs = playheadUs,
                                        frameRate = comp.frameRate,
                                        onSelect = { onSelectLayer(layer.id) },
                                        onEmptyTap = { onSelectLayer(null) },
                                        onSeek = onSeek,
                                        onBeginGesture = beginEdit,
                                        scrollOffsetPx = horizontalScrollState.value,
                                        onMoveEdge = { x ->
                                            edgeScroll = when {
                                                x == null -> 0f
                                                x - viewportLeft < 48.dp.value * density.density -> -16f * density.density
                                                x - viewportLeft > totalViewportWidthPx - 48.dp.value * density.density -> 16f * density.density
                                                else -> 0f
                                            }
                                        },
                                        onPreviewGesture = onPreviewGesture,
                                        onCommitGesture = commitEdit,
                                        onCancelGesture = cancelEdit,
                                        onOptionsClick = { onOpenClipOptions(layer.id) },
                                        groupSelection = selection?.takeIf { layer.id in it },
                                        selectedKeys = selectedKeys?.takeIf { layer.id == selectedLayerId },
                                        onKeyToggle = onKeyToggle, onKeyDoubleTap = onKeyDoubleTap
                                    )
                                }

                            }
                        }
                    }

                    // Trailing spacer equal to half screen width: keeps durationUs aligned under center playhead
                    Spacer(
                        modifier = Modifier
                            .width(centerXDp)
                            .fillMaxHeight()
                            .pointerInput(Unit) {
                                detectTapGestures { onSelectLayer(null) }
                            }
                    )
                }
            }

            // 2. Stationary Fixed-Center Playhead (pinned at exact 50% horizontal center of the screen)
            StationaryTimelinePlayhead(
                playheadUs = playheadUs,
                startTimecodeUs = comp.startTimecodeUs,
                columnPx = centerPx,
                frameRate = comp.frameRate,
                marker = Markers.active(project, selectedLayer, playheadUs),
                playheadPopover = if (markerOnBar) null else markerPopover,
                onTap = { playheadMarker(false) },
                onHold = { playheadMarker(true) },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxHeight()
            )

            TimelineSideControls(sortedLayers, selection, verticalScrollState, reorder,
                onDTap, onDLongPress, onDragSelect, beginEdit, onPreviewGesture, commitEdit, cancelEdit,
                onScrollStart = {
                    skipInspectorRestore = true
                    if (inspectorVisible) onDismissInspector()
                })
        }
    }
}

@Preview(showBackground = true)
@Preview(name = "Dark Mode", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun TimelineViewPreview() {
    val context = LocalContext.current
    FluxxTheme {
        TimelineView(
            project = ProjectDocument(),
            mediaRepository = MediaRepository(context),
            playheadUs = 1_000_000L,
            selectedLayerId = null,
            onSelectLayer = {},
            onSeek = { _, _ -> },
            onToggleVisibility = { _, _ -> },
            onBeginGesture = {},
            onPreviewGesture = {},
            onCommitGesture = {},
            onCancelGesture = {},
            onOpenClipOptions = {}
        )
    }
}

/**
 * Clean vector Eye Icon drawn via Canvas (no emoji).
 */
@Composable
internal fun EyeIcon(
    visible: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        if (visible) {
            // Upper eye curve
            val eyePath = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.1f, h * 0.5f)
                quadraticTo(w * 0.5f, h * 0.1f, w * 0.9f, h * 0.5f)
                quadraticTo(w * 0.5f, h * 0.9f, w * 0.1f, h * 0.5f)
                close()
            }
            drawPath(
                path = eyePath,
                color = color,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.3f)
            )
            // Pupil circle
            drawCircle(
                color = color,
                radius = w * 0.18f,
                center = Offset(w * 0.5f, h * 0.5f)
            )
        } else {
            // Eye outline
            val eyePath = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.15f, h * 0.5f)
                quadraticTo(w * 0.5f, h * 0.15f, w * 0.85f, h * 0.5f)
                quadraticTo(w * 0.5f, h * 0.85f, w * 0.15f, h * 0.5f)
                close()
            }
            drawPath(
                path = eyePath,
                color = color,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f)
            )
            // Strikethrough diagonal line for hidden state
            drawLine(
                color = color,
                start = Offset(w * 0.15f, h * 0.85f),
                end = Offset(w * 0.85f, h * 0.15f),
                strokeWidth = 1.3f
            )
        }
    }
}

/** TL1: the ruler's px per µs, for tests that check tick positions exactly (not read by accessibility services). */
internal val TimelineZoomKey = androidx.compose.ui.semantics.SemanticsPropertyKey<Double>("TimelinePxPerUs")

/**
 * Timeline Ruler with zoom-derived major, medium, and minor graduated tick marks.
 * Spacing dynamically derives from the current timeline zoom (pxPerUs).
 */
@Composable
private fun TimelineRuler(
    durationUs: Long,
    frameRate: FrameRate,
    pxPerUs: Double,
    scale: com.fluxx.android.editor.TimelineScale,
    startTimecodeUs: Long,
    markers: List<Marker>,
    visibleStartUs: Long,
    visibleEndUs: Long,
    onMarkerSelect: (Marker) -> Unit,
    popoverMarkerId: Long?,
    popover: @Composable () -> Unit,
    onSeek: (Long) -> Unit
) {
    val selectMarker by rememberUpdatedState(onMarkerSelect)
    val seek by rememberUpdatedState(onSeek)
    val density = LocalDensity.current
    val labelPaint = remember(density) { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = TextSecondary.toArgb()
        textSize = with(density) { 9.sp.toPx() }
        typeface = android.graphics.Typeface.MONOSPACE
    } }
    // TL1 tiered ticks: classified by the displayed timecode frame (start frame + composition frame).
    val level = remember(frameRate, scale, density) { com.fluxx.android.editor.RulerTiers.level(frameRate, scale.pxPerFrame, density.density) }
    val startFrame = remember(startTimecodeUs, frameRate) { com.fluxx.android.model.CompositionTimecode.startFrame(startTimecodeUs, frameRate) }
    ExactTimelineTargets { Box(Modifier.fillMaxWidth().height(RULER_HEIGHT_DP.dp)) {
        Canvas(Modifier.fillMaxWidth().height(RULER_HEIGHT_DP.dp).background(Background)
            .testTag("timeline-ruler").semantics { this[TimelineZoomKey] = pxPerUs }
            .pointerInput(durationUs, pxPerUs, markers) {
                detectTapGestures { offset ->
                    val targetUs = (offset.x / pxPerUs).toLong().coerceIn(0L, durationUs)
                    val marker = markers.filter { it.timeUs <= durationUs }
                        .minByOrNull { abs(it.timeUs * pxPerUs - offset.x) }
                    // GRAPH G4.2: a 48dp target (24dp each side); the nearest marker wins.
                    if (marker != null && abs(marker.timeUs * pxPerUs - offset.x) <= (com.fluxx.android.editor.TimelineHits.TARGET_DP / 2f).dp.toPx()) selectMarker(marker)
                    else seek(frameRate.snap(targetUs).coerceAtMost(durationUs))
                }
            }) {
            val baseline = size.height - 1.dp.toPx()
            val startX = (visibleStartUs * pxPerUs).toFloat()
            val endX = (visibleEndUs * pxPerUs).toFloat()
            drawLine(TextSecondary.copy(alpha = .45f), Offset(startX, baseline), Offset(endX, baseline), .75.dp.toPx())
            // Begin at the visible frame window, not frame zero, so long projects don't redraw every tick. Each tick is a
            // whole-pixel rectangle at its frame's x ([TimelineScale.frameX]) with a whole-pixel width, centred like the
            // playhead line, so there are no anti-aliased double lines and the playhead sits exactly on its frame.
            val majorH = 12.dp.toPx()
            val wMajor = 1.dp.roundToPx().coerceAtLeast(1); val wMinor = .75.dp.roundToPx().coerceAtLeast(1)
            val lastFrame = minOf(frameRate.nearestFrame(visibleEndUs) + 1, scale.lastFrame(durationUs))
            val firstFrame = (frameRate.nearestFrame(visibleStartUs) - 1).coerceAtLeast(0)
            val tickColour = TextSecondary.copy(alpha = .95f); val minorColour = TextSecondary.copy(alpha = .65f)
            fun tick(frame: Long) {
                if (frame < firstFrame || frame > lastFrame) return
                val tier = com.fluxx.android.editor.RulerTiers.tier(startFrame + frame, level) ?: return
                val major = tier == com.fluxx.android.editor.TickTier.MAJOR
                val w = if (major) wMajor else wMinor
                val h = kotlin.math.round(majorH * when (tier) { com.fluxx.android.editor.TickTier.MAJOR -> 1f
                    com.fluxx.android.editor.TickTier.MID -> com.fluxx.android.editor.RulerTiers.MID_HEIGHT
                    com.fluxx.android.editor.TickTier.MINOR -> com.fluxx.android.editor.RulerTiers.MINOR_HEIGHT })
                val bottom = kotlin.math.round(baseline); val top = bottom - h
                drawRect(if (major) tickColour else minorColour, topLeft = Offset((scale.frameX(frame) - w / 2).toFloat(), top),
                    size = androidx.compose.ui.geometry.Size(w.toFloat(), h))
            }
            if (level.minorEvery == 1L) { var f = firstFrame; while (f <= lastFrame) { tick(f); f++ } }
            else {
                // Each period's ticks only (major, mid, minors), from the period boundary at or before the window.
                val offsets = com.fluxx.android.editor.RulerTiers.offsets(level)
                var boundary = Math.floorDiv(firstFrame + startFrame, level.period) * level.period - startFrame
                while (boundary <= lastFrame) {
                    for (o in offsets) tick(boundary + o)
                    boundary += level.period
                }
            }
            for ((index, marker) in markers.withIndex()) {
                if (marker.timeUs < visibleStartUs || marker.timeUs > visibleEndUs || marker.timeUs > durationUs) continue
                val x = (marker.timeUs * pxPerUs).toFloat()
                val half = 4.5.dp.toPx()
                val path = Path().apply {
                    moveTo(x - half, 16.dp.toPx()); lineTo(x + half, 16.dp.toPx())
                    lineTo(x + half, 23.dp.toPx()); lineTo(x, 28.dp.toPx())
                    lineTo(x - half, 23.dp.toPx()); close()
                }
                drawPath(path, Color(marker.colorArgb))
                drawPath(path, Color(0xFF1C1C1C), style = Stroke(.5.dp.toPx()))
                if (marker.description.isNotBlank()) {
                    val nextX = markers.getOrNull(index + 1)?.let { (it.timeUs * pxPerUs).toFloat() } ?: size.width
                    val room = minOf(140.dp.toPx(), nextX - x - 10.dp.toPx()).coerceAtLeast(0f)
                    val count = labelPaint.breakText(marker.description, true, room, null)
                    if (count > 0) {
                        val label = if (count < marker.description.length) marker.description.take((count - 1).coerceAtLeast(0)) + "…"
                            else marker.description
                        labelPaint.color = marker.colorArgb
                        drawContext.canvas.nativeCanvas.drawText(label, x + 6.dp.toPx(), 12.dp.toPx(), labelPaint)
                    }
                }
            }
        }
        // The marker popover is anchored to the pentagon itself (16..28dp of the ruler).
        markers.firstOrNull { it.id == popoverMarkerId }?.let { edited ->
            val x = with(density) { (edited.timeUs * pxPerUs).toFloat().toDp() }
            Box(Modifier.offset(x = x - 4.5.dp, y = MARKER_PENTAGON_TOP_DP.dp).size(9.dp, (MARKER_PENTAGON_BOTTOM_DP - MARKER_PENTAGON_TOP_DP).dp)
                .testTag("composition-marker-anchor")) { popover() }
        }
    } }
}
/**
 * Individual Timeline Clip Row: reproduces the layer structure from the reference screenshot
 */
@Composable
private fun TimelineClipRow(
    project: ProjectDocument,
    mediaRepository: MediaRepository,
    layer: CompositionLayer,
    isSelected: Boolean,
    activeProperty: AnimPropertyType?,
    activeAnimatorTrack: AnimatorTrackAddress? = null,
    onMarkerSelect: (Marker) -> Unit,
    popoverMarkerId: Long?,
    popover: @Composable () -> Unit,
    onDragSelect: () -> Unit,
    scrollOffsetPx: Int,
    onMoveEdge: (Float?) -> Unit,
    pxPerUs: Double,
    playheadUs: Long,
    frameRate: FrameRate,
    onSelect: () -> Unit,
    onEmptyTap: () -> Unit,
    onSeek: (Long, Boolean) -> Unit,
    onBeginGesture: () -> Unit,
    onPreviewGesture: (EditorAction) -> Unit,
    onCommitGesture: () -> Unit,
    onCancelGesture: () -> Unit,
    onOptionsClick: () -> Unit,
    /** TLP T3: the selection when this layer is part of it (a hold-drag then moves every selected layer). */
    groupSelection: com.fluxx.android.editor.LayerSelection? = null,
    /** TLP T5: the selected keys (local times) of this row in key selection mode, else null. */
    selectedKeys: Set<Long>? = null,
    onKeyToggle: (Long) -> Unit = {},
    onKeyDoubleTap: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val currentLayer by rememberUpdatedState(layer)
    val markerSnapTimes by rememberUpdatedState(remember(project.composition.markers, layer.markers,
        layer.resolvedKeyframeAnchorUs, layer.timing) {
        buildList<Long> {
            val start = layer.timing.startUs
            val end = layer.timing.endUs ?: start
            for (marker in project.composition.markers) if (marker.timeUs in start..end) add(marker.timeUs - start)
            for (marker in layer.markers) {
                val time = Math.addExact(layer.resolvedKeyframeAnchorUs, marker.timeUs)
                if (time in start..end) add(time - start)
            }
        }
    })
    val begin by rememberUpdatedState(onBeginGesture)
    val preview by rememberUpdatedState(onPreviewGesture)
    val commit by rememberUpdatedState(onCommitGesture)
    val cancel by rememberUpdatedState(onCancelGesture)
    val selectForDrag by rememberUpdatedState(onDragSelect)
    val currentScroll by rememberUpdatedState(scrollOffsetPx)
    val moveEdge by rememberUpdatedState(onMoveEdge)
    val selectMarker by rememberUpdatedState(onMarkerSelect)
    var moveBase by remember { mutableStateOf<CompositionLayer?>(null) }
    var moveProject by remember { mutableStateOf<ProjectDocument?>(null) }
    val group by rememberUpdatedState(groupSelection)
    val currentProject by rememberUpdatedState(project)
    val keyMode by rememberUpdatedState(selectedKeys != null)
    val keyToggle by rememberUpdatedState(onKeyToggle)
    val keyDoubleTap by rememberUpdatedState(onKeyDoubleTap)
    var movePixels by remember { mutableFloatStateOf(0f) }
    var moveScrollOrigin by remember { mutableStateOf(0) }
    var labelRootX by remember { mutableFloatStateOf(0f) }
    fun previewMove() {
        val base = moveBase ?: return
        val delta = ((movePixels + currentScroll - moveScrollOrigin) / pxPerUs).roundToLong()
        // TLP T3: a selected layer moves the whole selection by the same frames (one Batch of Move, same gesture calls).
        val together = group?.let { g -> moveProject?.let { p -> com.fluxx.android.editor.MultiLayerEdits.moveTogether(p, g, delta) ?: EditorAction.Batch(emptyList()) } }
        preview(together ?: TimelineEdits.move(project, base, base.timing.startUs + delta))
    }
    LaunchedEffect(scrollOffsetPx) { if (moveBase != null) previewMove() }
    DisposableEffect(layer.id) { onDispose { if (moveBase != null) { moveEdge(null); cancel() } } }
    val timing = layer.timing
    val startUs = timing.startUs
    val durationUs = timing.durationUs ?: 0L
    val endUs = startUs + durationUs

    val startX = (startUs * pxPerUs).toFloat()
    val widthPx = (durationUs * pxPerUs).toFloat().coerceAtLeast(32f)

    val density = LocalDensity.current
    val startDp = with(density) { startX.toDp() }
    val widthDp = with(density) { widthPx.toDp() }

    // Fetch real decoded thumbnail where available
    var thumbnail by remember(layer.asset?.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(layer.asset?.uri) {
        val uri = layer.asset?.uri
        if (uri != null) {
            thumbnail = mediaRepository.thumbnail(uri)
        }
    }

    // Clip background styling matching screenshot's visual distinction
    val clipBgColor = when (layer.type) {
        LayerType.VIDEO -> ClipVideoDark
        LayerType.IMAGE -> SurfaceElevated
        LayerType.SOLID -> Color(layer.solidColorArgb).compositeOver(Background)
        LayerType.TEXT -> ClipGold
    }

    val clipTextColor = when (layer.type) {
        LayerType.TEXT -> ClipGoldText
        LayerType.SOLID -> if (clipBgColor.luminance() > .179f) Color.Black else Color.White
        else -> TextPrimary
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TRACK_ROW_HEIGHT_DP.dp)
            .pointerInput(Unit) {
                detectTapGestures { onEmptyTap() }
            }
    ) {
        // Positioned Clip Body
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = startDp)
                .width(widthDp)
                .height(26.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(clipBgColor)
                .then(if (layer.type == LayerType.SOLID) Modifier.border(.5.dp,
                    clipTextColor.copy(alpha = .35f), RoundedCornerShape(6.dp)) else Modifier)
                .then(
                    if (isSelected) Modifier.border(
                        width = 1.5.dp,
                        color = if (layer.type == LayerType.SOLID) clipTextColor else Highlight,
                        shape = RoundedCornerShape(6.dp)
                    ) else Modifier
                )
        ) {
            // Video / Image: Repeated timeline thumbnails strip when available
            if (layer.type == LayerType.VIDEO || layer.type == LayerType.IMAGE) {
                thumbnail?.let { bmp ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(6.dp))
                    ) {
                        val numThumbnails = (widthPx / 48f).toInt().coerceAtLeast(1)
                        repeat(numThumbnails) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(48.dp)
                                    .fillMaxHeight()
                                    .border(0.5.dp, ClipVideoBorder)
                            )
                        }
                    }
                }
            }

            // Center: Clip Name / Label (the tap / hold-drag target is the hit box below, at least 48dp wide)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                val label = if (layer.type == LayerType.TEXT) layer.text.source.evaluate(playheadUs - layer.resolvedKeyframeAnchorUs)
                    .replace('\n', ' ').ifBlank { "Text" } else layer.name.ifBlank {
                    when (layer.type) {
                        LayerType.VIDEO -> "Video ${layer.id}"
                        LayerType.IMAGE -> "Image ${layer.id}"
                        LayerType.SOLID -> "Rectangle ${layer.id}"
                        LayerType.TEXT -> "Text ${layer.id}"
                    }
                }
                Text(
                    text = label,
                    color = clipTextColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

        }

        // GRAPH G4.2: the clip's tap / hold-drag target, at least 48dp wide (centred on a narrower clip), so a clip that
        // is a sliver at the zoom floor stays reachable. Keys and markers are drawn above it and win where they sit.
        val hitBox = com.fluxx.android.editor.TimelineHits.clipBox(startDp.value, widthDp.value)
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = hitBox.first.dp)
                .width(hitBox.second.dp)
                .height(TRACK_ROW_HEIGHT_DP.dp)
                .testTag("timeline-clip-hit-${layer.id}")
                .pointerInput(layer.id) {
                    detectTapGestures { onSelect() }
                }
                .onGloballyPositioned { labelRootX = it.positionInRoot().x }
                .pointerInput(layer.id, pxPerUs) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { point ->
                            moveBase = currentLayer; moveProject = currentProject; movePixels = 0f; moveScrollOrigin = currentScroll
                            selectForDrag(); begin(); moveEdge(labelRootX + point.x)
                        },
                        onDragEnd = { previewMove(); moveBase = null; moveEdge(null); commit() },
                        onDragCancel = { moveBase = null; moveEdge(null); cancel() },
                        onDrag = { change, amount ->
                            change.consume(); movePixels += amount.x
                            moveEdge(labelRootX + change.position.x); previewMove()
                        }
                    )
                }
        )

        // Keyframe Markers Container (unclipped, layered above clip body)
        val anim = layer.animTransform
        val anchorOffsetUs = layer.resolvedKeyframeAnchorUs - startUs
        // Every keyed property of the layer (all text properties and animators included), aggregated once per change.
        val observedTimes = remember(anim, layer.text, anchorOffsetUs) { LayerKeyMarkers.clipTimes(layer, anchorOffsetUs) }
        val focusedTimes = remember(anim, layer.text, activeProperty, activeAnimatorTrack, anchorOffsetUs) {
            LayerKeyMarkers.focusedClipTimes(layer, activeProperty, anchorOffsetUs) +
                LayerKeyMarkers.focusedAnimatorClipTimes(layer, activeAnimatorTrack, anchorOffsetUs)
        }
        val diamondPath = remember { Path() }
        val markerStroke = remember(density) { Stroke(width = with(density) { 1.5.dp.toPx() }) }
        val knockoutStroke = remember(density) { Stroke(width = with(density) { LayerKeyMarkers.KNOCKOUT_STROKE_DP.dp.toPx() }) }
        // Keep pointer nodes stable while the transient document moves/reorders the markers.
        var dragTimes by remember(layer.id) { mutableStateOf<List<Long>?>(null) }
        var movingFrom by remember(layer.id) { mutableStateOf<Long?>(null) }
        var movingTo by remember(layer.id) { mutableStateOf<Long?>(null) }
        val keyframeTimes = dragTimes ?: observedTimes
        val latestPlayhead by rememberUpdatedState(playheadUs)
        val latestTimes by rememberUpdatedState(observedTimes)
        val cancelDrag by rememberUpdatedState(onCancelGesture)
        DisposableEffect(layer.id) { onDispose { if (dragTimes != null) cancelDrag() } }

        if (keyframeTimes.isNotEmpty()) {
            val snapThresholdPx = with(density) { 12.dp.toPx() }
            val snapThresholdUs = (snapThresholdPx / pxPerUs).toLong()

            Box(
                modifier = Modifier
                    .offset(x = startDp)
                    .width(widthDp)
                    .fillMaxHeight()
            ) {
                // GRAPH G4.2: editable keys split overlapping 48dp targets at their midpoints (nearest wins), so keys
                // that cluster when zoomed far out all stay reachable; quiet keys are display only.
                val editableTimes = keyframeTimes.filter { it in 0L..durationUs && isSelected && (it in focusedTimes || movingFrom == it) }
                    .map { if (movingFrom == it) movingTo ?: it else it }.sorted()
                val editableXs = editableTimes.map { with(density) { (it * pxPerUs).toFloat().toDp().value } }
                val editableSpans = com.fluxx.android.editor.TimelineHits.spans(editableXs, LayerKeyMarkers.HIT_WIDTH_DP / 2f)
                for (kfTimeUs in keyframeTimes) {
                    if (kfTimeUs in 0L..durationUs) {
                        val editable = isSelected && (kfTimeUs in focusedTimes || movingFrom == kfTimeUs)
                        val displayTime = if (movingFrom == kfTimeUs) movingTo ?: kfTimeUs else kfTimeUs
                        val kfOffsetDp = with(density) { (displayTime * pxPerUs).toFloat().toDp() }
                        val isAtPlayhead = playheadUs == startUs + displayTime
                        val span = if (editable) editableSpans.getOrNull(editableTimes.indexOf(displayTime)) else null
                        val hitLeft = span?.start?.dp ?: (kfOffsetDp - (LayerKeyMarkers.HIT_WIDTH_DP / 2).dp)
                        val hitWidth = span?.let { (it.endInclusive - it.start).dp } ?: LayerKeyMarkers.HIT_WIDTH_DP.dp

                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .offset(x = hitLeft)
                                .size(width = hitWidth, height = TRACK_ROW_HEIGHT_DP.dp)
                                .then(if (editable) Modifier.pointerInput(kfTimeUs, startUs, anchorOffsetUs, activeProperty, activeAnimatorTrack) {
                                    // TLP T5: a double tap selects the key; in key selection mode taps toggle keys (no seek).
                                    val local = kfTimeUs - anchorOffsetUs
                                    detectTapGestures(
                                        onTap = {
                                            if (keyMode) keyToggle(local) else onSeek(startUs + kfTimeUs, false)
                                        },
                                        onDoubleTap = { if (keyMode) keyToggle(local) else keyDoubleTap(local) }
                                    )
                                }
                                .pointerInput(kfTimeUs, startUs, durationUs, pxPerUs, anchorOffsetUs, activeProperty, activeAnimatorTrack) {
                                    var initialKfTime = kfTimeUs
                                    var accumulatedUs = kfTimeUs
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            initialKfTime = kfTimeUs
                                            accumulatedUs = kfTimeUs
                                            dragTimes = latestTimes
                                            movingFrom = kfTimeUs
                                            movingTo = kfTimeUs
                                            begin()
                                        },
                                        onDragEnd = { commit(); dragTimes = null; movingFrom = null; movingTo = null },
                                        onDragCancel = { cancel(); dragTimes = null; movingFrom = null; movingTo = null },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            val deltaUs = (dragAmount.x / pxPerUs).roundToLong()
                                            accumulatedUs += deltaUs
                                            val snappedUs = TimelineEdits.snapKeyframeTime(
                                                rawTimeUs = accumulatedUs,
                                                playheadLocalUs = latestPlayhead - startUs,
                                                durationUs = durationUs,
                                                frameRate = frameRate,
                                                snapThresholdUs = snapThresholdUs,
                                                adjacentTimes = dragTimes.orEmpty(),
                                                movingTime = initialKfTime,
                                                clipStartUs = startUs,
                                                markerTimes = markerSnapTimes
                                            )
                                            movingTo = snappedUs
                                            val fromLocal = Math.subtractExact(initialKfTime, anchorOffsetUs)
                                            val toLocal = Math.subtractExact(snappedUs, anchorOffsetUs)
                                            // An animator-focused key moves only that field (existing AnimatorEdit.Key MOVE).
                                            preview(if (activeProperty == null && activeAnimatorTrack != null)
                                                EditorAction.EditTextAnimator(layer.id, AnimatorEdit.Key(activeAnimatorTrack, AnimatorKeyOperation.MOVE, fromLocal, toLocal))
                                            else EditorAction.MoveKeyframe(layer.id, activeProperty, fromLocal, toLocal))
                                        }
                                    )
                                } else Modifier),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            // Focused-property keys: full ink, normal size. Other properties: quiet (dimmed, smaller), display only.
                            // Centred on the bar midline, above the layer name, with a knock-out ring in the bar colour.
                            // The diamond stays centred on its own x even when its hit span is cut short by a neighbour.
                            val marker = LayerKeyMarkers.Marker(kfTimeUs, editable)
                            Canvas(modifier = Modifier.offset(x = kfOffsetDp - hitLeft - (marker.sizeDp / 2f).dp)
                                .size(marker.sizeDp.dp).alpha(marker.alpha)) {
                                LayerKeyMarkers.diamond(diamondPath, size)
                                drawPath(path = diamondPath, color = clipBgColor, style = knockoutStroke)
                                // TLP T5: a selected key gets an outer accent outline (beside the playhead fill rule).
                                if (selectedKeys?.contains(kfTimeUs - anchorOffsetUs) == true) drawPath(path = diamondPath, color = Highlight, style = Stroke(width = 2.5.dp.toPx()))
                                if (isAtPlayhead) {
                                    drawPath(
                                        path = diamondPath,
                                        color = Highlight
                                    )
                                    drawPath(
                                        path = diamondPath,
                                        color = Color(0xFF1C1C1C),
                                        style = markerStroke
                                    )
                                } else {
                                    drawPath(
                                        path = diamondPath,
                                        color = Color(0xFF1C1C1C)
                                    )
                                    drawPath(
                                        path = diamondPath,
                                        color = Highlight.copy(alpha = 0.9f),
                                        style = markerStroke
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        // Draw last: the strict top zone wins over the keyframe target underneath it.
        ExactTimelineTargets {
            for ((index, marker) in layer.markers.withIndex()) {
                val time = Math.addExact(layer.resolvedKeyframeAnchorUs, marker.timeUs)
                if (time >= startUs && time < endUs) {
                    val x = with(density) { (time * pxPerUs).toFloat().toDp() }
                    // Split overlapping horizontal targets at their midpoint so dense flags remain reachable.
                    val previous = layer.markers.getOrNull(index - 1)?.let { Math.addExact(layer.resolvedKeyframeAnchorUs, it.timeUs) }
                    val next = layer.markers.getOrNull(index + 1)?.let { Math.addExact(layer.resolvedKeyframeAnchorUs, it.timeUs) }
                    val left = if (previous != null && previous >= startUs)
                        maxOf(x - 24.dp, with(density) { ((previous / 2.0 + time / 2.0) * pxPerUs).toFloat().toDp() }) else x - 24.dp
                    val right = if (next != null && next < endUs)
                        minOf(x + 24.dp, with(density) { ((next / 2.0 + time / 2.0) * pxPerUs).toFloat().toDp() }) else x + 24.dp
                    Box(Modifier.offset(x = left).width((right - left).coerceAtLeast(0.dp))
                        .height(if (isSelected && (activeProperty != null || activeAnimatorTrack != null)) 12.dp else TRACK_ROW_HEIGHT_DP.dp)
                        .pointerInput(marker.id, marker.timeUs) { detectTapGestures { selectMarker(marker) } }
                        .semantics {
                            contentDescription = "Layer marker: ${marker.description}"
                            onClick { selectMarker(marker); true }
                        }) {
                        Box(Modifier.offset(x = x - left - 4.dp).size(8.dp, 7.dp)) { Canvas(Modifier.matchParentSize()) {
                            val flag = Path().apply {
                                moveTo(0f, 0f); lineTo(size.width, 0f)
                                lineTo(size.width / 2, size.height); close()
                            }
                            drawPath(flag, Color(marker.colorArgb))
                            val outline = if (Color(marker.colorArgb).luminance() > .179f) Color.Black else Color.White
                            drawPath(flag, outline, style = Stroke(.75.dp.toPx()))
                        }
                            // The marker popover is anchored to this triangle above the layer bar.
                            if (marker.id == popoverMarkerId) popover()
                        }
                    }
                    if (marker.description.isNotBlank()) {
                        val labelEnd = with(density) { ((next?.coerceAtMost(endUs) ?: endUs) * pxPerUs).toFloat().toDp() }
                        val labelWidth = minOf(140.dp, labelEnd - x - 10.dp).coerceAtLeast(0.dp)
                        if (labelWidth > 4.dp) Text(marker.description,
                            modifier = Modifier.offset(x = x + 6.dp).width(labelWidth)
                                .background(Color(marker.colorArgb), RoundedCornerShape(2.dp))
                                .padding(horizontal = 2.dp),
                            color = if (Color(marker.colorArgb).luminance() > .179f) Color.Black else Color.White,
                            fontSize = 8.sp, lineHeight = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/**
 * Stationary Pure White Playhead line with time readout pill bubble (00:00:00) fixed at horizontal center
 */
@Composable
private fun StationaryTimelinePlayhead(
    playheadUs: Long,
    startTimecodeUs: Long,
    columnPx: Int,
    frameRate: FrameRate,
    marker: Marker?,
    onTap: () -> Unit,
    onHold: () -> Unit,
    playheadPopover: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val tint = marker?.let { Color(it.colorArgb) } ?: Highlight
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    // TL1: the box is placed so its line's left edge is columnPx - lineWidth / 2 (integer px), the same rule the ruler uses
    // for a tick centred on its frame x: the line covers exactly the tick's pixels.
    val density = LocalDensity.current
    val boxPx = with(density) { 72.dp.roundToPx() }
    val linePx = with(density) { 1.dp.roundToPx().coerceAtLeast(1) }
    val lineLeft = boxPx / 2 - linePx / 2
    Box(
        modifier = modifier.offset { androidx.compose.ui.unit.IntOffset(columnPx - boxPx / 2, 0) }.width(with(density) { boxPx.toDp() }),
        contentAlignment = Alignment.TopCenter
    ) {
        // Vertical Pure White Playhead Line (extends down through all tracks)
        if (marker != null && tint.luminance() < .179f) {
            Box(Modifier.align(Alignment.TopCenter).fillMaxHeight().width(3.dp).background(Color.White))
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset { androidx.compose.ui.unit.IntOffset(lineLeft, 0) }
                .fillMaxHeight()
                .width(with(density) { linePx.toDp() })
                .background(tint)
                .testTag("timeline-playhead-line")
        )

        // Time readout occupies the ruler's upper band; ticks occupy the lower band.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = 1.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (marker != null && tint.luminance() < .179f) Color.White else Surface)
                .border(0.5.dp, tint, RoundedCornerShape(3.dp))
                .padding(horizontal = 4.dp, vertical = 0.5.dp)
        ) {
            Text(
                // TL1: the frame on screen (floor), counted from the composition's start frame.
                text = TimeFormat.formatPlayhead(playheadUs, startTimecodeUs, frameRate),
                color = tint,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
        }
        ExactTimelineTargets {
            Box(Modifier.align(Alignment.TopCenter).size(48.dp, RULER_HEIGHT_DP.dp)
                .pointerInput(Unit) { detectTapGestures(onTap = { tap() }, onLongPress = { hold() }) }
                .semantics {
                    contentDescription = "Playhead marker"
                    onClick("Add or remove marker") { tap(); true }
                    onLongClick("Configure marker") { hold(); true }
                }) { playheadPopover?.invoke() }
        }
    }
}
