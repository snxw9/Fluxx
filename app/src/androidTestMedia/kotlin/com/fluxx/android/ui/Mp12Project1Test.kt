package com.fluxx.android.ui

import com.fluxx.android.ProjectManager
import com.fluxx.android.model.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * MP1.2 A2 (Snxw's approved exception, this pass only): Project 1 loaded through the app's own loader
 * ([ProjectManager.loadProject], read-only) and played in-process from the start to the end through the normal preview.
 * The loaded document lives in this test's editor only: the host has no save path (no autosave, no Save), and nothing
 * here writes, renames or exports it. The file's size and mtime are checked before and after (and by adb stat outside).
 */
class Mp12Project1Test : Mp12Base() {
    /**
     * MP1.2 H6: clip 13 of Project 1 (AVC 1440x1080) stops playback ~16.8 s into the clip in every run, with or without
     * memory pressure. Reads its samples around there (time, size, flags) and decodes it straight through, both with the
     * app's VideoDecoder (surface output) and with a plain buffer-output MediaCodec, reporting where output stops.
     */
    @Test fun clip13Probe() {
        val file = File(context.filesDir, "projects/Project 1.fluxx")
        assumeTrue("MP12: Project 1 not found; skipping", file.isFile)
        val before = file.length() to file.lastModified()
        val project = kotlinx.coroutines.runBlocking { ProjectManager(context).loadProject(file) }.getOrThrow()
        val clips = Mp12Bench.clips(project) { "" }
        val n = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("mp12Clip")?.toIntOrNull() ?: 13
        val clip = clips.first { it.index == n }
        val layer = project.composition.layers.first { it.asset?.uri == clip.uri && it.timing.startUs == clip.startUs }
        val uri = android.net.Uri.parse(clip.uri)
        val stopAt = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("mp12StopUs")?.toLongOrNull() ?: 440_800_000L
        val stopSrc = maxOf(layer.timing.sourceInUs, stopAt - clip.startUs + layer.timing.sourceInUs)
        say("H6 clip $n (%.2f-%.2f s): ".format(clip.startUs / 1e6, clip.endUs / 1e6) + "'${clip.name.take(40)}' in-point ${layer.timing.sourceInUs} us, stop at source %.3f s".format(stopSrc / 1e6))
        val ex = android.media.MediaExtractor(); ex.setDataSource(context, uri, null)
        val vt = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)!!.startsWith("video/") }
        val fmt = ex.getTrackFormat(vt); say("H6 format: $fmt")
        ex.selectTrack(vt)
        val samples = ArrayList<Triple<Long, Long, Int>>()
        val buf = java.nio.ByteBuffer.allocate(8 * 1024 * 1024)
        while (ex.sampleTime >= 0) { val n = ex.readSampleData(buf, 0); samples += Triple(ex.sampleTime, n.toLong(), ex.sampleFlags); ex.advance() }
        ex.release()
        val sorted = samples.map { it.first }.sorted()
        val gaps = sorted.zipWithNext { a, b -> b - a }
        say("H6 samples: ${samples.size}, first %.3f last %.3f s, syncs ${samples.count { it.third and 1 != 0 }}, max pts gap %.3f s at %.3f s, decode-order backwards steps ${samples.zipWithNext().count { (a, b) -> b.first < a.first }}"
            .format(sorted.first() / 1e6, sorted.last() / 1e6, (gaps.maxOrNull() ?: 0) / 1e6, sorted.getOrNull(gaps.indexOf(gaps.maxOrNull() ?: 0))?.div(1e6) ?: -1.0))
        samples.withIndex().filter { (_, s) -> s.first in (stopSrc - 1_000_000)..(stopSrc + 1_000_000) }.take(60)
            .chunked(12).forEach { ch -> say("H6 around stop: " + ch.joinToString(" ") { (i, s) -> "#$i %.3f/%d/%d".format(s.first / 1e6, s.second, s.third) }) }
        // The app's decoder, from the clip's in-point to 3 s past the stop, frame by frame (5 s per frame at most).
        val dec = com.fluxx.android.engine.VideoDecoder(context, uri)
        var t = layer.timing.sourceInUs; var last = -1L; val step = 50_000L; val t0 = System.nanoTime(); var err: String? = null
        try { while (t < stopSrc + 3_000_000) { val img = dec.frameAt(t, { false }); last = img.timestamp / 1000; t += step } }
        catch (e: Exception) { err = "${e.javaClass.simpleName}: ${e.message}" } finally { dec.close() }
        say("H6 VideoDecoder: reached request %.3f s, last frame %.3f s, %.1f s wall, ${err ?: "no error"}".format(t / 1e6, last / 1e6, (System.nanoTime() - t0) / 1e9))
        // A plain buffer-output decoder over the same samples (no surface, no ImageReader).
        val ex2 = android.media.MediaExtractor(); ex2.setDataSource(context, uri, null); ex2.selectTrack(vt)
        val codec = android.media.MediaCodec.createDecoderByType(fmt.getString(android.media.MediaFormat.KEY_MIME)!!)
        val codecName = codec.name; codec.configure(fmt, null, null, 0); codec.start()
        val info = android.media.MediaCodec.BufferInfo(); var outs = 0; var lastOut = -1L; var inEnd = false; var idle = 0L; val t1 = System.nanoTime()
        try {
            while (idle < 5_000_000_000L && lastOut < stopSrc + 3_000_000) {
                if (!inEnd) { val i = codec.dequeueInputBuffer(0); if (i >= 0) { val n = ex2.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEnd = true } else { codec.queueInputBuffer(i, 0, n, ex2.sampleTime, 0); ex2.advance() } } }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) { if (info.size > 0) { outs++; lastOut = info.presentationTimeUs }; codec.releaseOutputBuffer(o, false); idle = 0; if (info.flags and 4 != 0) break }
                else idle += 10_000_000L
            }
        } finally { codec.stop(); codec.release(); ex2.release() }
        say("H6 buffer decoder ($codecName): $outs frames, last %.3f s, %s, %.1f s wall".format(lastOut / 1e6, if (idle >= 5_000_000_000L) "STALLED 5 s" else "no stall", (System.nanoTime() - t1) / 1e9))
        check(file.length() to file.lastModified() == before) { "Project 1 changed on disk" }
    }

    /**
     * MP1.2 audio check (Snxw hears choppy sound on clips 17, 20, 21, 22, 27, 28): every clip's audio track (codec, rate,
     * channels, declared delays, first audio vs first video time, sample spacing and gaps) and a timed decode of its
     * first 10 s (real-time factor, output format, output timestamp gaps). Read-only, in place.
     */
    @Test fun audioProbe() {
        val file = File(context.filesDir, "projects/Project 1.fluxx")
        assumeTrue("MP12: Project 1 not found; skipping", file.isFile)
        val before = file.length() to file.lastModified()
        val project = kotlinx.coroutines.runBlocking { ProjectManager(context).loadProject(file) }.getOrThrow()
        for (cl in Mp12Bench.clips(project) { "" }) {
            val uri = android.net.Uri.parse(cl.uri)
            val ex = android.media.MediaExtractor()
            try {
                ex.setDataSource(context, uri, null)
                val tracks = (0 until ex.trackCount).map { ex.getTrackFormat(it) }
                val vt = tracks.indexOfFirst { it.getString("mime")!!.startsWith("video/") }
                val at = tracks.indexOfFirst { it.getString("mime")!!.startsWith("audio/") }
                if (at < 0) { say("AUDIO clip ${cl.index} '${cl.name.take(36)}': no audio track"); continue }
                val af = tracks[at]
                fun i(k: String) = if (af.containsKey(k)) runCatching { af.getInteger(k).toString() }.getOrElse { runCatching { af.getLong(k).toString() }.getOrDefault("?") } else "-"
                ex.selectTrack(vt); val firstV = ex.sampleTime; ex.unselectTrack(vt)
                ex.selectTrack(at)
                val pts = ArrayList<Long>(); val sizes = ArrayList<Int>()
                val buf = java.nio.ByteBuffer.allocate(1 shl 20)
                while (ex.sampleTime >= 0 && pts.size < 4000) { pts += ex.sampleTime; sizes += ex.readSampleData(buf, 0); ex.advance() }
                val steps = pts.zipWithNext { a, b -> b - a }
                val med = steps.sorted().getOrNull(steps.size / 2) ?: 0
                val gaps = steps.count { it > 2 * med || it < 0 }
                say("AUDIO clip ${cl.index} '${cl.name.take(36)}': ${af.getString("mime")} rate ${i("sample-rate")} ch ${i("channel-count")} bitrate ${i("bitrate")} encoder-delay ${i("encoder-delay")} encoder-padding ${i("encoder-padding")} csd ${(0..2).count { af.containsKey("csd-$it") }} duration ${i("durationUs")}; first audio %.3f s vs first video %.3f s; samples ${pts.size} step median %.1f ms, gaps/backwards $gaps, max step %.1f ms"
                    .format(pts.firstOrNull()?.div(1e6) ?: -1.0, firstV / 1e6, med / 1000.0, (steps.maxOrNull() ?: 0) / 1000.0))
                // Timed decode of the first 10 s.
                ex.seekTo(0, android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val codec = android.media.MediaCodec.createDecoderByType(af.getString("mime")!!)
                val name = codec.name
                codec.configure(af, null, null, 0); codec.start()
                val info = android.media.MediaCodec.BufferInfo(); var inEnd = false; var lastOut = -1L; var outGaps = 0; var frames = 0L
                var outFmt = ""; val t0 = System.nanoTime(); var prevEnd = -1L
                try {
                    while (lastOut < 10_000_000 && (System.nanoTime() - t0) < 20_000_000_000L) {
                        if (!inEnd) { val ii = codec.dequeueInputBuffer(1000); if (ii >= 0) { val n = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                            if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, 4); inEnd = true } else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() } } }
                        val o = codec.dequeueOutputBuffer(info, 1000)
                        if (o == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outFmt = codec.outputFormat.let { "${it.getInteger("sample-rate")} Hz ${it.getInteger("channel-count")} ch" }
                        if (o >= 0) {
                            if (info.size > 0) {
                                val f = codec.outputFormat; val ch = f.getInteger("channel-count"); val rate = f.getInteger("sample-rate")
                                val n = info.size / (2 * ch); frames += n
                                if (prevEnd >= 0 && kotlin.math.abs(info.presentationTimeUs - prevEnd) > 5_000) outGaps++
                                prevEnd = info.presentationTimeUs + n * 1_000_000L / rate; lastOut = info.presentationTimeUs
                            }
                            codec.releaseOutputBuffer(o, false); if (info.flags and 4 != 0) break
                        }
                    }
                } finally { codec.stop(); codec.release() }
                val wall = (System.nanoTime() - t0) / 1e6
                say("AUDIO clip ${cl.index} decode ($name): %.1f s of audio in %.0f ms (%.0fx real time), output $outFmt, output timestamp jumps > 5 ms: $outGaps".format(lastOut / 1e6, wall, lastOut / 1000.0 / wall.coerceAtLeast(1.0)))
            } catch (e: Exception) { say("AUDIO clip ${cl.index} '${cl.name.take(36)}': probe failed ${e.javaClass.simpleName}: ${e.message}") }
            finally { ex.release() }
        }
        check(file.length() to file.lastModified() == before) { "Project 1 changed on disk" }
    }

    /** MP1.2 audio: the order of format changes and the first output buffers of clips 17 and 1 (from 0 and from a mid-file seek). */
    @Test fun audioFormatProbe() {
        val file = File(context.filesDir, "projects/Project 1.fluxx")
        assumeTrue("MP12: Project 1 not found; skipping", file.isFile)
        val project = kotlinx.coroutines.runBlocking { ProjectManager(context).loadProject(file) }.getOrThrow()
        val clips = Mp12Bench.clips(project) { "" }
        for (k in listOf(17, 20, 1)) for (seekUs in listOf(0L, 4_000_000L)) {
            val cl = clips.first { it.index == k }
            val ex = android.media.MediaExtractor(); ex.setDataSource(context, android.net.Uri.parse(cl.uri), null)
            val at = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString("mime")!!.startsWith("audio/") }
            val af = ex.getTrackFormat(at); ex.selectTrack(at)
            if (seekUs > 0) ex.seekTo(seekUs, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val codec = android.media.MediaCodec.createDecoderByType(af.getString("mime")!!)
            codec.configure(af, null, null, 0); codec.start()
            val log = ArrayList<String>(); val info = android.media.MediaCodec.BufferInfo(); var outs = 0; var inEnd = false; val t0 = System.nanoTime()
            log += "configured-format ${codec.outputFormat.let { "${it.getInteger("sample-rate")}/${it.getInteger("channel-count")}" }}"
            try {
                while (outs < 6 && System.nanoTime() - t0 < 5_000_000_000L) {
                    if (!inEnd) { val ii = codec.dequeueInputBuffer(1000); if (ii >= 0) { val n = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, 4); inEnd = true } else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() } } }
                    val o = codec.dequeueOutputBuffer(info, 1000)
                    if (o == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) log += "FORMAT-CHANGED ${codec.outputFormat.let { "${it.getInteger("sample-rate")}/${it.getInteger("channel-count")}" }}"
                    if (o >= 0) { log += "out pts %.3f size ${info.size} (${info.size / 4} frames at 16-bit stereo)".format(info.presentationTimeUs / 1e6); outs++; codec.releaseOutputBuffer(o, false) }
                }
            } finally { codec.stop(); codec.release(); ex.release() }
            say("AUDIOFMT clip $k from %.1f s: declared ${af.getInteger("sample-rate")} Hz: ".format(seekUs / 1e6) + log.joinToString(" | "))
        }
    }

    /**
     * MP1.2 audio: the app's decoded-PCM cache (what playback reads) against a direct decode of the same file, for clips
     * 17, 20 (choppy for Snxw, HE-AAC 24 kHz MKV), 1 (AAC 44.1 kHz MKV) and 15 (Opus): best offset, then 20 ms blocks whose
     * difference is large (bad blocks) or that are silent in the cache but not in the file (holes).
     */
    @Test fun audioCacheCompare() {
        val file = File(context.filesDir, "projects/Project 1.fluxx")
        assumeTrue("MP12: Project 1 not found; skipping", file.isFile)
        val project = kotlinx.coroutines.runBlocking { ProjectManager(context).loadProject(file) }.getOrThrow()
        val clips = Mp12Bench.clips(project) { "" }
        for (k in listOf(17, 20, 1, 15)) {
            val cl = clips.first { it.index == k }
            val durUs = project.composition.layers.first { it.asset?.uri == cl.uri }.asset!!.durationUs ?: continue
            val pcm = com.fluxx.android.render.SourcePcmCache.get(context).get(cl.uri, durUs)
            pcm.setScope(listOf(0L..durUs)); pcm.fill()
            val t0 = System.nanoTime()
            while (!pcm.complete && System.nanoTime() - t0 < 60_000_000_000L) Thread.sleep(100)
            val n = (pcm.frames - 4800).toInt().coerceAtMost(48_000 * 40)
            val cache = ShortArray(n * 2); val scratch = ByteArray(n * 4)
            val ok = pcm.read(0, n, cache, scratch)
            // Direct decode at the decoder's own rate (48 kHz for the HE-AAC and Opus files, else 44.1 kHz).
            val ex = android.media.MediaExtractor(); ex.setDataSource(context, android.net.Uri.parse(cl.uri), null)
            val at = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString("mime")!!.startsWith("audio/") }
            val af = ex.getTrackFormat(at); ex.selectTrack(at)
            val codec = android.media.MediaCodec.createDecoderByType(af.getString("mime")!!); codec.configure(af, null, null, 0); codec.start()
            val ref = ShortArray(n * 2 + 96_000); var refN = 0; var rate = 0; var inEnd = false; val info = android.media.MediaCodec.BufferInfo()
            try {
                while (refN < ref.size / 2 - 8192) {
                    if (!inEnd) { val ii = codec.dequeueInputBuffer(1000); if (ii >= 0) { val m = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                        if (m < 0) { codec.queueInputBuffer(ii, 0, 0, 0, 4); inEnd = true } else { codec.queueInputBuffer(ii, 0, m, ex.sampleTime, 0); ex.advance() } } }
                    val o = codec.dequeueOutputBuffer(info, 1000)
                    if (o >= 0) {
                        val f = codec.outputFormat; rate = f.getInteger("sample-rate"); val ch = f.getInteger("channel-count")
                        val sb = codec.getOutputBuffer(o)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val frames = info.size / (2 * ch)
                        for (i in 0 until frames) { if (refN >= ref.size / 2) break; ref[refN * 2] = sb.get(i * ch); ref[refN * 2 + 1] = sb.get(i * ch + if (ch > 1) 1 else 0); refN++ }
                        codec.releaseOutputBuffer(o, false); if (info.flags and 4 != 0) break
                    }
                }
            } finally { codec.stop(); codec.release(); ex.release() }
            if (rate != 48_000) { say("AUDIOCMP clip $k: decoder rate $rate Hz (the cache is resampled to 48 kHz): offset/blocks compare skipped; cache read ok=$ok complete=${pcm.complete}"); continue }
            // Best offset (ref index = cache index + lag), lag 0..4800, on 1 s from 0.5 s.
            var best = 0; var bestErr = Double.MAX_VALUE
            for (lag in 0..4800) { var e = 0.0; var i = 24_000; while (i < 72_000) { val d = (cache[i * 2] - ref[(i + lag) * 2]).toDouble(); e += d * d; i += 4 }; if (e < bestErr) { bestErr = e; best = lag } }
            var bad = 0; var holes = 0; val badAt = ArrayList<String>()
            val blk = 960; var b = 0
            while ((b + 1) * blk + best < refN && (b + 1) * blk < n) {
                var es = 0.0; var ed = 0.0; var ec = 0.0
                for (i in b * blk until (b + 1) * blk) { val r = ref[(i + best) * 2].toDouble(); val c = cache[i * 2].toDouble(); es += r * r; ed += (r - c) * (r - c); ec += c * c }
                if (es > 1e6 * blk / 960) {
                    if (ec < es * 0.001) { holes++; if (badAt.size < 12) badAt += "hole %.2f".format(b * 0.02) }
                    else if (ed > es * 0.1) { bad++; if (badAt.size < 12) badAt += "bad %.2f".format(b * 0.02) }
                }
                b++
            }
            say("AUDIOCMP clip $k '${cl.name.take(30)}': cache complete=${pcm.complete} read=$ok, offset ${best} frames (%.1f ms; app priming %.1f ms), $b blocks of 20 ms: bad $bad, holes $holes; first: ${badAt.joinToString(", ")}".format(best / 48.0, pcm.primingUs / 1000.0))
        }
    }

    @Test fun project1Playthrough() = run("Project 1") { it }

    /**
     * Snxw's check: Project 1 with clip 13 (the VOGUE file) swapped, IN MEMORY ONLY, for another long AVC file from
     * Download/Seal (same start and length; preferably 1440x1080), so the rest of Project 1 is shown to play end to end
     * without the H6 hold being needed. The file on disk is untouched (checked).
     */
    @Test fun project1Clip13Replaced() = run("Project 1, clip 13 replaced") { project ->
        val clip = Mp12Bench.clips(project) { "" }.first { it.index == 13 }
        val layer = project.composition.layers.first { it.asset?.uri == clip.uri && it.timing.startUs == clip.startUs }
        val needUs = layer.timing.durationUs ?: 0L
        val inProject = project.composition.layers.mapNotNull { it.asset?.uri }.toSet()
        val cr = context.contentResolver
        val candidates = ArrayList<Pair<android.net.Uri, String>>()
        for (volume in android.provider.MediaStore.getExternalVolumeNames(context)) {
            val col = android.provider.MediaStore.Video.Media.getContentUri(volume)
            cr.query(col, arrayOf(android.provider.MediaStore.MediaColumns._ID, android.provider.MediaStore.MediaColumns.DISPLAY_NAME),
                "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${android.provider.MediaStore.MediaColumns.DURATION} >= ?",
                arrayOf("Download/Seal/", "60000"), null)?.use { c ->
                while (c.moveToNext()) { val u = android.content.ContentUris.withAppendedId(col, c.getLong(0)); if (u.toString() !in inProject) candidates += u to c.getString(1) }
            }
        }
        val probed = candidates.mapNotNull { (u, n) -> Mp12Bench.probe(context, u, n, 0) }
        val long = probed.filter { it.durationUs >= needUs + layer.timing.sourceInUs }
        // An AVC file long enough (1440x1080 first), else any file long enough, else the longest AVC one (clip 13 trimmed to it).
        val pick = long.filter { it.codec == "avc" }.let { a -> a.firstOrNull { it.width == 1440 && it.height == 1080 } ?: a.firstOrNull() }
            ?: long.firstOrNull() ?: probed.filter { it.codec == "avc" }.maxByOrNull { it.durationUs }
        assumeTrue("MP12: no replacement file of 60 s or more in Download/Seal; skipping", pick != null)
        say("clip 13 replaced in memory: '${clip.name.take(30)}' -> ${pick!!.codec} ${pick.width}x${pick.height} %.2f fps %.1f s '${pick.name.take(40)}'".format(pick.fps, pick.durationUs / 1e6))
        val asset = AssetReference("mp12-replacement", pick.uri.toString(), MediaAccess.AVAILABLE, pick.durationUs)
        project.copy(composition = project.composition.copy(layers = FrozenList.of(project.composition.layers.map {
            if (it.id == layer.id) it.copy(asset = asset, name = pick.name, timing = it.timing.copy(sourceInUs = 0, durationUs = minOf(needUs, pick.durationUs - 50_000))) else it })))
    }

    private fun run(label: String, transform: (ProjectDocument) -> ProjectDocument) {
        val file = File(context.filesDir, "projects/Project 1.fluxx")
        assumeTrue("MP12: Project 1 not found; skipping", file.isFile)
        assumeTrue("MP12: Project 1 has a pending atomic-save file (.bak / .new); not touching it", !File(file.path + ".bak").exists() && !File(file.path + ".new").exists())
        val before = file.length() to file.lastModified()
        say("Project 1 before: size ${before.first} mtime ${before.second}")
        try {
            val project = transform(kotlinx.coroutines.runBlocking { ProjectManager(context).loadProject(file) }.getOrThrow())
            host(project)
            Thread.sleep(3000)
            val limit = (project.composition.resolvedDurationUs / 1000 * 2 + 60_000).coerceAtMost(25 * 60_000L)
            playThrough(label, project, limit)
        } finally {
            val after = file.length() to file.lastModified()
            say("Project 1 after: size ${after.first} mtime ${after.second} -> ${if (after == before) "UNCHANGED" else "CHANGED"}")
            check(after == before) { "Project 1 changed on disk" }
        }
    }
}
