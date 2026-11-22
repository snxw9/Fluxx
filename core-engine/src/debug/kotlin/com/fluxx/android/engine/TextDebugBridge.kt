package com.fluxx.android.engine

import java.io.File

/** Not present in release variants. Run off the main thread; writes only CPU diagnostics. */
object TextDebugBridge {
    init { System.loadLibrary("fluxxengine") }
    external fun surface(session: Long, surface: android.view.Surface?, width: Int, height: Int): Boolean
    external fun repack(session: Long)
    external fun gpuLedger(): String
    external fun pixels(session: Long, output: java.nio.ByteBuffer): Boolean
    external fun allocationCount(): Long
    external fun allocationCanary(): Long
    /** Reusable output: mesh ns, rasterizations, atlas uploads, shape count, glyph count, generation. */
    external fun frameCounters(session: Long, handle: Long, output: LongArray)
    /**
     * TEXT PERF 10: native frame stages since the last call, in ns (then reset): work fence wait, batch record,
     * batch submit+wait, present fence wait, acquire, present record, present submit, present idle wait,
     * GPU batch, GPU present (timestamp queries), batch count, frame count. TEXT PERF 11 appends: text layers drawn,
     * native mesh build ns, re-collect+pack ns, re-collect count, atlas upload CPU ns, tier calls, placement lookups,
     * text-only flushes merged into the next submit.
     */
    external fun frameStages(session: Long, output: LongArray)
    const val STAGES = 20
    /** TEXT PERF 11 parity switch: false submits every flush separately (TEXT PERF 10 behaviour). */
    external fun mergeTextBatches(session: Long, merge: Boolean)
    fun dump(session: FontSession, directory: File) {
        check(directory.mkdirs() || directory.isDirectory)
        session.withHandle { dump(it, directory.absolutePath) }
    }
    private external fun dump(handle: Long, directory: String)
}
