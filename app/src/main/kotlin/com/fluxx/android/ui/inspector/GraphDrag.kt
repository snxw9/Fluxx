package com.fluxx.android.ui.inspector

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.fluxx.android.editor.CurveLibrary
import com.fluxx.android.editor.CurveLibraryState

/**
 * GRAPH G2.1 §8: the display-only draft of the pinned order while a curve is dragged (nothing is persisted until the
 * drop). [order] is what the Pinned section shows; [gap] is the dragged curve's index in it (drawn as an empty cell, the
 * lifted tile follows the finger); [evicted] is the pinned curve that returns to the library when the set is full;
 * [unpinning] means a pinned curve is outside Pinned (the section closes its gap; dropping unpins).
 */
internal data class PinDraft(val order: List<String>, val gap: Int?, val evicted: String?, val unpinning: Boolean)

internal object PinDrafts {
    /**
     * [slot] is null when the pointer is outside Pinned. Otherwise: for a pinned curve or a non-full set, the index the
     * dragged curve takes among the OTHER pinned curves (0..others.size); for a full set and an unpinned curve, the slot
     * it replaces (0..14).
     */
    fun draft(pinned: List<String>, id: String, slot: Int?, max: Int = CurveLibrary.MAX_PINNED): PinDraft {
        val was = id in pinned
        val others = pinned - id
        if (slot == null) return if (was) PinDraft(others, null, null, true) else PinDraft(pinned, null, null, false)
        if (!was && pinned.size >= max) {
            val s = slot.coerceIn(0, pinned.lastIndex)
            return PinDraft(pinned.toMutableList().also { it[s] = id }, s, pinned[s], false)
        }
        val s = slot.coerceIn(0, others.size)
        return PinDraft(others.toMutableList().also { it.add(s, id) }, s, null, false)
    }

    /** The one store write of a drop, with the existing library operations; the result's pinned list is the draft's order. */
    fun commit(state: CurveLibraryState, id: String, slot: Int?): CurveLibraryState =
        if (slot == null) { if (id in state.pinned) CurveLibrary.unpin(state, id) else state } else CurveLibrary.pinAt(state, id, slot)

    /**
     * The slot under the pointer among the DISPLAYED cells (the draft order, in display order). Over a cell: that cell's
     * index (the dragged curve takes its place). Below / right of the last cell: the end. Above the first row: 0. In a
     * gap between rows: null (keep the current slot). [gapPresent]: the displayed cells already include the dragged
     * curve's gap; [full]: replace mode (no end slot).
     */
    fun candidate(cells: List<Rect>, x: Float, y: Float, gapPresent: Boolean, full: Boolean): Int? {
        if (cells.isEmpty()) return 0
        val endSlot = if (full) cells.lastIndex else cells.size - if (gapPresent) 1 else 0
        if (y > cells.maxOf { it.bottom }) return endSlot
        if (y < cells.minOf { it.top }) return 0
        val j = GridDrop.slot(cells, x, y) ?: return null
        if (!full && !gapPresent && j == cells.lastIndex && x > cells[j].center.x) return cells.size
        return j
    }

    /**
     * [candidate] over the cells that are laid out ([cells]: display index to bounds, ascending; cells scrolled out of
     * the list have no bounds) among [total] displayed cells. Returns a display index; the end slot only when the last
     * cell is visible; null to keep the current slot.
     */
    fun candidateIndexed(cells: List<Pair<Int, Rect>>, total: Int, x: Float, y: Float, gapPresent: Boolean, full: Boolean): Int? {
        if (cells.isEmpty()) return if (total == 0) 0 else null
        val c = candidate(cells.map { it.second }, x, y, gapPresent, full) ?: return null
        if (c < cells.size) return cells[c].first
        return if (cells.last().first == total - 1) total else null
    }

    /**
     * Hysteresis: a new slot is accepted only if it still wins with the pointer moved [h] px back towards the current
     * slot's cell, so the gap does not jitter on a boundary.
     */
    fun stable(current: Int?, pointer: Offset, currentCell: Rect?, h: Float, slotAt: (Offset) -> Int?): Int? {
        val c = slotAt(pointer) ?: return current
        if (current == null || currentCell == null || c == current) return c
        val d = currentCell.center - pointer
        val len = d.getDistance()
        val back = if (len <= h || len == 0f) currentCell.center else pointer + d * (h / len)
        return if (slotAt(back) == c) c else current
    }
}

/**
 * GRAPH G2.1 §8 auto-scroll while dragging: a [EDGE_DP] zone at the top and bottom edge; the speed ramps up with
 * proximity (quadratic) from [MIN_DP_S] at the zone's inner edge to [MAX_DP_S] at (or past) the edge. G2 scrolled a
 * fixed 12 px per 16 ms (750 px/s, ≈ 267 dp/s at the A16's 2.8125 density, 450 dpi) inside a 32dp zone; the new maximum is ≈ 3.75×.
 */
internal object DragAutoScroll {
    const val EDGE_DP = 56f
    const val MIN_DP_S = 150f
    const val MAX_DP_S = 1000f
    /** dp/s for a pointer [distanceDp] inside from an edge (≤ 0 = at or beyond it); 0 outside the zone. */
    fun speed(distanceDp: Float): Float {
        if (distanceDp >= EDGE_DP) return 0f
        val f = ((EDGE_DP - distanceDp) / EDGE_DP).coerceIn(0f, 1f)
        return MIN_DP_S + (MAX_DP_S - MIN_DP_S) * f * f
    }
    /** Signed dp/s for a pointer at [y] in a list from [top] to [bottom] (all dp): negative scrolls up. */
    fun velocity(y: Float, top: Float, bottom: Float): Float {
        val up = speed(y - top); val down = speed(bottom - y)
        return when { up > 0f && (down == 0f || y - top <= bottom - y) -> -up; down > 0f -> down; else -> 0f }
    }
}
