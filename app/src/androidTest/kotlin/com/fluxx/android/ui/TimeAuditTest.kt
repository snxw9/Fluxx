package com.fluxx.android.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.editor.*
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import com.fluxx.android.render.PreviewController
import com.fluxx.android.ui.editor.EditorScreen
import com.fluxx.android.ui.inspector.GraphTime
import com.fluxx.android.ui.theme.FluxxTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * GRAPH G2.1 §6a: the time display audit on the A16 (a fixture composition, never the user's projects). For playheads
 * parked on chosen frames it records (a) the transport timecode, (b)/(c) the timeline's ruler ticks and the playhead's x
 * against the tick of that frame, and (d) the Graph page's time formatter, and logs them as a table (tag GraphG21).
 */
class TimeAuditTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var previousLibrary: CurveLibraryStore? = null
    @Before fun lib() { previousLibrary = CurveLibraryStore.replaceForTest(CurveLibraryStore.inMemory()) }
    @After fun restore() { CurveLibraryStore.replaceForTest(previousLibrary) }

    private fun audit(rate: FrameRate, start: Long) {
        val editor = EditorViewModel()
        editor.dispatch(EditorAction.Load(ProjectDocument(Composition(durationUs = 4_000_000, frameRate = rate, startTimecodeUs = start,
            layers = FrozenList.of(listOf(CompositionLayer(1, LayerType.SOLID, name = "Audit", timing = ClipTiming(0, 0, 4_000_000))))))))
        val controller = mutableStateOf<PreviewController?>(null)
        compose.setContent {
            FluxxTheme {
                EditorScreen(editor, MediaRepository(context), controller.value, null, true, false,
                    onSurfaceAvailable = { s, w, h ->
                        if (controller.value == null) controller.value = PreviewController(context, s, w, h, onPosition = { editor.reportPosition(it) })
                        controller.value!!.update(editor.displayedState.project, editor.displayedState.playheadUs, false)
                    },
                    onSurfaceDestroyed = {}, onSurfaceResized = { _, _ -> }, onRequestMediaPermission = {}, onOpenDocumentPicker = {},
                    onSaveProject = {}, onLoadProject = {}, onExportStart = {}, onExportCancel = {}, onOpenExportedVideo = {})
            }
        }
        compose.waitUntil(10_000) { controller.value != null }
        val density = compose.density.density
        val pxPerUs = 120.0 * density / 1e6 // TimelineView's default zoom (120 dp/s)
        val log = StringBuilder("\nrate ${CompositionTimecode.frameRateLabel(rate)} start ${start}us\n")
        log.append("frame | playhead us | (a) transport | pill | (d) Graph label | old Graph tile (s) | old axis | tick x - playhead x (px)\n")
        for (f in listOf(0L, 1L, 15L, 29L, 30L, 45L, 89L)) {
            val t = rate.frameTimeUs(f)
            compose.runOnIdle { editor.dispatch(EditorAction.Seek(t)); controller.value!!.seek(t) }
            Thread.sleep(500); compose.waitForIdle()
            val expected = CompositionTimecode.format(start + t, rate)
            // Every timecode on screen (transport, its duration, the playhead pill), unmerged.
            val codes = compose.onAllNodes(SemanticsMatcher("timecode") { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { Regex("""\d{2}:\d{2}:\d{2}""").containsMatchIn(it.text) } == true }, useUnmergedTree = true)
                .fetchSemanticsNodes().flatMap { n -> n.config[SemanticsProperties.Text].map { it.text } }
            val shown = codes.count { it == expected }
            // The scroll container (ScrollAxisRange ≈ duration × pxPerUs) and the fixed playhead.
            val scroller = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)).fetchSemanticsNodes()
                .minByOrNull { kotlin.math.abs((it.config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() - 4_000_000 * pxPerUs)).toFloat() }!!
            val scroll = scroller.config[SemanticsProperties.HorizontalScrollAxisRange].value()
            val viewport = scroller.boundsInRoot
            val playheadX = compose.onNodeWithContentDescription("Playhead marker").fetchSemanticsNode().boundsInRoot.center.x
            val spacer = Math.round(viewport.width / 2f) // the leading spacer (half the viewport, laid out in whole px)
            val tickX = viewport.left + spacer + (t * pxPerUs).toFloat() - scroll
            val old = String.format(java.util.Locale.ROOT, "%.2f", (t) / 1e6)
            val oldAxis = "${Math.round(t / (1e6 * rate.denominator / rate.numerator))}f"
            log.append("$f | $t | ${codes.joinToString(" ")} (x$shown) | $expected | ${GraphTime.label(t, rate, start)} | $old | $oldAxis | ${"%.2f".format(tickX - playheadX)}\n")
            assertTrue("transport and pill show $expected: $codes", shown >= 1)
            assertEquals(expected, GraphTime.label(t, rate, start))
            assertTrue("playhead within 1.5px of its frame tick: ${tickX - playheadX}", kotlin.math.abs(tickX - playheadX) <= 1.5f)
            assertEquals(t, editor.state.playheadUs)
        }
        // A playhead between frames (a scrub stops anywhere): the timecode shows the NEAREST frame.
        val mid = rate.frameTimeUs(15) + (rate.frameTimeUs(16) - rate.frameTimeUs(15)) * 3 / 4
        compose.runOnIdle { editor.dispatch(EditorAction.Seek(mid)); controller.value!!.seek(mid) }
        Thread.sleep(500); compose.waitForIdle()
        log.append("between frames 15 and 16 (3/4): playhead ${mid}us shows ${CompositionTimecode.format(start + mid, rate)} (nearest frame 16)\n")
        android.util.Log.d("GraphG21", log.toString())
    }

    @Test fun thirtyFps() = audit(FrameRate(30, 1), 0)
    @Test fun ntscWithAStartTimecode() = audit(FrameRate(30000, 1001), 3_600_000_000L)
}
