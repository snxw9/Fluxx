package com.fluxx.android.engine

/** All graphics entry points are confined to the preview worker. */
object FluxxEngine {
    init { System.loadLibrary("fluxxengine") }
    fun ping(value: Int) = nativePing(value)
    private external fun nativePing(value: Int): Int
    fun init(surface: android.view.Surface, width: Int, height: Int, assets: android.content.res.AssetManager) = nativeInit(surface,width,height,assets)
    private external fun nativeInit(surface: android.view.Surface, width: Int, height: Int, assets: android.content.res.AssetManager): Boolean
    fun cleanup() = nativeCleanup()
    private external fun nativeCleanup()
    external fun resize(width: Int, height: Int)
    external fun beginFrame(width: Int, height: Int): Boolean
    external fun setFrameLayer(id: Long, buffer: android.hardware.HardwareBuffer, matrix: FloatArray, opacity: Float): Boolean
    external fun finishFrame(): Boolean
    external fun releaseLayers()
    external fun bitmapBuffer(bitmap: android.graphics.Bitmap): android.hardware.HardwareBuffer?
}
