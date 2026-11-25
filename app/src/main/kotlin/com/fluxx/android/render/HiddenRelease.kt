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
