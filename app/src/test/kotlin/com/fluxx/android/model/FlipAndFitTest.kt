package com.fluxx.android.model

import com.fluxx.android.editor.*
import com.fluxx.android.render.LayerGeometry
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class FlipAndFitTest {
    private fun layer() = CompositionLayer(1, LayerType.IMAGE,
        asset = AssetReference("image", "content://image"),
        timing = ClipTiming(2_000_000, 0, 5_000_000),
        transform = Transform(positionX = .2f, positionY = -.3f, scaleX = 2f, scaleY = 3f),
        anchorX = 0f, anchorY = 1f)

    private fun state(layer: CompositionLayer = layer()) = EditorState(ProjectDocument(Composition(
        width = 1080, height = 1920, durationUs = 7_000_000,
        layers = FrozenList.of(listOf(layer)))), selectedLayerId = layer.id)

    private fun edited(action: EditorAction, state: EditorState = state()) =
        EditorReducer.reduce(state, action).selectedLayer!!

    private fun assertScale(layer: CompositionLayer, x: Float, y: Float) {
        assertEquals(x, layer.transform.scaleX, .00001f)
        assertEquals(y, layer.transform.scaleY, .00001f)
        assertEquals(x, layer.animTransform.scale.staticX, .00001f)
        assertEquals(y, layer.animTransform.scale.staticY, .00001f)
    }

    @Test fun flipHorizontal_negatesStaticScaleX_whenNotAnimated() {
        val vm = EditorViewModel()
        vm.dispatch(EditorAction.Load(state().project))
        vm.dispatch(EditorAction.Select(1))
        val original = vm.state.project
        vm.dispatch(EditorAction.FlipHorizontal(1, 3_000_000))
        assertScale(vm.state.selectedLayer!!, -2f, 3f)
        vm.undo()
        assertEquals(original, vm.state.project)
        assertFalse(vm.canUndo)
        vm.redo()
        assertScale(vm.state.selectedLayer!!, -2f, 3f)
    }

    @Test fun flipVertical_negatesStaticScaleY_whenNotAnimated() {
        assertScale(edited(EditorAction.FlipVertical(1, 3_000_000)), 2f, -3f)
    }

    @Test fun flipHorizontal_createsKeyframe_whenScaleIsAnimated() {
        val original = layer().copy(keyframeAnchorUs = 4_000_000,
            animTransform = AnimatableTransform(scale = AnimatableProperty2D(isAnimated = true,
                staticX = 2f, staticY = 3f, keyframes = FrozenList.of(listOf(
                    Keyframe2D(-2_000_000, 2f, 3f), Keyframe2D(0, 4f, 5f))))))
        val before = original.evaluatedTransform(3_000_000)
        val changed = edited(EditorAction.FlipHorizontal(1, 3_000_000), state(original))
        val key = changed.animTransform.scale.keyframes.single { it.timeUs == -1_000_000L }
        assertEquals(-before.scaleX, key.x, .00001f)
        assertEquals(before.scaleY, key.y, .00001f)
        assertEquals(original.transform, changed.transform)
        assertEquals(3, changed.animTransform.scale.keyframes.size)
        val restored = edited(EditorAction.FlipHorizontal(1, 3_000_000), state(changed))
        assertEquals(3, restored.animTransform.scale.keyframes.size)
        assertEquals(before.scaleX, restored.evaluatedTransform(3_000_000).scaleX, .00001f)
    }

    private fun matrix(layer: CompositionLayer, rotation: Int = 0, sar: Float = 1f,
        sourceWidth: Int = 1920, sourceHeight: Int = 1080): FloatArray = FloatArray(16).also {
        LayerGeometry.matrix(it, layer.transform, 1080, 1920, sourceWidth, sourceHeight, rotation, sar,
            referenceWidth = layer.referenceWidth.takeIf { size -> size > 0 } ?: 1080,
            referenceHeight = layer.referenceHeight.takeIf { size -> size > 0 } ?: 1920,
            anchorX = layer.anchorX, anchorY = layer.anchorY)
    }

    private fun assertPivotUnchanged(before: CompositionLayer, after: CompositionLayer) {
        val a = matrix(before); val b = matrix(after)
        val x = 2 * before.anchorX - 1
        val y = 2 * before.anchorY - 1
        assertEquals(a[0] * x + a[4] * y + a[12], b[0] * x + b[4] * y + b[12], .00001f)
        assertEquals(a[1] * x + a[5] * y + a[13], b[1] * x + b[5] * y + b[13], .00001f)
        assertEquals(before.anchorX, after.anchorX, 0f)
        assertEquals(before.anchorY, after.anchorY, 0f)
    }

    @Test fun fitToWidth_computesExactAspectFit_andPreservesAnchor() {
        val before = layer().copy(referenceWidth = 1920, referenceHeight = 1080)
        val after = edited(EditorAction.FitToWidth(1, 3_000_000, 1920, 1080), state(before))
        assertScale(after, .5625f, .5625f)
        assertEquals(1f, abs(matrix(after)[0]), .00001f)
        assertPivotUnchanged(before, after)
        assertPivotUnchanged(before, edited(EditorAction.FlipHorizontal(1, 3_000_000), state(before)))
        assertPivotUnchanged(before, edited(EditorAction.FlipVertical(1, 3_000_000), state(before)))
    }

    @Test fun fitToHeight_computesExactAspectFit() {
        val after = edited(EditorAction.FitToHeight(1, 3_000_000, 1920, 1080))
        assertScale(after, 1920f / 607.5f, 1920f / 607.5f)
        assertEquals(1f, abs(matrix(after)[5]), .00001f)
    }

    @Test fun stretchToArea_computesIndependentScales() {
        val flipped = layer().copy(transform = layer().transform.copy(scaleX = -2f, scaleY = -3f))
        val after = edited(EditorAction.StretchToComposition(1, 3_000_000, 1920, 1080), state(flipped))
        assertScale(after, 1f, 1920f / 607.5f)
        val m = matrix(after)
        assertEquals(1f, abs(m[0]), .00001f)
        assertEquals(1f, abs(m[5]), .00001f)
        assertPivotUnchanged(flipped, after)
    }

    @Test fun quarterTurn_fitMathParity() {
        for (rotation in listOf(90, 270, -90)) {
            for (sar in listOf(1f, 1.2f)) {
                for (mode in 0..2) {
                    val action = when (mode) {
                        0 -> EditorAction.FitToWidth(1, 0, 1921, 1081, rotation, sar)
                        1 -> EditorAction.FitToHeight(1, 0, 1921, 1081, rotation, sar)
                        else -> EditorAction.StretchToComposition(1, 0, 1921, 1081, rotation, sar)
                    }
                    val after = edited(action, state(layer().copy(referenceWidth = 1081, referenceHeight = 1921)))
                    val m = matrix(after, rotation, sar, 1921, 1081)
                    if (mode != 1) assertEquals(1f, abs(m[0]) + abs(m[4]), .00001f)
                    if (mode != 0) assertEquals(1f, abs(m[1]) + abs(m[5]), .00001f)
                }
            }
        }
    }

    @Test fun allFitsReplaceFlipSignsAndAutoKeyWithoutChangingOtherChannels() {
        val original = layer().copy(transform = layer().transform.copy(scaleX = -2f, scaleY = -3f))
        val base = EditorReducer.reduce(state(original), EditorAction.ToggleAnimated(1, AnimPropertyType.SCALE, true, 2_000_000))
        for (action in listOf(EditorAction.FitToWidth(1, 3_000_000, 1920, 1080),
            EditorAction.FitToHeight(1, 3_000_000, 1920, 1080),
            EditorAction.StretchToComposition(1, 3_000_000, 1920, 1080))) {
            val after = edited(action, base)
            val key = after.animTransform.scale.keyframes.single { it.timeUs == 1_000_000L }
            assertTrue(key.x > 0f && key.y > 0f)
            assertEquals(original.transform, after.transform)
            assertEquals(original.animTransform.position, after.animTransform.position)
            assertEquals(original.animTransform.rotation, after.animTransform.rotation)
            assertEquals(original.animTransform.opacity, after.animTransform.opacity)
            val static = edited(action, state(original))
            assertTrue(static.transform.scaleX > 0f && static.transform.scaleY > 0f)
        }
    }

    @Test fun zeroReferencesUseCurrentCompositionAndInvalidDimensionsAreRejected() {
        assertEquals(LayerGeometry.computeStretch(1080, 1920, 1080, 1920, 1920, 1080),
            LayerGeometry.computeStretch(1080, 1920, 0, 0, 1920, 1080))
        assertThrows(IllegalArgumentException::class.java) {
            LayerGeometry.computeFitToWidth(1080, 1920, 0, 0, 0, 1080)
        }
    }

    @Test fun replacingAnimatedKeyPreservesItsEasing() {
        val original = layer().copy(animTransform = AnimatableTransform(scale = AnimatableProperty2D(
            isAnimated = true, keyframes = FrozenList.of(listOf(Keyframe2D(0, 2f, 3f, EasingPreset.LINEAR))))))
        for (action in listOf(EditorAction.FlipVertical(1, 2_000_000), EditorAction.FitToWidth(1, 2_000_000, 1920, 1080))) {
            val after = edited(action, state(original))
            assertEquals(EasingPreset.LINEAR, after.animTransform.scale.keyframes.single().easing)
            assertEquals(original.transform, after.transform)
        }
    }
}
