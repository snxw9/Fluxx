package com.fluxx.android.editor

import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * TEXT F3.2 regression: dragging an animator-focused key on the timeline crashed with "Unsupported transient edit"
 * (A16 crash buffer 17:47:23 / 17:47:59, EditorViewModel.previewGesture ← TimelineClipRow keyframe drag). The drag
 * previews EditTextAnimator(Key MOVE) through the gesture protocol; it must preview, commit as one undo step, and any
 * failure must leave the document valid without throwing.
 */
class AnimatorKeyDragTest {
    private fun keyed(a: Float, b: Float) = AnimatableProperty1D(true, a, FrozenList.of(listOf(
        Keyframe1D(0, a, EasingPreset.LINEAR), Keyframe1D(1_000_000, b, EasingPreset.LINEAR))))
    private val selector = TextRangeSelector(2, offset = keyed(-100f, 100f), seedKeyframes = FrozenList.of(listOf(
        TextSeedKeyframe(0, 0), TextSeedKeyframe(1_000_000, 25))))
    private val animator = TextAnimator(1, enabled = AnimatableProperty1D(true, 1f, FrozenList.of(listOf(
        Keyframe1D(0, 1f, EasingPreset.LINEAR), Keyframe1D(1_000_000, 0f, EasingPreset.LINEAR)))),
        selectors = FrozenList.of(listOf(selector)),
        properties = FrozenList.of(listOf(TextPropertyOverride(AnimatorProperty.OPACITY, scalar = keyed(0f, 100f)),
            TextPropertyOverride(AnimatorProperty.POSITION, vector = AnimatableProperty2D(true, 0f, 0f, FrozenList.of(listOf(
                Keyframe2D(0, 0f, 100f, EasingPreset.LINEAR), Keyframe2D(1_000_000, 0f, 0f, EasingPreset.LINEAR))))))))
    private fun project() = ProjectDocument(Composition(durationUs = 2_000_000, layers = FrozenList.of(listOf(
        CompositionLayer(1, LayerType.TEXT, timing = ClipTiming(durationUs = 2_000_000), text = TextProperties(animators = FrozenList.of(listOf(animator))))))))

    private val addresses = listOf(
        TextAnimatorEdits.property(1, AnimatorProperty.OPACITY),
        TextAnimatorEdits.property(1, AnimatorProperty.POSITION),
        TextAnimatorEdits.selectorField(1, 2, SelectorField.OFFSET),
        TextAnimatorEdits.seed(1, 2),
        TextAnimatorEdits.animatorEnabled(1))

    @Test fun draggingAnimatorKeysPreviewsCommitsAndUndoesInOneStep() {
        for (address in addresses) {
            val editor = EditorViewModel(); editor.dispatch(EditorAction.Load(project()))
            val original = editor.state.project
            editor.beginGesture(); val id = editor.activeGestureId
            // The timeline sends one MOVE per drag step, always from the key's original time.
            for (to in listOf(1_050_000L, 1_200_000L, 1_400_000L, 1_500_000L))
                editor.previewGesture(EditorAction.EditTextAnimator(1, AnimatorEdit.Key(address, AnimatorKeyOperation.MOVE, 1_000_000, to)), id)
            assertNull("$address preview error", editor.gestureError)
            val layer = editor.displayedState.project.composition.layers[0]
            assertTrue("$address previews the move", TextAnimatorActions.keyTimes(layer, address).contains(1_500_000))
            editor.commitGesture(id)
            val times = TextAnimatorActions.keyTimes(editor.state.project.composition.layers[0], address)
            assertEquals("$address committed", listOf(0L, 1_500_000L), times.sorted())
            editor.undo()
            assertEquals("$address one undo step", original, editor.state.project)
            assertFalse(editor.canUndo)
        }
    }

    @Test fun aFailingMoveNeverThrowsAndKeepsTheDocument() {
        val editor = EditorViewModel(); editor.dispatch(EditorAction.Load(project()))
        val original = editor.state.project
        editor.beginGesture(); val id = editor.activeGestureId
        val good = EditorAction.EditTextAnimator(1, AnimatorEdit.Key(addresses[0], AnimatorKeyOperation.MOVE, 1_000_000, 1_200_000))
        editor.previewGesture(good, id)
        // A selector that no longer exists (deleted/undone under the drag): the reducer refuses it.
        val gone = EditorAction.EditTextAnimator(1, AnimatorEdit.Key(TextAnimatorEdits.selectorField(1, 99, SelectorField.OFFSET),
            AnimatorKeyOperation.MOVE, 1_000_000, 1_300_000))
        editor.previewGesture(gone, id)
        assertNotNull(editor.gestureError)
        // The last valid preview stays.
        assertTrue(TextAnimatorActions.keyTimes(editor.displayedState.project.composition.layers[0], addresses[0]).contains(1_200_000))
        editor.clearGestureError()
        // An action that is not a transient edit is refused without throwing.
        editor.previewGesture(EditorAction.Seek(5), id)
        assertEquals("Unsupported transient edit", editor.gestureError)
        editor.commitGesture(id)
        assertTrue(TextAnimatorActions.keyTimes(editor.state.project.composition.layers[0], addresses[0]).contains(1_200_000))
        editor.undo(); assertEquals(original, editor.state.project)
    }
}
