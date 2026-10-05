package com.fluxx.android.render

import android.icu.text.BreakIterator
import com.fluxx.android.model.TextSourceDocument
import java.util.Locale

/** UAX14 opportunities in source UTF-16 order; preparation only, never called during evaluation. */
object TextLineBreakPreparation {
    fun opportunities(document: TextSourceDocument): IntArray {
        val iterator=BreakIterator.getLineInstance(Locale.ROOT)
        iterator.setText(document.text)
        val boundaries=ArrayList<Int>()
        var edge=iterator.first()
        while(edge!=BreakIterator.DONE) {
            if(document.boundaries.binarySearch(edge)>=0) boundaries.add(edge)
            edge=iterator.next()
        }
        return boundaries.toIntArray()
    }
}
