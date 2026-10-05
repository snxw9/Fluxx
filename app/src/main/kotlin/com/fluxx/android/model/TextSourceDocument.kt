package com.fluxx.android.model

enum class TextFaceStyle { REGULAR, BOLD, ITALIC, BOLD_ITALIC }
enum class TextCaps { NONE, ALL, SMALL }
enum class TextScriptPosition { NORMAL, SUPER, SUB }
enum class TextKerning { METRICS, OFF }
enum class ParagraphAlignment { LEFT, CENTRE, RIGHT, JUSTIFY_LAST_LEFT, JUSTIFY_LAST_CENTRE, JUSTIFY_LAST_RIGHT, JUSTIFY_ALL }
enum class ParagraphDirection { LTR, RTL }

/** Requested identity survives fallback and library deletion. Axis tracks are held, not interpolated. */
data class TextFontAxis(val tag: String,val value: AnimatableProperty1D) {
    init {
        require(tag.length==4 && tag.all { it.code in 32..126 }) { "Font axis tags must have four ASCII characters" }
        require(!value.isAnimated && value.keyframes.isEmpty()) { "Animated variable-font axes are unsupported; use static axis values" }
    }
}
data class TextFontRef(val family: String="fluxx.sans",val style: TextFaceStyle=TextFaceStyle.REGULAR,
    val sha256: String?=null,val faceIndex: Int=0,val namedInstance: Int=-1,
    val axes: FrozenList<TextFontAxis> = FrozenList.empty(),val postScriptName: String="",val styleName: String="") {
    init {
        require(family.isNotBlank() && family.length<=128 && '\u0000' !in family)
        require(sha256==null || sha256.matches(Regex("[a-f0-9]{64}"))) { "Font references require a lowercase SHA-256" }
        require(faceIndex in 0..255 && namedInstance in -1..65535)
        require(sha256!=null || (faceIndex==0 && namedInstance==-1 && axes.isEmpty()))
        require(axes.size<=16 && axes.map { it.tag }.distinct().size==axes.size) { "A font reference supports at most 16 distinct axes" }
        require(postScriptName.length<=128 && '\u0000' !in postScriptName)
        require(styleName.length<=128 && '\u0000' !in styleName)
    }
    val bundledId get()=family+when(style) { TextFaceStyle.REGULAR->"";TextFaceStyle.BOLD->".bold";TextFaceStyle.ITALIC->".italic";TextFaceStyle.BOLD_ITALIC->".bold-italic" }
    companion object {
        val BUNDLED_IDS=setOf("fluxx.sans","fluxx.serif","fluxx.mono").flatMap { family ->
            listOf(family,"$family.bold","$family.italic","$family.bold-italic")
        }.toSet()
    }
}
/** Index is a UTF-16 source-character boundary, not a glyph or transformed display index. */
data class TextManualKerning(val character: Int,val amount: Float) {
    init { require(character>=0 && amount.isFinite() && amount in -10000f..10000f) { "Manual kerning must be finite and within ±10000/1000 em" } }
}
data class TextRunStyle(val font: TextFontRef?=null,val sizeFactor: Float=1f,
    val fill: Int?=null,val stroke: Int?=null,val strokeDelta: Float=0f,val trackingDelta: Float=0f,
    val baselineDelta: Float=0f,val fauxBold: Boolean=false,val fauxItalic: Boolean=false,
    val caps: TextCaps=TextCaps.NONE,val script: TextScriptPosition=TextScriptPosition.NORMAL,
    val horizontalScale: Float=100f,val verticalScale: Float=100f,val kerning: TextKerning=TextKerning.METRICS,
    val manualKerning: FrozenList<TextManualKerning> = FrozenList.empty()) {
    init {
        require(sizeFactor.isFinite() && sizeFactor>0 && sizeFactor<=100f) { "Run size factor must be positive and at most 100" }
        require(horizontalScale.isFinite() && verticalScale.isFinite() && horizontalScale in 0f..10000f && verticalScale in 0f..10000f)
        require(strokeDelta.isFinite() && trackingDelta.isFinite() && baselineDelta.isFinite())
        require(manualKerning.size<=TextLimits.MAX_SOURCE_UNITS)
        require(manualKerning.zipWithNext().all { it.first.character<it.second.character }) { "Manual kerning entries must be ordered and unique" }
    }
}
data class TextStyleRun(val start: Int,val end: Int,val style: TextRunStyle=TextRunStyle())
data class TextParagraphStyle(val alignment: ParagraphAlignment?=null,val indentLeft: Float=0f,val indentRight: Float=0f,
    val indentFirst: Float=0f,val spaceBefore: Float=0f,val spaceAfter: Float=0f,val direction: ParagraphDirection=ParagraphDirection.LTR) {
    init { require(listOf(indentLeft,indentRight,indentFirst,spaceBefore,spaceAfter).all { it.isFinite() }) }
}

/** Source Text's atomic held value. Runs are static relative formatting over the animated layer base. */
data class TextSourceDocument(val text: String,
    val runs: FrozenList<TextStyleRun> = defaultRuns(text),
    val paragraphs: FrozenList<TextParagraphStyle> = FrozenList.empty(),val boxWidth: Float=0f) {
    val boundaries: IntArray = TextSourceBoundaries.of(text)
    init {
        TextLimits.validateSource(text)
        require(boxWidth.isFinite() && boxWidth>=0) { "Text box width must be nonnegative and finite" }
        require(runs.size<=256) { "Source Text supports at most 256 style runs" }
        var end=0
        for(run in runs) {
            val insertion=text.isEmpty() && runs.size==1 && run.start==0 && run.end==0
            require(insertion || run.start==end && run.end>run.start && run.end<=text.length) { "Style runs must be contiguous and cover Source Text" }
            require(boundaries.binarySearch(run.start)>=0 && boundaries.binarySearch(run.end)>=0) { "Style boundaries must not split a surrogate pair or combining-mark cluster" }
            for(k in run.style.manualKerning) require(k.character in run.start until run.end && boundaries.binarySearch(k.character)>=0) { "Manual kerning must address a source character in its run" }
            end=run.end
        }
        require(end==text.length) { "Style runs must cover Source Text" }
        require(paragraphs.isEmpty() || paragraphs.size==text.count { it=='\n' }+1) { "Paragraph style count must match explicit paragraphs" }
    }
    fun runAt(caret: Int): TextStyleRun? = runs.firstOrNull { caret in it.start until it.end } ?: runs.lastOrNull()
    /** A collapsed selection formats all text; a nonempty selection addresses touched paragraphs.
     * A selection ending at the next paragraph's start does not touch that paragraph. */
    fun formatParagraphs(start: Int,end: Int,change: (TextParagraphStyle)->TextParagraphStyle): TextSourceDocument {
        require(start in 0..text.length && end in start..text.length)
        val count=text.count { it=='\n' }+1
        val first=if(start==end) 0 else text.take(start).count { it=='\n' }
        val last=if(start==end) count-1 else text.take(end-1).count { it=='\n' }
        val updated=List(count) { index ->
            val current=paragraphs.getOrNull(index) ?: TextParagraphStyle()
            if(index in first..last) change(current) else current
        }
        return copy(paragraphs=if(updated.all { it==TextParagraphStyle() }) FrozenList.empty() else FrozenList.of(updated))
    }
    fun format(start: Int,end: Int,change: (TextRunStyle)->TextRunStyle): TextSourceDocument {
        require(start in 0..text.length && end in start..text.length)
        if(text.isEmpty()) return copy(runs=FrozenList.of(listOf(TextStyleRun(0,0,change(runAt(0)?.style ?: TextRunStyle())))))
        val a=if(start==end) 0 else TextSourceBoundaries.floor(boundaries,start)
        val b=if(start==end) text.length else TextSourceBoundaries.ceiling(boundaries,end)
        val out=mutableListOf<TextStyleRun>()
        for(r in runs) {
            fun emit(s: Int,e: Int,style: TextRunStyle) { if(e>s) out.add(TextStyleRun(s,e,style.copy(manualKerning=FrozenList.of(style.manualKerning.filter { it.character in s until e })))) }
            emit(r.start,minOf(r.end,a),r.style)
            val lo=maxOf(r.start,a);val hi=minOf(r.end,b)
            if(hi>lo) emit(lo,hi,change(r.style))
            emit(maxOf(r.start,b),r.end,r.style)
        }
        return copy(runs=merge(out))
    }
    /** Minimal replacement range inherits the style at the caret; untouched ranges retain formatting. */
    fun replace(value: String, editStart: Int = -1, editEnd: Int = -1): TextSourceDocument {
        TextLimits.validateSource(value)
        if(value==text) return this
        if(value.isEmpty()) return copy(text=value,runs=FrozenList.of(listOf(TextStyleRun(0,0,
            (runAt(if(editStart>=0) editStart else 0)?.style ?: TextRunStyle()).copy(manualKerning=FrozenList.empty())))),
            paragraphs=if(paragraphs.isEmpty()) paragraphs else FrozenList.of(listOf(paragraphs.first())))
        val inserted=value.length-text.length+(editEnd-editStart)
        val hinted=editStart in 0..text.length && editEnd in editStart..text.length && inserted>=0 &&
            editStart+inserted<=value.length && value.regionMatches(0,text,0,editStart) &&
            value.regionMatches(editStart+inserted,text,editEnd,text.length-editEnd)
        var prefix=if(hinted) editStart else 0
        if(!hinted) while(prefix<minOf(text.length,value.length) && text[prefix]==value[prefix]) prefix++
        prefix=TextSourceBoundaries.floor(boundaries,prefix)
        var suffix=if(hinted) text.length-editEnd else 0
        if(!hinted) while(suffix<minOf(text.length-prefix,value.length-prefix) && text[text.lastIndex-suffix]==value[value.lastIndex-suffix]) suffix++
        val oldEnd=TextSourceBoundaries.ceiling(boundaries,text.length-suffix)
        suffix=text.length-oldEnd
        val newEnd=value.length-suffix;val delta=value.length-text.length
        val out=mutableListOf<TextStyleRun>()
        for(r in runs) if(r.start<prefix) out.add(TextStyleRun(r.start,minOf(r.end,prefix),r.style.copy(manualKerning=FrozenList.of(r.style.manualKerning.filter { it.character<prefix }))))
        if(newEnd>prefix) out.add(TextStyleRun(prefix,newEnd,(runAt(prefix)?.style ?: TextRunStyle()).copy(manualKerning=FrozenList.empty())))
        for(r in runs) if(r.end>oldEnd) out.add(TextStyleRun(maxOf(r.start,oldEnd)+delta,r.end+delta,
            r.style.copy(manualKerning=FrozenList.of(r.style.manualKerning.filter { it.character>=oldEnd }.map { it.copy(character=Math.addExact(it.character,delta)) }))))
        // New combining marks can join at either edit edge: inherit that whole cluster's initial style.
        val edges=TextSourceBoundaries.of(value);val normalized=mutableListOf<TextStyleRun>()
        for(i in 0 until edges.lastIndex) {
            val index=edges[i];val style=out.firstOrNull { index in it.start until it.end }?.style ?: TextRunStyle()
            normalized.add(TextStyleRun(index,edges[i+1],style.copy(manualKerning=FrozenList.of(style.manualKerning.filter { it.character==index }))))
        }
        val newParagraphs=if(paragraphs.isEmpty()) paragraphs else {
            val paragraphStarts=ArrayList<Int>();paragraphStarts.add(0)
            value.forEachIndexed { index,cp -> if(cp=='\n') paragraphStarts.add(index+1) }
            FrozenList.of(paragraphStarts.map { position ->
                val original=when { position<=prefix->position;position>=newEnd->position-delta;else->prefix }
                paragraphs[text.take(original.coerceIn(0,text.length)).count { it=='\n' }]
            })
        }
        return copy(text=value,runs=merge(normalized),paragraphs=newParagraphs)
    }
    fun storageBytes(): Long = text.toByteArray(Charsets.UTF_8).size.toLong()+runs.sumOf {
        96L+it.style.manualKerning.size*8L+(it.style.font?.let { f -> f.family.toByteArray().size+f.postScriptName.toByteArray().size+f.styleName.toByteArray().size+64L+f.axes.size*32L } ?: 0L)
    }+paragraphs.size*32L
    companion object {
        const val MAX_PREPARED_LAYOUTS=64
        private fun defaultRuns(text: String)=if(text.isEmpty()) FrozenList.empty() else FrozenList.of(listOf(TextStyleRun(0,text.length)))
        private fun merge(runs: List<TextStyleRun>): FrozenList<TextStyleRun> {
            val out=mutableListOf<TextStyleRun>()
            for(r in runs) {
                val previous=out.lastOrNull()
                if(previous!=null && previous.end==r.start && previous.style.copy(manualKerning=FrozenList.empty())==r.style.copy(manualKerning=FrozenList.empty()))
                    out[out.lastIndex]=previous.copy(end=r.end,style=previous.style.copy(manualKerning=FrozenList.of(previous.style.manualKerning+r.style.manualKerning)))
                else out.add(r)
            }
            return FrozenList.of(out)
        }
    }
}
object TextSourceBoundaries {
    fun of(text: String): IntArray {
        val out=ArrayList<Int>();out.add(0);var i=0
        while(i<text.length) {
            val cp=text.codePointAt(i);val type=Character.getType(cp)
            val mark=type==Character.NON_SPACING_MARK.toInt() || type==Character.COMBINING_SPACING_MARK.toInt() || type==Character.ENCLOSING_MARK.toInt()
            if(i>0 && (!mark || text[i-1]=='\n') && out.last()!=i) out.add(i)
            i+=Character.charCount(cp)
        }
        if(out.last()!=text.length) out.add(text.length)
        return out.toIntArray()
    }
    fun floor(edges: IntArray,index: Int): Int { val i=edges.binarySearch(index);return edges[if(i>=0) i else (-i-2).coerceAtLeast(0)] }
    fun ceiling(edges: IntArray,index: Int): Int { val i=edges.binarySearch(index);return edges[if(i>=0) i else (-i-1).coerceAtMost(edges.lastIndex)] }
}
