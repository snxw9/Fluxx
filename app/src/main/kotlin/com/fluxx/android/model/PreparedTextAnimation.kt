package com.fluxx.android.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Immutable native layout metadata plus reusable per-frame working storage.
 * The direct record ABI is shared with text_renderer.h; no objects are emitted per glyph. */
class PreparedTextAnimation(private val text: TextProperties,private val data: DoubleArray,
    ease: SelectorEaseTable,private val document: TextSourceDocument?=null,private val linePool: LinePermutationPool=LinePermutationPool(),
    breaks: IntArray?=null,paragraphKernel: ParagraphWrapKernel=NativeParagraphWrapKernel,
    characterCatalogue: TextCharacterCatalogue?=null) {
    private val lines=data[4].toInt();private val glyphs=data[5].toInt()
    private val prefix=6+lines*2+glyphs*8
    private val clusters=data[prefix+1].toInt();private val characterCount=data[prefix+2].toInt()
    private val words=data[prefix+3].toInt();private val clusterBase=prefix+4
    private val charBase=clusterBase+clusters*6;private val glyphBase=charBase+characterCount*4
    private val starts=IntArray(clusters) { data[clusterBase+it*6].toInt() }
    private val ends=IntArray(clusters) { data[clusterBase+it*6+1].toInt() }
    private val clusterLines=IntArray(clusters) { data[clusterBase+it*6+2].toInt() }
    private val rawClusterLines=clusterLines.copyOf()
    private val paragraphActive=document!=null && document.text.isNotEmpty() &&
        (document.boxWidth>0 || document.paragraphs.any { it!=TextParagraphStyle() })
    private val lineCapacity=if(paragraphActive) minOf(LinePermutationPool.MAX_LINES,lines+clusters) else lines
    private val clusterWords=IntArray(clusters) { data[clusterBase+it*6+3].toInt() }
    private val glyphClusters=IntArray(glyphs) { data[glyphBase+it].toInt() }
    private val firstGlyph=IntArray(clusters) { -1 }.also { out->for(g in 0 until glyphs) if(out[glyphClusters[g]]<0) out[glyphClusters[g]]=g }
    private val ordinals=IntArray(clusters)
    private val nextInLine=BooleanArray(clusters) { it+1<clusters && clusterLines[it]==clusterLines[it+1] }
    private val membership=run {
        val maps=Array(4) { IntArray(characterCount) }
        var nonSpaces=0
        for(i in 0 until characterCount) {
            maps[0][i]=i;maps[1][i]=data[charBase+i*4+2].toInt()
            maps[2][i]=data[charBase+i*4+1].toInt();maps[3][i]=data[charBase+i*4].toInt()
            if(maps[1][i]>=0) nonSpaces=max(nonSpaces,maps[1][i]+1)
        }
        SelectorMembership(maps,intArrayOf(characterCount,nonSpaces,words,lines),starts,ends)
    }
    private val engines=Array(text.animators.size) { PreparedSelectorEngine(text.animators[it],membership,ease,
        linePool,lines,lineCapacity) }
    private val properties=Array(text.animators.size) { text.animators[it].properties.toTypedArray() }
    private val coverage=Array(text.animators.size) { DoubleArray(clusters) }
    private val independent=BooleanArray(text.animators.size) { a->text.animators[a].selectors.any { s->
        (s.kind.staticValue!=0f || s.kind.keyframes.any { it.value!=0f }) &&
            (s.lockDimensions.staticValue==0f || s.lockDimensions.keyframes.any { it.value==0f })
    } }
    private val coverageY=Array(text.animators.size) { if(independent[it]) DoubleArray(clusters) else coverage[it] }
    private val coverageZ=Array(text.animators.size) { if(independent[it]) DoubleArray(clusters) else coverage[it] }
    private val values=Array(text.animators.size) { DoubleArray(AnimatorProperty.entries.size*2) }
    private val colours=Array(text.animators.size) { IntArray(AnimatorProperty.entries.size) }
    private val present=Array(text.animators.size) { a -> BooleanArray(AnimatorProperty.entries.size).also { out -> text.animators[a].properties.forEach { out[it.property.ordinal]=true } } }
    private val active=BooleanArray(text.animators.size)
    private val grouping=IntArray(text.animators.size)
    private val vector=FloatArray(2)
    private val boundary=DoubleArray(clusters);private val offset=DoubleArray(clusters)
    private val lineTotals=DoubleArray(lineCapacity);private val lineShift=DoubleArray(lineCapacity);private val ownTotals=DoubleArray(lineCapacity)
    private val baseAdvances=DoubleArray(lineCapacity);private val advances=DoubleArray(lineCapacity)
    private val rawAdvances=DoubleArray(lineCapacity);private val runTotals=DoubleArray(lineCapacity)
    private val lineCharacters=IntArray(lineCapacity);private val extraSpacing=DoubleArray(lineCapacity);private val lineOffsets=DoubleArray(lineCapacity)
    private val characterTotals=DoubleArray(lineCapacity);private val clusterAdvances=DoubleArray(clusters)
    private val clusterX=DoubleArray(clusters)
    private val matrices=DoubleArray(clusters*6);private val affine=DoubleArray(6)
    private val groupSizes=intArrayOf(clusters,words+clusters,lineCapacity,1)
    private val groups=Array(4) { DoubleArray(groupSizes[it]*4) }
    private val fill=IntArray(clusters);private val stroke=IntArray(clusters)
    private val width=DoubleArray(clusters);private val blur=DoubleArray(clusters);private val opacity=DoubleArray(clusters)
    private val sourceEdges=document?.boundaries?.filter { it<document.text.length && document.text[it]!='\n' }?.toIntArray() ?: IntArray(0)
    private val sourceEnds=IntArray(sourceEdges.size) { i -> requireNotNull(document).boundaries[document.boundaries.binarySearch(sourceEdges[i])+1] }
    private val styles=Array(clusters) { c -> document?.runAt(sourceEdges.getOrNull(starts[c]) ?: 0)?.style ?: TextRunStyle() }
    private val manual=DoubleArray(characterCount).also { amounts ->
        if(document!=null) for(run in document.runs) for(k in run.style.manualKerning) {
            val character=sourceEdges.binarySearch(k.character);if(character>=0) amounts[character]+=k.amount
        }
    }
    private val runSpacing=DoubleArray(clusters) { c ->
        styles[c].trackingDelta.toDouble()
    }
    private val runManual=DoubleArray(clusters) { c -> var amount=0.0;for(character in starts[c] until ends[c]) amount+=manual[character];amount }
    private val tail=glyphBase+glyphs
    private val runSizes=DoubleArray(clusters) { if(data.size==tail+2+clusters) data[tail+2+it] else styles[it].sizeFactor.toDouble() }
    private val runBaseline=DoubleArray(clusters) { styles[it].baselineDelta.toDouble() }
    private val yOffsets=DoubleArray(clusters)
    val characters=if(TextCharacterPreparation.needed(text) && clusters>0) PreparedTextCharacters(requireNotNull(document),starts,ends,
        requireNotNull(characterCatalogue) { "Character animation needs worker preparation; retry preparation or narrow the animated range" },text.fontId) else null
    private val paragraph=if(paragraphActive) PreparedParagraphLayout(requireNotNull(document),text.alignment,lines,
        starts,ends,rawClusterLines,DoubleArray(clusters) { data[clusterBase+it*6+5]/data[0] },
        DoubleArray(clusters) { runSizes[it]*styles[it].horizontalScale/100 },runSpacing,runManual,
        BooleanArray(clusters) { c -> membership.maps[1][starts[c]]<0 },sourceEdges,
        breaks ?: com.fluxx.android.render.TextLineBreakPreparation.opportunities(requireNotNull(document)),paragraphKernel) else null
    val modifiers: ByteBuffer = ByteBuffer.allocateDirect(Math.multiplyExact(clusters,STRIDE)).order(ByteOrder.nativeOrder())
    val inkBounds=FloatArray(4)
    val logicalBounds=FloatArray(4)
    // Shared visual cluster geometry. Storage is allocated only during preparation.
    private val visualBounds=FloatArray(clusters*4)
    private val visualOrigins=FloatArray(clusters*2)
    private val strokeClamped=BooleanArray(clusters)
    private var hasEvaluation=false
    private var evaluatedTime=0L
    var selectorNs=0L;private set
    var geometryNs=0L;private set
    val coverageBuilds get()=engines.sumOf { it.coverageBuilds }
    val linePermutationBuilds get()=linePool.builds
    val linePermutationBytes get()=linePool.bytes
    init {
        require(lines<=LinePermutationPool.MAX_LINES) { "Text exceeds 256 lines per layer; reduce explicit paragraphs" }
        require(data[prefix]==271828.0 && (data.size==tail || (data.size==tail+2+clusters && data[tail]==314159.0 && data[tail+1]==clusters.toDouble())))
        var ordinal=0;var line=-1
        for(c in 0 until clusters) { if(clusterLines[c]!=line) { line=clusterLines[c];ordinal=0 };ordinals[c]=ordinal++ }
    }
    // TEXT PERF 10: per-frame scalars shared by the evaluation stages below. evaluate() was one 5,123-unit method that
    // ART never JIT-compiled (it ran interpreted every frame); the stages are the same statements in the same order.
    private var size=0.0;private var factor=0.0;private var tracking=0.0;private var baseline=0.0;private var step=0.0
    private var workingLines=0;private var baseFill=0;private var baseStroke=0;private var baseWidth=0.0
    private var rawMax=0.0;private var newMax=0.0
    /** Modifier records staged as floats and copied to [modifiers] in one bulk write (no per-float JNI poke). */
    private val records=FloatArray(clusters*(STRIDE/4))
    private val recordView=modifiers.asFloatBuffer()
    private val trig=AffineTrig()
    /** SDF tier inputs from the staged records (TEXT PERF 10): identical to scanning [modifiers]. */
    fun maxGlyphScale()=com.fluxx.android.render.SdfAtlasTiers.maxGlyphScale(records,clusters)
    fun maxModifierDistanceEm()=com.fluxx.android.render.SdfAtlasTiers.maxModifierDistanceEm(records,clusters)
    fun evaluate(time: Long) {
        if(hasEvaluation && evaluatedTime==time) { selectorNs=0;geometryNs=0;return }
        val began=System.nanoTime()
        evaluateSelectors(time)
        selectorNs=System.nanoTime()-began
        evaluateLines()
        positionClusters()
        applyAnimators()
        writeRecords()
        evaluateBounds()
        geometryNs=System.nanoTime()-began-selectorNs
        evaluatedTime=time;hasEvaluation=true
    }
    private fun evaluateSelectors(time: Long) {
        size=text.size.evaluate(time).toDouble();factor=size/data[0]
        tracking=text.tracking.evaluate(time).toDouble()*size/1000
        val leading=text.leading.evaluate(time).toDouble();baseline=text.baselineShift.evaluate(time).toDouble()
        step=(data[1]-data[2]+max(0.0,data[3]))*factor+leading
        paragraph?.evaluate(size,text.tracking.evaluate(time).toDouble(),step)
        workingLines=if(paragraph!=null) paragraph.lineCount else lines
        if(paragraph!=null) {
            paragraph.clusterLines.copyInto(clusterLines)
            for(c in 0 until clusters) nextInLine[c]=c+1<clusters && clusterLines[c]==clusterLines[c+1]
            membership.updateLines(clusterLines,workingLines)
        }
        boundary.fill(0.0);lineTotals.fill(0.0);lineShift.fill(0.0);runTotals.fill(0.0)
        lineCharacters.fill(0);extraSpacing.fill(0.0)
        characterTotals.fill(0.0);characters?.begin()
        for(c in 0 until clusters) lineCharacters[clusterLines[c]]+=ends[c]-starts[c]
        baseFill=text.fill.evaluate(time);baseStroke=text.strokeColour.evaluate(time)
        baseWidth=text.strokeWidth.evaluate(time).toDouble()
        if(paragraph==null) for(c in 0 until clusters) {
            val spacingScale=runSizes[c]*styles[c].horizontalScale/100
            val delta=runManual[c]*size*spacingScale/1000+(if(nextInLine[c]) runSpacing[c]*size*spacingScale/1000+tracking*(spacingScale-1) else 0.0)
            boundary[c]+=delta;runTotals[clusterLines[c]]+=delta
        }
        for(a in engines.indices) evaluateAnimator(a,time)
        characters?.complete(size,runSizes,styles)
        for(c in 0 until clusters) {
            val original=data[clusterBase+c*6+5]*factor
            val changed=characters!=null && characters.changed[c]
            clusterAdvances[c]=if(changed) characters!!.advances[c] else original
            if(changed) {
                val delta=clusterAdvances[c]-original;val l=clusterLines[c]
                boundary[c]+=delta;runTotals[l]+=delta;characterTotals[l]+=delta
            }
        }
        var accumulatedSpacing=0.0
        for(l in 0 until workingLines) { lineOffsets[l]=accumulatedSpacing;accumulatedSpacing+=extraSpacing[l] }
    }
    private fun evaluateAnimator(a: Int,time: Long) {
        val animator=text.animators[a]
        active[a]=animator.enabled.hold(time)!=0f
        if(!active[a]) return
        engines[a].evaluate(time,coverage[a])
        if(independent[a]) { engines[a].evaluate(time,coverageY[a],1);engines[a].evaluate(time,coverageZ[a],2) }
        values[a].fill(0.0)
        grouping[a]=animator.anchorGrouping.hold(time).toInt()
        for(p in properties[a]) {
            val i=p.property.ordinal
            when {
                p.scalar!=null -> values[a][i*2]=p.scalar.evaluate(time).toDouble()
                p.vector!=null -> { p.vector.evaluate(time,vector,0);values[a][i*2]=vector[0].toDouble();values[a][i*2+1]=vector[1].toDouble() }
                else -> colours[a][i]=requireNotNull(p.colour).evaluate(time)
            }
            if(p.property==AnimatorProperty.CHARACTER_OFFSET || p.property==AnimatorProperty.CHARACTER_VALUE)
                characters?.apply(p.property,values[a][i*2],coverage[a])
        }
        ownTotals.fill(0.0)
        if(present[a][AnimatorProperty.TRACKING.ordinal]) for(c in 0 until clusters) if(nextInLine[c]) {
            val delta=coverage[a][c]*values[a][AnimatorProperty.TRACKING.ordinal*2]*size*runSizes[c]/1000
            boundary[c]+=delta;ownTotals[clusterLines[c]]+=delta
        }
        val anchor=values[a][AnimatorProperty.LINE_ANCHOR.ordinal*2]
        for(l in 0 until workingLines) { lineTotals[l]+=ownTotals[l];lineShift[l]+=SelectorMath.lineShift(anchor,ownTotals[l]) }
        if(present[a][AnimatorProperty.LINE_SPACING.ordinal]) {
            ownTotals.fill(0.0)
            for(c in 0 until clusters) ownTotals[clusterLines[c]]+=coverage[a][c]*(ends[c]-starts[c])
            val value=values[a][AnimatorProperty.LINE_SPACING.ordinal*2]
            for(l in 0 until workingLines) if(lineCharacters[l]>0) extraSpacing[l]+=value*ownTotals[l]/lineCharacters[l]
        }
    }
    private fun evaluateLines() {
        var oldMax=0.0;newMax=0.0;rawMax=0.0
        for(l in 0 until lines) {
            rawAdvances[l]=data[6+l]*factor+(data[6+lines+glyphs*7+l].toInt()-1).coerceAtLeast(0)*tracking
            rawMax=max(rawMax,rawAdvances[l])
        }
        for(l in 0 until workingLines) {
            baseAdvances[l]=if(paragraph!=null) paragraph.lineAdvances[l]+characterTotals[l] else rawAdvances[l]+runTotals[l]
            advances[l]=baseAdvances[l]+lineTotals[l];oldMax=max(oldMax,baseAdvances[l]);newMax=max(newMax,advances[l])
        }
        val last=(lines-1).coerceAtLeast(0)*step
        logicalBounds[0]=0f;logicalBounds[1]=(-data[1]*factor+min(0.0,last)).toFloat()
        logicalBounds[2]=oldMax.coerceAtLeast(0.0).toFloat();logicalBounds[3]=(abs(last)+(data[1]-data[2])*factor).toFloat()
        if(lines==0) logicalBounds.fill(0f)
        else if(paragraph==null) {
            var first=Double.POSITIVE_INFINITY;var final=Double.NEGATIVE_INFINITY
            for(l in 0 until workingLines) {
                val y=l*step+lineOffsets[l]
                first=min(first,y-data[1]*factor);final=max(final,y-data[2]*factor)
            }
            logicalBounds[1]=first.toFloat();logicalBounds[3]=(final-first).toFloat()
        }
        if(paragraph!=null) {
            val source=requireNotNull(document)
            var left=0.0;var right=source.boxWidth.toDouble()
            var top=Double.POSITIVE_INFINITY;var bottom=Double.NEGATIVE_INFINITY
            for(l in 0 until workingLines) {
                if(source.boxWidth==0f) {
                    left=min(left,paragraph.origins[l]);right=max(right,paragraph.origins[l]+baseAdvances[l])
                }
                top=min(top,paragraph.lineTop[l]+lineOffsets[l]-data[1]*factor)
                bottom=max(bottom,paragraph.lineTop[l]+lineOffsets[l]-data[2]*factor)
            }
            logicalBounds[0]=left.toFloat();logicalBounds[1]=top.toFloat()
            logicalBounds[2]=max(0.0,right-left).toFloat();logicalBounds[3]=max(0.0,bottom-top).toFloat()
        }
    }
    private fun positionClusters() {
        var pen=0.0;var line=-1;val align=text.alignment.ordinal/2.0
        for(c in 0 until clusters) {
            val l=clusterLines[c];if(l!=line) { line=l;pen=0.0 }
            val rawLine=rawClusterLines[c]
            val rawX=data[clusterBase+c*6+4]*factor+ordinals[c]*tracking+(rawMax-rawAdvances[rawLine])*align
            if(paragraph!=null) {
                clusterX[c]=paragraph.x[c]+pen+lineShift[l]-(lineTotals[l]+characterTotals[l])*paragraph.align[l]
                offset[c]=clusterX[c]-rawX;yOffsets[c]=paragraph.y[c]+lineOffsets[l]-rawLine*step-runBaseline[c]
            } else {
                offset[c]=pen+lineShift[l]+((newMax-advances[l])-(rawMax-rawAdvances[l]))*align
                clusterX[c]=rawX+offset[c];yOffsets[c]=lineOffsets[l]-runBaseline[c]
            }
            pen+=boundary[c]
            fill[c]=styles[c].fill ?: baseFill;stroke[c]=styles[c].stroke ?: baseStroke
            width[c]=baseWidth+styles[c].strokeDelta;blur[c]=0.0;opacity[c]=1.0
            val i=c*6;matrices[i]=1.0;matrices[i+1]=0.0;matrices[i+2]=0.0;matrices[i+3]=1.0;matrices[i+4]=0.0;matrices[i+5]=0.0
        }
        for(g in groups) { var i=0;while(i<g.size) { g[i]=Double.POSITIVE_INFINITY;g[i+1]=Double.POSITIVE_INFINITY;g[i+2]=Double.NEGATIVE_INFINITY;g[i+3]=Double.NEGATIVE_INFINITY;i+=4 } }
        for(c in 0 until clusters) for(kind in 0..3) {
            val group=group(c,kind);val b=groups[kind];val i=group*4
            val x=clusterX[c];val right=x+clusterAdvances[c];val y=rawClusterLines[c]*step+yOffsets[c]-baseline
            b[i]=min(b[i],min(x,right));b[i+1]=min(b[i+1],y-data[1]*factor)
            b[i+2]=max(b[i+2],max(x,right));b[i+3]=max(b[i+3],y-data[2]*factor)
        }
    }
    private fun applyAnimators() {
        for(a in engines.indices) if(active[a]) applyAnimator(a)
    }
    private fun applyAnimator(a: Int) {
        // Ordinals once per animator (Enum.ordinal was a call per glyph in the debuggable JIT); same indices.
        val oGROUPING_ALIGNMENT=AnimatorProperty.GROUPING_ALIGNMENT.ordinal;val oFILL_OPACITY=AnimatorProperty.FILL_OPACITY.ordinal
        val oSTROKE_OPACITY=AnimatorProperty.STROKE_OPACITY.ordinal;val oFILL_HUE=AnimatorProperty.FILL_HUE.ordinal
        val oFILL_SATURATION=AnimatorProperty.FILL_SATURATION.ordinal;val oFILL_BRIGHTNESS=AnimatorProperty.FILL_BRIGHTNESS.ordinal
        val oSTROKE_HUE=AnimatorProperty.STROKE_HUE.ordinal;val oSTROKE_SATURATION=AnimatorProperty.STROKE_SATURATION.ordinal
        val oSTROKE_BRIGHTNESS=AnimatorProperty.STROKE_BRIGHTNESS.ordinal
        val v=values[a];val has=present[a]
        for(c in 0 until clusters) {
            val weight=coverage[a][c];val weightY=coverageY[a][c];val weightZ=coverageZ[a][c]
            if(weight==0.0 && weightY==0.0 && weightZ==0.0) continue
            val kind=grouping[a];val bounds=groups[kind];val gi=group(c,kind)*4
            val px=(bounds[gi]+bounds[gi+2])*.5+v[oGROUPING_ALIGNMENT*2]/100*(bounds[gi+2]-bounds[gi])
            val py=(bounds[gi+1]+bounds[gi+3])*.5+v[oGROUPING_ALIGNMENT*2+1]/100*(bounds[gi+3]-bounds[gi+1])
            SelectorMath.affine(affine,weight,v[0],v[1],px,py,v[12],v[13],v[4],
                if(has[1]) v[2] else 100.0,if(has[1]) v[3] else 100.0,v[8],v[10],weightY,trig)
            val i=c*6;val a0=matrices[i];val a1=matrices[i+1];val a2=matrices[i+2];val a3=matrices[i+3];val a4=matrices[i+4];val a5=matrices[i+5]
            matrices[i]=affine[0]*a0+affine[2]*a1;matrices[i+1]=affine[1]*a0+affine[3]*a1
            matrices[i+2]=affine[0]*a2+affine[2]*a3;matrices[i+3]=affine[1]*a2+affine[3]*a3
            matrices[i+4]=affine[0]*a4+affine[2]*a5+affine[4];matrices[i+5]=affine[1]*a4+affine[3]*a5+affine[5]
            if(has[3]) opacity[c]*=SelectorMath.clamp(1+weight*(v[6]/100-1),0.0,1.0)
            val colourWeight=SelectorMath.clamp(weight,0.0,1.0).toFloat()
            if(has[7]) fill[c]=if(independent[a]) TextAnimatorColour.blend(fill[c],colours[a][7],weight,weightY,weightZ) else interpolateColour(fill[c],colours[a][7],colourWeight)
            if(has[8]) stroke[c]=if(independent[a]) TextAnimatorColour.blend(stroke[c],colours[a][8],weight,weightY,weightZ) else interpolateColour(stroke[c],colours[a][8],colourWeight)
            val cw=colourWeight.toDouble();val cy=SelectorMath.clamp(weightY,0.0,1.0);val cz=SelectorMath.clamp(weightZ,0.0,1.0)
            if(has[oFILL_OPACITY]) fill[c]=TextAnimatorColour.opacity(fill[c],v[oFILL_OPACITY*2],cw)
            if(has[oSTROKE_OPACITY]) stroke[c]=TextAnimatorColour.opacity(stroke[c],v[oSTROKE_OPACITY*2],cw)
            fill[c]=TextAnimatorColour.hsb(fill[c],v[oFILL_HUE*2]*cw,
                v[oFILL_SATURATION*2]*cy,v[oFILL_BRIGHTNESS*2]*cz)
            stroke[c]=TextAnimatorColour.hsb(stroke[c],v[oSTROKE_HUE*2]*cw,
                v[oSTROKE_SATURATION*2]*cy,v[oSTROKE_BRIGHTNESS*2]*cz)
            width[c]+=weight*v[18];blur[c]+=weight*v[24]
        }
    }
    private fun writeRecords() {
        val distanceScale=48/(16*size)
        for(c in 0 until clusters) {
            val fontSize=size*runSizes[c]
            // The static faux outline shift consumes the same encoded distance as stroke/blur.
            val budget=(.10-if(styles[c].fauxBold) .025 else 0.0)*fontSize
            val effectiveWidth=coerce(width[c],0.0,2*budget)
            strokeClamped[c]=width[c]>effectiveWidth
            width[c]=effectiveWidth;blur[c]=coerce(blur[c],0.0,max(0.0,budget-width[c]/2))
            val record=c*STRIDE
            for(k in 0..5) put(record+k*4,matrices[c*6+k])
            put(record+24,offset[c]);put(record+28,yOffsets[c])
            colour(record+32,fill[c]);colour(record+48,stroke[c])
            put(record+64,width[c]/2*distanceScale/runSizes[c]);put(record+68,blur[c]*distanceScale/runSizes[c])
            put(record+72,opacity[c]);put(record+76,width[c]);put(record+80,blur[c])
            put(record+84,if(characters!=null && characters.changed[c]) 1.0 else 0.0)
        }
        recordView.position(0);recordView.put(records,0,records.size)
    }
    private fun evaluateBounds() {
        val align=text.alignment.ordinal/2.0
        for(c in 0 until clusters) {
            val j=c*4;visualBounds[j]=Float.POSITIVE_INFINITY;visualBounds[j+1]=Float.POSITIVE_INFINITY
            visualBounds[j+2]=Float.NEGATIVE_INFINITY;visualBounds[j+3]=Float.NEGATIVE_INFINITY
            val g=firstGlyph[c];val l=if(g>=0) data[6+lines+g*7].toInt() else 0
            val x=clusterX[c];val y=l*step-baseline+yOffsets[c];val m=c*6
            visualOrigins[c*2]=(matrices[m]*x+matrices[m+2]*y+matrices[m+4]).toFloat()
            visualOrigins[c*2+1]=(matrices[m+1]*x+matrices[m+3]*y+matrices[m+5]).toFloat()
        }
        var left=Double.POSITIVE_INFINITY;var top=left;var right=Double.NEGATIVE_INFINITY;var bottom=right
        for(g in 0 until glyphs) {
            val i=6+lines+g*7;val c=glyphClusters[g];val l=data[i].toInt()
            if(characters!=null && characters.changed[c]) continue
            if(data[i+5]<=0 || data[i+6]>=0) continue
            val padding=(width[c]/2+blur[c]+size*runSizes[c]/48)*max(styles[c].horizontalScale,styles[c].verticalScale)/100*(if(styles[c].fauxItalic) 1.213 else 1.0)
            val x=(data[i+1]+data[i+3])*factor+ordinals[c]*tracking+(rawMax-rawAdvances[l])*align+offset[c]-padding
            val y=l*step+(data[i+2]-data[i+4])*factor-baseline+yOffsets[c]-padding
            val w=data[i+5]*factor+padding*2;val h=-data[i+6]*factor+padding*2;val m=c*6
            for(corner in 0..3) {
                val xx=x+(if(corner and 1!=0) w else 0.0);val yy=y+(if(corner and 2!=0) h else 0.0)
                val tx=matrices[m]*xx+matrices[m+2]*yy+matrices[m+4]
                val ty=matrices[m+1]*xx+matrices[m+3]*yy+matrices[m+5]
                left=min(left,tx);top=min(top,ty);right=max(right,tx);bottom=max(bottom,ty)
                visualCorner(c,tx,ty)
            }
        }
        if(characters!=null) for(c in 0 until clusters) if(characters.changed[c]) {
            val bounds=characterBounds(c,left,top,right,bottom)
            left=bounds[0];top=bounds[1];right=bounds[2];bottom=bounds[3]
        }
        for(c in 0 until clusters) if(!visualBounds[c*4].isFinite()) {
            val x=visualOrigins[c*2];val y=visualOrigins[c*2+1]
            visualBounds[c*4]=x;visualBounds[c*4+1]=(y-data[1]*factor).toFloat()
            visualBounds[c*4+2]=(x+max(1.0,clusterAdvances[c])).toFloat();visualBounds[c*4+3]=(y-data[2]*factor).toFloat()
        }
        if(left.isFinite()) { inkBounds[0]=left.toFloat();inkBounds[1]=top.toFloat();inkBounds[2]=(right-left).toFloat();inkBounds[3]=(bottom-top).toFloat() }
        else inkBounds.fill(0f)
        for(value in inkBounds) require(value.isFinite()) { "Text animator arithmetic exceeds finite bounds" }
    }
    private val inkScratch=DoubleArray(4)
    /** Ink of one character-animated cluster (catalogue glyph boxes), folded into the running layer ink. */
    private fun characterBounds(c: Int,left0: Double,top0: Double,right0: Double,bottom0: Double): DoubleArray {
        val characters=requireNotNull(characters)
        var left=left0;var top=top0;var right=right0;var bottom=bottom0
        val scaleX=size*runSizes[c]*styles[c].horizontalScale/100
        val scaleY=size*runSizes[c]*styles[c].verticalScale/100
        val padding=(width[c]/2+blur[c]+size*runSizes[c]/48)*max(styles[c].horizontalScale,styles[c].verticalScale)/100*(if(styles[c].fauxItalic) 1.213 else 1.0)
        val baseY=rawClusterLines[c]*step-baseline+yOffsets[c]+data[6+lines+firstGlyph[c]*7+2]*factor
        val shear=if(styles[c].fauxItalic) tan(Math.toRadians(12.0)) else 0.0
        val bold=if(styles[c].fauxBold) .025*size*runSizes[c] else 0.0
        var pen=0.0
        for(character in starts[c] until ends[c]) {
            val metrics=characters.catalogue.metrics[characters.fontIndices[character]]
            for(part in 0..characters.marks[character].size) {
                val cp=if(part==0) characters.codes.getInt(character*4) else characters.marks[character][part-1]
                val index=metrics.index(cp)
                if(metrics.width(index)>0 && metrics.height(index)>0) {
                    val x=clusterX[c]+pen+metrics.bearingX(index)*scaleX-padding-bold*styles[c].horizontalScale/100
                    val y=baseY-metrics.bearingY(index)*scaleY-padding-bold*styles[c].verticalScale/100
                    val w=metrics.width(index)*scaleX+2*padding+2*bold*styles[c].horizontalScale/100
                    val h=metrics.height(index)*scaleY+2*padding+2*bold*styles[c].verticalScale/100;val m=c*6
                    for(corner in 0..3) {
                        val yy=y+(if(corner and 2!=0) h else 0.0)
                        val xx=x+(if(corner and 1!=0) w else 0.0)-shear*(yy-baseY)
                        val tx=matrices[m]*xx+matrices[m+2]*yy+matrices[m+4];val ty=matrices[m+1]*xx+matrices[m+3]*yy+matrices[m+5]
                        left=min(left,tx);top=min(top,ty);right=max(right,tx);bottom=max(bottom,ty)
                        visualCorner(c,tx,ty)
                    }
                }
                pen+=metrics.advance(index)*scaleX
            }
        }
        inkScratch[0]=left;inkScratch[1]=top;inkScratch[2]=right;inkScratch[3]=bottom
        return inkScratch
    }
    /** UI-only summary; renderer evaluation never calls this allocating formatter. */
    fun geometrySnapshot(): TextPreparedGeometry {
        val source=requireNotNull(document)
        return TextPreparedGeometry(source,
            IntArray(clusters) { sourceEdges.getOrNull(starts[it]) ?: 0 },
            IntArray(clusters) { sourceEnds.getOrNull(ends[it]-1) ?: source.text.length },
            clusterLines.copyOf(),clusterWords.copyOf(),visualBounds.copyOf(),visualOrigins.copyOf(),logicalBounds.copyOf())
    }
    private fun visualCorner(cluster: Int,x: Double,y: Double) {
        val i=cluster*4;visualBounds[i]=min(visualBounds[i],x.toFloat());visualBounds[i+1]=min(visualBounds[i+1],y.toFloat())
        visualBounds[i+2]=max(visualBounds[i+2],x.toFloat());visualBounds[i+3]=max(visualBounds[i+3],y.toFloat())
    }
    fun strokeClampLabel(start: Int,end: Int): String? {
        var clamped=false;var low=Double.POSITIVE_INFINITY;var high=0.0
        for(c in 0 until clusters) {
            val index=sourceEdges.getOrNull(starts[c]) ?: 0
            val limit=sourceEnds.getOrNull(ends[c]-1) ?: index+1
            if(start!=end && (end<=index || start>=limit)) continue
            clamped=clamped || strokeClamped[c];low=min(low,width[c]);high=max(high,width[c])
        }
        if(!clamped) return null
        fun rounded(value: Double)=(round(value*100)/100).toFloat().toString()
        return "Apply · clamp ${rounded(low)}–${rounded(high)} px"
    }
    /** coerceIn exactly, including its exception for an empty range, without the out-of-line stdlib call per value. */
    @Suppress("NOTHING_TO_INLINE") private inline fun coerce(x: Double,low: Double,high: Double): Double {
        if(low>high) throw IllegalArgumentException("Cannot coerce value to an empty range: maximum $high is less than minimum $low.")
        return if(x<low) low else if(x>high) high else x
    }
    private fun group(c: Int,kind: Int)=when(kind) {0->c;1->if(clusterWords[c]>=0) clusterWords[c] else words+c;2->clusterLines[c];else->0}
    // f-f==0 is isFinite() exactly (false for NaN and both infinities) without Float.isFinite -> Math.abs -> floatToRawIntBits.
    private fun put(offset: Int,value: Double) { val f=value.toFloat();require(f-f==0f) { "Text animator arithmetic exceeds finite geometry" };records[offset shr 2]=f }
    private fun colour(offset: Int,argb: Int) {
        val i=offset shr 2;records[i]=((argb ushr 16) and 255)/255f;records[i+1]=((argb ushr 8) and 255)/255f
        records[i+2]=(argb and 255)/255f;records[i+3]=(argb ushr 24)/255f
    }
    companion object { const val STRIDE=96 }
}
