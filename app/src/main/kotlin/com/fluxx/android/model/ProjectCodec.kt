package com.fluxx.android.model

import com.google.flatbuffers.FlatBufferBuilder
import java.nio.ByteBuffer
import fluxx.schema.Layer as SLayer
import fluxx.schema.Composition as SComp
import fluxx.schema.Project as SProject

/** Pure binary codec. Access checks and metadata IO belong to the repository. */
object ProjectCodec {
    fun encode(project: ProjectDocument): ByteArray {
        val b = FlatBufferBuilder(1024)
        val comp = project.composition
        val offsets = comp.layers.map { layer ->
            val uri = layer.asset?.uri?.let(b::createString) ?: 0
            val assetId = layer.asset?.id?.let(b::createString) ?: 0
            val name = b.createString(layer.name)

            val animPosOffset = run {
                val kfOffsets = layer.animTransform.position.keyframes.map { kf ->
                    fluxx.schema.Keyframe2D.createKeyframe2D(b, kf.timeUs, kf.x, kf.y, (if (kf.easing == EasingPreset.LINEAR) 0 else 1).toByte())
                }.toIntArray()
                val kfVec = fluxx.schema.AnimatableProperty2D.createKeyframesVector(b, kfOffsets)
                fluxx.schema.AnimatableProperty2D.createAnimatableProperty2D(b, layer.animTransform.position.isAnimated,
                    layer.animTransform.position.staticX, layer.animTransform.position.staticY, kfVec)
            }

            val animScaleOffset = run {
                val kfOffsets = layer.animTransform.scale.keyframes.map { kf ->
                    fluxx.schema.Keyframe2D.createKeyframe2D(b, kf.timeUs, kf.x, kf.y, (if (kf.easing == EasingPreset.LINEAR) 0 else 1).toByte())
                }.toIntArray()
                val kfVec = fluxx.schema.AnimatableProperty2D.createKeyframesVector(b, kfOffsets)
                fluxx.schema.AnimatableProperty2D.createAnimatableProperty2D(b, layer.animTransform.scale.isAnimated,
                    layer.animTransform.scale.staticX, layer.animTransform.scale.staticY, kfVec)
            }

            val animRotOffset = run {
                val kfOffsets = layer.animTransform.rotation.keyframes.map { kf ->
                    fluxx.schema.Keyframe1D.createKeyframe1D(b, kf.timeUs, kf.value, (if (kf.easing == EasingPreset.LINEAR) 0 else 1).toByte())
                }.toIntArray()
                val kfVec = fluxx.schema.AnimatableProperty1D.createKeyframesVector(b, kfOffsets)
                fluxx.schema.AnimatableProperty1D.createAnimatableProperty1D(b, layer.animTransform.rotation.isAnimated,
                    layer.animTransform.rotation.staticValue, kfVec)
            }

            val animOpOffset = run {
                val kfOffsets = layer.animTransform.opacity.keyframes.map { kf ->
                    fluxx.schema.Keyframe1D.createKeyframe1D(b, kf.timeUs, kf.value, (if (kf.easing == EasingPreset.LINEAR) 0 else 1).toByte())
                }.toIntArray()
                val kfVec = fluxx.schema.AnimatableProperty1D.createKeyframesVector(b, kfOffsets)
                fluxx.schema.AnimatableProperty1D.createAnimatableProperty1D(b, layer.animTransform.opacity.isAnimated,
                    layer.animTransform.opacity.staticValue, kfVec)
            }

            val textOffset = if (layer.type == LayerType.TEXT) encodeText(b, layer.text) else 0
            val markerVector = SLayer.createMarkersVector(b, encodeMarkers(b, layer.markers))
            SLayer.startLayer(b)
            if (textOffset != 0) SLayer.addTextPayload(b, textOffset)
            SLayer.addMarkers(b, markerVector)
            SLayer.addId(b, layer.id.toULong())
            with(layer.evaluatedTransform(layer.timing.startUs)) {
                SLayer.addPositionX(b, positionX); SLayer.addPositionY(b, positionY)
                SLayer.addScaleX(b, scaleX); SLayer.addScaleY(b, scaleY)
                SLayer.addRotation(b, rotationDegrees); SLayer.addOpacity(b, opacity)
            }
            SLayer.addAssetUri(b, uri); SLayer.addAssetId(b, assetId)
            SLayer.addType(b, layer.type.ordinal); SLayer.addZOrder(b, layer.zOrder)
            SLayer.addStartUs(b, layer.timing.startUs); SLayer.addSourceInUs(b, layer.timing.sourceInUs)
            SLayer.addDurationUs(b, layer.timing.durationUs ?: -1)
            SLayer.addSourceDurationUs(b, layer.asset?.durationUs ?: -1)
            SLayer.addVisible(b, layer.visible); SLayer.addMuted(b, layer.muted)
            SLayer.addAudioGain(b, layer.audioGain)
            SLayer.addName(b, name); SLayer.addSolidColorArgb(b, layer.solidColorArgb)
            SLayer.addReferenceWidth(b, layer.referenceWidth); SLayer.addReferenceHeight(b, layer.referenceHeight)
            SLayer.addKeyframeAnchorUs(b, layer.keyframeAnchorUs ?: -1L)
            SLayer.addHasKeyframeAnchor(b, layer.keyframeAnchorUs != null)
            SLayer.addAnchorX(b, layer.anchorX); SLayer.addAnchorY(b, layer.anchorY)
            if (animPosOffset != 0) SLayer.addAnimPosition(b, animPosOffset)
            if (animScaleOffset != 0) SLayer.addAnimScale(b, animScaleOffset)
            if (animRotOffset != 0) SLayer.addAnimRotation(b, animRotOffset)
            if (animOpOffset != 0) SLayer.addAnimOpacity(b, animOpOffset)
            SLayer.endLayer(b)
        }.toIntArray()
        val layers = SComp.createLayersVector(b, offsets)
        val markerVector = SComp.createMarkersVector(b, encodeMarkers(b, comp.markers))
        SComp.startComposition(b)
        SComp.addStartTimecodeUs(b, comp.startTimecodeUs)
        SComp.addMarkers(b, markerVector)
        SComp.addLayers(b, layers); SComp.addWidth(b, comp.width); SComp.addHeight(b, comp.height)
        SComp.addDurationUs(b, comp.durationUs ?: -1)
        SComp.addFpsNumerator(b, comp.frameRate.numerator); SComp.addFpsDenominator(b, comp.frameRate.denominator)
        val c = SComp.endComposition(b)
        val p = SProject.createProject(b, CompositionDefaults.PROJECT_VERSION, c)
        b.finish(p)
        return b.sizedByteArray()
    }

    private fun encodeText(b: FlatBufferBuilder, text: TextProperties): Int {
        val strings = text.source.keyframes.map { k ->
            val value = b.createString(k.value)
            fluxx.schema.StringKeyframe.createStringKeyframe(b, k.timeUs, value)
        }.toIntArray()
        val stringKeys = fluxx.schema.AnimatableString.createKeyframesVector(b, strings)
        val value = b.createString(text.source.staticValue)
        val source = fluxx.schema.AnimatableString.createAnimatableString(b, text.source.isAnimated, value, stringKeys)
        val sizes = text.size.keyframes.map { fluxx.schema.Keyframe1D.createKeyframe1D(b, it.timeUs, it.value,
            (if (it.easing == EasingPreset.LINEAR) 0 else 1).toByte()) }.toIntArray()
        val sizeKeys = fluxx.schema.AnimatableProperty1D.createKeyframesVector(b, sizes)
        val size = fluxx.schema.AnimatableProperty1D.createAnimatableProperty1D(b, text.size.isAnimated, text.size.staticValue, sizeKeys)
        val colours = text.fill.keyframes.map { fluxx.schema.ColourKeyframe.createColourKeyframe(b, it.timeUs, it.argb,
            (if (it.easing == EasingPreset.LINEAR) 0 else 1).toByte()) }.toIntArray()
        val colourKeys = fluxx.schema.AnimatableColour.createKeyframesVector(b, colours)
        val fill = fluxx.schema.AnimatableColour.createAnimatableColour(b, text.fill.isAnimated, text.fill.staticValue, colourKeys)
        val font = b.createString(text.fontId)
        return fluxx.schema.TextPayload.createTextPayload(b, source, size, fill, font, text.alignment.ordinal, 0)
    }
    private fun decodeText(payload: fluxx.schema.TextPayload?, byteCount: Int, consume: (Long) -> Unit): TextProperties {
        if (payload == null) return TextProperties()
        require(payload.textAnimatorsLength == 0) { "Text animators are not supported by this version" }
        fun count(value: Int) { require(value in 0..byteCount / 4) { "Invalid text key count" } }
        fun source(buffer: ByteBuffer?): String {
            if (buffer == null) return ""
            require(buffer.remaining() <= 16384) { "Text source byte limit exceeded" }
            consume(buffer.remaining().toLong())
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            return decoder.decode(buffer.duplicate()).toString().also(TextLimits::validateSource)
        }
        fun easing(value: Byte): EasingPreset { require(value.toInt() in 0..1) { "Unsupported text easing" }
            return if (value.toInt() == 0) EasingPreset.LINEAR else EasingPreset.EASY_EASE }
        val source = payload.source?.let { p ->
            count(p.keyframesLength)
            AnimatableString(p.isAnimated, source(p.staticValueAsByteBuffer), FrozenList.of((0 until p.keyframesLength).map {
                val k = requireNotNull(p.keyframes(it)); StringKeyframe(k.timeUs, source(k.valueAsByteBuffer)) }))
        } ?: AnimatableString()
        val size = payload.size?.let { p ->
            count(p.keyframesLength)
            val keys = (0 until p.keyframesLength).map { val k = requireNotNull(p.keyframes(it))
                Keyframe1D(k.timeUs, TextLimits.decodeSize(k.value), easing(k.easing)) }
            TextLimits.ordered(keys.map { it.timeUs })
            AnimatableProperty1D(p.isAnimated, TextLimits.decodeSize(p.staticValue), FrozenList.of(keys))
        } ?: AnimatableProperty1D(staticValue = 72f)
        val fill = payload.fill?.let { p ->
            count(p.keyframesLength)
            AnimatableColour(p.isAnimated, p.staticValue, FrozenList.of((0 until p.keyframesLength).map {
                val k = requireNotNull(p.keyframes(it)); ColourKeyframe(k.timeUs, k.argb, easing(k.easing)) }))
        } ?: AnimatableColour()
        return TextProperties(source, size, fill, payload.fontId ?: "fluxx.sans",
            requireNotNull(TextAlignment.entries.getOrNull(payload.alignment)))
    }
    private fun encodeMarkers(b: FlatBufferBuilder, markers: List<Marker>): IntArray = markers.map {
        val description = b.createString(it.description)
        fluxx.schema.Marker.createMarker(b, it.id.toULong(), it.timeUs, it.colorArgb, description)
    }.toIntArray()

    private fun decodeMarkers(count: Int, byteCount: Int, read: (Int) -> fluxx.schema.Marker?): FrozenList<Marker> {
        require(count >= 0 && count <= byteCount / 4) { "Invalid marker count" }
        return FrozenList.of((0 until count).map {
            val marker = requireNotNull(read(it))
            Marker(marker.id.toLong(), marker.timeUs, marker.colorArgb, marker.description.orEmpty())
        })
    }

    fun decode(bytes: ByteArray): ProjectDocument {
        require(bytes.size >= 8) { "Invalid Fluxx project" }
        val p = SProject.getRootAsProject(ByteBuffer.wrap(bytes))
        require(p.version in 1..CompositionDefaults.PROJECT_VERSION) {
            "Unsupported Fluxx project version ${p.version}; supported versions are 1–${CompositionDefaults.PROJECT_VERSION}"
        }
        val c = requireNotNull(p.mainComp) { "Project missing composition" }
        require(c.layersLength >= 0 && c.layersLength <= bytes.size / 4) { "Invalid layer count" }
        val legacy = p.version == 1
        val records = (0 until c.layersLength).map { requireNotNull(c.layers(it)) }
        val used = records.map { it.id.toLong() }.filter { it > 0 }.toMutableSet()
        var next = 1L
        var textBytes = 0L
        val layers = records.mapIndexed { index, l ->
            val id = if (legacy && l.id == 0UL) {
                while (next in used) next++
                next.also { used.add(it) }
            } else l.id.toLong()
            val type = if (legacy) LayerType.VIDEO else
                requireNotNull(LayerType.entries.getOrNull(l.type)) { "Unsupported layer type ${l.type}" }
            fun duration(value: Long): Long? {
                require(value >= -1) { "Invalid duration" }
                return value.takeIf { it >= 0 }
            }
            val asset = if (type == LayerType.VIDEO || type == LayerType.IMAGE) AssetReference(
                id = if (legacy) l.assetUri?.let { "legacy:$it" } ?: "legacy-missing:$id"
                    else requireNotNull(l.assetId) { "Missing asset identity" },
                uri = l.assetUri, durationUs = if (legacy) null else duration(l.sourceDurationUs)) else null

            val animPos = l.animPosition
            val posProp = if (animPos != null) {
                val kfs = (0 until animPos.keyframesLength).mapNotNull { idx ->
                    animPos.keyframes(idx)?.let { k ->
                        Keyframe2D(k.timeUs, k.x, k.y, if (k.easing.toInt() == 0) EasingPreset.LINEAR else EasingPreset.EASY_EASE)
                    }
                }
                AnimatableProperty2D(animPos.isAnimated, animPos.staticX, animPos.staticY, FrozenList.of(kfs.associateBy { it.timeUs }.values.sortedBy { it.timeUs }))
            } else {
                AnimatableProperty2D(isAnimated = false, staticX = l.positionX, staticY = l.positionY)
            }

            val animScale = l.animScale
            val scaleProp = if (animScale != null) {
                val kfs = (0 until animScale.keyframesLength).mapNotNull { idx ->
                    animScale.keyframes(idx)?.let { k ->
                        Keyframe2D(k.timeUs, k.x, k.y, if (k.easing.toInt() == 0) EasingPreset.LINEAR else EasingPreset.EASY_EASE)
                    }
                }
                AnimatableProperty2D(animScale.isAnimated, animScale.staticX, animScale.staticY, FrozenList.of(kfs.associateBy { it.timeUs }.values.sortedBy { it.timeUs }))
            } else {
                AnimatableProperty2D(isAnimated = false, staticX = l.scaleX, staticY = l.scaleY)
            }

            val animRot = l.animRotation
            val rotProp = if (animRot != null) {
                val kfs = (0 until animRot.keyframesLength).mapNotNull { idx ->
                    animRot.keyframes(idx)?.let { k ->
                        Keyframe1D(k.timeUs, k.value, if (k.easing.toInt() == 0) EasingPreset.LINEAR else EasingPreset.EASY_EASE)
                    }
                }
                AnimatableProperty1D(animRot.isAnimated, animRot.staticValue, FrozenList.of(kfs.associateBy { it.timeUs }.values.sortedBy { it.timeUs }))
            } else {
                AnimatableProperty1D(isAnimated = false, staticValue = l.rotation)
            }

            val animOp = l.animOpacity
            val opProp = if (animOp != null) {
                val kfs = (0 until animOp.keyframesLength).mapNotNull { idx ->
                    animOp.keyframes(idx)?.let { k ->
                        Keyframe1D(k.timeUs, k.value, if (k.easing.toInt() == 0) EasingPreset.LINEAR else EasingPreset.EASY_EASE)
                    }
                }
                AnimatableProperty1D(animOp.isAnimated, animOp.staticValue, FrozenList.of(kfs.associateBy { it.timeUs }.values.sortedBy { it.timeUs }))
            } else {
                AnimatableProperty1D(isAnimated = false, staticValue = l.opacity)
            }

            val animTransform = AnimatableTransform(posProp, scaleProp, rotProp, opProp)

            CompositionLayer(id, type, if (legacy) index else l.zOrder,
                if (legacy) ClipTiming() else ClipTiming(l.startUs, l.sourceInUs, duration(l.durationUs)),
                if (legacy) true else l.visible,
                Transform(if (posProp.isAnimated) posProp.staticX else l.positionX,
                    if (posProp.isAnimated) posProp.staticY else l.positionY,
                    if (scaleProp.isAnimated) scaleProp.staticX else l.scaleX,
                    if (scaleProp.isAnimated) scaleProp.staticY else l.scaleY,
                    if (rotProp.isAnimated) rotProp.staticValue else l.rotation,
                    if (opProp.isAnimated) opProp.staticValue.coerceIn(0f, 1f) else l.opacity), asset,
                if (legacy) false else l.muted, if (legacy) 1f else l.audioGain,
                l.name.orEmpty(), l.solidColorArgb,
                if (p.version >= 4) l.referenceWidth else 0,
                if (p.version >= 4) l.referenceHeight else 0,
                animTransform = animTransform,
                keyframeAnchorUs = l.keyframeAnchorUs.takeIf { l.hasKeyframeAnchor || it != -1L },
                anchorX = l.anchorX, anchorY = l.anchorY,
                markers = if (p.version >= 7) decodeMarkers(l.markersLength, bytes.size) { l.markers(it) } else FrozenList.empty(),
                text = if (p.version >= 9) decodeText(l.textPayload, bytes.size) { count ->
                    textBytes = Math.addExact(textBytes, count)
                    require(textBytes <= TextLimits.MAX_DOCUMENT_BYTES) { "Text payload exceeds document budget" }
                } else TextProperties())
        }
        return ProjectDocument(Composition(
            width = if (legacy) CompositionDefaults.WIDTH else c.width,
            height = if (legacy) CompositionDefaults.HEIGHT else c.height,
            durationUs = if (legacy) null else c.durationUs.let { require(it >= -1); it.takeIf { it >= 0 } },
            frameRate = if (legacy) FrameRate() else FrameRate(c.fpsNumerator, c.fpsDenominator),
            layers = FrozenList.of(layers),
            startTimecodeUs = if (p.version >= 8) c.startTimecodeUs else 0,
            markers = if (p.version >= 7) decodeMarkers(c.markersLength, bytes.size) { c.markers(it) } else FrozenList.empty()))
    }
}
