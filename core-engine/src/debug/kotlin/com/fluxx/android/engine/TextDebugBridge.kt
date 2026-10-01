package com.fluxx.android.engine

import java.io.File

/** Not present in release variants. Run off the main thread; writes only CPU diagnostics. */
object TextDebugBridge {
    fun dump(session: FontSession, directory: File) {
        check(directory.mkdirs() || directory.isDirectory)
        session.withHandle { dump(it, directory.absolutePath) }
    }
    private external fun dump(handle: Long, directory: String)
}
