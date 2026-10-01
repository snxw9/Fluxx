package com.fluxx.android.engine

import android.content.res.AssetManager

/** CPU-only E1 proof bridge. Create/use/close on a worker; never shape on the UI thread.
 * Sessions share a native catalog while live; no FreeType pointers or GPU resources cross JNI.
 * E2 will consume cached native layouts directly; this JSON API is for parity diagnostics.
 */
class FontSession(assets: AssetManager) : AutoCloseable {
    private var handle = TextNative.create(assets).also { check(it != 0L) }

    @Synchronized
    fun shape(fontId: String, text: String): String = withHandle {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 16384) { "Text exceeds the E1 input limit" }
        TextNative.shape(it, fontId, bytes)
    }

    @Synchronized
    internal fun <T> withHandle(block: (Long) -> T): T {
        check(handle != 0L) { "Font session is closed" }
        return block(handle)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) TextNative.close(handle)
        handle = 0
    }
}

internal object TextNative {
    init { System.loadLibrary("fluxxengine") }
    external fun create(assets: AssetManager): Long
    external fun close(handle: Long)
    external fun shape(handle: Long, font: String, utf8: ByteArray): String
}
