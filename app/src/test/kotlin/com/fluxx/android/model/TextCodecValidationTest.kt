package com.fluxx.android.model

import com.google.flatbuffers.FlatBufferBuilder
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class TextCodecValidationTest {
    private fun document(version: Int = 9, payload: (FlatBufferBuilder) -> Int): ByteArray {
        val b = FlatBufferBuilder(256)
        val text = payload(b)
        fluxx.schema.Layer.startLayer(b)
        fluxx.schema.Layer.addId(b, 1UL); fluxx.schema.Layer.addType(b, LayerType.TEXT.ordinal)
        if (text != 0) fluxx.schema.Layer.addTextPayload(b, text)
        val layer = fluxx.schema.Layer.endLayer(b)
        val layers = fluxx.schema.Composition.createLayersVector(b, intArrayOf(layer))
        fluxx.schema.Composition.startComposition(b); fluxx.schema.Composition.addLayers(b, layers)
        val composition = fluxx.schema.Composition.endComposition(b)
        val root = fluxx.schema.Project.createProject(b, version, composition); b.finish(root)
        return b.sizedByteArray()
    }
    @Test fun everyLegacyVersionKeepsPlaceholderTextEmpty() {
        for (version in 2..8) assertEquals(TextProperties(), ProjectCodec.decode(document(version) { 0 }).composition.layers.first().text)
    }
    @Test fun unsupportedAnimatorEnvelopeIsExplicitlyRejected() {
        val bytes = document { b ->
            val animator = fluxx.schema.TextAnimator.createTextAnimator(b, 1U, 0, 0)
            val vector = fluxx.schema.TextPayload.createTextAnimatorsVector(b, intArrayOf(animator))
            fluxx.schema.TextPayload.createTextPayload(b, 0, 0, 0, 0, 1, vector)
        }
        val error = assertThrows(IllegalArgumentException::class.java) { ProjectCodec.decode(bytes) }
        assertTrue(error.message!!.contains("animators"))
    }
    @Test fun absentStaticStringInPresentPayloadDefaultsToEmpty() {
        val bytes = document { b ->
            val source = fluxx.schema.AnimatableString.createAnimatableString(b, false, 0, 0)
            fluxx.schema.TextPayload.createTextPayload(b, source, 0, 0, 0, 1, 0)
        }
        assertEquals("", ProjectCodec.decode(bytes).composition.layers.first().text.source.staticValue)
    }
    @Test fun malformedUtf8AndOversizedPersistedStringsCannotBeTruncated() {
        for (raw in listOf(byteArrayOf(0xc0.toByte(), 0x80.toByte()), ByteArray(4097) { 'x'.code.toByte() })) {
            val bytes = document { b ->
                val value = b.createString(ByteBuffer.wrap(raw))
                val source = fluxx.schema.AnimatableString.createAnimatableString(b, false, value, 0)
                fluxx.schema.TextPayload.createTextPayload(b, source, 0, 0, 0, 1, 0)
            }
            assertThrows(Exception::class.java) { ProjectCodec.decode(bytes) }
        }
    }
    @Test fun aggregateSourceBudgetAndFrameUniqueTracksAreEnforced() {
        val huge = TextProperties(source = AnimatableString(true, "", FrozenList.of((0 until 257).map {
            StringKeyframe(it * 100_000L, "x".repeat(4096)) })))
        assertThrows(IllegalArgumentException::class.java) {
            Composition(layers = FrozenList.of(listOf(CompositionLayer(1, LayerType.TEXT, text = huge))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Composition(layers = FrozenList.of(listOf(CompositionLayer(1, LayerType.TEXT, text = TextProperties(
                source = AnimatableString(true, "", FrozenList.of(listOf(StringKeyframe(0, "a"), StringKeyframe(1, "b")))))))))
        }
    }
}
