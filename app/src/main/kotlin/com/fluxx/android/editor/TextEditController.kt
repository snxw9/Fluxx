package com.fluxx.android.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fluxx.android.model.*
import com.fluxx.android.render.LayerGeometry
import kotlinx.coroutines.*

/** One asynchronous draft transaction over the existing gesture protocol. No font work runs here synchronously. */
class TextEditController(private val editor: EditorViewModel, private val scope: CoroutineScope,
    private val measure: suspend (String, String) -> TextLayoutMetrics,
    private val changed: () -> Unit = {}, private val typingChanged: (Boolean) -> Unit = {}) {
    private class Session(val state: EditorState, val layer: CompositionLayer, val time: Long, val token: Long,
        val typing: Boolean) {
        var revision = 0L; var job: Job? = null; var finishing = false
        val after = mutableListOf<() -> Unit>()
    }
    private var session: Session? = null
    var error by mutableStateOf<String?>(null)
        private set
    val active get() = session != null
    val typing get() = session?.typing == true
    fun begin(id: Long, typing: Boolean = true) {
        if (session != null) return
        val snapped = editor.state.project.composition.frameRate.snap(editor.state.playheadUs)
        if (snapped != editor.state.playheadUs) editor.dispatch(EditorAction.Seek(snapped))
        val state = editor.state
        val layer = requireNotNull(state.project.composition.layers.firstOrNull { it.id == id })
        require(layer.type == LayerType.TEXT)
        editor.beginGesture()
        session = Session(state, layer, state.project.composition.frameRate.snap(state.playheadUs),
            requireNotNull(editor.activeGestureId), typing)
        error = null; typingChanged(typing)
    }
    fun source(value: String) {
        val s = session ?: return
        try { TextLimits.validateSource(value); preview(TextEdits.source(s.layer, s.time, value)) }
        catch (failure: IllegalArgumentException) { s.revision++; s.job?.cancel(); error = failure.message }
    }
    fun size(value: Float) {
        val s = session ?: return
        preview(TextEdits.size(s.layer, s.time, value))
    }
    fun preview(text: TextProperties) {
        val s = session ?: return
        if (s.finishing) return
        val revision = ++s.revision
        s.job?.cancel()
        s.job = scope.launch {
            try {
                val local = s.time - s.layer.resolvedKeyframeAnchorUs
                val old = s.layer.text
                val oldSource = old.source.evaluate(local); val newSource = text.source.evaluate(local)
                val oldMetrics = measure(old.fontId, oldSource)
                val newMetrics = measure(text.fontId, newSource)
                ensureActive()
                if (session !== s || revision != s.revision || editor.activeGestureId != s.token || editor.state.project !== s.state.project) return@launch
                val position = if (!old.layoutAnimated && !text.layoutAnimated && !s.layer.animTransform.position.isAnimated)
                    LayerGeometry.compensateTextPosition(s.layer.evaluatedTransform(s.time), oldMetrics.logical(old.size.evaluate(local)),
                        newMetrics.logical(text.size.evaluate(local)), s.layer.anchorX, s.layer.anchorY,
                        s.layer.referenceWidth.takeIf { it > 0 } ?: s.state.project.composition.width,
                        s.layer.referenceHeight.takeIf { it > 0 } ?: s.state.project.composition.height,
                        old.alignment, text.alignment) else null
                editor.previewGesture(EditorAction.Batch(listOf(EditorAction.SetTextPayload(s.layer.id, text, position))), s.token)
                error = null; changed()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (session === s && revision == s.revision) error = failure.message ?: "Text layout unavailable" }
        }
    }
    fun finish(after: () -> Unit = {}) {
        val s = session
        if (s == null) { after(); return }
        s.after += after
        if (s.finishing) return
        s.finishing = true
        scope.launch {
            s.job?.join()
            if (session === s) {
                if (error == null && editor.state.project === s.state.project) editor.commitGesture(s.token)
                else editor.cancelGesture(s.token)
                session = null; typingChanged(false); changed()
                s.after.forEach { it() }
            }
        }
    }
    fun cancel() {
        val s = session ?: return
        session = null; s.job?.cancel(); editor.cancelGesture(s.token)
        error = null; typingChanged(false); changed()
    }
    fun edit(id: Long, text: TextProperties) { finish {
        val current = editor.state.project.composition.layers.first { it.id == id }.text
        begin(id, false); preview(current.copy(fontId = text.fontId, alignment = text.alignment)); finish()
    } }
}

object TextEdits {
    private fun local(layer: CompositionLayer, time: Long) = Math.subtractExact(time, layer.resolvedKeyframeAnchorUs)
    fun source(layer: CompositionLayer, time: Long, value: String): TextProperties {
        val p = layer.text.source; val t = local(layer, time)
        if (value == p.evaluate(t)) return layer.text
        return layer.text.copy(source = if (!p.isAnimated) p.copy(staticValue = value) else p.copy(
            keyframes = FrozenList.of((p.keyframes.filterNot { it.timeUs == t } + StringKeyframe(t, value)).sortedBy { it.timeUs })))
    }
    fun size(layer: CompositionLayer, time: Long, value: Float): TextProperties {
        TextLimits.validateSize(value)
        val p = layer.text.size; val t = local(layer, time)
        return layer.text.copy(size = if (!p.isAnimated) p.copy(staticValue = value) else p.copy(keyframes = FrozenList.of(
            (p.keyframes.filterNot { it.timeUs == t } + Keyframe1D(t, value, p.keyframes.firstOrNull { it.timeUs == t }?.easing ?: EasingPreset.EASY_EASE)).sortedBy { it.timeUs })))
    }
    fun fill(layer: CompositionLayer, time: Long, argb: Int): EditorAction = if (layer.text.fill.isAnimated)
        EditorAction.SetColourKeyframe(layer.id, local(layer, time), argb,
            layer.text.fill.keyframes.firstOrNull { it.timeUs == local(layer, time) }?.easing ?: EasingPreset.EASY_EASE)
        else EditorAction.SetTextPayload(layer.id, layer.text.copy(fill = layer.text.fill.copy(staticValue = argb)))
}
