package com.fluxx.android.ui.inspector

import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import com.fluxx.android.ui.timeline.LayerKeyMarkers
import org.junit.Assert.*
import org.junit.Test

/** TEXT F3.1 Animate tab: geometry, list structure, selector tiles, hidden fields, limits, reorder/delete undo, preference. */
class TextAnimateStateTest {
    /** The body the panel gives the page: sheet − frame (16) − header (40 × font scale); content width at 360dp. */
    private fun metrics(expanded: Boolean,scale: Float): AnimateMetrics {
        val sheet=InspectorSheetBudget.height(expanded,scale)
        return AnimateMetrics(TextInspectorBudget.bodyHeight(sheet,scale),TextInspectorBudget.contentWidth(360f),InspectorSheetBudget.compact(sheet))
    }
    private var serial=100L
    private val ids=TextAnimatorEdits.Ids { serial++ }
    private val rate=FrameRate(30,1)
    private fun layer(text: TextProperties)=CompositionLayer(1,LayerType.TEXT,timing=ClipTiming(durationUs=4_000_000),text=text)
    private fun put(text: TextProperties,edits: List<AnimatorEdit>)=TextAnimatorEdits.payload(layer(text),rate,edits)
    /** One animator holding a Range and a Wiggly selector and Position + Opacity + Character offset. */
    private fun sample(): TextProperties {
        var t=put(TextProperties(),listOf(TextAnimatorEdits.addAnimator(TextProperties(),ids)))
        val a=t.animators[0].id
        t=put(t,listOf(TextAnimatorEdits.addSelector(t,a,TextSelectorKind.WIGGLY,ids)))
        for(p in listOf(AnimatorProperty.POSITION,AnimatorProperty.OPACITY,AnimatorProperty.CHARACTER_OFFSET)) t=put(t,listOfNotNull(TextAnimatorEdits.addProperty(t,a,p)))
        return t
    }

    @Test fun pageFitsBothSharedHeightsAtNormalAndLargeFontScalesAndScrollsWhenOpen() {
        val expanded=metrics(true,1f);val standard=metrics(false,1f)
        assertEquals(212f,expanded.body,.01f);assertEquals(272f,standard.body,.01f)
        assertTrue(expanded.compact);assertFalse(standard.compact)
        assertEquals(40f,expanded.pitch,0f);assertEquals(48f,standard.pitch,0f)
        val t=sample();val a=t.animators[0]
        val open=AnimateBars().expandAnimator(a.id).expandSelector(a.selectors[0].id)
        for(m in listOf(expanded,standard,metrics(true,1.3f),metrics(false,1.3f))) {
            assertTrue("fits at ${m.body}",m.fits)
            assertEquals(6,m.columns)
            assertTrue("cells are touch sized",m.cell>=TextInspectorBudget.MIN_TARGET && m.pitch>=TextInspectorBudget.MIN_TARGET)
            // Collapsed: add row + one bar fit without scrolling; an expanded animator with a selector open scrolls.
            assertFalse(m.scrolls(AnimateRows.build(t,AnimateBars(),0,m.columns).size))
            assertTrue(m.scrolls(AnimateRows.build(t,open,0,m.columns).size))
        }
        assertEquals(5,expanded.visibleRows);assertEquals(5,standard.visibleRows)
    }

    @Test fun listStructureFollowsTheBarsAndOneAnimatorIsExpandedAtATime() {
        var t=sample();val first=t.animators[0]
        // TEXT F3.2: with no animators the "+ Animator" bar is the only (top) row.
        assertEquals(listOf("add"),AnimateRows.build(t.copy(animators=FrozenList.empty()),AnimateBars(),0,6).map { it.key })
        t=put(t,listOf(TextAnimatorEdits.addAnimator(t,ids)));val second=t.animators[1]
        // Collapsed: one bar per animator, then the add bar at the bottom.
        assertEquals(listOf("a:${first.id}","a:${second.id}","add"),AnimateRows.build(t,AnimateBars(),0,6).map { it.key })
        var bars=AnimateBars().toggleAnimator(first.id)
        val rows=AnimateRows.build(t,bars,0,6)
        // Property bar, its tiles in insertion order (no category groups), Selector row, two selector bars, Grouping.
        assertEquals(listOf("a:${first.id}","p:${first.id}","pt:${first.id}:0","sl:${first.id}","s:${first.selectors[0].id}",
            "s:${first.selectors[1].id}","g:${first.id}","a:${second.id}"),rows.map { it.key })
        // TEXT F3.3: "+ Animator" is hidden while a bar is expanded, and returns at the bottom once all are collapsed.
        assertFalse(rows.any { it == AnimateRow.Add })
        assertEquals("add",AnimateRows.build(t,bars.toggleAnimator(first.id),0,6).last().key)
        assertEquals(listOf("a:${first.id}","a:${second.id}","add"),AnimateRows.build(t,AnimateBars().toggleAnimator(99),0,6).map { it.key })
        assertEquals(first.properties.map { it.property },(rows[2] as AnimateRow.PropertyTiles).properties)
        assertEquals(first.properties.size,(rows[1] as AnimateRow.PropertyLabel).count)
        // Insertion order is kept even across categories (Opacity added before Position stays first).
        val reordered=first.copy(properties=FrozenList.of(first.properties.reversed()))
        val t2=t.copy(animators=FrozenList.of(listOf(reordered,second)))
        assertEquals(reordered.properties.map { it.property },(AnimateRows.build(t2,bars,0,6)[2] as AnimateRow.PropertyTiles).properties)
        // Expanding the second collapses the first; toggling again collapses all.
        bars=bars.toggleAnimator(second.id);assertEquals(second.id,bars.animator)
        assertEquals(1,AnimateRows.build(t,bars,0,6).count { it is AnimateRow.PropertyLabel })
        assertNull(bars.toggleAnimator(second.id).animator)
        // The Property bar collapses its tiles only.
        val closed=AnimateBars().expandAnimator(first.id).toggleGroup(first.id,AnimateRows.PROPERTIES)
        assertTrue(AnimateRows.build(t,closed,0,6).none { it is AnimateRow.PropertyTiles })
        assertFalse((AnimateRows.build(t,closed,0,6).first { it is AnimateRow.PropertyLabel } as AnimateRow.PropertyLabel).open)
        // Focus is separate from the bars: collapsing keeps it (and the rail) on the same field.
        val focus=TextAnimatorEdits.property(first.id,AnimatorProperty.OPACITY)
        assertEquals(focus,AnimateSelection.focus(t,focus))
        assertNull(AnimateSelection.focus(t,TextAnimatorEdits.property(first.id,AnimatorProperty.BLUR)))
        // Stale ids (undo, delete) drop out; saved bars round-trip.
        val stale=AnimateBars(99,setOf(98,first.selectors[0].id),setOf("99:Text","${first.id}:Text"),setOf(97))
        assertEquals(AnimateBars(null,setOf(first.selectors[0].id),setOf("${first.id}:Text"),emptySet()),stale.resolve(t))
        val saved=AnimateBars(first.id,setOf(1,2),setOf("${first.id}:Colour"),setOf(3))
        assertEquals(saved,AnimateBars.restore(saved.save()))
        // A new animator starts with one Range selector at 0–100 %.
        val s=second.selectors.single()
        assertEquals(TextSelectorKind.RANGE,TextAnimatorEdits.kind(s));assertEquals(0f,s.start.staticValue,0f);assertEquals(100f,s.end.staticValue,0f)
    }

    @Test fun rangeAndWigglySelectorTilesWithAdvancedGroups() {
        assertEquals(listOf(SelectorField.START,SelectorField.END,SelectorField.OFFSET,SelectorField.SHAPE),AnimateFields.primary(TextSelectorKind.RANGE))
        assertEquals(listOf(SelectorField.UNITS,SelectorField.BASED_ON,SelectorField.MODE,SelectorField.AMOUNT,SelectorField.SMOOTHNESS,SelectorField.EASE_HIGH,
            SelectorField.EASE_LOW,SelectorField.RANDOMIZE_ORDER,null),AnimateFields.advanced(TextSelectorKind.RANGE,true))
        assertEquals(listOf(SelectorField.MAX_AMOUNT,SelectorField.MIN_AMOUNT,SelectorField.WIGGLES_PER_SECOND,SelectorField.CORRELATION),AnimateFields.primary(TextSelectorKind.WIGGLY))
        assertEquals(listOf(SelectorField.BASED_ON,SelectorField.MODE,SelectorField.TEMPORAL_PHASE,SelectorField.SPATIAL_PHASE,SelectorField.LOCK_DIMENSIONS,null),
            AnimateFields.advanced(TextSelectorKind.WIGGLY,true))
        // Every field the engine reads for a kind is a tile, the bar eye (ENABLED) or deliberately hidden.
        for(kind in TextSelectorKind.entries) {
            val reachable=AnimateFields.selectorTiles(kind).filterNotNull().toSet()+SelectorField.ENABLED+AnimateFields.hidden(kind)
            assertEquals(kind.name,TextAnimatorEdits.fields(kind).toSet(),reachable)
            assertTrue(null in AnimateFields.selectorTiles(kind))
        }
        // Advanced is collapsed by default; Smoothness only for Square; hidden fields never appear in any state.
        val t=sample();val a=t.animators[0];val range=a.selectors[0];val wiggly=a.selectors[1]
        val base=AnimateBars().expandAnimator(a.id).expandSelector(range.id).expandSelector(wiggly.id)
        assertTrue(AnimateRows.build(t,base,0,6).none { it is AnimateRow.SelectorTiles && it.key.contains(":x:") })
        for(shape in SelectorShape.entries) {
            val shaped=put(t,listOf(TextAnimatorEdits.set(t,TextAnimatorEdits.selectorField(a.id,range.id,SelectorField.SHAPE),0,shape.ordinal.toFloat())))
            val rows=AnimateRows.build(shaped,base.toggleAdvanced(range.id).toggleAdvanced(wiggly.id),0,6)
            val rangeFields=rows.filterIsInstance<AnimateRow.SelectorTiles>().filter { it.selectorId==range.id }.flatMap { r -> r.cells.mapNotNull { it.field } }.toSet()
            val wigglyFields=rows.filterIsInstance<AnimateRow.SelectorTiles>().filter { it.selectorId==wiggly.id }.flatMap { r -> r.cells.mapNotNull { it.field } }.toSet()
            assertEquals(shape.name,shape==SelectorShape.SQUARE,SelectorField.SMOOTHNESS in rangeFields)
            for(hidden in listOf(SelectorField.MIN_AMOUNT,SelectorField.MAX_AMOUNT,SelectorField.ENABLED,SelectorField.KIND)) assertFalse(hidden in rangeFields)
            for(hidden in listOf(SelectorField.AMOUNT,SelectorField.ENABLED,SelectorField.KIND)) assertFalse(hidden in wigglyFields)
            // Changing shape never touches the stored smoothness.
            assertEquals(range.smoothness,shaped.animators[0].selectors[0].smoothness)
        }
    }

    @Test fun hiddenNonDefaultFieldsShowOneHintAndResetInOneEdit() {
        val t=sample();val a=t.animators[0];val range=a.selectors[0]
        assertTrue(AnimateHidden.active(range).isEmpty())
        val min=TextAnimatorEdits.selectorField(a.id,range.id,SelectorField.MIN_AMOUNT)
        val legacy=put(t,listOf(TextAnimatorEdits.set(t,min,0,20f)))
        val s=legacy.animators[0].selectors[0]
        assertEquals(listOf(SelectorField.MIN_AMOUNT),AnimateHidden.active(s))
        val bars=AnimateBars().expandAnimator(a.id).expandSelector(s.id).toggleAdvanced(s.id)
        assertEquals(1,AnimateRows.build(legacy,bars,0,6).filterIsInstance<AnimateRow.SelectorTiles>().flatMap { it.cells }.count { it.hint })
        assertEquals(0,AnimateRows.build(t,bars,0,6).filterIsInstance<AnimateRow.SelectorTiles>().flatMap { it.cells }.count { it.hint })
        val reset=put(legacy,listOf(AnimateHidden.reset(a.id,s)))
        assertTrue(AnimateHidden.active(reset.animators[0].selectors[0]).isEmpty())
    }

    @Test fun addPropertyPopoverGroupsInOrder() {
        val g=AnimateFields.GROUPS
        assertEquals(listOf("Transform","Colour","Text","Advanced"),g.map { it.name })
        assertEquals(listOf(AnimatorProperty.ANCHOR,AnimatorProperty.POSITION,AnimatorProperty.SCALE,AnimatorProperty.SKEW,AnimatorProperty.SKEW_AXIS,
            AnimatorProperty.ROTATION,AnimatorProperty.OPACITY),g[0].properties)
        assertEquals(listOf(AnimatorProperty.FILL,AnimatorProperty.STROKE_COLOUR,AnimatorProperty.FILL_OPACITY,AnimatorProperty.STROKE_OPACITY,
            AnimatorProperty.STROKE_WIDTH),g[1].properties)
        assertEquals("Colour shift",g[1].sub!!.name);assertTrue(g[1].sub!!.collapsed)
        assertEquals(listOf(AnimatorProperty.FILL_HUE,AnimatorProperty.FILL_SATURATION,AnimatorProperty.FILL_BRIGHTNESS,AnimatorProperty.STROKE_HUE,
            AnimatorProperty.STROKE_SATURATION,AnimatorProperty.STROKE_BRIGHTNESS),g[1].sub!!.properties)
        assertEquals(listOf(AnimatorProperty.TRACKING,AnimatorProperty.LINE_ANCHOR,AnimatorProperty.LINE_SPACING,AnimatorProperty.GROUPING_ALIGNMENT,
            AnimatorProperty.BLUR),g[2].properties)
        assertEquals(listOf(AnimatorProperty.CHARACTER_OFFSET,AnimatorProperty.CHARACTER_VALUE),g[3].properties);assertTrue(g[3].collapsed)
        val all=g.flatMap { it.all }
        assertEquals(AnimatorProperty.entries.toSet(),all.toSet());assertEquals(AnimatorProperty.entries.size,all.size)
        for(p in AnimatorProperty.entries) assertTrue(p.name,AnimateFields.property(p).icon in InspectorIcons.PATHS)
        assertEquals("Colour",AnimateFields.pageGroup(AnimatorProperty.STROKE_HUE));assertEquals("Text",AnimateFields.pageGroup(AnimatorProperty.GROUPING_ALIGNMENT))
        // Every icon the page can show exists in the family.
        val icons=listOf("ANIMATOR","ADD","RANGE","WIGGLY","SEED","LINK","LINK_OFF","BACK","EYE","EYE_OFF","GRIP","EXPANDED","DUPLICATE","DELETE","WARNING")+
            SelectorField.entries.flatMap { f->AnimateFields.selector(f).let { s->listOf(s.icon)+s.choices.map { it.icon } } }+
            AnimateFields.GROUPING.map { it.icon }+AnimateFields.ANCHOR_GROUPING.icon+AnimateFields.ANIMATOR_ENABLED.icon
        for(name in icons) assertTrue(name,name in InspectorIcons.PATHS)
    }

    @Test fun choiceListsMirrorTheBackendEnums() {
        assertEquals(SelectorShape.entries.map { it.ordinal.toFloat() },AnimateFields.SHAPES.map { it.value })
        assertEquals(SelectorBasedOn.entries.map { it.ordinal.toFloat() },AnimateFields.BASED_ON.map { it.value })
        assertEquals(SelectorMode.entries.map { it.ordinal.toFloat() },AnimateFields.MODES.map { it.value })
        assertEquals(AnchorGrouping.entries.map { it.ordinal.toFloat() },AnimateFields.GROUPING.map { it.value })
        assertEquals("SHAPE_TRIANGLE",AnimateFields.icon(AnimateFields.selector(SelectorField.SHAPE),3f))
    }

    /** The limits table of docs/checkpoints/TEXT_F3_1.md: slider min/max, typed (= hard) min/max. */
    @Test fun limitsTable() {
        fun row(l: PropertyLimit?)=l?.let { listOf(it.sliderMin,it.sliderMax,it.typedMin,it.typedMax,it.hardMin,it.hardMax) }
        val properties=mapOf(
            AnimatorProperty.ANCHOR to listOf(-500f,500f,-1e4f,1e4f,-1e4f,1e4f), AnimatorProperty.POSITION to listOf(-500f,500f,-1e4f,1e4f,-1e4f,1e4f),
            AnimatorProperty.SCALE to listOf(0f,400f,-2000f,2000f,-2000f,2000f),
            AnimatorProperty.SKEW to listOf(-85f,85f,-85f,85f,-85f,85f), AnimatorProperty.SKEW_AXIS to listOf(-180f,180f,-3600f,3600f,-3600f,3600f),
            AnimatorProperty.ROTATION to listOf(-360f,360f,-3600f,3600f,-3600f,3600f), AnimatorProperty.OPACITY to listOf(0f,100f,0f,100f,0f,100f),
            AnimatorProperty.FILL to null, AnimatorProperty.STROKE_COLOUR to null,
            AnimatorProperty.FILL_OPACITY to listOf(0f,100f,0f,100f,0f,100f), AnimatorProperty.STROKE_OPACITY to listOf(0f,100f,0f,100f,0f,100f),
            AnimatorProperty.STROKE_WIDTH to listOf(-50f,50f,-200f,200f,-200f,200f),
            AnimatorProperty.FILL_HUE to listOf(-180f,180f,-3600f,3600f,-3600f,3600f), AnimatorProperty.STROKE_HUE to listOf(-180f,180f,-3600f,3600f,-3600f,3600f),
            AnimatorProperty.FILL_SATURATION to listOf(-100f,100f,-100f,100f,-100f,100f), AnimatorProperty.FILL_BRIGHTNESS to listOf(-100f,100f,-100f,100f,-100f,100f),
            AnimatorProperty.STROKE_SATURATION to listOf(-100f,100f,-100f,100f,-100f,100f), AnimatorProperty.STROKE_BRIGHTNESS to listOf(-100f,100f,-100f,100f,-100f,100f),
            AnimatorProperty.TRACKING to listOf(-1000f,1000f,-1000f,1000f,-1000f,1000f), AnimatorProperty.LINE_ANCHOR to listOf(0f,100f,0f,100f,0f,100f),
            AnimatorProperty.LINE_SPACING to listOf(-100f,100f,-2000f,2000f,-2000f,2000f), AnimatorProperty.GROUPING_ALIGNMENT to listOf(-100f,100f,-1000f,1000f,-1000f,1000f),
            AnimatorProperty.BLUR to listOf(0f,50f,0f,250f,0f,250f),
            AnimatorProperty.CHARACTER_OFFSET to listOf(-100f,100f,-1114111f,1114111f,-1114111f,1114111f),
            AnimatorProperty.CHARACTER_VALUE to listOf(32f,591f,0f,1114111f,0f,1114111f))
        assertEquals(AnimatorProperty.entries.toSet(),properties.keys)
        for((p,expected) in properties) assertEquals(p.name,expected,row(AnimateFields.property(p).limit))
        val selectors=mapOf(
            SelectorField.START to listOf(0f,100f,-1e5f,1e5f,-1e5f,1e5f), SelectorField.END to listOf(0f,100f,-1e5f,1e5f,-1e5f,1e5f),
            SelectorField.OFFSET to listOf(-100f,100f,-1e5f,1e5f,-1e5f,1e5f), SelectorField.AMOUNT to listOf(-100f,100f,-100f,100f,-100f,100f),
            SelectorField.MIN_AMOUNT to listOf(-100f,100f,-100f,100f,-100f,100f), SelectorField.MAX_AMOUNT to listOf(-100f,100f,-100f,100f,-100f,100f),
            SelectorField.EASE_HIGH to listOf(-100f,100f,-100f,100f,-100f,100f), SelectorField.EASE_LOW to listOf(-100f,100f,-100f,100f,-100f,100f),
            SelectorField.SMOOTHNESS to listOf(0f,100f,0f,100f,0f,100f), SelectorField.WIGGLES_PER_SECOND to listOf(0f,10f,0f,1000f,0f,1000f),
            SelectorField.CORRELATION to listOf(0f,100f,0f,100f,0f,100f),
            SelectorField.TEMPORAL_PHASE to listOf(-360f,360f,-36000f,36000f,-36000f,36000f), SelectorField.SPATIAL_PHASE to listOf(-360f,360f,-36000f,36000f,-36000f,36000f))
        for((f,expected) in selectors) assertEquals(f.name,expected,row(AnimateFields.selector(f).limit))
        // Discrete fields carry no numeric limit.
        for(f in SelectorField.entries-selectors.keys) assertNull(f.name,AnimateFields.selector(f).limit)
        // Structural caps.
        assertEquals(16,TextAnimatorEdits.MAX_ANIMATORS);assertEquals(8,TextAnimatorEdits.MAX_SELECTORS);assertEquals(128,TextAnimatorEdits.NAME_LIMIT)
        assertEquals(256,TextAnimatorEdits.MAX_SEEDS)
        assertEquals(4096,TextCharacterPreparation.MAX_CODEPOINTS);assertEquals(16384,TextCharacterPreparation.MAX_CATALOGUE_GLYPHS)
        val a=TextAnimator(1)
        assertThrows(IllegalArgumentException::class.java) { a.copy(selectors=FrozenList.of((1L..9L).map { TextRangeSelector(it+10) })) }
        assertThrows(IllegalArgumentException::class.java) { a.copy(name="x".repeat(129)) }
        assertThrows(IllegalArgumentException::class.java) { a.copy(properties=FrozenList.of(List(2) { TextAnimatorEdits.newProperty(AnimatorProperty.BLUR) })) }
        assertThrows(IllegalArgumentException::class.java) { TextProperties(animators=FrozenList.of((1L..17L).map { TextAnimator(it) })) }
        // Every hard cap is accepted by the real reducer.
        val all=TextAnimator(1,selectors=FrozenList.of(listOf(TextRangeSelector(2))),properties=FrozenList.of(AnimatorProperty.entries.map { TextAnimatorEdits.newProperty(it) }))
        val text=TextProperties(animators=FrozenList.of(listOf(all)))
        for(p in AnimatorProperty.entries) { val l=AnimateFields.property(p).limit ?: continue
            for(v in listOf(l.hardMin,l.hardMax)) assertNotNull(put(text,listOf(TextAnimatorEdits.set(text,TextAnimatorEdits.property(1,p),0,v,if(p.kind==2) v else null)))) }
        for(f in selectors.keys) { val l=AnimateFields.selector(f).limit!!
            for(v in listOf(l.hardMin,l.hardMax)) assertNotNull(put(text,listOf(TextAnimatorEdits.set(text,TextAnimatorEdits.selectorField(1,2,f),0,v)))) }
    }

    @Test fun gripReorderTargetsAndCommitsOneUndoEntry() {
        // Slot centres captured at drag start; the target is the number of siblings above the pointer.
        val order=listOf(1L,2L,3L);val centres=mapOf(1L to 20f,2L to 60f,3L to 100f)
        assertEquals(0,AnimateReorder.target(order,3,centres,10f));assertEquals(1,AnimateReorder.target(order,3,centres,30f))
        assertEquals(2,AnimateReorder.target(order,1,centres,120f));assertEquals(1,AnimateReorder.target(order,1,centres,70f))
        // Previews always build on the drag-start base, so a drag through several targets ends as one Reorder.
        var t=sample();t=put(t,listOf(TextAnimatorEdits.addAnimator(t,ids)));t=put(t,listOf(TextAnimatorEdits.addAnimator(t,ids)))
        val ids3=t.animators.map { it.id }
        val previews=(listOf(1,2,0,2)).map { put(t,listOf(AnimatorEdit.Reorder(ids3[0],it))) }
        assertEquals(listOf(ids3[1],ids3[2],ids3[0]),previews.last().animators.map { it.id })
        val vm=EditorViewModel()
        vm.dispatch(EditorAction.Load(ProjectDocument(Composition(durationUs=4_000_000,frameRate=rate,layers=FrozenList.of(listOf(layer(t)))))))
        vm.dispatch(EditorAction.SetTextPayload(1,previews.last()))
        assertEquals(listOf(ids3[1],ids3[2],ids3[0]),vm.state.project.composition.layers[0].text.animators.map { it.id })
        vm.undo()
        assertEquals(ids3,vm.state.project.composition.layers[0].text.animators.map { it.id })
        // Selectors reorder the same way (order matters: modes combine top to bottom).
        val a=t.animators[0]
        assertEquals(listOf(a.selectors[1].id,a.selectors[0].id),put(t,listOf(AnimatorEdit.ReorderSelector(a.id,a.selectors[0].id,1))).animators[0].selectors.map { it.id })
    }

    @Test fun swipeDeleteAndUndoRestore() {
        val t=sample();val a=t.animators[0]
        val vm=EditorViewModel()
        vm.dispatch(EditorAction.Load(ProjectDocument(Composition(durationUs=4_000_000,frameRate=rate,layers=FrozenList.of(listOf(layer(t)))))))
        vm.dispatch(TextAnimatorEdits.action(1,listOf(AnimatorEdit.RemoveSelector(a.id,a.selectors[1].id))))
        assertEquals(1,vm.state.project.composition.layers[0].text.animators[0].selectors.size)
        vm.dispatch(TextAnimatorEdits.action(1,listOf(AnimatorEdit.Remove(a.id))))
        assertTrue(vm.state.project.composition.layers[0].text.animators.isEmpty())
        vm.undo();assertEquals(t.animators[0].copy(selectors=FrozenList.of(listOf(a.selectors[0]))),vm.state.project.composition.layers[0].text.animators[0])
        vm.undo();assertEquals(t,vm.state.project.composition.layers[0].text)
    }

    @Test fun duplicateKeepsSeedsKeysAndGroupingAsOneEdit() {
        var t=sample();val a=t.animators[0]
        val enabled=TextAnimatorEdits.animatorEnabled(a.id)
        t=put(t,listOf(AnimatorEdit.Key(enabled,AnimatorKeyOperation.ENABLE,0)))
        t=put(t,listOf(TextAnimatorEdits.set(t,TextAnimatorEdits.grouping(a.id),0,2f)))
        t=put(t,listOf(TextAnimatorEdits.set(t,TextAnimatorEdits.seed(a.id,a.selectors[1].id),0,seedValue=42L)))
        val edits=TextAnimatorEdits.duplicateAnimator(t,a.id,ids)
        assertEquals(1,(TextAnimatorEdits.action(1,edits) as? EditorAction.Batch)?.let { 1 } ?: 0) // one Batch = one undo entry
        val out=put(t,edits);val src=out.animators[0];val copy=out.animators[1]
        assertNotEquals(src.id,copy.id);assertEquals(src.selectors.map { it.seed },copy.selectors.map { it.seed })
        assertTrue(src.selectors.map { it.id }.intersect(copy.selectors.map { it.id }.toSet()).isEmpty())
        assertEquals(src.enabled,copy.enabled);assertEquals(src.anchorGrouping,copy.anchorGrouping);assertEquals(src.properties,copy.properties)
        val sel=TextAnimatorEdits.duplicateSelector(out,src.id,src.selectors[1].id,ids)
        val dup=put(out,sel).animators[0].selectors
        assertEquals(dup[1].seed,dup[2].seed);assertNotEquals(dup[1].id,dup[2].id)
        // The eye is the keyframeable Hold enabled flag: off keeps every key and value.
        val off=put(out,listOf(TextAnimatorEdits.set(out,enabled,0,0f)))
        assertEquals(src.properties,off.animators[0].properties);assertEquals(src.selectors,off.animators[0].selectors)
        assertEquals(0f,off.animators[0].enabled.hold(0),0f);assertTrue(off.animators[0].enabled.isAnimated)
    }

    @Test fun controlsPreferencePersistsAcrossInstances() {
        val map=HashMap<String,String>()
        val store=object: PreferenceStore { override fun get(key: String)=map[key]; override fun put(key: String,value: String) { map[key]=value } }
        val first=AnimateControlsPreference(store)
        assertEquals(AnimateControls.SLIDERS,first.value.value)
        first.set(AnimateControls.PAD);assertEquals(AnimateControls.PAD,first.value.value)
        assertEquals(AnimateControls.PAD,AnimateControlsPreference(store).value.value)   // "restart": a new instance reads it back
        map[AnimateControlsPreference.KEY]="garbage";assertEquals(AnimateControls.SLIDERS,AnimateControlsPreference(store).value.value)
        assertEquals(setOf(AnimatorProperty.POSITION,AnimatorProperty.ROTATION,AnimatorProperty.SCALE),AnimateControlsPreference.PROPERTIES)
        assertEquals("Controls: Sliders → Pad, dial, rulers",AnimateRail.controlsLabel(AnimateControls.SLIDERS))
        assertEquals("Controls: Pad, dial, rulers → Sliders",AnimateRail.controlsLabel(AnimateControls.PAD))
    }

    @Test fun savedFocusRoundTripsEveryAddressShape() {
        for(address in listOf(TextAnimatorEdits.property(1,AnimatorProperty.OPACITY),TextAnimatorEdits.grouping(1),TextAnimatorEdits.animatorEnabled(1),TextAnimatorEdits.seed(1,11),
            TextAnimatorEdits.selectorField(1,12,SelectorField.TEMPORAL_PHASE),null))
            assertEquals(address,AnimateAddressCodec.restore(AnimateAddressCodec.save(address)))
    }

    @Test fun timelineFocusesOnlyTheFocusedAnimatorFieldKeys() {
        val keyed=AnimatableProperty1D(true,0f,FrozenList.of(listOf(Keyframe1D(0,0f),Keyframe1D(500_000,100f))))
        val s=TextRangeSelector(11,offset=keyed)
        val a=TextAnimator(1,selectors=FrozenList.of(listOf(s)),properties=FrozenList.of(listOf(TextPropertyOverride(AnimatorProperty.OPACITY,
            scalar=AnimatableProperty1D(true,100f,FrozenList.of(listOf(Keyframe1D(250_000,0f))))))))
        val layer=CompositionLayer(1,LayerType.TEXT,timing=ClipTiming(startUs=1_000_000,durationUs=2_000_000),text=TextProperties(animators=FrozenList.of(listOf(a))))
        val offset=TextAnimatorEdits.selectorField(1,11,SelectorField.OFFSET)
        assertEquals(setOf(0L,500_000L),LayerKeyMarkers.focusedAnimatorClipTimes(layer,offset,0))
        assertEquals(setOf(250_000L),LayerKeyMarkers.focusedAnimatorClipTimes(layer,TextAnimatorEdits.property(1,AnimatorProperty.OPACITY),0))
        assertTrue(LayerKeyMarkers.focusedAnimatorClipTimes(layer,null,0).isEmpty())
        assertTrue(LayerKeyMarkers.focusedAnimatorClipTimes(layer,TextAnimatorEdits.selectorField(1,99,SelectorField.OFFSET),0).isEmpty())
        assertTrue(LayerKeyMarkers.localTimes(layer).containsAll(listOf(0L,250_000L,500_000L)))
    }

    @Test fun captions() {
        assertEquals("A",AnimateCaption.character(65f))
        assertEquals("U+0",AnimateCaption.character(0f))
        assertEquals("1844…",AnimateCaption.seed(-1L))
        assertEquals("42",AnimateCaption.seed(42L))
        assertEquals("12",AnimateCaption.vector(PropertyLimits.ANIM_OFFSET,12f,12f))
        assertEquals("1x30.0",formatRotation(390f))  // the dial's turns notation, reused as the rotation slider read-out
    }
}
