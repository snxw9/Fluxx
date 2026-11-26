package com.fluxx.android.editor

import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test

/** TLP T5: key selection (one property), odd / even, trash, easing, sequence / stagger, copy / paste. */
class TlpKeyTest {
    private val rate = FrameRate(30, 1)
    private fun f(n: Long) = rate.frameTimeUs(n)
    private val rot = GraphTarget(AnimPropertyType.ROTATION)
    private val pos = GraphTarget(AnimPropertyType.POSITION)
    /** Rotation keys at frames 0, 10, 20, 30, 40, 50 (values 0, 10 … 50); Position keys at 0, 15, 45. */
    private fun layer(start: Long = 0) = CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(start, 0, f(120)), animTransform = AnimatableTransform(
        rotation = AnimatableProperty1D(true, 0f, FrozenList.of((0..5).map { Keyframe1D(f(10L * it), 10f * it) })),
        position = AnimatableProperty2D(true, keyframes = FrozenList.of(listOf(Keyframe2D(0, 0f, 0f), Keyframe2D(f(15), .5f, .25f), Keyframe2D(f(45), 1f, -1f))))))
    private fun vm(l: CompositionLayer = layer()) = EditorViewModel().also {
        it.dispatch(EditorAction.Load(ProjectDocument(Composition(durationUs = 20_000_000, frameRate = rate, layers = FrozenList.of(listOf(l))))))
        it.dispatch(EditorAction.Select(1))
    }
    private fun EditorViewModel.layer() = state.project.composition.layers.single()
    private fun EditorViewModel.rotTimes() = layer().animTransform.rotation.keyframes.map { it.timeUs }

    @Test fun selectionIsScopedToOnePropertyOddEvenAndPrune() {
        val l = layer()
        assertEquals(KeyEdits.times(l, rot), KeyEdits.selectAll(l, rot).times)
        assertEquals(listOf(f(0), f(20), f(40)), KeyEdits.odd(l, rot).times)
        assertEquals(listOf(f(10), f(30), f(50)), KeyEdits.even(l, rot).times)
        assertEquals(listOf(0L, f(45)), KeyEdits.odd(l, pos).times)
        val v = vm(); v.selectKeys(KeyframeSelection(1, rot, listOf(f(20), f(40))))
        assertFalse("selecting keys is not an undoable edit", v.canUndo)
        v.dispatch(EditorAction.RemoveKeyframe(1, AnimPropertyType.ROTATION, f(20)))
        assertEquals(listOf(f(40)), v.keySelection!!.times)
        v.dispatch(EditorAction.RemoveKeyframe(1, AnimPropertyType.ROTATION, f(40)))
        assertNull("no selected key left: key selection mode exits", v.keySelection)
        assertNull(KeyEdits.prune(v.state.project, KeyframeSelection(9, rot, listOf(0))))
    }

    @Test fun trashAndEasingAreOneUndo() {
        val v = vm(); val before = v.state.project
        val sel = KeyEdits.even(v.layer(), rot)
        v.dispatch(KeyEdits.delete(v.layer(), sel)!!)
        assertEquals(listOf(f(0), f(20), f(40)), v.rotTimes())
        v.undo(); assertEquals(before, v.state.project)
        v.dispatch(KeyEdits.ease(v.layer(), sel, EasingCurve.HOLD)!!)
        val keys = v.layer().animTransform.rotation.keyframes
        assertEquals(listOf(EasingPreset.EASY_EASE, EasingPreset.HOLD, EasingPreset.EASY_EASE, EasingPreset.HOLD, EasingPreset.EASY_EASE, EasingPreset.HOLD), keys.map { it.easing })
        v.undo(); assertEquals(before, v.state.project)
        // Hold-only tracks (Source Text) have no easing.
        assertNull(KeyEdits.ease(v.layer(), KeyframeSelection(1, GraphTarget(AnimPropertyType.SOURCE_TEXT), listOf(0L)), EasingCurve.LINEAR))
    }

    @Test fun sequencePacksOneFrameApartAnchoredOnTheFirstSelectedKey() {
        val v = vm(); val before = v.state.project
        // Keys 10, 30, 50 → 10, 11, 12; unselected 0, 20, 40 untouched; order preserved; selection follows.
        val sel = KeyframeSelection(1, rot, listOf(f(30), f(10), f(50)))
        val moved = KeyEdits.pack(v.state.project, v.layer(), sel, 1)!!
        v.dispatch(moved.action)
        assertEquals(listOf(f(0), f(10), f(11), f(12), f(20), f(40)), v.rotTimes())
        assertEquals(listOf(30f, 50f), listOf(f(11), f(12)).map { t -> v.layer().animTransform.rotation.keyframes.first { it.timeUs == t }.value })
        assertEquals(listOf(f(11), f(10), f(12)), moved.times)
        v.undo(); assertEquals(before, v.state.project)
        // Stagger 4: 10, 14, 18.
        v.dispatch(KeyEdits.pack(v.state.project, v.layer(), sel, 4)!!.action)
        assertEquals(listOf(f(0), f(10), f(14), f(18), f(20), f(40)), v.rotTimes())
    }

    @Test fun movesAreOrderedSoSelectedKeysNeverCollideAndUnselectedKeysAreReplaced() {
        // Selected 0, 10, 20 with stagger 20 → 0, 20, 40: 10 → 20 needs 20 to move first (right movers latest first).
        val v = vm()
        v.dispatch(KeyEdits.pack(v.state.project, v.layer(), KeyframeSelection(1, rot, listOf(f(0), f(10), f(20))), 20)!!.action)
        val keys = v.layer().animTransform.rotation.keyframes
        // MoveKeyframe's rule: a moved key that lands on an UNSELECTED key replaces it (the unselected key at 40 is gone).
        assertEquals(listOf(f(0), f(20), f(30), f(40), f(50)), keys.map { it.timeUs })
        assertEquals(listOf(0f, 10f, 30f, 20f, 50f), keys.map { it.value })
        // Left movers earliest first: 30, 40, 50 → 30, 31, 32 never pass through each other.
        val w = vm()
        w.dispatch(KeyEdits.pack(w.state.project, w.layer(), KeyframeSelection(1, rot, listOf(f(30), f(40), f(50))), 1)!!.action)
        assertEquals(listOf(30f, 40f, 50f), listOf(f(30), f(31), f(32)).map { t -> w.layer().animTransform.rotation.keyframes.first { it.timeUs == t }.value })
    }

    @Test fun twoDimensionalKeysMoveBothAxes() {
        val v = vm()
        v.dispatch(KeyEdits.pack(v.state.project, v.layer(), KeyframeSelection(1, pos, listOf(0L, f(45))), 3)!!.action)
        val k = v.layer().animTransform.position.keyframes
        assertEquals(listOf(0L, f(3), f(15)), k.map { it.timeUs })
        assertEquals(1f, k[1].x, 0f); assertEquals(-1f, k[1].y, 0f)
    }

    @Test fun copyPasteKeepsRelativeTimingAtThePlayheadInLocalTimeAndOnlyOntoTheSameProperty() {
        // The layer starts at frame 100; its keys are anchor-relative.
        val v = vm(layer(start = f(100)))
        val l = v.layer()
        val clip = KeyEdits.copy(l, KeyframeSelection(1, rot, listOf(f(30), f(10))))!!
        assertEquals(listOf(0L, f(30) - f(10)), clip.keys.map { it.relativeUs }) // exact µs, not re-rounded to frames
        assertTrue(KeyEdits.canPaste(l, rot, clip)); assertFalse(KeyEdits.canPaste(l, pos, clip))
        assertFalse(KeyEdits.copyable(l, GraphTarget(AnimPropertyType.FILL_COLOUR)))
        // Playhead at frame 300 (local 200): keys at local 200 and 220, values and curves kept. Outside the clip is fine.
        v.dispatch(EditorAction.Seek(f(300)))
        val pasted = KeyEdits.paste(v.state.project, v.layer(), rot, clip, v.state.playheadUs)!!
        v.dispatch(pasted.action)
        val keys = v.layer().animTransform.rotation.keyframes
        assertEquals(10f, keys.first { it.timeUs == f(300) - l.resolvedKeyframeAnchorUs }.value, 0f)
        assertEquals(30f, keys.first { it.timeUs == f(300) - l.resolvedKeyframeAnchorUs + (f(30) - f(10)) }.value, 0f)
        assertEquals(8, keys.size)
        v.undo(); assertEquals(6, v.layer().animTransform.rotation.keyframes.size)
    }
}

/** TLP.1 part 2: key tap rules on the timeline (no rule seeks). */
class TlpKeyTapTest {
    @Test fun outsideKeyModeATapDoesNothingAndADoubleTapSelects() {
        assertEquals(KeyTapEffect.NONE, KeyTapRules.tap(keyMode = false))
        assertEquals(KeyTapEffect.SELECT, KeyTapRules.doubleTap(keyMode = false))
    }
    @Test fun inKeyModeTapsAndDoubleTapsToggle() {
        assertEquals(KeyTapEffect.TOGGLE, KeyTapRules.tap(keyMode = true))
        assertEquals(KeyTapEffect.TOGGLE, KeyTapRules.doubleTap(keyMode = true))
        // A toggle of the last selected key leaves nothing: the host exits key selection mode.
        val sel = KeyframeSelection(1, GraphTarget(AnimPropertyType.ROTATION), listOf(10L))
        assertTrue(sel.toggle(10L).times.isEmpty()); assertEquals(listOf(10L, 20L), sel.toggle(20L).times)
    }
}

/** TLP.1 part 4: the ⋮ Copy keys scope (selected keys, else every key of the property) and Paste with or without a selection. */
class TlpKeyMenuTest {
    private val rate = FrameRate(30, 1)
    private fun f(n: Long) = rate.frameTimeUs(n)
    private val rot = GraphTarget(AnimPropertyType.ROTATION)
    private val opacity = GraphTarget(AnimPropertyType.OPACITY)
    private val layer = CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(0, 0, f(120)), animTransform = AnimatableTransform(
        rotation = AnimatableProperty1D(true, 0f, FrozenList.of((0..2).map { Keyframe1D(f(10L * it), 10f * it) }))))

    @Test fun copyTakesTheSelectionElseEveryKeyOfTheProperty() {
        assertEquals(listOf(0L, f(10), f(20)), KeyEdits.copyScope(layer, rot, null)!!.times)
        val sel = KeyframeSelection(1, rot, listOf(f(20)))
        assertEquals(listOf(f(20)), KeyEdits.copyScope(layer, rot, sel)!!.times)
        // A selection on another layer or property does not narrow this property's copy.
        assertEquals(3, KeyEdits.copyScope(layer, rot, sel.copy(layerId = 9))!!.size)
        assertEquals(3, KeyEdits.copyScope(layer, rot, KeyframeSelection(1, opacity, listOf(0L)))!!.size)
        // No keys / not copyable: nothing to copy.
        assertNull(KeyEdits.copyScope(layer, opacity, null))
        assertNull(KeyEdits.copyScope(layer, GraphTarget(AnimPropertyType.FILL_COLOUR), null))
        assertEquals(listOf(0L, f(10), f(20)), KeyEdits.copy(layer, KeyEdits.copyScope(layer, rot, null)!!)!!.keys.map { it.relativeUs })
    }

    @Test fun pasteNeedsOnlyASameKindClipboardAndIsOneUndo() {
        val v = EditorViewModel()
        v.dispatch(EditorAction.Load(ProjectDocument(Composition(durationUs = 20_000_000, frameRate = rate, layers = FrozenList.of(listOf(layer))))))
        val clip = KeyEdits.copy(layer, KeyEdits.copyScope(layer, rot, null)!!)!!
        assertTrue(KeyEdits.canPaste(layer, rot, clip)); assertFalse(KeyEdits.canPaste(layer, opacity, clip)); assertFalse(KeyEdits.canPaste(layer, rot, null))
        v.dispatch(EditorAction.Seek(f(60)))
        assertNull(v.keySelection) // no selection needed
        v.dispatch(KeyEdits.paste(v.state.project, v.state.project.composition.layers.single(), rot, clip, v.state.playheadUs)!!.action)
        assertEquals(listOf(0L, f(10), f(20), f(60), f(70), f(80)), v.state.project.composition.layers.single().animTransform.rotation.keyframes.map { it.timeUs })
        v.undo(); assertEquals(3, v.state.project.composition.layers.single().animTransform.rotation.keyframes.size)
    }
}

/** TLP.1 part 5: selected keys move together (whole frames, clamp at 0, collision-free order, unselected keys replaced). */
class TlpKeyGroupMoveTest {
    private val rate = FrameRate(30, 1)
    private fun f(n: Long) = rate.frameTimeUs(n)
    private val rot = GraphTarget(AnimPropertyType.ROTATION)
    private val pos = GraphTarget(AnimPropertyType.POSITION)
    /** Rotation keys at frames 0, 10, 20, 30, 40, 50 (values 0 … 50); Position at 5, 15. */
    private val fixture = CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(0, 0, f(120)), animTransform = AnimatableTransform(
        rotation = AnimatableProperty1D(true, 0f, FrozenList.of((0..5).map { Keyframe1D(f(10L * it), 10f * it) })),
        position = AnimatableProperty2D(true, keyframes = FrozenList.of(listOf(Keyframe2D(f(5), .1f, .2f), Keyframe2D(f(15), .3f, .4f))))))
    private fun vm() = EditorViewModel().also {
        it.dispatch(EditorAction.Load(ProjectDocument(Composition(durationUs = 20_000_000, frameRate = rate, layers = FrozenList.of(listOf(fixture))))))
    }
    private fun EditorViewModel.layer() = state.project.composition.layers.single()
    private fun EditorViewModel.rot() = layer().animTransform.rotation.keyframes.map { it.timeUs to it.value }

    @Test fun wholeFramesOffsetsKeptOneUndoThroughTheGesture() {
        val v = vm(); val base = v.state.project
        val sel = KeyframeSelection(1, rot, listOf(f(30), f(10)))
        v.beginGesture()
        v.previewGesture(KeyEdits.moveTogether(base, base.composition.layers.single(), sel, f(1))!!.action)
        val moved = KeyEdits.moveTogether(base, base.composition.layers.single(), sel, f(2) + 4_000)!! // 2.12 frames → 2
        v.previewGesture(moved.action); v.commitGesture()
        assertEquals(listOf(f(32), f(12)), moved.times) // selection order kept, times follow
        assertEquals(listOf(0L to 0f, f(12) to 10f, f(20) to 20f, f(32) to 30f, f(40) to 40f, f(50) to 50f), v.rot())
        v.undo(); assertEquals(base, v.state.project)
        assertNull("under half a frame: nothing moves", KeyEdits.moveTogether(base, v.layer(), sel, 10_000))
    }

    @Test fun clampKeepsTheEarliestKeyAtZero() {
        val v = vm()
        val m = KeyEdits.moveTogether(v.state.project, v.layer(), KeyframeSelection(1, rot, listOf(f(10), f(30))), -f(100))!!
        assertEquals(listOf(0L, f(20)), m.times)
        // Left movers earliest first: 10 → 0 replaces the unselected key at 0, then 30 → 20 replaces the one at 20.
        assertEquals(listOf(EditorAction.MoveKeyframe(1, AnimPropertyType.ROTATION, f(10), 0), EditorAction.MoveKeyframe(1, AnimPropertyType.ROTATION, f(30), f(20))),
            (m.action as EditorAction.Batch).actions)
        v.dispatch(m.action)
        assertEquals(listOf(0L to 10f, f(20) to 30f, f(40) to 40f, f(50) to 50f), v.rot())
        assertNull("already at 0: a leftward drag does nothing", KeyEdits.moveTogether(v.state.project, v.layer(), KeyframeSelection(1, rot, listOf(0L, f(20))), -f(5)))
    }

    @Test fun adjacentSelectedKeysNeverCollideMidBatch() {
        // 0, 10, 20 → +10: right movers latest first (20 → 30 before 10 → 20 before 0 → 10); 30 (unselected) is replaced.
        val v = vm()
        val m = KeyEdits.moveTogether(v.state.project, v.layer(), KeyframeSelection(1, rot, listOf(0L, f(10), f(20))), f(10))!!
        assertEquals(listOf(f(20), f(10), 0L), (m.action as EditorAction.Batch).actions.map { (it as EditorAction.MoveKeyframe).oldTimeUs })
        v.dispatch(m.action)
        assertEquals(listOf(f(10) to 0f, f(20) to 10f, f(30) to 20f, f(40) to 40f, f(50) to 50f), v.rot())
    }

    @Test fun twoDimensionalKeysMoveWhole() {
        val v = vm()
        v.dispatch(KeyEdits.moveTogether(v.state.project, v.layer(), KeyframeSelection(1, pos, listOf(f(5), f(15))), f(3))!!.action)
        val k = v.layer().animTransform.position.keyframes
        assertEquals(listOf(f(8), f(18)), k.map { it.timeUs }); assertEquals(listOf(.1f, .3f), k.map { it.x }); assertEquals(listOf(.2f, .4f), k.map { it.y })
    }
}
