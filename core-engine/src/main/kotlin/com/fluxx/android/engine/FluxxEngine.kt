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
}
