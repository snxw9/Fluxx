package com.fluxx.android.render

/**
 * MP1: a TEST-ONLY observer of background media jobs (the PCM cache decode, waveform peaks, timeline thumbnails):
 * "pcm-run", "pcm-run-end", "peaks-start", "peaks-done", "thumb-start", "thumb-done", ... Null in the app (nothing is
 * built then). The MP1 benchmarks use it to see the scope of each job and whether one is still running. No behaviour change.
 */
object MediaProbes {
    @Volatile @JvmStatic var events: ((String) -> Unit)? = null
    /** A short stable key for a URI in probe lines (never the URI itself: file names stay out of the logs). */
    fun key(uri: String): String = uri.hashCode().toUInt().toString(16)
}
