package com.fluxx.android.model

import com.fluxx.android.editor.*
import org.junit.Assert.*
import org.junit.Test

class KeyframeAnimationTest {
    private fun editor(layer: CompositionLayer): EditorViewModel = EditorViewModel().also {
        it.dispatch(EditorAction.Load(ProjectDocument(Composition(durationUs = 10_000_000,
            layers = FrozenList.of(listOf(layer))))))
    }

    @Test fun lutMatchesIndependentDoublePrecisionBezierInverse() {
        for (i in -10..1010) {
            val x = (i / 1000.0).coerceIn(0.0, 1.0)
            var low = 0.0
            var high = 1.0
            repeat(60) {
                val t = (low + high) / 2.0
                val one = 1.0 - t
                val bx = 3.0 * one * one * t * .33 + 3.0 * one * t * t * .67 + t * t * t
                if (bx < x) low = t else high = t
            }
            val t = (low + high) / 2.0
            val expected = (3.0 * (1.0 - t) * t * t + t * t * t).toFloat()
            assertEquals(expected, KeyframeEvaluator.easeProgress(i / 1000f, EasingPreset.EASY_EASE), 1e-4f)
            assertEquals(x.toFloat(), KeyframeEvaluator.easeProgress(i / 1000f, EasingPreset.LINEAR), 1e-6f)
        }
    }

    @Test fun keyframeSnappingUsesNearestTargetAndExcludesMovingMarker() {
        fun snap(raw: Long, playhead: Long = -1, neighbors: List<Long> = emptyList()) =
            TimelineEdits.snapKeyframeTime(raw, playhead, 2_000_000, FrameRate(30), 20_000, neighbors, 500_000)
        assertEquals(0L, snap(10_000))
        assertEquals(2_000_000L, snap(1_990_000))
        assertEquals(700_000L, snap(695_000, playhead = 700_000))
        assertEquals(710_000L, snap(709_000, playhead = 700_000, neighbors = listOf(710_000)))
        assertEquals(FrameRate(30).snap(511_000), snap(511_000, neighbors = listOf(500_000)))
    }

    @Test fun disablingAnimationFreezesEvaluatedValueAndEnablingAgainIsIdempotent() {
        val vm = editor(CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(durationUs = 2_000_000)))
        vm.dispatch(EditorAction.SetKeyframe(1, AnimPropertyType.ROTATION, 0, 0f, EasingPreset.LINEAR))
        vm.dispatch(EditorAction.SetKeyframe(1, AnimPropertyType.ROTATION, 1_000_000, 720f))
        val before = vm.state.project
        vm.dispatch(EditorAction.ToggleAnimated(1, AnimPropertyType.ROTATION, true, 500_000))
        assertEquals(before, vm.state.project)
        vm.dispatch(EditorAction.ToggleAnimated(1, AnimPropertyType.ROTATION, false, 500_000))
        val layer = vm.state.project.composition.layers.single()
        assertFalse(layer.animTransform.rotation.isAnimated)
        assertEquals(360f, layer.evaluatedTransform(0).rotationDegrees, 0f)
        assertEquals(360f, ProjectCodec.decode(ProjectCodec.encode(vm.state.project))
            .composition.layers.single().evaluatedTransform(1_000_000).rotationDegrees, 0f)
    }

    @Test fun staticChannelsStayCurrentWhenAnotherChannelIsAnimated() {
        val vm = editor(CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(durationUs = 5_000_000)))
        vm.dispatch(EditorAction.SetTransform(1, Transform(positionX = .7f, scaleX = 2f, opacity = .4f)))
        vm.dispatch(EditorAction.ToggleAnimated(1, AnimPropertyType.ROTATION, true, 0))
        vm.dispatch(EditorAction.SetKeyframe(1, AnimPropertyType.ROTATION, 1_000_000, 720f))
        val out = FloatArray(6)
        vm.state.project.composition.layers.single().evaluatedTransformInto(500_000, out)
        assertArrayEquals(floatArrayOf(.7f, 0f, 2f, 1f, 360f, .4f), out, 1e-3f)
        val restored = ProjectCodec.decode(ProjectCodec.encode(vm.state.project)).composition.layers.single()
        restored.evaluatedTransformInto(500_000, out)
        assertArrayEquals(floatArrayOf(.7f, 0f, 2f, 1f, 360f, .4f), out, 1e-3f)
    }

    @Test fun animatedGesturePreviewsCancelAndCommitAsOneUndoStep() {
        val vm = editor(CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(durationUs = 5_000_000)))
        vm.dispatch(EditorAction.ToggleAnimated(1, AnimPropertyType.ROTATION, true, 0))
        vm.dispatch(EditorAction.Seek(1_000_000))
        val before = vm.state.project
        vm.beginGesture()
        vm.editTransform(1, Transform(rotationDegrees = 90f), preview = true)
        vm.editTransform(1, Transform(rotationDegrees = 765f), preview = true)
        assertEquals(before, vm.state.project)
        assertEquals(765f, vm.displayedState.selectedLayer?.evaluatedTransform(1_000_000)?.rotationDegrees
            ?: vm.displayedState.project.composition.layers.single().evaluatedTransform(1_000_000).rotationDegrees, 0f)
        vm.cancelGesture()
        assertEquals(before, vm.displayedState.project)
        vm.beginGesture()
        vm.editTransform(1, Transform(rotationDegrees = 720f), preview = true)
        vm.commitGesture()
        assertEquals(2, vm.state.project.composition.layers.single().animTransform.rotation.keyframes.size)
        vm.undo()
        assertEquals(before, vm.state.project)
        vm.redo()
        assertEquals(720f, vm.state.project.composition.layers.single().evaluatedTransform(1_000_000).rotationDegrees, 0f)
    }

    @Test fun pastePreservesLocalTimesAndShiftsOnlyClipStart() {
        val vm = editor(CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(durationUs = 2_000_000)))
        vm.dispatch(EditorAction.SetKeyframe(1, AnimPropertyType.ROTATION, 0, 0f))
        vm.dispatch(EditorAction.SetKeyframe(1, AnimPropertyType.ROTATION, 1_000_000, 720f))
        val source = vm.state.project.composition.layers.single()
        assertTrue(vm.copyLayer(1))
        vm.dispatch(EditorAction.Seek(3_000_000))
        vm.pasteLayer()
        val pasted = vm.state.selectedLayer!!
        assertNotEquals(source.id, pasted.id)
        assertEquals(3_000_000L, pasted.timing.startUs)
        assertEquals(source.animTransform, pasted.animTransform)
        assertEquals(source.evaluatedTransform(500_000), pasted.evaluatedTransform(3_500_000))
    }

    @Test fun resetAllPreservesOpacityTrackAndLastKeyRemovalPreservesValue() {
        val vm = editor(CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(durationUs = 2_000_000)))
        vm.dispatch(EditorAction.SetKeyframe(1, AnimPropertyType.OPACITY, 0, .3f))
        vm.dispatch(EditorAction.SetKeyframe(1, AnimPropertyType.ROTATION, 0, 765f))
        val before = vm.state.project
        vm.dispatch(EditorAction.Batch(listOf(AnimPropertyType.POSITION, AnimPropertyType.SCALE, AnimPropertyType.ROTATION)
            .map { EditorAction.ResetPropertyAnimation(1, it) }))
        val reset = vm.state.project.composition.layers.single()
        assertEquals(before.composition.layers.single().animTransform.opacity, reset.animTransform.opacity)
        assertEquals(0f, reset.evaluatedTransform(0).rotationDegrees, 0f)
        vm.undo()
        assertEquals(before, vm.state.project)
        vm.dispatch(EditorAction.RemoveKeyframe(1, AnimPropertyType.ROTATION, 0))
        assertEquals(765f, vm.state.project.composition.layers.single().evaluatedTransform(0).rotationDegrees, 0f)
    }

    @Test fun legacyVersionsWithoutAnimationFieldsLoadStatic() {
        for (version in 1..4) {
            val b = com.google.flatbuffers.FlatBufferBuilder(256)
            val uri = b.createString("content://video/1")
            val asset = b.createString("asset")
            fluxx.schema.Layer.startLayer(b)
            fluxx.schema.Layer.addId(b, 1UL)
            fluxx.schema.Layer.addAssetUri(b, uri)
            fluxx.schema.Layer.addAssetId(b, asset)
            fluxx.schema.Layer.addRotation(b, 765f)
            val layer = fluxx.schema.Layer.endLayer(b)
            val layers = fluxx.schema.Composition.createLayersVector(b, intArrayOf(layer))
            fluxx.schema.Composition.startComposition(b)
            fluxx.schema.Composition.addLayers(b, layers)
            val comp = fluxx.schema.Composition.endComposition(b)
            b.finish(fluxx.schema.Project.createProject(b, version, comp))
            val decoded = ProjectCodec.decode(b.sizedByteArray()).composition.layers.single()
            assertFalse(decoded.animTransform.isAnyAnimated())
            assertEquals(765f, decoded.evaluatedTransform(0).rotationDegrees, 0f)
        }
    }

    // --- 1. Evaluator Pure-Function Tests ---

    @Test
    fun evaluatorReturnsDefaultWhenNoKeyframes() {
        val emptyList = FrozenList.empty<Keyframe1D>()
        val result = KeyframeEvaluator.evaluate1D(emptyList, 42f, true, 500_000L)
        assertEquals(42f, result, 0.0001f)
    }

    @Test
    fun evaluatorReturnsSingleKeyframeValueRegardlessOfTime() {
        val singleList = FrozenList.of(listOf(Keyframe1D(1_000_000L, 100f, EasingPreset.LINEAR)))
        assertEquals(100f, KeyframeEvaluator.evaluate1D(singleList, 0f, true, 0L), 0.0001f)
        assertEquals(100f, KeyframeEvaluator.evaluate1D(singleList, 0f, true, 1_000_000L), 0.0001f)
        assertEquals(100f, KeyframeEvaluator.evaluate1D(singleList, 0f, true, 2_000_000L), 0.0001f)
    }

    @Test
    fun evaluatorClampsBeforeFirstAndAfterLastKeyframe() {
        val kfs = FrozenList.of(listOf(
            Keyframe1D(1_000_000L, 10f, EasingPreset.LINEAR),
            Keyframe1D(2_000_000L, 20f, EasingPreset.LINEAR)
        ))
        // Before first keyframe: clamped to first value
        assertEquals(10f, KeyframeEvaluator.evaluate1D(kfs, 0f, true, 0L), 0.0001f)
        assertEquals(10f, KeyframeEvaluator.evaluate1D(kfs, 0f, true, 500_000L), 0.0001f)

        // After last keyframe: clamped to last value
        assertEquals(20f, KeyframeEvaluator.evaluate1D(kfs, 0f, true, 2_500_000L), 0.0001f)
        assertEquals(20f, KeyframeEvaluator.evaluate1D(kfs, 0f, true, 10_000_000L), 0.0001f)
    }

    @Test
    fun evaluatorExactHitsReturnExactValues() {
        val kfs = FrozenList.of(listOf(
            Keyframe1D(1_000_000L, 10f, EasingPreset.EASY_EASE),
            Keyframe1D(2_000_000L, 50f, EasingPreset.EASY_EASE),
            Keyframe1D(3_000_000L, 100f, EasingPreset.EASY_EASE)
        ))
        assertEquals(10f, KeyframeEvaluator.evaluate1D(kfs, 0f, true, 1_000_000L), 0.00001f)
        assertEquals(50f, KeyframeEvaluator.evaluate1D(kfs, 0f, true, 2_000_000L), 0.00001f)
        assertEquals(100f, KeyframeEvaluator.evaluate1D(kfs, 0f, true, 3_000_000L), 0.00001f)
    }

    @Test
    fun linearInterpolationIsExactAtMidpoint() {
        val kfs = FrozenList.of(listOf(
            Keyframe1D(0L, 0f, EasingPreset.LINEAR),
            Keyframe1D(1_000_000L, 100f, EasingPreset.LINEAR)
        ))
        assertEquals(50f, KeyframeEvaluator.evaluate1D(kfs, 0f, true, 500_000L), 0.001f)
        assertEquals(25f, KeyframeEvaluator.evaluate1D(kfs, 0f, true, 250_000L), 0.001f)
        assertEquals(75f, KeyframeEvaluator.evaluate1D(kfs, 0f, true, 750_000L), 0.001f)
    }

    @Test
    fun easyEaseCubicBezierExhibitsSymmetricEaseInAndEaseOut() {
        val kfs = FrozenList.of(listOf(
            Keyframe1D(0L, 0f, EasingPreset.EASY_EASE),
            Keyframe1D(1_000_000L, 100f, EasingPreset.EASY_EASE)
        ))
        // Symmetric at t=0.5: exactly 50%
        val mid = KeyframeEvaluator.evaluate1D(kfs, 0f, true, 500_000L)
        assertEquals(50f, mid, 0.5f)

        // Ease-in: at t=0.2 (20% time), progress should be less than 20% (slower start)
        val early = KeyframeEvaluator.evaluate1D(kfs, 0f, true, 200_000L)
        assertTrue("Early progress ($early) should be less than linear 20.0", early < 20f)

        // Ease-out: at t=0.8 (80% time), progress should be greater than 80% (slower finish)
        val late = KeyframeEvaluator.evaluate1D(kfs, 0f, true, 800_000L)
        assertTrue("Late progress ($late) should be greater than linear 80.0", late > 80f)

        // Point symmetry around midpoint: (early) + (100 - late) should be very close to equal
        assertEquals(early, 100f - late, 1.0f)
    }

    @Test
    fun evaluator2DComputesBothChannelsIndependently() {
        val kfs = FrozenList.of(listOf(
            Keyframe2D(0L, 10f, 100f, EasingPreset.LINEAR),
            Keyframe2D(1_000_000L, 20f, 300f, EasingPreset.LINEAR)
        ))
        val buffer = FloatArray(2)
        KeyframeEvaluator.evaluate2D(kfs, 0f, 0f, true, 500_000L, buffer, 0)
        assertEquals(15f, buffer[0], 0.001f)
        assertEquals(200f, buffer[1], 0.001f)
    }

    // --- 2. Continuous Multi-Turn Unbounded Rotation ---

    @Test
    fun continuousMultiTurnRotationDoesNotWrapMod360() {
        val kfs = FrozenList.of(listOf(
            Keyframe1D(0L, 0f, EasingPreset.LINEAR),
            Keyframe1D(1_000_000L, 720f, EasingPreset.LINEAR)
        ))
        // At midpoint, rotation must be exactly 360°, NOT 0°
        val mid = KeyframeEvaluator.evaluate1D(kfs, 0f, true, 500_000L)
        assertEquals(360f, mid, 0.001f)

        // Negative multi-turn: 0° to -720°
        val negKfs = FrozenList.of(listOf(
            Keyframe1D(0L, 0f, EasingPreset.LINEAR),
            Keyframe1D(1_000_000L, -720f, EasingPreset.LINEAR)
        ))
        val negMid = KeyframeEvaluator.evaluate1D(negKfs, 0f, true, 500_000L)
        assertEquals(-360f, negMid, 0.001f)
    }

    // --- 3. Evaluated Transform Zero-Allocation Buffer ---

    @Test
    fun evaluatedTransformFills6SlotBufferCorrectly() {
        val anim = AnimatableTransform(
            position = AnimatableProperty2D(
                isAnimated = true,
                keyframes = FrozenList.of(listOf(Keyframe2D(0L, 100f, 200f), Keyframe2D(1_000_000L, 300f, 400f)))
            ),
            scale = AnimatableProperty2D(
                isAnimated = true,
                keyframes = FrozenList.of(listOf(Keyframe2D(0L, 1f, 1f), Keyframe2D(1_000_000L, 2f, 3f)))
            ),
            rotation = AnimatableProperty1D(
                isAnimated = true,
                keyframes = FrozenList.of(listOf(Keyframe1D(0L, 0f), Keyframe1D(1_000_000L, 180f)))
            ),
            opacity = AnimatableProperty1D(
                isAnimated = true,
                keyframes = FrozenList.of(listOf(Keyframe1D(0L, 1f), Keyframe1D(1_000_000L, 0.5f)))
            )
        )
        val layer = CompositionLayer(type = LayerType.SOLID,
            id = 1L,
            timing = ClipTiming(startUs = 1_000_000L, durationUs = 5_000_000L),
            animTransform = anim
        )

        val out = FloatArray(6)
        // Composition playhead at 1.5s = local playhead at 0.5s
        layer.evaluatedTransformInto(1_500_000L, out)

        assertEquals(200f, out[0], 2f) // posX ~200
        assertEquals(300f, out[1], 2f) // posY ~300
        assertEquals(1.5f, out[2], 0.05f) // scaleX ~1.5
        assertEquals(2.0f, out[3], 0.05f) // scaleY ~2.0
        assertEquals(90f, out[4], 1f) // rot ~90°
        assertEquals(0.75f, out[5], 0.05f) // opacity ~0.75
    }

    // --- 4. FlatBuffers Persistence & Round-Trip ---

    @Test
    fun keyframeAnimationRoundTripsThroughFlatBuffers() {
        val anim = AnimatableTransform(
            position = AnimatableProperty2D(
                isAnimated = true,
                keyframes = FrozenList.of(listOf(
                    Keyframe2D(0L, -50f, 120f, EasingPreset.LINEAR),
                    Keyframe2D(2_000_000L, 300f, -40f, EasingPreset.EASY_EASE)
                ))
            ),
            scale = AnimatableProperty2D(
                isAnimated = true,
                keyframes = FrozenList.of(listOf(
                    Keyframe2D(0L, 0.5f, 0.5f, EasingPreset.EASY_EASE),
                    Keyframe2D(1_500_000L, 2.5f, 2.5f, EasingPreset.LINEAR)
                ))
            ),
            rotation = AnimatableProperty1D(
                isAnimated = true,
                keyframes = FrozenList.of(listOf(
                    Keyframe1D(0L, 0f, EasingPreset.LINEAR),
                    Keyframe1D(3_000_000L, 720f, EasingPreset.EASY_EASE)
                ))
            ),
            opacity = AnimatableProperty1D(
                isAnimated = true,
                keyframes = FrozenList.of(listOf(
                    Keyframe1D(0L, 1f, EasingPreset.LINEAR),
                    Keyframe1D(1_000_000L, 0.2f, EasingPreset.EASY_EASE)
                ))
            )
        )
        val layer = CompositionLayer(type = LayerType.SOLID,
            id = 101L,
            timing = ClipTiming(0L, 0L, 5_000_000L),
            animTransform = anim
        )
        val doc = ProjectDocument(Composition(durationUs = 5_000_000L, layers = FrozenList.of(listOf(layer))))

        val bytes = ProjectCodec.encode(doc)
        val decoded = ProjectCodec.decode(bytes)

        val decodedLayer = decoded.composition.layers.single()
        val decodedAnim = decodedLayer.animTransform

        assertTrue(decodedAnim.position.isAnimated)
        assertEquals(2, decodedAnim.position.keyframes.size)
        assertEquals(-50f, decodedAnim.position.keyframes[0].x, 0.001f)
        assertEquals(EasingPreset.LINEAR, decodedAnim.position.keyframes[0].easing)
        assertEquals(300f, decodedAnim.position.keyframes[1].x, 0.001f)
        assertEquals(EasingPreset.EASY_EASE, decodedAnim.position.keyframes[1].easing)

        assertTrue(decodedAnim.rotation.isAnimated)
        assertEquals(720f, decodedAnim.rotation.keyframes[1].value, 0.001f)

        assertTrue(decodedAnim.opacity.isAnimated)
        assertEquals(0.2f, decodedAnim.opacity.keyframes[1].value, 0.001f)
    }

    @Test
    fun unanimatedLayerDecodesWithIsAnimatedFalseAndMatchesStaticTransform() {
        val staticTransform = Transform(positionX = 15f, positionY = -25f, scaleX = 1.2f, scaleY = 0.8f, rotationDegrees = 45f, opacity = 0.9f)
        val layer = CompositionLayer(type = LayerType.SOLID,
            id = 1L,
            transform = staticTransform,
            timing = ClipTiming(0L, 0L, 5_000_000L)
        )
        val doc = ProjectDocument(Composition(durationUs = 5_000_000L, layers = FrozenList.of(listOf(layer))))

        val decoded = ProjectCodec.decode(ProjectCodec.encode(doc))
        val decodedLayer = decoded.composition.layers.single()
        val at = decodedLayer.animTransform

        assertFalse(at.position.isAnimated)
        assertFalse(at.scale.isAnimated)
        assertFalse(at.rotation.isAnimated)
        assertFalse(at.opacity.isAnimated)
        assertEquals(15f, at.position.staticX, 0.001f)
        assertEquals(-25f, at.position.staticY, 0.001f)
        assertEquals(1.2f, at.scale.staticX, 0.001f)
        assertEquals(45f, at.rotation.staticValue, 0.001f)
        assertEquals(0.9f, at.opacity.staticValue, 0.001f)
    }

    // --- 5. Reducer Actions ---

    @Test
    fun toggleAnimatedSeedsInitialKeyframeFromStaticTransform() {
        val layer = CompositionLayer(type = LayerType.SOLID,
            id = 1L,
            transform = Transform(rotationDegrees = 45f),
            timing = ClipTiming(startUs = 1_000_000L, durationUs = 5_000_000L)
        )
        val state = EditorState(ProjectDocument(Composition(durationUs = 10_000_000L, layers = FrozenList.of(listOf(layer)))))

        // Toggle rotation animated at playhead = 2.0s (localTime = 1.0s)
        val updated = EditorReducer.reduce(state, EditorAction.ToggleAnimated(1L, AnimPropertyType.ROTATION, true, playheadUs = 2_000_000L))
        val updatedLayer = updated.project.composition.layers.single()
        val at = updatedLayer.animTransform

        assertTrue(at.rotation.isAnimated)
        assertEquals(1, at.rotation.keyframes.size)
        assertEquals(1_000_000L, at.rotation.keyframes[0].timeUs)
        assertEquals(45f, at.rotation.keyframes[0].value, 0.001f)
    }

    @Test
    fun setKeyframeMaintainsSortedOrderAndReplacesAtSameTimestamp() {
        val layer = CompositionLayer(type = LayerType.SOLID,
            id = 1L,
            timing = ClipTiming(0L, 0L, 5_000_000L)
        )
        var state = EditorState(ProjectDocument(Composition(durationUs = 5_000_000L, layers = FrozenList.of(listOf(layer)))))

        state = EditorReducer.reduce(state, EditorAction.SetKeyframe(1L, AnimPropertyType.ROTATION, 2_000_000L, 90f))
        state = EditorReducer.reduce(state, EditorAction.SetKeyframe(1L, AnimPropertyType.ROTATION, 1_000_000L, 45f))
        state = EditorReducer.reduce(state, EditorAction.SetKeyframe(1L, AnimPropertyType.ROTATION, 3_000_000L, 180f))

        val kfs = state.project.composition.layers.single().animTransform.rotation.keyframes
        assertEquals(3, kfs.size)
        assertEquals(1_000_000L, kfs[0].timeUs)
        assertEquals(2_000_000L, kfs[1].timeUs)
        assertEquals(3_000_000L, kfs[2].timeUs)

        // Replace keyframe at 2_000_000L
        state = EditorReducer.reduce(state, EditorAction.SetKeyframe(1L, AnimPropertyType.ROTATION, 2_000_000L, 120f))
        val updatedKfs = state.project.composition.layers.single().animTransform.rotation.keyframes
        assertEquals(3, updatedKfs.size)
        assertEquals(120f, updatedKfs[1].value, 0.001f)
    }

    @Test
    fun removeKeyframeClearsAnimationFlagWhenLastKeyframeRemoved() {
        val layer = CompositionLayer(type = LayerType.SOLID,
            id = 1L,
            timing = ClipTiming(0L, 0L, 5_000_000L)
        )
        var state = EditorState(ProjectDocument(Composition(durationUs = 5_000_000L, layers = FrozenList.of(listOf(layer)))))
        state = EditorReducer.reduce(state, EditorAction.SetKeyframe(1L, AnimPropertyType.ROTATION, 1_000_000L, 45f))
        assertTrue(state.project.composition.layers.single().animTransform.rotation.isAnimated)

        state = EditorReducer.reduce(state, EditorAction.RemoveKeyframe(1L, AnimPropertyType.ROTATION, 1_000_000L))
        val rot = state.project.composition.layers.single().animTransform.rotation
        assertFalse(rot.isAnimated)
        assertTrue(rot.keyframes.isEmpty())
    }

    @Test
    fun moveKeyframeRelocatesKeyframeCorrectly() {
        val layer = CompositionLayer(type = LayerType.SOLID,
            id = 1L,
            timing = ClipTiming(0L, 0L, 5_000_000L)
        )
        var state = EditorState(ProjectDocument(Composition(durationUs = 5_000_000L, layers = FrozenList.of(listOf(layer)))))
        state = EditorReducer.reduce(state, EditorAction.SetKeyframe(1L, AnimPropertyType.ROTATION, 1_000_000L, 45f))
        state = EditorReducer.reduce(state, EditorAction.SetKeyframe2D(1L, AnimPropertyType.POSITION, 1_000_000L, 10f, 20f))

        // Move all keyframes at 1s to 2.5s (property = null)
        state = EditorReducer.reduce(state, EditorAction.MoveKeyframe(1L, null, 1_000_000L, 2_500_000L))

        val at = state.project.composition.layers.single().animTransform
        assertEquals(2_500_000L, at.rotation.keyframes.single().timeUs)
        assertEquals(2_500_000L, at.position.keyframes.single().timeUs)
    }

    // --- 6. Duplicate, Copy & Paste Preserve Keyframes ---

    @Test
    fun duplicateLayerPreservesKeyframeAnimation() {
        val anim = AnimatableTransform(
            rotation = AnimatableProperty1D(
                isAnimated = true,
                keyframes = FrozenList.of(listOf(Keyframe1D(0L, 0f), Keyframe1D(1_000_000L, 360f)))
            )
        )
        val layer = CompositionLayer(type = LayerType.SOLID, id = 1L, timing = ClipTiming(0L, 0L, 5_000_000L), animTransform = anim)
        val vm = EditorViewModel()
        vm.dispatch(EditorAction.Load(ProjectDocument(Composition(durationUs = 10_000_000L, layers = FrozenList.of(listOf(layer))))))

        vm.duplicateLayer(1L)

        val layers = vm.state.project.composition.layers
        assertEquals(2, layers.size)
        val dup = layers[1]
        assertTrue(dup.animTransform.rotation.isAnimated)
        assertEquals(2, dup.animTransform.rotation.keyframes.size)
        assertEquals(360f, dup.animTransform.rotation.keyframes[1].value, 0.001f)
    }

    // --- 7. Non-Destructive Trimming ---

    @Test
    fun trimmingClipPreservesOutOfBoundsKeyframes() {
        val anim = AnimatableTransform(
            rotation = AnimatableProperty1D(
                isAnimated = true,
                keyframes = FrozenList.of(listOf(
                    Keyframe1D(0L, 0f),
                    Keyframe1D(2_000_000L, 180f),
                    Keyframe1D(4_000_000L, 360f)
                ))
            )
        )
        val layer = CompositionLayer(type = LayerType.SOLID, id = 1L, timing = ClipTiming(0L, 0L, 5_000_000L), animTransform = anim)
        var state = EditorState(ProjectDocument(Composition(durationUs = 10_000_000L, layers = FrozenList.of(listOf(layer)))))

        // Trim end of clip to 1.5s (out of bounds for 2s and 4s keyframes)
        state = EditorReducer.reduce(state, EditorAction.Trim(1L, 0L, 1_500_000L))

        // All 3 keyframes must still exist non-destructively
        val trimmedLayer = state.project.composition.layers.single()
        assertEquals(3, trimmedLayer.animTransform.rotation.keyframes.size)

        // Re-extend clip to 5s
        state = EditorReducer.reduce(state, EditorAction.Trim(1L, 0L, 5_000_000L))
        val reExtendedLayer = state.project.composition.layers.single()
        assertEquals(3, reExtendedLayer.animTransform.rotation.keyframes.size)
        assertEquals(360f, reExtendedLayer.animTransform.rotation.keyframes[2].value, 0.001f)
    }

    // --- 8. Export Fast-Path Guard Verification ---

    @Test
    fun isAnyAnimatedReturnsTrueWhenAnyPropertyHasKeyframes() {
        val staticAnim = AnimatableTransform()
        assertFalse(staticAnim.isAnyAnimated())

        val animatedRot = staticAnim.copy(rotation = AnimatableProperty1D(isAnimated = true, keyframes = FrozenList.of(listOf(Keyframe1D(0L, 0f)))))
        assertTrue(animatedRot.isAnyAnimated())

        val animatedPos = staticAnim.copy(position = AnimatableProperty2D(isAnimated = true, keyframes = FrozenList.of(listOf(Keyframe2D(0L, 0f, 0f)))))
        assertTrue(animatedPos.isAnyAnimated())
    }
}
