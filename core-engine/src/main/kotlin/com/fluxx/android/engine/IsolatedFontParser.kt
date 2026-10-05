package com.fluxx.android.engine

/** Untrusted font bytes must never enter FreeType in the application process. */
object IsolatedFontParser {
    fun inspect(bytes: ByteArray): String {
        check(android.os.Process.isIsolated()) { "Imported fonts require the isolated font worker" }
        require(bytes.isNotEmpty() && bytes.size<=16*1024*1024) { "Font files must be at most 16 MiB" }
        return IsolatedFontNative.inspect(bytes)
    }
    fun prepare(assets: android.content.res.AssetManager,ids: Array<String>,fonts: Array<ByteArray>,faces: IntArray,
        instances: IntArray,tags: Array<String>,values: Array<FloatArray>,input: ByteArray,ligatures: Boolean,nominal: Boolean=false): ByteArray {
        check(android.os.Process.isIsolated()) { "Imported fonts require the isolated font worker" }
        return IsolatedFontNative.prepare(assets,ids,fonts,faces,instances,tags,values,input,ligatures,nominal)
    }
}

internal object IsolatedFontNative {
    init { System.loadLibrary("fluxxengine") }
    external fun inspect(bytes: ByteArray): String
    external fun prepare(assets: android.content.res.AssetManager,ids: Array<String>,fonts: Array<ByteArray>,faces: IntArray,
        instances: IntArray,tags: Array<String>,values: Array<FloatArray>,input: ByteArray,ligatures: Boolean,nominal: Boolean): ByteArray
}
