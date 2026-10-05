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
    private val changed: () -> Unit = {}, private val typingChanged: (Boolean) -> Unit = {},
    private val measureWithLigatures: (suspend (String,String,Boolean) -> TextLayoutMetrics)? = null,
    private val measureDocument: (suspend (String,TextSourceDocument,Boolean)->TextLayoutMetrics)?=null,
    private val measureComplete: (suspend (String,TextSourceDocument,Boolean,TextProperties)->TextLayoutMetrics)?=null) {
    private class Session(val state: EditorState, val layer: CompositionLayer, val time: Long, val token: Long,
        val typing: Boolean) {
        var revision = 0L; var job: Job? = null; var finishing = false
        var draft = layer.text
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
    fun source(value: String) = sourceSelection(value,-1,-1)
    fun sourceSelection(value: String,start: Int,end: Int) {
        val s = session ?: return
        try { TextLimits.validateSource(value); preview(TextEdits.source(s.layer.copy(text=s.draft), s.time, value,start,end)) }
        catch (failure: IllegalArgumentException) { s.revision++; s.job?.cancel(); error = failure.message }
    }
    fun size(value: Float) {
        val s = session ?: return
        preview(TextEdits.size(s.layer, s.time, value))
    }
    fun preview(text: TextProperties) {
        val s = session ?: return
        if (s.finishing) return
        s.draft=text
        val revision = ++s.revision
        s.job?.cancel()
        s.job = scope.launch {
            try {
                val local = s.time - s.layer.resolvedKeyframeAnchorUs
                val old = s.layer.text
                val oldSource = old.source.evaluate(local); val newSource = text.source.evaluate(local)
                val oldMetrics = measureComplete?.invoke(old.fontId,old.source.evaluateDocument(local),old.effectiveLigatures(local),old) ?: measureDocument?.invoke(old.fontId,old.source.evaluateDocument(local),old.effectiveLigatures(local)) ?: measureWithLigatures?.invoke(old.fontId,oldSource,old.effectiveLigatures(local)) ?: measure(old.fontId, oldSource)
                val newMetrics = measureComplete?.invoke(text.fontId,text.source.evaluateDocument(local),text.effectiveLigatures(local),text) ?: measureDocument?.invoke(text.fontId,text.source.evaluateDocument(local),text.effectiveLigatures(local)) ?: measureWithLigatures?.invoke(text.fontId,newSource,text.effectiveLigatures(local)) ?: measure(text.fontId, newSource)
                ensureActive()
                if (session !== s || revision != s.revision || editor.activeGestureId != s.token || editor.state.project !== s.state.project) return@launch
                val position = if (!old.layoutAnimated && !text.layoutAnimated && !s.layer.animTransform.position.isAnimated)
                    LayerGeometry.compensateTextPosition(s.layer.evaluatedTransform(s.time), oldMetrics.logical(old, local),
                        newMetrics.logical(text, local), s.layer.anchorX, s.layer.anchorY,
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
    fun format(id: Long,start: Int,end: Int,change: (TextRunStyle)->TextRunStyle) { finish {
        val layer=editor.state.project.composition.layers.first { it.id==id }
        val time=editor.state.project.composition.frameRate.snap(editor.state.playheadUs)
        val document=layer.text.source.evaluateDocument(time-layer.resolvedKeyframeAnchorUs)
        val a=start.coerceIn(0,document.text.length);val b=end.coerceIn(a,document.text.length)
        val formatted=try { document.format(a,b,change) } catch(failure: IllegalArgumentException) { error=failure.message;return@finish }
        begin(id,false);preview(TextEdits.document(layer,time,formatted));finish()
    } }
    fun boxWidth(value: Float) {
        val s=session ?: return
        try {
            require(value.isFinite() && value>=0) { "Box width must be nonnegative and finite" }
            val layer=s.layer.copy(text=s.draft)
            val document=s.draft.source.evaluateDocument(s.time-layer.resolvedKeyframeAnchorUs)
            preview(TextEdits.document(layer,s.time,document.copy(boxWidth=value)))
        } catch(failure: IllegalArgumentException) { error=failure.message }
    }
    fun paragraph(id: Long,start: Int,end: Int,change: (TextParagraphStyle)->TextParagraphStyle) { finish {
        val layer=editor.state.project.composition.layers.first { it.id==id }
        val time=editor.state.project.composition.frameRate.snap(editor.state.playheadUs)
        val document=layer.text.source.evaluateDocument(time-layer.resolvedKeyframeAnchorUs)
        val a=start.coerceIn(0,document.text.length);val b=end.coerceIn(a,document.text.length)
        val formatted=try { document.formatParagraphs(a,b,change) } catch(failure: IllegalArgumentException) { error=failure.message;return@finish }
        begin(id,false);preview(TextEdits.document(layer,time,formatted));finish()
    } }
    fun editBoxWidth(id: Long,value: Float) { finish { begin(id,false);boxWidth(value);finish() } }
}

object TextEdits {
    private fun local(layer: CompositionLayer, time: Long) = Math.subtractExact(time, layer.resolvedKeyframeAnchorUs)
    fun source(layer: CompositionLayer, time: Long, value: String,start: Int=-1,end: Int=-1): TextProperties {
        val p = layer.text.source; val t = local(layer, time)
        if (value == p.evaluate(t)) return layer.text
        return document(layer,time,p.evaluateDocument(t).replace(value,start,end))
    }
    fun document(layer: CompositionLayer,time: Long,value: TextSourceDocument): TextProperties {
        val p=layer.text.source;val t=local(layer,time)
        return layer.text.copy(source=if(!p.isAnimated) p.copy(staticValue=value.text,document=value) else p.copy(
            keyframes=FrozenList.of((p.keyframes.filterNot { it.timeUs==t }+StringKeyframe(t,value.text,value)).sortedBy { it.timeUs })))
    }
    fun size(layer: CompositionLayer, time: Long, value: Float): TextProperties {
        TextLimits.validateSize(value)
        val p = layer.text.size; val t = local(layer, time)
        return layer.text.copy(size = if (!p.isAnimated) p.copy(staticValue = value) else p.copy(keyframes = FrozenList.of(
            (p.keyframes.filterNot { it.timeUs == t } + Keyframe1D(t, value, p.keyframes.firstOrNull { it.timeUs == t }?.easing ?: EasingPreset.EASY_EASE)).sortedBy { it.timeUs })))
    }
    fun scalar(layer: CompositionLayer, time: Long, property: AnimPropertyType, value: Float): TextProperties {
        val p = layer.text.scalar(property); val t = local(layer, time)
        return layer.text.withScalar(property, if (!p.isAnimated) p.copy(staticValue = value) else p.copy(keyframes = FrozenList.of(
            (p.keyframes.filterNot { it.timeUs == t } + Keyframe1D(t, value, p.keyframes.firstOrNull { it.timeUs == t }?.easing ?: EasingPreset.EASY_EASE)).sortedBy { it.timeUs })))
    }
    fun colour(layer: CompositionLayer, time: Long, property: AnimPropertyType, argb: Int): EditorAction {
        val p = layer.text.colour(property); val t = local(layer, time)
        return if (p.isAnimated) EditorAction.SetColourKeyframe(layer.id, t, argb,
            p.keyframes.firstOrNull { it.timeUs == t }?.easing ?: EasingPreset.EASY_EASE, property)
        else EditorAction.SetTextPayload(layer.id, layer.text.withColour(property, p.copy(staticValue = argb)))
    }
    fun fill(layer: CompositionLayer, time: Long, argb: Int): EditorAction = if (layer.text.fill.isAnimated)
        EditorAction.SetColourKeyframe(layer.id, local(layer, time), argb,
            layer.text.fill.keyframes.firstOrNull { it.timeUs == local(layer, time) }?.easing ?: EasingPreset.EASY_EASE)
        else EditorAction.SetTextPayload(layer.id, layer.text.copy(fill = layer.text.fill.copy(staticValue = argb)))
}
