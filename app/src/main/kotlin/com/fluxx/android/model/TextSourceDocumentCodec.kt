package com.fluxx.android.model

import com.google.flatbuffers.FlatBufferBuilder

/** Rich Source Text is one held value; legacy string fields remain readable by the v10/v11 loaders. */
internal object TextSourceDocumentCodec {
    fun encode(b: FlatBufferBuilder, d: TextSourceDocument): Int {
        val runs=d.runs.map { r ->
            val s=r.style
            val font=s.font?.let { f ->
                val axes=f.axes.map { a ->
                    val tag=b.createString(a.tag)
                    val keys=fluxx.schema.AnimatableProperty1D.createKeyframesVector(b,intArrayOf())
                    val value=fluxx.schema.AnimatableProperty1D.createAnimatableProperty1D(b,false,a.value.staticValue,keys)
                    fluxx.schema.FontAxisValue.createFontAxisValue(b,tag,value)
                }.toIntArray()
                val axisVector=fluxx.schema.TextFontReference.createAxesVector(b,axes)
                val family=b.createString(f.family);val hash=f.sha256?.let(b::createString) ?: 0
                val ps=b.createString(f.postScriptName)
                val styleName=b.createString(f.styleName)
                fluxx.schema.TextFontReference.createTextFontReference(b,family,f.style.ordinal,hash,f.faceIndex,f.namedInstance,axisVector,ps,styleName)
            } ?: 0
            val manual=s.manualKerning.map { fluxx.schema.TextManualKerning.createTextManualKerning(b,it.character,it.amount) }.toIntArray()
            val manualVector=fluxx.schema.TextStyleRun.createManualKerningVector(b,manual)
            fluxx.schema.TextStyleRun.createTextStyleRun(b,r.start,r.end,font,s.sizeFactor,s.fill!=null,s.fill ?: -1,
                s.stroke!=null,s.stroke ?: 0xff000000.toInt(),s.strokeDelta,s.trackingDelta,s.baselineDelta,s.fauxBold,s.fauxItalic,
                s.caps.ordinal,s.script.ordinal,s.horizontalScale,s.verticalScale,s.kerning.ordinal,manualVector)
        }.toIntArray()
        val runVector=fluxx.schema.TextSourceDocument.createRunsVector(b,runs)
        val paragraphs=d.paragraphs.map { p -> fluxx.schema.TextParagraphStyle.createTextParagraphStyle(b,p.alignment?.ordinal ?: -1,
            p.indentLeft,p.indentRight,p.indentFirst,p.spaceBefore,p.spaceAfter,p.direction.ordinal) }.toIntArray()
        val paragraphVector=fluxx.schema.TextSourceDocument.createParagraphsVector(b,paragraphs)
        val text=b.createString(d.text)
        return fluxx.schema.TextSourceDocument.createTextSourceDocument(b,1u,text,runVector,paragraphVector,d.boxWidth)
    }

    fun decode(p: fluxx.schema.TextSourceDocument?, legacy: String, version: Int, bytes: Int, consume: (Long)->Unit): TextSourceDocument? {
        if(p==null) return null
        require(version>=12 && p.version==1u) { "Unsupported rich Source Text version; open with a compatible Fluxx version" }
        require(p.textAsByteBuffer.remaining()<=16384) { "Text source byte limit exceeded" }
        val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val text=decoder.decode(p.textAsByteBuffer.duplicate()).toString()
        require(text==legacy) { "Rich Source Text disagrees with its legacy string" }
        fun count(n: Int,max: Int) { require(n in 0..minOf(max,bytes/4)) { "Invalid rich Source Text vector count" } }
        fun <T> choice(values: List<T>,n: Int): T = requireNotNull(values.getOrNull(n)) { "Unsupported rich text enum $n" }
        count(p.runsLength,256);count(p.paragraphsLength,TextLimits.MAX_SOURCE_UNITS+1)
        val runs=(0 until p.runsLength).map { i ->
            val r=requireNotNull(p.runs(i));count(r.manualKerningLength,TextLimits.MAX_SOURCE_UNITS)
            val font=r.font?.let { f ->
                count(f.axesLength,16)
                val axes=(0 until f.axesLength).map { j ->
                    val a=requireNotNull(f.axes(j));val v=requireNotNull(a.value)
                    require(v.keyframesLength==0 && !v.isAnimated) { "Animated variable-font axes are unsupported; use static axis values" }
                    TextFontAxis(requireNotNull(a.tag),AnimatableProperty1D(staticValue=v.staticValue))
                }
                TextFontRef(requireNotNull(f.family),choice(TextFaceStyle.entries,f.style),f.sha256,f.faceIndex,f.namedInstance,FrozenList.of(axes),f.postScriptName.orEmpty(),f.styleName.orEmpty())
            }
            val manual=(0 until r.manualKerningLength).map { j -> val k=requireNotNull(r.manualKerning(j));TextManualKerning(k.character,k.amount) }
            TextStyleRun(r.start,r.end,TextRunStyle(font,r.sizeFactor,r.fill.takeIf { r.hasFill },r.stroke.takeIf { r.hasStroke },
                r.strokeDelta,r.trackingDelta,r.baselineDelta,r.fauxBold,r.fauxItalic,choice(TextCaps.entries,r.caps),
                choice(TextScriptPosition.entries,r.scriptPosition),r.horizontalScale,r.verticalScale,choice(TextKerning.entries,r.kerning),FrozenList.of(manual)))
        }
        val paragraphs=(0 until p.paragraphsLength).map { i -> val q=requireNotNull(p.paragraphs(i))
            require(q.alignment>=-1)
            TextParagraphStyle(if(q.alignment==-1) null else choice(ParagraphAlignment.entries,q.alignment),q.indentLeft,q.indentRight,
                q.indentFirst,q.spaceBefore,q.spaceAfter,choice(ParagraphDirection.entries,q.direction)) }
        return TextSourceDocument(text,FrozenList.of(runs),FrozenList.of(paragraphs),p.boxWidth).also {
            // The legacy string was already charged. Charge its second on-wire copy and formatting metadata.
            consume(it.storageBytes())
        }
    }
}
