package com.fluxx.android.ui

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.fluxx.android.ProjectManager
import com.fluxx.android.editor.EditorAction
import com.fluxx.android.model.*
import com.fluxx.android.render.AudioTiming
import com.fluxx.android.render.PreviewFrames
import com.fluxx.android.render.SourcePcm
import com.fluxx.android.render.SourcePcmCache
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * AUD3: choppy audio on HE-AAC clips (Snxw, by ear: Project 1 clips 17, 20, 21, 22, 27, 28; HE-AAC declared at 24 kHz).
 * Controls: clips 1 and 5 (AAC-LC 44.1 kHz), 15 (Opus), and 23 / 26 (HE-AAC declared at 22.05 kHz, unconfirmed by ear),
 * plus HE-AAC files from Download/Seal in other containers / rates. Project 1 is read-only (its loader only; stat checked).
 *  a1: what each file declares versus what its decoder outputs.
 *  a2: the app's PCM (the cache playback reads) against a straight-through reference decode, aligned and level-normalised;
 *      the method is proven on the clean controls first. A fresh test cache (filled from 0 in one run) and the app's own
 *      existing cache for the same source are both measured.
 *  a3: playback from a cold cache (the same file under a distinct file:// spelling, so the app builds a new cache entry):
 *      blocks the mixer had to play as silence (not decoded yet), track underruns, per second, from 0 and after a seek.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class Aud3Test : Mp12Base() {
    override val tag = "AUD3"
    private fun aud(line: String) = Mp12Bench.say("AUD3", line)

    data class Item(val label: String, val uri: String, val name: String, val durationUs: Long, val path: String?)

    private fun project1(): ProjectDocument {
        val file = File(context.filesDir, "projects/Project 1.fluxx")
        assumeTrue("AUD3: Project 1 not found; skipping", file.isFile)
        return kotlinx.coroutines.runBlocking { ProjectManager(context).loadProject(file) }.getOrThrow()
    }
    private val p1stat by lazy { File(context.filesDir, "projects/Project 1.fluxx").let { it.length() to it.lastModified() } }
    private fun checkP1() {
        val now = File(context.filesDir, "projects/Project 1.fluxx").let { it.length() to it.lastModified() }
        aud("Project 1 stat: before ${p1stat.first}/${p1stat.second}, now ${now.first}/${now.second} -> ${if (now == p1stat) "UNCHANGED" else "CHANGED"}")
        check(now == p1stat) { "Project 1 changed on disk" }
    }

    /** The MediaStore file path of a content URI (for a distinct file:// spelling in a3), or null. */
    private fun pathOf(uri: String): String? = runCatching {
        context.contentResolver.query(Uri.parse(uri), arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun items(): List<Item> {
        val p = project1(); p1stat
        val clips = Mp12Bench.clips(p) { "" }
        val labels = linkedMapOf(1 to "control AAC-LC 44.1k", 5 to "control AAC-LC 44.1k", 15 to "control Opus", 23 to "HE-AAC 22.05k (unconfirmed)", 26 to "HE-AAC 22.05k (unconfirmed)",
            17 to "CHOPPY HE-AAC 24k", 20 to "CHOPPY HE-AAC 24k", 21 to "CHOPPY HE-AAC 24k", 22 to "CHOPPY HE-AAC 24k", 27 to "CHOPPY HE-AAC 24k", 28 to "CHOPPY HE-AAC 24k")
        val out = ArrayList<Item>()
        for ((k, label) in labels) {
            val cl = clips.first { it.index == k }
            val asset = p.composition.layers.first { it.asset?.uri == cl.uri }.asset!!
            out += Item("clip $k $label", cl.uri, cl.name, asset.durationUs ?: continue, pathOf(cl.uri))
        }
        // HE-AAC from Download/Seal outside Project 1 (declared below 32 kHz), MP4 first (a different container), up to 3.
        val inP1 = clips.map { it.uri }.toSet()
        val extra = ArrayList<Item>()
        val cr = context.contentResolver
        for (volume in android.provider.MediaStore.getExternalVolumeNames(context)) {
            val col = android.provider.MediaStore.Video.Media.getContentUri(volume)
            cr.query(col, arrayOf(android.provider.MediaStore.MediaColumns._ID, android.provider.MediaStore.MediaColumns.DISPLAY_NAME, android.provider.MediaStore.MediaColumns.DURATION),
                "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf("Download/Seal/"), "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} ASC")?.use { c ->
                while (c.moveToNext() && extra.size < 3) {
                    val uri = android.content.ContentUris.withAppendedId(col, c.getLong(0)).toString()
                    if (uri in inP1) continue
                    val name = c.getString(1)
                    val (mime, rate) = audioDeclared(uri) ?: continue
                    if (mime != "audio/mp4a-latm" || rate >= 32_000) continue
                    val dur = c.getLong(2) * 1000
                    if (dur < 8_000_000) continue
                    extra += Item("Seal HE-AAC? ${rate / 1000.0}k ${name.substringAfterLast('.')}", uri, name, dur, pathOf(uri))
                }
            }
        }
        if (extra.isEmpty()) aud("no further HE-AAC candidates (declared < 32 kHz, >= 8 s) in Download/Seal")
        return out + extra
    }

    private fun audioDeclared(uri: String): Pair<String, Int>? {
        val ex = MediaExtractor()
        return try { ex.setDataSource(context, Uri.parse(uri), null)
            (0 until ex.trackCount).map { ex.getTrackFormat(it) }.firstOrNull { it.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
                ?.let { it.getString(MediaFormat.KEY_MIME)!! to it.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
        } catch (_: Exception) { null } finally { ex.release() }
    }

    // ---- a1 ----------------------------------------------------------------------------------------------------------
    @Test fun a1Formats() {
        for (it in items()) {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(context, Uri.parse(it.uri), null)
                val at = (0 until ex.trackCount).first { t -> ex.getTrackFormat(t).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
                val f = ex.getTrackFormat(at); ex.selectTrack(at)
                val mime = f.getString(MediaFormat.KEY_MIME)!!
                val csd = if (f.containsKey("csd-0")) f.getByteBuffer("csd-0")!!.let { b -> ByteArray(b.remaining()).also { a -> b.duplicate().get(a) } } else null
                val asc = csd?.let { a -> val aot = (a[0].toInt() and 0xff) shr 3; val sfi = ((a[0].toInt() and 7) shl 1) or ((a.getOrElse(1) { 0 }.toInt() and 0xff) shr 7)
                    "csd-0 ${a.joinToString("") { b -> "%02x".format(b) }} (object type $aot${if (aot == 5 || aot == 29) " SBR explicit" else if (aot == 2) " AAC-LC (SBR implicit if any)" else ""}, rate index $sfi)" } ?: "no csd-0"
                val profile = if (f.containsKey(MediaFormat.KEY_AAC_PROFILE)) f.getInteger(MediaFormat.KEY_AAC_PROFILE).toString() else "-"
                val delay = if (f.containsKey(MediaFormat.KEY_ENCODER_DELAY)) f.getInteger(MediaFormat.KEY_ENCODER_DELAY) else null
                val firstPts = ex.sampleTime
                val declared = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val codec = MediaCodec.createDecoderByType(mime); val name = codec.name
                codec.configure(f, null, null, 0); codec.start()
                val info = MediaCodec.BufferInfo(); var outRate = 0; var outCh = 0; var firstOut = -1L; var frameSizes = HashMap<Int, Int>(); var inEnd = false; var outs = 0
                val t0 = System.nanoTime()
                try {
                    while (outs < 40 && System.nanoTime() - t0 < 5_000_000_000L) {
                        if (!inEnd) { val i = codec.dequeueInputBuffer(1000); if (i >= 0) { val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, 4); inEnd = true } else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() } } }
                        val o = codec.dequeueOutputBuffer(info, 1000)
                        if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) codec.outputFormat.let { of -> outRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); outCh = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                        if (o >= 0) { if (info.size > 0) { if (firstOut < 0) firstOut = info.presentationTimeUs
                            val fr = info.size / (2 * maxOf(1, outCh)); frameSizes[fr] = (frameSizes[fr] ?: 0) + 1; outs++ }
                            codec.releaseOutputBuffer(o, false) }
                    }
                } finally { codec.stop(); codec.release() }
                // The app's priming: AudioTiming.primingSamples at the decoder's OUTPUT rate (SourcePcm recomputes it at the format change).
                val prim = AudioTiming.primingSamples(mime, delay, firstPts)
                aud("A1 ${it.label} '${it.name.take(32)}': $mime ($name) declared $declared Hz ${f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)} ch, aac-profile $profile, $asc; decoder output $outRate Hz $outCh ch, samples per buffer $frameSizes, first in %.3f s / first out %.3f s; encoder-delay ${delay ?: "-"}; app priming $prim samples = %.1f ms at the output rate (%.1f ms if taken at the declared rate)"
                    .format(firstPts / 1e6, firstOut / 1e6, AudioTiming.us(prim, maxOf(1, outRate)) / 1000.0, AudioTiming.us(prim, declared) / 1000.0))
            } catch (e: Exception) { aud("A1 ${it.label}: failed ${e.javaClass.simpleName}: ${e.message}") } finally { ex.release() }
        }
        checkP1()
    }

    // ---- a2: content -------------------------------------------------------------------------------------------------
    /** A straight-through decode (no cache, no seek) to 48 kHz stereo floats, resampled with the app's own resampler. */
    private fun reference(uri: String, maxFrames: Int): FloatArray {
        val ex = MediaExtractor(); ex.setDataSource(context, Uri.parse(uri), null)
        val at = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
        val f = ex.getTrackFormat(at); ex.selectTrack(at)
        val codec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!); codec.configure(f, null, null, 0); codec.start()
        val out = FloatArray(maxFrames * 2); var n = 0
        var rs: com.fluxx.android.render.Resampler? = null
        val info = MediaCodec.BufferInfo(); var inEnd = false; var stereo = FloatArray(0); var shorts = ShortArray(0)
        try {
            while (n < maxFrames) {
                if (!inEnd) { val i = codec.dequeueInputBuffer(1000); if (i >= 0) { val m = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (m < 0) { codec.queueInputBuffer(i, 0, 0, 0, 4); inEnd = true } else { codec.queueInputBuffer(i, 0, m, ex.sampleTime, 0); ex.advance() } } }
                val o = codec.dequeueOutputBuffer(info, 1000)
                if (o >= 0) {
                    val of = codec.outputFormat; val ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT); val rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (rs == null) rs = com.fluxx.android.render.Resampler(rate)
                    val frames = info.size / (2 * ch)
                    if (frames > 0) {
                        val sb = codec.getOutputBuffer(o)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN).also { it.position(info.offset); it.limit(info.offset + info.size) }.asShortBuffer()
                        if (shorts.size < frames * ch) shorts = ShortArray(frames * ch); sb.get(shorts, 0, frames * ch)
                        if (stereo.size < frames * 2) stereo = FloatArray(frames * 2)
                        com.fluxx.android.render.AudioFold.stereo(shorts, frames, ch, stereo)
                        rs.run(stereo, frames, n.toLong()) { _, data, count -> val c = minOf(count, maxFrames - n); System.arraycopy(data, 0, out, n * 2, c * 2); n += c }
                    }
                    codec.releaseOutputBuffer(o, false); if (info.flags and 4 != 0) break
                }
            }
        } finally { codec.stop(); codec.release(); ex.release() }
        return out.copyOf(n * 2)
    }

    private fun readCache(pcm: SourcePcm, frames: Int): FloatArray {
        val sh = ShortArray(frames * 2); val sc = ByteArray(frames * 4)
        pcm.read(0, frames, sh, sc)
        return FloatArray(frames * 2) { sh[it] / 32768f }
    }

    private fun mono(a: FloatArray, i: Int) = (a[i * 2] + a[i * 2 + 1]) * 0.5f

    /** The lag (ref index = cache index + lag) maximising the normalised correlation over [from, from + len), searched in [lo, hi]. */
    private fun lag(c: FloatArray, r: FloatArray, from: Int, len: Int, lo: Int, hi: Int, step: Int): Int {
        var best = lo; var bestScore = -2.0
        var l = lo
        while (l <= hi) {
            var xy = 0.0; var xx = 0.0; var yy = 0.0; var i = from
            while (i < from + len) { val j = i + l; if (j >= 0 && j < r.size / 2 && i < c.size / 2) { val x = mono(c, i).toDouble(); val y = mono(r, j).toDouble(); xy += x * y; xx += x * x; yy += y * y }; i += step }
            val s = if (xx > 0 && yy > 0) xy / Math.sqrt(xx * yy) else -1.0
            if (s > bestScore) { bestScore = s; best = l }
            l += step
        }
        return best
    }

    private fun compare(label: String, c: FloatArray, r: FloatArray) {
        val frames = minOf(c.size, r.size) / 2
        if (frames < 96_000) { aud("A2 $label: too short (${frames} frames)"); return }
        // Global lag: coarse (step 8) over +/-200 ms on 2 s from 0.5 s, then fine (+/-16, step 1).
        val coarse = lag(c, r, 24_000, 96_000, -9_600, 9_600, 8)
        val g = lag(c, r, 24_000, 96_000, coarse - 16, coarse + 16, 1)
        // Level: least-squares gain of the cache against the reference.
        var xy = 0.0; var yy = 0.0
        for (i in 24_000 until frames - 9_700) { val j = i + g; if (j in 0 until r.size / 2) { val x = mono(c, i).toDouble(); val y = mono(r, j).toDouble(); xy += x * y; yy += y * y } }
        val gain = if (yy > 0) xy / yy else 1.0
        // 20 ms blocks: relative error energy (blocks with signal only), and the local lag per 1 s window.
        val blk = 960; val perSec = ArrayList<Double>(); var worst = 0.0; var worstAt = -1.0; var bad = 0; var badAtJoin = 0; var silentHoles = 0; var blocks = 0
        val lags = ArrayList<Int>()
        var s = 0
        while ((s + 1) * 48_000 + g + 960 < r.size / 2 && (s + 1) * 48_000 < frames) {
            val local = lag(c, r, s * 48_000 + 2_400, 43_200, g - 480, g + 480, 1).let { it - g }
            lags += local
            var es = 0.0; var ed = 0.0
            for (b in 0 until 50) {
                var bs = 0.0; var bd = 0.0; var bc = 0.0
                for (i in s * 48_000 + b * blk until s * 48_000 + (b + 1) * blk) { val j = i + g; if (j !in 0 until r.size / 2) continue
                    for (ch in 0..1) { val y = r[j * 2 + ch] * gain; val x = c[i * 2 + ch].toDouble(); bs += y * y; bd += (x - y) * (x - y); bc += x * x } }
                es += bs; ed += bd
                if (bs > 1e-3 * blk) {
                    blocks++
                    val e = bd / bs
                    if (bc < bs * 1e-4) silentHoles++
                    if (e > 0.1) { bad++; if (b == 0 || b == 49) badAtJoin++ }
                    if (e > worst) { worst = e; worstAt = s + b * 0.02 }
                }
            }
            perSec += if (es > 0) Math.sqrt(ed / es) else 0.0
            s++
        }
        val lagChanges = lags.zipWithNext().count { (a, b) -> kotlin.math.abs(a - b) > 2 }
        aud("A2 $label: lag %d frames (%.1f ms), gain %.3f; %d s compared: per-second relative RMS error median %.3f max %.3f; 20 ms blocks with signal $blocks, bad (> 10%% error energy) $bad (of which at a 1 s chunk join $badAtJoin), silent holes $silentHoles; worst block %.3f at %.2f s; local lag per second (frames vs global): %s; lag changes $lagChanges"
            .format(g, g / 48.0, gain, perSec.size, perSec.sorted().getOrElse(perSec.size / 2) { 0.0 }, perSec.maxOrNull() ?: 0.0, worst, worstAt, lags.take(40).joinToString(",")))
    }

    @Test fun a2Content() {
        val dir = File(context.cacheDir, "aud3-test").apply { mkdirs() }
        for (it in items()) {
            try {
                val frames = minOf(AudioTiming.frames(it.durationUs).toInt() - 4_800, 48_000 * 30)
                val ref = reference(it.uri, frames + 20_000)
                // (1) A fresh test cache, filled from 0 by the app's own decode (one run, nothing before).
                val f = File(dir, "a2-${it.uri.hashCode().toUInt()}.pcm"); f.delete(); File(f.path.removeSuffix(".pcm") + ".cov").delete()
                val fresh = SourcePcm(context, it.uri, f, it.durationUs)
                fresh.setScope(listOf(0L..it.durationUs)); fresh.fill()
                val t0 = System.nanoTime(); while (!fresh.complete && System.nanoTime() - t0 < 60_000_000_000L) Thread.sleep(50)
                aud("A2 ${it.label}: fresh cache complete=${fresh.complete} in ${(System.nanoTime() - t0) / 1_000_000} ms, app priming %.1f ms".format(fresh.primingUs / 1000.0))
                compare("${it.label} FRESH", readCache(fresh, frames), ref)
                // (2) A second fresh cache filled the way playback asks: a reader at 3 s first, then 0 (two runs).
                val f2 = File(dir, "a2b-${it.uri.hashCode().toUInt()}.pcm"); f2.delete(); File(f2.path.removeSuffix(".pcm") + ".cov").delete()
                val two = SourcePcm(context, it.uri, f2, it.durationUs)
                two.setScope(listOf(0L..it.durationUs)); two.ensure(144_000)
                val t1 = System.nanoTime(); while (!two.covers(144_000, 192_000) && System.nanoTime() - t1 < 30_000_000_000L) Thread.sleep(20)
                two.ensure(0)
                while (!two.complete && System.nanoTime() - t1 < 60_000_000_000L) { Thread.sleep(50); two.fill() }
                compare("${it.label} TWO-RUNS (3 s first, then 0)", readCache(two, frames), ref)
                fresh.close(); two.close()
                listOf(f, f2).forEach { x -> x.delete(); File(x.path.removeSuffix(".pcm") + ".cov").delete() }
                // (3) The app's own existing cache for this source (what Project 1 playback reads now).
                val app = SourcePcmCache.get(context).get(it.uri, it.durationUs)
                if (app.covers(0, frames.toLong())) compare("${it.label} APP-CACHE", readCache(app, frames), ref)
                else aud("A2 ${it.label} APP-CACHE: not fully covered (not compared)")
            } catch (e: Exception) { aud("A2 ${it.label}: failed ${e.javaClass.simpleName}: ${e.message}") }
        }
        dir.delete()
        checkP1()
    }

    // ---- a4: MP4 with an edit list (negative first pts and an encoder delay) -----------------------------------------
    /**
     * The true timeline of a source at 48 kHz: index 0 = stream time 0. Inputs are queued with their pts shifted by
     * [shiftUs] (so no input is negative and the decoder drops nothing), outputs placed at (pts - shift), contiguously.
     * Returns (samples, output buffers, first output pts in source time).
     */
    private fun trueTimeline(uri: String, maxFrames: Int, shiftUs: Long): Triple<FloatArray, Int, Long> {
        val ex = MediaExtractor(); ex.setDataSource(context, Uri.parse(uri), null)
        val at = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
        val f = ex.getTrackFormat(at); ex.selectTrack(at)
        val codec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!); codec.configure(f, null, null, 0); codec.start()
        val out = FloatArray(maxFrames * 2); var rs: com.fluxx.android.render.Resampler? = null
        val info = MediaCodec.BufferInfo(); var inEnd = false; var stereo = FloatArray(0); var shorts = ShortArray(0); var bufs = 0; var firstPts = Long.MIN_VALUE
        try {
            while (true) {
                if (!inEnd) { val i = codec.dequeueInputBuffer(1000); if (i >= 0) { val m = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (m < 0) { codec.queueInputBuffer(i, 0, 0, 0, 4); inEnd = true } else { codec.queueInputBuffer(i, 0, m, ex.sampleTime + shiftUs, 0); ex.advance() } } }
                val o = codec.dequeueOutputBuffer(info, 1000)
                if (o >= 0) {
                    val of = codec.outputFormat; val ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT); val rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (rs == null) rs = com.fluxx.android.render.Resampler(rate)
                    val frames = info.size / (2 * ch)
                    if (frames > 0) {
                        bufs++; val pts = info.presentationTimeUs - shiftUs; if (firstPts == Long.MIN_VALUE) firstPts = pts
                        val sb = codec.getOutputBuffer(o)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN).also { it.position(info.offset); it.limit(info.offset + info.size) }.asShortBuffer()
                        if (shorts.size < frames * ch) shorts = ShortArray(frames * ch); sb.get(shorts, 0, frames * ch)
                        if (stereo.size < frames * 2) stereo = FloatArray(frames * 2)
                        com.fluxx.android.render.AudioFold.stereo(shorts, frames, ch, stereo)
                        rs.run(stereo, frames, Math.floorDiv(pts * 48_000 + 500_000, 1_000_000L)) { first, data, count ->
                            for (k in 0 until count) { val p = first + k; if (p in 0 until maxFrames) { out[(p * 2).toInt()] = data[k * 2]; out[(p * 2 + 1).toInt()] = data[k * 2 + 1] } } }
                    }
                    codec.releaseOutputBuffer(o, false); if (info.flags and 4 != 0) break
                }
            }
        } finally { codec.stop(); codec.release(); ex.release() }
        return Triple(out, bufs, firstPts)
    }

    /** Error of [c] against [r] at lag 0 after a 9-tap moving average (insensitive to a sub-sample resampling phase). */
    private fun lowPassError(c: FloatArray, r: FloatArray, from: Int, to: Int): Double {
        var es = 0.0; var ed = 0.0
        for (i in from until to) { var x = 0.0; var y = 0.0; for (k in -4..4) { x += mono(c, i + k); y += mono(r, i + k) }; es += y * y; ed += (x - y) * (x - y) }
        return if (es > 0) Math.sqrt(ed / es) else 0.0
    }

    @Test fun a4Mp4EditList() {
        val dir = File(context.cacheDir, "aud3-test").apply { mkdirs() }
        val all = items()
        val p = project1(); val clips = Mp12Bench.clips(p) { "" }
        val more = listOf(14, 19, 24).map { k -> val cl = clips.first { it.index == k }
            Item("clip $k MP4 AAC", cl.uri, cl.name, p.composition.layers.first { it.asset?.uri == cl.uri }.asset!!.durationUs!!, null) }
        for (it in all.filter { x -> x.label.contains("mp4") || x.label.startsWith("clip 1 ") } + more) {
            try {
                val ex = MediaExtractor(); ex.setDataSource(context, Uri.parse(it.uri), null)
                val at = (0 until ex.trackCount).first { t -> ex.getTrackFormat(t).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
                val f = ex.getTrackFormat(at); ex.selectTrack(at); val firstIn = ex.sampleTime; ex.release()
                val delay = if (f.containsKey(MediaFormat.KEY_ENCODER_DELAY)) f.getInteger(MediaFormat.KEY_ENCODER_DELAY) else null
                val pad = if (f.containsKey(MediaFormat.KEY_ENCODER_PADDING)) f.getInteger(MediaFormat.KEY_ENCODER_PADDING) else null
                val declared = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val frames = minOf(AudioTiming.frames(it.durationUs).toInt() - 4_800, 48_000 * 20)
                val shift = maxOf(0L, -firstIn)
                val (plain, plainBufs, plainFirst) = trueTimeline(it.uri, frames, 0)
                val (truth, shiftedBufs, shiftedFirst) = trueTimeline(it.uri, frames, shift)
                // The app's cache, fresh.
                val fc = File(dir, "a4-${it.uri.hashCode().toUInt()}.pcm"); fc.delete(); File(fc.path.removeSuffix(".pcm") + ".cov").delete()
                val pcm = SourcePcm(context, it.uri, fc, it.durationUs); pcm.setScope(listOf(0L..it.durationUs)); pcm.fill()
                val t0 = System.nanoTime(); while (!pcm.complete && System.nanoTime() - t0 < 60_000_000_000L) Thread.sleep(50)
                val app = readCache(pcm, frames); pcm.close(); fc.delete(); File(fc.path.removeSuffix(".pcm") + ".cov").delete()
                // Where the app's sound sits against the true timeline (positive = the app plays it EARLY by that much).
                val l = lag(app, truth, 48_000, 96_000, -9_600, 9_600, 4).let { c -> lag(app, truth, 48_000, 96_000, c - 8, c + 8, 1) }
                val firstSound = (0 until frames).firstOrNull { i -> kotlin.math.abs(mono(truth, i)) > 1e-3f } ?: -1
                aud("A4 ${it.label} '${it.name.take(30)}': declared $declared Hz, first input pts %.4f s (= %d samples at the declared rate), encoder-delay ${delay ?: "-"} padding ${pad ?: "-"}; app priming %.1f ms; decoder: unshifted %d buffers, first out %.4f s; inputs shifted +%.4f s: %d buffers, first out %.4f s; true first sound at %.1f ms; app vs true: lag %d frames = app %s by %.1f ms; low-pass error at that lag %.3f, at lag 0 (correct placement) %.3f"
                    .format(firstIn / 1e6, Math.round(-firstIn * declared / 1e6), pcm.primingUs / 1000.0, plainBufs, plainFirst / 1e6, shift / 1e6, shiftedBufs, shiftedFirst / 1e6,
                        firstSound / 48.0, l, if (l >= 0) "EARLY" else "late", kotlin.math.abs(l) / 48.0,
                        lowPassError(app, FloatArray(truth.size) { j -> val q = j + l * 2; if (q in truth.indices) truth[q] else 0f }, 24_000, frames - 20_000),
                        lowPassError(app, truth, 24_000, frames - 20_000)))
            } catch (e: Exception) { aud("A4 ${it.label}: failed ${e.javaClass.simpleName}: ${e.message}") }
        }
        dir.delete()
        checkP1()
    }

    // ---- a3: playback ------------------------------------------------------------------------------------------------
    private fun cold(path: String, k: Int) = Uri.fromFile(File(File(path).parentFile, "./".repeat(k) + File(path).name)).toString()

    @Test fun a3Playback() {
        val all = items()
        val pick = all.filter { it.label.contains("CHOPPY") }.take(3) + all.filter { it.label.contains("control") } + all.filter { it.label.contains("22.05") }.take(1)
        val nonce = (System.currentTimeMillis() / 1000 % 50 + 2).toInt()
        host(Aud2Bench.project(1440, 1080, 10_000_000, 20, emptyList()))
        for ((idx, it) in pick.withIndex()) {
            val path = it.path ?: run { aud("A3 ${it.label}: no file path; skipped"); null } ?: continue
            for ((mode, startUs) in listOf("from 0" to 0L, "after a seek to 5 s" to 5_000_000L)) {
                val uri = cold(path, nonce + idx * 2 + if (startUs > 0) 1 else 0)
                val clipUs = minOf(it.durationUs - 100_000, 10_000_000L)
                val layer = CompositionLayer(1, LayerType.VIDEO, name = it.name, zOrder = 10, timing = ClipTiming(0, 0, clipUs),
                    asset = AssetReference("aud3-$idx", uri, MediaAccess.AVAILABLE, it.durationUs))
                val proj = Aud2Bench.project(1440, 1080, clipUs, 20, listOf(layer))
                val ev = CopyOnWriteArrayList<Pair<Long, String>>()
                PreviewFrames.testPlayback = { e -> ev += System.nanoTime() to e }
                val main = InstrumentationRegistry.getInstrumentation()
                compose.runOnIdle { editor.dispatch(EditorAction.Load(proj)); c.update(editor.state.project, startUs, false) }
                Thread.sleep(1500)
                var under0 = 0
                val t0 = System.nanoTime()
                main.runOnMainSync { c.play() }
                var maxUnder = 0
                while (System.nanoTime() - t0 < (clipUs - startUs) * 1000 + 3_000_000_000L) {
                    Thread.sleep(100)
                    Aud1Bench.track(c)?.let { t -> maxUnder = maxOf(maxUnder, t.underrunCount) }
                    var playing = true; main.runOnMainSync { playing = c.state.value.playing }; if (!playing) break
                }
                main.runOnMainSync { if (c.state.value.playing) c.pause() }
                PreviewFrames.testPlayback = null
                val playAt = ev.firstOrNull { e -> e.second == "track-play" }?.first
                val misses = ev.filter { e -> e.second.startsWith("miss ") }
                val missFrames = misses.sumOf { e -> e.second.substringAfter("n=").toLong() }
                val bySec = misses.groupBy { e -> (e.second.substringAfter("frame=").substringBefore(' ').toLong() / 48_000).toInt() }.toSortedMap().map { (s, l) -> "$s s: ${l.size}" }
                aud("A3 ${it.label} $mode: first sound %s ms after Play; blocks played as silence (not decoded yet) ${misses.size} (%.0f ms) %s; track underruns $maxUnder"
                    .format(playAt?.let { p -> "%.0f".format((p - t0) / 1e6) } ?: "never", missFrames / 48.0, if (bySec.isEmpty()) "" else "by second [${bySec.joinToString("; ")}]"))
                Thread.sleep(500)
            }
        }
        checkP1()
    }
}
