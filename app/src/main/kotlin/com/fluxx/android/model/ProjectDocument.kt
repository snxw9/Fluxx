package com.fluxx.android.model

import java.math.BigInteger
import java.util.Collections

/** A defensive, immutable list; caller-owned mutable collections cannot change project snapshots. */
class FrozenList<out T> private constructor(private val values: List<T>) : List<T> by values {
    override fun equals(other: Any?): Boolean = values == other
    override fun hashCode(): Int = values.hashCode()
    override fun toString(): String = values.toString()
    companion object {
        fun <T> of(values: Collection<T>): FrozenList<T> = FrozenList(Collections.unmodifiableList(ArrayList(values)))
        fun <T> empty(): FrozenList<T> = of(emptyList())
    }
}

object CompositionDefaults {
    const val WIDTH = 1080
    const val HEIGHT = 1920
    const val PROJECT_VERSION = 8 // Display timecode origin; layer times remain composition-relative.
}

/** Convert from the absolute frame index, never by repeatedly adding a rounded frame period. */
data class FrameRate(val numerator: Int = 30, val denominator: Int = 1) {
    init { require(numerator > 0 && denominator > 0) { "Frame rate must be positive" } }

    fun frameTimeUs(frame: Long): Long {
        require(frame >= 0)
        return BigInteger.valueOf(frame).multiply(BigInteger.valueOf(1_000_000L * denominator))
            .divide(BigInteger.valueOf(numerator.toLong())).longValueExact()
    }

    fun nearestFrame(timeUs: Long): Long {
        require(timeUs >= 0)
        val denominatorUs = 1_000_000L * denominator
        val half = denominatorUs / 2
        if (timeUs <= (Long.MAX_VALUE - half) / numerator) return (timeUs * numerator + half) / denominatorUs
        val divisor = BigInteger.valueOf(denominatorUs)
        return BigInteger.valueOf(timeUs).multiply(BigInteger.valueOf(numerator.toLong()))
            .add(divisor.divide(BigInteger.valueOf(2))).divide(divisor).longValueExact()
    }

    fun snap(timeUs: Long): Long = frameTimeUs(nearestFrame(timeUs))
}

enum class LayerType { VIDEO, IMAGE, SOLID, TEXT }
enum class MediaAccess { UNKNOWN, AVAILABLE, MISSING }

/** Identity and source URI survive loss of permission. Access is refreshed, never trusted from disk. */
data class AssetReference(
    val id: String,
    val uri: String?,
    val access: MediaAccess = MediaAccess.UNKNOWN,
    val durationUs: Long? = null
) {
    init {
        require(id.isNotBlank())
        require(uri == null || uri.isNotBlank())
        require(durationUs == null || durationUs >= 0)
    }
}

/** Positions are normalized to the layer's reference canvas; rotation is clockwise on screen. */
data class Transform(
    val positionX: Float = 0f, val positionY: Float = 0f,
    val scaleX: Float = 1f, val scaleY: Float = 1f,
    val rotationDegrees: Float = 0f, val opacity: Float = 1f
) {
    init {
        require(listOf(positionX, positionY, scaleX, scaleY, rotationDegrees, opacity).all { it.isFinite() })
        require(opacity in 0f..1f)
    }
}

enum class EasingPreset { EASY_EASE, LINEAR }

data class Keyframe1D(
    val timeUs: Long,
    val value: Float,
    val easing: EasingPreset = EasingPreset.EASY_EASE
) {
    init {
        // Signed anchor-relative time: split segments retain keys before their in-point.
        require(value.isFinite()) { "Keyframe value must be finite" }
    }
}

data class Keyframe2D(
    val timeUs: Long,
    val x: Float,
    val y: Float,
    val easing: EasingPreset = EasingPreset.EASY_EASE
) {
    init {
        // Signed anchor-relative time: split segments retain keys before their in-point.
        require(x.isFinite() && y.isFinite()) { "Keyframe coordinates must be finite" }
    }
}

object KeyframeEvaluator {
    // 1024-entry LUT for Easy Ease preset: P0=(0,0), P1=(0.33, 0.0), P2=(0.67, 1.0), P3=(1.0, 1.0)
    // x(s) = 0.99s + 0.03s^2 - 0.02s^3
    // y(s) = 3s^2 - 2s^3
    private val EASY_EASE_LUT = FloatArray(1024) { i ->
        val targetX = i / 1023f
        var s = targetX
        for (iter in 0 until 8) {
            val fx = 0.99f * s + 0.03f * s * s - 0.02f * s * s * s - targetX
            if (kotlin.math.abs(fx) < 1e-6f) break
            val dfx = 0.99f + 0.06f * s - 0.06f * s * s
            if (kotlin.math.abs(dfx) < 1e-6f) break
            s = (s - fx / dfx).coerceIn(0f, 1f)
        }
        // Safeguard the inverse when Newton has not converged.
        if (kotlin.math.abs(0.99f * s + 0.03f * s * s - 0.02f * s * s * s - targetX) >= 1e-6f) {
            var lo = 0f
            var hi = 1f
            repeat(24) {
                s = (lo + hi) * .5f
                if (0.99f * s + 0.03f * s * s - 0.02f * s * s * s < targetX) lo = s else hi = s
            }
        }
        (3f * s * s - 2f * s * s * s).coerceIn(0f, 1f)
    }

    fun easeProgress(p: Float, easing: EasingPreset): Float {
        val clamped = p.coerceIn(0f, 1f)
        return when (easing) {
            EasingPreset.LINEAR -> clamped
            EasingPreset.EASY_EASE -> {
                val index = (clamped * 1023f).toInt().coerceIn(0, 1022)
                val frac = clamped * 1023f - index
                EASY_EASE_LUT[index] * (1f - frac) + EASY_EASE_LUT[index + 1] * frac
            }
        }
    }

    fun evaluate1D(keyframes: List<Keyframe1D>, staticValue: Float, isAnimated: Boolean, timeUs: Long): Float {
        if (!isAnimated || keyframes.isEmpty()) return staticValue
        if (keyframes.size == 1) return keyframes[0].value
        if (timeUs <= keyframes.first().timeUs) return keyframes.first().value
        if (timeUs >= keyframes.last().timeUs) return keyframes.last().value

        var low = 0
        var high = keyframes.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (keyframes[mid].timeUs <= timeUs) {
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        val k0 = keyframes[high]
        val k1 = keyframes[high + 1]
        val dt = k1.timeUs - k0.timeUs
        if (dt <= 0L) return k1.value
        val progress = (timeUs - k0.timeUs).toFloat() / dt.toFloat()
        val ease = easeProgress(progress, k0.easing)
        return (k0.value.toDouble() * (1.0 - ease) + k1.value.toDouble() * ease).toFloat()
    }

    fun evaluate2D(keyframes: List<Keyframe2D>, staticX: Float, staticY: Float, isAnimated: Boolean, timeUs: Long, out: FloatArray, offset: Int) {
        if (!isAnimated || keyframes.isEmpty()) {
            out[offset] = staticX
            out[offset + 1] = staticY
            return
        }
        if (keyframes.size == 1) {
            out[offset] = keyframes[0].x
            out[offset + 1] = keyframes[0].y
            return
        }
        if (timeUs <= keyframes.first().timeUs) {
            out[offset] = keyframes.first().x
            out[offset + 1] = keyframes.first().y
            return
        }
        if (timeUs >= keyframes.last().timeUs) {
            out[offset] = keyframes.last().x
            out[offset + 1] = keyframes.last().y
            return
        }

        var low = 0
        var high = keyframes.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (keyframes[mid].timeUs <= timeUs) {
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        val k0 = keyframes[high]
        val k1 = keyframes[high + 1]
        val dt = k1.timeUs - k0.timeUs
        if (dt <= 0L) {
            out[offset] = k1.x
            out[offset + 1] = k1.y
            return
        }
        val progress = (timeUs - k0.timeUs).toFloat() / dt.toFloat()
        val ease = easeProgress(progress, k0.easing)
        out[offset] = (k0.x.toDouble() * (1.0 - ease) + k1.x.toDouble() * ease).toFloat()
        out[offset + 1] = (k0.y.toDouble() * (1.0 - ease) + k1.y.toDouble() * ease).toFloat()
    }
}

data class AnimatableProperty1D(
    val isAnimated: Boolean = false,
    val staticValue: Float = 0f,
    val keyframes: FrozenList<Keyframe1D> = FrozenList.empty()
) {
    init {
        require(staticValue.isFinite()) { "Static value must be finite" }
        for (i in 1 until keyframes.size) require(keyframes[i - 1].timeUs < keyframes[i].timeUs) {
            "Keyframes must be sorted with unique timestamps"
        }
    }

    fun evaluate(timeUs: Long): Float =
        KeyframeEvaluator.evaluate1D(keyframes, staticValue, isAnimated, timeUs)
}

data class AnimatableProperty2D(
    val isAnimated: Boolean = false,
    val staticX: Float = 0f,
    val staticY: Float = 0f,
    val keyframes: FrozenList<Keyframe2D> = FrozenList.empty()
) {
    init {
        require(staticX.isFinite() && staticY.isFinite()) { "Static coordinates must be finite" }
        for (i in 1 until keyframes.size) require(keyframes[i - 1].timeUs < keyframes[i].timeUs) {
            "Keyframes must be sorted with unique timestamps"
        }
    }

    fun evaluate(timeUs: Long, out: FloatArray, offset: Int) {
        KeyframeEvaluator.evaluate2D(keyframes, staticX, staticY, isAnimated, timeUs, out, offset)
    }

    fun evaluatePair(timeUs: Long): Pair<Float, Float> {
        val buf = FloatArray(2)
        evaluate(timeUs, buf, 0)
        return Pair(buf[0], buf[1])
    }
}

data class AnimatableTransform(
    val position: AnimatableProperty2D = AnimatableProperty2D(staticX = 0f, staticY = 0f),
    val scale: AnimatableProperty2D = AnimatableProperty2D(staticX = 1f, staticY = 1f),
    val rotation: AnimatableProperty1D = AnimatableProperty1D(staticValue = 0f),
    val opacity: AnimatableProperty1D = AnimatableProperty1D(staticValue = 1f)
) {
    fun isAnyAnimated(): Boolean =
        position.isAnimated || scale.isAnimated || rotation.isAnimated || opacity.isAnimated

    /** Zero-allocation evaluation writing [posX, posY, scaleX, scaleY, rotation, opacity] into [out] at [offset]. */
    fun evaluate(localTimeUs: Long, out: FloatArray, offset: Int = 0) {
        position.evaluate(localTimeUs, out, offset)
        scale.evaluate(localTimeUs, out, offset + 2)
        out[offset + 4] = rotation.evaluate(localTimeUs)
        out[offset + 5] = opacity.evaluate(localTimeUs).coerceIn(0f, 1f)
    }

    /** Non-hot-path evaluation for UI/Inspectors. */
    fun evaluateTransform(localTimeUs: Long): Transform {
        val buf = FloatArray(6)
        evaluate(localTimeUs, buf, 0)
        return Transform(
            positionX = buf[0], positionY = buf[1],
            scaleX = buf[2], scaleY = buf[3],
            rotationDegrees = buf[4], opacity = buf[5]
        )
    }
}

data class ClipTiming(val startUs: Long = 0, val sourceInUs: Long = 0, val durationUs: Long? = null) {
    init {
        require(startUs >= 0 && sourceInUs >= 0)
        require(durationUs == null || (durationUs >= 0 &&
            durationUs <= Long.MAX_VALUE - startUs && durationUs <= Long.MAX_VALUE - sourceInUs))
    }
    val endUs: Long? get() = durationUs?.let { startUs + it }
    fun isActive(timeUs: Long): Boolean = timeUs >= startUs && endUs?.let { timeUs < it } == true
}

data class CompositionLayer(
    val id: Long,
    val type: LayerType = LayerType.VIDEO,
    val zOrder: Int = 0,
    val timing: ClipTiming = ClipTiming(),
    val visible: Boolean = true,
    val transform: Transform = Transform(),
    val asset: AssetReference? = null,
    val muted: Boolean = false,
    val audioGain: Float = 1f,
    val name: String = "",
    val solidColorArgb: Int = 0xff4477cc.toInt(),
    // Initial fit and position coordinate space, independent of subsequent canvas resizing.
    // Zero pairs retain legacy appearance until the first resize captures the old canvas.
    val referenceWidth: Int = 0,
    val referenceHeight: Int = 0,
    val animTransform: AnimatableTransform = AnimatableTransform(
        position = AnimatableProperty2D(staticX = transform.positionX, staticY = transform.positionY),
        scale = AnimatableProperty2D(staticX = transform.scaleX, staticY = transform.scaleY),
        rotation = AnimatableProperty1D(staticValue = transform.rotationDegrees),
        opacity = AnimatableProperty1D(staticValue = transform.opacity)
    ),
    val keyframeAnchorUs: Long? = null,
    val anchorX: Float = .5f,
    val anchorY: Float = .5f,
    val markers: FrozenList<Marker> = FrozenList.empty()
) {
    init {
        Markers.validate(markers, anchor = resolvedKeyframeAnchorUs)
        require(anchorX.isFinite() && anchorY.isFinite() && anchorX in 0f..1f && anchorY in 0f..1f)
        require((referenceWidth == 0 && referenceHeight == 0) || (referenceWidth > 0 && referenceHeight > 0))
        require(id > 0) { "Layer IDs must be positive" }
        require(audioGain.isFinite() && audioGain in 0f..1f)
        require(type !in listOf(LayerType.VIDEO, LayerType.IMAGE) || asset != null) { "Media layers require an asset identity" }
        if (type == LayerType.VIDEO && asset?.durationUs != null) {
            require(timing.sourceInUs <= asset.durationUs) { "Source in-point exceeds source duration" }
            require(timing.durationUs == null || timing.durationUs <= asset.durationUs - timing.sourceInUs) {
                "Clip extends beyond its source"
            }
        }
    }

    val resolvedKeyframeAnchorUs: Long get() = keyframeAnchorUs ?: timing.startUs

    /** Translate clip and animation together; trims deliberately do not call this. */
    fun movedTo(startUs: Long): CompositionLayer = copy(
        timing = timing.copy(startUs = startUs),
        keyframeAnchorUs = keyframeAnchorUs?.let { Math.addExact(it, Math.subtractExact(startUs, timing.startUs)) }
    )

    fun evaluatedTransform(playheadUs: Long): Transform {
        if (!animTransform.isAnyAnimated()) return transform
        val out = FloatArray(6)
        evaluatedTransformInto(playheadUs, out)
        return Transform(out[0], out[1], out[2], out[3], out[4], out[5])
    }

    /** Render flyweight. Static properties always read the existing SetTransform source of truth. */
    fun evaluatedTransformInto(playheadUs: Long, out: FloatArray) {
        val local = playheadUs - resolvedKeyframeAnchorUs
        val a = animTransform
        if (a.position.isAnimated) a.position.evaluate(local, out, 0) else {
            out[0] = transform.positionX; out[1] = transform.positionY
        }
        if (a.scale.isAnimated) a.scale.evaluate(local, out, 2) else {
            out[2] = transform.scaleX; out[3] = transform.scaleY
        }
        out[4] = if (a.rotation.isAnimated) a.rotation.evaluate(local) else transform.rotationDegrees
        out[5] = (if (a.opacity.isAnimated) a.opacity.evaluate(local) else transform.opacity).coerceIn(0f, 1f)
    }

    fun withReferenceCanvas(width: Int, height: Int): CompositionLayer =
        if (referenceWidth == 0) copy(referenceWidth = width, referenceHeight = height) else this
}

data class Composition(
    val width: Int = CompositionDefaults.WIDTH,
    val height: Int = CompositionDefaults.HEIGHT,
    val durationUs: Long? = null,
    val frameRate: FrameRate = FrameRate(),
    val layers: FrozenList<CompositionLayer> = FrozenList.empty(),
    val markers: FrozenList<Marker> = FrozenList.empty(),
    val startTimecodeUs: Long = 0
) {
    /** Null stored duration means content-driven length; an empty composition is exactly zero. */
    val resolvedDurationUs: Long = durationUs ?: (layers.maxOfOrNull { it.timing.endUs ?: it.timing.startUs } ?: 0L)
    val endTimecodeUs: Long = Math.addExact(startTimecodeUs, resolvedDurationUs)

    init {
        require(markers.all { it.timeUs >= 0 })
        Markers.validate(markers, frameRate)
        layers.forEach { Markers.validate(it.markers, frameRate, it.resolvedKeyframeAnchorUs) }
        require(width > 0 && height > 0)
        require(durationUs == null || durationUs >= 0)
        require(startTimecodeUs >= 0)
        require(layers.map { it.id }.toSet().size == layers.size) { "Duplicate layer ID" }
        layers.mapNotNull { it.asset }.groupBy { it.id }.values.forEach { references ->
            require(references.map { it.uri }.distinct().size == 1) { "Conflicting asset identity" }
        }
    }
}

data class ProjectDocument(val composition: Composition = Composition())

/** Higher zOrder is drawn later (in front). Equal zOrder uses stable ID, not insertion order. */
data class EvaluatedLayer(
    val id: Long, val type: LayerType, val zOrder: Int, val asset: AssetReference?,
    val localTimeUs: Long, val sourceTimeUs: Long, val transform: Transform, val audioGain: Float,
    val solidColorArgb: Int = 0xff4477cc.toInt(),
    val referenceWidth: Int = 0,
    val referenceHeight: Int = 0
)
data class EvaluatedFrame(
    val compositionTimeUs: Long, val width: Int, val height: Int,
    val layers: FrozenList<EvaluatedLayer>, val unresolvedLayerIds: FrozenList<Long>
)

/** Prepared once per project revision. Sampling does not sort or rebuild the layer collection. */
class FramePlan(val project: ProjectDocument) {
    private val ordered=project.composition.layers.filter { it.visible }.sortedWith(compareBy<CompositionLayer> { it.zOrder }.thenBy { it.id })
    val headroom=AudioMixPolicy.headroom(project)
    fun forEachActive(timeUs: Long, consume: (CompositionLayer, Long, Long, Float) -> Unit) {
        require(timeUs>=0)
        val duration=project.composition.durationUs
        if(duration!=null && timeUs>=duration) return
        for(layer in ordered) if(layer.timing.isActive(timeUs)) {
            val local=timeUs-layer.timing.startUs
            val gain=if(layer.type==LayerType.VIDEO && !layer.muted) layer.audioGain*headroom else 0f
            consume(layer,local,layer.timing.sourceInUs+local,gain)
        }
    }
}
object FrameEvaluator {
    fun evaluate(project: ProjectDocument,timeUs: Long): EvaluatedFrame {
        val comp=project.composition
        val active=ArrayList<EvaluatedLayer>()
        FramePlan(project).forEachActive(timeUs) { layer,local,source,gain ->
            val t = layer.evaluatedTransform(timeUs)
            active.add(EvaluatedLayer(layer.id,layer.type,layer.zOrder,layer.asset,local,source,t,gain,layer.solidColorArgb,
                layer.referenceWidth.takeIf { it > 0 } ?: comp.width,layer.referenceHeight.takeIf { it > 0 } ?: comp.height))
        }
        val unresolved=if(comp.durationUs!=null && timeUs>=comp.durationUs) emptyList() else comp.layers
            .filter { it.visible && timeUs>=it.timing.startUs && it.timing.durationUs==null }.map { it.id }.sorted()
        return EvaluatedFrame(timeUs,comp.width,comp.height,FrozenList.of(active),FrozenList.of(unresolved))
    }
}

/** Fixed headroom based on peak overlapping gains: no pumping and no summed full-scale clipping. */
object AudioMixPolicy {
    fun headroom(project: ProjectDocument): Float {
        val duration=project.composition.durationUs ?: Long.MAX_VALUE
        val events = project.composition.layers.filter { it.visible && it.type == LayerType.VIDEO && !it.muted && it.audioGain > 0 }
            .flatMap { l -> l.timing.endUs?.coerceAtMost(duration)?.takeIf { it > l.timing.startUs }?.let {
                listOf(l.timing.startUs to l.audioGain, it to -l.audioGain)
            } ?: emptyList() }.groupBy({ it.first }, { it.second }).toSortedMap()
        var gain = 0f
        var peak = 1f
        events.values.forEach { gain += it.sum(); peak = maxOf(peak, gain) }
        return 1f / peak
    }
}
