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
    internal var moved = false
    internal var onMenu: (() -> Unit)? = null
    internal var startX = 0f
    fun start(id: String, x: Float, y: Float, pointer: androidx.compose.ui.input.pointer.PointerId, onMenu: (() -> Unit)?) {
        this.id = id; pointerX = x; pointerY = y; startX = x; startY = y; moved = false; this.pointer = pointer; this.onMenu = onMenu
    }
    fun move(x: Float, y: Float) { pointerX = x; pointerY = y }
    fun end() { id = null; pointer = null; onMenu = null }
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
        dragState.start(key.substringAfter('|'), (b?.left ?: 0f) + long.position.x, (b?.top ?: 0f) + long.position.y, long.id, onMenu)
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
            if (kotlin.math.hypot(x - dragState.startX, y - dragState.startY) > slop) dragState.moved = true
            change.consume()
            dragState.move(x, y)
            if (!change.pressed) {
                val curve = dragState.id; val moved = dragState.moved; val menu = dragState.onMenu
                dragState.end()
                if (curve != null) { if (moved) onDrop(curve, x, y) else menu?.invoke() }
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
    AutoScroll(drag, list)
    Box(modifier.onGloballyPositioned { drag.listBounds = it.boundsInRoot() }.dragContainer(drag) { id, _, y -> railDrop(store, pinned, drag, id, y) }) {
        LazyColumn(Modifier.width(GraphLayout.RAIL.dp).fillMaxHeight().testTag("graph-pinned-rail"), state = list,
            verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
            items(pinned, key = { "rail|${it.id}" }) { e ->
                val key = "rail|${e.id}"
                val selected = CurveLibrary.matches(e, curve)
                Box(Modifier.size(GraphLayout.RAIL.dp).clip(RoundedCornerShape(10.dp)).background(if (selected) Surface else Color.Transparent)
                    .graphicsLayer { alpha = if (drag.id == e.id) .4f else 1f }
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
        DropIndicator(drag, pinned.map { "rail|${it.id}" })
    }
}

private fun railDrop(store: CurveLibraryStore, pinned: List<LibraryEntry>, drag: CurveDragState, id: String, y: Float) {
    val keys = pinned.map { "rail|${it.id}" }
    val target = dropIndex(drag, keys, y) ?: return
    val from = pinned.indexOfFirst { it.id == id }
    if (from < 0) return
    store.update { CurveLibrary.movePinned(it, from, if (target > from) target - 1 else target) }
}

/** The gap index (0..keys.size) under [y] among the visible items [keys], or null when none is visible. */
private fun dropIndex(drag: CurveDragState, keys: List<String>, y: Float): Int? {
    val visible = keys.mapIndexedNotNull { i, k -> drag.bounds[k]?.let { i to it } }
    if (visible.isEmpty()) return null
    for ((i, r) in visible) if (y < r.center.y) return i
    return visible.last().first + 1
}


/** A 2dp line at the drop position while a curve is dragged. */
@Composable private fun BoxScope.DropIndicator(drag: CurveDragState, keys: List<String>) {
    if (drag.id == null) return
    val i = dropIndex(drag, keys, drag.pointerY) ?: return
    val r = if (i < keys.size) drag.bounds[keys[i]]?.let { it.top - 1f } else drag.bounds[keys.last()]?.let { it.bottom + 1f }
    r ?: return
    val y = with(LocalDensity.current) { (r - drag.listBounds.top).toDp() }
    Box(Modifier.align(Alignment.TopStart).offset(y = y).fillMaxWidth().height(2.dp).background(Highlight).testTag("graph-drop-indicator"))
}

/** Auto-scrolls [list] while a drag is near its top or bottom edge. */
@Composable private fun AutoScroll(drag: CurveDragState, list: ScrollableState) {
    val edge = with(LocalDensity.current) { 32.dp.toPx() }
    LaunchedEffect(drag.id != null) {
        while (drag.id != null) {
            val b = drag.listBounds
            val step = when { drag.pointerY < b.top + edge -> -12f; drag.pointerY > b.bottom - edge -> 12f; else -> 0f }
            if (step != 0f) list.scrollBy(step)
            delay(16)
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

/** The insertion gap (or, with [full], the slot) among the pinned cells for a pointer at ([x], [y]); null when none is visible. */
private fun pinnedDrop(drag: CurveDragState, keys: List<String>, x: Float, y: Float, full: Boolean): Int? {
    val visible = keys.mapIndexedNotNull { i, k -> drag.bounds[k]?.let { i to it } }
    if (visible.isEmpty()) return if (keys.isEmpty()) 0 else null
    val rects = visible.map { it.second }
    return if (full) GridDrop.slot(rects, x, y)?.let { visible[it].first }
    else GridDrop.index(rects, x, y)?.let { r -> if (r < visible.size) visible[r].first else visible.last().first + 1 }
}

/** A 2dp bar at the grid drop position while a curve is dragged over Pinned. */
@Composable private fun BoxScope.GridDropIndicator(drag: CurveDragState, keys: List<String>, inPinned: Boolean) {
    if (drag.id == null || !inPinned || keys.isEmpty()) return
    val i = pinnedDrop(drag, keys, drag.pointerX, drag.pointerY, false) ?: return
    val r = (if (i < keys.size) drag.bounds[keys[i]] else drag.bounds[keys.last()]) ?: return
    val x = if (i < keys.size) r.left - 1f else r.right + 1f
    val d = LocalDensity.current
    Box(Modifier.align(Alignment.TopStart).offset(x = with(d) { (x - drag.listBounds.left).toDp() } - 1.dp, y = with(d) { (r.top - drag.listBounds.top).toDp() })
        .size(2.dp, with(d) { r.height.toDp() }).background(Highlight).testTag("graph-drop-indicator"))
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
    AutoScroll(drag, grid)
    val pinnedKeys = sections.pinned.map { "pin|${it.id}" }
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
    fun drop(id: String, x: Float, y: Float) {
        val wasPinned = id in state.pinned
        if (inPinned(y)) {
            val full = state.pinned.size >= CurveLibrary.MAX_PINNED && !wasPinned
            val index = pinnedDrop(drag, pinnedKeys, x, y, full) ?: (if (full) return else 0)
            store.update { CurveLibrary.pinAt(it, id, if (wasPinned) { val from = it.pinned.indexOf(id); if (index > from) index - 1 else index } else index) }
        } else if (wasPinned) store.update { CurveLibrary.unpin(it, id) }
    }
    val nameHeight = 2 * GraphLayout.GRID_NAME_LINE * fontScale.coerceAtLeast(1f)
    @Composable fun Cell(e: LibraryEntry, key: String) {
        val selected = CurveLibrary.matches(e, curve)
        // A cell scrolled out of the grid drops its bounds (stale bounds would misplace drops).
        DisposableEffect(key) { onDispose { drag.bounds.remove(key) } }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (selected) Surface else Color.Transparent)
            .graphicsLayer { alpha = if (drag.id == e.id) .4f else 1f }
            .onGloballyPositioned { drag.bounds[key] = it.boundsInRoot() }
            .curveGestures(key, drag, onTap = { onApply(e.curve) }, onMenu = if (e.user) ({ ui.menuFor = e.id; ui.menuMode = 0 }) else null,
                onHaptic = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) })
            .semantics(mergeDescendants = true) { contentDescription = e.name + if (e.user) ", user curve" else ""; if (selected) stateDescription = "Selected" }
            .testTag("graph-lib-${e.id}").padding(top = 1.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(GraphLayout.GRID_THUMB_H.dp)) {
                CurveThumbnail(e.curve, selected, Modifier.fillMaxSize(), insetDp = 3f)
                if (e.user) StarBadge(Modifier.align(Alignment.TopEnd).padding(1.dp))
                if (ui.menuFor == e.id) CurveMenuPopover(state, e, store, ui)
            }
            Text(e.name, color = if (selected) TextPrimary else TextSecondary, fontSize = 7.sp, lineHeight = GraphLayout.GRID_NAME_LINE.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 1.dp, start = 1.dp, end = 1.dp).height(nameHeight.dp).testTag("graph-lib-name"))
        }
    }
    @Composable fun Caption(text: String, key: String) {
        DisposableEffect(key) { onDispose { drag.bounds.remove(key) } }
        Column(Modifier.fillMaxWidth().height(GraphLayout.gridCaption(fontScale).dp).onGloballyPositioned { drag.bounds[key] = it.boundsInRoot() }
            .testTag("graph-lib-$key"), verticalArrangement = Arrangement.Bottom) {
            Text(text, color = TextSecondary, fontSize = 7.sp, lineHeight = 8.sp, maxLines = 1, modifier = Modifier.padding(start = 2.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
        }
    }
    val span: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) = { GridItemSpan(maxLineSpan) }
    Box(modifier.onGloballyPositioned { drag.listBounds = it.boundsInRoot() }.dragContainer(drag, ::drop).testTag("graph-library-column")) {
        LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize().testTag("graph-library-grid"), state = grid,
            contentPadding = PaddingValues(top = GraphLayout.LIBRARY_HEADER.dp, bottom = GraphLayout.scrimSize().dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            item(key = "cap|pinned", span = span) { Caption("Pinned", "cap|pinned") }
            items(sections.pinned, key = { "pin|${it.id}" }) { Cell(it, "pin|${it.id}") }
            item(key = "cap|presets", span = span) { Caption("Presets", "cap|presets") }
            items(sections.presets, key = { "preset|${it.id}" }) { Cell(it, "preset|${it.id}") }
            item(key = "cap|mine", span = span) { Caption("My curves", "cap|mine") }
            items(sections.mine, key = { "mine|${it.id}" }) { Cell(it, "mine|${it.id}") }
        }
        GridDropIndicator(drag, pinnedKeys, drag.id != null && inPinned(drag.pointerY))
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
                // GRAPH G2 (A3): typed fields (the values bar's clamps), commit on IME Done or focus loss; invalid input is
                // discarded and empty keeps the old value. Edits the STORED curve only (never the graph). Each commit
                // re-keys its field so it can be edited again; the curve is read back from the store, never captured.
                val rows = EaseLimits.rows(e.curve)
                val rounds = remember { mutableStateMapOf<Int, Int>() }
                Row(Modifier.testTag("graph-curve-values"), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    rows.forEachIndexed { i, (limit, value, _) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(limit.name, color = TextSecondary, fontSize = 8.sp, lineHeight = 9.sp, maxLines = 1)
                            key(i, rounds[i] ?: 0) {
                                InspectorValueField(EaseLimits.format(limit, value), limit.name, limit.unit, onCommit = { text ->
                                    store.update { s -> s.user.firstOrNull { it.id == e.id }?.let { u -> EaseLimits.typed(u.curve, i, text)?.let { c -> CurveLibrary.edit(s, e.id, c) } } ?: s }
                                    rounds[i] = (rounds[i] ?: 0) + 1
                                }, onCancel = { rounds[i] = (rounds[i] ?: 0) + 1 },
                                    modifier = Modifier.testTag("graph-curve-value-$i"), autoFocus = i == 0)
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
