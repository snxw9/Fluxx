package com.fluxx.android.model

import com.google.flatbuffers.FlatBufferBuilder
import fluxx.schema.TextAnimator as SA
import fluxx.schema.TextRangeSelector as SS
import fluxx.schema.TextPropertyOverride as SP

/** Additive v11/v12 tables; existing universal track payloads are reused. */
internal object TextAnimatorCodec {
    private fun easing(p: EasingPreset): Byte = if(p==EasingPreset.LINEAR) 0 else 1
    private fun easing(p: Byte): EasingPreset { require(p.toInt() in 0..1);return if(p.toInt()==0) EasingPreset.LINEAR else EasingPreset.EASY_EASE }
    private fun scalar(b: FlatBufferBuilder,p: AnimatableProperty1D): Int {
        val keys=p.keyframes.map { fluxx.schema.Keyframe1D.createKeyframe1D(b,it.timeUs,it.value,easing(it.easing)) }.toIntArray()
        val v=fluxx.schema.AnimatableProperty1D.createKeyframesVector(b,keys)
        return fluxx.schema.AnimatableProperty1D.createAnimatableProperty1D(b,p.isAnimated,p.staticValue,v)
    }
    private fun vector(b: FlatBufferBuilder,p: AnimatableProperty2D): Int {
        val keys=p.keyframes.map { fluxx.schema.Keyframe2D.createKeyframe2D(b,it.timeUs,it.x,it.y,easing(it.easing)) }.toIntArray()
        val v=fluxx.schema.AnimatableProperty2D.createKeyframesVector(b,keys)
        return fluxx.schema.AnimatableProperty2D.createAnimatableProperty2D(b,p.isAnimated,p.staticX,p.staticY,v)
    }
    private fun colour(b: FlatBufferBuilder,p: AnimatableColour): Int {
        val keys=p.keyframes.map { fluxx.schema.ColourKeyframe.createColourKeyframe(b,it.timeUs,it.argb,easing(it.easing)) }.toIntArray()
        val v=fluxx.schema.AnimatableColour.createKeyframesVector(b,keys)
        return fluxx.schema.AnimatableColour.createAnimatableColour(b,p.isAnimated,p.staticValue,v)
    }
    fun encode(b: FlatBufferBuilder,animators: List<TextAnimator>): Int {
        val groups=animators.map { a ->
            val selectors=a.selectors.map { s ->
                val t=s.tracks.map { scalar(b,it) }
                val seedKeys=s.seedKeyframes.map { fluxx.schema.TextSeedKeyframe.createTextSeedKeyframe(b,it.timeUs,it.seed.toULong()) }.toIntArray()
                val sk=SS.createSeedKeyframesVector(b,seedKeys)
                SS.createTextRangeSelector(b,3u,s.id.toULong(),t[0],t[1],t[2],t[3],t[4],t[5],t[6],t[7],t[8],t[9],t[10],t[11],t[12],t[13],t[14],s.seed.toULong(),
                    t[15],t[16],t[17],t[18],t[19],t[20],sk)
            }.toIntArray()
            val sv=SA.createSelectorsVector(b,selectors)
            val properties=a.properties.map { p ->
                val s=p.scalar?.let { scalar(b,it) } ?: 0;val v=p.vector?.let { vector(b,it) } ?: 0;val c=p.colour?.let { colour(b,it) } ?: 0
                SP.createTextPropertyOverride(b,if(p.property.ordinal>=13) 3u else 2u,(p.property.ordinal+1).toUInt(),s,v,c)
            }.toIntArray()
            val pv=SA.createPropertyOverridesVector(b,properties);val name=b.createString(a.name)
            val enabled=scalar(b,a.enabled);val grouping=scalar(b,a.anchorGrouping)
            return@map SA.createTextAnimator(b,2u,0,pv,a.id.toULong(),name,enabled,sv,grouping)
        }.toIntArray()
        return fluxx.schema.TextPayload.createTextAnimatorsVector(b,groups)
    }
    fun decode(payload: fluxx.schema.TextPayload,bytes: Int,version: Int): FrozenList<TextAnimator> {
        require(payload.textAnimatorsLength in 0..16)
        require(version>=11 || payload.textAnimatorsLength==0) { "Text animators require v11" }
        fun count(n: Int) { require(n in 0..bytes/4) }
        fun scalar(p: fluxx.schema.AnimatableProperty1D?): AnimatableProperty1D {
            requireNotNull(p);count(p.keyframesLength)
            return AnimatableProperty1D(p.isAnimated,p.staticValue,FrozenList.of((0 until p.keyframesLength).map {
                val k=requireNotNull(p.keyframes(it));Keyframe1D(k.timeUs,k.value,easing(k.easing)) }))
        }
        fun vector(p: fluxx.schema.AnimatableProperty2D): AnimatableProperty2D {
            count(p.keyframesLength)
            return AnimatableProperty2D(p.isAnimated,p.staticX,p.staticY,FrozenList.of((0 until p.keyframesLength).map {
                val k=requireNotNull(p.keyframes(it));Keyframe2D(k.timeUs,k.x,k.y,easing(k.easing)) }))
        }
        fun colour(p: fluxx.schema.AnimatableColour): AnimatableColour {
            count(p.keyframesLength)
            return AnimatableColour(p.isAnimated,p.staticValue,FrozenList.of((0 until p.keyframesLength).map {
                val k=requireNotNull(p.keyframes(it));ColourKeyframe(k.timeUs,k.argb,easing(k.easing)) }))
        }
        return FrozenList.of((0 until payload.textAnimatorsLength).map { index ->
            val a=requireNotNull(payload.textAnimators(index));require(a.version==2u) { "Unsupported text animator version ${a.version}; open with a compatible Fluxx version. 3D, paths and expressions are not supported." }
            require(a.rangeSelector==null) { "Singular range_selector is unsupported/ambiguous with v11 selectors" }
            require(a.selectorsLength in 0..8);require(a.propertyOverridesLength in 0..AnimatorProperty.entries.size)
            val selectors=FrozenList.of((0 until a.selectorsLength).map { i ->
                val s=requireNotNull(a.selectors(i));require(s.version==2u || (version>=12 && s.version==3u)) {
                    "Unsupported text selector version ${s.version}; open with a compatible Fluxx version. Expression selectors are unsupported."
                }
                require(version>=12 || (s.kind==null && s.wigglesPerSecond==null && s.correlation==null && s.temporalPhase==null &&
                    s.spatialPhase==null && s.lockDimensions==null && s.seedKeyframesLength==0)) { "Wiggly/seed tracks require schema v12" }
                require(s.seedKeyframesLength in 0..4096);count(s.seedKeyframesLength)
                fun optional(p: fluxx.schema.AnimatableProperty1D?,value: Float)=p?.let { scalar(it) } ?: com.fluxx.android.model.scalar(value)
                TextRangeSelector(s.id.toLong(),scalar(s.enabled),scalar(s.start),scalar(s.end),scalar(s.offset),scalar(s.units),
                    scalar(s.basedOn),scalar(s.shape),scalar(s.smoothness),scalar(s.easeHigh),scalar(s.easeLow),scalar(s.mode),
                    scalar(s.amount),scalar(s.minAmount),scalar(s.maxAmount),scalar(s.randomizeOrder),s.seed.toLong(),
                    optional(s.kind,0f),optional(s.wigglesPerSecond,2f),optional(s.correlation,50f),optional(s.temporalPhase,0f),
                    optional(s.spatialPhase,0f),optional(s.lockDimensions,1f),FrozenList.of((0 until s.seedKeyframesLength).map { k ->
                        val key=requireNotNull(s.seedKeyframes(k));TextSeedKeyframe(key.timeUs,key.seed.toLong())
                    }))
            })
            val properties=FrozenList.of((0 until a.propertyOverridesLength).map { i ->
                val p=requireNotNull(a.propertyOverrides(i));require(p.version==2u || (version>=12 && p.version==3u)) { "Unsupported text property table version ${p.version}; open with a compatible Fluxx version." }
                require(p.propertyId in 1u..(if(version>=12) AnimatorProperty.entries.size else 13).toUInt()) {
                    "Unsupported text animator property ${p.propertyId}; open with a compatible Fluxx version. 3D and expressions are unsupported."
                }
                TextPropertyOverride(AnimatorProperty.entries[p.propertyId.toInt()-1],p.scalar?.let { scalar(it) },
                    p.vector?.let { vector(it) },p.colour?.let { colour(it) })
            })
            TextAnimator(a.id.toLong(),requireNotNull(a.name),scalar(a.enabled),scalar(a.anchorGrouping),selectors,properties)
        })
    }
}
