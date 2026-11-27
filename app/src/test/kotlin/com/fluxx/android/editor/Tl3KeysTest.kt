package com.fluxx.android.editor

import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test

/** TL3 B3: multi-track key selection, its invariants, per-track operations and bulk value edits. */
class Tl3KeysTest {
    private val rate = FrameRate(30, 1)
    private fun f(n: Long) = rate.frameTimeUs(n)
    private val pos = GraphTarget(AnimPropertyType.POSITION)
    private val scale = GraphTarget(AnimPropertyType.SCALE)
    private val rot = GraphTarget(AnimPropertyType.ROTATION)
    private val custom = EasingCurve.bezier(.1f, .2f, .3f, .9f)

    private fun layer(id: Long, start: Long = 0) = CompositionLayer(id, LayerType.SOLID, timing = ClipTiming(f(start), 0, f(120)),
        keyframeAnchorUs = f(start), animTransform = AnimatableTransform(
            position = AnimatableProperty2D(true, keyframes = FrozenList.of(listOf(Keyframe2D(f(10), 1f, 2f), Keyframe2D(f(20), 3f, 4f).withCurve(custom), Keyframe2D(f(30), 5f, 6f)))),
            scale = AnimatableProperty2D(true, keyframes = FrozenList.of(listOf(Keyframe2D(f(10), 1f, 1f), Keyframe2D(f(20), 2f, 2f)))),
            rotation = AnimatableProperty1D(true, keyframes = FrozenList.of(listOf(Keyframe1D(f(10), 0f), Keyframe1D(f(14), 10f), Keyframe1D(f(18), 20f), Keyframe1D(f(22), 30f))))))
    private val project = ProjectDocument(Composition(durationUs = f(600), frameRate = rate, layers = FrozenList.of(listOf(layer(1), layer(2, start = 30)))))
    private fun l(id: Long) = project.composition.layers.first { it.id == id }
    private fun sel(vararg t: KeyframeSelection) = KeySelectionSet(t.toList())

    @Test fun aSelectionSpansTracksAndLayersAndTitlesItself() {
        val s = sel(KeyframeSelection(1, pos, listOf(f(10))), KeyframeSelection(2, pos, listOf(f(20), f(30))))
        assertEquals(3, s.size); assertEquals(2, s.layerCount)
        assertEquals("3 keyframes selected · 2 layers", s.title())
        assertEquals("1 keyframe selected", KeySelectionSet.of(KeyframeSelection(1, pos, listOf(f(10))))!!.title())
        assertTrue(s.contains(2, pos, f(30))); assertFalse(s.contains(2, scale, f(30)))
        val added = s.toggle(1, scale, f(10))!!
        assertEquals(4, added.size)
        assertNull(KeySelectionSet.of(KeyframeSelection(1, pos, listOf(f(10))))!!.toggle(1, pos, f(10)))
    }

    @Test fun selectionInvariantsAcrossTwirlAndInspector() {
        val s = sel(KeyframeSelection(1, pos, listOf(f(10))), KeyframeSelection(1, rot, listOf(f(14))), KeyframeSelection(2, pos, listOf(f(20))))
        // 1. Opening the inspector on layer 1 keeps its keys and drops layer 2's.
        assertEquals(setOf(1L), KeySelectionRules.constrain(s, 1, null, emptySet())!!.layerIds.toSet())
        // 2. A property page narrows to its track.
        assertEquals(listOf(KeyframeSelection(1, rot, listOf(f(14)))), KeySelectionRules.constrain(s, 1, rot, emptySet())!!.tracks)
        assertNull(KeySelectionRules.constrain(s, 1, scale, emptySet()))
        // 3. Collapsing layer 2 (no sheet) deselects its keys.
        assertEquals(setOf(1L), KeySelectionRules.constrain(s, null, null, setOf(1L))!!.layerIds.toSet())
        // 4. No sheet and no twirl-down open: key mode ends.
        assertNull(KeySelectionRules.constrain(s, null, null, emptySet()))
        // 5. Otherwise the selection survives.
        assertEquals(s, KeySelectionRules.constrain(s, null, null, setOf(1L, 2L)))
    }

    @Test fun easingStaggerAndOddEvenWorkPerTrack() {
        val s = sel(KeyframeSelection(1, pos, listOf(f(10), f(20))), KeyframeSelection(1, rot, listOf(f(10), f(14), f(22))))
        val eased = MultiKeyEdits.ease(project, s, EasingCurve.LINEAR) as EditorAction.Batch
        assertEquals(2, eased.actions.size)
        assertTrue(eased.actions.all { it is EditorAction.SetKeyEasing })
        // Stagger 2 frames per track: each track anchored on its own first selected key.
        val packed = MultiKeyEdits.pack(project, s, 2)!!
        assertEquals(listOf(f(10), f(12)), packed.selection!!.track(1, pos)!!.times)
        assertEquals(listOf(f(10), f(12), f(14)), packed.selection!!.track(1, rot)!!.times)
        // Odd / Even within each track in time order.
        val odd = MultiKeyEdits.odd(project, listOf(1L to pos, 1L to rot))!!
        assertEquals(listOf(f(10), f(30)), odd.track(1, pos)!!.times)
        assertEquals(listOf(f(10), f(18)), odd.track(1, rot)!!.times)
        assertEquals(listOf(f(14), f(22)), MultiKeyEdits.even(project, listOf(1L to rot))!!.track(1, rot)!!.times)
        assertEquals(9, MultiKeyEdits.selectAll(project, listOf(1L to pos, 1L to rot, 2L to scale))!!.size)
    }

    @Test fun aGroupMoveShiftsEveryTrackByWholeFramesAndClampsAtLocalZero() {
        val s = sel(KeyframeSelection(1, pos, listOf(f(10))), KeyframeSelection(2, rot, listOf(f(40))))
        val moved = MultiKeyEdits.moveTogether(project, s, f(5))!!
        assertEquals(listOf(f(15)), moved.selection!!.track(1, pos)!!.times)
        assertEquals(listOf(f(45)), moved.selection!!.track(2, rot)!!.times)
        // Far left: layer 1's earliest key stops at local 0; layer 2 moves the same frames.
        val left = MultiKeyEdits.moveTogether(project, s, -f(50))!!
        assertEquals(listOf(0L), left.selection!!.track(1, pos)!!.times)
        assertEquals(listOf(f(30)), left.selection!!.track(2, rot)!!.times)
        // A lone key stays inside its clip.
        val one = KeySelectionSet.of(KeyframeSelection(1, pos, listOf(f(30))))!!
        val clipped = MultiKeyEdits.moveTogether(project, one, f(500), 0L..f(120))!!
        assertEquals(listOf(f(120)), clipped.selection!!.track(1, pos)!!.times)
    }

    @Test fun bulkValueEditsChangeTheSamePropertyOnlyPerAxisAndKeepEasing() {
        val s = sel(KeyframeSelection(1, pos, listOf(f(10), f(20))), KeyframeSelection(2, pos, listOf(f(20))), KeyframeSelection(1, scale, listOf(f(10))))
        val scope = MultiKeyEdits.valueScope(s, 1, pos, f(20))
        assertEquals(setOf(1L to pos, 2L to pos), scope.map { it.layerId to it.target }.toSet())
        // Not in the selection: that key alone.
        assertEquals(listOf(KeyframeSelection(1, pos, listOf(f(30)))), MultiKeyEdits.valueScope(s, 1, pos, f(30)))
        var doc = project
        val action = MultiKeyEdits.setValue(doc, scope, axis = 0, value = 9f)!!
        doc = EditorReducer.reduce(EditorState(doc), action).project
        fun p(id: Long) = doc.composition.layers.first { it.id == id }.animTransform.position.keyframes
        assertEquals(listOf(9f, 9f, 5f), p(1).map { it.x }); assertEquals(listOf(2f, 4f, 6f), p(1).map { it.y }) // Y untouched
        assertEquals(9f, p(2).first { it.timeUs == f(20) }.x)
        // Scale is never changed by a Position edit; the custom curve survives the value edit.
        assertEquals(l(1).animTransform.scale, doc.composition.layers.first { it.id == 1L }.animTransform.scale)
        assertEquals(custom, p(1).first { it.timeUs == f(20) }.curve)
        assertNull("same value: nothing to do", MultiKeyEdits.setValue(doc, scope, 0, 9f))
    }
}
