package com.fluxx.android.model

import com.fluxx.android.editor.*
import com.fluxx.android.render.LayerGeometry
import org.junit.Assert.*
import org.junit.Test

class TextPropertiesTest {
    @Test fun sourceHoldsAndSignedRebasePreservesSampling() {
        val text = TextProperties(source = AnimatableString(true, "unused", FrozenList.of(listOf(
            StringKeyframe(-100, "first"), StringKeyframe(100, "second")))))
        assertEquals("first", text.source.evaluate(-1000))
        assertEquals("first", text.source.evaluate(99))
        assertEquals("second", text.source.evaluate(100))
        for (time in listOf(-1000L, 0, 100, 1000)) assertEquals(text.source.evaluate(time), text.rebase(500).source.evaluate(time - 500))
    }
    @Test fun colourIsLinearLightPremultipliedWithExactEndpoints() {
        val black = 0xff000000.toInt(); val white = -1
        assertEquals(black, interpolateColour(black, white, 0f))
        assertEquals(white, interpolateColour(black, white, 1f))
        assertEquals(0xffbcbcbc.toInt(), interpolateColour(black, white, .5f))
        assertEquals(0x80ff0000.toInt(), interpolateColour(0x000000ff, 0xffff0000.toInt(), .5f))
    }
    @Test fun codecV9RoundTripsTypedTracksAndRequestedFallbackFont() {
        val layer = CompositionLayer(id = 1, type = LayerType.TEXT, timing = ClipTiming(durationUs = 1_000_000),
            text = TextProperties(source = AnimatableString(true, "static", FrozenList.of(listOf(StringKeyframe(0, "office\nAV"), StringKeyframe(500_000, "next")))),
                size = AnimatableProperty1D(true, 72f, FrozenList.of(listOf(Keyframe1D(0, 12f), Keyframe1D(500_000, 120f)))),
                fill = AnimatableColour(true, -1, FrozenList.of(listOf(ColourKeyframe(0, 0x80ff0000.toInt())))), fontId = "missing.future.font"))
        val project = ProjectDocument(Composition(layers = FrozenList.of(listOf(layer))))
        assertEquals(project, ProjectCodec.decode(ProjectCodec.encode(project)))
    }
    @Test fun invalidTextAndUnorderedKeysAreRejected() {
        for (source in listOf("\u0000", "\r", "\ud800", "x".repeat(4097))) {
            assertThrows(IllegalArgumentException::class.java) { TextLimits.validateSource(source) }
        }
        assertThrows(IllegalArgumentException::class.java) { AnimatableString(true, "", FrozenList.of(listOf(StringKeyframe(0, "a"), StringKeyframe(0, "b")))) }
        assertThrows(IllegalArgumentException::class.java) { TextLimits.decodeSize(Float.NaN) }
        assertEquals(8f, TextLimits.decodeSize(-1f), 0f)
    }
    @Test fun sharedMetricsKeepBaselineAndTextGeometryAtNaturalSize() {
        val metrics = TextLayoutMetrics.fromNative(doubleArrayOf(1000.0, 800.0, -200.0, 100.0, 2.0, 0.0, 1200.0, 500.0), false)
        val fake = FakeTextMetricsProvider(mapOf(("font" to "text") to metrics))
        val bounds = fake.measure("font", "text").logical(100f)
        assertEquals(TextBounds(0f, -80f, 120f, 210f), bounds)
        val matrix = FloatArray(16)
        LayerGeometry.textMatrix(matrix, Transform(), bounds, 640, 360)
        assertEquals(2f / 640, matrix[0], .00001f)
        assertEquals(2f / 360, matrix[5], .00001f)
        assertTrue(TextLayoutMetrics.fromNative(doubleArrayOf(1000.0, 800.0, -200.0, 0.0, 1.0, 0.0, 0.0), true).logical(100f).empty)
    }
    @Test fun staticPositionCompensationPreservesMeshOriginWithRotationAndFlip() {
        val old = TextBounds(0f, -80f, 120f, 100f); val next = TextBounds(0f, -120f, 300f, 150f)
        val transform = Transform(scaleX = -2f, scaleY = .5f, rotationDegrees = 35f)
        val compensated = LayerGeometry.compensateTextPosition(transform, old, next, .3f, .8f, 640, 360)
        val before = FloatArray(16); val after = FloatArray(16)
        LayerGeometry.textMatrix(before, transform, old, 640, 360, anchorX = .3f, anchorY = .8f)
        LayerGeometry.textMatrix(after, compensated, next, 640, 360, anchorX = .3f, anchorY = .8f)
        assertArrayEquals(before, after, .00001f)
    }
    @Test fun textFitUsesEvaluatedBoundsAndScaleAutoKeyWithoutChangingSize() {
        val layer = CompositionLayer(1, LayerType.TEXT, timing = ClipTiming(durationUs = 1_000_000),
            animTransform = AnimatableTransform(scale = AnimatableProperty2D(true, -1f, 2f,
                FrozenList.of(listOf(Keyframe2D(0, -1f, 2f))))))
        val state = EditorState(ProjectDocument(Composition(width = 640, height = 360, layers = FrozenList.of(listOf(layer)))))
        val bounds = TextBounds(0f, -40f, 320f, 90f)
        val fitted = EditorReducer.reduce(state, EditorAction.FitText(1, 500_000, bounds, 2)).project.composition.layers.first()
        assertEquals(layer.text, fitted.text)
        assertEquals(2f, fitted.evaluatedTransform(500_000).scaleX, 0f)
        assertEquals(4f, fitted.evaluatedTransform(500_000).scaleY, 0f)
        assertEquals(2, fitted.animTransform.scale.keyframes.size)
        assertThrows(IllegalArgumentException::class.java) {
            EditorReducer.reduce(state, EditorAction.FitText(1, 0, TextBounds(0f, 0f, 0f, 0f), 0))
        }
    }
    @Test fun keyedLayoutOrPositionRejectsImplicitCompensation() {
        val text = TextProperties(source = AnimatableString(true, "", FrozenList.of(listOf(StringKeyframe(0, "keyed")))))
        val layer = CompositionLayer(1, LayerType.TEXT, text = text)
        val state = EditorState(ProjectDocument(Composition(layers = FrozenList.of(listOf(layer)))))
        val result = EditorReducer.reduce(state, EditorAction.SetTextPayload(1, text.copy(fontId = "fluxx.serif"), Transform(positionX = .8f)))
        assertEquals(layer.transform, result.project.composition.layers.first().transform)
    }
    @Test fun typedActionsSplitAndGroupedMoveUseExistingReducer() {
        val layer = CompositionLayer(id = 1, type = LayerType.TEXT, timing = ClipTiming(durationUs = 1_000_000))
        var state = EditorState(ProjectDocument(Composition(layers = FrozenList.of(listOf(layer)))))
        state = EditorReducer.reduce(state, EditorAction.ToggleAnimated(1, AnimPropertyType.SOURCE_TEXT, true, 0))
        state = EditorReducer.reduce(state, EditorAction.SetStringKeyframe(1, 500_000, "next"))
        state = EditorReducer.reduce(state, EditorAction.MoveKeyframe(1, oldTimeUs = 500_000, newTimeUs = 600_000))
        assertEquals("next", state.project.composition.layers.first().text.source.evaluate(600_000))
        state = EditorReducer.reduce(state, EditorAction.Split(1, 400_000))
        val right = state.project.composition.layers.first { it.id == state.selectedLayerId }
        assertEquals("next", right.text.source.evaluate(200_000))
        assertEquals(listOf(-400_000L, 200_000L), right.text.source.keyframes.map { it.timeUs })
    }
}
