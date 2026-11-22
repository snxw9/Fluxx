package com.fluxx.android.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.fluxx.android.editor.EditorAction
import com.fluxx.android.model.CompositionLayer
import com.fluxx.android.model.Marker
import com.fluxx.android.model.Markers
import com.fluxx.android.model.ProjectDocument
import com.fluxx.android.ui.common.colorpicker.ColourPickerVariant
import com.fluxx.android.ui.common.colorpicker.FluxxColourPicker
import com.fluxx.android.ui.common.colorpicker.MARKER_SWATCHES
import com.fluxx.android.ui.theme.Border
import com.fluxx.android.ui.theme.Surface
import com.fluxx.android.ui.theme.TextPrimary
import com.fluxx.android.ui.theme.TextSecondary

/** A marker is identified by its owner (null = the composition) and its owner-scoped ID. */
internal data class MarkerRef(val layerId: Long?, val id: Long)

/** Pure marker-editing rules shared by the timeline and its JVM tests. */
internal object MarkerEditing {
    const val DESCRIPTION_MAX = 128
    const val COMPOSITION_DEFAULT = 0xFFF44336.toInt()
    const val LAYER_DEFAULT = 0xFFFF9800.toInt()

    /** What a playhead tap or hold does. Removal on tap is exactly the pre-TEXT UI 8 behaviour. */
    sealed interface PlayheadResult {
        data class Remove(val action: EditorAction) : PlayheadResult
        /** One undo entry (the add), then the popover opens on the new marker. */
        data class Create(val action: EditorAction, val ref: MarkerRef, val marker: Marker) : PlayheadResult
        data class Edit(val ref: MarkerRef, val marker: Marker) : PlayheadResult
        data object OutsideLayer : PlayheadResult
    }

    fun playhead(project: ProjectDocument, selectedLayer: CompositionLayer?, playheadUs: Long, hold: Boolean,
        defaultColour: Int): PlayheadResult {
        val comp = project.composition
        val time = comp.frameRate.snap(playheadUs)
        val markers = selectedLayer?.markers ?: comp.markers
        val anchor = selectedLayer?.resolvedKeyframeAnchorUs ?: 0L
        val existing = Markers.atFrame(markers, anchor, time, comp.frameRate)
        val owner = selectedLayer?.id
        if (existing != null) return if (hold) PlayheadResult.Edit(MarkerRef(owner, existing.id), existing)
            else PlayheadResult.Remove(if (owner == null) EditorAction.DeleteCompositionMarker(existing.id)
                else EditorAction.DeleteLayerMarker(owner, existing.id))
        if (selectedLayer != null && (playheadUs < selectedLayer.timing.startUs || playheadUs >= (selectedLayer.timing.endUs ?: Long.MAX_VALUE) ||
                time < selectedLayer.timing.startUs || time >= (selectedLayer.timing.endUs ?: Long.MAX_VALUE))) return PlayheadResult.OutsideLayer
        val marker = Marker(Math.addExact(markers.maxOfOrNull { it.id } ?: 0L, 1L), Math.subtractExact(time, anchor), defaultColour)
        return PlayheadResult.Create(if (owner == null) EditorAction.AddCompositionMarker(marker) else EditorAction.AddLayerMarker(owner, marker),
            MarkerRef(owner, marker.id), marker)
    }

    fun find(project: ProjectDocument, ref: MarkerRef): Marker? =
        if (ref.layerId == null) project.composition.markers.firstOrNull { it.id == ref.id }
        else project.composition.layers.firstOrNull { it.id == ref.layerId }?.markers?.firstOrNull { it.id == ref.id }

    /**
     * Whether the marker has a glyph to anchor to: composition pentagons always; layer pennants only inside the
     * trimmed clip (the timeline's draw rule). Otherwise the popover anchors to the playhead target, where it sits.
     */
    fun drawnOnBar(project: ProjectDocument, ref: MarkerRef): Boolean {
        if (ref.layerId == null) return true
        val layer = project.composition.layers.firstOrNull { it.id == ref.layerId } ?: return false
        val marker = layer.markers.firstOrNull { it.id == ref.id } ?: return false
        val time = Math.addExact(layer.resolvedKeyframeAnchorUs, marker.timeUs)
        val start = layer.timing.startUs
        return time >= start && time < start + (layer.timing.durationUs ?: 0L)
    }

    fun edit(ref: MarkerRef, colour: Int, description: String): EditorAction =
        if (ref.layerId == null) EditorAction.EditCompositionMarker(ref.id, colour, description)
        else EditorAction.EditLayerMarker(ref.layerId, ref.id, colour, description)

    /**
     * TEXT F3.3: the layer selected after a marker is tapped or created. Marker selection is not an inspector change:
     * it keeps the inspector open on the same page and focus. Only a marker owned by a different layer moves the
     * selection to that layer; a composition marker keeps the current selection.
     */
    fun selectionAfterMarker(selected: Long?, owner: Long?): Long? = owner ?: selected

    /** Single line, at most [DESCRIPTION_MAX] characters (the schema's limit). */
    fun sanitize(text: String) = text.replace("\n", "").replace("\r", "").take(DESCRIPTION_MAX)
}

/** The editor's transient-gesture protocol, unchanged: begin, preview (Batch-wrapped), commit = one undo entry. */
internal interface MarkerGestureSink { fun begin(); fun preview(action: EditorAction); fun commit(); fun cancel() }

/**
 * One open marker popover. Description and colour apply live through the transient gesture protocol; each
 * description session (focus to blur / Done / close) and each colour interaction is exactly one undo entry. Starting
 * one kind of session ends the other. Closing commits whatever is in flight: closing never discards edits.
 */
internal class MarkerEditSession(val ref: MarkerRef, marker: Marker, private val sink: MarkerGestureSink) {
    private enum class Mode { NONE, DESCRIPTION, COLOUR }
    var colour by mutableIntStateOf(marker.colorArgb); private set
    var description by mutableStateOf(marker.description); private set
    private var mode = Mode.NONE
    private var colourBefore = colour
    var closed = false; private set
    val active get() = mode != Mode.NONE

    private fun start(next: Mode) {
        if (closed || mode == next) return
        end()
        mode = next; sink.begin()
    }
    private fun push() = sink.preview(EditorAction.Batch(listOf(MarkerEditing.edit(ref, colour, description))))
    private fun end() { if (mode != Mode.NONE) { mode = Mode.NONE; sink.commit() } }

    fun beginDescription() = start(Mode.DESCRIPTION)
    fun description(text: String) {
        if (closed) return
        val clean = MarkerEditing.sanitize(text)
        if (clean == description) return
        start(Mode.DESCRIPTION); description = clean; push()
    }
    fun endDescription() { if (mode == Mode.DESCRIPTION) end() }

    fun beginColour() { if (closed) return; start(Mode.COLOUR); colourBefore = colour }
    fun colour(argb: Int) { if (closed) return; if (mode != Mode.COLOUR) beginColour(); colour = argb; push() }
    fun endColour() { if (mode == Mode.COLOUR) end() }
    /** A cancelled pointer interaction (not a close) reverts only that interaction. */
    fun cancelColour() { if (mode == Mode.COLOUR) { mode = Mode.NONE; colour = colourBefore; sink.cancel() } }

    /** Outside tap / Back / Done: commit in-flight edits, then stop. */
    fun close() { if (!closed) { end(); closed = true } }
    /** The marker is gone (removed, undone): its gesture was already cancelled by that edit; drop without committing. */
    fun detach() { mode = Mode.NONE; closed = true }
}

/** Popover body: description field, then the shared COMPACT picker with the marker swatches. No buttons at all. */
@Composable
internal fun MarkerPopoverContent(session: MarkerEditSession) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val owner = if (session.ref.layerId == null) "Composition" else "Layer"
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.width(MARKER_POPOVER_WIDTH.dp).testTag("marker-popover-content"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 4.dp).clip(shape).background(Surface)
            .border(1.dp, if (focused) TextPrimary else Border, shape).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
            if (session.description.isEmpty()) Text("$owner marker description", color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            BasicTextField(session.description, { session.description(it) }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary), cursorBrush = SolidColor(TextPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { session.endDescription(); focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth().testTag("marker-description")
                    .onFocusChanged {
                        if (it.isFocused && !focused) session.beginDescription()
                        if (focused && !it.isFocused) session.endDescription()
                        focused = it.isFocused
                    }
                    .semantics { contentDescription = "$owner marker description" })
        }
        FluxxColourPicker(ColourPickerVariant.COMPACT, session.colour,
            onBegin = session::beginColour, onPreview = session::colour, onFinish = session::endColour, onCancel = session::cancelColour,
            name = "marker colour", swatches = MARKER_SWATCHES, header = false)
    }
}

internal const val MARKER_POPOVER_WIDTH = 288

/** The composition marker pentagon's vertical span on the ruler (its popover anchor). */
internal const val MARKER_PENTAGON_TOP_DP = 16
internal const val MARKER_PENTAGON_BOTTOM_DP = 28
