package com.fluxx.android.ui

import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.editor.EditorAction
import com.fluxx.android.editor.LayerSelection
import com.fluxx.android.editor.MultiLayerEdits
import com.fluxx.android.engine.VideoDecoder
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * MP1.1 A1: Snxw's crashing imports, reproduced through the REAL add path with the CONTENT URIs the media browser returns
 * (MediaStore rows found by display name; files read in place). Each scenario starts from an empty in-memory document:
 *  1. the 50-minute HEVC file alone;  2. 30 clips in one call (30 x the typical clip; 10 of each of the three files);
 *  3. 30 clips in three calls of 10;  4. scenario 2 followed by the layer bar's Sequence and Stagger on all 30.
 * Then 60 s of observation (background jobs), 15 s of playback from the start, 15 s with the first layer's twirl-down open
 * (the waveform row requests peaks). Records: time to return, UI frames, memory (dumpsys meminfo) before / peak / after,
 * threads, open fds, decoders opened / closed, background media jobs in flight, composition duration and the timeline
 * content width at the default zoom. A crash ends the instrumentation: its stack is in the crash buffer.
 * Lines marked MP1-ONLY use MP1 APIs (removed for the AUD2-tip bisect build).
 */
class Mp11ImportStressTest : Aud2Base() {
    private fun say(line: String) {
        android.util.Log.i("MP11", line)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("MP11", line) })
    }

    data class Item(val uri: Uri, val name: String, val bytes: Long)

    /** A MediaStore video row whose DISPLAY_NAME matches [like] (SQL LIKE), as the picker returns it (content URI). */
    private fun content(like: String): Item? {
        val cr = context.contentResolver
        for (collection in listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Files.getContentUri("external"))) {
            cr.query(collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf(like), null)?.use { c ->
                if (c.moveToFirst()) return Item(android.content.ContentUris.withAppendedId(collection, c.getLong(0)), c.getString(1), c.getLong(2))
            }
        }
        return null
    }
    private val typical by lazy { content("%" + Aud2Bench.TYPICAL_ID + "%") }
    private val vp9 by lazy { content(Aud1Bench.PREFIX + "%") }
    private val long by lazy { content("Project 3%") }
    private fun need(i: Item?, what: String): Item { assumeTrue("MP11: $what not found in MediaStore; skipping", i != null); return i!! }

    private val pkg get() = context.packageName
    private val density get() = context.resources.displayMetrics.density
    private val screenPx get() = context.resources.displayMetrics.widthPixels
    /** The timeline's horizontal content at the default zoom (120 dp/s) plus the two half-viewport spacers. */
    private fun contentWidthPx(durationUs: Long) = durationUs * 120.0 * density / 1e6 + screenPx

    private val jobEvents = CopyOnWriteArrayList<Pair<Long, String>>()

    /** The media browser's Add: the same per-item flow (addMedia, then the preview update), in selection order, on Main. */
    private fun addAll(items: List<Item>): Long {
        val t0 = System.nanoTime(); var error: Throwable? = null
        val done = java.util.concurrent.CountDownLatch(1)
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val repo = MediaRepository(context)
                for (e in items) {
                    val at = editor.state.playheadUs
                    editor.addMedia(repo, e.uri, e.name, at)
                    c.update(editor.state.project, at, false)
                }
            } catch (t: Throwable) { error = t } finally { done.countDown() }
        }
        check(done.await(120, java.util.concurrent.TimeUnit.SECONDS)) { "add did not return within 120 s" }
        error?.let { say("add threw ${it.javaClass.simpleName}: ${it.message}") }
        return (System.nanoTime() - t0) / 1_000_000
    }

    private fun counts(): String {
        val threads = File("/proc/self/task").list()?.size ?: -1
        val fds = File("/proc/self/fd").list()?.size ?: -1
        val ev0 = rec.decoder.map { it.second }; val opens = ev0.count { it.startsWith("open ") }; val closes = ev0.count { it.startsWith("close") }
        // MP1-ONLY: background media jobs in flight (started minus finished).
        val ev = jobEvents.map { it.second }
        val thumbs = ev.count { it.startsWith("thumb-start") } - ev.count { it.startsWith("thumb-done") || it.startsWith("thumb-failed") }
        val peaks = ev.count { it.startsWith("peaks-start") } - ev.count { it.startsWith("peaks-done") }
        val pcm = ev.count { it.startsWith("pcm-run ") } - ev.count { it.startsWith("pcm-run-end") }
        return "threads $threads, fds $fds, decoders opened $opens closed $closes; jobs in flight: thumbnails $thumbs, peaks $peaks, PCM runs $pcm"
    }
    private fun mem(): Map<String, Long> = Aud2Bench.meminfo(pkg)
    private fun mb(m: Map<String, Long>, k: String) = (m[k] ?: 0) / 1024.0

    private fun scenario(label: String, batches: List<List<Item>>, after: (List<Long>) -> Unit = {}) {
        rec.start()   // preview frames, decoder events (kept for the whole scenario), clock
        com.fluxx.android.render.MediaProbes.events = { e -> jobEvents += System.nanoTime() to e }   // MP1-ONLY
        try {
            host(ProjectDocument())
            val before = mem()
            say("$label: before: ${Aud2Bench.mem(before)}; ${counts()}")
            val total = batches.sumOf { it.size }
            var addMs = 0L
            val ui = uiFrames(compose.activity.window) {
                for ((k, b) in batches.withIndex()) {
                    val ms = addAll(b); addMs += ms
                    val d = compose.runOnIdle { editor.state.project.composition.resolvedDurationUs }
                    say("$label: batch ${k + 1}/${batches.size} (${b.size} clips) returned in $ms ms; composition %.1f s; timeline content %.0f px at the default zoom (Compose limit 262143)".format(d / 1e6, contentWidthPx(d)))
                    Thread.sleep(1000)
                }
            }
            val ids = compose.runOnIdle { editor.state.project.composition.layers.sortedBy { it.id }.map { it.id } }
            say("$label: $total clips added in $addMs ms (layers now ${ids.size}); UI frames during the add ${Aud2Bench.stats(ui)} ms, over 100 ms ${ui.count { it > 100 }}; undo depth ${undoDepth()}")
            after(ids)
            // 60 s of observation while background work runs; memory sampled every 10 s.
            var peak = mem()
            val obs = uiFrames(compose.activity.window) {
                repeat(6) { Thread.sleep(10_000); val m = mem(); if ((m["TOTAL PSS"] ?: 0) > (peak["TOTAL PSS"] ?: 0)) peak = m
                    say("$label: +${(it + 1) * 10} s: PSS %.0f MB (Java %.0f, native %.0f, graphics %.0f); ${counts()}".format(mb(m, "TOTAL PSS"), mb(m, "Java Heap"), mb(m, "Native Heap"), mb(m, "Graphics"))) }
            }
            say("$label: observation UI frames ${Aud2Bench.stats(obs)} ms, over 100 ms ${obs.count { it > 100 }}")
            // Play from the start for 15 s.
            seekTo(0, 1500)
            val p0 = System.nanoTime()
            toggle(); Thread.sleep(15_000); if (playing) toggle()
            val frames = rec.frames.toList().filter { it[2] >= p0 }
            say("$label: play from 0: first frame %.0f ms, first sound %.0f ms, %d frames in 15 s, status '%s'".format(
                frames.firstOrNull()?.let { (it[2] - p0) / 1e6 } ?: Double.NaN, rec.firstSoundNs()?.let { (it - p0) / 1e6 } ?: Double.NaN, frames.size, c.state.value.message ?: ""))
            // The first layer's twirl-down open: the waveform row requests peaks.
            ids.firstOrNull()?.let { id -> compose.runOnIdle { editor.setExpanded(setOf(id)) } }
            Thread.sleep(15_000)
            val endMem = mem(); if ((endMem["TOTAL PSS"] ?: 0) > (peak["TOTAL PSS"] ?: 0)) peak = endMem
            say("$label: after (twirl open 15 s): ${Aud2Bench.mem(endMem)}; ${counts()}")
            say("$label: PSS before %.0f / peak %.0f / after %.0f MB; graphics before %.0f / peak %.0f / after %.0f MB".format(
                mb(before, "TOTAL PSS"), mb(peak, "TOTAL PSS"), mb(endMem, "TOTAL PSS"), mb(before, "Graphics"), mb(peak, "Graphics"), mb(endMem, "Graphics")))
            say("$label: RESULT no crash")
        } finally {
            rec.stop()
            com.fluxx.android.render.MediaProbes.events = null   // MP1-ONLY
        }
    }

    private fun undoDepth(): Int = runCatching {
        val f = editor.javaClass.declaredFields.firstOrNull { it.name == "undoStack" } ?: return -1
        f.isAccessible = true; (f.get(editor) as? Collection<*>)?.size ?: -1
    }.getOrDefault(-1)

    @Test fun s1LongSourceAlone() { val l = need(long, "Project 3"); say("s1: ${l.name.length}-char name, %.2f GB, ${l.uri}".format(l.bytes / 1e9)); scenario("s1 50-minute file", listOf(listOf(l))) }

    @Test fun s2Bulk30Typical() { val t = need(typical, "the typical clip"); scenario("s2a 30 x typical, one call", listOf(List(30) { t })) }

    @Test fun s2Bulk30Mixed() {
        val t = need(typical, "the typical clip"); val v = need(vp9, "the VP9 file"); val l = need(long, "Project 3")
        scenario("s2b 10 of each file, one call", listOf(List(10) { t } + List(10) { v } + List(10) { l }))
    }

    @Test fun s3Batches10() { val t = need(typical, "the typical clip"); scenario("s3 30 x typical, 3 calls of 10", List(3) { List(10) { t } }) }

    @Test fun s4SequenceStagger() {
        val t = need(typical, "the typical clip")
        scenario("s4 30 x typical + Sequence + Stagger", listOf(List(30) { t })) { ids ->
            val sel = LayerSelection(ids)
            for ((name, make) in listOf<Pair<String, (ProjectDocument) -> EditorAction?>>(
                "Sequence" to { p -> MultiLayerEdits.sequence(p, sel, 0, MultiLayerEdits.SEQUENCE_REVERSE_DEFAULT) },
                "Stagger" to { p -> MultiLayerEdits.stagger(p, sel, 2, MultiLayerEdits.STAGGER_REVERSE_DEFAULT) })) {
                val t0 = System.nanoTime()
                // As the layer bar applies it (applyLayerAction): pause, dispatch, preview update.
                compose.runOnIdle { c.pause(); make(editor.state.project)?.let { editor.dispatch(it) }; c.update(editor.state.project, editor.state.playheadUs, false) }
                val d = compose.runOnIdle { editor.state.project.composition.resolvedDurationUs }
                say("s4: $name applied in %.0f ms; composition %.1f s; timeline content %.0f px at the default zoom".format((System.nanoTime() - t0) / 1e6, d / 1e6, contentWidthPx(d)))
                Thread.sleep(3000)
            }
        }
    }
}
