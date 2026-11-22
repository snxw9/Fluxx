package com.fluxx.android.ui.inspector

import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import com.fluxx.android.ui.timeline.LayerKeyMarkers
import org.junit.Assert.*
import org.junit.Test

/** TEXT F3 Animate page: geometry, inventory (every backend field reachable), limits and transient state. */
class TextAnimateStateTest {
    /** The body the panel gives the page: sheet − frame (16) − header (40 × font scale); content width at 360dp. */
    private fun metrics(expanded: Boolean,scale: Float): AnimateMetrics {
        val sheet=InspectorSheetBudget.height(expanded,scale)
        return AnimateMetrics(TextInspectorBudget.bodyHeight(sheet,scale),TextInspectorBudget.contentWidth(360f),InspectorSheetBudget.compact(sheet))
    }

    @Test fun pageFitsBothSharedHeightsAtNormalAndLargeFontScales() {
        val expanded=metrics(true,1f);val standard=metrics(false,1f)
        assertEquals(212f,expanded.body,.01f);assertEquals(272f,standard.body,.01f)
        assertTrue(expanded.compact);assertFalse(standard.compact)
        assertEquals(40f,expanded.pitch,0f);assertEquals(48f,standard.pitch,0f)
        for(m in listOf(expanded,standard,metrics(true,1.3f),metrics(false,1.3f))) {
            assertTrue("fits at ${m.body}",m.fits)
            assertTrue(m.rows>=3)
            assertEquals(6,m.columns)
            assertTrue("cells are touch sized",m.cell>=TextInspectorBudget.MIN_TARGET && m.pitch>=TextInspectorBudget.MIN_TARGET)
            assertEquals(m.body,m.strip+m.switchRow+m.groupGap+m.rows*m.pitch+m.slack,.01f)
        }
        // 268: 40 strip + 40 switch + 8 gap + 3 × 40 rows = 208 of 212.
        assertEquals(3,expanded.rows);assertEquals(4f,expanded.slack,.01f)
        assertEquals(3,standard.rows)
    }

    @Test fun everyBackendPropertyAndSelectorFieldIsReachable() {
        // Properties: every AnimatorProperty exactly once in the add popover, each with a spec and a family icon.
        val grouped=AnimateFields.GROUPS.flatMap { it.second }
        assertEquals(AnimatorProperty.entries.toSet(),grouped.toSet());assertEquals(AnimatorProperty.entries.size,grouped.size)
        for(p in AnimatorProperty.entries) assertTrue(p.name,AnimateFields.property(p).icon in InspectorIcons.PATHS)
        // Selectors: each kind shows exactly the fields its engine reads, plus the seed (null), on the face or behind More.
        for(kind in TextSelectorKind.entries) {
            val tiles=AnimateFields.selectorTiles(kind)
            assertEquals(kind.name,TextAnimatorEdits.fields(kind).toSet()+setOf<SelectorField?>(null),tiles.toSet())
            assertEquals(tiles.size,tiles.toSet().size)
            for(m in listOf(metrics(true,1f),metrics(false,1f),metrics(true,1.3f))) {
                val face=SelectorFaces.layout(kind,m.selectorRows,m.columns)
                assertEquals(tiles,face.face+face.more)
                val stripRows=if(face.strip) 1 else 0
                val cells=face.face.size+if(face.more.isNotEmpty()) 1 else 0
                assertTrue("$kind fits ${m.selectorRows} rows",stripRows+(cells+m.columns-1)/m.columns<=m.selectorRows)
            }
        }
        // Wiggly fits its whole face at 268; Range keeps its strip plus the five primary tiles.
        val compact=metrics(true,1f)
        assertTrue(SelectorFaces.layout(TextSelectorKind.WIGGLY,compact.selectorRows,compact.columns).more.isEmpty())
        assertEquals(listOf(SelectorField.SHAPE,SelectorField.BASED_ON,SelectorField.MODE,SelectorField.AMOUNT,SelectorField.UNITS),
            SelectorFaces.layout(TextSelectorKind.RANGE,compact.selectorRows,compact.columns).face)
        // Every icon the page can show exists in the family.
        val icons=listOf("ANIMATOR","ADD","PROPERTIES","SELECTORS","RANGE","WIGGLY","OVERFLOW","NEXT","SEED","LINK","LINK_OFF","BACK")+
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

    @Test fun everyLimitIsAcceptedByTheModelAtItsHardCaps() {
        // Apply each field's hard min and max through the real reducer: the model validation must accept both.
        val a=TextAnimator(1,selectors=FrozenList.of(listOf(TextRangeSelector(2))),
            properties=FrozenList.of(AnimatorProperty.entries.map { TextAnimatorEdits.newProperty(it) }))
        val layer=CompositionLayer(1,LayerType.TEXT,timing=ClipTiming(durationUs=1_000_000),text=TextProperties(animators=FrozenList.of(listOf(a))))
        val rate=FrameRate()
        fun accept(address: AnimatorTrackAddress,limit: PropertyLimit,vector: Boolean) {
            for(v in listOf(limit.hardMin,limit.hardMax,limit.default)) {
                val text=TextAnimatorEdits.payload(layer,rate,listOf(TextAnimatorEdits.set(layer.text,address,0,v,if(vector) v else null)))
                assertNotNull(text)
            }
        }
        for(p in AnimatorProperty.entries) {
            val spec=AnimateFields.property(p);val limit=spec.limit ?: continue
            accept(TextAnimatorEdits.property(1,p),limit,p.kind==2)
        }
        for(f in SelectorField.entries) {
            val limit=AnimateFields.selector(f).limit ?: continue
            accept(TextAnimatorEdits.selectorField(1,2,f),limit,false)
        }
        // Slider ranges sit inside typed ranges inside hard caps (PropertyLimit invariants).
        assertTrue(PropertyLimits.SELECTOR_SIGNED.hardMin>=-100f && PropertyLimits.SELECTOR_SIGNED.hardMax<=100f)
        assertEquals(1000f,PropertyLimits.SELECTOR_WIGGLES.hardMax,0f)
        assertEquals(1f,PropertyLimits.ANIM_PERCENT.copy(default=100f).hard(1f),0f)
    }

    @Test fun pagingNeverHidesACell() {
        val items=(1..26).toList()
        for(capacity in listOf(6,12,18,30)) {
            val pages=AnimatePaging.pages(items,capacity)
            assertEquals(items,pages.flatten())
            assertTrue(pages.all { it.size+(if(pages.size>1) 1 else 0)<=capacity })
        }
    }

    @Test fun selectionAndFocusAreTransientAndResolveStaleIds() {
        val s1=TextRangeSelector(11);val s2=TextRangeSelector(12)
        val a=TextAnimator(1,selectors=FrozenList.of(listOf(s1,s2)),properties=FrozenList.of(listOf(TextAnimatorEdits.newProperty(AnimatorProperty.OPACITY))))
        val b=TextAnimator(2)
        val text=TextProperties(animators=FrozenList.of(listOf(a,b)))
        assertEquals(2L,AnimateSelection.animator(text,2)!!.id)
        assertEquals(1L,AnimateSelection.animator(text,99)!!.id)
        assertNull(AnimateSelection.animator(TextProperties(),1))
        assertEquals(12L,AnimateSelection.selector(a,12)!!.id);assertEquals(11L,AnimateSelection.selector(a,99)!!.id)
        assertNull(AnimateSelection.selector(b,11))
        val opacity=TextAnimatorEdits.property(1,AnimatorProperty.OPACITY)
        assertEquals(opacity,AnimateSelection.focus(text,a,opacity))
        assertNull(AnimateSelection.focus(text,b,opacity))
        assertNull(AnimateSelection.focus(text,a,TextAnimatorEdits.property(1,AnimatorProperty.BLUR)))
        // The saved token list round-trips every address shape.
        for(address in listOf(opacity,TextAnimatorEdits.grouping(1),TextAnimatorEdits.animatorEnabled(1),TextAnimatorEdits.seed(1,11),
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
        // All animator keys still appear (quiet) on the layer bar.
        assertTrue(LayerKeyMarkers.localTimes(layer).containsAll(listOf(0L,250_000L,500_000L)))
    }

    @Test fun captionsAndUnitCounts() {
        assertEquals("A",AnimateCaption.character(65f))
        assertEquals("U+0",AnimateCaption.character(0f))
        assertEquals("1844…",AnimateCaption.seed(-1L))
        assertEquals("42",AnimateCaption.seed(42L))
        assertEquals("12",AnimateCaption.vector(PropertyLimits.ANIM_OFFSET,12f,12f))
        val doc=TextSourceDocument("ab cd\nef")
        assertEquals(8,AnimateUnits.count(doc.text,doc.boundaries,0))
        assertEquals(6,AnimateUnits.count(doc.text,doc.boundaries,1))
        assertEquals(3,AnimateUnits.count(doc.text,doc.boundaries,2))
        assertEquals(2,AnimateUnits.count(doc.text,doc.boundaries,3))
    }
}
