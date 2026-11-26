package com.fluxx.android.editor

import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test

/** TLP.2 part 4: drag snapping maths; part 5: the drag readouts. */
class DragSnapTest {
    private val rate = FrameRate(30, 1)
    private fun f(n: Long) = rate.frameTimeUs(n)
    private val zone = f(3) // 3 frames
    private fun t(n: Long, kind: SnapKind) = SnapTarget(f(n), kind)

    @Test fun singleCandidateSnapsInsideTheZoneOnly() {
        val targets = listOf(t(100, SnapKind.PLAYHEAD))
        // Start at frame 90, dragged +8 frames (98): 2 frames from the playhead, inside the zone → delta becomes +10.
        assertEquals(SnapResult(f(100) - f(90), targets[0]), DragSnap.snap(f(98) - f(90), listOf(f(90)), targets, zone))
        // Zone edge: exactly 3 frames away still snaps; 3 frames + 1µs does not.
        assertEquals(targets[0], DragSnap.snap(f(97) - f(90), listOf(f(90)), targets, zone).target)
        val outside = DragSnap.snap(f(97) - f(90) - 1, listOf(f(90)), targets, zone)
        assertNull(outside.target); assertEquals(f(97) - f(90) - 1, outside.deltaUs)
        // No targets in range: the raw delta.
        assertEquals(SnapResult(f(5), null), DragSnap.snap(f(5), listOf(f(90)), emptyList(), zone))
    }

    @Test fun aLayersEndCanSnapAndTheWholeSelectionShiftsKeepingOffsets() {
        // Layer 30..90 (start and end candidates) dragged +8: its END (98) is 2 frames from a clip edge at 100.
        val snap = DragSnap.snap(f(8), listOf(f(30), f(90)), listOf(t(100, SnapKind.CLIP_EDGE)), zone)
        assertEquals(f(10), snap.deltaUs)
        // A group: candidates of three layers; the closest pair wins and every member moves by the same delta.
        val group = listOf(f(0), f(60), f(45), f(75), f(120), f(150))
        val g = DragSnap.snap(f(31), group, listOf(t(80, SnapKind.MARKER), t(152, SnapKind.TICK)), zone)
        // 120 + 31 = 151 is 1 frame from 152; 45 + 31 = 76 is 4 frames from 80 (outside). → +32.
        assertEquals(f(32), g.deltaUs); assertEquals(SnapKind.TICK, g.target!!.kind)
        val moved = group.map { it + g.deltaUs }
        assertEquals(group.zipWithNext { a, b -> b - a }, moved.zipWithNext { a, b -> b - a })
    }

    @Test fun tiesGoPlayheadThenMarkerThenClipEdgeThenTick() {
        val c = listOf(1_000_000L) // exact µs ties (frame times at 30 fps are not equidistant to the µs)
        // Two targets at exactly the same distance (60ms either side): the higher priority wins.
        fun winner(a: SnapKind, b: SnapKind) = DragSnap.snap(0, c, listOf(SnapTarget(940_000, a), SnapTarget(1_060_000, b)), zone).target!!.kind
        assertEquals(SnapKind.PLAYHEAD, winner(SnapKind.MARKER, SnapKind.PLAYHEAD))
        assertEquals(SnapKind.MARKER, winner(SnapKind.MARKER, SnapKind.CLIP_EDGE))
        assertEquals(SnapKind.CLIP_EDGE, winner(SnapKind.TICK, SnapKind.CLIP_EDGE))
        assertEquals(SnapKind.MARKER, winner(SnapKind.TICK, SnapKind.MARKER))
        // A nearer target beats a higher-priority one further away.
        assertEquals(SnapKind.TICK, DragSnap.snap(0, c, listOf(SnapTarget(1_060_000, SnapKind.PLAYHEAD), SnapTarget(1_030_000, SnapKind.TICK)), zone).target!!.kind)
    }

    @Test fun theClampStillWinsAfterASnap() {
        // Layers at frames 10 and 40 (group): a snap that would push the earliest before 0 is clamped by moveTogether.
        val layers = listOf(10L, 40L).mapIndexed { i, s -> CompositionLayer(i + 1L, LayerType.SOLID, timing = ClipTiming(f(s), 0, f(20))) }
        val project = ProjectDocument(Composition(durationUs = f(600), frameRate = rate, layers = FrozenList.of(layers)))
        val snap = DragSnap.snap(-f(48), listOf(f(10), f(40)), listOf(SnapTarget(-f(5), SnapKind.MARKER)), zone)
        val batch = MultiLayerEdits.moveTogether(project, LayerSelection(listOf(1, 2)), snap.deltaUs) as EditorAction.Batch
        assertEquals(listOf(EditorAction.Move(1, 0), EditorAction.Move(2, f(30))), batch.actions)
    }

    @Test fun targetsAreThePlayheadMarkersOtherClipEdgesAndTheCompositionBounds() {
        val a = CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(f(10), 0, f(20)), markers = FrozenList.of(listOf(Marker(9, f(3)))))
        val b = CompositionLayer(2, LayerType.SOLID, timing = ClipTiming(f(60), 0, f(30)), markers = FrozenList.of(listOf(Marker(8, f(5)))))
        val project = ProjectDocument(Composition(durationUs = f(300), frameRate = rate, layers = FrozenList.of(listOf(a, b)),
            markers = FrozenList.of(listOf(Marker(7, f(120))))))
        val layerDrag = DragSnap.targets(project, f(45), setOf(1L), clipEdges = true).toSet()
        assertEquals(setOf(t(45, SnapKind.PLAYHEAD), t(120, SnapKind.MARKER), t(65, SnapKind.MARKER), t(60, SnapKind.CLIP_EDGE),
            t(90, SnapKind.CLIP_EDGE), t(0, SnapKind.BOUND), t(300, SnapKind.BOUND)), layerDrag)
        // A key drag: no clip edges.
        assertTrue(DragSnap.targets(project, f(45), setOf(1L), clipEdges = false).none { it.kind == SnapKind.CLIP_EDGE })
    }

    @Test fun rulerTicksAreMajorsAndMidsOnlyAtTheCurrentZoom() {
        // 30 fps, 1 s majors with mids at 15 frames (default zoom).
        val level = RulerLevel(1, 30, mids = true, minorEvery = 3)
        val near = DragSnap.ticksNear(f(40), rate, level, startFrame = 0).map { rate.nearestFrame(it.timeUs) }.toSet()
        assertEquals(setOf(0L, 15L, 30L, 45L, 60L, 75L), near)
        assertTrue("never a minor", near.none { it % 15 != 0L })
        // A start timecode shifts the grid: start frame 10 → majors at composition frames 20, 50 …
        val shifted = DragSnap.ticksNear(f(40), rate, level, startFrame = 10).map { rate.nearestFrame(it.timeUs) }.toSet()
        assertTrue(20L in shifted && 50L in shifted && 35L in shifted)
    }

    // ---- TLP.3 A2: per-class zones in frames, clamped in dp -----------------------------------------------------------
    private val default = 120.0 / 1_000_000 // 120dp per second: 4dp per frame at 30 fps

    @Test fun zonesAreFramesOfTheZoomClampedInDp() {
        assertEquals(8f, SnapZones.dp(SnapKind.PLAYHEAD, default, rate), 1e-4f)   // 2 frames = 8dp (= the cap)
        assertEquals(8f, SnapZones.dp(SnapKind.MARKER, default, rate), 1e-4f)
        assertEquals(8f, SnapZones.dp(SnapKind.NEIGHBOUR, default, rate), 1e-4f)
        assertEquals(4f, SnapZones.dp(SnapKind.TICK, default, rate), 1e-4f)       // 1 frame
        assertEquals(4f, SnapZones.dp(SnapKind.MID_TICK, default, rate), 1e-4f)
        // Zoomed in (20dp per frame): the caps.
        val zoomed = 600.0 / 1_000_000
        assertEquals(8f, SnapZones.dp(SnapKind.CLIP_EDGE, zoomed, rate), 1e-4f)
        assertEquals(6f, SnapZones.dp(SnapKind.TICK, zoomed, rate), 1e-4f)
        assertEquals(5f, SnapZones.dp(SnapKind.MID_TICK, zoomed, rate), 1e-4f)
        // Zoomed far out (1dp per second): the 3dp floor holds for every class.
        val far = 1.0 / 1_000_000
        for (k in SnapKind.entries) assertEquals("$k", 3f, SnapZones.dp(k, far, rate), 1e-4f)
        val farZone = SnapZones.of(far, rate)
        assertTrue(DragSnap.snap(f(85), listOf(0), listOf(t(30 * 3, SnapKind.TICK)), farZone).target != null) // 5 frames off, inside 3dp = 3 s
    }

    @Test fun aMajorTickCapturesWithinOneFrameOnlyAndAMarkerWithinTwo() {
        val zones = SnapZones.of(default, rate)
        val tick = listOf(t(30, SnapKind.TICK))
        // A drag across the major tick at frame 30 lands on 26, 27, 28 and 32, 33, 34 unsnapped; 29 and 31 snap to 30.
        for (n in listOf(26L, 27L, 28L, 32L, 33L, 34L)) assertNull("$n", DragSnap.snap(f(n), listOf(0), tick, zones).target)
        for (n in listOf(29L, 31L)) assertEquals("$n", f(30), DragSnap.snap(f(n), listOf(0), tick, zones).deltaUs)
        // At every frame pair of the second (33333 / 33334us steps) one frame is inside the 1-frame zone.
        for (n in 1L..59L) assertEquals("$n", f(n), DragSnap.snap(f(n - 1), listOf(0), listOf(t(n, SnapKind.TICK)), zones).deltaUs)
        // A marker captures within 2 frames (8dp), not 3.
        val marker = listOf(t(30, SnapKind.MARKER))
        for (n in listOf(28L, 29L, 31L, 32L)) assertEquals("$n", f(30), DragSnap.snap(f(n), listOf(0), marker, zones).deltaUs)
        for (n in listOf(27L, 33L)) assertNull("$n", DragSnap.snap(f(n), listOf(0), marker, zones).target)
        // A mid tick: 1 frame.
        assertNull(DragSnap.snap(f(13), listOf(0), listOf(t(15, SnapKind.MID_TICK)), zones).target)
        assertEquals(f(15), DragSnap.snap(f(14), listOf(0), listOf(t(15, SnapKind.MID_TICK)), zones).deltaUs)
    }

    @Test fun perClassZonesKeepPriorityAndTheNearestWins() {
        val zones = SnapZones.of(default, rate)
        // Landing on frame 30.5-ish: a tick at 31 (0.5 frame) beats a marker at 32 (1.5 frames): nearest first.
        val raw = (f(30) + f(31)) / 2
        assertEquals(SnapKind.TICK, DragSnap.snap(raw, listOf(0), listOf(t(31, SnapKind.TICK), t(32, SnapKind.MARKER)), zones).target!!.kind)
        // Landing 2 frames from a marker and 2 from a tick: the tick is outside its 1-frame zone, the marker captures.
        assertEquals(SnapKind.MARKER, DragSnap.snap(f(30), listOf(0), listOf(t(32, SnapKind.TICK), t(28, SnapKind.MARKER)), zones).target!!.kind)
        // An exact tie inside both zones: the playhead beats the major tick, the major tick beats the mid.
        val c = listOf(1_000_000L)
        fun winner(a: SnapKind, b: SnapKind) = DragSnap.snap(0, c, listOf(SnapTarget(990_000, a), SnapTarget(1_010_000, b)), zones).target!!.kind
        assertEquals(SnapKind.PLAYHEAD, winner(SnapKind.TICK, SnapKind.PLAYHEAD))
        assertEquals(SnapKind.TICK, winner(SnapKind.MID_TICK, SnapKind.TICK))
    }

    @Test fun readoutsUseThePlayheadFormatWithASignedDelta() {
        assertEquals("00:01:15", DragReadout.start(f(45), 0, rate))
        assertEquals("+00:00:06", DragReadout.delta(f(45), f(51), rate))
        assertEquals("-00:00:06", DragReadout.delta(f(51), f(45), rate))
        assertEquals("+00:00:00", DragReadout.delta(f(45), f(45), rate))
        assertEquals("+00:01:00", DragReadout.delta(0, f(30), rate))
        // Floor to the displayed frame, at the rational and other rates.
        for ((r, fps) in listOf(FrameRate(24, 1) to 24L, FrameRate(25, 1) to 25L, FrameRate(30000, 1001) to 30L)) {
            val oneSecondAndTwo = r.frameTimeUs(fps + 2)
            assertEquals("$r", "00:01:02", DragReadout.start(oneSecondAndTwo, 0, r))
            assertEquals("$r floor", "00:01:01", DragReadout.start(oneSecondAndTwo - 1, 0, r))
            assertEquals("$r", "-00:01:02", DragReadout.delta(oneSecondAndTwo, 0, r))
        }
        // A start timecode offsets the start readout like the playhead's.
        assertEquals("00:02:15", DragReadout.start(f(45), 1_000_000, rate))
    }
}
