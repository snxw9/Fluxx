package com.fluxx.android.engine

/** Untrusted font bytes must never enter FreeType in the application process. */
object IsolatedFontParser {
    fun timings(): LongArray {check(android.os.Process.isIsolated());return IsolatedFontNative.timings()}
    fun inspect(bytes: ByteArray): String {
        check(android.os.Process.isIsolated()) { "Imported fonts require the isolated font worker" }
        require(bytes.isNotEmpty() && bytes.size<=16*1024*1024) { "Font files must be at most 16 MiB" }
        return IsolatedFontNative.inspect(bytes)
    }
    fun prepare(assets: android.content.res.AssetManager,ids: Array<String>,fonts: Array<ByteArray>,faces: IntArray,
        instances: IntArray,tags: Array<String>,values: Array<FloatArray>,input: ByteArray,ligatures: Boolean,nominal: Boolean=false,seeds: ByteArray=ByteArray(0)): ByteArray {
        check(android.os.Process.isIsolated()) { "Imported fonts require the isolated font worker" }
        return IsolatedFontNative.prepare(assets,ids,fonts,faces,instances,tags,values,input,ligatures,nominal,seeds)
    }
    /** TEXT FIX 9c: imported-font atlas tiers (bounded request/reply wire format, see prepared_text_packet.h). */
    fun tiers(assets: android.content.res.AssetManager,ids: Array<String>,fonts: Array<ByteArray>,faces: IntArray,
        instances: IntArray,tags: Array<String>,values: Array<FloatArray>,requests: ByteArray): ByteArray {
        check(android.os.Process.isIsolated()) { "Imported fonts require the isolated font worker" }
        return IsolatedFontNative.tiers(assets,ids,fonts,faces,instances,tags,values,requests)
    }
}

internal object IsolatedFontNative {
    init { System.loadLibrary("fluxxengine") }
    external fun inspect(bytes: ByteArray): String
    external fun timings(): LongArray
    external fun prepare(assets: android.content.res.AssetManager,ids: Array<String>,fonts: Array<ByteArray>,faces: IntArray,
        instances: IntArray,tags: Array<String>,values: Array<FloatArray>,input: ByteArray,ligatures: Boolean,nominal: Boolean,seeds: ByteArray): ByteArray
    external fun tiers(assets: android.content.res.AssetManager,ids: Array<String>,fonts: Array<ByteArray>,faces: IntArray,
        instances: IntArray,tags: Array<String>,values: Array<FloatArray>,requests: ByteArray): ByteArray
}
