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
