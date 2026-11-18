package com.fluxx.android.ui.timeline

import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import com.fluxx.android.ui.inspector.PopoverPlacer
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** TEXT UI 8 marker editor: one anchored popover, live apply, one undo per session, removal unchanged. */
class MarkerPopoverTest {
    private val layer=CompositionLayer(1,LayerType.SOLID,timing=ClipTiming(0,0,4_000_000))
    private fun editor()=EditorViewModel().apply {
        dispatch(EditorAction.Load(ProjectDocument(Composition(durationUs=4_000_000,layers=FrozenList.of(listOf(layer))))))
    }
    private fun sink(e: EditorViewModel)=object: MarkerGestureSink {
        override fun begin()=e.beginGesture()
        override fun preview(action: EditorAction)=e.previewGesture(action)
        override fun commit()=e.commitGesture()
        override fun cancel()=e.cancelGesture()
    }
    private fun create(e: EditorViewModel,at: Long,selected: CompositionLayer?=null): MarkerEditing.PlayheadResult.Create {
        val r=MarkerEditing.playhead(e.state.project,selected,at,false,
            if(selected==null) MarkerEditing.COMPOSITION_DEFAULT else MarkerEditing.LAYER_DEFAULT) as MarkerEditing.PlayheadResult.Create
        e.dispatch(r.action);return r
    }
    private fun src(path: String)=File("src/main/kotlin/com/fluxx/android/$path").readText()

    @Test fun popoverIsAnchoredToTheMarkerAndClampedNearScreenEdges() {
        val w=1080;val h=2400;val m=24;val gap=12;val pw=864;val ph=700
        // Composition marker on the ruler near the top: no room above, so it opens below the pentagon.
        val ruler=PopoverPlacer.place(530,600,557,636,pw,ph,w,h,h,m,gap)
        assertFalse(ruler.above);assertEquals(636+gap,ruler.y)
        // Layer marker low on the screen: opens above the triangle.
        val low=PopoverPlacer.place(530,1900,554,1921,pw,ph,w,h,h,m,gap)
        assertTrue(low.above);assertEquals(1900-gap-ph,low.y)
        // Near the left / right edges: clamped with the 8dp margin, never centred on the window.
        assertEquals(m,PopoverPlacer.place(0,1900,24,1921,pw,ph,w,h,h,m,gap).x)
        assertEquals(w-pw-m,PopoverPlacer.place(1056,1900,1080,1921,pw,ph,w,h,h,m,gap).x)
        // With the keyboard up the popover lifts above it (only the popover moves).
        val ime=PopoverPlacer.place(530,1900,554,1921,pw,ph,w,h,1500,m,gap)
        assertTrue(ime.y+ph<=1500-m)
        // The pentagon anchor spans the ruler's marker band.
        assertEquals(16,MARKER_PENTAGON_TOP_DP);assertEquals(28,MARKER_PENTAGON_BOTTOM_DP)
    }
    @Test fun oneAnchoredPopoverWithNoButtonsAndTheOldDialogIsGone() {
        assertFalse(File("src/main/kotlin/com/fluxx/android/ui/timeline/MarkerDialog.kt").exists())
        val popover=src("ui/timeline/MarkerPopover.kt");val timeline=src("ui/timeline/TimelineView.kt")
        for(gone in listOf("Dialog(","TextButton(","Button(","\"Delete\"","\"Save\"","\"Add\"","Close marker editor","AnchoredPopover("))
            assertFalse(gone,popover.contains(gone))
        assertTrue(popover.contains("ColourPickerVariant.COMPACT") && popover.contains("MARKER_SWATCHES"))
        // Exactly one popover, anchored inside the marker glyph hosts (ruler pentagon, layer triangle).
        assertEquals(1,Regex("""AnchoredPopover\(""").findAll(timeline).count())
        assertTrue(timeline.contains("\"marker-popover\"") && timeline.contains("composition-marker-anchor"))
        assertTrue(timeline.contains("if (marker.id == popoverMarkerId) popover()"))
        assertFalse(timeline.contains("MarkerDialog") || timeline.contains("MarkerDraft"))
    }
    @Test fun createPlacesTheMarkerAsOneUndoThenOpensThePopover() {
        val e=editor()
        val r=create(e,1_000_000)
        val marker=MarkerEditing.find(e.state.project,r.ref)!!
        assertEquals(MarkerEditing.COMPOSITION_DEFAULT,marker.colorArgb);assertEquals("",marker.description)
        assertEquals(0xFFF44336.toInt(),MarkerEditing.COMPOSITION_DEFAULT);assertEquals(0xFFFF9800.toInt(),MarkerEditing.LAYER_DEFAULT)
        // The popover opens on it; closing without entering anything keeps the marker (empty description allowed).
        val s=MarkerEditSession(r.ref,marker,sink(e));s.close()
        assertNotNull(MarkerEditing.find(e.state.project,r.ref))
        e.undo();assertNull(MarkerEditing.find(e.state.project,r.ref));assertFalse(e.canUndo)
        // Layer markers too, in layer-local time.
        val l=create(e,2_000_000,e.state.project.composition.layers.single())
        assertEquals(1L,l.ref.layerId);assertEquals(MarkerEditing.LAYER_DEFAULT,MarkerEditing.find(e.state.project,l.ref)!!.colorArgb)
    }
    @Test fun descriptionAndColourApplyLiveWithOneUndoPerSession() {
        val e=editor();val r=create(e,1_000_000)
        val s=MarkerEditSession(r.ref,MarkerEditing.find(e.state.project,r.ref)!!,sink(e))
        s.beginDescription()
        for(t in listOf("I","In","Intro")) {s.description(t);assertEquals(t,MarkerEditing.find(e.displayedState.project,r.ref)!!.description)}
        s.endDescription()
        assertEquals("Intro",MarkerEditing.find(e.state.project,r.ref)!!.description)
        // One colour drag = one entry, live while dragging.
        s.beginColour()
        for(c in listOf(0xFF00FF00.toInt(),0xFF0000FF.toInt())) {s.colour(c);assertEquals(c,MarkerEditing.find(e.displayedState.project,r.ref)!!.colorArgb)}
        s.endColour()
        assertEquals(0xFF0000FF.toInt(),MarkerEditing.find(e.state.project,r.ref)!!.colorArgb)
        // add + description session + colour interaction = 3 undo entries (not one per keystroke / move).
        e.undo();assertEquals(MarkerEditing.COMPOSITION_DEFAULT,MarkerEditing.find(e.state.project,r.ref)!!.colorArgb)
        assertEquals("Intro",MarkerEditing.find(e.state.project,r.ref)!!.description)
        e.undo();assertEquals("",MarkerEditing.find(e.state.project,r.ref)!!.description)
        e.undo();assertNull(MarkerEditing.find(e.state.project,r.ref));assertFalse(e.canUndo)
        // Single line, schema length.
        assertEquals("ab",MarkerEditing.sanitize("a\nb"));assertEquals(128,MarkerEditing.sanitize("x".repeat(200)).length)
    }
    @Test fun outsideTapClosesWithoutDiscardingEdits() {
        val e=editor();val r=create(e,1_000_000)
        val s=MarkerEditSession(r.ref,MarkerEditing.find(e.state.project,r.ref)!!,sink(e))
        // Typing, then tapping outside while the field still has focus: the close commits the description.
        s.beginDescription();s.description("Drop");s.close()
        assertTrue(s.closed);assertFalse(s.active)
        assertEquals("Drop",MarkerEditing.find(e.state.project,r.ref)!!.description)
        // Mid colour drag too.
        val t=MarkerEditSession(r.ref,MarkerEditing.find(e.state.project,r.ref)!!,sink(e))
        t.beginColour();t.colour(0xFF123456.toInt());t.close()
        assertEquals(0xFF123456.toInt(),MarkerEditing.find(e.state.project,r.ref)!!.colorArgb)
        // A closed session ignores late callbacks.
        t.colour(0xFF000000.toInt());assertEquals(0xFF123456.toInt(),MarkerEditing.find(e.state.project,r.ref)!!.colorArgb)
        // A cancelled pointer interaction (not a close) reverts only that interaction.
        val u=MarkerEditSession(r.ref,MarkerEditing.find(e.state.project,r.ref)!!,sink(e))
        u.beginColour();u.colour(0xFFFFFFFF.toInt());u.cancelColour()
        assertEquals(0xFF123456.toInt(),u.colour);assertEquals(0xFF123456.toInt(),MarkerEditing.find(e.displayedState.project,r.ref)!!.colorArgb)
    }
    @Test fun tappingThePlayheadOnAMarkerStillRemovesIt() {
        val e=editor();val r=create(e,1_000_000)
        // Tap = remove (unchanged); hold = edit (the old dialog gesture now opens the popover).
        val tap=MarkerEditing.playhead(e.state.project,null,1_000_000,false,0)
        assertEquals(MarkerEditing.PlayheadResult.Remove(EditorAction.DeleteCompositionMarker(r.ref.id)),tap)
        val hold=MarkerEditing.playhead(e.state.project,null,1_000_000,true,0)
        assertEquals(r.ref,(hold as MarkerEditing.PlayheadResult.Edit).ref)
        // Outside the selected layer: no marker (toast), as before.
        val short=layer.copy(timing=ClipTiming(0,0,500_000))
        assertEquals(MarkerEditing.PlayheadResult.OutsideLayer,MarkerEditing.playhead(e.state.project,short,1_000_000,false,0))
        e.dispatch((tap as MarkerEditing.PlayheadResult.Remove).action)
        assertNull(MarkerEditing.find(e.state.project,r.ref))
        // The timeline ruler/layer tap rule is unchanged too (tapMarker: at the playhead removes, else selects).
        assertTrue(src("ui/timeline/TimelineView.kt").contains("onMarkerAction(if (owner == null) EditorAction.DeleteCompositionMarker(marker.id)"))
    }
    @Test fun thePopoverClosesWhenItsMarkerIsRemoved() {
        val e=editor();val r=create(e,1_000_000)
        val s=MarkerEditSession(r.ref,MarkerEditing.find(e.state.project,r.ref)!!,sink(e))
        s.beginDescription();s.description("Gone")
        // Removed while open (playhead tap after an outside tap, undo, layer deletion): the edit cancels the gesture,
        // the timeline sees the marker missing and detaches the session without committing to a missing marker.
        e.dispatch(EditorAction.DeleteCompositionMarker(r.ref.id))
        assertNull(MarkerEditing.find(e.state.project,r.ref))
        s.detach();assertTrue(s.closed);assertFalse(s.active)
        s.close();s.description("late")
        assertNull(MarkerEditing.find(e.state.project,r.ref))
        assertTrue(src("ui/timeline/TimelineView.kt").contains("if (!editedMarkerExists) { markerSession?.detach(); markerSession = null; markerPopoverOpen = false }"))
    }
    @Test fun aMarkerWithoutAGlyphAnchorsToThePlayhead() {
        val e=editor();val c=create(e,1_000_000);val l=create(e,2_000_000,e.state.project.composition.layers.single())
        assertTrue(MarkerEditing.drawnOnBar(e.state.project,c.ref));assertTrue(MarkerEditing.drawnOnBar(e.state.project,l.ref))
        // Trim the clip so the retained layer marker falls outside it: no pennant is drawn, so the playhead hosts the popover.
        e.dispatch(EditorAction.Trim(1,0,1_000_000))
        assertNotNull(MarkerEditing.find(e.state.project,l.ref))
        assertFalse(MarkerEditing.drawnOnBar(e.state.project,l.ref))
        assertTrue(src("ui/timeline/TimelineView.kt").contains("playheadPopover = if (markerOnBar) null else markerPopover"))
    }
}
