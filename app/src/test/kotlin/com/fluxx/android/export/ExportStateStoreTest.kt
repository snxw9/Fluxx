package com.fluxx.android.export

import org.junit.Assert.*
import org.junit.Test

/**
 * TEXT F3.2 export/playback: the process-global export state is reset, the done dialog opens only for a new
 * completion, a finished export can start again, and the preview is rebuilt as soon as an export ends.
 * (android.net.Uri cannot be built on the JVM, so "saved" is exercised through the primitive rule.)
 */
class ExportStateStoreTest {
    @Test fun everyTerminalStateHasANewCompletionAndResetReturnsToIdle() {
        val store = ExportStateStore()
        assertEquals(ExportState(), store.value)
        store.running(message = "Preparing export"); assertTrue(store.value.running); assertEquals(0L, store.value.completion)
        store.finish("Saved to Movies/Fluxx")
        val first = store.value.completion
        assertFalse(store.value.running); assertNotEquals(0L, first)
        // Dismissing the done dialog / opening a project: idle again (the dialog shows Start).
        store.reset(); assertEquals(ExportState(), store.value)
        // Export again: a new run and a new, different completion.
        store.running(10, "Exporting composition"); store.reset(); assertTrue("a running export is never reset", store.value.running)
        store.finish("Export cancelled"); assertTrue(store.value.completion > first)
    }

    @Test fun doneDialogOpensOnlyForANewCompletion() {
        // Export done while the editor watches: shown once.
        assertTrue(ExportRules.showDone(running = false, saved = true, completion = 3, seen = 2))
        // Already seen (e.g. the screen recomposes or rotates): not again.
        assertFalse(ExportRules.showDone(running = false, saved = true, completion = 3, seen = 3))
        // Dismissed → reset → reopened project: idle state has no completion, so no dialog.
        assertFalse(ExportRules.showDone(ExportStateStore().also { it.finish("x"); it.reset() }.value, seen = 0))
        // Failures and cancels finish without an output: no done view (the Start view shows their message).
        val failed = ExportStateStore().also { it.finish("Export failed: x") }.value
        assertFalse(ExportRules.showDone(failed, seen = 0))
        assertFalse(ExportRules.showDone(running = true, saved = true, completion = 4, seen = 0))
    }

    @Test fun previewIsRebuiltWhenAnExportEnds() {
        val store = ExportStateStore()
        val idle = store.value
        store.running(message = "Preparing export"); val running = store.value
        assertFalse("not while starting", ExportRules.rebuildPreview(idle, running))
        store.running(50, "Exporting composition")
        assertFalse("not while exporting", ExportRules.rebuildPreview(running, store.value))
        val progress = store.value
        for (end in listOf("Saved to Movies/Fluxx", "Export failed: boom", "Export cancelled")) {
            val s = ExportStateStore().also { it.running(50, "Exporting composition") }
            val before = s.value; s.finish(end)
            assertTrue("$end rebuilds the preview", ExportRules.rebuildPreview(before, s.value))
        }
        // Collector start (no previous) and plain idle repeats never rebuild.
        assertFalse(ExportRules.rebuildPreview(null, idle)); assertFalse(ExportRules.rebuildPreview(idle, idle))
        assertFalse(ExportRules.rebuildPreview(progress, progress))
        // A reset after the end does not rebuild again.
        store.finish("Saved"); val done = store.value; store.reset()
        assertFalse(ExportRules.rebuildPreview(done, store.value))
        // A conflated short run (idle → done without an observed running state) still rebuilds.
        assertTrue(ExportRules.rebuildPreview(idle, done))
    }
}
