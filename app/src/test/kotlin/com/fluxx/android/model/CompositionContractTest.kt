package com.fluxx.android.model

import com.fluxx.android.editor.*
import com.google.flatbuffers.FlatBufferBuilder
import org.junit.Assert.*
import org.junit.Test
import fluxx.schema.Layer as SLayer
import fluxx.schema.Composition as SComp
import fluxx.schema.Project as SProject

class CompositionContractTest {
    private fun clip(id: Long, start: Long = 0, duration: Long = 100, order: Int = 0) =
        CompositionLayer(id, zOrder = order, timing = ClipTiming(start, 10, duration),
            asset = AssetReference("asset$id", "content://video/$id", durationUs = 1000))
    private fun project(vararg layers: CompositionLayer) = ProjectDocument(Composition(
        durationUs = 1000, layers = FrozenList.of(layers.toList())))

    @Test fun roundTripPreservesTwoLayerTimingOrderAndTransforms() {
        val original = project(clip(8, 100, 400, 7).copy(transform = Transform(0.2f, -0.3f, 2f, 0.5f, 37f, 0.4f)),
            clip(3, 0, 300, -1).copy(muted = true, audioGain = 0.5f))
        val restored = ProjectCodec.decode(ProjectCodec.encode(original))
        assertEquals(original, restored)
        assertEquals(listOf(3L, 8L), FrameEvaluator.evaluate(restored, 100).layers.map { it.id })
    }

    @Test fun boundaryIsHalfOpenAndSourceTimeUsesLocalTime() {
        val p = project(clip(1, 100, 100), clip(2, 200, 100))
        assertTrue(FrameEvaluator.evaluate(p, 99).layers.isEmpty())
        assertEquals(10L, FrameEvaluator.evaluate(p, 100).layers.single().sourceTimeUs)
        assertEquals(109L, FrameEvaluator.evaluate(p, 199).layers.single().sourceTimeUs)
        assertEquals(2L, FrameEvaluator.evaluate(p, 200).layers.single().id)
        assertTrue(FrameEvaluator.evaluate(p, 300).layers.isEmpty())
        assertTrue(FrameEvaluator.evaluate(p, 1000).layers.isEmpty())
    }

    @Test fun rationalGridDoesNotAccumulateRounding() {
        val rate = FrameRate(30000, 1001)
        assertEquals(1_001_000_000L, rate.frameTimeUs(30000))
        assertEquals(30000L, rate.nearestFrame(1_001_000_000L))
        assertEquals(33_366L, rate.frameTimeUs(1))
    }

    @Test fun movePreservesSourceAndTrimPreservesSurvivingSourceMapping() {
        val initial = EditorState(project(clip(1, 100, 200)))
        val moved = EditorReducer.reduce(initial, EditorAction.Move(1, 300))
        assertEquals(10L, FrameEvaluator.evaluate(moved.project, 300).layers.single().sourceTimeUs)
        val trimmed = EditorReducer.reduce(initial, EditorAction.Trim(1, 150, 250))
        assertEquals(FrameEvaluator.evaluate(initial.project, 175).layers.single().sourceTimeUs,
            FrameEvaluator.evaluate(trimmed.project, 175).layers.single().sourceTimeUs)
        assertThrows(IllegalArgumentException::class.java) {
            EditorReducer.reduce(initial, EditorAction.Trim(1, 0, 250))
        }
    }

    @Test fun unknownMissingAssetSurvivesAndIsNotAnInfiniteClip() {
        val l = clip(1).copy(asset = AssetReference("lost", "content://lost"), timing = ClipTiming())
        val loaded = ProjectCodec.decode(ProjectCodec.encode(project(l)))
        assertEquals("content://lost", loaded.composition.layers.single().asset?.uri)
        assertEquals(listOf(1L), FrameEvaluator.evaluate(loaded, 0).unresolvedLayerIds)
        assertTrue(FrameEvaluator.evaluate(loaded, 0).layers.isEmpty())
    }

    private fun legacy(version: Int = 1): ByteArray {
        val b = FlatBufferBuilder(256)
        val uri = b.createString("content://old/video")
        // Use the original eight-field table, not the v2 helper.
        b.startTable(8)
        SLayer.addAssetUri(b, uri); SLayer.addPositionX(b, 0.4f); SLayer.addOpacity(b, 0.7f)
        val first = b.endTable()
        b.startTable(8)
        SLayer.addAssetUri(b, uri)
        val second = b.endTable()
        val vector = SComp.createLayersVector(b, intArrayOf(first, second))
        b.startTable(1); SComp.addLayers(b, vector)
        val comp = b.endTable()
        val root = SProject.createProject(b, version, comp)
        b.finish(root)
        return b.sizedByteArray()
    }

    @Test fun legacyMigrationRetainsAppearanceAndAssignsUniqueIds() {
        val loaded = ProjectCodec.decode(legacy())
        assertEquals(1080, loaded.composition.width)
        assertEquals(1920, loaded.composition.height)
        assertEquals(FrameRate(), loaded.composition.frameRate)
        assertEquals(2, loaded.composition.layers.map { it.id }.toSet().size)
        assertEquals(0.4f, loaded.composition.layers.first().transform.positionX)
        assertEquals(0.7f, loaded.composition.layers.first().transform.opacity)
        assertNull(loaded.composition.durationUs)
        val asset = loaded.composition.layers.first().asset!!
        val resolved = EditorReducer.reduce(EditorState(loaded), EditorAction.ResolveAsset(asset.uri,
            asset.copy(access = MediaAccess.AVAILABLE, durationUs = 1234567)))
        assertEquals(1234567L, resolved.project.composition.durationUs)
        assertTrue(resolved.project.composition.layers.all { it.timing.durationUs == 1234567L })
    }

    @Test fun futureVersionIsRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) { ProjectCodec.decode(legacy(99)) }
        assertTrue(error.message!!.contains("Unsupported Fluxx project version 99"))
    }

    @Test fun snapshotsCannotBeChangedThroughCallerList() {
        val source = mutableListOf(clip(1))
        val frozen = FrozenList.of(source)
        source.clear()
        assertEquals(1, frozen.size)
    }
}
