package com.fluxx.android.render

import com.fluxx.android.model.CompositionLayer
import com.fluxx.android.model.LayerType
import com.fluxx.android.model.ProjectDocument

/**
 * TLP T2: which layers the renderer prepares and which media keys a visible layer still needs (pure, JVM tested).
 * A hidden layer behaves as if it did not exist: no preparation, no draw, no evaluation, no export; it stays in the
 * document, the timeline, undo and the saved file.
 */
object RenderLayers {
    /** Text layers to prepare (shaping, native layouts, glyph uploads, character catalogues): visible ones only. */
    fun preparedText(layers: List<CompositionLayer>): List<CompositionLayer> =
        layers.filter { it.type == LayerType.TEXT && it.visible }.sortedWith(compareBy<CompositionLayer> { it.zOrder }.thenBy { it.id })
    /** Decoder keys (video URIs) a visible layer uses. */
    fun wantedDecoders(project: ProjectDocument): Set<String> =
        project.composition.layers.filter { it.visible && it.type == LayerType.VIDEO }.mapNotNull { it.asset?.uri }.toSet()
    /** Raster keys (image URIs, solid colours) a visible layer uses, in the renderer's key format. */
    fun wantedRasters(project: ProjectDocument): Set<String> = project.composition.layers.filter { it.visible }.mapNotNull {
        when (it.type) { LayerType.SOLID -> "solid:${it.solidColorArgb}"; LayerType.IMAGE -> it.asset?.uri; else -> null }
    }.toSet()
}

/**
 * TLP T2: releases resources (decoders, GPU textures) that no visible layer has needed for [delayMs], so a quick
 * off / on toggle does not stutter. The clock is injectable for tests. A key that is wanted again before the delay keeps
 * its resource; a released key is simply re-acquired on the next frame that needs it.
 */
class DelayedRelease<K>(private val delayMs: Long = DEFAULT_DELAY_MS, private val clock: () -> Long) {
    private val unwantedSince = HashMap<K, Long>()
    /** The keys of [held] to release now, given the keys visible layers [wanted]. */
    fun due(held: Collection<K>, wanted: Set<K>): List<K> {
        val now = clock()
        unwantedSince.keys.retainAll(held.toSet())
        val out = ArrayList<K>()
        for (k in held) {
            if (k in wanted) { unwantedSince.remove(k); continue }
            val since = unwantedSince.getOrPut(k) { now }
            if (now - since >= delayMs) { out += k; unwantedSince.remove(k) }
        }
        return out
    }
    /** Keys waiting for their delay (tests and diagnostics). */
    val pending: Set<K> get() = unwantedSince.keys.toSet()
    companion object { const val DEFAULT_DELAY_MS = 3_000L }
}

/**
 * AUD2 B4: which decoder each video layer of one frame uses (pure, JVM tested). Assigned from the TOP layer down, so
 * when there are more distinct sources than [MAX_DECODERS]-style slots the layers left out are the lowest ones.
 * - Layers of the same URI whose source times are within [SHARE_US] share one decoder (it serves both from its cache).
 * - The same URI at different source times (one clip on several layers, other in-points) gets one decoder per time:
 *   a shared decoder seeked on every layer request (89 flushes in 20 s for 3 layers, AUD2 A5).
 * - Keys are "uri" for the first decoder of a URI and "uri#k" for the k-th; a null key = no decoder (not drawn).
 */
object DecoderKeys {
    const val SHARE_US = 50_000L
    /** Simultaneous preview / export decoders (A5: 1 1080p layer is smooth, 2 at ~21 fps, 4 at ~4 fps on the A16). */
    const val MAX_DECODERS = 4
    const val MAX_DECODERS_LOW_RAM = 2
    /** [layers] in draw order (bottom first): (layer id, uri, source time us). */
    fun assign(layers: List<Triple<Long, String, Long>>, slots: Int): Map<Long, String?> {
        val out = LinkedHashMap<Long, String?>()
        val given = ArrayList<Triple<String, String, Long>>()   // (key, uri, source time)
        for ((id, uri, src) in layers.asReversed()) {
            val shared = given.firstOrNull { it.second == uri && kotlin.math.abs(it.third - src) <= SHARE_US }
            out[id] = when {
                shared != null -> shared.first
                given.size >= slots -> null
                else -> { val k = given.count { it.second == uri }; val key = if (k == 0) uri else "$uri#$k"; given += Triple(key, uri, src); key }
            }
        }
        return out
    }
    fun uri(key: String): String = key.substringBefore('#')

    /**
     * MP1 A0: export never leaves a layer out. When every pooled decoder is still needed by this frame, the one to close
     * is the one whose NEXT use is farthest away (Belady): a decoder already drawn this frame is next needed in the next
     * frame (at its draw position), one not drawn yet is needed later in this frame. [pool] the open keys, [drawn] the keys
     * drawn so far this frame, [frameOrder] this frame's keys in draw order. With N keys through S slots this reopens
     * about N - S decoders per frame (slower, never a missing layer).
     */
    fun exportVictim(pool: List<String>, drawn: List<String>, frameOrder: List<String>): String {
        val n = frameOrder.size
        return pool.maxBy { k ->
            val at = frameOrder.indexOf(k)
            when { at < 0 -> Int.MAX_VALUE; k in drawn -> n + at; else -> at }
        }
    }
}
