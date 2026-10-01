package com.fluxx.android.engine

import java.io.File

/** Not present in release variants. Run off the main thread; writes only CPU diagnostics. */
object TextDebugBridge {
    init { System.loadLibrary("fluxxengine") }
    external fun surface(session: Long, surface: android.view.Surface?, width: Int, height: Int): Boolean
    external fun repack(session: Long)
    external fun gpuLedger(): String
    external fun pixels(session: Long, output: java.nio.ByteBuffer): Boolean
    fun dump(session: FontSession, directory: File) {
        check(directory.mkdirs() || directory.isDirectory)
        session.withHandle { dump(it, directory.absolutePath) }
    }
    private external fun dump(handle: Long, directory: String)
}
