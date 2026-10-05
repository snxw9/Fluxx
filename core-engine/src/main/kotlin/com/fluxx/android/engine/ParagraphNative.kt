package com.fluxx.android.engine

import java.nio.ByteBuffer

/** Pure arithmetic over borrowed prepared buffers. No font operations or native session. */
object ParagraphNative {
    init { System.loadLibrary("fluxxengine") }
    external fun wrap(clusters: ByteBuffer,paragraphs: ByteBuffer,positions: ByteBuffer,lines: ByteBuffer,
        count: Int,paragraphCount: Int,width: Double,step: Double,lineCapacity: Int): Int
}
