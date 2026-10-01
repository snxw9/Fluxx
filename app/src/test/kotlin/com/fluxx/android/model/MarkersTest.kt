package com.fluxx.android.model

import com.fluxx.android.editor.*
import com.google.flatbuffers.FlatBufferBuilder
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test
import fluxx.schema.Composition as SComp
import fluxx.schema.Layer as SLayer
import fluxx.schema.Project as SProject

class MarkersTest {
    private fun layer(markers: List<Marker> = emptyList()) = CompositionLayer(1, LayerType.SOLID,
        timing = ClipTiming(1_000_000, 0, 4_000_000), markers = FrozenList.of(markers))
    private fun project(layer: CompositionLayer = layer(), markers: List<Marker> = emptyList(),
        rate: FrameRate = FrameRate()) = ProjectDocument(Composition(durationUs = 8_000_000,
        frameRate = rate, layers = FrozenList.of(listOf(layer)), markers = FrozenList.of(markers)))

    @Test fun addCompositionAndLayerMarkersMaintainsSortingAndFrameUniqueness() {
        var state = EditorState(project())
        for (marker in listOf(Marker(1, 2_000_000), Marker(2, 1_000_000), Marker(3, 1_000_001, description = "Updated"))) {
            state = EditorReducer.reduce(state, EditorAction.AddCompositionMarker(marker))
        }
        assertEquals(listOf(1_000_001L, 2_000_000L), state.project.composition.markers.map { it.timeUs })
        assertEquals(2L, state.project.composition.markers.first().id)
        assertEquals("Updated", state.project.composition.markers.first().description)
        for (marker in listOf(Marker(1, 2_000_000), Marker(2, -500_000), Marker(3, -499_999))) {
            state = EditorReducer.reduce(state, EditorAction.AddLayerMarker(1, marker))
        }
        assertEquals(listOf(-499_999L, 2_000_000L), state.selectedLayer!!.markers.map { it.timeUs })
        assertEquals(2, state.selectedLayer!!.markers.size)
        // Off-grid anchors must compare composition frames, not local frames.
        val offGrid = layer().copy(keyframeAnchorUs = 16_000)
        state = EditorState(project(offGrid))
        state = EditorReducer.reduce(state, EditorAction.AddLayerMarker(1, Marker(1, 0)))
        state = EditorReducer.reduce(state, EditorAction.AddLayerMarker(1, Marker(2, 1_000)))
        assertEquals(2, state.selectedLayer!!.markers.size)
    }

    @Test fun layerMarkersRebaseOnSplitWithoutDuplication() {
        val original = layer(listOf(Marker(1, -500_000), Marker(2, 1_000_000),
            Marker(3, 2_000_000), Marker(4, 5_000_000)))
        val vm = EditorViewModel()
        vm.dispatch(EditorAction.Load(project(original)))
        vm.dispatch(EditorAction.Split(1, 3_000_000))
        val split = vm.state.project
        val left = split.composition.layers.first { it.id == 1L }
        val right = vm.state.selectedLayer!!
        assertEquals(listOf(1L, 2L), left.markers.map { it.id })
        assertEquals(listOf(3L, 4L), right.markers.map { it.id })
        assertEquals(listOf(0L, 3_000_000L), right.markers.map { it.timeUs })
        assertEquals(3_000_000L, right.resolvedKeyframeAnchorUs)
        assertEquals(4, (left.markers + right.markers).map { it.id }.distinct().size)
        vm.undo()
        assertEquals(project(original), vm.state.project)
        vm.redo()
        assertEquals(split, vm.state.project)
    }

    @Test fun layerMarkersSurviveMoveTrimDuplicateAndUndo() {
        val original = layer(listOf(Marker(1, 0), Marker(2, 3_000_000)))
        val vm = EditorViewModel()
        vm.dispatch(EditorAction.Load(project(original)))
        vm.dispatch(EditorAction.Select(1))
        vm.dispatch(EditorAction.Trim(1, 2_000_000, 3_000_000))
        assertEquals(original.markers, vm.state.selectedLayer!!.markers)
        assertEquals(1_000_000L, vm.state.selectedLayer!!.resolvedKeyframeAnchorUs)
        vm.dispatch(EditorAction.Move(1, 4_000_000))
        val moved = vm.state.selectedLayer!!
        assertEquals(3_000_000L, moved.resolvedKeyframeAnchorUs)
        assertEquals(original.markers, moved.markers)
        vm.duplicateLayer(1)
        assertEquals(moved.markers, vm.state.selectedLayer!!.markers)
        vm.undo()
        assertEquals(listOf(moved), vm.state.project.composition.layers)
        vm.copyLayer(1)
        vm.dispatch(EditorAction.Seek(6_000_000))
        vm.pasteLayer()
        assertEquals(moved.markers, vm.state.selectedLayer!!.markers)
        assertEquals(5_000_000L, vm.state.selectedLayer!!.resolvedKeyframeAnchorUs)
        val beforeDelete = vm.state.project
        vm.dispatch(EditorAction.Remove(vm.state.selectedLayerId!!))
        assertNull(vm.state.selectedMarkerId)
        vm.undo()
        assertEquals(beforeDelete, vm.state.project)
    }

    @Test fun playheadColorLookupQuantizesToFrameGridAndReturnsExistingObjects() {
        for (rate in listOf(FrameRate(), FrameRate(30000, 1001))) {
            val time = rate.frameTimeUs(60)
            val compMarker = Marker(1, time, 0xFFFF0000.toInt())
            val selected = layer(listOf(Marker(1, time - 1_000_000, 0xFF00FF00.toInt())))
            val p = project(selected, listOf(compMarker), rate)
            repeat(1000) {
                assertSame(selected.markers.single(), Markers.active(p, selected, time + 1))
                assertSame(compMarker, Markers.active(p, null, time + 1))
            }
            assertNull(Markers.active(p, selected, rate.frameTimeUs(61)))
        }
        // Allocation claims require profiling; identity assertions only guard against copying results.
        val hidden = layer(listOf(Marker(1, -1_000_001)))
        assertNull(Markers.active(project(hidden), hidden, 0))
    }

    @Test fun prevNextChainsThroughMarkersWhenActiveAndFallsThroughWhenExhausted() {
        val selected = layer(listOf(Marker(1, 1_000_000), Marker(2, 2_000_000)))
        val p = project(selected, listOf(Marker(1, 1_500_000)))
        var state = EditorState(p, 1, 2_000_001, 1)
        val next = ContextualNavigation.findNext(p, selected, false, null, state.playheadUs, state.selectedMarkerId)
        assertEquals(3_000_000L, next)
        state = EditorReducer.reduce(state, EditorAction.Seek(next))
        assertEquals(5_000_000L, ContextualNavigation.findNext(p, selected, false, null, next, state.selectedMarkerId))
        state = EditorReducer.reduce(state, EditorAction.Seek(5_000_000))
        assertNull(state.selectedMarkerId)
        assertEquals(8_000_000L, ContextualNavigation.findNext(p, selected, false, null, 5_000_000))
        assertEquals(2_000_000L, ContextualNavigation.findPrevious(p, selected, false, null, 3_000_001))
        assertEquals(5_000_000L, ContextualNavigation.findNext(p, null, false, null, 1_500_000))
        assertEquals(2_000_000L, ContextualNavigation.findNext(p, selected, false, null, 1_500_000))
        val animated = selected.copy(animTransform = AnimatableTransform(rotation = AnimatableProperty1D(
            true, keyframes = FrozenList.of(listOf(Keyframe1D(1_500_000, 45f))))))
        assertEquals(2_500_000L, ContextualNavigation.findNext(project(animated), animated, true,
            AnimPropertyType.ROTATION, 2_000_000, 1))
    }

    @Test fun parkedMarkersChainThroughTransportWithoutExplicitSelectionInBothScopes() {
        for (rate in listOf(FrameRate(), FrameRate(30000, 1001))) {
            val anchor = rate.frameTimeUs(50)
            val selected = layer().copy(
                timing = ClipTiming(rate.frameTimeUs(20), 0, rate.frameTimeUs(100)),
                keyframeAnchorUs = anchor,
                markers = FrozenList.of(listOf(45L, 60L, 75L).mapIndexed { index, frame ->
                    Marker(index + 1L, rate.frameTimeUs(frame) - anchor)
                }))
            val p = project(selected, listOf(30L, 60L, 90L).mapIndexed { index, frame ->
                Marker(index + 1L, rate.frameTimeUs(frame))
            }, rate)
            val vm = EditorViewModel()
            // Reuse handlers across load, scope changes and consecutive presses without recomposition.
            val next = { vm.navigateBoundary(true, false, null) }
            val previous = { vm.navigateBoundary(false, false, null) }
            vm.dispatch(EditorAction.Load(p))
            for (layerId in listOf(null, 1L)) {
                vm.dispatch(EditorAction.Select(layerId))
                // Regression: ordinary scrubbing between markers must not skip the marker tier.
                vm.dispatch(EditorAction.Seek(rate.frameTimeUs(50)))
                assertNull(vm.state.selectedMarkerId)
                assertEquals(rate.frameTimeUs(60), next())
                vm.dispatch(EditorAction.Seek(rate.frameTimeUs(50)))
                assertEquals(rate.frameTimeUs(if (layerId == null) 30 else 45), previous())
                vm.dispatch(EditorAction.Seek(0))
                assertEquals(rate.frameTimeUs(if (layerId == null) 30 else 45), next())
                vm.dispatch(EditorAction.Seek(rate.frameTimeUs(100)))
                assertEquals(rate.frameTimeUs(if (layerId == null) 90 else 75), previous())
                for (offset in listOf(0L, 1L)) {
                    vm.dispatch(EditorAction.Select(layerId))
                    vm.dispatch(EditorAction.Seek(rate.frameTimeUs(60) + offset))
                    assertNull(vm.state.selectedMarkerId)
                    assertEquals(rate.frameTimeUs(if (layerId == null) 90 else 75), next())
                    assertEquals(rate.frameTimeUs(60), previous())
                    assertEquals(rate.frameTimeUs(if (layerId == null) 30 else 45), previous())
                    assertEquals(rate.frameTimeUs(60), next())
                    assertNull(vm.state.selectedMarkerId)
                    assertEquals(rate.frameTimeUs(if (layerId == null) 90 else 75),
                        vm.navigateBoundary(true, true, null))
                }
            }
        }
    }

    @Test fun markerSelectionLifecycleClearsOnLayerChangeScrubAndPropertyFocus() {
        val initial = EditorState(project(layer(listOf(Marker(1, 1_000_000)))), 1, 2_000_000, 1)
        assertEquals(1L, EditorReducer.reduce(initial, EditorAction.Seek(2_000_001)).selectedMarkerId)
        assertNull(EditorReducer.reduce(initial, EditorAction.Seek(2_100_000)).selectedMarkerId)
        assertNull(EditorReducer.reduce(initial, EditorAction.Select(1)).selectedMarkerId)
        assertNull(EditorReducer.reduce(initial, EditorAction.Select(null)).selectedMarkerId)
        // Inspector focus, empty ruler taps and playback start dispatch this transient action.
        assertNull(EditorReducer.reduce(initial, EditorAction.SelectMarker(null)).selectedMarkerId)
        assertNull(EditorReducer.reduce(initial, EditorAction.DeleteLayerMarker(1, 1)).selectedMarkerId)
        assertNull(EditorReducer.reduce(initial, EditorAction.Remove(1)).selectedMarkerId)
        assertNull(EditorReducer.reduce(initial, EditorAction.Load(initial.project)).selectedMarkerId)
    }

    @Test fun markerEditsAreSingleHistoryEntriesAndSelectionIsNotUndoable() {
        val vm = EditorViewModel()
        vm.dispatch(EditorAction.AddCompositionMarker(Marker(1, 1_000_000)))
        vm.dispatch(EditorAction.SelectMarker(null))
        vm.dispatch(EditorAction.SelectMarker(1))
        vm.undo()
        assertTrue(vm.state.project.composition.markers.isEmpty())
        assertFalse(vm.canUndo)
        vm.redo()
        vm.dispatch(EditorAction.EditCompositionMarker(1, 0xFF000000.toInt(), "Cue"))
        vm.undo()
        assertEquals("", vm.state.project.composition.markers.single().description)
        vm.redo()
        vm.dispatch(EditorAction.DeleteCompositionMarker(1))
        vm.undo()
        assertEquals("Cue", vm.state.project.composition.markers.single().description)
    }

    @Test fun projectCodecRoundTripPersistsMarkersAndLoadsLegacyDocumentsAsEmpty() {
        val p = project(layer(listOf(Marker(1, -500_000, description = "Hidden"))),
            listOf(Marker(1, 2_000_000, 0xFF345678.toInt(), "Composition cue")))
        assertEquals(p, ProjectCodec.decode(ProjectCodec.encode(p)))
        for (version in 1..6) {
            val b = FlatBufferBuilder(256)
            val uri = b.createString("content://legacy")
            val asset = b.createString("legacy")
            SLayer.startLayer(b)
            SLayer.addId(b, 1UL)
            SLayer.addAssetUri(b, uri)
            SLayer.addAssetId(b, asset)
            val layer = SLayer.endLayer(b)
            val layers = SComp.createLayersVector(b, intArrayOf(layer))
            SComp.startComposition(b)
            SComp.addLayers(b, layers)
            val comp = SComp.endComposition(b)
            val document = SProject.createProject(b, version, comp)
            b.finish(document)
            val decoded = ProjectCodec.decode(b.sizedByteArray()).composition
            assertTrue(decoded.markers.isEmpty())
            assertTrue(decoded.layers.single().markers.isEmpty())
        }
    }

    @Test fun validationAndFrameRateChangesMaintainInvariants() {
        assertThrows(IllegalArgumentException::class.java) { Marker(0, 0) }
        assertThrows(IllegalArgumentException::class.java) { Marker(1, 0, description = "a\nb") }
        assertThrows(IllegalArgumentException::class.java) { Marker(1, 0, description = "a".repeat(129)) }
        assertThrows(IllegalArgumentException::class.java) { project(markers = listOf(Marker(1, -1))) }
        assertThrows(IllegalArgumentException::class.java) { project(markers = listOf(Marker(1, 0), Marker(2, 1))) }
        assertThrows(ArithmeticException::class.java) { layer(listOf(Marker(1, Long.MAX_VALUE))) }
        val p = project(markers = listOf(Marker(1, 0), Marker(2, 33_333)))
        val updated = EditorReducer.reduce(EditorState(p), EditorAction.SetComposition(1080, 1920, 8_000_000, FrameRate(1)))
        assertEquals(1, updated.project.composition.markers.size)
    }

    @Test fun nearestFrameFastPathMatchesExactArithmeticIncludingOverflowFallback() {
        for (rate in listOf(FrameRate(30), FrameRate(30000, 1001), FrameRate(1, Int.MAX_VALUE))) {
            val divisor = BigInteger.valueOf(1_000_000L * rate.denominator)
            for (time in listOf(0L, 16_666, 16_667, 33_366, 1_001_000_000, Long.MAX_VALUE)) {
                val expected = BigInteger.valueOf(time).multiply(BigInteger.valueOf(rate.numerator.toLong()))
                    .add(divisor.divide(BigInteger.TWO)).divide(divisor).longValueExact()
                assertEquals(expected, rate.nearestFrame(time))
            }
        }
    }

    @Test fun keyframeSnapsToCompositionAndLayerMarkerTargets() {
        assertEquals(1_123_456L, TimelineEdits.snapKeyframeTime(1_120_000, 0, 4_000_000,
            FrameRate(), 20_000, emptyList(), 900_000, markerTimes = listOf(1_123_456)))
        assertEquals(2_234_567L, TimelineEdits.snapKeyframeTime(2_230_000, 0, 4_000_000,
            FrameRate(), 20_000, emptyList(), 900_000, markerTimes = listOf(1_123_456, 2_234_567)))
    }
}
