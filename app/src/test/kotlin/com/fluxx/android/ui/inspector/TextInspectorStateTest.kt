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
    @Test fun linkedGlyphScaleKeepsTheRatioInOneEdit() {
        val l=layer();val doc=l.text.source.sourceDocument.format(0,0) {it.copy(horizontalScale=100f,verticalScale=50f)}
        val changed=l.copy(text=l.text.copy(source=AnimatableString(staticValue=doc.text,document=doc)))
        val d=TextNumericDraft(changed,500_000,FrameRate(),TextEditScope(null),TextControl.H_SCALE)
        val style=d.payload(200f,linked=true).source.sourceDocument.runAt(0)!!.style
        assertEquals(200f,style.horizontalScale,0f);assertEquals(100f,style.verticalScale,1e-3f)
        val unlinked=d.payload(200f,linked=false).source.sourceDocument.runAt(0)!!.style
        assertEquals(50f,unlinked.verticalScale,0f)
        // Linked result is held to the glyph-scale hard cap.
        assertEquals(1000f,TextNumericDraft(changed,500_000,FrameRate(),TextEditScope(null),TextControl.V_SCALE).payload(1000f,true).source.sourceDocument.runAt(0)!!.style.horizontalScale,0f)
    }
    @Test fun resetReturnsToTheInheritedDefault() {
        val l=layer()
        assertEquals(72f,TextNumericDraft(l,500_000,FrameRate(),TextEditScope(null),TextControl.SIZE).default,0f)
        assertEquals(100f,TextNumericDraft(l,500_000,FrameRate(),TextEditScope(TextSelectionRange(0,2)),TextControl.SIZE).default,0f)
        assertEquals(0f,TextNumericDraft(l,500_000,FrameRate(),TextEditScope(TextSelectionRange(0,2)),TextControl.TRACKING).default,0f)
        assertEquals(100f,TextNumericDraft(l,500_000,FrameRate(),TextEditScope(null),TextControl.H_SCALE).default,0f)
    }
    @Test fun savedSourceDraftAndFontsPageRestoreWithoutDocumentMutation() {
        // Colour has no page any more (it is edited entirely inside its popover): only Source, Fonts and Browse persist.
        // Exhaustive without an else branch: adding a mode back (e.g. a colour page) breaks this test's compilation.
        fun name(m: TextInspectorMode)=when(m) {TextInspectorMode.Browse->"browse";TextInspectorMode.Source->"source";TextInspectorMode.Fonts->"fonts"}
        assertEquals(listOf("browse","source","fonts"),listOf(TextInspectorMode.Browse,TextInspectorMode.Source,TextInspectorMode.Fonts).map(::name))
        assertEquals(TextInspectorMode.Source,TextInspectorModeCodec.restore(TextInspectorModeCodec.save(TextInspectorMode.Source)))
        assertEquals(TextInspectorMode.Fonts,TextInspectorModeCodec.restore(TextInspectorModeCodec.save(TextInspectorMode.Fonts)))
        assertEquals(TextInspectorMode.Browse,TextInspectorModeCodec.restore(listOf("draft",-1L)))
    }
    @Test fun mixedValuesRemainExplicitAndValuesStopAtTheHardCaps() {
        val l=layer();val doc=l.text.source.sourceDocument.format(2,3) {it.copy(horizontalScale=120f)}
        val changed=l.copy(text=l.text.copy(source=AnimatableString(staticValue=doc.text,document=doc)))
        val d=TextNumericDraft(changed,500_000,FrameRate(),TextEditScope(null),TextControl.H_SCALE)
        assertTrue(d.mixed);assertEquals(1000f,d.scrubValue(20000f),0f)
        assertEquals(1f,d.scrubValue(-1f),0f)
        val size=TextNumericDraft(l,500_000,FrameRate(),TextEditScope(null),TextControl.SIZE)
        assertEquals(1000f,size.scrubValue(10000f),0f);assertEquals(8f,size.scrubValue(1f),0f)
        assertEquals(1000f,TextNumericDraft(l,500_000,FrameRate(),TextEditScope(null),TextControl.TRACKING).scrubValue(5000f),0f)
        assertEquals(1000f,TextNumericDraft(l,500_000,FrameRate(),TextEditScope(TextSelectionRange(0,2)),TextControl.SIZE).scrubValue(5000f),0f)
        assertEquals(size.initial,size.scrubValue(Float.NaN),0f);assertEquals(1000f,size.scrubValue(Float.POSITIVE_INFINITY),0f)
    }
}
