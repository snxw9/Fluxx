package com.fluxx.android.model

import kotlin.math.*

enum class TextSplit { CHARACTERS, WORDS, LINES, PARAGRAPHS, STYLE_RUNS }
enum class TextPiecePivot { GROUP, EACH }
data class TextExplodeOptions(val split: TextSplit=TextSplit.CHARACTERS,val skipSpaces: Boolean=true,
    val keepOriginal: Boolean=false,val pivot: TextPiecePivot=TextPiecePivot.GROUP)
data class TextExplodePlan(val original: CompositionLayer,val options: TextExplodeOptions,
    val pieces: List<CompositionLayer>,val sampledTime: Long)

/** Preparation/editor operation only. Produces ordinary, editable source documents, never a frozen mesh. */
object TextExplode {
    fun sampleTime(layer: CompositionLayer,playhead: Long): Long =
        if(layer.timing.isActive(playhead)) playhead else layer.timing.startUs

    fun summary(layer: CompositionLayer,playhead: Long): String =
        "Freeze Source Text, size, tracking, leading, baseline, Ligatures and box width at ${sampleTime(layer,playhead)} µs. " +
        "Drop ${layer.text.animators.size} animator(s), including their paint effects. Keep layer timing, transform, opacity and paint tracks. " +
        "Characters use the whole layer with optional ligatures off; contextual character forms may change. " +
        "Lines, paragraphs and style runs retain the box. Exactness checks refuse layouts that ordinary text pieces cannot reproduce."

    suspend fun prepare(layer: CompositionLayer,composition: Composition,playhead: Long,firstId: Long,
        options: TextExplodeOptions,joinedWords: List<TextSelectionRange> = emptyList(),
        geometry: suspend (TextProperties)->TextPreparedGeometry): TextExplodePlan {
        require(layer.type==LayerType.TEXT)
        if(options.pivot==TextPiecePivot.EACH) require(!layer.animTransform.position.isAnimated &&
            !layer.animTransform.scale.isAnimated && !layer.animTransform.rotation.isAnimated) {
            "Each piece pivot needs static position, scale and rotation. Choose Group pivot to keep transform animation."
        }
        val sample=sampleTime(layer,playhead);val local=Math.subtractExact(sample,layer.resolvedKeyframeAnchorUs)
        val original=layer.text;val document=original.source.evaluateDocument(local)
        fun held(value: Float)=AnimatableProperty1D(staticValue=value)
        val frozen=original.copy(source=AnimatableString(staticValue=document.text,document=document),
            size=held(original.size.evaluate(local)),tracking=held(original.tracking.evaluate(local)),
            leading=held(original.leading.evaluate(local)),baselineShift=held(original.baselineShift.evaluate(local)),
            ligatures=held(if(options.split==TextSplit.CHARACTERS) 0f else original.ligatures.hold(local)),
            animators=FrozenList.empty(),fill=AnimatableColour(staticValue=original.fill.evaluate(local)),
            strokeColour=AnimatableColour(staticValue=original.strokeColour.evaluate(local)),
            strokeWidth=held(original.strokeWidth.evaluate(local)),strokeOverFill=held(original.strokeOverFill.hold(local)))
        // The reference includes evaluated layout animators; pieces must reproduce those positions or refuse.
        val reference=geometry(original.copy(source=frozen.source,ligatures=frozen.ligatures))
        if(options.split==TextSplit.CHARACTERS && original.animators.isNotEmpty()) {
            val without=geometry(frozen)
            require(reference.count==without.count) { "Character-changing animators cannot be baked into ordinary text pieces. Remove them before exploding." }
            for(c in reference.starts.indices) for(k in 0..3) {
                val axis=k%2
                require(abs((reference.boxes[c*4+k]-reference.origins[c*2+axis])-
                    (without.boxes[c*4+k]-without.origins[c*2+axis]))<.002f) {
                    "This animator changes glyph shape, scale or rotation. Ordinary text pieces cannot preserve it while copying layer transforms. Remove it before exploding."
                }
            }
        }
        val ranges=ranges(document,reference,options,joinedWords)
        require(ranges.isNotEmpty()) { "No pieces to explode. Turn off Skip spaces for whitespace text."
        }
        val bounds=reference.logical
        val pivotX=bounds[0]+layer.anchorX*bounds[2];val pivotY=bounds[1]+layer.anchorY*bounds[3]
        val result=ArrayList<CompositionLayer>(ranges.size)
        for((index,range) in ranges.withIndex()) {
            val boxed=options.split in listOf(TextSplit.LINES,TextSplit.PARAGRAPHS,TextSplit.STYLE_RUNS)
            var slice=slice(document,range.start,range.end,if(boxed) document.boxWidth else 0f)
            if(!boxed) slice=slice.copy(paragraphs=FrozenList.of(List(slice.text.count { it=='\n' }+1) { TextParagraphStyle(alignment=ParagraphAlignment.LEFT) }))
            if(options.split==TextSplit.LINES) {
                val p=document.text.take(range.start).count { it=='\n' }
                val style=document.paragraphs.getOrNull(p) ?: TextParagraphStyle()
                val line=reference.lines[reference.starts.indexOf(range.start)]
                val paragraphEnd=document.text.indexOf('\n',range.start).let { if(it<0) document.text.length else it }
                val last=reference.starts.indices.none { reference.starts[it]>=range.end && reference.starts[it]<paragraphEnd && reference.lines[it]!=line }
                val alignment=style.alignment ?: ParagraphAlignment.entries[original.alignment.ordinal]
                val mapped=if(!last && alignment.ordinal>=3) ParagraphAlignment.JUSTIFY_ALL else alignment
                val paragraphStart=if(p==0) 0 else document.text.lastIndexOf('\n',range.start-1)+1
                slice=slice.copy(paragraphs=FrozenList.of(listOf(style.copy(alignment=mapped,
                    indentFirst=if(range.start==paragraphStart) style.indentFirst else 0f,spaceBefore=0f,spaceAfter=0f))))
            }
            if(options.split==TextSplit.STYLE_RUNS && document.boxWidth>0f) {
                val first=reference.starts.indexOf(range.start)
                val sourceParagraph=document.text.take(range.start).count {it=='\n'}
                val paragraphStart=if(sourceParagraph==0) 0 else document.text.lastIndexOf('\n',range.start-1)+1
                val last=reference.starts.indices.lastOrNull {reference.starts[it]<range.end && reference.ends[it]>range.start}
                slice=slice.copy(paragraphs=FrozenList.of(slice.paragraphs.mapIndexed {p,style->
                    var adjusted=style
                    if(p==0 && range.start>paragraphStart && first>=0) adjusted=adjusted.copy(indentFirst=reference.origins[first*2]-style.indentLeft)
                    if(p==slice.paragraphs.lastIndex && last!=null) {
                        val paragraphEnd=document.text.indexOf('\n',reference.starts[last]).let {if(it<0) document.text.length else it}
                        val lastVisualLine=reference.starts.indices.none {reference.starts[it]>=range.end && reference.starts[it]<paragraphEnd && reference.lines[it]!=reference.lines[last]}
                        val a=style.alignment ?: ParagraphAlignment.entries[original.alignment.ordinal]
                        if(!lastVisualLine && a.ordinal>=3) adjusted=adjusted.copy(alignment=ParagraphAlignment.JUSTIFY_ALL)
                    }
                    adjusted
                }))
            }
            // Paint tracks retain their original signed keyframe time origin. Geometry is sampled at local.
            val text=frozen.copy(source=AnimatableString(staticValue=slice.text,document=slice),alignment=TextAlignment.LEFT,
                fill=original.fill,strokeColour=original.strokeColour,strokeWidth=original.strokeWidth,strokeOverFill=original.strokeOverFill)
            val pieceGeometry=geometry(text)
            val first=reference.starts.indexOf(range.start)
            require(first>=0 && pieceGeometry.count>0) { "This split cuts a shaped cluster. Choose Words or Paragraphs."
            }
            val dx=reference.origins[first*2]-pieceGeometry.origins[0]
            val dy=reference.origins[first*2+1]-pieceGeometry.origins[1]
            if(options.split!=TextSplit.CHARACTERS || range in joinedWords) verify(reference,pieceGeometry,range,dx,dy)
            val b=pieceGeometry.logical
            fun anchor(target: Float,origin: Float,length: Float): Float {
                require(length>0f || abs(target-origin)<.002f) { "A zero-size piece cannot retain this Group pivot. Choose Each piece with static transforms, or Skip spaces."
                }
                val value=if(length==0f) .5f else (target-origin)/length
                require(value.isFinite() && abs(value)<=CompositionLayer.MAX_ANCHOR) { "Exploded pivot exceeds the supported anchor bounds. Choose Each piece with static transforms."
                }
                return value
            }
            var piece=layer.copy(id=Math.addExact(firstId,index.toLong()),text=text,
                name=slice.text.replace('\n',' ').take(40).ifBlank { "Space ${index+1}" },
                keyframeAnchorUs=layer.resolvedKeyframeAnchorUs,
                anchorX=if(options.pivot==TextPiecePivot.GROUP) anchor(pivotX-dx,b[0],b[2]) else .5f,
                anchorY=if(options.pivot==TextPiecePivot.GROUP) anchor(pivotY-dy,b[1],b[3]) else .5f)
            if(options.pivot==TextPiecePivot.EACH) {
                val t=layer.evaluatedTransform(sample);val angle=Math.toRadians(t.rotationDegrees.toDouble())
                val x=dx+b[0]+b[2]/2-pivotX;val y=dy+b[1]+b[3]/2-pivotY
                val rw=layer.referenceWidth.takeIf { it>0 } ?: composition.width
                val rh=layer.referenceHeight.takeIf { it>0 } ?: composition.height
                val position=t.copy(positionX=t.positionX+(2*(cos(angle)*t.scaleX*x-sin(angle)*t.scaleY*y)/rw).toFloat(),
                    positionY=t.positionY+(2*(sin(angle)*t.scaleX*x+cos(angle)*t.scaleY*y)/rh).toFloat())
                piece=piece.copy(anchorX=.5f,anchorY=.5f,transform=position,
                    animTransform=layer.animTransform.copy(position=AnimatableProperty2D(staticX=position.positionX,staticY=position.positionY)))
            }
            result.add(piece)
        }
        return TextExplodePlan(layer,options,result,sample)
    }

    private fun verify(original: TextPreparedGeometry,piece: TextPreparedGeometry,range: TextSelectionRange,dx: Float,dy: Float) {
        val selected=original.starts.indices.filter {original.starts[it]>=range.start && original.ends[it]<=range.end}
        fun fail(): Nothing=throw IllegalArgumentException("This ${range.start}–${range.end} piece changes wrapping, justification or contextual shaping. Choose Characters or a larger Paragraphs split; no layers were changed.")
        if(selected.size!=piece.count) fail()
        for((n,c) in selected.withIndex()) {
            if(original.starts[c]-range.start!=piece.starts[n] || original.ends[c]-range.start!=piece.ends[n]) fail()
            if(abs(original.origins[c*2]-piece.origins[n*2]-dx)>.002f || abs(original.origins[c*2+1]-piece.origins[n*2+1]-dy)>.002f) fail()
            for(k in 0..3) if(abs(original.boxes[c*4+k]-piece.boxes[n*4+k]-(if(k%2==0) dx else dy))>.002f) fail()
        }
    }

    internal fun ranges(document: TextSourceDocument,g: TextPreparedGeometry,options: TextExplodeOptions,
        joinedWords: List<TextSelectionRange> = emptyList()): List<TextSelectionRange> {
        val ranges=when(options.split) {
            TextSplit.CHARACTERS->document.boundaries.toList().zipWithNext().map {edge->
                joinedWords.firstOrNull {edge.first>=it.start && edge.first<it.end} ?: TextSelectionRange(edge.first,edge.second) }.distinct()
            TextSplit.WORDS->g.starts.indices.groupBy { if(g.words[it]<0) -it-1 else g.words[it] }.values.map {
                TextSelectionRange(it.minOf {c->g.starts[c]},it.maxOf {c->g.ends[c]}) }
            TextSplit.LINES->g.starts.indices.groupBy {g.lines[it]}.values.map {TextSelectionRange(it.minOf {c->g.starts[c]},it.maxOf {c->g.ends[c]})}
            TextSplit.PARAGRAPHS->{ val starts=listOf(0)+document.text.indices.filter {document.text[it]=='\n'}.map {it+1}
                starts.map {a->TextSelectionRange(a,document.text.indexOf('\n',a).let {if(it<0) document.text.length else it})} }
            TextSplit.STYLE_RUNS->document.runs.map {TextSelectionRange(it.start,it.end)}
        }
        return ranges.filter {it.end>it.start && (!options.skipSpaces || !document.text.substring(it.start,it.end).isBlank())}
            .filter {document.text.substring(it.start,it.end).any {it!='\n'}}.sortedBy {it.start}
    }

    internal fun slice(document: TextSourceDocument,start: Int,end: Int,width: Float): TextSourceDocument {
        val runs=document.runs.filter {it.end>start && it.start<end}.map {run->
            val a=maxOf(start,run.start);val b=minOf(end,run.end)
            TextStyleRun(a-start,b-start,run.style.copy(manualKerning=FrozenList.of(run.style.manualKerning
                .filter {it.character in a until b}.map {it.copy(character=Math.subtractExact(it.character,start))}))) }
        val first=document.text.take(start).count {it=='\n'}
        val value=document.text.substring(start,end)
        return TextSourceDocument(value,FrozenList.of(runs),FrozenList.of(List(value.count {it=='\n'}+1) {
            document.paragraphs.getOrNull(first+it) ?: TextParagraphStyle() }),width)
    }
}
