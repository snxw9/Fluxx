package com.fluxx.android.model

import com.fluxx.android.editor.*
import org.junit.Assert.*
import org.junit.Test

/** TEXT F3: the Animate page's edits are existing AnimatorEdit actions; one undo per edit / drag session. */
class TextAnimatorEditsTest {
    private var serial=100L
    private val ids=TextAnimatorEdits.Ids { serial++ }
    private val rate=FrameRate(30,1)
    private fun layer(text: TextProperties=TextProperties())=CompositionLayer(1,LayerType.TEXT,timing=ClipTiming(durationUs=4_000_000),text=text)
    private fun state(text: TextProperties=TextProperties())=EditorState(ProjectDocument(Composition(durationUs=4_000_000,frameRate=rate,
        layers=FrozenList.of(listOf(layer(text))))),1)
    private fun EditorState.text()=project.composition.layers.first().text
    private fun apply(s: EditorState,edits: List<AnimatorEdit>)=EditorReducer.reduce(s,TextAnimatorEdits.action(1,edits))
    private fun withAnimator(): EditorState=apply(state(),listOf(TextAnimatorEdits.addAnimator(TextProperties(),ids)))

    @Test fun addAnimatorCreatesAWorkingRangeSelectorAndRespectsTheCap() {
        val s=withAnimator()
        val a=s.text().animators.single()
        assertEquals("Animator 1",a.name)
        assertEquals(1f,a.enabled.staticValue,0f)
        val selector=a.selectors.single()
        assertEquals(TextSelectorKind.RANGE,TextAnimatorEdits.kind(selector))
        assertEquals(0f,selector.start.staticValue,0f);assertEquals(100f,selector.end.staticValue,0f)
        assertNotEquals(a.id,selector.id)
        // Names stay unique; the 16th animator is the last.
        var t=s.text()
        repeat(15) { t=TextAnimatorActions.payload(layer(t),rate,TextAnimatorEdits.addAnimator(t,ids)) }
        assertEquals(16,t.animators.size);assertEquals(16,t.animators.map { it.name }.toSet().size)
        assertThrows(IllegalArgumentException::class.java) { TextAnimatorEdits.addAnimator(t,ids) }
        assertThrows(IllegalArgumentException::class.java) { TextAnimatorEdits.duplicateAnimator(t,t.animators[0].id,ids) }
    }

    @Test fun selectorsOfBothKindsAndTheEightSelectorCap() {
        var s=withAnimator();val id=s.text().animators[0].id
        s=apply(s,listOf(TextAnimatorEdits.addSelector(s.text(),id,TextSelectorKind.WIGGLY,ids)))
        val wiggly=s.text().animators[0].selectors[1]
        assertEquals(TextSelectorKind.WIGGLY,TextAnimatorEdits.kind(wiggly))
        assertEquals(TextRangeSelector(1).copy(id=wiggly.id,kind=AnimatableProperty1D(staticValue=1f)),wiggly)
        repeat(6) { s=apply(s,listOf(TextAnimatorEdits.addSelector(s.text(),id,TextSelectorKind.RANGE,ids))) }
        assertEquals(8,s.text().animators[0].selectors.size)
        assertThrows(IllegalArgumentException::class.java) { TextAnimatorEdits.addSelector(s.text(),id,TextSelectorKind.RANGE,ids) }
        assertThrows(IllegalArgumentException::class.java) { TextAnimatorEdits.duplicateSelector(s.text(),id,wiggly.id,ids) }
    }

    @Test fun newPropertiesUseTheBackendResetValuesAndAreNotDuplicated() {
        var s=withAnimator();val id=s.text().animators[0].id
        for(p in AnimatorProperty.entries) s=apply(s,listOf(requireNotNull(TextAnimatorEdits.addProperty(s.text(),id,p))))
        val a=s.text().animators[0]
        assertEquals(AnimatorProperty.entries,a.properties.map { it.property })
        for(o in a.properties) {
            val address=TextAnimatorEdits.property(id,o.property)
            // Reset on the new property is a no-op: the default is the backend's own reset value.
            assertEquals(s.text(),TextAnimatorActions.payload(layer(s.text()),rate,TextAnimatorEdits.reset(s.text(),address,0)))
        }
        assertEquals(100f,a.properties.first { it.property==AnimatorProperty.OPACITY }.scalar!!.staticValue,0f)
        assertEquals(100f,a.properties.first { it.property==AnimatorProperty.SCALE }.vector!!.staticX,0f)
        assertEquals(0xff000000.toInt(),a.properties.first { it.property==AnimatorProperty.STROKE_COLOUR }.colour!!.staticValue)
        assertNull(TextAnimatorEdits.addProperty(s.text(),id,AnimatorProperty.OPACITY))
    }

    @Test fun staticFieldsEditInPlaceAndKeyedFieldsGetAKeyAtTheSnappedPlayhead() {
        var s=withAnimator();val a=s.text().animators[0];val sel=a.selectors[0]
        s=apply(s,listOf(TextAnimatorEdits.addProperty(s.text(),a.id,AnimatorProperty.OPACITY)!!))
        val opacity=TextAnimatorEdits.property(a.id,AnimatorProperty.OPACITY)
        s=apply(s,listOf(TextAnimatorEdits.set(s.text(),opacity,500_000,0f)))
        assertEquals(0f,TextAnimatorEdits.track(s.text(),opacity)!!.staticValue,0f)
        assertFalse(TextAnimatorEdits.animated(s.text(),opacity))
        // Keyframe the selector offset, then edit at another (unsnapped) time: a second key, snapped to the frame grid.
        val offset=TextAnimatorEdits.selectorField(a.id,sel.id,SelectorField.OFFSET)
        s=apply(s,listOf(TextAnimatorEdits.enable(offset,0)))
        assertTrue(TextAnimatorEdits.animated(s.text(),offset))
        s=apply(s,listOf(TextAnimatorEdits.set(s.text(),offset,1_000_010,100f)))
        val keys=TextAnimatorEdits.track(s.text(),offset)!!.keyframes
        assertEquals(listOf(0L,1_000_000L),keys.map { it.timeUs })
        assertEquals(100f,keys.last().value,0f)
        // Colour, vector and seed fields go through the same paths.
        s=apply(s,listOf(TextAnimatorEdits.addProperty(s.text(),a.id,AnimatorProperty.FILL)!!,TextAnimatorEdits.addProperty(s.text(),a.id,AnimatorProperty.POSITION)!!))
        val fill=TextAnimatorEdits.property(a.id,AnimatorProperty.FILL);val position=TextAnimatorEdits.property(a.id,AnimatorProperty.POSITION)
        s=apply(s,listOf(TextAnimatorEdits.set(s.text(),fill,0,argb=0xffff0000.toInt())))
        s=apply(s,listOf(TextAnimatorEdits.set(s.text(),position,0,12f,-30f)))
        assertEquals(0xffff0000.toInt(),TextAnimatorEdits.colour(s.text(),fill,0))
        assertArrayEquals(floatArrayOf(12f,-30f),TextAnimatorEdits.vector(s.text(),position,0),0f)
        val seed=TextAnimatorEdits.seed(a.id,sel.id)
        s=apply(s,listOf(TextAnimatorEdits.set(s.text(),seed,0,seedValue=-1L)))
        assertEquals(-1L,TextAnimatorEdits.seedValue(s.text(),seed,0))
        assertEquals("18446744073709551615",java.lang.Long.toUnsignedString(TextAnimatorEdits.seedValue(s.text(),seed,0)))
    }

    @Test fun railToggleFreezeAndEasingUseTheExistingKeyOperations() {
        var s=withAnimator();val a=s.text().animators[0];val amount=TextAnimatorEdits.selectorField(a.id,a.selectors[0].id,SelectorField.AMOUNT)
        s=apply(s,listOf(TextAnimatorEdits.toggleKey(s.text(),amount,0,false)))
        assertEquals(listOf(0L),TextAnimatorEdits.keyTimes(layer(s.text()),amount))
        s=apply(s,listOf(TextAnimatorEdits.toggleKey(s.text(),amount,1_000_000,false)))
        assertEquals(listOf(0L,1_000_000L),TextAnimatorEdits.keyTimes(layer(s.text()),amount))
        s=apply(s,listOf(TextAnimatorEdits.easing(amount,1_000_000,EasingPreset.LINEAR)))
        assertEquals(EasingPreset.LINEAR,TextAnimatorEdits.track(s.text(),amount)!!.keyframes.last().easing)
        s=apply(s,listOf(TextAnimatorEdits.toggleKey(s.text(),amount,1_000_000,true)))
        assertEquals(listOf(0L),TextAnimatorEdits.keyTimes(layer(s.text()),amount))
        s=apply(s,listOf(TextAnimatorEdits.freeze(amount,0)))
        assertFalse(TextAnimatorEdits.animated(s.text(),amount))
        assertTrue(TextAnimatorEdits.keyTimes(layer(s.text()),amount).isEmpty())
        // Hold fields are hold; properties are not.
        assertTrue(TextAnimatorEdits.isHold(TextAnimatorEdits.selectorField(a.id,a.selectors[0].id,SelectorField.SHAPE)))
        assertTrue(TextAnimatorEdits.isHold(TextAnimatorEdits.grouping(a.id)))
        assertFalse(TextAnimatorEdits.isHold(TextAnimatorEdits.property(a.id,AnimatorProperty.POSITION)))
    }

    @Test fun duplicateReorderRenameAndDeleteAreSingleUndoEntries() {
        val editor=EditorViewModel();editor.dispatch(EditorAction.Load(withAnimator().project));editor.dispatch(EditorAction.Select(1))
        fun text()=editor.state.selectedLayer!!.text
        val first=text().animators[0]
        editor.dispatch(TextAnimatorEdits.action(1,listOf(TextAnimatorEdits.addProperty(text(),first.id,AnimatorProperty.ROTATION)!!)))
        editor.dispatch(TextAnimatorEdits.action(1,listOf(TextAnimatorEdits.addAnimator(text(),ids))))
        val before=editor.state.project
        editor.dispatch(TextAnimatorEdits.action(1,TextAnimatorEdits.duplicateAnimator(text(),first.id,ids)))
        val copy=text().animators[1]
        assertEquals("Animator 1 copy",copy.name)
        assertEquals(text().animators[0].properties,copy.properties)
        assertNotEquals(first.id,copy.id);assertNotEquals(first.selectors[0].id,copy.selectors[0].id)
        assertEquals(first.selectors[0].copy(id=copy.selectors[0].id),copy.selectors[0])
        assertEquals(listOf("Animator 1","Animator 1 copy","Animator 2"),text().animators.map { it.name })
        editor.undo();assertEquals(before,editor.state.project)
        editor.redo()
        assertNull(TextAnimatorEdits.moveAnimator(text(),first.id,-1))
        editor.dispatch(TextAnimatorEdits.action(1,listOf(TextAnimatorEdits.moveAnimator(text(),first.id,1)!!)))
        assertEquals(listOf("Animator 1 copy","Animator 1","Animator 2"),text().animators.map { it.name })
        editor.dispatch(TextAnimatorEdits.action(1,listOf(TextAnimatorEdits.rename(text(),first.id,"  Fade\nup\u0000 ")!!)))
        assertEquals("Fade up",text().animators[1].name)
        assertNull(TextAnimatorEdits.rename(text(),first.id,"   "))
        assertEquals(TextAnimatorEdits.NAME_LIMIT,TextAnimatorEdits.rename(text(),first.id,"x".repeat(300))!!.animator.name.length)
        editor.dispatch(TextAnimatorEdits.action(1,listOf(AnimatorEdit.Remove(first.id))))
        assertEquals(2,text().animators.size)
        editor.undo();assertEquals("Fade up",text().animators[1].name)
    }

    @Test fun aDragIsOneUndoEntryAndCancelLeavesNoTrace() {
        val editor=EditorViewModel();editor.dispatch(EditorAction.Load(withAnimator().project));editor.dispatch(EditorAction.Select(1))
        val base=editor.state.selectedLayer!!;val a=base.text.animators[0]
        val end=TextAnimatorEdits.selectorField(a.id,a.selectors[0].id,SelectorField.END)
        val committed=editor.state.project
        editor.beginGesture()
        for(v in listOf(90f,70f,40f)) editor.previewGesture(EditorAction.Batch(listOf(EditorAction.SetTextPayload(1,
            TextAnimatorEdits.payload(base,rate,listOf(TextAnimatorEdits.set(base.text,end,0,v)))))))
        assertEquals(40f,TextAnimatorEdits.value(editor.displayedState.selectedLayer!!.text,end,0),0f)
        editor.cancelGesture()
        assertEquals(committed,editor.state.project)
        editor.beginGesture()
        for(v in listOf(80f,60f)) editor.previewGesture(EditorAction.Batch(listOf(EditorAction.SetTextPayload(1,
            TextAnimatorEdits.payload(base,rate,listOf(TextAnimatorEdits.set(base.text,end,0,v)))))))
        editor.commitGesture()
        assertEquals(60f,TextAnimatorEdits.value(editor.state.selectedLayer!!.text,end,0),0f)
        editor.undo()
        assertEquals(committed,editor.state.project)
    }

    @Test fun payloadMatchesTheReducerForEveryStructuralEdit() {
        var s=withAnimator();val a=s.text().animators[0]
        val edits=listOf(TextAnimatorEdits.addSelector(s.text(),a.id,TextSelectorKind.WIGGLY,ids),
            TextAnimatorEdits.addProperty(s.text(),a.id,AnimatorProperty.TRACKING)!!)
        assertEquals(apply(s,edits).text(),TextAnimatorEdits.payload(layer(s.text()),rate,edits))
        s=apply(s,edits)
        val reorder=TextAnimatorEdits.moveSelector(s.text(),a.id,a.selectors[0].id,1)!!
        assertEquals(apply(s,listOf(reorder)).text(),TextAnimatorEdits.payload(layer(s.text()),rate,listOf(reorder)))
        assertNull(TextAnimatorEdits.moveSelector(s.text(),a.id,a.selectors[0].id,5))
    }

    @Test fun timelineMoveOfAFocusedAnimatorKeyMovesOnlyThatField() {
        var s=withAnimator();val a=s.text().animators[0];val sel=a.selectors[0]
        val start=TextAnimatorEdits.selectorField(a.id,sel.id,SelectorField.START);val end=TextAnimatorEdits.selectorField(a.id,sel.id,SelectorField.END)
        s=apply(s,listOf(TextAnimatorEdits.enable(start,0),TextAnimatorEdits.enable(end,0)))
        s=EditorReducer.reduce(s,EditorAction.EditTextAnimator(1,AnimatorEdit.Key(start,AnimatorKeyOperation.MOVE,0,500_000)))
        assertEquals(listOf(500_000L),TextAnimatorEdits.keyTimes(layer(s.text()),start))
        assertEquals(listOf(0L),TextAnimatorEdits.keyTimes(layer(s.text()),end))
        // Prev/Next follows the focused animator field when the inspector is open.
        val focused=s.project.composition.layers.first()
        assertEquals(500_000L,ContextualNavigation.findNext(s.project,focused,true,null,0,animatorTrack=start))
        assertEquals(0L,ContextualNavigation.findPrevious(s.project,focused,true,null,500_000,animatorTrack=end))
        // A stale (deleted) field falls back to the ordinary tiers.
        val stale=TextAnimatorEdits.selectorField(a.id,9999,SelectorField.START)
        assertEquals(4_000_000L,ContextualNavigation.findNext(s.project,focused,true,null,0,animatorTrack=stale))
    }
}
