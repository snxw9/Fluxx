package com.fluxx.android.editor

import androidx.compose.runtime.snapshots.Snapshot
import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * MP2.1 B3: the UI-only playback position and the editor state. While the preview plays, position reports go to
 * [EditorViewModel.playbackPosition] and do NOT write the observed editor state (so nothing that read it recomposes per
 * frame), yet [EditorViewModel.state] reads the live position in an event handler; at every pause, stop, seek and commit
 * the two agree and the editor state is exactly what it was before MP2.1.
 */
class PlaybackPositionTest {
    private val rate = FrameRate(30, 1)
    private fun vm(): EditorViewModel {
        val layers = (0 until 3).map { k -> CompositionLayer(k + 1L, LayerType.SOLID, timing = ClipTiming(k * 2_000_000L, 0, 2_000_000), name = "s$k") }
        return EditorViewModel().also { it.dispatch(EditorAction.Load(ProjectDocument(Composition(durationUs = 6_000_000, frameRate = rate, layers = FrozenList.of(layers))))) }
    }

    /** The snapshot objects [read] reads, and whether applying [write] changes any of them (that would recompose a reader). */
    private fun invalidates(read: () -> Unit, write: () -> Unit): Boolean {
        val reads = HashSet<Any>()
        Snapshot.observe(readObserver = { reads.add(it) }) { read() }
        val changed = HashSet<Any>()
        val handle = Snapshot.registerApplyObserver { set, _ -> changed.addAll(set) }
        try { write(); Snapshot.sendApplyNotifications() } finally { handle.dispose() }
        return changed.any { it in reads }
    }

    @Test fun pausedReportsAreSeeksAsBefore() {
        val e = vm()
        e.reportPosition(rate.frameTimeUs(15))
        assertEquals(rate.frameTimeUs(15), e.state.playheadUs)
        assertEquals(e.state.playheadUs, e.playbackPosition.value)
        assertTrue(invalidates({ e.state }) { e.reportPosition(rate.frameTimeUs(16)) })
    }

    @Test fun playingReportsDoNotInvalidateTheEditorStateButReadLiveInHandlers() {
        val e = vm(); var playing = true
        e.playbackRunning = { playing }
        val project = e.state.project
        // 30 frames of playback: readers of the editor state (the timeline, the screen) are never invalidated.
        assertFalse(invalidates({ e.state; e.displayedState.project }) { for (f in 1..30) e.reportPosition(rate.frameTimeUs(f.toLong())) })
        // The live position is observable for the per-frame consumers that read it.
        assertTrue(invalidates({ e.playbackPosition.value }) { e.reportPosition(rate.frameTimeUs(31)) })
        // An event handler reads the live position (the same object otherwise: nothing else changed).
        assertEquals(rate.frameTimeUs(31), e.state.playheadUs)
        assertEquals(rate.frameTimeUs(31), e.displayedState.playheadUs)
        assertSame(project, e.state.project)
        // Pause: the preview's next report (its pause frame) arrives with playback stopped and is a normal Seek.
        playing = false
        assertTrue(invalidates({ e.state }) { e.reportPosition(rate.frameTimeUs(31)) })
        assertEquals(rate.frameTimeUs(31), e.state.playheadUs)
        assertEquals(e.state.playheadUs, e.playbackPosition.value)
    }

    @Test fun aSeekOrAnEditWhilePlayingAgreesWithThePosition() {
        val e = vm(); var playing = true
        e.playbackRunning = { playing }
        for (f in 1..20) e.reportPosition(rate.frameTimeUs(f.toLong()))
        // A commit (an edit) while playing keeps the live playhead in the stored state.
        e.dispatch(EditorAction.SetVisibility(2, false))
        assertEquals(rate.frameTimeUs(20), e.state.playheadUs)
        assertEquals(e.state.playheadUs, e.playbackPosition.value)
        assertFalse(e.state.project.composition.layers.first { it.id == 2L }.visible)
        // A seek (the transport's previous / next, a tap on the ruler) moves both.
        e.dispatch(EditorAction.Seek(4_000_000, snap = true))
        assertEquals(4_000_000L, e.state.playheadUs); assertEquals(4_000_000L, e.playbackPosition.value)
        // Undo during playback restores the document; the playhead stays where playback is.
        e.reportPosition(rate.frameTimeUs(130))
        e.undo()
        assertTrue(e.state.project.composition.layers.first { it.id == 2L }.visible)
        assertEquals(rate.frameTimeUs(130), e.state.playheadUs)
        assertEquals(e.state.playheadUs, e.playbackPosition.value)
        // Stop at the end: the last report with playback stopped.
        playing = false
        e.reportPosition(6_000_000)
        assertEquals(6_000_000L, e.state.playheadUs); assertEquals(6_000_000L, e.playbackPosition.value)
    }

    @Test fun theSnappedTimeOfAnEmptyCompositionIsZeroAsTheSeekRuleSays() {
        val e = EditorViewModel(); e.playbackRunning = { true }
        e.reportPosition(1_234_567)
        assertEquals(0L, e.playbackPosition.value); assertEquals(0L, e.state.playheadUs)
    }

    @Test fun thePlayheadIsNeverInUndoHistory() {
        val e = vm(); e.playbackRunning = { true }
        e.dispatch(EditorAction.SetVisibility(1, false))
        for (f in 1..10) e.reportPosition(rate.frameTimeUs(f.toLong()))
        assertTrue(e.canUndo)
        e.undo()
        assertFalse(e.canUndo)  // the reports added nothing to undo
        assertEquals(rate.frameTimeUs(10), e.state.playheadUs)
    }
}
