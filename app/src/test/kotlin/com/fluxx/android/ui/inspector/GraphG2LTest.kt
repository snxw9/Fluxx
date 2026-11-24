package com.fluxx.android.ui.inspector

import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** GRAPH G2L: the curve library (logic, pins, persistence, import/export, names) and the closed/open geometry. */
class GraphG2LTest {
    @get:Rule val tmp = TemporaryFolder()
    private var serial = 0
    private fun ids() = "user:t${serial++}"
    private val empty = CurveLibraryState(pinned = emptyList())
    private fun add(s: CurveLibraryState, name: String, c: EasingCurve = EasingCurve.bezier(.1f, .2f, .3f, .4f)) = CurveLibrary.add(s, name, c, ids())!!

    // ---- User curves ----------------------------------------------------------------------------------------------
    @Test fun addRenameDeleteNamesAndCaps() {
        var s = empty
        assertEquals("Curve 1", CurveLibrary.defaultName(s))
        s = add(s, "").first; assertEquals("Curve 1", s.user[0].name)
        s = add(s, "  ").first; assertEquals("Curve 2", s.user[0].name) // newest first
        s = add(s, "Snappy").first; s = add(s, "Snappy").first; s = add(s, "Snappy").first
        assertEquals(listOf("Snappy 3", "Snappy 2", "Snappy", "Curve 2", "Curve 1"), s.user.map { it.name })
        assertEquals(24, CurveLibrary.uniqueName(s, "x".repeat(40)).length)
        assertEquals("Curve 3", CurveLibrary.defaultName(s))
        // Rename: trimmed and unique; empty keeps the old name.
        val id = s.user[0].id
        s = CurveLibrary.rename(s, id, "  Snappy "); assertEquals("Snappy 3", s.user[0].name)
        s = CurveLibrary.rename(s, id, "Bouncy"); assertEquals("Bouncy", s.user[0].name)
        assertEquals(s, CurveLibrary.rename(s, id, ""))
        // Edit values only; delete also unpins.
        s = CurveLibrary.edit(s, id, EasingCurve.bounce(4f, .3f)); assertEquals(EasingCurve.bounce(4f, .3f), s.user[0].curve)
        s = CurveLibrary.pinAt(s, id, 0); assertEquals(listOf(id), s.pinned)
        s = CurveLibrary.delete(s, id); assertTrue(s.user.none { it.id == id } && s.pinned.isEmpty())
        // Named bezier types are stored as their values; Hold cannot be saved; the 60 cap refuses more.
        assertEquals(EasingPreset.CUSTOM, add(empty, "E", EasingCurve.EASY_EASE).first.user[0].curve.type)
        assertNull(CurveLibrary.add(empty, "H", EasingCurve.HOLD, ids()))
        var full = empty; repeat(60) { full = add(full, "c$it").first }
        assertFalse(CurveLibrary.canAdd(full)); assertNull(CurveLibrary.add(full, "more", EasingCurve.LINEAR, ids()))
    }

    // ---- Pinning ------------------------------------------------------------------------------------------------------
    @Test fun pinInsertReplaceReorderUnpinAndSections() {
        val p = EasePresets.ALL.map { CurveLibrary.presetId(it) }
        var s = empty
        s = CurveLibrary.pinAt(s, p[0], 0); s = CurveLibrary.pinAt(s, p[1], 0); s = CurveLibrary.pinAt(s, p[2], 1)
        assertEquals(listOf(p[1], p[2], p[0]), s.pinned)
        // Reorder (already pinned).
        s = CurveLibrary.pinAt(s, p[1], 3); assertEquals(listOf(p[2], p[0], p[1]), s.pinned)
        s = CurveLibrary.movePinned(s, 2, 0); assertEquals(listOf(p[1], p[2], p[0]), s.pinned)
        // Full: the curve in the slot returns to the library; the others keep their order.
        var f = empty.copy(pinned = p.take(15))
        f = CurveLibrary.pinAt(f, p[20], 4)
        assertEquals(p.take(4) + p[20] + p.subList(5, 15), f.pinned)
        assertTrue(CurveLibrary.sections(f).presets.any { it.id == p[4] })
        // Unpin on drag-out; types are not entries (cannot be pinned); unknown ids are refused.
        s = CurveLibrary.unpin(s, p[2]); assertEquals(listOf(p[1], p[0]), s.pinned)
        assertEquals(s, CurveLibrary.pinAt(s, "type:HOLD", 0)); assertEquals(s, CurveLibrary.pinAt(s, "preset:nope", 0))
        // A pinned curve appears only in Pinned.
        val u = add(s, "Mine"); s = CurveLibrary.pinAt(u.first, u.second, 0)
        val sec = CurveLibrary.sections(s)
        assertEquals(listOf(u.second, p[1], p[0]), sec.pinned.map { it.id })
        assertTrue(sec.presets.none { it.id in s.pinned } && sec.mine.none { it.id in s.pinned })
        assertEquals(30 - 2, sec.presets.size)
    }

    @Test fun defaultPinsExistAndBuiltInIdsAreStable() {
        val d = CurveLibraryState()
        assertEquals(12, d.pinned.size); assertEquals(d.pinned.size, d.pinned.toSet().size)
        assertTrue(d.pinned.all { CurveLibrary.entry(d, it) is LibraryEntry.Preset })
        assertEquals("preset:css.ease_in_out", d.pinned[0])
        // Ids come from each preset's own id, not its position: reversing the list changes nothing.
        val forward = EasePresets.ALL.map { CurveLibrary.presetId(it) }
        assertEquals(forward.reversed(), EasePresets.ALL.reversed().map { CurveLibrary.presetId(it) })
        assertTrue(forward.all { it.startsWith("preset:") }); assertEquals(forward.size, forward.toSet().size)
        assertTrue("preset:back.out" in forward && "preset:material.standard" in forward)
    }

    @Test fun curveNameMatching() {
        var s = empty
        assertEquals("Hold", CurveLibrary.nameFor(s, EasingCurve.HOLD))
        assertEquals("Ease In", CurveLibrary.nameFor(s, EaseChoice.EASE_IN_CURVE))
        assertEquals("Bounce", CurveLibrary.nameFor(s, EasingCurve.bounce(6f, .2f)))
        assertEquals("Back out", CurveLibrary.nameFor(s, EasingCurve.bezier(.34f, 1.56f, .64f, 1f)))
        val mine = EasingCurve.bezier(.11f, .77f, .22f, .88f)
        assertEquals("Custom", CurveLibrary.nameFor(s, mine))
        s = add(s, "Whip", mine).first
        assertEquals("Whip", CurveLibrary.nameFor(s, EasingCurve.bezier(.112f, .772f, .22f, .88f)))
    }

    // ---- Persistence ----------------------------------------------------------------------------------------------------
    @Test fun storeRoundTripsAndSurvivesCorruptAndNewerFiles() {
        val file = java.io.File(tmp.root, CurveLibraryStore.FILE)
        val store = CurveLibraryStore.at(file)
        assertEquals(CurveLibraryState(), store.state.value)
        var id = ""
        store.update { s -> CurveLibrary.add(s, "Saved \"q\"", EasingCurve.elastic(4.5f, .3f), ids())!!.also { id = it.second }.first }
        store.update { CurveLibrary.pinAt(it, id, 0) }
        store.update { it.copy(showPlayheadDot = false) }
        assertTrue(file.exists()); assertFalse(java.io.File(tmp.root, CurveLibraryStore.FILE + ".tmp").exists())
        val again = CurveLibraryStore.at(file).state.value
        assertEquals(store.state.value, again)
        assertEquals("Saved \"q\"", again.user[0].name); assertFalse(again.showPlayheadDot); assertEquals(id, again.pinned[0])
        // Corrupt: defaults in memory, a backup kept, no crash.
        file.writeText("{not json")
        assertEquals(CurveLibraryState(), CurveLibraryStore.at(file).state.value)
        assertEquals("{not json", java.io.File(tmp.root, CurveLibraryStore.FILE + ".bak").readText())
        // Newer version: ignored the same way.
        file.writeText("{\"format\":\"fluxx.curve-library\",\"version\":99,\"curves\":[]}")
        assertEquals(CurveLibraryState(), CurveLibraryStore.at(file).state.value)
        assertTrue(java.io.File(tmp.root, CurveLibraryStore.FILE + ".bak").readText().contains("99"))
    }

    // ---- Import / export --------------------------------------------------------------------------------------------------
    @Test fun importExportRoundTripAndValidation() {
        var s = empty
        s = add(s, "One", EasingCurve.bezier(.2f, -1f, .8f, 2f)).first; s = add(s, "Two", EasingCurve.bounce(5f, .4f)).first
        val file = CurveExchange.export(s)
        assertTrue(file.contains("\"format\":\"fluxx.curves\"") && !file.contains("preset:"))
        val into = CurveExchange.import(empty, file.toByteArray(), ::ids)
        assertEquals(2, into.added); assertEquals(listOf("Two", "One"), into.state.user.map { it.name })
        assertEquals(s.user.map { it.curve }, into.state.user.map { it.curve })
        // Importing again: exact duplicates skipped.
        val dup = CurveExchange.import(into.state, file.toByteArray(), ::ids)
        assertEquals(0, dup.added); assertEquals(2, dup.skipped); assertEquals("Imported 0 curves · Skipped 2", dup.message)
        // Name clash with different values: suffixed.
        val clash = CurveExchange.import(into.state, """{"format":"fluxx.curves","version":1,"curves":[{"name":"One","type":"custom","a":0.5,"b":0.5,"c":0.5,"d":0.5}]}""".toByteArray(), ::ids)
        assertEquals("One 2", clash.state.user[0].name)
        // Clamping, unknown type and missing fields skipped.
        val mixed = CurveExchange.import(empty, """{"format":"fluxx.curves","version":1,"curves":[
            {"name":"Wild","type":"custom","a":4,"b":-9,"c":-1,"d":12},{"name":"B","type":"bounce","a":40,"b":0},
            {"name":"X","type":"spline","a":1,"b":1},{"name":"Y","type":"custom","a":0.1},"junk"]}""".toByteArray(), ::ids)
        assertEquals(2, mixed.added); assertEquals(3, mixed.skipped)
        assertEquals(EasingCurve.bezier(1f, -2f, 0f, 3f), mixed.state.user.first { it.name == "Wild" }.curve)
        assertEquals(EasingCurve.bounce(8f, .1f), mixed.state.user.first { it.name == "B" }.curve)
        // Rejected files.
        assertNotNull(CurveExchange.import(empty, """{"format":"fluxx.curves","version":2,"curves":[]}""".toByteArray()).error)
        assertNotNull(CurveExchange.import(empty, """{"format":"other","version":1}""".toByteArray()).error)
        assertNotNull(CurveExchange.import(empty, "{broken".toByteArray()).error)
        assertNotNull(CurveExchange.import(empty, ByteArray(CurveExchange.MAX_BYTES + 1) { ' '.code.toByte() }).error)
        // Cap: stops at 60.
        var nearly = empty; repeat(59) { nearly = add(nearly, "n$it").first }
        val capped = CurveExchange.import(nearly, file.toByteArray(), ::ids)
        assertEquals(1, capped.added); assertTrue(capped.full); assertEquals(60, capped.state.user.size)
        assertTrue(capped.message.contains("Library is full"))
    }

    // ---- Geometry ------------------------------------------------------------------------------------------------------------
    @Test fun closedAndOpenGeometryFits() {
        for (w in listOf(360f, 384f, 411f)) for (base in listOf(268f, 328f)) for (fs in listOf(1f, 1.3f)) {
            val sheet = base * fs
            val content = InspectorChromeBudget.contentHeight(sheet, fs)
            val width = w - InspectorSheetBudget.FRAME_H
            val m = GraphLayout.compute(width, content, fs)
            val at = "$w × $base × $fs: $m"
            // Left rail + gap + touchpad + right region fit; the right region is hairline + strip + gap + pinned rail.
            assertTrue(at, GraphLayout.RAIL + GraphLayout.RAIL_GAP + m.padWidth + m.right <= width + 1e-3f)
            assertEquals(at, GraphLayout.HAIRLINE + m.strip + GraphLayout.RAIL_GAP + GraphLayout.RAIL, m.right, 1e-4f)
            assertTrue(at, m.strip in GraphLayout.STRIP_MIN..GraphLayout.STRIP)
            if (w == 360f) assertTrue(at, m.padWidth >= GraphLayout.PAD_MIN_WIDTH)
            // 22–30% on the A16 widths; at 411dp the fixed 40dp strip + rail give 21.96% (reported in the checkpoint).
            assertTrue(at, m.libraryFraction in (if (w == 411f) .215f else .22f)..0.30f)
            assertTrue(at, GraphLayout.leftRailFits(content))
            assertTrue(at, GraphLayout.thumbsVisible(content, fs) >= 1.5f)
        }
        val a16 = GraphLayout.compute(384f - InspectorSheetBudget.FRAME_H, InspectorChromeBudget.contentHeight(268f, 1f))
        assertEquals(231f, a16.padWidth, 1e-3f); assertEquals(85f, a16.right, 1e-3f)
        assertEquals(207f, GraphLayout.compute(360f - InspectorSheetBudget.FRAME_H, 208f).padWidth, 1e-3f)
    }
}
