package com.fluxx.android.ui

import com.fluxx.android.editor.EditorAction
import com.fluxx.android.editor.EditorViewModel
import com.fluxx.android.editor.LayerSelection
import com.fluxx.android.editor.MultiLayerEdits
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * MP1.2 A1 + A3: the inventory of Download/Seal (MediaStore rows, read in place), then sequences of DIFFERENT files built
 * in memory through the real add path (content URIs, the browser's bulk add, then each clip trimmed to [CLIP_US] and the
 * layer bar's Sequence), played start to end with the memory trace of [Mp12Base]. Representative combinations only:
 *  m1 4:3 @ 30 fps, mixed sources, 20 fps ones first (the reported case, and 20 fps clips in a 30 fps composition);
 *  m2 16:9 @ 30, mixed;  m3 9:16 @ 30, mixed;  m4 1:1 @ 30 with 60 fps sources in the mix;  m5 16:9 @ 24 with 60 fps sources.
 * A missing kind of source shrinks the mix (reported); fewer than [MIN_FILES] usable files skips the combination.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class Mp12MixedSequenceTest : Mp12Base() {
    companion object {
        const val CLIP_US = 4_000_000L
        const val CLIPS = 15
        const val MIN_FILES = 10
        @Volatile private var inventory: List<Mp12Bench.Source>? = null
        private val used = HashSet<String>()
    }

    private fun sources(): List<Mp12Bench.Source> = inventory ?: Mp12Bench.sealVideos(context).also { inventory = it }

    @Test fun a1Inventory() {
        val t0 = System.nanoTime()
        val all = sources()
        say("A1 inventory: ${all.size} video files in Download/Seal (MediaStore, ${(System.nanoTime() - t0) / 1_000_000} ms)")
        say("A1 columns: name | container | codec | WxH | fps | duration | size | rotation | audio")
        for (s in all) Mp12Bench.say("MP12INV", s.row())
        val groups = all.groupBy { "${it.codec} ${it.width}x${it.height} %.0f fps".format(it.fps) }.entries.sortedByDescending { it.value.size }
        say("A1 groups (codec size fps: files): " + groups.joinToString("; ") { "${it.key}: ${it.value.size}" })
    }

    /** Up to [CLIPS] usable files (>= CLIP_US long, decodable size), varied: one per (codec, size, rate) group in turn, [first] preferred. */
    private fun pick(label: String, first: (Mp12Bench.Source) -> Boolean = { false }, need: (Mp12Bench.Source) -> Boolean = { true }, needCount: Int = 0): List<Mp12Bench.Source> {
        val usable = sources().filter { it.durationUs >= CLIP_US && it.width in 64..4096 && it.height in 64..4096 && it.fps > 1 }
        val fresh = usable.filter { it.uri.toString() !in used }.ifEmpty { usable }
        val out = ArrayList<Mp12Bench.Source>()
        // The required kind first (e.g. 60 fps sources), then the preferred, then round-robin over the groups.
        out += fresh.filter(need).filter { needCount > 0 }.distinctBy { "${it.codec}${it.width}${it.height}" }.take(needCount)
        val groups = fresh.filter { it !in out }.sortedByDescending { first(it) }.groupBy { "${it.codec} ${it.width}x${it.height} ${Math.round(it.fps)}" }.values.map { ArrayDeque(it) }
        var k = 0
        while (out.size < CLIPS && groups.any { it.isNotEmpty() }) { groups[k % groups.size].removeFirstOrNull()?.let { if (it !in out) out += it }; k++ }
        out.forEach { used += it.uri.toString() }
        say("$label: picked ${out.size} files: " + out.joinToString("; ") { "${it.codec} ${it.width}x${it.height} %.0f fps".format(it.fps) })
        return out
    }

    private fun combo(label: String, w: Int, h: Int, fps: Int, picks: List<Mp12Bench.Source>) {
        assumeTrue("MP12 $label: only ${picks.size} usable files (need $MIN_FILES); skipping", picks.size >= MIN_FILES)
        val st = TestDeviceState.read()
        assumeTrue("MP12 $label: battery at ${st.temperatureC} C (> 42); skipping", st.temperatureC <= 42.0)
        say("$label: state $st")
        val base = Aud2Bench.project(w, h, 10_000_000, fps, emptyList()).let { p -> p.copy(composition = p.composition.copy(durationUs = null)) }
        host(base)
        // The real add: one bulk add of content URIs (as the browser's multi-select Add), then trim, then the layer bar's Sequence.
        val done = java.util.concurrent.CountDownLatch(1); var error: Throwable? = null
        CoroutineScope(Dispatchers.Main).launch {
            try { editor.addMediaBatch(MediaRepository(context), picks.map { it.uri to it.name }, 0, EditorViewModel.BulkPlacement.AT_PLAYHEAD) }
            catch (t: Throwable) { error = t } finally { done.countDown() }
        }
        check(done.await(120, java.util.concurrent.TimeUnit.SECONDS)) { "add did not return" }
        error?.let { throw it }
        val project = compose.runOnIdle {
            val ids = editor.state.project.composition.layers.filter { it.type == LayerType.VIDEO }.map { it.id }
            editor.dispatch(EditorAction.Batch(ids.map { id -> EditorAction.SetTiming(id, ClipTiming(0, 0, CLIP_US)) }))
            MultiLayerEdits.sequence(editor.state.project, LayerSelection(ids), 0, MultiLayerEdits.SEQUENCE_REVERSE_DEFAULT)?.let { editor.dispatch(it) }
            c.update(editor.state.project, 0, false)
            editor.state.project
        }
        Thread.sleep(3000)
        playThrough(label, project, project.composition.resolvedDurationUs / 1000 * 2 + 60_000)
    }

    @Test fun m1FourByThreeMixed() = combo("m1 4:3 @30 mixed (20 fps first)", 1440, 1080, 30, pick("m1", first = { Math.round(it.fps) == 20L }))
    @Test fun m2SixteenByNineMixed() = combo("m2 16:9 @30 mixed", 1920, 1080, 30, pick("m2"))
    @Test fun m3NineBySixteenMixed() = combo("m3 9:16 @30 mixed", 1080, 1920, 30, pick("m3", first = { it.ratio < 1 }))
    @Test fun m4SquareWith60() = combo("m4 1:1 @30 with 60 fps", 1080, 1080, 30, pick("m4", need = { it.fps > 50 }, needCount = 3))
    @Test fun m5TwentyFourWith60() = combo("m5 16:9 @24 with 60 fps", 1920, 1080, 24, pick("m5", need = { it.fps > 50 }, needCount = 5))
}
