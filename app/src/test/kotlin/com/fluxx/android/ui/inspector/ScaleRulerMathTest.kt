package com.fluxx.android.ui.inspector

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** Log-scale ruler mapping: round trip, range ends, 100% snap, base-relative drag, detents and reach. */
class ScaleRulerMathTest {
    private val m=ScaleRulerMath

    @Test fun positionAndValueRoundTripAcrossTheRange() {
        for(v in listOf(.0005f,.01f,.25f,.5f,1f,1.75f,2f,10f,50f,100f)) assertEquals(v,m.valueAt(m.position(v)),v*1e-4f)
        assertEquals(0f,m.position(1f),0f);assertEquals(1f,m.position(2f),1e-6f);assertEquals(-1f,m.position(.5f),1e-6f)
    }
    @Test fun rangeEndsPinTheNeedle() {
        assertEquals(m.MAX,m.valueAt(m.position(500f)),1e-3f)
        assertEquals(m.MIN,m.valueAt(m.position(0f)),1e-7f)
        assertEquals(m.MAX,m.drag(1f,1000f),1e-3f);assertEquals(m.MIN,m.drag(1f,-1000f),1e-7f)
        // A typed 500x places the needle at the end; dragging continues from the ruler range.
        assertEquals(m.MAX,m.drag(500f,0f),1e-3f)
        assertTrue(m.RANGE_DOUBLINGS>17f && m.RANGE_DOUBLINGS<18f)
    }
    @Test fun dragIsRelativeToTheValueAtDragStart() {
        // scale = base · 2^(dx / 96dp)
        assertEquals(.6f,m.drag(.3f,1f),1e-5f);assertEquals(1.5f,m.drag(3f,-1f),1e-5f)
        assertEquals(1f,m.travel(m.DP_PER_DOUBLING,0f),1e-6f)
        assertEquals(4f,m.drag(1f,m.travel(2*m.DP_PER_DOUBLING,0f)),1e-4f)
        // Negative (typed) scale keeps its sign; the ruler moves its magnitude.
        assertEquals(-2f,m.drag(-1f,1f),1e-5f)
    }
    @Test fun softSnapAt100PercentCanBeDraggedThrough() {
        assertEquals(1f,m.drag(1f,.02f),0f);assertEquals(1f,m.drag(1.01f,-.02f),0f)
        assertNotEquals(1f,m.drag(1f,.05f));assertTrue(m.drag(1f,.05f)>1f)
        assertTrue(m.drag(.9f,.2f)>1f)
    }
    @Test fun detentsAtPowersOfTwoAndAStrongOneAt100() {
        assertEquals(2,m.detent(.9f,1.1f));assertEquals(2,m.detent(1.2f,1f))
        assertEquals(1,m.detent(1.9f,2.1f));assertEquals(1,m.detent(.3f,.2f))
        assertEquals(0,m.detent(1.2f,1.3f));assertEquals(0,m.detent(1f,1f))
    }
    @Test fun velocityGainIsMildAndBounded() {
        assertEquals(1f,m.gain(0f),0f);assertEquals(1f,m.gain(m.GAIN_START),0f)
        assertEquals(m.GAIN_MAX,m.gain(1e6f),0f);assertEquals(m.gain(-1500f),m.gain(1500f),0f)
        assertTrue(m.GAIN_MAX<=3f)
    }
    @Test fun tenThousandPercentIsReachableInOneOrTwoDrags() {
        // A 290dp strip: one slow full-width drag from 100% gives ~3 doublings; two quick drags reach 10000%.
        val slow=m.travel(290f,0f);assertTrue(slow>2.9f && slow<3.1f)
        val fast=m.travel(290f,m.GAIN_FULL)
        assertEquals(m.MAX,m.drag(m.drag(1f,fast),fast),1e-3f)
    }
    @Test fun inertiaDecaysAndEnds() {
        val v=2000f
        val a=m.inertiaStep(v,100f);val b=m.inertiaStep(v,300f);val c=m.inertiaStep(v,1200f);val d=m.inertiaStep(v,1e6f)
        assertTrue(a in 0f..b && b<c && c<=d);assertEquals(d,c,d*.03f)
        assertEquals(0f,m.inertiaStep(v,0f),0f);assertEquals(-a,m.inertiaStep(-v,100f),1e-6f)
    }
    @Test fun linkedScalingKeepsTheRatioWithinTheHardCap() {
        assertEquals(1f,m.linkedY(2f,1f,2f*1f),1e-6f);assertEquals(3f,m.linkedY(1f,1.5f,2f),1e-6f)
        assertEquals(500f,m.linkedY(.001f,10f,100f),0f)
        assertEquals(2f,m.linkedY(0f,5f,2f),0f)
    }
    @Test fun ticksAreBuiltOnceWithEmphasised100AndLabelledMajors() {
        val t=m.TICKS
        assertTrue(t.first().percent>=m.MIN_PERCENT*.999f && t.last().percent<=m.MAX_PERCENT*1.001f)
        assertEquals(1,t.count {abs(it.percent-100f)<.001f})
        val labels=t.mapNotNull {it.label}
        for(l in listOf("10","20","50","100","200","500","1k","2k","5k","10k","0.05","0.1")) assertTrue(l,l in labels)
        assertTrue(t.zipWithNext().all {it.first.position<it.second.position})
        assertEquals("175%",m.readout(1.75f));assertEquals("10000%",m.readout(100f));assertEquals("0.05%",m.readout(.0005f))
        assertEquals(0f,m.fade(1f),0f);assertEquals(1f,m.fade(0f),0f)
    }
}
