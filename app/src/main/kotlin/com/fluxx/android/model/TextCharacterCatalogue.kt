package com.fluxx.android.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeSet
import kotlin.math.*

/** Preparation-only reachability. Selector influence is conservatively bounded to [-1,1]. */
object TextCharacterPreparation {
    const val MAX_CODEPOINTS=4096
    const val MAX_CATALOGUE_GLYPHS=16384
    fun needed(text: TextProperties)=text.animators.any { a->a.properties.any {
        it.property==AnimatorProperty.CHARACTER_OFFSET || it.property==AnimatorProperty.CHARACTER_VALUE
    } }
    fun valid(value: Double): Boolean = value.isFinite() && value>=0 && value<=0x10ffff &&
        value.toInt() !in 0xd800..0xdfff && Character.getType(value.toInt())!=Character.CONTROL.toInt() &&
        Character.getType(value.toInt())!=Character.FORMAT.toInt()
    fun resolve(original: Int,current: Int,property: AnimatorProperty,requested: Double,coverage: Double): Int {
        if(coverage==0.0) return current
        val value=floor((if(property==AnimatorProperty.CHARACTER_VALUE) requested else current+coverage*requested)+.5)
        return if(valid(value)) value.toInt() else original
    }
    fun codepoints(text: TextProperties,documents: Array<TextSourceDocument>): IntArray {
        val bases=TreeSet<Int>();val out=TreeSet<Int>()
        for(document in documents) {
            var at=0
            while(at<document.text.length) {
                val cp=document.text.codePointAt(at);if(cp!='\n'.code) { bases.add(cp);out.add(cp) }
                at+=Character.charCount(cp)
            }
        }
        for(base in bases) {
            var ranges=mutableListOf(base.toDouble() to base.toDouble())
            for(a in text.animators) for(p in a.properties) {
                if(p.property!=AnimatorProperty.CHARACTER_OFFSET && p.property!=AnimatorProperty.CHARACTER_VALUE) continue
                val track=requireNotNull(p.scalar)
                val values=listOf(track.staticValue)+track.keyframes.map { it.value }
                if(p.property==AnimatorProperty.CHARACTER_VALUE) ranges.add(values.min().toDouble()-.5 to values.max().toDouble()+.5)
                else {
                    val magnitude=values.maxOf { abs(it.toDouble()) }
                    ranges=ranges.map { (lo,hi)->(lo-magnitude-.5) to (hi+magnitude+.5) }.toMutableList()
                }
                ranges.add(base.toDouble() to base.toDouble()) // Invalid results restore the source character.
            }
            for((lo,hi) in ranges) {
                if(hi<0 || lo>0x10ffff) continue
                val first=ceil(lo).coerceIn(0.0,0x10ffff.toDouble()).toInt()
                val last=floor(hi).coerceIn(0.0,0x10ffff.toDouble()).toInt()
                for(cp in first..last) if(valid(cp.toDouble())) {
                    out.add(cp)
                    require(out.size<=MAX_CODEPOINTS) { "Character animation exceeds 4096 reachable codepoints; narrow Character Offset/Value tracks or reduce distinct source characters" }
                }
            }
        }
        require(out.size<=MAX_CODEPOINTS)
        return out.toIntArray()
    }
    fun fonts(font: String,documents: Array<TextSourceDocument>)=
        documents.flatMap { it.runs.map { run->run.style.font ?: TextFontRef(font) } }.distinct().toTypedArray()
}

/** One standalone glyph per sorted codepoint, in common 1000-unit metrics. */
class TextNominalMetrics(val codepoints: IntArray,private val data: DoubleArray) {
    private val lines=data[4].toInt();private val glyphs=data[5].toInt()
    private val clusters=6+lines*2+glyphs*8+4
    init { require(glyphs==codepoints.size && data[0]>0 && codepoints.indices.all { it==0 || codepoints[it-1]<codepoints[it] }) }
    fun index(cp: Int)=codepoints.binarySearch(cp).also { require(it>=0) { "Character codepoint was not prepared; narrow the animated range" } }
    fun advance(index: Int)=data[clusters+index*6+5]/data[0]
    fun bearingX(index: Int)=data[6+lines+index*7+3]/data[0]
    fun bearingY(index: Int)=data[6+lines+index*7+4]/data[0]
    fun width(index: Int)=data[6+lines+index*7+5]/data[0]
    fun height(index: Int)=-data[6+lines+index*7+6]/data[0]
}

class TextCharacterCatalogue(val fonts: Array<TextFontRef>,val codepoints: IntArray,val metrics: Array<TextNominalMetrics>) {
    init { require(fonts.size==metrics.size && fonts.size*codepoints.size<=TextCharacterPreparation.MAX_CATALOGUE_GLYPHS) {
        "Character catalogue exceeds 16384 font/codepoint glyphs; reduce fonts or animated character ranges"
    } }
}

/** Codes are borrowed by native preparation and retained with the modifier buffer; drawing APIs stay unchanged. */
class PreparedTextCharacters(document: TextSourceDocument,private val starts: IntArray,private val ends: IntArray,
    val catalogue: TextCharacterCatalogue,layerFont: String) {
    val edges=document.boundaries.filter { it<document.text.length && document.text[it]!='\n' }.toIntArray()
    val originals=IntArray(edges.size) { document.text.codePointAt(edges[it]) }
    val fontIndices=IntArray(edges.size) { i->catalogue.fonts.indexOf(document.runAt(edges[i])?.style?.font ?: TextFontRef(layerFont)).also { require(it>=0) } }
    val codes: ByteBuffer=ByteBuffer.allocateDirect(maxOf(4,edges.size*4)).order(ByteOrder.nativeOrder())
    val changed=BooleanArray(starts.size)
    private val active=BooleanArray(edges.size)
    val advances=DoubleArray(starts.size)
    val marks=Array(edges.size) { i->
        val stop=document.boundaries[document.boundaries.binarySearch(edges[i])+1]
        val points=ArrayList<Int>();var at=edges[i]+Character.charCount(originals[i])
        while(at<stop) { val cp=document.text.codePointAt(at);points.add(cp);at+=Character.charCount(cp) }
        points.toIntArray()
    }
    init { require((ends.lastOrNull() ?: 0)==edges.size) }
    fun begin() { for(i in originals.indices) codes.putInt(i*4,originals[i]);changed.fill(false);active.fill(false) }
    fun apply(property: AnimatorProperty,value: Double,coverage: DoubleArray) {
        for(c in starts.indices) if(coverage[c]!=0.0) for(i in starts[c] until ends[c]) {
            val attempted=floor((if(property==AnimatorProperty.CHARACTER_VALUE) value else codes.getInt(i*4)+coverage[c]*value)+.5)
            active[i]=TextCharacterPreparation.valid(attempted)
            codes.putInt(i*4,if(active[i]) attempted.toInt() else originals[i])
        }
    }
    fun complete(size: Double,runSizes: DoubleArray,horizontal: Array<TextRunStyle>) {
        for(c in starts.indices) {
            var advance=0.0
            for(i in starts[c] until ends[c]) {
                val cp=codes.getInt(i*4);if(active[i]) changed[c]=true
                val m=catalogue.metrics[fontIndices[i]];advance+=m.advance(m.index(cp))
                for(mark in marks[i]) advance+=m.advance(m.index(mark))
            }
            advances[c]=advance*size*runSizes[c]*horizontal[c].horizontalScale/100
        }
    }
}
