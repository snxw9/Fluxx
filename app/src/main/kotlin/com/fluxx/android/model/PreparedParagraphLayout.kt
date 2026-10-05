package com.fluxx.android.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

interface ParagraphWrapKernel {
    fun wrap(clusters: ByteBuffer,paragraphs: ByteBuffer,positions: ByteBuffer,lines: ByteBuffer,
        count: Int,paragraphCount: Int,width: Double,step: Double,lineCapacity: Int): Int
}
object NativeParagraphWrapKernel: ParagraphWrapKernel {
    override fun wrap(clusters: ByteBuffer,paragraphs: ByteBuffer,positions: ByteBuffer,lines: ByteBuffer,
        count: Int,paragraphCount: Int,width: Double,step: Double,lineCapacity: Int)=
        com.fluxx.android.engine.ParagraphNative.wrap(clusters,paragraphs,positions,lines,count,paragraphCount,width,step,lineCapacity)
}

/** Immutable shaped advances; reusable per-frame native arithmetic and output buffers. */
class PreparedParagraphLayout(private val document: TextSourceDocument,private val alignment: TextAlignment,
    private val rawLines: Int,private val starts: IntArray,private val ends: IntArray,rawClusterLines: IntArray,
    private val advances: DoubleArray,private val scales: DoubleArray,private val runTracking: DoubleArray,
    private val manual: DoubleArray,spaces: BooleanArray,sourceEdges: IntArray,breaks: IntArray,
    private val kernel: ParagraphWrapKernel=NativeParagraphWrapKernel) {
    private val count=starts.size
    val maximumLines=minOf(LinePermutationPool.MAX_LINES,rawLines+count)
    private val input=direct(maxOf(8,count*24))
    private val paragraphs=direct(maxOf(8,rawLines*56))
    private val positions=direct(maxOf(8,count*8))
    private val lines=direct(maxOf(8,maximumLines*48))
    val clusterLines=IntArray(count)
    val x=DoubleArray(count);val y=DoubleArray(count)
    val lineAdvances=DoubleArray(maximumLines)
    val origins=DoubleArray(maximumLines)
    val lineTop=DoubleArray(maximumLines)
    val align=DoubleArray(maximumLines)
    var lineCount=0;private set
    init {
        require(rawLines in 1..LinePermutationPool.MAX_LINES) { "Text exceeds 256 explicit lines; reduce paragraphs" }
        require(document.paragraphs.all { it.direction==ParagraphDirection.LTR }) { "RTL layout requires T5; requested direction is preserved" }
        var at=0
        for(p in 0 until rawLines) {
            val begin=at;while(at<count&&rawClusterLines[at]==p) at++
            val style=document.paragraphs.getOrNull(p) ?: TextParagraphStyle()
            val a=style.alignment?.ordinal ?: alignment.ordinal
            val record=p*56
            paragraphs.putInt(record,begin);paragraphs.putInt(record+4,at);paragraphs.putInt(record+8,a)
            paragraphs.putDouble(record+16,style.indentLeft.toDouble());paragraphs.putDouble(record+24,style.indentRight.toDouble())
            paragraphs.putDouble(record+32,style.indentFirst.toDouble());paragraphs.putDouble(record+40,style.spaceBefore.toDouble())
            paragraphs.putDouble(record+48,style.spaceAfter.toDouble())
        }
        require(at==count)
        for(c in 0 until count) {
            val sourceEnd=if(ends[c]==0) 0 else document.boundaries[document.boundaries.binarySearch(sourceEdges[ends[c]-1])+1]
            input.putInt(c*24+8,if(spaces[c]) 1 else 0)
            input.putInt(c*24+12,if(breaks.binarySearch(sourceEnd)>=0) 1 else 0)
        }
    }
    fun evaluate(size: Double,tracking: Double,step: Double) {
        for(c in 0 until count) {
            // Tracking belongs to a boundary: the final cluster of an explicit paragraph has none.
            val p=paragraphFor(c);val hasNext=c+1<paragraphs.getInt(p*56+4)
            input.putDouble(c*24,size*(advances[c]+manual[c]*scales[c]/1000))
            input.putDouble(c*24+16,if(hasNext) size*(runTracking[c]+tracking)*scales[c]/1000 else 0.0)
        }
        lineCount=kernel.wrap(input,paragraphs,positions,lines,count,rawLines,document.boxWidth.toDouble(),step,maximumLines)
        require(lineCount in 1..maximumLines)
        for(l in 0 until lineCount) {
            val record=l*48;val p=lines.getInt(record+8)
            origins[l]=lines.getDouble(record+16);lineAdvances[l]=lines.getDouble(record+24);lineTop[l]=lines.getDouble(record+32)
            val a=paragraphs.getInt(p*56+8)
            align[l]=when(a) { 1,4->.5;2,5->1.0;else->0.0 }
            for(c in lines.getInt(record) until lines.getInt(record+4)) {
                clusterLines[c]=l;x[c]=positions.getDouble(c*8);y[c]=lineTop[l]
            }
        }
    }
    private val paragraphIndices=rawClusterLines.copyOf()
    private fun paragraphFor(cluster: Int)=paragraphIndices[cluster]
    companion object {
        private fun direct(bytes: Int)=ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    }
}
