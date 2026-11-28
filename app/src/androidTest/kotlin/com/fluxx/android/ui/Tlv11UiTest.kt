package com.fluxx.android.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
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
import com.fluxx.android.ui.timeline.TimelineZoomKey
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * TLV1.1 A1 on the A16 (fixture compositions only; Compose test input): a key drag that AUTO-SCROLLED the timeline seeks the
 * playhead to the key's new time on release (twirl row and layer bar), one undo step; a drag that never reached the edge
 * leaves the playhead where it was.
 */
class Tlv11UiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var previousLibrary: CurveLibraryStore? = null
    @Before fun lib() { previousLibrary = CurveLibraryStore.replaceForTest(CurveLibraryStore.inMemory()) }
    @After fun restore() { CurveLibraryStore.replaceForTest(previousLibrary) }
    private lateinit var editor: EditorViewModel
    private val controller = mutableStateOf<PreviewController?>(null)
    private val rate = FrameRate(30, 1)
    private fun f(n: Long) = rate.frameTimeUs(n)
    private val playhead0 get() = f(45)

    private fun settle() { Thread.sleep(500); compose.waitForIdle() }
    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun layer(id: Long) = editor.state.project.composition.layers.first { it.id == id }
    private fun pxPerUs() = compose.onNodeWithTag("timeline-ruler").fetchSemanticsNode().config[TimelineZoomKey]
    private fun scrollNode() = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)).fetchSemanticsNodes().first()
    /** The time under the playhead as the scroll stands (us). */
    private fun centreUs() = scrollNode().config[SemanticsProperties.HorizontalScrollAxisRange].value() / pxPerUs()
    /** A time's x inside [row]'s visible bounds (as Tl31UiTest: from the scroll semantics). */
    private fun xIn(row: String, t: Long): Float {
        val s = scrollNode()
        val scroll = s.config[SemanticsProperties.HorizontalScrollAxisRange].value()
        val rootX = s.boundsInRoot.left + Math.round(s.boundsInRoot.width / 2f) + (t * pxPerUs() - scroll).toFloat()
        return rootX - node(row).fetchSemanticsNode().boundsInRoot.left
    }
    private val rowTag = "twirl-row-" + TwirlRow.Property(1, GraphTarget(AnimPropertyType.POSITION), "", "", TwirlValueKind.SCALAR).key
    private fun posTimes() = layer(1).animTransform.position.keyframes.map { it.timeUs }

    /** Layer 1 (0..8 s) with position keys at 36, 48 and 60; a 20 s composition so the view can scroll well right. */
    private fun host() {
        editor = EditorViewModel()
        val keyed = CompositionLayer(1, LayerType.SOLID, name = "L1", zOrder = 3, timing = ClipTiming(0, 0, f(240)),
            animTransform = AnimatableTransform(position = AnimatableProperty2D(true, keyframes = FrozenList.of(listOf(
                Keyframe2D(f(36), 0f, 0f), Keyframe2D(f(48), .2f, .1f), Keyframe2D(f(60), .3f, .3f))))))
        val other = CompositionLayer(2, LayerType.SOLID, name = "L2", zOrder = 2, timing = ClipTiming(0, 0, 20_000_000))
        editor.dispatch(EditorAction.Load(ProjectDocument(Composition(durationUs = 20_000_000, frameRate = rate, layers = FrozenList.of(listOf(keyed, other))))))
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
        compose.runOnIdle { editor.dispatch(EditorAction.Seek(playhead0)); controller.value!!.seek(editor.state.playheadUs) }
        settle()
    }
    private fun expand() { compose.runOnIdle { editor.setExpanded(setOf(1L)) }; settle() }
    private fun openLayer1Sheet() {
        node("timeline-clip-hit-1").performTouchInput { click(Offset(200.dp.toPx(), centerY)) }; settle()
        compose.onNodeWithContentDescription("Transform").performClick(); settle()
        compose.waitUntil(5_000) { exists("timeline-key-1-${f(48)}") }
    }

    /** The playhead equals [key], the view settled on it (within half a px), and ONE undo restores [before]. */
    private fun assertLandedAndOneUndo(key: Long, before: ProjectDocument) {
        assertEquals("the playhead seeks to the key's new time", key, editor.state.playheadUs)
        assertEquals("the view settles on the playhead (not eased back)", key.toDouble(), centreUs(), 0.5 / pxPerUs())
        compose.runOnIdle { editor.undo() }; settle()
        assertEquals("one undo step restores the keys", before.composition.layers, editor.state.project.composition.layers)
        assertFalse("the seek is not in undo history", editor.canUndo)
    }

    @Test fun twirlRowKeyDraggedIntoTheEdgeSeeksToItsNewTime() {
        host(); expand()
        val before = editor.state.project
        val root = compose.onRoot().getBoundsInRoot()
        val rowLeft = node(rowTag).getBoundsInRoot().left
        val start = xIn(rowTag, f(48))
        val target = (root.right - rowLeft - 10.dp).value
        node(rowTag).performTouchInput {
            down(Offset(start, centerY)); advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(Offset(target.dp.toPx(), centerY))
        }
        Thread.sleep(1500)
        node(rowTag).performTouchInput { up() }
        settle(); Thread.sleep(500); compose.waitForIdle()
        val moved = posTimes().first { it != f(36) && it != f(60) }
        val fingerOnly = f(48) + ((target * context.resources.displayMetrics.density - start) / pxPerUs()).toLong()
        assertTrue("the content scrolled under the still finger: key at $moved > finger-only $fingerOnly", moved > fingerOnly + f(10))
        assertLandedAndOneUndo(moved, before)
    }

    @Test fun twirlRowKeyDragThatNeverReachesTheEdgeDoesNotSeek() {
        host(); expand()
        val before = editor.state.project
        val start = xIn(rowTag, f(48))
        node(rowTag).performTouchInput {
            down(Offset(start, centerY)); advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset(40.dp.toPx(), 0f)); up()
        }
        settle()
        val moved = posTimes().first { it != f(36) && it != f(60) }
        assertTrue("the key moved: $moved", moved > f(48))
        assertEquals("no seek", playhead0, editor.state.playheadUs)
        assertEquals("eased back to the playhead", playhead0.toDouble(), centreUs(), 0.5 / pxPerUs())
        compose.runOnIdle { editor.undo() }; settle()
        assertEquals(before.composition.layers, editor.state.project.composition.layers)
    }

    @Test fun layerBarKeyDraggedIntoTheEdgeSeeksToItsNewTime() {
        host(); openLayer1Sheet()
        val before = editor.state.project
        val keyNode = node("timeline-key-1-${f(48)}")
        val root = compose.onRoot().getBoundsInRoot()
        val keyLeft = keyNode.getBoundsInRoot().left
        // The finger goes down at the key's centre (half its hit width) and moves to [target] in the key node's coordinates.
        val keyHalf = (keyNode.getBoundsInRoot().let { it.right - it.left } / 2f).value
        val target = (root.right - keyLeft - 10.dp).value
        keyNode.performTouchInput {
            down(center); advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(Offset(target.dp.toPx(), centerY))
        }
        Thread.sleep(1500)
        compose.onRoot().performTouchInput { up() }
        settle(); Thread.sleep(500); compose.waitForIdle()
        val moved = posTimes().first { it != f(36) && it != f(60) }
        val fingerOnly = f(48) + ((target - keyHalf) * context.resources.displayMetrics.density / pxPerUs()).toLong()
        assertTrue("the content scrolled under the still finger: key at $moved > finger-only $fingerOnly", moved > fingerOnly + f(10))
        assertLandedAndOneUndo(moved, before)
    }

    @Test fun layerBarKeyDragThatNeverReachesTheEdgeDoesNotSeek() {
        host(); openLayer1Sheet()
        node("timeline-key-1-${f(48)}").performTouchInput {
            down(center); advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset(30.dp.toPx(), 0f)); up()
        }
        settle()
        val moved = posTimes().first { it != f(36) && it != f(60) }
        assertTrue("the key moved: $moved", moved > f(48))
        assertEquals("no seek", playhead0, editor.state.playheadUs)
        assertEquals("eased back to the playhead", playhead0.toDouble(), centreUs(), 0.5 / pxPerUs())
    }
}
