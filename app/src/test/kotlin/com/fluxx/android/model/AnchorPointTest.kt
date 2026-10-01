package com.fluxx.android.model

import com.fluxx.android.editor.*
import com.fluxx.android.render.LayerGeometry
import com.google.flatbuffers.FlatBufferBuilder
import org.junit.Assert.*
import org.junit.Test

class AnchorPointTest {
    private fun layer() = CompositionLayer(1, LayerType.SOLID,
        timing = ClipTiming(0, 0, 5_000_000),
        transform = Transform(.2f, -.3f, .7f, 1.4f, 37f, .4f))
    private fun project(layer: CompositionLayer) = ProjectDocument(Composition(
        durationUs = 5_000_000, layers = FrozenList.of(listOf(layer))))

    @Test fun centreAnchorPreservesLegacyTranslationBits() {
        for (yDown in listOf(true, false)) for (x in listOf(0f, -0f, .3f)) {
            val m = FloatArray(16)
            LayerGeometry.matrix(m, Transform(positionX = x, positionY = x), 1920, 1080, 1081, 1921,
                yDown = yDown, referenceWidth = 1080, referenceHeight = 1920)
            assertEquals((x * 1080 / 1920).toRawBits(), m[12].toRawBits())
            assertEquals(((if (yDown) x else -x) * 1920 / 1080).toRawBits(), m[13].toRawBits())
        }
    }

    @Test fun anchorAlwaysMapsToPositionForEitherProjection() {
        val t = layer().transform
        for (yDown in listOf(true, false)) for (rotation in listOf(0, 90, 180, 270)) {
            for (ax in listOf(0f, .5f, 1f)) for (ay in listOf(0f, .5f, 1f)) {
                val m = FloatArray(16)
                LayerGeometry.matrix(m, t, 1920, 1080, 1081, 1921, rotation, 1.2f, yDown,
                    1080, 1920, ax, ay)
                assertEquals(t.positionX * 1080 / 1920, m[0] * (2 * ax - 1) + m[4] * (2 * ay - 1) + m[12], 1e-6f)
                assertEquals((if (yDown) t.positionY else -t.positionY) * 1920 / 1080,
                    m[1] * (2 * ax - 1) + m[5] * (2 * ay - 1) + m[13], 1e-6f)
            }
        }
    }

    @Test fun compensationPreservesCornersWithSourceRotationPixelAspectOddReferencesAndResize() {
        val t = layer().transform
        for (rotation in listOf(0, 90, 180, 270, -90)) for (sar in listOf(1f, 1.2f)) {
            for (ax in listOf(0f, .5f, 1f)) for (ay in listOf(0f, .5f, 1f)) {
                val moved = LayerGeometry.compensatePosition(FloatArray(16), t, .2f, .7f, ax, ay,
                    1081, 1921, rotation, sar, 1081, 1921)
                for ((cw, ch) in listOf(1080 to 1920, 1920 to 1080, 720 to 720)) {
                    val before = FloatArray(16); val after = FloatArray(16)
                    LayerGeometry.matrix(before, t, cw, ch, 1081, 1921, rotation, sar,
                        referenceWidth = 1081, referenceHeight = 1921, anchorX = .2f, anchorY = .7f)
                    LayerGeometry.matrix(after, moved, cw, ch, 1081, 1921, rotation, sar,
                        referenceWidth = 1081, referenceHeight = 1921, anchorX = ax, anchorY = ay)
                    for (x in listOf(-1f, 1f)) for (y in listOf(-1f, 1f)) {
                        assertEquals((before[0] * x + before[4] * y + before[12]) * cw / 2,
                            (after[0] * x + after[4] * y + after[12]) * cw / 2, .002f)
                        assertEquals((before[1] * x + before[5] * y + before[13]) * ch / 2,
                            (after[1] * x + after[5] * y + after[13]) * ch / 2, .002f)
                    }
                }
            }
        }
    }

    @Test fun reducerProtectsCurvesAndChangesOnlyStaticPosition() {
        val original = layer()
        val state = EditorState(project(original), original.id)
        val changed = EditorReducer.reduce(state, EditorAction.SetAnchorPoint(1, 0f, 1f,
            Transform(positionX = .8f, positionY = .9f))).selectedLayer!!
        assertEquals(original.transform.copy(positionX = .8f, positionY = .9f), changed.transform)
        assertEquals(original.animTransform, changed.animTransform)
        val animated = EditorReducer.reduce(state, EditorAction.ToggleAnimated(1, AnimPropertyType.POSITION, true, 0))
        val edited = EditorReducer.reduce(animated, EditorAction.SetAnchorPoint(1, 0f, 1f,
            Transform(positionX = 10f))).selectedLayer!!
        assertEquals(animated.selectedLayer!!.transform, edited.transform)
        assertEquals(animated.selectedLayer!!.animTransform, edited.animTransform)
    }

    @Test fun spatialAndTemporalAnchorsRoundTripIndependentlyAndOldFilesDefaultToCentre() {
        val original = layer().copy(anchorX = .1f, anchorY = .9f, keyframeAnchorUs = -1L)
        assertEquals(project(original), ProjectCodec.decode(ProjectCodec.encode(project(original))))
        for (version in 1..5) {
            val b = FlatBufferBuilder(128)
            val uri = b.createString("content://video"); val asset = b.createString("video")
            fluxx.schema.Layer.startLayer(b)
            fluxx.schema.Layer.addId(b, 1UL)
            fluxx.schema.Layer.addAssetUri(b, uri); fluxx.schema.Layer.addAssetId(b, asset)
            val l = fluxx.schema.Layer.endLayer(b)
            val layers = fluxx.schema.Composition.createLayersVector(b, intArrayOf(l))
            fluxx.schema.Composition.startComposition(b)
            fluxx.schema.Composition.addLayers(b, layers)
            val c = fluxx.schema.Composition.endComposition(b)
            val p = fluxx.schema.Project.createProject(b, version, c)
            b.finish(p)
            val restored = ProjectCodec.decode(b.sizedByteArray()).composition.layers.single()
            assertEquals(.5f, restored.anchorX, 0f); assertEquals(.5f, restored.anchorY, 0f)
        }
    }

    @Test fun gestureIsOneUndoStepCancellationRestoresAndCopiesRetainPivot() {
        val original = layer()
        val vm = EditorViewModel()
        vm.dispatch(EditorAction.Load(project(original)))
        vm.beginGesture()
        vm.editAnchor(1, .2f, .3f, original.transform.copy(positionX = .4f), preview = true)
        vm.editAnchor(1, .8f, .9f, original.transform.copy(positionX = .7f), preview = true)
        assertEquals(original, vm.state.project.composition.layers.single())
        vm.commitGesture()
        val committed = vm.state.project
        vm.undo()
        assertEquals(project(original), vm.state.project)
        vm.redo()
        assertEquals(committed, vm.state.project)
        vm.beginGesture()
        vm.editAnchor(1, 0f, 0f, null, preview = true)
        vm.cancelGesture()
        assertEquals(committed, vm.displayedState.project)
        vm.duplicateLayer(1)
        assertTrue(vm.state.project.composition.layers.all { it.anchorX == .8f && it.anchorY == .9f })
        vm.copyLayer(1)
        vm.dispatch(EditorAction.Seek(2_000_000))
        vm.pasteLayer()
        assertTrue(vm.state.project.composition.layers.all { it.anchorX == .8f && it.anchorY == .9f })
    }

    @Test fun resetAllIsAtomicAndLeavesAnimatedOpacityUntouched() {
        val vm = EditorViewModel()
        vm.dispatch(EditorAction.Load(project(layer().copy(anchorX = 0f, anchorY = 1f))))
        vm.dispatch(EditorAction.ToggleAnimated(1, AnimPropertyType.OPACITY, true, 0))
        val before = vm.state.project
        vm.dispatch(EditorAction.Batch(listOf(AnimPropertyType.POSITION, AnimPropertyType.SCALE,
            AnimPropertyType.ROTATION).map { EditorAction.ResetPropertyAnimation(1, it) } +
            EditorAction.SetAnchorPoint(1, .5f, .5f)))
        val reset = vm.state.project.composition.layers.single()
        assertEquals(Transform(opacity = .4f), reset.transform)
        assertEquals(.5f, reset.anchorX, 0f); assertEquals(.5f, reset.anchorY, 0f)
        assertEquals(before.composition.layers.single().animTransform.opacity, reset.animTransform.opacity)
        vm.undo()
        assertEquals(before, vm.state.project)
    }
}
