package com.fluxx.android.ui

import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import com.fluxx.android.editor.EditorAction
import com.fluxx.android.media.AudioPeakProvider
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.ClipTiming
import com.fluxx.android.render.MediaProbes
import com.fluxx.android.render.SourcePcmCache
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * MP1 A3 / B2: the long source (`Project 3`, 5 GB HEVC, 50:18) trimmed to 5 s at the start, around 25:00 and around 49:00,
 * each at timeline 0 and played from the start. Per position: extractor open + seek cost (test-side), the decoder used,
 * time to first frame and to first sound, the PCM cache size after 20 s, the CPU / storage reads of background work and
 * whether any background decode is still running at the limit (it is then ABORTED and reported). Once per class: the
 * moov position, the codec each benchmark file gets, the waveform peaks' scope and time, the thumbnail work.
 * Heat: the battery is checked before each position (> 42 C: wait, then skip). Every position has a wall-clock limit.
 */
class Mp1LongSourceTest : Aud2Base() {
    override val tag = "MP1"
    private val probe = Mp1Probe()

    @Test fun longSourceTrimmed() {
        val f = Mp1Bench.findLong(context)
        assumeTrue("MP1: the long source '${Mp1Bench.LONG_PREFIX}...' was not found (${Mp1Bench.lookupLog}); skipping", f != null)
        // A distinct URI spelling per run (instrumentation argument mp1UriK) so every run starts with cold PCM / peak caches.
        val k = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("mp1UriK")?.toIntOrNull() ?: 0
        val uri = Aud2Bench.distinctUri(f!!, k)
        report("A3 long source: found (${Mp1Bench.lookupLog}) %.2f GB".format(f.length() / 1e9))
        val m = Aud1Bench.media(f)
        report("A3 long source: %.1f s %dx%d %d fps".format(m.durationUs / 1e6, m.width, m.height, m.fps))
        val atoms = Mp1Bench.atoms(f)
        report("A3 long source atoms: " + atoms.joinToString(", ") { "${it.first}@%.2fGB(%.1fMB)".format(it.second / 1e9, it.third / 1e6) })
        codecs(f)
        probe.start(); rec.start()
        try {
            host(Aud2Bench.project(1920, 1080, 5_000_000, m.fps, listOf(Aud2Bench.layer(1, f, m, uri = uri, timing = ClipTiming(0, 0, 5_000_000)))))
            Thread.sleep(1500)
            for ((label, inUs) in listOf("start (0:00)" to 0L, "middle (25:00)" to 1_500_000_000L, "end (49:00)" to 2_940_000_000L)) {
                if (!Mp1Bench.coolEnough(context) { report("A3 long $label: $it") }) continue
                position(label, f, uri, m, inUs)
            }
            // As the twirl-down row and the timeline clip ask since MP1 B2: the last position's used range (49:00, 5 s).
            peaksAndThumb(uri, 2_940_000_000L, 5_000_000L)
        } finally {
            // Never leave a background decode running at the end of the class.
            val pcm = SourcePcmCache.get(context).get(uri, m.durationUs)
            if (pcm.decoding) report("A3 long: a PCM decode was still running at the end of the class: aborted")
            SourcePcmCache.get(context).release(uri)
            compose.runOnIdle { AudioPeakProvider.get(context).cancel(uri) }
            probe.stop()
        }
    }

    private fun codecs(long: File) {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val files = listOfNotNull(Aud2Bench.find(context, Aud2Bench.VP9)?.let { "VP9 worst case" to it },
            Aud2Bench.find(context, Aud2Bench.TYPICAL)?.let { "H.264 typical" to it }, "HEVC long source" to long)
        for ((name, file) in files) {
            val ex = MediaExtractor(); ex.setDataSource(file.path)
            val parts = (0 until ex.trackCount).map { ex.getTrackFormat(it) }.map { fmt ->
                val codec = list.findDecoderForFormat(fmt)
                val info = codec?.let { c -> list.codecInfos.firstOrNull { it.name == c } }
                "${fmt.getString(MediaFormat.KEY_MIME)} -> ${codec ?: "none"} (${if (info?.isHardwareAccelerated == true) "hardware" else if (info?.isSoftwareOnly == true) "software" else "?"})"
            }
            ex.release()
            report("A2.5 codec $name: " + parts.joinToString("; "))
        }
    }

    private fun position(label: String, f: File, uri: String, m: Aud1Bench.Media, inUs: Long) {
        val limitNs = 60_000_000_000L
        val start = System.nanoTime()
        // Test-side: extractor open and the seek to the sync frame before the in-point.
        val e0 = System.nanoTime(); val ex = MediaExtractor(); ex.setDataSource(f.path)
        val openMs = (System.nanoTime() - e0) / 1e6
        val vt = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        ex.selectTrack(vt); val s0 = System.nanoTime(); ex.seekTo(inUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val seekMs = (System.nanoTime() - s0) / 1e6; val sync = ex.sampleTime; ex.release()
        val cpu0 = Mp1Bench.cpuMs(); val io0 = Mp1Bench.io(); val th0 = Mp1Bench.threadCpuMs()
        rec.clear(); probe.events.clear()
        val project = Aud2Bench.project(1920, 1080, 5_000_000, m.fps, listOf(Aud2Bench.layer(1, f, m, uri = uri, timing = ClipTiming(0, inUs, 5_000_000))))
        val l0 = System.nanoTime()
        compose.runOnIdle { editor.dispatch(EditorAction.Load(project)); editor.dispatch(EditorAction.Seek(0)); c.update(editor.state.project, 0, false) }
        while (System.nanoTime() - l0 < 20_000_000_000L && rec.frames.none { it[2] >= l0 }) Thread.sleep(5)
        val firstFrameMs = rec.frames.firstOrNull { it[2] >= l0 }?.let { (it[2] - l0) / 1e6 } ?: Double.NaN
        Thread.sleep(500)
        val opened = rec.decoder.toList().firstOrNull { it.second.startsWith("open") }?.second
        val firstReq = rec.decoder.toList().firstOrNull { it.second.startsWith("frame ") }?.second
        val p0 = System.nanoTime()
        compose.runOnIdle { c.play() }
        while (System.nanoTime() - p0 < 15_000_000_000L && rec.firstSoundNs() == null) Thread.sleep(5)
        val firstSoundMs = rec.firstSoundNs()?.let { (it - p0) / 1e6 } ?: Double.NaN
        val playFrameMs = rec.frames.firstOrNull { it[2] >= p0 }?.let { (it[2] - p0) / 1e6 } ?: Double.NaN
        // 20 s after Play (the 5 s clip ends after 5 s; background work may go on).
        while (System.nanoTime() - p0 < 20_000_000_000L) Thread.sleep(100)
        if (playing) toggle()
        val pcm = SourcePcmCache.get(context).get(uri, m.durationUs)
        val cpu = Mp1Bench.cpuMs() - cpu0; val io = Mp1Bench.io(); val th = Mp1Bench.threadCpuMs()
        val busy = th.mapValues { it.value - (th0[it.key] ?: 0) }.filter { it.value >= 200 }.entries.sortedByDescending { it.value }.take(6)
        val runs = probe.events.toList().filter { it.second.startsWith("pcm-run") }.map { it.second.substringAfter(' ').substringAfter(' ') }
        report("A3 long $label: extractor open %.0f ms, seek to sync %.0f ms (sync %.2f s before the in-point); decoder `${opened ?: "none"}`; first decoder request `${firstReq?.substringAfter(' ')?.substringAfter(' ') ?: "-"}`"
            .format(openMs, seekMs, (inUs - sync) / 1e6))
        report("A3 long $label: first frame %.0f ms after load; Play -> first frame %.0f ms, first sound %.0f ms; underruns ${rec.underruns}".format(firstFrameMs, playFrameMs, firstSoundMs))
        report("A3 long $label after 20 s: PCM cache ${pcm.coveredChunks} s decoded, %.1f MB on disk (logical file %.0f MB); background decode still running: ${pcm.decoding}; process CPU %.1f s; storage read %.1f MB (rchar %.1f MB); busiest threads ${busy.joinToString { "${it.key} %.1fs".format(it.value / 1000.0) }}; PCM runs ${runs.joinToString(" | ")}"
            .format(pcm.allocatedBytes / 1e6, pcm.frames * 4 / 1e6, cpu / 1000.0, (io.first - io0.first) / 1e6, (io.second - io0.second) / 1e6))
        if (pcm.decoding) { SourcePcmCache.get(context).release(uri); report("A3 long $label: background PCM decode still running at the 20 s limit: ABORTED (last runs above)") }
        check(System.nanoTime() - start < limitNs + 30_000_000_000L) { "position over its wall-clock limit" }
        Thread.sleep(1000)
    }

    /** The waveform peaks as the twirl-down row requests them, and the timeline thumbnail; both with a limit. */
    private fun peaksAndThumb(uri: String, inUs: Long, durUs: Long) {
        val provider = AudioPeakProvider.get(context)
        probe.events.clear()
        val t0 = System.nanoTime(); val cpu0 = Mp1Bench.cpuMs()
        compose.runOnIdle { provider.request(uri, inUs, durUs) }
        val limit = 40_000_000_000L
        while (System.nanoTime() - t0 < limit && compose.runOnIdle { provider.states[uri] is AudioPeakProvider.Peaks.Loading }) Thread.sleep(200)
        val state = compose.runOnIdle { provider.states[uri] }
        val still = compose.runOnIdle { provider.decoding(uri) }
        val done = probe.events.toList().firstOrNull { it.second.startsWith("peaks-done") || it.second.startsWith("peaks-cached") }?.second
        report("A3 long peaks: ${state?.javaClass?.simpleName} after %.1f s; CPU %.1f s; `${done ?: "not finished"}`; still decoding at the %d s limit: $still".format((System.nanoTime() - t0) / 1e9, (Mp1Bench.cpuMs() - cpu0) / 1000.0, limit / 1_000_000_000))
        if (still) { compose.runOnIdle { provider.cancel(uri) }; report("A3 long peaks: ABORTED at the limit (decoding the whole first audio track)") }
        (state as? AudioPeakProvider.Peaks.Ready)?.let { report("A3 long peaks scope: source %.1f s, %d level-0 buckets (%.1f MB in memory)".format(it.pyramid.durationUs / 1e6, it.pyramid.levels[0].first.size, it.pyramid.levels.sumOf { l -> l.first.size * 8L } / 1e6)) }
        probe.events.clear()
        val r0 = System.nanoTime()
        val bmp = kotlinx.coroutines.runBlocking { MediaRepository(context).timelineThumbnail(uri, inUs + minOf(1_000_000L, durUs / 2)) }
        report("A3 long thumbnail (the timeline loader, the clip's used range): ${if (bmp != null) "${bmp.width}x${bmp.height}" else "none"} in %.0f ms; ${probe.events.joinToString(" | ") { it.second }}".format((System.nanoTime() - r0) / 1e6))
        val r1 = System.nanoTime()
        val again = kotlinx.coroutines.runBlocking { MediaRepository(context).timelineThumbnail(uri, inUs + minOf(1_000_000L, durUs / 2)) }
        report("A3 long thumbnail again (memory / disk cache): ${if (again != null) "hit" else "none"} in %.1f ms".format((System.nanoTime() - r1) / 1e6))
    }
}
