package com.fluxx.android.editor

import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test

/** TLV1.1 A1: a key drag that auto-scrolled seeks to the key's new time; any other end never seeks. */
class KeyDragLandingTest {
    private val rate = FrameRate(30, 1)
    private fun f(n: Long) = rate.frameTimeUs(n)
    private val pos = GraphTarget(AnimPropertyType.POSITION)
    private val rot = GraphTarget(AnimPropertyType.ROTATION)

    private fun layer(id: Long, start: Long) = CompositionLayer(id, LayerType.SOLID, timing = ClipTiming(f(start), 0, f(600)),
        keyframeAnchorUs = f(start), animTransform = AnimatableTransform(
            position = AnimatableProperty2D(true, keyframes = FrozenList.of(listOf(Keyframe2D(f(10), 1f, 2f), Keyframe2D(f(20), 3f, 4f), Keyframe2D(f(30), 5f, 6f)))),
            rotation = AnimatableProperty1D(true, keyframes = FrozenList.of(listOf(Keyframe1D(f(5), 0f), Keyframe1D(f(40), 10f))))))
    private val project = ProjectDocument(Composition(durationUs = f(900), frameRate = rate, layers = FrozenList.of(listOf(layer(1, 0), layer(2, 100)))))

    @Test fun onlyACommittedDragThatAutoScrolledSeeks() {
        assertEquals(f(250), KeyDragLanding.seekTo(committed = true, autoScrolled = true, keyCompositionUs = f(250)))
        // No auto-scroll: unchanged (eases back, no seek).
        assertNull(KeyDragLanding.seekTo(committed = true, autoScrolled = false, keyCompositionUs = f(250)))
        // A cancelled drag never seeks, scrolled or not.
        assertNull(KeyDragLanding.seekTo(committed = false, autoScrolled = true, keyCompositionUs = f(250)))
        assertNull(KeyDragLanding.seekTo(committed = false, autoScrolled = false, keyCompositionUs = null))
        // The key's time unknown: no seek.
        assertNull(KeyDragLanding.seekTo(committed = true, autoScrolled = true, keyCompositionUs = null))
    }

    @Test fun theTrackerCountsOnlyEdgeStepsThatMovedTheView() {
        val t = KeyDragLanding.Tracker()
        assertFalse(t.autoScrolled)
        // The finger in the edge zone at a scroll limit: steps consume nothing (not an auto-scroll).
        t.onEdgeStep(0f); t.onEdgeStep(0f)
        assertFalse(t.autoScrolled)
        t.onEdgeStep(-12.5f)
        assertTrue(t.autoScrolled)
        // A new drag starts clean.
        t.begin()
        assertFalse(t.autoScrolled)
    }

    @Test fun aLoneKeyLandsAtItsMovedTimeInCompositionTime() {
        val one = KeySelectionSet.of(KeyframeSelection(2, pos, listOf(f(20))))!!
        val moved = MultiKeyEdits.moveTogether(project, one, f(300), f(100)..f(700))!!
        val local = KeyDragLanding.movedTime(one, moved.selection, 2, pos, f(20))!!
        // Keys land on the COMPOSITION frame grid: local = frame time - anchor.
        assertEquals(f(420) - f(100), local)
        // Composition time = the layer's key anchor + the local time.
        val anchor = project.composition.layers.first { it.id == 2L }.resolvedKeyframeAnchorUs
        assertEquals(f(420), anchor + local)
    }

    @Test fun aGroupDragSeeksToTheDraggedKeyNotTheEarliest() {
        // Three keys on two layers and two tracks; the finger drags layer 2's position key at 30 (not the earliest key).
        val s = KeySelectionSet(listOf(KeyframeSelection(1, rot, listOf(f(5), f(40))), KeyframeSelection(2, pos, listOf(f(30), f(10)))))
        val moved = MultiKeyEdits.moveTogether(project, s, f(200))!!
        assertEquals(f(330) - f(100), KeyDragLanding.movedTime(s, moved.selection, 2, pos, f(30)))
        assertEquals(f(310) - f(100), KeyDragLanding.movedTime(s, moved.selection, 2, pos, f(10)))
        assertEquals(f(240), KeyDragLanding.movedTime(s, moved.selection, 1, rot, f(40)))
        // A key not in the drag: unknown.
        assertNull(KeyDragLanding.movedTime(s, moved.selection, 1, pos, f(10)))
    }
}
