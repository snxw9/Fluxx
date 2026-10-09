package com.fluxx.android.render

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.icu.text.BreakIterator
import com.fluxx.android.model.*
import java.util.Locale

/** Unicode word and Joining_Type queries run only in preparation, never in a frame. */
object TextJoiningPreparation {
    fun words(document: TextSourceDocument): List<TextSelectionRange> {
        val iterator=BreakIterator.getWordInstance(Locale.ROOT)
        iterator.setText(document.text)
        val result=ArrayList<TextSelectionRange>()
        var start=iterator.first();var end=iterator.next()
        while(end!=BreakIterator.DONE) {
            var i=start;var joining=false
            while(i<end) {
                val cp=document.text.codePointAt(i)
                when(UCharacter.getIntPropertyValue(cp,UProperty.JOINING_TYPE)) {
                    UCharacter.JoiningType.DUAL_JOINING,UCharacter.JoiningType.RIGHT_JOINING,
                    UCharacter.JoiningType.LEFT_JOINING,UCharacter.JoiningType.JOIN_CAUSING->joining=true
                }
                i+=Character.charCount(cp)
            }
            if(joining) result.add(TextSelectionRange(TextSourceBoundaries.floor(document.boundaries,start),TextSourceBoundaries.ceiling(document.boundaries,end)))
            start=end;end=iterator.next()
        }
        return result.distinct()
    }
}
