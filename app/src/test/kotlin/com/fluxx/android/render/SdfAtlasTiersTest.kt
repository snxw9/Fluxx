package com.fluxx.android.render

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** SDF atlas tiers: selection, hysteresis, spread scaling, memory cap, eviction order and fallback while loading. */
class SdfAtlasTiersTest {
    private val t=SdfAtlasTiers

    @Test fun tiersAndSpreadScaling() {
        assertArrayEquals(intArrayOf(48,96,192,384),t.PPEM)
        assertEquals(listOf(8,16,32,32),t.PPEM.map {t.spread(it)})
        // Edges look identical across tiers: base-tier distances scale by the spread/ppem ratio.
        assertEquals(listOf(1f,1f,1f,2f),t.PPEM.map {t.distanceScale(it)})
        assertEquals(1f/6,t.encodableEm(96),1e-6f);assertEquals(1f/12,t.encodableEm(384),1e-6f)
    }
    @Test fun selectionFollowsPixelsPerEm() {
        assertEquals(48,t.select(40f,null));assertEquals(48,t.select(72f,null))
        assertEquals(96,t.select(100f,null));assertEquals(192,t.select(250f,null))
        assertEquals(384,t.select(300f,null));assertEquals(384,t.select(1000f,null));assertEquals(384,t.select(50_000f,null))
        // 100px text at 50x layer scale on a half-resolution preview.
        assertEquals(2500f,t.pixelsPerEm(100f,1f,1f,50f,.5f),1e-3f)
        assertEquals(0f,t.pixelsPerEm(Float.NaN,1f,1f,1f,1f),0f)
        assertEquals(48,t.select(0f,null));assertEquals(192,t.select(Float.POSITIVE_INFINITY,192))
    }
    @Test fun hysteresisPreventsThrashAroundThresholds() {
        // Up: 72px is 48's limit; staying at 48 until > 72 × 1.15 = 82.8.
        assertEquals(48,t.select(80f,48));assertEquals(96,t.select(83f,48))
        // Down: from 96, drop only below 72 × 0.85 = 61.2.
        assertEquals(96,t.select(65f,96));assertEquals(48,t.select(60f,96))
        // Oscillating ±10% around a threshold never switches.
        var tier=96;val seen=mutableSetOf<Int>()
        for(i in 0 until 200) {tier=t.select(if(i%2==0) 140f else 150f,tier);seen+=tier}
        assertEquals(setOf(96),seen)
        // A big jump still moves straight to the right tier.
        assertEquals(384,t.select(900f,48));assertEquals(48,t.select(20f,384))
    }
    @Test fun strokeAndBoldCapTheTierToAnEncodableSpread() {
        assertEquals(384,t.maxTierFor(0f));assertEquals(384,t.maxTierFor(t.BOLD_EM))
        // 0.1 em half stroke cannot be encoded by 384 ppem / 32 spread (0.083 em): stays at 192.
        val distance=t.distanceEm(strokeWidth=20f,size=100f,bold=false)
        assertEquals(.1f,distance,1e-6f);assertEquals(192,t.maxTierFor(distance));assertEquals(192,t.select(1000f,null,distance))
        assertEquals(192,t.select(1000f,384,distance))
        assertEquals(.1f+t.BOLD_EM,t.distanceEm(500f,100f,true),1e-6f)
    }
    @Test fun memoryCapDegradesTheHighestTiersFirst() {
        assertEquals(24L*1024*1024,t.capBytes(false));assertEquals(12L*1024*1024,t.capBytes(true))
        val requests=listOf(SdfAtlasTiers.Request(1,384,80),SdfAtlasTiers.Request(2,192,60),SdfAtlasTiers.Request(3,48,200))
        val granted=t.degrade(requests,t.capBytes(true))
        assertTrue(granted.getValue(1)<384)
        assertEquals(48,granted.getValue(3))
        assertTrue(requests.sumOf {t.glyphBytes(granted.getValue(it.id))*it.glyphs}<=t.capBytes(true)*.85)
        // Never raises a tier, never goes below the base, and leaves small sets alone.
        assertEquals(mapOf(1L to 384,2L to 96),t.degrade(listOf(SdfAtlasTiers.Request(1,384,10),SdfAtlasTiers.Request(2,96,10)),t.capBytes(false)))
        assertEquals(mapOf(1L to 48),t.degrade(listOf(SdfAtlasTiers.Request(1,384,100_000)),t.capBytes(true)))
    }
    @Test fun evictionOrderIsHighestTierThenLeastRecentlyUsed() {
        val order=t.evictionOrder(listOf(SdfAtlasTiers.Entry(48,1),SdfAtlasTiers.Entry(384,9),SdfAtlasTiers.Entry(192,2),SdfAtlasTiers.Entry(384,3),SdfAtlasTiers.Entry(96,0)))
        assertEquals(listOf(SdfAtlasTiers.Entry(384,3),SdfAtlasTiers.Entry(384,9),SdfAtlasTiers.Entry(192,2),SdfAtlasTiers.Entry(96,0),SdfAtlasTiers.Entry(48,1)),order)
    }
    @Test fun previousTierIsUsedUntilTheSharperOneIsReady() {
        assertEquals(48,t.resolve(384) {false})
        assertEquals(96,t.resolve(384) {it==96})
        assertEquals(192,t.resolve(384) {it<=192})
        assertEquals(384,t.resolve(384) {true})
        assertEquals(96,t.resolve(96) {true});assertEquals(48,t.resolve(48) {false})
    }
    @Test fun perGlyphAnimatorScalePicksTheTier() {
        val buffer=ByteBuffer.allocateDirect(96*2).order(ByteOrder.nativeOrder())
        buffer.putFloat(0,1f);buffer.putFloat(12,1f)
        buffer.putFloat(96,3f);buffer.putFloat(96+12,4f);buffer.putFloat(96+64,.3f);buffer.putFloat(96+68,.15f)
        assertEquals(4f,t.maxGlyphScale(buffer),0f)
        assertEquals(.15f,t.maxModifierDistanceEm(buffer),1e-6f)
        assertEquals(1f,t.maxGlyphScale(null),0f)
        // 60px text whose largest glyph is scaled 4x needs the 192 tier even though the base size alone would not.
        assertEquals(48,t.select(t.pixelsPerEm(60f,1f,1f,1f,1f),null))
        assertEquals(192,t.select(t.pixelsPerEm(60f,1f,t.maxGlyphScale(buffer),1f,1f),null))
    }
}
