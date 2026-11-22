package com.fluxx.android.ui.inspector

import org.junit.Assert.*
import org.junit.Test

/** The shared limits table, ladder snapping, typed entry and popover placement. JVM only. */
class InspectorLimitsTest {
    private fun check(l: PropertyLimit,slider: Pair<Float,Float>,typed: Pair<Float,Float>,hard: Pair<Float,Float>) {
        assertEquals(l.name,slider.first,l.sliderMin,0f);assertEquals(l.name,slider.second,l.sliderMax,0f)
        assertEquals(l.name,typed.first,l.typedMin,0f);assertEquals(l.name,typed.second,l.typedMax,0f)
        assertEquals(l.name,hard.first,l.hardMin,0f);assertEquals(l.name,hard.second,l.hardMax,0f)
        // Beyond the hard cap clamps; typed values clamp to the typed range; the slider pins to its ends.
        assertEquals(hard.second,l.hard(hard.second*10+1),0f);assertEquals(hard.first,l.hard(-1e9f),0f)
        assertEquals(typed.second,l.typed(1e9f),0f);assertEquals(typed.first,l.typed(-1e9f),0f)
        assertEquals(1f,l.fraction(1e9f),0f);assertEquals(0f,l.fraction(-1e9f),0f)
        assertFalse(l.hard(Float.NaN).isNaN())
    }
    @Test fun everyRowOfTheLimitsTable() {
        check(PropertyLimits.FONT_SIZE,8f to 300f,8f to 1000f,8f to 1000f)
        check(PropertyLimits.TRACKING,-1000f to 1000f,-1000f to 1000f,-1000f to 1000f)
        check(PropertyLimits.MANUAL_KERNING,-100f to 100f,-500f to 500f,-1000f to 1000f)
        check(PropertyLimits.LEADING,0f to 300f,0f to 2000f,-2000f to 2000f)
        check(PropertyLimits.GLYPH_SCALE,10f to 400f,1f to 1000f,1f to 1000f)
        check(PropertyLimits.BASELINE,-100f to 100f,-1000f to 1000f,-1000f to 1000f)
        check(PropertyLimits.STROKE_WIDTH,0f to 50f,0f to 200f,0f to 200f)
        check(PropertyLimits.PARAGRAPH,0f to 100f,0f to 500f,0f to 500f)
        check(PropertyLimits.OPACITY,0f to 100f,0f to 100f,0f to 100f)
        check(PropertyLimits.LAYER_SCALE,.05f to 10000f,-50000f to 50000f,-50000f to 50000f)
        check(PropertyLimits.ROTATION,-360f to 360f,-36000f to 36000f,-36000f to 36000f)
        val position=PropertyLimits.position(1920)
        assertEquals(-19200f,position.typedMin,0f);assertEquals(19200f,position.hardMax,0f)
        // Selection scope maps to the run-relative limits.
        assertSame(PropertyLimits.RELATIVE_SIZE,PropertyLimits.forControl(TextControl.SIZE,true))
        assertSame(PropertyLimits.STROKE_DELTA,PropertyLimits.forControl(TextControl.STROKE_WIDTH,true))
        assertSame(PropertyLimits.INDENT_FIRST,PropertyLimits.forControl(TextControl.INDENT_FIRST,false))
        assertNull(PropertyLimits.forControl(TextControl.BOLD,false))
    }
    @Test fun fontSizeLadderSnapsAndRoundTrips() {
        val l=PropertyLimits.FONT_SIZE
        assertEquals(23,PropertyLimits.SIZE_LADDER.size)
        assertEquals(listOf(8f,10f,12f,14f,16f,18f,20f,24f,28f,32f,36f,42f,48f,56f,64f,72f,90f,108f,120f,144f,180f,240f,300f),PropertyLimits.SIZE_LADDER)
        for(rung in PropertyLimits.SIZE_LADDER) assertEquals(rung,l.valueAt(l.fraction(rung)),0f)
        // Any slider position lands on a rung.
        for(i in 0..200) assertTrue(l.valueAt(i/200f) in PropertyLimits.SIZE_LADDER)
        // Off-ladder typed values sit between rungs without snapping the stored value.
        assertTrue(l.fraction(70f)>l.fraction(64f) && l.fraction(70f)<l.fraction(72f))
        assertEquals(1f,l.fraction(1000f),0f)
        // Each rung is its own detent: crossing one is one haptic tick.
        assertNotEquals(l.detent(64f),l.detent(72f));assertEquals(l.detent(72f),l.detent(80f))
        // Leading follows the size ladder (plus zero).
        assertEquals(listOf(0f)+PropertyLimits.SIZE_LADDER,PropertyLimits.LEADING.ladder)
    }
    @Test fun continuousSlidersSnapSoftlyToZeroAndDefaultWithDetents() {
        val t=PropertyLimits.TRACKING
        assertEquals(0f,t.valueAt(.5f+PropertyLimit.SNAP_FRACTION/2),0f)
        assertEquals(-1000f,t.valueAt(0f),0f);assertEquals(1000f,t.valueAt(1f),0f)
        assertTrue(t.crossesAnchor(-3f,4f));assertFalse(t.crossesAnchor(3f,4f))
        assertNotEquals(t.detent(99f),t.detent(101f));assertEquals(t.detent(10f),t.detent(90f))
        val g=PropertyLimits.GLYPH_SCALE
        assertEquals(100f,g.valueAt(g.fraction(101f)),0f)
        assertEquals(.5f,PropertyLimits.STROKE_WIDTH.round(.6f),0f)
    }
    @Test fun typedEntryParsesClampsAndRejects() {
        val l=PropertyLimits.FONT_SIZE
        assertEquals(72f,PropertyLimits.parseTyped(l,"72")!!,0f)
        assertEquals(12.5f,PropertyLimits.parseTyped(l,"12,5 px")!!,0f)
        assertEquals(1000f,PropertyLimits.parseTyped(l,"5000")!!,0f)
        assertEquals(8f,PropertyLimits.parseTyped(l,"1")!!,0f)
        for(bad in listOf("","abc","NaN","Infinity","1e999")) assertNull(bad,PropertyLimits.parseTyped(l,bad))
        assertEquals(-1000f,PropertyLimits.parseTyped(PropertyLimits.TRACKING,"-9999")!!,0f)
        assertEquals(-500f,PropertyLimits.parseTyped(PropertyLimits.MANUAL_KERNING,"-9999")!!,0f)
        assertEquals(175f,PropertyLimits.parseTyped(PropertyLimits.LAYER_SCALE,"175%")!!,0f)
        assertEquals("72",PropertyLimits.format(l,72f));assertEquals("2.5",PropertyLimits.format(PropertyLimits.STROKE_WIDTH,2.5f))
    }
    @Test fun typedEntryCommitsOrCancelsExactlyOnce() {
        val events=mutableListOf<String>()
        fun entry()=TypedEntry({events+="commit:$it"},{events+="cancel"})
        // IME Done commits once; disposal afterwards does nothing.
        entry().also {it.commit("12");it.dispose("13")};assertEquals(listOf("commit:12"),events);events.clear()
        // Tap outside (popover disposed) commits the current text.
        entry().also {it.keyboard(true);it.dispose("40")};assertEquals(listOf("commit:40"),events);events.clear()
        // Escape cancels; nothing commits later.
        entry().also {it.cancel();it.commit("1");it.dispose("2")};assertEquals(listOf("cancel"),events);events.clear()
        // Back hides the keyboard after it was shown: cancel, and closing the popover then commits nothing.
        entry().also {it.keyboard(false);it.keyboard(true);it.keyboard(false);it.dispose("9")};assertEquals(listOf("cancel"),events);events.clear()
        // The keyboard not yet shown is not a cancellation.
        entry().also {it.keyboard(false);it.commit("5")};assertEquals(listOf("commit:5"),events)
    }
    @Test fun popoverSitsAboveItsAnchorAndStaysOnScreen() {
        val m=8;val w=1080;val h=2400
        // Room above: directly above, centred on the tile.
        var p=PopoverPlacer.place(500,1800,560,1860,300,150,w,h,h,m,4)
        assertTrue(p.above);assertEquals(1800-4-150,p.y);assertEquals(530-150,p.x);assertEquals(.5f,p.originX,1e-3f)
        // No room above: below.
        p=PopoverPlacer.place(500,20,560,80,300,150,w,h,h,m,4)
        assertFalse(p.above);assertEquals(84,p.y)
        // Left and right edges clamp with 8dp margins; the transform origin follows the anchor.
        p=PopoverPlacer.place(0,1800,40,1840,300,150,w,h,h,m)
        assertEquals(m,p.x);assertTrue(p.originX<.2f)
        p=PopoverPlacer.place(1040,1800,1080,1840,300,150,w,h,h,m)
        assertEquals(w-300-m,p.x);assertTrue(p.originX>.8f)
        // Wider than the window: pinned to the left margin.
        assertEquals(m,PopoverPlacer.place(500,1800,560,1860,2000,150,w,h,h,m).x)
        // Keyboard showing (top at 1500): a popover anchored under it lifts above the keyboard top.
        p=PopoverPlacer.place(500,1900,560,1960,300,150,w,h,1500,m,4)
        assertTrue(p.y+150<=1500-m);assertTrue(p.y>=m)
        // Anchor above the keyboard with room: unchanged.
        p=PopoverPlacer.place(500,1000,560,1060,300,150,w,h,1500,m,4)
        assertEquals(1000-4-150,p.y)
        // Never off the top even in a tiny window.
        assertTrue(PopoverPlacer.place(0,0,10,10,100,500,200,300,300,m).y>=0)
    }
}
