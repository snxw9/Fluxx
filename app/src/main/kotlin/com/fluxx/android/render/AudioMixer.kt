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
