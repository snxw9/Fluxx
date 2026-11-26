package com.fluxx.android.ui.editor

import com.fluxx.android.editor.EditorAction
import com.fluxx.android.editor.EditorViewModel
import com.fluxx.android.render.AudioGains
import com.fluxx.android.render.PreviewController

/**
 * AUD2 B3: the inspector's audio edits, in one place (EditorScreen and the device benchmark run the same calls).
 * - A gain slider drag is ONE gesture: [begin], [preview] per step, [commit] (one undo step). A step previews the gain
 *   live ([PreviewController.previewGains]): playback keeps running; no new mix, no seek, no re-render.
 * - [change] (mute, a typed gain) is one edit; a gain-only change is live too, mute keeps the AUD1 path (it changes what
 *   is mixed).
 * Gains are held to the model's range ([AudioGains.clamp]).
 */
object GainEdits {
    fun begin(editor: EditorViewModel) { if (editor.activeGestureId == null) editor.beginGesture() }

    fun preview(editor: EditorViewModel, preview: PreviewController?, layerId: Long, muted: Boolean, gain: Float) {
        begin(editor)
        editor.previewGesture(EditorAction.Batch(listOf(EditorAction.SetAudio(layerId, muted, AudioGains.clamp(gain)))))
        if (preview?.previewGains(editor.displayedState.project) != true)
            preview?.update(editor.displayedState.project, editor.displayedState.playheadUs, false)
    }

    fun commit(editor: EditorViewModel, preview: PreviewController?) {
        editor.commitGesture()
        if (preview?.previewGains(editor.state.project) != true) preview?.update(editor.state.project, editor.state.playheadUs, false)
    }

    fun change(editor: EditorViewModel, preview: PreviewController?, layerId: Long, muted: Boolean, gain: Float) {
        editor.dispatch(EditorAction.SetAudio(layerId, muted, AudioGains.clamp(gain)))
        if (preview?.previewGains(editor.state.project) != true) preview?.update(editor.state.project, editor.state.playheadUs, false)
    }
}
