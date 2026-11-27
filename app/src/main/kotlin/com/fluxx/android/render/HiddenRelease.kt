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
 * MP1.2: decoders of clips that are over (pure, JVM tested). A decoder is IDLE when no visible video layer of its source is
 * active at the frame time or starts within [Culling.WARM_LOOKAHEAD_US] after it (a clip that ended, or one not reached).
 * Before MP1.2 such a decoder stayed open until a new source needed its slot: a sequence of different files kept four
 * decoders (~1 GB of codec buffers at 1440p on the A16) and lmkd killed the app.
 * - Playing: every idle decoder closes at once (a clip that ended is not coming back), before the next clip's opens.
 * - Paused / scrubbing: the most recently used [KEEP_IDLE_PAUSED] idle decoder waits for the 3 s delayed release (a scrub
 *   back over the cut finds it open); older idle ones close at once.
 * - [trimmed] (onTrimMemory): every idle decoder closes at once.
 */
object DecoderRetention {
    const val KEEP_IDLE_PAUSED = 1

    /** The URIs some visible video layer uses in [t, t + [lookaheadUs]], into [out] (cleared first). */
    fun nearUris(layers: List<CompositionLayer>, t: Long, lookaheadUs: Long, out: MutableSet<String>) {
        out.clear()
        for (l in layers) {
            if (l.type != LayerType.VIDEO || !l.visible) continue
            val uri = l.asset?.uri ?: continue
            val end = l.timing.endUs
            if (l.timing.startUs <= t + lookaheadUs && (end == null || end > t)) out += uri
        }
    }

    /** The idle keys of [heldLru] (least recently used first). */
    fun idle(heldLru: List<String>, near: Set<String>): List<String> = heldLru.filter { DecoderKeys.uri(it) !in near }

    /** The keys of [heldLru] to close now. The idle keys left (paused) go to the delayed release. */
    fun closeNow(heldLru: List<String>, near: Set<String>, playing: Boolean, trimmed: Boolean = false): List<String> {
        val idle = idle(heldLru, near)
        return if (playing || trimmed) idle else idle.dropLast(KEEP_IDLE_PAUSED)
    }
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
     * MP1.2: what one open decoder really holds, as measured on the A16: the codec's own output buffers (Codec2 commits
     * about [CODEC_OUTPUT_BUFFERS] to the surface) plus the reader's (its [cacheFrames] plus two), each about w x h x 2
     * bytes (aligned YUV plus metadata). A 1920x1440 VP9 decoder held ~250 MB of dmabuf; the old w x h x 8 guess said 22 MB.
     */
    const val CODEC_OUTPUT_BUFFERS = 32
    fun decoderBytes(width: Int, height: Int, cacheFrames: Int): Long =
        width.toLong() * height * 2 * (cacheFrames + 2 + CODEC_OUTPUT_BUFFERS)
    /** MP1.2: the preview's decoder memory budget: a fifth of the device's RAM (A16, 3.7 GB: ~750 MB). */
    fun memoryBudget(totalMemBytes: Long): Long = totalMemBytes / 5

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
