package com.fluxx.android.render

import java.util.Collections
import java.util.IdentityHashMap

/**
 * MP2.2 B1: which decoders have an image staged in the current GPU batch (pure, JVM tested).
 *
 * A staged draw holds a native reference to a decoder's output buffer, but not the buffer's content: advancing that
 * decoder (a decode evicts and closes a cached `Image`, and the codec writes the next picture into its buffer) before
 * the batch is submitted could change what the draw samples. So the renderer flushes before `frameAt` only when the
 * decoder about to be advanced already has an image staged since the last flush ([mustFlushBefore]). With distinct
 * decoders that never happens; with layers sharing a decoder (within `DecoderKeys.SHARE_US`) it does.
 *
 * The set is cleared on every flush, at the start of each frame and after a cancelled or failed frame ([clear]).
 * [STAGED] (test-only, [checking]) records every staged decoder across renderers, so a check run by the decoder itself
 * can report one that is advanced or closed while it still has an image staged.
 */
class StagedDecoders {
    private val keys = HashSet<String>()
    private val decoders = ArrayList<Any>(4)

    val isEmpty: Boolean get() = keys.isEmpty()
    operator fun contains(key: String) = key in keys

    /**
     * Whether a flush must run before [key]'s decoder is advanced: something is staged ([staged] > 0) and either the
     * test-only flush-always mode is on (the behaviour before MP2.2) or that decoder has an image in the batch.
     */
    fun mustFlushBefore(key: String, staged: Int, flushAlways: Boolean): Boolean = staged > 0 && (flushAlways || key in keys)

    /** [decoder] (under [key]) has an image staged in the current batch. */
    fun staged(key: String, decoder: Any) {
        keys += key
        decoders += decoder
        if (checking) STAGED.add(decoder)
    }

    /** The batch was submitted (and its fence waited), or the frame began, ended, was cancelled or failed. */
    fun clear() {
        if (decoders.isNotEmpty() && checking) for (d in decoders) STAGED.remove(d)
        keys.clear(); decoders.clear()
    }

    companion object {
        /** TEST-ONLY (default off): record staged decoders in [STAGED] for [violation]. */
        @Volatile @JvmStatic var checking = false
        private val STAGED: MutableSet<Any> = Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap()))
        /** Violations seen since the last [resetViolations] (test-only), with the first one's description. */
        @Volatile @JvmStatic var violations = 0L; private set
        @Volatile @JvmStatic var firstViolation: String? = null; private set
        /** Called (test-only) when [decoder] is about to be advanced or closed: counts it if it still has an image staged. */
        @JvmStatic fun violation(decoder: Any, what: String) {
            if (!checking || !STAGED.contains(decoder)) return
            synchronized(this) { violations++; if (firstViolation == null) firstViolation = "$what while an image was staged (${Thread.currentThread().name})" }
        }
        @JvmStatic fun resetViolations() { synchronized(this) { violations = 0; firstViolation = null } }
    }
}
