package com.fluxx.android.render

import android.content.Context
import android.media.*
import android.net.Uri
import com.fluxx.android.model.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * AUD2 B3: gain (pure, JVM tested). The model allows 0..1 ([RANGE]); the slider uses the same range. The mix is the sum of
 * voices x gain x headroom ([AudioMixPolicy.headroom] divides by the peak summed gain), SATURATED to 16 bits.
 */
object AudioGains {
    val RANGE = 0f..1f
    fun clamp(gain: Float): Float = if (gain.isNaN()) 0f else gain.coerceIn(RANGE.start, RANGE.endInclusive)
    /** A mixed sample (full scale 1.0) to 16-bit PCM: clamped, never wrapped. */
    fun saturate(sample: Float): Short = (sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
    /** The layers the mixer plays (as [BlockMixer] and the headroom select them). */
    fun audible(project: ProjectDocument): List<CompositionLayer> = project.composition.layers.filter {
        it.visible && it.type == LayerType.VIDEO && !it.muted && it.audioGain > 0 && it.timing.endUs != null && it.asset?.uri != null }
    /** Each audible layer's gain as mixed (its gain x the project's headroom). */
    fun effective(project: ProjectDocument): Map<Long, Float> {
        val headroom = AudioMixPolicy.headroom(project)
        return audible(project).associate { it.id to it.audioGain * headroom }
    }
    /**
     * Preview playback splits [effective] into the output volume (the largest effective gain: applied by the AudioTrack at
     * once, behind the ~0.3 s of audio already queued) and each layer's weight relative to it (mixed per block). Their
     * product is exactly [effective]; with one audible layer a gain step is heard immediately and exactly.
     */
    fun split(project: ProjectDocument): Pair<Float, Map<Long, Float>> {
        val eff = effective(project)
        val volume = eff.values.maxOrNull() ?: 0f
        return volume to eff.mapValues { if (volume > 0f) it.value / volume else 0f }
    }
    /**
     * Whether [b] differs from [a] only in audio gains and plays the same voices (no layer crossing 0, nothing else):
     * then a running mix can take the new gains live. Timing, visibility, mute, duration or any other edit is not.
     */
    fun onlyGainsChanged(a: ProjectDocument, b: ProjectDocument): Boolean {
        if (a === b) return true
        if (audible(a).map { it.id } != audible(b).map { it.id }) return false
        fun neutral(p: ProjectDocument) = p.copy(composition = p.composition.copy(layers = FrozenList.of(p.composition.layers.map { it.copy(audioGain = 0f) })))
        return neutral(a) == neutral(b)
    }
}

/** One audio decoder at a time; disk-backed accumulation keeps RAM independent of layer count. */
object AudioMixer {
    const val RATE = 48000
    const val CHANNELS = 2
    fun signature(project: ProjectDocument): List<Any?> = listOf(project.composition.resolvedDurationUs,
        project.composition.layers.filter { it.visible && it.type == LayerType.VIDEO && !it.muted && it.audioGain > 0 }
            .sortedBy { it.id }
            .map { listOf(it.asset?.uri,it.timing,it.audioGain) })

    /**
     * AUD1: the whole mix as 16-bit stereo PCM (export). Built on the same per-source decoded-PCM cache and [BlockMixer] as
     * preview playback (decoded once, priming trimmed, bulk), so export and preview place every sample identically.
     */
    fun mix(context: Context, project: ProjectDocument, target: File, cancelled: () -> Boolean,
        progress: (Float) -> Unit = {}) {
        val mixer = BlockMixer(context, project)
        val total = mixer.totalFrames
        // Decode what is needed (cached sources return at once); progress is the decoded share, then the mix.
        var waited = 0L
        while (!mixer.ready(0, total)) {
            checkCancelled(cancelled)
            check(waited < 600_000) { "Audio decoding stalled" }
            Thread.sleep(20); waited += 20
        }
        progress(.5f)
        target.outputStream().buffered(64 * 1024).use { out ->
            val block = ShortArray(StreamingWindow.BLOCK_FRAMES * 2)
            val bytes = ByteArray(block.size * 2)
            val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            var frame = 0L
            while (frame < total) {
                checkCancelled(cancelled)
                val n = minOf(StreamingWindow.BLOCK_FRAMES.toLong(), total - frame).toInt()
                mixer.mix(frame, n, block)
                bb.clear(); bb.asShortBuffer().put(block, 0, n * 2)
                out.write(bytes, 0, n * 4)
                frame += n
            }
        }
        progress(1f)
    }

    private fun checkCancelled(cancelled: () -> Boolean) { if(cancelled()) throw java.util.concurrent.CancellationException("Audio mix cancelled") }
}
