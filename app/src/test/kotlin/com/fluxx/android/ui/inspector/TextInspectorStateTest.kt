package com.fluxx.android.ui.inspector

import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test

class TextInspectorStateTest {
    private fun layer()=CompositionLayer(1,LayerType.TEXT,keyframeAnchorUs=1_000_000,
        text=TextProperties(source=AnimatableString(staticValue="a\u0301BC"),size=AnimatableProperty1D(true,staticValue=48f,
            keyframes=FrozenList.of(listOf(Keyframe1D(-500_000,30f,EasingPreset.LINEAR))))))
    @Test fun capturesFrameTimeSignedOriginAndPreservesInterpolation() {
        val draft=TextNumericDraft(layer(),501_001,FrameRate(),TextEditScope(null),TextControl.SIZE)
        val result=draft.payload(77.125f)
        assertEquals(-500_000L,result.size.keyframes.single().timeUs)
        assertEquals(EasingPreset.LINEAR,result.size.keyframes.single().easing)
        assertEquals(77.125f,result.size.keyframes.single().value,0f)
        assertThrows(IllegalArgumentException::class.java) {draft.payload(Float.NaN)}
        assertThrows(IllegalArgumentException::class.java) {draft.payload(-1f)}
    }
    @Test fun rangeNumericEditsAreRelativeAndDoNotChangeBaseOrOtherRuns() {
        val source=layer();val scope=TextEditScope(TextSelectionRange(2,3))
        val result=TextNumericDraft(source,500_000,FrameRate(),scope,TextControl.SIZE).payload(150f)
        val doc=result.source.sourceDocument
        assertEquals(source.text.size,result.size);assertEquals(1.5f,doc.runAt(2)!!.style.sizeFactor,0f)
        assertEquals(1f,doc.runAt(0)!!.style.sizeFactor,0f)
        assertNull(scope.owner(TextControl.SIZE));assertFalse(scope.available(TextControl.LEADING))
        assertEquals(3,TextEditScope(TextSelectionRange(0,4)).count(source.text.source.sourceDocument))
    }
    @Test fun exactDraftCommitsOneUndoAndDiscardDoesNotCreateKey() {
        val l=layer();val original=ProjectDocument(Composition(layers=FrozenList.of(listOf(l))))
        val editor=EditorViewModel();editor.dispatch(EditorAction.Load(original))
        val draft=TextNumericDraft(l,700_000,FrameRate(),TextEditScope(null),TextControl.SIZE)
        draft.payload(44f);assertEquals(original,editor.state.project);assertFalse(editor.canUndo)
        editor.dispatch(EditorAction.SetTextPayload(1,draft.payload(55f)))
        val changed=editor.state.project;editor.undo();assertEquals(original,editor.state.project);assertFalse(editor.canUndo)
        editor.redo();assertEquals(changed,editor.state.project)
    }
    @Test fun manualSpacingUsesOriginalBoundariesAndPreservesOutsideSelection() {
        val d=TextNumericDraft(layer(),500_000,FrameRate(),TextEditScope(TextSelectionRange(0,2)),TextControl.MANUAL_KERNING)
        assertEquals(listOf(TextManualKerning(0,-12.5f)),d.payload(-12.5f).source.sourceDocument.runAt(0)!!.style.manualKerning.toList())
    }
    @Test fun accelerationIsBoundedFineAndSymmetric() {
        val slow=TextScrubTuning.delta(TextControl.BOX,100f,0f)
        val fast=TextScrubTuning.delta(TextControl.BOX,100f,5000f)
        assertTrue(fast>slow*10);assertEquals(-fast,TextScrubTuning.delta(TextControl.BOX,-100f,-5000f),0f)
        assertEquals(fast*.1f,TextScrubTuning.delta(TextControl.BOX,100f,5000f,true),0f)
        assertEquals(50f*TextScrubTuning.MAX_ACCELERATION,TextScrubTuning.delta(TextControl.TRACKING,100f,5000f),0f)
    }
    @Test fun sourceBudgetAt360AndAccessibilityScales() {
        for(scale in listOf(1f,1.3f,2f)) for(height in listOf(268f,328f)) {
            val total=height*scale;val body=TextInspectorBudget.bodyHeight(total,scale)
            assertTrue(body>=96f);assertEquals(total,body+48+32*scale+40+48+4,0.001f)
            assertEquals(if(scale>1.2f) 3 else 4,TextInspectorBudget.columns(360f,scale))
        }
        assertTrue(TextInspectorBudget.needsOverlay(200f,2f))
        for(total in listOf(100f,132f,164f,200f)) for(scale in listOf(1f,1.3f,2f)) {
            assertEquals(total,48+TextInspectorBudget.headerHeight(total,scale)+TextInspectorBudget.tabsHeight(total)+TextInspectorBudget.bodyHeight(total,scale)+48+4,.001f)
        }
    }
    @Test fun savedDraftRestoresCapturedScopeTimeAndLayerWithoutDocumentMutation() {
        val d=TextNumericDraft(layer(),700_001,FrameRate(24),TextEditScope(TextSelectionRange(2,3)),TextControl.TRACKING)
        val restored=TextInspectorModeCodec.restore(TextInspectorModeCodec.save(TextInspectorMode.Numeric(d))) as TextInspectorMode.Numeric
        assertEquals(d.time,restored.draft.time);assertEquals(d.scope,restored.draft.scope)
        assertEquals(d.layer,restored.draft.layer);assertEquals(d.payload(-12.125f),restored.draft.payload(-12.125f))
        val c=TextInspectorMode.Colour(TextControl.STROKE,layer(),500_000,TextEditScope(TextSelectionRange(0,2)))
        assertEquals(c,TextInspectorModeCodec.restore(TextInspectorModeCodec.save(c)))
    }
    @Test fun mixedValuesRemainExplicitAndScrubStopsAtModelBounds() {
        val l=layer();val doc=l.text.source.sourceDocument.format(2,3) {it.copy(horizontalScale=120f)}
        val changed=l.copy(text=l.text.copy(source=AnimatableString(staticValue=doc.text,document=doc)))
        val d=TextNumericDraft(changed,500_000,FrameRate(),TextEditScope(null),TextControl.H_SCALE)
        assertTrue(d.mixed);assertEquals(10000f,d.scrubValue(20000f),0f)
        assertEquals(0f,d.scrubValue(-1f),0f)
        val size=TextNumericDraft(l,500_000,FrameRate(),TextEditScope(null),TextControl.SIZE)
        assertEquals(500f,size.scrubValue(10000f),0f)
    }
}
