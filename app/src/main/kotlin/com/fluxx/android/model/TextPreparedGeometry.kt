package com.fluxx.android.model

import kotlin.math.*

/** Immutable editor snapshot of the same evaluated geometry used for rendering.
 * Source ranges are logical UTF-16; boxes/origins and line indices are visual. */
class TextPreparedGeometry(val document: TextSourceDocument,val starts: IntArray,val ends: IntArray,
    val lines: IntArray,val words: IntArray,val boxes: FloatArray,val origins: FloatArray,val logical: FloatArray) {
    val count get()=starts.size
    fun hit(x: Float,y: Float,nearest: Boolean=false): Int {
        var result=-1;var distance=Float.POSITIVE_INFINITY
        for(c in starts.indices) {
            val i=c*4;val dx=max(max(boxes[i]-x,0f),x-boxes[i+2]);val dy=max(max(boxes[i+1]-y,0f),y-boxes[i+3])
            val d=dx*dx+dy*dy
            if(d==0f) return c
            if(nearest && d<distance) { result=c;distance=d }
        }
        return result
    }
    fun range(cluster: Int,word: Boolean=false): TextSelectionRange {
        var a=starts[cluster];var b=ends[cluster]
        if(word && words[cluster]>=0) for(c in starts.indices) if(words[c]==words[cluster]) { a=min(a,starts[c]);b=max(b,ends[c]) }
        return TextSelectionRange(a,b)
    }
    fun safeRange(start: Int,end: Int): TextSelectionRange {
        var a=min(start,end).coerceIn(0,document.text.length);var b=max(start,end).coerceIn(0,document.text.length)
        for(c in starts.indices) { if(a>starts[c] && a<ends[c]) a=starts[c];if(b>starts[c] && b<ends[c]) b=ends[c] }
        return TextSelectionRange(TextSourceBoundaries.floor(document.boundaries,a),TextSourceBoundaries.ceiling(document.boundaries,b))
    }
}

/** Editor-only state. It is never serialized or dispatched to undo history. */
data class TextSelectionRange(val start: Int,val end: Int) {
    init { require(start>=0 && end>=start) }
    val collapsed get()=start==end
}
