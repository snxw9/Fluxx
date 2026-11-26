package com.fluxx.android.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.editor.*
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import com.fluxx.android.render.PreviewController
import com.fluxx.android.ui.editor.EditorScreen
import com.fluxx.android.ui.theme.FluxxTheme
import com.fluxx.android.ui.timeline.TimelineView
import com.fluxx.android.ui.timeline.TimelineZoomKey
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TL3 on the A16 (fixture compositions, never the user's projects; Compose test input only): the twirl-down (arrow, D
 * name column, keyed rows), the shared transition with the sheet, key gestures in twirl rows, the ONE value popover with
 * bulk edits, name taps, the colour bar's add-key and the audio row.
 */
class Tl3UiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var previousLibrary: CurveLibraryStore? = null
    @Before fun lib() { previousLibrary = CurveLibraryStore.replaceForTest(CurveLibraryStore.inMemory()) }
    @After fun restore() { CurveLibraryStore.replaceForTest(previousLibrary) }
    private lateinit var editor: EditorViewModel
    private val controller = mutableStateOf<PreviewController?>(null)
    private val rate = FrameRate(30, 1)
    private fun f(n: Long) = rate.frameTimeUs(n)

    private fun settle() { Thread.sleep(500); compose.waitForIdle() }
    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun count(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun state(tag: String) = node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
    private fun layer(id: Long) = editor.state.project.composition.layers.first { it.id == id }
    private fun pxPerUs() = compose.onNodeWithTag("timeline-ruler").fetchSemanticsNode().config[TimelineZoomKey]
    private fun rowTag(id: Long, p: AnimPropertyType) = "twirl-row-" + TwirlRow.Property(id, GraphTarget(p), "", "", TwirlValueKind.SCALAR).key
    private fun nameTag(id: Long, p: AnimPropertyType) = "twirl-name-" + TwirlRow.Property(id, GraphTarget(p), "", "", TwirlValueKind.SCALAR).key

    private fun keyed(id: Long, start: Long = 0, name: String = "L$id") = CompositionLayer(id, LayerType.SOLID, name = name, zOrder = (10 - id).toInt(),
        timing = ClipTiming(0, 0, f(240)), animTransform = AnimatableTransform(
            position = AnimatableProperty2D(true, keyframes = FrozenList.of(listOf(Keyframe2D(f(36 + start), 0f, 0f), Keyframe2D(f(48 + start), .2f, .1f), Keyframe2D(f(60), .3f, .3f)))),
            scale = AnimatableProperty2D(staticX = 1f, staticY = 1f),
            opacity = AnimatableProperty1D(true, keyframes = FrozenList.of(listOf(Keyframe1D(f(40), 1f), Keyframe1D(f(54), .5f))))))

    /** The real EditorScreen over [layers], the playhead at [playhead]. */
    private fun host(layers: List<CompositionLayer>, playhead: Long = f(45)) {
        editor = EditorViewModel()
        editor.dispatch(EditorAction.Load(ProjectDocument(Composition(durationUs = 10_000_000, frameRate = rate, layers = FrozenList.of(layers)))))
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
        compose.runOnIdle { editor.dispatch(EditorAction.Seek(playhead)); controller.value!!.seek(editor.state.playheadUs) }
        settle()
    }

    private fun arrow(id: Long) { node("d-arrow-$id").performClick(); settle() }
    /** Tap / hold a key of [row] at composition time [t] (twirl rows draw keys at t × px per µs). */
    private fun tapKey(row: String, t: Long) { val x = (t * pxPerUs()).toFloat(); node(row).performTouchInput { click(Offset(x, centerY)) }; settle() }
    private fun holdKey(row: String, t: Long) { val x = (t * pxPerUs()).toFloat(); node(row).performTouchInput { longClick(Offset(x, centerY)) }; settle() }

    // ---- B1 / B2 ----------------------------------------------------------------------------------------------------
    @Test fun theArrowExpandsOnlyKeyedRowsAndTheDGrowsWithNames() {
        host(listOf(keyed(1), CompositionLayer(5, LayerType.SOLID, zOrder = 0, timing = ClipTiming(0, 0, f(240)))))
        assertEquals("Collapsed", state("d-arrow-1"))
        val narrow = node("d-button-1").getBoundsInRoot()
        arrow(1)
        assertEquals("Expanded", state("d-arrow-1"))
        assertTrue(exists(rowTag(1, AnimPropertyType.POSITION)) && exists(rowTag(1, AnimPropertyType.OPACITY)))
        assertFalse("no keys: no row", exists(rowTag(1, AnimPropertyType.SCALE)))
        assertTrue(exists(nameTag(1, AnimPropertyType.POSITION)))
        val wide = node("d-button-1").getBoundsInRoot()
        val nw = narrow.right - narrow.left; val ww = wide.right - wide.left
        assertTrue("the D widens: $nw -> $ww", ww > nw + 30.dp)
        // The rows sit directly under the layer row.
        assertEquals(wide.bottom.value, node(rowTag(1, AnimPropertyType.POSITION)).getBoundsInRoot().top.value, 1.5f)
        assertFalse("expansion is not in undo history", editor.canUndo)
        arrow(1); assertEquals("Collapsed", state("d-arrow-1")); assertFalse(exists(rowTag(1, AnimPropertyType.POSITION)))
        // A layer with nothing keyed: one dim row.
        arrow(5); assertTrue(exists("twirl-row-tw|5|empty")); assertTrue(exists("twirl-name-tw|5|empty"))
    }

    @Test fun theArrowWithTheSheetOpenClosesItAndALayerTapCollapsesAndOpensIt() {
        host(listOf(keyed(1), keyed(2)))
        node("timeline-clip-hit-2").performTouchInput { click(Offset(200.dp.toPx(), centerY)) }; settle()
        assertTrue("sheet open", exists("inspector-name-row"))
        arrow(1)
        assertFalse("the sheet closed", exists("inspector-name-row"))
        assertEquals(setOf(1L), editor.expandedLayers); assertEquals("Expanded", state("d-arrow-1"))
        // Tapping a layer: every twirl-down collapses while the sheet opens.
        node("timeline-clip-hit-1").performTouchInput { click(Offset(200.dp.toPx(), centerY)) }; settle()
        assertTrue(editor.expandedLayers.isEmpty()); assertEquals("Collapsed", state("d-arrow-1"))
        assertTrue(exists("inspector-name-row"))
    }

    // ---- B3 ----------------------------------------------------------------------------------------------------------
    @Test fun keyGesturesInTwirlRowsOnePopoverAndKeyModeSurvivesTheTransition() {
        host(listOf(keyed(1)))
        arrow(1)
        val row = rowTag(1, AnimPropertyType.POSITION)
        // Outside key mode a tap opens the value popover at once; another tap replaces it (ONE popover).
        tapKey(row, f(36)); assertEquals(1, count("twirl-value-popover"))
        tapKey(row, f(48)); assertEquals(1, count("twirl-value-popover"))
        assertNull("a tap selects nothing", editor.keySelection)
        assertEquals("no seek", f(45), editor.state.playheadUs)
        // A hold released without moving selects and enters key mode; a tap then toggles at once.
        holdKey(row, f(36)); assertEquals(listOf(f(36)), editor.keySelection!!.times)
        tapKey(row, f(48)); assertEquals(setOf(f(36), f(48)), editor.keySelection!!.times.toSet())
        tapKey(row, f(48)); assertEquals(listOf(f(36)), editor.keySelection!!.times)
        // No sheet: the key bar is a bottom overlay.
        assertTrue(exists("key-bottom-bar")); assertFalse(exists("key-header-bar"))
        val timeline = node("timeline-ruler").getBoundsInRoot()
        // Tapping the layer opens the inspector: key mode stays on, the bar moves to the landing page's name row.
        node("timeline-clip-hit-1").performTouchInput { click(Offset(200.dp.toPx(), centerY)) }; settle()
        assertNotNull("key mode survives", editor.keySelection); assertEquals(listOf(f(36)), editor.keySelection!!.times)
        assertTrue(exists("key-header-bar")); assertFalse(exists("key-bottom-bar"))
        assertEquals("no layout shift", timeline, node("timeline-ruler").getBoundsInRoot())
    }

    @Test fun bulkValueEditsChangeTheSamePropertyOnAllSelectedLayersOnly() {
        host(listOf(keyed(1), keyed(2)))
        arrow(1); arrow(2)
        val r1 = rowTag(1, AnimPropertyType.POSITION); val r2 = rowTag(2, AnimPropertyType.POSITION)
        holdKey(r1, f(48)); tapKey(r2, f(48)); tapKey(rowTag(1, AnimPropertyType.OPACITY), f(40))
        assertEquals(3, editor.keySelection!!.size)
        val scaleBefore = layer(1).animTransform.scale
        // In key mode a hold release on a selected key opens its popover: 2 Position keys on 2 layers.
        holdKey(r1, f(48))
        compose.onNodeWithTag("twirl-value-count", useUnmergedTree = true).assertTextEquals("Changes 2 keyframes on 2 layers")
        node("twirl-value-0").performTextReplacement("100"); node("twirl-value-0").performImeAction(); settle()
        val x1 = layer(1).animTransform.position.keyframes.first { it.timeUs == f(48) }.x
        val x2 = layer(2).animTransform.position.keyframes.first { it.timeUs == f(48) }.x
        assertEquals(x1, x2); assertNotEquals(.2f, x1)
        assertEquals("Y untouched", .1f, layer(1).animTransform.position.keyframes.first { it.timeUs == f(48) }.y)
        assertEquals("Opacity is never changed by a Position edit", 1f, layer(1).animTransform.opacity.keyframes.first().value)
        assertEquals(scaleBefore, layer(1).animTransform.scale)
        assertEquals("no seek", f(45), editor.state.playheadUs)
        editor.undo(); settle(); assertEquals(.2f, layer(1).animTransform.position.keyframes.first { it.timeUs == f(48) }.x)
    }

    @Test fun aNameTapSelectsEveryKeyOfThePropertyAndAgainDeselects() {
        host(listOf(keyed(1)))
        arrow(1)
        node(nameTag(1, AnimPropertyType.POSITION)).performClick(); settle()
        assertEquals(setOf(f(36), f(48), f(60)), editor.keySelection!!.times.toSet())
        node(nameTag(1, AnimPropertyType.POSITION)).performClick(); settle()
        assertNull(editor.keySelection)
    }

    // ---- B4 ----------------------------------------------------------------------------------------------------------
    @Test fun theColourBarShowsAndAddingAKeyKeepsItsLook() {
        val text = CompositionLayer(3, LayerType.TEXT, timing = ClipTiming(0, 0, f(240)), text = TextProperties(source = AnimatableString(staticValue = "Colour"),
            fill = AnimatableColour(true, keyframes = FrozenList.of(listOf(ColourKeyframe(f(30), 0xFFFF0000.toInt()), ColourKeyframe(f(60), 0xFF0000FF.toInt()))))))
        host(listOf(text))
        arrow(3)
        val row = "twirl-row-" + TwirlRow.Property(3, GraphTarget(AnimPropertyType.FILL_COLOUR), "", "", TwirlValueKind.COLOUR).key
        assertTrue(exists(row))
        val before = layer(3).text.fill
        tapKey(row, f(42)) // the bar between the keys, not a diamond (12 frames from either)
        assertTrue(exists("twirl-bar-popover")); assertTrue(exists("twirl-bar-hex"))
        node("twirl-bar-add").performClick(); settle()
        val after = layer(3).text.fill
        assertEquals(3, after.keyframes.size)
        var worst = 0; var t = f(30)
        while (t <= f(60)) { val a = before.evaluate(t); val b = after.evaluate(t)
            for (s in intArrayOf(24, 16, 8, 0)) worst = maxOf(worst, kotlin.math.abs(((a ushr s) and 255) - ((b ushr s) and 255))); t += 1000 }
        assertTrue("look kept: $worst / 255", worst <= 1)
        assertEquals("no seek", f(45), editor.state.playheadUs)
    }

    // ---- B5 ----------------------------------------------------------------------------------------------------------
    /** A generated 1 s, 48 kHz mono 16-bit WAV (a fixture in the app's cache directory, never the user's media). */
    private fun wav(): File {
        val f = File(context.cacheDir, "tl3-fixture.wav")
        val n = 48_000; val data = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(48_000).putInt(96_000).putShort(2).putShort(16).put("data".toByteArray()).putInt(n * 2)
        for (i in 0 until n) data.putShort((kotlin.math.sin(i * 2 * Math.PI * 220 / 48_000) * 16_000).toInt().toShort())
        f.writeBytes(data.array()); return f
    }

    @Test fun aLayerWithAudioGetsTheAudioRowFirstAndItsPeaks() {
        val uri = android.net.Uri.fromFile(wav()).toString()
        val video = CompositionLayer(4, LayerType.VIDEO, name = "Audio fixture", timing = ClipTiming(0, 0, 1_000_000),
            asset = AssetReference("tl3-fixture", uri, durationUs = 1_000_000))
        val project = ProjectDocument(Composition(durationUs = 2_000_000, frameRate = rate, layers = FrozenList.of(listOf(video))))
        // The timeline alone (no preview decoder for the fixture).
        compose.setContent { FluxxTheme { TimelineView(project, MediaRepository(context), 500_000, null, {}, { _, _ -> }, { _, _ -> },
            {}, {}, {}, {}, {}, expandedLayers = setOf(4L)) } }
        settle()
        assertTrue(exists("twirl-row-tw|4|audio"))
        assertTrue(exists("twirl-name-tw|4|audio"))
        compose.waitUntil(15_000) { node("twirl-row-tw|4|audio").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() == "Audio waveform" }
        val ready = com.fluxx.android.media.AudioPeakProvider.get(context).states[uri] as com.fluxx.android.media.AudioPeakProvider.Peaks.Ready
        assertEquals(.49f, ready.pyramid.levels.last().second[0], .02f) // 16000 / 32768
    }
}
