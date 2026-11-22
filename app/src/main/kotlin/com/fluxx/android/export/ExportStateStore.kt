package com.fluxx.android.export

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * TEXT F3.2: the export state the service publishes, as a plain store (no Android types, JVM-tested).
 *
 * Every terminal state (saved, failed, cancelled) carries a fresh, non-zero [ExportState.completion] id, so the editor
 * reacts to a NEW completion only, never to a stale output left in this process-global state. [reset] returns to the
 * idle state when the done dialog is dismissed or a project is opened.
 */
class ExportStateStore {
    private val mutable = MutableStateFlow(ExportState())
    private var completions = 0L
    val state = mutable.asStateFlow()
    val value get() = mutable.value
    fun running(percent: Int? = null, message: String) { mutable.value = ExportState(true, percent, message) }
    /** Terminal state: idle again, with a new completion id. */
    fun finish(message: String, output: android.net.Uri? = null) {
        mutable.value = ExportState(message = message, output = output, completion = ++completions)
    }
    /** Back to idle (no message, no output). A running export is never reset. */
    fun reset() { if (!mutable.value.running) mutable.value = ExportState() }
}

/** Editor-side rules for the export dialog and the preview (JVM-tested). */
object ExportRules {
    /** Show the done view for a completion the editor has not seen yet. */
    fun showDone(state: ExportState, seen: Long) = showDone(state.running, state.output != null, state.completion, seen)
    fun showDone(running: Boolean, saved: Boolean, completion: Long, seen: Long) = !running && saved && completion != 0L && completion != seen
    /** The preview is stopped for an export; rebuild it as soon as the export has ended (saved, failed or cancelled). */
    fun rebuildPreview(previous: ExportState?, current: ExportState) = previous != null && !current.running &&
        // A StateFlow can conflate a short run, so a new completion id counts even if "running" was never observed.
        (previous.running || (current.completion != 0L && current.completion != previous.completion))
}
