package com.fluxx.android.ui.inspector

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.animation.core.VectorConverter
import kotlin.math.roundToInt
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxx.android.editor.*
import com.fluxx.android.model.EasingCurve
import com.fluxx.android.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The type strip inside the centre item: icon-only type tiles, tap to apply. */
@Composable internal fun GraphTypeStrip(width: Float, curve: EasingCurve, onType: (EaseChoice) -> Unit, modifier: Modifier = Modifier) {
    val selected = graphTypeTile(curve)
    LazyColumn(modifier.width(width.dp).fillMaxHeight().testTag("graph-type-strip"), verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(vertical = 2.dp)) {
        items(GRAPH_TYPE_TILES, key = { "type-${it.name}" }) { c ->
            InspectorIconButton(InspectorIcons.icon(CHOICE_ICONS.getValue(c)), c.label, Modifier.size(width.dp, 40.dp).testTag("graph-type-${c.name}"),
                selected = c == selected) { onType(c) }
        }
    }
}

/**
 * Long-press / drag state shared by the pinned rail and the library list. Positions are in root coordinates; each
 * item registers its current bounds, so drop targets follow scrolling and auto-scroll.
 */
internal class CurveDragState {
    var id by mutableStateOf<String?>(null); private set
    var pointerY by mutableFloatStateOf(0f); private set
    /** GRAPH G2 (A1): the pointer's x (root px), for drop slots in the 2-column grid. */
    var pointerX by mutableFloatStateOf(0f); private set
    val bounds = mutableStateMapOf<String, Rect>()
    var listBounds by mutableStateOf(Rect.Zero)
    /** Set by the long-pressed item; the container then owns the rest of the gesture. */
    internal var pointer: androidx.compose.ui.input.pointer.PointerId? = null
    internal var startY = 0f
    /** State: crossing the slop recomposes the lists (draft order) and shows the lifted tile. */
    var moved by mutableStateOf(false); internal set
    internal var onMenu: (() -> Unit)? = null
    internal var startX = 0f
    /** GRAPH G2.1 §8: the lifted tile (root px): where the pointer grabbed it, and its size. */
    var grab by mutableStateOf(Offset.Zero); private set
    var tile by mutableStateOf(androidx.compose.ui.geometry.Size.Zero); private set
    /** The live drop slot ([PinDrafts]) and, in the library, whether the pointer is over Pinned. Display only. */
    var slot by mutableStateOf<Int?>(null)
    var inside by mutableStateOf(false)
    /** After a drop: the curve being animated into its cell from [landingFrom] (root px, tile top-left). */
    var landing by mutableStateOf<String?>(null)
    var landingFrom = Offset.Zero
    /** Called on every move and every auto-scroll frame, to refresh [slot] from the current cell bounds. */
    internal var onRefresh: (() -> Unit)? = null
    fun start(id: String, x: Float, y: Float, pointer: androidx.compose.ui.input.pointer.PointerId, onMenu: (() -> Unit)?,
        grab: Offset = Offset.Zero, tile: androidx.compose.ui.geometry.Size = androidx.compose.ui.geometry.Size.Zero) {
        this.id = id; pointerX = x; pointerY = y; startX = x; startY = y; moved = false; this.pointer = pointer; this.onMenu = onMenu
        this.grab = grab; this.tile = tile; slot = null; inside = false; landing = null
    }
    fun move(x: Float, y: Float) { pointerX = x; pointerY = y; if (moved) onRefresh?.invoke() }
    fun end() { id = null; pointer = null; onMenu = null; slot = null; inside = false }
    /** The lifted tile's top-left (root px). */
    val tileTopLeft get() = Offset(pointerX, pointerY) - grab
}

/**
 * One curve tile's gestures: tap applies; a long press (light haptic) hands the gesture to the list container
 * ([dragContainer]): release without moving opens [onMenu] (user curves only), moving drags. Before the long press a
 * vertical move is left to the list's scroll.
 */
private fun Modifier.curveGestures(key: String, dragState: CurveDragState, onTap: () -> Unit, onMenu: (() -> Unit)?,
    onHaptic: () -> Unit): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val long = awaitLongPressOrCancellation(down.id)
        if (long == null) {
            val up = currentEvent.changes.firstOrNull { it.id == down.id }
            if (up != null && !up.pressed && !up.isConsumed) { up.consume(); onTap() }
            return@awaitEachGesture
        }
        onHaptic()
        val b = dragState.bounds[key]
        dragState.start(key.substringAfter('|'), (b?.left ?: 0f) + long.position.x, (b?.top ?: 0f) + long.position.y, long.id, onMenu,
            grab = long.position, tile = b?.size ?: androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()))
        long.consume()
    }
}

/**
 * The list container's half of a drag. It sees every event first (Initial pass) and, once an item has reported a long
 * press, follows that pointer: moves are consumed (so the list does not scroll by hand; [AutoScroll] scrolls near the
 * edges), and the release drops (or, without movement, opens the item's menu). It survives the dragged item scrolling
 * out of the list.
 */
private fun Modifier.dragContainer(dragState: CurveDragState, onDrop: (String, Float, Float) -> Unit): Modifier = composed {
    // The drop handler reads the CURRENT library state: never the one captured when the pointer coroutine started.
    val drop by rememberUpdatedState(onDrop)
    pointerInput(dragState) { dragLoop(dragState) { id, x, y -> drop(id, x, y) } }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.dragLoop(dragState: CurveDragState, onDrop: (String, Float, Float) -> Unit) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
        while (true) {
            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            val id = dragState.pointer
            val change = event.changes.firstOrNull { it.id == id }
            if (id == null || change == null) { if (event.changes.none { it.pressed }) break else continue }
            val x = dragState.listBounds.left + change.position.x
            val y = dragState.listBounds.top + change.position.y
            if (!dragState.moved && kotlin.math.hypot(x - dragState.startX, y - dragState.startY) > slop) dragState.moved = true
            change.consume()
            dragState.move(x, y)
            if (!change.pressed) {
                val curve = dragState.id; val moved = dragState.moved; val menu = dragState.onMenu
                // The drop reads the live slot before the state ends; the lifted tile then lands in its cell.
                if (curve != null && moved) { dragState.landingFrom = dragState.tileTopLeft; onDrop(curve, x, y); dragState.landing = curve }
                dragState.end()
                if (curve != null && !moved) menu?.invoke()
                break
            }
        }
    }
}

/** The curve drawing used for thumbnails (fills its box; no name). */
@Composable internal fun CurveThumbnail(curve: EasingCurve, selected: Boolean, modifier: Modifier, insetDp: Float = 4f) {
    val range = remember(curve) { EasePlot.range(curve) }
    Canvas(modifier) {
        val inset = insetDp.dp.toPx()
        val plot = EasePlot(size.width, size.height, inset, range.first, range.second)
        val pts = EaseSampling.points(curve)
        val path = Path().apply { moveTo(plot.x(pts[0]), plot.y(pts[1])); var i = 2; while (i < pts.size) { lineTo(plot.x(pts[i]), plot.y(pts[i + 1])); i += 2 } }
        drawPath(path, if (selected) TextPrimary else TextSecondary, style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The star badge on user-made curves (monochrome family glyph). */
@Composable private fun StarBadge(modifier: Modifier) =
    Icon(InspectorIcons.icon("GRAPH_STAR"), "User curve", modifier.size(10.dp), tint = TextSecondary)

/** Library-wide UI state that outlives one tile: the open popover, the save field and transient messages. */
internal class GraphLibraryUi {
    var menuFor by mutableStateOf<String?>(null)
    var menuMode by mutableStateOf(0) // 0 icons, 1 xy, 2 rename
}

/**
 * The pinned rail (closed library): up to 15 pinned curves, 40dp thumbnail tiles, vertical scroll only. Long-press
 * then move reorders; long-press release opens the curve menu for user curves; built-ins have no popover.
 */
@Composable internal fun GraphPinnedRail(state: CurveLibraryState, curve: EasingCurve, store: CurveLibraryStore, ui: GraphLibraryUi,
    onApply: (EasingCurve) -> Unit, modifier: Modifier = Modifier) {
    val pinned = CurveLibrary.sections(state).pinned
    val drag = remember { CurveDragState() }
    val list = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    // GRAPH G2.1 §8: the rail reorders with the same lift, follow and slide-aside as the library (display-only draft).
    val draft = drag.id?.takeIf { drag.moved }?.let { PinDrafts.draft(state.pinned, it, drag.slot ?: state.pinned.indexOf(it).coerceAtLeast(0)) }
    val shown = draft?.order?.mapNotNull { id -> CurveLibrary.entry(state, id) } ?: pinned
    val latestShown by rememberUpdatedState(shown.map { "rail|${it.id}" })
    drag.onRefresh = {
        val keys = latestShown
        // Settled positions of the laid-out cells (never mid-slide; the rail scrolls); slots are display indices.
        val cells = settledList(list, drag.listBounds, keys)
        if (cells.isNotEmpty()) {
            val h = with(density) { DRAG_HYSTERESIS.dp.toPx() }
            val cur = drag.slot ?: state.pinned.indexOf(drag.id)
            val cx = cells[0].second.center.x
            val next = PinDrafts.stable(cur, Offset(cx, drag.pointerY), cells.firstOrNull { it.first == cur }?.second, h) { at ->
                PinDrafts.candidateIndexed(cells, keys.size, cx, at.y, gapPresent = true, full = false) }
            if (next != null && next != drag.slot) {
                if (drag.slot != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                // Keep the scroll by INDEX: a lazy list anchors to its first visible item's key, so moving that item in the
                // draft would scroll the list after it and run the slot on under a still finger.
                list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
                drag.slot = next
            }
        }
    }
    AutoScroll(drag, list)
    Box(modifier.onGloballyPositioned { drag.listBounds = it.boundsInRoot() }.dragContainer(drag) { id, _, _ ->
        // One store write per drop: the live slot (already computed while dragging).
        val slot = drag.slot ?: return@dragContainer
        store.update { PinDrafts.commit(it, id, slot) }
    }) {
        LazyColumn(Modifier.width(GraphLayout.RAIL.dp).fillMaxHeight().testTag("graph-pinned-rail"), state = list,
            verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
            items(shown, key = { "rail|${it.id}" }) { e ->
                val key = "rail|${e.id}"
                val selected = CurveLibrary.matches(e, curve)
                val lifted = drag.id == e.id && drag.moved || drag.landing == e.id
                Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = androidx.compose.animation.core.tween(DRAG_REFLOW_MS))
                    .size(GraphLayout.RAIL.dp).clip(RoundedCornerShape(10.dp)).background(if (selected && !lifted) Surface else Color.Transparent)
                    .graphicsLayer { alpha = if (lifted) 0f else 1f }
                    .onGloballyPositioned { drag.bounds[key] = it.boundsInRoot() }
                    .curveGestures(key, drag, onTap = { onApply(e.curve) }, onMenu = if (e.user) ({ ui.menuFor = e.id; ui.menuMode = 0 }) else null,
                        onHaptic = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) })
                    .semantics(mergeDescendants = true) { contentDescription = e.name; if (selected) stateDescription = "Selected" }
                    .testTag("graph-pinned-${e.id}"), contentAlignment = Alignment.Center) {
                    CurveThumbnail(e.curve, selected, Modifier.size(28.dp))
                    if (e.user) StarBadge(Modifier.align(Alignment.TopEnd).padding(2.dp))
                    if (ui.menuFor == e.id) CurveMenuPopover(state, e, store, ui)
                }
            }
        }
        LiftedTile(drag, state, curve, rail = true)
    }
}

/**
 * GRAPH G2.1 §8: the SETTLED (target) rects, in root px, of the laid-out items among [keys] (display index → rect), from
 * the lazy layout's own item offsets, which exclude placement (slide-aside) animation.
 */
private fun settledGrid(grid: LazyGridState, list: Rect, keys: List<String>): List<Pair<Int, Rect>> {
    val info = grid.layoutInfo
    val index = keys.withIndex().associate { it.value to it.index }
    return info.visibleItemsInfo.mapNotNull { item ->
        val i = index[item.key as? String] ?: return@mapNotNull null
        val x = list.left + item.offset.x; val y = list.top + item.offset.y - info.viewportStartOffset
        i to Rect(x, y, x + item.size.width, y + item.size.height)
    }.sortedBy { it.first }
}
private fun settledList(lazy: LazyListState, list: Rect, keys: List<String>): List<Pair<Int, Rect>> {
    val info = lazy.layoutInfo
    val index = keys.withIndex().associate { it.value to it.index }
    return info.visibleItemsInfo.mapNotNull { item ->
        val i = index[item.key as? String] ?: return@mapNotNull null
        val y = list.top + item.offset - info.viewportStartOffset
        i to Rect(list.left, y, list.right, y + item.size)
    }.sortedBy { it.first }
}

/** GRAPH G2.1 §8 timings and sizes. */
internal const val DRAG_REFLOW_MS = 180
internal const val DRAG_LAND_MS = 150
internal const val DRAG_LIFT_SCALE = 1.08f
internal const val DRAG_HYSTERESIS = 8f

/**
 * The lifted tile: drawn above the list in an overlay, following the pointer exactly (scale [DRAG_LIFT_SCALE], shadow,
 * slight transparency; dimmed when the drop would unpin). After the drop it animates into its final cell ([DRAG_LAND_MS]).
 */
@Composable private fun BoxScope.LiftedTile(drag: CurveDragState, state: CurveLibraryState, curve: EasingCurve, rail: Boolean, unpinning: Boolean = false) {
    val id = drag.id?.takeIf { drag.moved } ?: drag.landing ?: return
    val e = CurveLibrary.entry(state, id) ?: return
    val d = LocalDensity.current
    val pos = remember(id) { androidx.compose.animation.core.Animatable(drag.tileTopLeft, Offset.VectorConverter) }
    val landing = drag.landing == id && drag.id == null
    if (landing) LaunchedEffect(id) {
        pos.snapTo(drag.landingFrom)
        // The cell's bounds after the committed write (one frame later); without a cell (unpinned) it just fades.
        withFrameNanos { }
        val key = drag.bounds.keys.firstOrNull { it.substringAfter('|') == id && (it.startsWith("pin|") || it.startsWith("rail|")) }
        val target = key?.let { drag.bounds[it] }?.topLeft
        if (target != null) pos.animateTo(target, androidx.compose.animation.core.tween(DRAG_LAND_MS))
        else delay(DRAG_LAND_MS.toLong())
        if (drag.landing == id) drag.landing = null
    }
    // While dragging the tile is drawn exactly at the pointer (no animation lag); landing animates [pos].
    val local = (if (landing) pos.value else drag.tileTopLeft) - drag.listBounds.topLeft
    val size = drag.tile
    val shape = RoundedCornerShape(if (rail) 10.dp else 6.dp)
    Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(local.x.roundToInt(), local.y.roundToInt()) }
        .size(with(d) { size.width.toDp() }, with(d) { size.height.toDp() })
        .graphicsLayer { scaleX = DRAG_LIFT_SCALE; scaleY = DRAG_LIFT_SCALE; alpha = if (unpinning && !landing) .5f else .92f
            shadowElevation = 8.dp.toPx(); this.shape = shape; clip = true }
        .background(SurfaceHigh).border(.5.dp, Border, shape).testTag("graph-drag-lifted")
        .semantics { contentDescription = "Dragging ${e.name}" + if (unpinning) ", release to unpin" else "" },
        contentAlignment = Alignment.Center) {
        val selected = CurveLibrary.matches(e, curve)
        if (rail) CurveThumbnail(e.curve, selected, Modifier.size(28.dp))
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CurveThumbnail(e.curve, selected, Modifier.fillMaxWidth().height(GraphLayout.GRID_THUMB_H.dp), insetDp = 3f)
            Text(e.name, color = TextPrimary, fontSize = 7.sp, lineHeight = GraphLayout.GRID_NAME_LINE.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Auto-scrolls [list] while a drag is inside a [DragAutoScroll.EDGE_DP] zone at its top or bottom edge, with a speed that
 * ramps up towards the edge ([DragAutoScroll.speed]); it keeps scrolling while the finger rests in the zone. Every frame
 * also refreshes the drop slot, so the gap follows cells scrolling under a still finger.
 */
@Composable private fun AutoScroll(drag: CurveDragState, list: ScrollableState) {
    val density = LocalDensity.current.density
    LaunchedEffect(drag.id != null) {
        var last = 0L
        while (drag.id != null) {
            val now = withFrameNanos { it }
            val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(.05f)
            last = now
            if (!drag.moved) continue
            val b = drag.listBounds
            val v = DragAutoScroll.velocity(drag.pointerY / density, b.top / density, b.bottom / density)
            // GRAPH G3.1: never scroll past an end. A drag that starts inside an edge zone (lifting the first rail item) used to
            // call scrollBy every frame at the top; that no-op scroll competed with the slot's requestScrollToItem anchor.
            val canMove = (v < 0f && list.canScrollBackward) || (v > 0f && list.canScrollForward)
            if (canMove && dt > 0f) list.scrollBy(v * dt * density)
            drag.onRefresh?.invoke()
        }
    }
}

/**
 * The floating library toggle at the bottom of the right column. GRAPH G2 (A2): one soft radial scrim in the surface
 * colour, the SAME composable and parameters open and closed, centred on the icon and fading out [GraphLayout.SCRIM_EXTENT]
 * beyond its bounds (left and top), so tiles scroll in from underneath it and there is no solid block.
 */
@Composable internal fun GraphLibraryButton(open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val size = GraphLayout.scrimSize(); val icon = GraphLayout.RAIL
    Box(modifier.size(size.dp).testTag("graph-library-scrim").drawBehind {
        val c = Offset(this.size.width - (icon / 2).dp.toPx(), this.size.height - (icon / 2).dp.toPx())
        val r = (icon / 2 + GraphLayout.SCRIM_EXTENT).dp.toPx()
        val inner = (icon / 2) / (icon / 2 + GraphLayout.SCRIM_EXTENT)
        drawCircle(Brush.radialGradient(0f to Surface.copy(alpha = .9f), inner * .8f to Surface.copy(alpha = .82f), 1f to Surface.copy(alpha = 0f),
            center = c, radius = r), r, c)
    }) {
        InspectorIconButton(InspectorIcons.icon("GRAPH_LIBRARY"), if (open) "Close curve library" else "Open curve library",
            Modifier.align(Alignment.BottomEnd).size(icon.dp).testTag("graph-library").semantics { selected = open },
            selected = open, tint = if (open) Highlight else null) { onToggle() }
    }
}

/**
 * The library (open). GRAPH G2 (A1): header (no title; import/export and save) over a 2-column grid of curve cells in
 * the Pinned / Presets / My curves sections (no Types: types live in the strip). Pinning is by drag only. The grid
 * state lives in the page session ([grid]), so the scroll position survives closing and reopening the library while
 * the Graph page stays open.
 */
@Composable internal fun GraphLibraryColumn(state: CurveLibraryState, curve: EasingCurve, store: CurveLibraryStore, ui: GraphLibraryUi,
    grid: LazyGridState, onApply: (EasingCurve) -> Unit, modifier: Modifier = Modifier) {
    val sections = CurveLibrary.sections(state)
    val drag = remember { CurveDragState() }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val fontScale = LocalDensity.current.fontScale
    val density = LocalDensity.current
    // GRAPH G2.1 §8: the display-only draft while dragging; the Pinned section shows its order and the lifted tile's gap.
    val dragged = drag.id?.takeIf { drag.moved }
    val full = dragged != null && dragged !in state.pinned && state.pinned.size >= CurveLibrary.MAX_PINNED
    val draft = dragged?.let { PinDrafts.draft(state.pinned, it, if (drag.inside) drag.slot else null) }
    // Full set: Pinned keeps its order; the cell in the slot is dimmed (it returns to the library on drop).
    val shownPinned = (if (full) state.pinned else draft?.order ?: state.pinned).mapNotNull { CurveLibrary.entry(state, it) }
    val evicted = draft?.evicted
    val pinnedKeys = shownPinned.map { "pin|${it.id}" }
    /** Whether a pointer at [y] (root px) is over the Pinned section, from the captions and cells currently laid out. */
    fun inPinned(y: Float): Boolean {
        val cap = drag.bounds["cap|pinned"]
        val presets = drag.bounds["cap|presets"]
        val visible = pinnedKeys.mapNotNull { drag.bounds[it] }
        if (cap == null) {
            // The Pinned caption scrolled away above: Pinned is whatever pinned cells are still visible, down to Presets.
            if (visible.isEmpty()) return false
            return y <= (presets?.top ?: (visible.maxOf { it.bottom } + 12f))
        }
        if (y < cap.top) return false
        // The Presets caption (when visible) closes the section; otherwise a section running past the viewport goes on.
        presets?.let { return y <= it.top }
        val last = pinnedKeys.lastOrNull()?.let { drag.bounds[it] }
        if (pinnedKeys.isNotEmpty() && last == null) return true
        return y <= (last?.bottom ?: cap.bottom) + 12f
    }
    val latest by rememberUpdatedState(Triple(pinnedKeys, full, !full && draft?.gap != null))
    drag.onRefresh = {
        val (keys, isFull, gapPresent) = latest
        val inside = inPinned(drag.pointerY)
        drag.inside = inside
        // Settled positions of the laid-out cells (a long Pinned section runs past the viewport); slots are display indices.
        // Never the animated bounds: a cell sliding aside is still under the finger, which would flip the slot back.
        val cells = settledGrid(grid, drag.listBounds, keys)
        if (inside) {
            val h = with(density) { DRAG_HYSTERESIS.dp.toPx() }
            val cur = drag.slot
            val next = PinDrafts.stable(cur, Offset(drag.pointerX, drag.pointerY), cur?.let { c -> cells.firstOrNull { it.first == c }?.second }, h) { at ->
                PinDrafts.candidateIndexed(cells, keys.size, at.x, at.y, gapPresent, isFull) }
            if (next != null && next != cur) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                grid.requestScrollToItem(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset) // see the rail
                drag.slot = next
            }
        } else if (!inside && drag.slot != null) drag.slot = null
    }
    AutoScroll(drag, grid)
    fun drop(id: String, @Suppress("UNUSED_PARAMETER") x: Float, @Suppress("UNUSED_PARAMETER") y: Float) {
        // One store write: the live slot; outside Pinned a pinned curve unpins (nothing changes for an unpinned one).
        val slot = if (drag.inside) drag.slot ?: return else null
        store.update { PinDrafts.commit(it, id, slot) }
    }
    val nameHeight = 2 * GraphLayout.GRID_NAME_LINE * fontScale.coerceAtLeast(1f)
    @Composable fun Cell(e: LibraryEntry, key: String, placement: Modifier) {
        val selected = CurveLibrary.matches(e, curve)
        // A cell scrolled out of the grid drops its bounds (stale bounds would misplace drops).
        DisposableEffect(key) { onDispose { drag.bounds.remove(key) } }
        // The lifted curve's cells are empty gaps (the overlay tile follows the finger); the evicted one is dimmed.
        val gap = (dragged == e.id || drag.landing == e.id)
        val dim = evicted == e.id && key.startsWith("pin|")
        Column(placement.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (selected && !gap) Surface else Color.Transparent)
            .then(if (dim) Modifier.border(1.dp, TextDisabled, RoundedCornerShape(6.dp)) else Modifier)
            .graphicsLayer { alpha = if (gap) 0f else if (dim) .35f else 1f }
            .onGloballyPositioned { drag.bounds[key] = it.boundsInRoot() }
            .curveGestures(key, drag, onTap = { onApply(e.curve) }, onMenu = if (e.user) ({ ui.menuFor = e.id; ui.menuMode = 0 }) else null,
                onHaptic = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) })
            .semantics(mergeDescendants = true) { contentDescription = e.name + if (e.user) ", user curve" else ""
                if (selected) stateDescription = "Selected"; if (dim) stateDescription = "Returns to the library" }
            .testTag("graph-lib-${e.id}").padding(top = 1.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CurveCellContent(e.curve, e.name, selected, e.user, nameHeight) { if (ui.menuFor == e.id) CurveMenuPopover(state, e, store, ui) }
        }
    }
    @Composable fun Caption(text: String, key: String) {
        DisposableEffect(key) { onDispose { drag.bounds.remove(key) } }
        CurveGridCaption(text, Modifier.onGloballyPositioned { drag.bounds[key] = it.boundsInRoot() }.testTag("graph-lib-$key"))
    }
    val span: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) = { GridItemSpan(maxLineSpan) }
    Box(modifier.onGloballyPositioned { drag.listBounds = it.boundsInRoot() }.dragContainer(drag, ::drop).testTag("graph-library-column")) {
        LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize().testTag("graph-library-grid"), state = grid,
            contentPadding = PaddingValues(top = GraphLayout.LIBRARY_HEADER.dp, bottom = GraphLayout.scrimSize().dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            item(key = "cap|pinned", span = span) { Caption("Pinned", "cap|pinned") }
            val reflow = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(DRAG_REFLOW_MS)
            items(shownPinned, key = { "pin|${it.id}" }) { Cell(it, "pin|${it.id}", Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = reflow)) }
            item(key = "cap|presets", span = span) { Caption("Presets", "cap|presets") }
            items(sections.presets, key = { "preset|${it.id}" }) { Cell(it, "preset|${it.id}", Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = reflow)) }
            item(key = "cap|mine", span = span) { Caption("My curves", "cap|mine") }
            items(sections.mine, key = { "mine|${it.id}" }) { Cell(it, "mine|${it.id}", Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = reflow)) }
        }
        LiftedTile(drag, state, curve, rail = false, unpinning = draft?.unpinning == true)
        LibraryHeader(state, curve, store) { _ ->
            // The new curve scrolls into view (the top of My curves).
            scope.launch {
                delay(50)
                val index = (1 + sections.pinned.size + 1 + sections.presets.size).coerceAtMost((grid.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                runCatching { grid.animateScrollToItem(index) }
            }
        }
    }
}

/** One library cell's look (shared by the library grid and the key bar's curve popover): thumbnail over up to two name lines. */
@Composable internal fun ColumnScope.CurveCellContent(curve: EasingCurve, name: String, selected: Boolean, user: Boolean, nameHeight: Float,
    overlay: @Composable BoxScope.() -> Unit = {}) {
    Box(Modifier.fillMaxWidth().height(GraphLayout.GRID_THUMB_H.dp)) {
        CurveThumbnail(curve, selected, Modifier.fillMaxSize(), insetDp = 3f)
        if (user) StarBadge(Modifier.align(Alignment.TopEnd).padding(1.dp))
        overlay()
    }
    Text(name, color = if (selected) TextPrimary else TextSecondary, fontSize = 7.sp, lineHeight = GraphLayout.GRID_NAME_LINE.sp, maxLines = 2,
        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 1.dp, start = 1.dp, end = 1.dp).height(nameHeight.dp).testTag("graph-lib-name"))
}

/** A section caption of the curve grid (spans both columns). */
@Composable internal fun CurveGridCaption(text: String, modifier: Modifier = Modifier) {
    val fontScale = LocalDensity.current.fontScale
    Column(modifier.fillMaxWidth().height(GraphLayout.gridCaption(fontScale).dp), verticalArrangement = Arrangement.Bottom) {
        Text(text, color = TextSecondary, fontSize = 7.sp, lineHeight = 8.sp, maxLines = 1, modifier = Modifier.padding(start = 2.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
    }
}

/**
 * TLP.3 A1: the key bar's More popover body. The library's 2-column grid cells (same thumbnails and names), with the types
 * Ease In, Ease Out, Bounce and Elastic first (default parameters, exactly as the Graph type strip applies them to a curve
 * of another type: `EaseChoice.curve(choice, EASY_EASE)`), then the library's Pinned, Presets and My curves sections.
 * Tap only: no long-press menu, no drag, no save / import / export. Bounded ([KeyCurvePopover]) and scrollable.
 */
@Composable internal fun KeyCurveGrid(state: CurveLibraryState, onApply: (EasingCurve) -> Unit) {
    val sections = CurveLibrary.sections(state)
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val span: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) = { GridItemSpan(maxLineSpan) }
    // TL3.1 B5: compact cells (a small thumbnail over ONE ellipsised name line), 4 columns, about 4 rows visible.
    @Composable fun Cell(curve: EasingCurve, name: String, user: Boolean, tag: String) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp)).clickable(role = Role.Button) { onApply(curve) }
            .semantics(mergeDescendants = true) { contentDescription = name }.testTag(tag).padding(top = 1.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(KeyCurvePopover.THUMB_DP.dp)) {
                CurveThumbnail(curve, false, Modifier.fillMaxSize(), insetDp = 2f)
                if (user) StarBadge(Modifier.align(Alignment.TopEnd))
            }
            Text(name, color = TextSecondary, fontSize = 7.sp, lineHeight = GraphLayout.GRID_NAME_LINE.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 1.dp).height((GraphLayout.GRID_NAME_LINE * fontScale).dp).testTag("key-more-name"))
        }
    }
    @Composable fun Label(text: String) = Text(text, color = TextSecondary, fontSize = 7.sp, lineHeight = 8.sp, maxLines = 1,
        modifier = Modifier.padding(start = 2.dp, top = 2.dp).testTag("key-more-label"))
    LazyVerticalGrid(GridCells.Fixed(KeyCurvePopover.COLUMNS), Modifier.width(KeyCurvePopover.WIDTH.dp).heightIn(max = KeyCurvePopover.maxHeight(fontScale).dp)
        .blocksPopoverSwipe().testTag("key-more-grid"),
        horizontalArrangement = Arrangement.spacedBy(KeyCurvePopover.GAP_DP.dp), verticalArrangement = Arrangement.spacedBy(KeyCurvePopover.GAP_DP.dp)) {
        items(KeyCurvePopover.TYPES, key = { "type|${it.name}" }) { c -> Cell(EaseChoice.curve(c, EasingCurve.EASY_EASE), c.label, false, "key-more-${c.name}") }
        if (sections.pinned.isNotEmpty()) item(key = "cap|pinned", span = span) { Label("Pinned") }
        items(sections.pinned, key = { "pin|${it.id}" }) { Cell(it.curve, it.name, it.user, "key-more-pinned-${it.id}") }
        if (sections.presets.isNotEmpty()) item(key = "cap|presets", span = span) { Label("Presets") }
        items(sections.presets, key = { "preset|${it.id}" }) { Cell(it.curve, it.name, it.user, "key-more-preset-${it.id}") }
        if (sections.mine.isNotEmpty()) item(key = "cap|mine", span = span) { Label("My curves") }
        items(sections.mine, key = { "mine|${it.id}" }) { Cell(it.curve, it.name, it.user, "key-more-mine-${it.id}") }
    }
}

/** TLP.3 A1 / TL3.1 B5: the key curve popover's entries and bounds (dp): 4 columns, about 4 rows visible, then scroll. */
internal object KeyCurvePopover {
    val TYPES = listOf(EaseChoice.EASE_IN, EaseChoice.EASE_OUT, EaseChoice.BOUNCE, EaseChoice.ELASTIC)
    const val COLUMNS = 4
    const val VISIBLE_ROWS = 4
    const val CELL_DP = 42f
    const val GAP_DP = 2f
    const val THUMB_DP = 18f
    const val WIDTH = COLUMNS * CELL_DP + (COLUMNS - 1) * GAP_DP
    /** About [VISIBLE_ROWS] rows of cells (thumbnail, a gap and one name line) with one slim label. */
    fun maxHeight(fontScale: Float) = VISIBLE_ROWS * (THUMB_DP + 1f + GraphLayout.GRID_NAME_LINE * fontScale + GAP_DP) + 10f
}

/** The header row: no title; import/export and save icons at the right, over a fade so thumbnails scroll under it. */
@Composable private fun BoxScope.LibraryHeader(state: CurveLibraryState, curve: EasingCurve, store: CurveLibraryStore, onSaved: (String) -> Unit) {
    var saving by remember { mutableStateOf(false) }
    var exchange by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val canSave = CurveLibrary.savable(curve) && CurveLibrary.canAdd(state)
    fun toast(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.use {
                it.write(CurveExchange.export(store.state.value).toByteArray(Charsets.UTF_8)) } }.isSuccess }
            toast(if (ok) "Exported ${store.state.value.user.size} curves" else "Export failed")
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)!!.use { readCapped(it, CurveExchange.MAX_BYTES + 1) } }.getOrNull() }
            if (bytes == null) { toast("Could not read the file"); return@launch }
            var message = ""
            store.update { s -> CurveExchange.import(s, bytes).also { message = it.message }.state }
            toast(message)
        }
    }
    Row(Modifier.align(Alignment.TopStart).fillMaxWidth().height(GraphLayout.LIBRARY_HEADER.dp)
        .background(Brush.verticalGradient(listOf(Surface, Surface, Surface.copy(alpha = 0f)))).testTag("graph-library-header"),
        horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Box {
            HeaderIcon("GRAPH_EXCHANGE", "Import or export curves", "graph-library-exchange", selected = exchange) {
                val open = PopoverToggle.next(if (exchange) "x" else null, "x") != null
                if (open) { PopoverDismissal.request(); exchange = true } else exchange = false
            }
            AnchoredPopover(exchange, { exchange = false }, "graph-exchange-popover", modal = false) {
                Row {
                    InspectorIconButton(InspectorIcons.icon("IMPORT"), "Import curves", Modifier.testTag("graph-import")) {
                        exchange = false; import.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }
                    InspectorIconButton(InspectorIcons.icon("GRAPH_EXPORT"), "Export my curves", Modifier.testTag("graph-export"), enabled = state.user.isNotEmpty()) {
                        exchange = false; export.launch("My curves.fluxxcurves") }
                }
            }
        }
        Box {
            HeaderIcon("GRAPH_SAVE", "Save this curve", "graph-library-save", enabled = canSave, selected = saving) {
                if (saving) return@HeaderIcon
                PopoverDismissal.request(); saving = true
            }
            if (saving) {
                val placeholder = CurveLibrary.defaultName(state)
                var done by remember { mutableStateOf(false) }
                fun save(text: String) {
                    if (done) return; done = true; saving = false
                    var newId: String? = null
                    store.update { s -> CurveLibrary.add(s, text, curve)?.also { newId = it.second }?.first ?: s }
                    newId?.let(onSaved)
                }
                // Closing the popover in any way saves: tap away, IME Done, focus loss, Back (closing disposes the field, which commits).
                AnchoredPopover(true, { saving = false }, "graph-save-popover", modal = false) {
                    CurveNameField("", placeholder, "Curve name") { save(it) }
                }
            }
        }
    }
}

@Composable private fun HeaderIcon(icon: String, label: String, tag: String, enabled: Boolean = true, selected: Boolean = false, click: () -> Unit) {
    Box(Modifier.size(32.dp, GraphLayout.LIBRARY_HEADER.dp).clip(RoundedCornerShape(8.dp)).background(if (selected) Surface else Color.Transparent)
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = label) { click() }
        .semantics { contentDescription = label; if (selected) stateDescription = "Selected" }
        .testTag(tag), contentAlignment = Alignment.Center) {
        Icon(InspectorIcons.icon(icon), null, Modifier.size(20.dp), tint = if (!enabled) TextDisabled else if (selected) TextPrimary else TextSecondary)
    }
}

/**
 * A text field for a curve name inside a non-modal popover: faded [placeholder], select-all, commits once on IME Done,
 * focus loss or disposal (closing the popover). No Done button.
 */
@Composable private fun CurveNameField(initial: String, placeholder: String, label: String, onCommit: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var committed by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val commit by rememberUpdatedState(onCommit)
    fun finish() { if (!committed) { committed = true; commit(text) } }
    val typing = LocalPopoverTyping.current
    DisposableEffect(typing) { typing?.let { it.intValue++ }; onDispose { typing?.let { it.intValue-- }; finish() } }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { withFrameNanos { }; withFrameNanos { }; runCatching { requester.requestFocus() }; keyboard?.show() }
    Box(Modifier.width(160.dp).height(36.dp).clip(RoundedCornerShape(8.dp)).background(Surface).border(1.dp, TextPrimary, RoundedCornerShape(8.dp))
        .padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
        if (text.isEmpty()) Text(placeholder, color = TextDisabled, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        BasicTextField(text, { text = it.take(CurveLibrary.NAME_LIMIT) }, singleLine = true,
            textStyle = MaterialTheme.typography.labelLarge.copy(color = TextPrimary), cursorBrush = SolidColor(TextPrimary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { finish() }),
            modifier = Modifier.fillMaxWidth().focusRequester(requester).onFocusChanged {
                if (focused && !it.isFocused) finish(); focused = it.isFocused }
                .semantics { contentDescription = label }.testTag("graph-name-field"))
    }
}

/** The user-curve menu: xy (edit values), pencil (rename), trash (delete), in that order. Edits the stored curve only. */
@Composable private fun CurveMenuPopover(state: CurveLibraryState, e: LibraryEntry, store: CurveLibraryStore, ui: GraphLibraryUi) {
    AnchoredPopover(true, { ui.menuFor = null; ui.menuMode = 0 }, "graph-curve-menu", modal = false) {
        when (ui.menuMode) {
            1 -> {
                // GRAPH G2.1 §7: a compact grid of independent fields (2 x 2 for bezier, 1 x 2 for Bounce / Elastic), each a
                // values-bar tile. No auto-focus and no re-keying (the G2 cause of the reappearing keyboard): a field opens
                // the keyboard only when tapped; Done, a tap elsewhere in the popover or closing it commits THAT field only
                // ([XyEdit.commit]: clamped, invalid discarded, empty keeps) and the keyboard closes. The curve is read back
                // from the store at commit time, never captured. Edits the STORED curve only (never the graph).
                val rows = EaseLimits.rows(e.curve)
                val typing = LocalPopoverTyping.current
                DisposableEffect(typing) { typing?.let { it.intValue++ }; onDispose { typing?.let { it.intValue-- } } }
                val focus = LocalFocusManager.current
                Column(Modifier.testTag("graph-curve-values").pointerInput(Unit) { detectTapGestures { focus.clearFocus() } },
                    verticalArrangement = Arrangement.spacedBy(XyEdit.GAP.dp)) {
                    rows.withIndex().chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(XyEdit.GAP.dp)) {
                            pair.forEach { (i, row) ->
                                val (limit, value, _) = row
                                XyValueField(limit.name, EaseLimits.format(limit, value), Modifier.testTag("graph-curve-value-$i")) { text ->
                                    store.update { st -> st.user.firstOrNull { it.id == e.id }?.let { u -> XyEdit.commit(u.curve, i, text)?.let { c -> CurveLibrary.edit(st, e.id, c) } } ?: st }
                                }
                            }
                        }
                    }
                }
            }
            2 -> CurveNameField(e.name, e.name, "Rename curve") { name -> store.update { CurveLibrary.rename(it, e.id, name) }; ui.menuFor = null; ui.menuMode = 0 }
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                InspectorIconButton(InspectorIcons.icon("GRAPH_XY"), "Edit values", Modifier.testTag("graph-curve-xy")) { ui.menuMode = 1 }
                InspectorIconButton(InspectorIcons.icon("GRAPH_RENAME"), "Rename", Modifier.testTag("graph-curve-rename")) { ui.menuMode = 2 }
                InspectorIconButton(InspectorIcons.icon("DELETE"), "Delete curve", Modifier.testTag("graph-curve-delete")) {
                    ui.menuFor = null; store.update { CurveLibrary.delete(it, e.id) } }
            }
        }
    }
}

/** Reads at most [limit] bytes (API 29 has no InputStream.readNBytes). */
private fun readCapped(input: java.io.InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); var total = 0
    while (total < limit) { val n = input.read(buf, 0, minOf(buf.size, limit - total)); if (n < 0) break; out.write(buf, 0, n); total += n }
    return out.toByteArray()
}

/** GRAPH G2.1 §7: the xy popover's per-field commit rule and tile size (pure, JVM tested). */
internal object XyEdit {
    const val TILE_W = 48f
    const val TILE_H = 44f
    const val GAP = 4f
    /** One field: empty keeps the value (null), invalid text is discarded (null), otherwise the clamped curve (null when unchanged). */
    fun commit(curve: EasingCurve, index: Int, text: String): EasingCurve? {
        if (text.isBlank()) return null
        return EaseLimits.typed(curve, index, text)?.takeIf { !EaseMath.same(it, curve) }
    }
}

/**
 * Knows when the keyboard THIS field raised has gone (Back hid it): armed by a rising IME or a settled one, never by a
 * falling one (the A4 lesson: a previous field's keyboard still sliding down is not Back).
 */
internal class ImeArm {
    private var armed = false
    private var last = -1
    fun reset() { armed = false; last = -1 }
    /** Returns true when the IME fell to 0 after this field's keyboard was up. */
    fun ime(bottom: Int): Boolean {
        val gone = bottom <= 0 && armed
        if (bottom <= 0) armed = false else if (last >= 0 && bottom > last) armed = true
        last = bottom
        return gone
    }
    fun settled(bottom: Int) { if (bottom > 0) armed = true }
}

/** One xy tile: corner label, centred value; tap to type. Commits once per focus session (Done / focus loss / dispose). */
@Composable private fun XyValueField(label: String, display: String, modifier: Modifier, onCommit: (String) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(display)) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val commit by rememberUpdatedState(onCommit)
    LaunchedEffect(display, focused) { if (!focused) field = TextFieldValue(display) }
    val latestText by rememberUpdatedState(field.text)
    val latestFocused by rememberUpdatedState(focused)
    DisposableEffect(Unit) { onDispose { if (latestFocused) commit(latestText) } }
    val hostIme = LocalHostImeBottom.current
    val ime = if (hostIme >= 0) hostIme else WindowInsets.ime.getBottom(LocalDensity.current)
    val arm = remember { ImeArm() }
    LaunchedEffect(ime, focused) {
        if (!focused) return@LaunchedEffect
        if (arm.ime(ime)) { focus.clearFocus(); return@LaunchedEffect }
        if (ime > 0) { kotlinx.coroutines.delay(150); arm.settled(ime) }
    }
    val shape = RoundedCornerShape(6.dp)
    Box(modifier.size(XyEdit.TILE_W.dp, XyEdit.TILE_H.dp).clip(shape).background(if (focused) Surface else SurfaceHigh)
        .border(if (focused) 1.dp else .5.dp, if (focused) TextPrimary else Border, shape)) {
        Text(label, Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 2.dp), color = TextSecondary, fontSize = 8.sp, lineHeight = 9.sp, maxLines = 1)
        BasicTextField(field, { field = it }, singleLine = true,
            textStyle = MaterialTheme.typography.labelMedium.copy(color = TextPrimary, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(TextPrimary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.DecimalSigned, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.align(Alignment.Center).padding(top = 8.dp, start = 2.dp, end = 2.dp).fillMaxWidth()
                .onFocusChanged {
                    if (it.isFocused && !focused) { arm.reset(); field = TextFieldValue(field.text, TextRange(0, field.text.length)) }
                    if (focused && !it.isFocused) commit(field.text)
                    focused = it.isFocused
                }
                .semantics { contentDescription = "$label, exact value" })
    }
}
