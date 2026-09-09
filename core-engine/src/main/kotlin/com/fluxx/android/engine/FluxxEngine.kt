package com.fluxx.android.engine

/**
 * Kotlin JNI Wrapper for testing the connection to the native C++ engine.
 */
object FluxxEngine {

    init {
        System.loadLibrary("fluxxengine")
    }

    /**
     * Sends an integer down to the native engine, which logs it and returns value + 1.
     */
    fun ping(value: Int): Int {
        return nativePing(value)
    }

    private external fun nativePing(value: Int): Int

    fun init(surface: android.view.Surface, width: Int, height: Int, assetManager: android.content.res.AssetManager) {
        nativeInit(surface, width, height, assetManager)
    }

    private external fun nativeInit(surface: android.view.Surface, width: Int, height: Int, assetManager: android.content.res.AssetManager)

    fun stageHardwareBuffer(hardwareBuffer: android.hardware.HardwareBuffer, generationId: Long, cropWidth: Int, cropHeight: Int) {
        nativeStageHardwareBuffer(hardwareBuffer, generationId, cropWidth, cropHeight)
    }

    fun getLastConsumedGeneration(): Long {
        return nativeGetLastConsumedGeneration()
    }

    private external fun nativeStageHardwareBuffer(hardwareBuffer: android.hardware.HardwareBuffer, generationId: Long, cropWidth: Int, cropHeight: Int)
    private external fun nativeGetLastConsumedGeneration(): Long
    fun cleanup() {
        nativeCleanup()
    }

    fun renderFrame() { nativeRenderFrame() }
    private external fun nativeRenderFrame()
    external fun nativeCleanup()

    fun setLayerTransform(matrix: FloatArray, opacity: Float) {
        nativeSetLayerTransform(matrix, opacity)
    }
    private external fun nativeSetLayerTransform(matrix: FloatArray, opacity: Float)

    fun getCompWidth(): Int {
        return nativeGetCompWidth()
    }
    private external fun nativeGetCompWidth(): Int

    fun getCompHeight(): Int {
        return nativeGetCompHeight()
    }
    private external fun nativeGetCompHeight(): Int

    fun renderExportFrame(): Boolean {
        return nativeRenderExportFrame()
    }
    private external fun nativeRenderExportFrame(): Boolean

    fun readbackPixels(buffer: java.nio.ByteBuffer): Boolean {
        return nativeReadbackPixels(buffer)
    }
    private external fun nativeReadbackPixels(buffer: java.nio.ByteBuffer): Boolean
}


