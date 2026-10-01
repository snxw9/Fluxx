package com.fluxx.android.model

import com.fluxx.android.editor.*
import com.fluxx.android.render.LayerGeometry
import org.junit.Assert.*
import org.junit.Test

class LayeredEditingTest {
    @Test fun resizingCanvasPreservesFootageCornersInPixelsAndPersistsTheReference() {
        val transform=Transform(positionX=0.3f,positionY=-0.2f,scaleX=0.8f,scaleY=1.2f,rotationDegrees=33f)
        val original=video(1).copy(transform=transform)
        val initial=EditorState(project(original)) // Also exercises layers without v4 reference fields.
        for ((sw,sh) in listOf(1920 to 1080,1080 to 1920,1080 to 1080,1440 to 1080)) {
            for (rotation in listOf(0,90,180,270)) {
                val before=FloatArray(16)
                LayerGeometry.matrix(before,transform,1080,1920,sw,sh,rotation,1.1f)
                var state=initial
                for ((cw,ch) in listOf(1920 to 1080,1080 to 1080,720 to 1280,1080 to 1920)) {
                    state=EditorReducer.reduce(state,EditorAction.SetComposition(cw,ch,10_000_000,FrameRate()))
                    state=state.copy(project=ProjectCodec.decode(ProjectCodec.encode(state.project)))
                    val layer=state.project.composition.layers.single()
                    assertEquals(transform,layer.transform)
                    assertEquals(original.timing,layer.timing)
                    assertEquals(original.asset,layer.asset)
                    assertEquals(1080,layer.referenceWidth)
                    assertEquals(1920,layer.referenceHeight)
                    val after=FloatArray(16)
                    LayerGeometry.matrix(after,layer.transform,cw,ch,sw,sh,rotation,1.1f,
                        referenceWidth=layer.referenceWidth,referenceHeight=layer.referenceHeight)
                    for (x in listOf(-1f,1f)) for (y in listOf(-1f,1f)) {
                        // Compare actual pixel coordinates relative to the canvas center, not NDC.
                        assertEquals((before[0]*x+before[4]*y+before[12])*1080/2,
                            (after[0]*x+after[4]*y+after[12])*cw/2,0.001f)
                        assertEquals((before[1]*x+before[5]*y+before[13])*1920/2,
                            (after[1]*x+after[5]*y+after[13])*ch/2,0.001f)
                    }
                }
            }
        }
    }

    @Test fun newLayersCaptureTheirOwnCanvasAndSettingsLeaveExistingEditsUntouched() {
        val initial=EditorReducer.reduce(EditorState(project()),EditorAction.Add(video(1)))
        val resized=EditorReducer.reduce(initial,EditorAction.SetComposition(1920,1080,5_000_000,FrameRate(24)))
        assertEquals(initial.project.composition.layers,resized.project.composition.layers)
        val added=EditorReducer.reduce(resized,EditorAction.Add(video(2)))
        assertEquals(1080,added.project.composition.layers[0].referenceWidth)
        assertEquals(1920,added.project.composition.layers[1].referenceWidth)
        assertEquals(1080,added.project.composition.layers[1].referenceHeight)
        assertEquals(added.project,ProjectCodec.decode(ProjectCodec.encode(added.project)))
    }

    @Test fun canvasResizeDoesNotExpandAnExistingSolid() {
        val solid=CompositionLayer(1,LayerType.SOLID,timing=ClipTiming(0,0,1_000_000))
        val resized=EditorReducer.reduce(EditorState(project(solid)),EditorAction.SetComposition(1920,1080,10_000_000,FrameRate()))
        val layer=resized.project.composition.layers.single()
        val matrix=FloatArray(16)
        LayerGeometry.matrix(matrix,layer.transform,1920,1080,layer.referenceWidth,layer.referenceHeight,
            referenceWidth=layer.referenceWidth,referenceHeight=layer.referenceHeight)
        assertEquals(1080f,matrix[0]*1920,0.001f)
        assertEquals(1920f,matrix[5]*1080,0.001f)
    }

    private fun video(id: Long,start: Long=0,end: Long=1_000_000,gain: Float=1f) = CompositionLayer(id,
        timing=ClipTiming(start,0,end-start),asset=AssetReference("asset$id","content://video/$id",durationUs=10_000_000),audioGain=gain)
    private fun project(vararg layers: CompositionLayer) = ProjectDocument(Composition(durationUs=10_000_000,layers=FrozenList.of(layers.toList())))

    @Test fun sixThousandLayersPersistWithoutAnArtificialCap() {
        val layers=(1L..6000L).map { CompositionLayer(it,LayerType.SOLID,zOrder=it.toInt(),
            timing=ClipTiming(0,0,1_000_000),name="Solid $it",solidColorArgb=it.toInt()) }
        val p=ProjectDocument(Composition(durationUs=1_000_000,layers=FrozenList.of(layers)))
        assertEquals(p,ProjectCodec.decode(ProjectCodec.encode(p)))
        var count=0
        FramePlan(p).forEachActive(500_000) { _,_,_,_ -> count++ }
        assertEquals(6000,count)
    }
    @Test fun overlappingAudioUsesFixedHeadroomButTouchingClipsDoNot() {
        assertEquals(0.5f,AudioMixPolicy.headroom(project(video(1),video(2))),0.0001f)
        assertEquals(1f,AudioMixPolicy.headroom(project(video(1),video(2,1_000_000,2_000_000))),0.0001f)
        val p=project(video(1,gain=0.25f),video(2,gain=0.25f))
        assertEquals(1f,AudioMixPolicy.headroom(p),0.0001f)
        assertEquals(listOf(0.25f,0.25f),FrameEvaluator.evaluate(p,0).layers.map {it.audioGain})
    }
    @Test fun reorderChangesDrawOrderAndRemoveKeepsOtherIds() {
        val initial=EditorState(project(video(1),video(2),video(3)))
        val reordered=EditorReducer.reduce(initial,EditorAction.Reorder(1,true))
        assertEquals(listOf(2L,1L,3L),FrameEvaluator.evaluate(reordered.project,0).layers.map {it.id})
        val removed=EditorReducer.reduce(reordered,EditorAction.Remove(1))
        assertEquals(setOf(2L,3L),removed.project.composition.layers.map {it.id}.toSet())
    }
    @Test fun trimsClampToSourceAndMovesExtendTheComposition() {
        val l=video(1).copy(timing=ClipTiming(100_000,100_000,900_000))
        val p=project(l)
        assertEquals(0L,TimelineEdits.trimStart(p,l,-100_000).startUs)
        val trim=TimelineEdits.trimEnd(p,l,20_000_000)
        assertEquals(10_000_000L,trim.endUs)
        val moved=EditorReducer.reduce(EditorState(p),EditorAction.Move(1,20_000_000))
        assertEquals(20_900_000L,moved.project.composition.durationUs)
    }
    @Test fun relinkingPreservesEditsAndRejectsAnInsufficientSource() {
        val l=video(1).copy(transform=Transform(rotationDegrees=33f),timing=ClipTiming(500_000,250_000,1_000_000))
        val state=EditorState(project(l),l.id)
        val replacement=l.asset!!.copy(uri="content://replacement",access=MediaAccess.AVAILABLE)
        val relinked=EditorReducer.reduce(state,EditorAction.Relink(replacement.id,replacement)).selectedLayer!!
        assertEquals(l.timing,relinked.timing);assertEquals(l.transform,relinked.transform);assertEquals(l.id,relinked.id)
        assertThrows(IllegalArgumentException::class.java) {
            EditorReducer.reduce(state,EditorAction.Relink(replacement.id,replacement.copy(durationUs=100_000)))
        }
    }
    @Test fun geometryPreservesFitAndConvertsCoordinateHandedness() {
        val down=FloatArray(16);val up=FloatArray(16)
        val transform=Transform(positionX=0.3f,positionY=0.2f,rotationDegrees=30f)
        LayerGeometry.matrix(down,transform,1080,1920,1920,1080)
        LayerGeometry.matrix(up,transform,1080,1920,1920,1080,yDown=false)
        assertEquals(down[0],up[0],0.00001f);assertEquals(-down[1],up[1],0.00001f)
        assertEquals(-down[4],up[4],0.00001f);assertEquals(-down[13],up[13],0.00001f)
        LayerGeometry.matrix(down,Transform(),1080,1920,1920,1080,sourceRotation=90)
        assertEquals(-1f,down[4],0.00001f);assertEquals(1f,down[1],0.00001f)
    }
}
