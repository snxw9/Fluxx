package com.fluxx.android.model

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Preparation-only wire value; Java's simple uppercase mapping never changes source membership. */
object StyledTextPreparation {
    fun encode(document: TextSourceDocument,layerFont: String,resolveFont: ((TextFontRef)->String)?=null): ByteArray {
        val source=document.text
        val offsets=IntArray(source.length+1)
        val uppercase=ArrayList<Int>()
        var i=0;var bytes=0
        while(i<source.length) {
            offsets[i]=bytes;val cp=source.codePointAt(i)
            uppercase.add(Character.toUpperCase(cp))
            bytes+=when { cp<0x80->1;cp<0x800->2;cp<0x10000->3;else->4 }
            i+=Character.charCount(cp)
        }
        offsets[source.length]=bytes
        val output=ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            fun string(value: String) { val encoded=value.toByteArray(Charsets.UTF_8);out.writeInt(encoded.size);out.write(encoded) }
            out.writeInt(1);string(source);out.writeInt(uppercase.size);uppercase.forEach(out::writeInt)
            val shapedRuns=document.runs.filter { it.end>it.start }
            out.writeInt(shapedRuns.size)
            for(run in shapedRuns) {
                val s=run.style
                val font=s.font?.let { ref ->
                    if(ref.sha256==null) ref.bundledId else requireNotNull(resolveFont) { "Imported fonts require isolated preparation" }(ref)
                } ?: layerFont
                out.writeInt(offsets[run.start]);out.writeInt(offsets[run.end]);string(font)
                out.writeFloat(s.sizeFactor);out.writeFloat(s.horizontalScale/100);out.writeFloat(s.verticalScale/100)
                out.writeInt(s.caps.ordinal);out.writeInt(s.script.ordinal)
                out.writeInt((if(s.kerning==TextKerning.METRICS) 1 else 0) or (if(s.fauxBold) 2 else 0) or (if(s.fauxItalic) 4 else 0))
            }
        }
        return output.toByteArray()
    }
    /** Source Text holds source, width and direction together: only actual documents are reachable. */
    fun alternatives(source: AnimatableString): Array<TextSourceDocument> {
        val documents=(listOf(source.sourceDocument)+source.keyframes.map { it.sourceDocument }).distinct()
        require(documents.size<=TextSourceDocument.MAX_PREPARED_LAYOUTS/2) {
            "Text exceeds 64 prepared layouts; reduce distinct held Source Text documents to at most 32 (including width/direction alternatives)"
        }
        return documents.toTypedArray()
    }
}
