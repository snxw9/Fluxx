package com.fluxx.android.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Immutable native layout metadata plus reusable per-frame working storage.
 * The direct record ABI is shared with text_renderer.h; no objects are emitted per glyph. */
class PreparedTextAnimation(private val text: TextProperties,private val data: DoubleArray,
    ease: SelectorEaseTable) {
    private val lines=data[4].toInt();private val glyphs=data[5].toInt()
    private val prefix=6+lines*2+glyphs*8
    private val clusters=data[prefix+1].toInt();private val characters=data[prefix+2].toInt()
    private val words=data[prefix+3].toInt();private val clusterBase=prefix+4
    private val charBase=clusterBase+clusters*6;private val glyphBase=charBase+characters*4
    private val starts=IntArray(clusters) { data[clusterBase+it*6].toInt() }
    private val ends=IntArray(clusters) { data[clusterBase+it*6+1].toInt() }
    private val clusterLines=IntArray(clusters) { data[clusterBase+it*6+2].toInt() }
    private val clusterWords=IntArray(clusters) { data[clusterBase+it*6+3].toInt() }
    private val glyphClusters=IntArray(glyphs) { data[glyphBase+it].toInt() }
    private val ordinals=IntArray(clusters)
    private val nextInLine=BooleanArray(clusters) { it+1<clusters && clusterLines[it]==clusterLines[it+1] }
    private val membership=run {
        val maps=Array(4) { IntArray(characters) }
        var nonSpaces=0
        for(i in 0 until characters) {
            maps[0][i]=i;maps[1][i]=data[charBase+i*4+2].toInt()
            maps[2][i]=data[charBase+i*4+1].toInt();maps[3][i]=data[charBase+i*4].toInt()
            if(maps[1][i]>=0) nonSpaces=max(nonSpaces,maps[1][i]+1)
        }
        SelectorMembership(maps,intArrayOf(characters,nonSpaces,words,lines),starts,ends)
    }
    private val engines=Array(text.animators.size) { PreparedSelectorEngine(text.animators[it],membership,ease) }
    private val properties=Array(text.animators.size) { text.animators[it].properties.toTypedArray() }
    private val coverage=Array(text.animators.size) { DoubleArray(clusters) }
    private val values=Array(text.animators.size) { DoubleArray(26) }
    private val colours=Array(text.animators.size) { IntArray(13) }
    private val present=Array(text.animators.size) { a -> BooleanArray(13).also { out -> text.animators[a].properties.forEach { out[it.property.ordinal]=true } } }
    private val active=BooleanArray(text.animators.size)
    private val grouping=IntArray(text.animators.size)
    private val vector=FloatArray(2)
    private val boundary=DoubleArray(clusters);private val offset=DoubleArray(clusters)
    private val lineTotals=DoubleArray(lines);private val lineShift=DoubleArray(lines);private val ownTotals=DoubleArray(lines)
    private val baseAdvances=DoubleArray(lines);private val advances=DoubleArray(lines)
    private val clusterX=DoubleArray(clusters)
    private val matrices=DoubleArray(clusters*6);private val affine=DoubleArray(6)
    private val groupSizes=intArrayOf(clusters,words+clusters,lines,1)
    private val groups=Array(4) { DoubleArray(groupSizes[it]*4) }
    private val fill=IntArray(clusters);private val stroke=IntArray(clusters)
    private val width=DoubleArray(clusters);private val blur=DoubleArray(clusters);private val opacity=DoubleArray(clusters)
    val modifiers: ByteBuffer = ByteBuffer.allocateDirect(Math.multiplyExact(clusters,STRIDE)).order(ByteOrder.nativeOrder())
    val inkBounds=FloatArray(4)
    var selectorNs=0L;private set
    var geometryNs=0L;private set
    val coverageBuilds get()=engines.sumOf { it.coverageBuilds }
    init {
        require(data[prefix]==271828.0 && data.size==glyphBase+glyphs)
        var ordinal=0;var line=-1
        for(c in 0 until clusters) { if(clusterLines[c]!=line) { line=clusterLines[c];ordinal=0 };ordinals[c]=ordinal++ }
    }
    fun evaluate(time: Long) {
        val began=System.nanoTime()
        val size=text.size.evaluate(time).toDouble();val factor=size/data[0]
        val tracking=text.tracking.evaluate(time).toDouble()*size/1000
        val leading=text.leading.evaluate(time).toDouble();val baseline=text.baselineShift.evaluate(time).toDouble()
        val step=(data[1]-data[2]+max(0.0,data[3]))*factor+leading
        boundary.fill(0.0);lineTotals.fill(0.0);lineShift.fill(0.0)
        val baseFill=text.fill.evaluate(time);val baseStroke=text.strokeColour.evaluate(time)
        val baseWidth=text.strokeWidth.evaluate(time).toDouble()
        for(a in engines.indices) {
            val animator=text.animators[a]
            active[a]=animator.enabled.hold(time)!=0f
            if(!active[a]) continue
            engines[a].evaluate(time,coverage[a]);values[a].fill(0.0)
            grouping[a]=animator.anchorGrouping.hold(time).toInt()
            for(p in properties[a]) {
                val i=p.property.ordinal
                when {
                    p.scalar!=null -> values[a][i*2]=p.scalar.evaluate(time).toDouble()
                    p.vector!=null -> { p.vector.evaluate(time,vector,0);values[a][i*2]=vector[0].toDouble();values[a][i*2+1]=vector[1].toDouble() }
                    else -> colours[a][i]=requireNotNull(p.colour).evaluate(time)
                }
            }
            ownTotals.fill(0.0)
            if(present[a][AnimatorProperty.TRACKING.ordinal]) for(c in 0 until clusters) if(nextInLine[c]) {
                val delta=coverage[a][c]*values[a][AnimatorProperty.TRACKING.ordinal*2]*size/1000
                boundary[c]+=delta;ownTotals[clusterLines[c]]+=delta
            }
            val anchor=values[a][AnimatorProperty.LINE_ANCHOR.ordinal*2]
            for(l in 0 until lines) { lineTotals[l]+=ownTotals[l];lineShift[l]+=SelectorMath.lineShift(anchor,ownTotals[l]) }
        }
        selectorNs=System.nanoTime()-began
        var oldMax=0.0;var newMax=0.0
        for(l in 0 until lines) {
            baseAdvances[l]=data[6+l]*factor+(data[6+lines+glyphs*7+l].toInt()-1).coerceAtLeast(0)*tracking
            advances[l]=baseAdvances[l]+lineTotals[l];oldMax=max(oldMax,baseAdvances[l]);newMax=max(newMax,advances[l])
        }
        var pen=0.0;var line=-1;val align=text.alignment.ordinal/2.0
        for(c in 0 until clusters) {
            val l=clusterLines[c];if(l!=line) { line=l;pen=0.0 }
            offset[c]=pen+lineShift[l]+((newMax-advances[l])-(oldMax-baseAdvances[l]))*align
            clusterX[c]=data[clusterBase+c*6+4]*factor+ordinals[c]*tracking+(oldMax-baseAdvances[l])*align+offset[c]
            pen+=boundary[c]
            fill[c]=baseFill;stroke[c]=baseStroke;width[c]=baseWidth;blur[c]=0.0;opacity[c]=1.0
            val i=c*6;matrices[i]=1.0;matrices[i+1]=0.0;matrices[i+2]=0.0;matrices[i+3]=1.0;matrices[i+4]=0.0;matrices[i+5]=0.0
        }
        for(g in groups) { var i=0;while(i<g.size) { g[i]=Double.POSITIVE_INFINITY;g[i+1]=Double.POSITIVE_INFINITY;g[i+2]=Double.NEGATIVE_INFINITY;g[i+3]=Double.NEGATIVE_INFINITY;i+=4 } }
        for(c in 0 until clusters) for(kind in 0..3) {
            val group=group(c,kind);val b=groups[kind];val i=group*4
            val x=clusterX[c];val right=x+data[clusterBase+c*6+5]*factor;val y=clusterLines[c]*step-baseline
            b[i]=min(b[i],min(x,right));b[i+1]=min(b[i+1],y-data[1]*factor)
            b[i+2]=max(b[i+2],max(x,right));b[i+3]=max(b[i+3],y-data[2]*factor)
        }
        for(a in engines.indices) if(active[a]) {
            val v=values[a];val has=present[a]
            for(c in 0 until clusters) {
                val weight=coverage[a][c];if(weight==0.0) continue
                val kind=grouping[a];val bounds=groups[kind];val gi=group(c,kind)*4
                val px=(bounds[gi]+bounds[gi+2])*.5;val py=(bounds[gi+1]+bounds[gi+3])*.5
                SelectorMath.affine(affine,weight,v[0],v[1],px,py,v[12],v[13],v[4],
                    if(has[1]) v[2] else 100.0,if(has[1]) v[3] else 100.0,v[8],v[10])
                val i=c*6;val a0=matrices[i];val a1=matrices[i+1];val a2=matrices[i+2];val a3=matrices[i+3];val a4=matrices[i+4];val a5=matrices[i+5]
                matrices[i]=affine[0]*a0+affine[2]*a1;matrices[i+1]=affine[1]*a0+affine[3]*a1
                matrices[i+2]=affine[0]*a2+affine[2]*a3;matrices[i+3]=affine[1]*a2+affine[3]*a3
                matrices[i+4]=affine[0]*a4+affine[2]*a5+affine[4];matrices[i+5]=affine[1]*a4+affine[3]*a5+affine[5]
                if(has[3]) opacity[c]*=(1+weight*(v[6]/100-1)).coerceIn(0.0,1.0)
                val colourWeight=weight.coerceIn(0.0,1.0).toFloat()
                if(has[7]) fill[c]=interpolateColour(fill[c],colours[a][7],colourWeight)
                if(has[8]) stroke[c]=interpolateColour(stroke[c],colours[a][8],colourWeight)
                width[c]+=weight*v[18];blur[c]+=weight*v[24]
            }
        }
        val distanceScale=48/(16*size)
        for(c in 0 until clusters) {
            width[c]=width[c].coerceIn(0.0,.20*size);blur[c]=blur[c].coerceIn(0.0,max(0.0,.10*size-width[c]/2))
            val record=c*STRIDE
            for(k in 0..5) put(record+k*4,matrices[c*6+k])
            put(record+24,offset[c]);put(record+28,0.0)
            colour(record+32,fill[c]);colour(record+48,stroke[c])
            put(record+64,width[c]/2*distanceScale);put(record+68,blur[c]*distanceScale)
            put(record+72,opacity[c]);put(record+76,width[c]);put(record+80,blur[c])
        }
        var left=Double.POSITIVE_INFINITY;var top=left;var right=Double.NEGATIVE_INFINITY;var bottom=right
        for(g in 0 until glyphs) {
            val i=6+lines+g*7;val c=glyphClusters[g];val l=data[i].toInt()
            if(data[i+5]<=0 || data[i+6]>=0) continue
            val padding=width[c]/2+blur[c]+size/48
            val x=(data[i+1]+data[i+3])*factor+ordinals[c]*tracking+(oldMax-baseAdvances[l])*align+offset[c]-padding
            val y=l*step+(data[i+2]-data[i+4])*factor-baseline-padding
            val w=data[i+5]*factor+padding*2;val h=-data[i+6]*factor+padding*2;val m=c*6
            for(corner in 0..3) {
                val xx=x+(if(corner and 1!=0) w else 0.0);val yy=y+(if(corner and 2!=0) h else 0.0)
                val tx=matrices[m]*xx+matrices[m+2]*yy+matrices[m+4]
                val ty=matrices[m+1]*xx+matrices[m+3]*yy+matrices[m+5]
                left=min(left,tx);top=min(top,ty);right=max(right,tx);bottom=max(bottom,ty)
            }
        }
        if(left.isFinite()) { inkBounds[0]=left.toFloat();inkBounds[1]=top.toFloat();inkBounds[2]=(right-left).toFloat();inkBounds[3]=(bottom-top).toFloat() }
        else inkBounds.fill(0f)
        for(value in inkBounds) require(value.isFinite()) { "Text animator arithmetic exceeds finite bounds" }
        geometryNs=System.nanoTime()-began-selectorNs
    }
    private fun group(c: Int,kind: Int)=when(kind) {0->c;1->if(clusterWords[c]>=0) clusterWords[c] else words+c;2->clusterLines[c];else->0}
    private fun put(offset: Int,value: Double) { val f=value.toFloat();require(f.isFinite()) { "Text animator arithmetic exceeds finite geometry" };modifiers.putFloat(offset,f) }
    private fun colour(offset: Int,argb: Int) {
        modifiers.putFloat(offset,((argb ushr 16) and 255)/255f);modifiers.putFloat(offset+4,((argb ushr 8) and 255)/255f)
        modifiers.putFloat(offset+8,(argb and 255)/255f);modifiers.putFloat(offset+12,(argb ushr 24)/255f)
    }
    companion object { const val STRIDE=96 }
}
