package com.fluxx.android.render

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * SDF atlas resolution tiers. A glyph rasterised at 48 ppem gets visibly round corners once it is magnified a lot,
 * so each text layer picks a tier from its effective on-screen pixels per em (font size × run scale × per-glyph
 * animator scale × layer scale × output scale). The native renderer (core-engine text/sdf_tiers.h) mirrors these
 * constants: one shared R8 texture array holds every tier, keyed by (font, glyph, ppem).
 *
 * Memory cap (all tiers combined): [pages] × 4 MiB device atlas pages (6 = 24 MiB; 3 = 12 MiB on low-RAM devices),
 * a host staging buffer of the same size while uploading, and [cpuBytes] for generated tier bitmaps.
 */
object SdfAtlasTiers {
    val PPEM = intArrayOf(48, 96, 192, 384)
    const val BASE = 48
    const val BASE_SPREAD = 8
    /** FreeType's SDF spread limit; 384 ppem keeps 32 (half the relative distance range of the lower tiers). */
    const val MAX_SPREAD = 32
    /** A tier counts as sharp while magnified at most this much (≈1.5 px corner rounding). */
    const val MAX_MAGNIFICATION = 1.5f
    /** Tier switches need the size to move this far past a threshold, so scrubbing and animation never thrash. */
    const val HYSTERESIS = .15f
    /** Anti-aliasing allowance a tier must encode beyond stroke, softness and faux bold, in em. */
    const val AA_MARGIN_EM = .01f
    /** Faux bold outline offset in em (font_manager: 0.075 distance units at 48/8). */
    const val BOLD_EM = .025f
    const val PAGE_BYTES = 2048L * 2048L
    fun pages(lowRam: Boolean) = if (lowRam) 3 else 6
    fun capBytes(lowRam: Boolean) = pages(lowRam) * PAGE_BYTES
    fun cpuBytes(lowRam: Boolean) = if (lowRam) 8L * 1024 * 1024 else 16L * 1024 * 1024

    fun spread(ppem: Int) = min(BASE_SPREAD * ppem / BASE, MAX_SPREAD)
    /** Distance (em) a tier can encode outside the outline. */
    fun encodableEm(ppem: Int) = spread(ppem).toFloat() / ppem
    /** Scale for distances encoded for the base tier (stroke, softness, bold) when sampling [ppem]. */
    fun distanceScale(ppem: Int) = (BASE_SPREAD.toFloat() / BASE) / encodableEm(ppem)
    /** Largest px/em a tier serves sharply. */
    fun sharpLimit(ppem: Int) = ppem * MAX_MAGNIFICATION
    fun lower(ppem: Int): Int { var result = BASE; for (p in PPEM) if (p < ppem) result = p; return result }

    fun pixelsPerEm(size: Float, runScale: Float, glyphScale: Float, layerScale: Float, outputScale: Float): Float {
        val px = abs(size) * abs(runScale) * abs(glyphScale) * abs(layerScale) * abs(outputScale)
        return if (px.isFinite()) px else 0f
    }
    /** Highest tier whose spread still encodes [distanceEm] (stroke + softness + bold) plus the AA margin. */
    // Loops over the primitive table: these run every preview frame and must not allocate or box (TEXT FIX 9d).
    fun maxTierFor(distanceEm: Float): Int { var result = BASE; for (p in PPEM) if (encodableEm(p) >= distanceEm + AA_MARGIN_EM && p > result) result = p; return result }
    fun ideal(pxPerEm: Float): Int { for (p in PPEM) if (pxPerEm <= sharpLimit(p)) return p; return PPEM[PPEM.size - 1] }

    /** Tier for this frame with hysteresis around every threshold. [current] is the layer's previous tier. */
    fun select(pxPerEm: Float, current: Int?, distanceEm: Float = 0f): Int = selectTier(pxPerEm, current ?: NO_TIER, distanceEm)
    /** [select] without boxing: [current] is [NO_TIER] when the layer has no previous tier. */
    const val NO_TIER = 0
    fun selectTier(pxPerEm: Float, current: Int, distanceEm: Float = 0f): Int {
        if (!pxPerEm.isFinite() || pxPerEm <= 0f) return if (current in PPEM) current else BASE
        val cap = maxTierFor(distanceEm)
        val target = min(ideal(pxPerEm), cap)
        if (current !in PPEM || current > cap) return target
        return when {
            target > current -> if (pxPerEm > sharpLimit(current) * (1 + HYSTERESIS)) target else current
            target < current -> if (pxPerEm < sharpLimit(lower(current)) * (1 - HYSTERESIS)) target else current
            else -> current
        }
    }

    /** Highest tier ≤ [wanted] whose glyphs are all ready; the base tier is always available (fallback while loading). */
    fun resolve(wanted: Int, ready: (Int) -> Boolean): Int = PPEM.filter { it <= wanted }.lastOrNull { it == BASE || ready(it) } ?: BASE

    /** Average Latin glyph bitmap at a tier (0.75 em box plus the spread on each side). */
    fun glyphBytes(ppem: Int): Long { val side = (ppem * .75f + 2 * spread(ppem)).toLong(); return side * side }
    data class Request(val id: Long, val ppem: Int, val glyphs: Int)
    /**
     * Keeps the estimated atlas within [capBytes] (85% packing efficiency): the requests on the highest tier step
     * down one tier at a time until it fits. The base tier is never degraded further (native packing decides).
     */
    fun degrade(requests: List<Request>, capBytes: Long): Map<Long, Int> {
        val ppems = IntArray(requests.size) { requests[it].ppem }
        degradeInPlace(ppems, IntArray(requests.size) { requests[it].glyphs }, requests.size, capBytes)
        return requests.indices.associate { requests[it].id to ppems[it] }
    }
    /**
     * Allocation-free form of [degrade] for the per-frame render path (TEXT FIX 9d: the F1 zero-allocation window):
     * steps the highest tiers in [ppems] down in place until the estimate fits.
     */
    fun degradeInPlace(ppems: IntArray, glyphs: IntArray, count: Int, capBytes: Long) {
        while (true) {
            var bytes = 0L; var highest = BASE
            for (i in 0 until count) { bytes += glyphBytes(ppems[i]) * glyphs[i]; if (ppems[i] > highest) highest = ppems[i] }
            if (bytes <= capBytes * .85 || highest <= BASE) return
            for (i in 0 until count) if (ppems[i] == highest) ppems[i] = lower(highest)
        }
    }
    data class Entry(val ppem: Int, val lastUse: Long)
    /** Order in which cached glyphs leave the atlas under the cap: highest tier first, then least recently used. */
    fun evictionOrder(entries: List<Entry>) = entries.sortedWith(compareByDescending<Entry> { it.ppem }.thenBy { it.lastUse })

    /** Largest per-glyph animator scale in a prepared modifier buffer (96-byte records: a, b, c, d first). */
    fun maxGlyphScale(modifiers: ByteBuffer?, stride: Int = 96): Float {
        modifiers ?: return 1f
        var result = 0f
        var at = 0
        while (at + 16 <= modifiers.capacity()) {
            val a = modifiers.getFloat(at); val b = modifiers.getFloat(at + 4); val c = modifiers.getFloat(at + 8); val d = modifiers.getFloat(at + 12)
            val s = max(hypot(a, b), hypot(c, d))
            if (s.isFinite()) result = max(result, s)
            at += stride
        }
        return if (result > 0f) result else 1f
    }
    /** Largest animator half stroke + softness in a modifier buffer, in em (base-tier distance units / 3). */
    fun maxModifierDistanceEm(modifiers: ByteBuffer?, stride: Int = 96): Float {
        modifiers ?: return 0f
        var result = 0f
        var at = 0
        while (at + 72 <= modifiers.capacity()) {
            val d = modifiers.getFloat(at + 64) + modifiers.getFloat(at + 68)
            if (d.isFinite()) result = max(result, d / 3f)
            at += stride
        }
        return result
    }
    /** Encoded distance a layer needs: static half stroke (≤ 0.1 em), animator stroke/softness, faux bold. */
    fun distanceEm(strokeWidth: Float, size: Float, bold: Boolean, modifierDistanceEm: Float = 0f): Float {
        val half = if (size > 0f) min(strokeWidth * .5f, size * .10f) / size else 0f
        return max(half.coerceAtLeast(0f), modifierDistanceEm) + if (bold) BOLD_EM else 0f
    }
}
