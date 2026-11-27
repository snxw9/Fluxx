package com.fluxx.android.editor

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*

class EditorViewModel : ViewModel() {
    var state by mutableStateOf(EditorState())
        private set
    var transientState by mutableStateOf<EditorState?>(null)
        private set
    val displayedState get() = transientState ?: state
    private var gestureBase: EditorState? = null
    private var gestureAction: EditorAction? = null
    private var gestureSerial = 0L
    val activeGestureId: Long? get() = gestureSerial.takeIf { gestureBase != null }
    private var projectGeneration = 0L
    // One session clipboard; it is neither document state nor an undoable selection.
    // TLP T4: it holds a LIST of layers (timeline order, top first), so copy / paste are multi-layer.
    private var copiedLayers by mutableStateOf<List<CompositionLayer>>(emptyList())
    val hasCopiedLayer: Boolean get() = copiedLayers.isNotEmpty()
    val copiedLayerCount: Int get() = copiedLayers.size

    fun copyLayer(id: Long): Boolean = copyLayers(listOf(id))

    /** TLP T4: copies [ids] (stored in timeline order so paste keeps their stacking). */
    fun copyLayers(ids: Collection<Long>): Boolean {
        commitGesture()
        val comp = state.project.composition
        val layers = LayerOrder.timeline(comp.layers).filter { it.id in ids }
        if (layers.isEmpty()) return false
        copiedLayers = layers.map { it.withReferenceCanvas(comp.width, comp.height) }
        return true
    }

    fun duplicateLayer(id: Long) = duplicateLayers(listOf(id), 1)

    /**
     * TLP T4: [count] copies of each of [ids], each directly above its source (the existing duplicate rule), as ONE Batch
     * (one undo). Each step's actions are computed against the state after the previous steps, so the batch replays exactly.
     */
    fun duplicateLayers(ids: List<Long>, count: Int) {
        if (count < 1) return
        commitGesture()
        var s = state
        val actions = ArrayList<EditorAction>()
        for (id in ids) repeat(count) {
            val source = s.project.composition.layers.firstOrNull { it.id == id } ?: return@repeat
            val step = copyActions(s, source, source.timing.startUs, directlyAboveSource = true)
            actions += step; s = EditorReducer.reduce(s, EditorAction.Batch(step))
        }
        if (actions.isNotEmpty()) dispatch(EditorAction.Batch(actions))
    }

    /**
     * Paste at the frame-snapped playhead. TLP T4: several copied layers keep their relative timing (the earliest goes to
     * the playhead) and their relative stacking (all above the existing layers), as ONE Batch (one undo).
     */
    fun pasteLayer() {
        val layers = copiedLayers
        if (layers.isEmpty()) return
        commitGesture()
        val at = state.project.composition.frameRate.snap(state.playheadUs)
        val earliest = layers.minOf { it.timing.startUs }
        var s = state
        val actions = ArrayList<EditorAction>()
        // Bottom first: each new layer sorts above the previous ones (fresh higher ids tie on the top z-order).
        for (source in layers.reversed()) {
            val step = copyActions(s, source, (at + (source.timing.startUs - earliest)).coerceAtLeast(0))
            actions += step; s = EditorReducer.reduce(s, EditorAction.Batch(step))
        }
        dispatch(if (actions.size == 1) actions.single() else EditorAction.Batch(actions))
    }

    private fun copyActions(state: EditorState, source: CompositionLayer, startUs: Long, directlyAboveSource: Boolean = false): List<EditorAction> {
        val comp = state.project.composition
        val id = EditorReducer.nextLayerId(state.project)
        // A copied media snapshot may outlive a relink. Avoid giving two different references one asset ID.
        val asset = source.asset?.let { snapshot ->
            val current = comp.layers.firstOrNull { it.asset?.id == snapshot.id }?.asset
            if (current != null && current != snapshot) snapshot.copy(id = java.util.UUID.randomUUID().toString()) else snapshot
        }
        val add = EditorAction.Add(source.movedTo(startUs).copy(
            id = id,
            text = source.text.withFreshAnimatorIds(),
            // The fresh ID sorts first among tied z-orders, without risking Int overflow.
            zOrder = comp.layers.maxOfOrNull { it.zOrder } ?: 0,
            name = if (source.name.isNotBlank()) "${source.name} Copy" else "${source.type.name} $id",
            asset = asset
        ))
        val ordered = comp.layers.sortedWith(compareByDescending<CompositionLayer> { it.zOrder }.thenByDescending { it.id })
        val sourceIndex = ordered.indexOfFirst { it.id == source.id }
        // Reorder targets are indices before removal. From the top, moving to the source's
        // predecessor inserts immediately above the source. Batch keeps creation+order atomic.
        return if (directlyAboveSource && sourceIndex > 0) listOf(add, EditorAction.ReorderLayer(id, ordered[sourceIndex - 1].id))
            else listOf(add)
    }


    /**
     * TLP T0: selection mode (null = off). Ordered, primary = last. UI state only: not in [EditorState], so it is never
     * in undo history and never saved. After every document change it is pruned to layers that still exist; when none
     * remain the mode exits. While on, the single selection mirrors the primary (not recorded in history: Select never is).
     */
    var layerSelection by mutableStateOf<LayerSelection?>(null)
        private set
    val selectionMode: Boolean get() = layerSelection != null
    fun enterSelection(id: Long) {
        if (state.project.composition.layers.none { it.id == id }) return
        commitGesture()
        layerSelection = LayerSelection(listOf(id)); syncPrimary()
    }
    fun exitSelection() { layerSelection = null }
    /** Replaces the selection (Select all, Odd / Even, Invert, add / remove); an empty one exits the mode. */
    fun setSelection(next: LayerSelection) {
        val ids = state.project.composition.layers.map { it.id }.toSet()
        layerSelection = next.prune(ids).takeUnless { it.isEmpty }; syncPrimary()
    }
    private fun syncSelection() {
        layerSelection?.let { setSelection(it) }; keySelection?.let { selectKeys(it) }
        if (expandedLayers.isNotEmpty()) expandedLayers = TwirlExpansion.prune(expandedLayers, state.project)
    }
    /**
     * TLP T5 / TL3 B3: key selection mode (null = off). Since TL3 it can span tracks (layer + property) and layers. UI
     * state only, pruned like the layers (keys that no longer exist drop out; none left = the mode exits).
     */
    var keySelection by mutableStateOf<KeySelectionSet?>(null)
        private set
    /** TLP T5: copied keys (session clipboard, like the layer clipboard; not document state). */
    var keyClipboard by mutableStateOf<KeyClipboard?>(null)
    fun selectKeys(next: KeySelectionSet?) { keySelection = next?.let { s -> KeySelectionSet.of(s.tracks.mapNotNull { KeyEdits.prune(state.project, it) }) } }
    /** One track (T5 call sites). */
    fun selectKeys(next: KeyframeSelection?) = selectKeys(KeySelectionSet.of(next))
    fun exitKeySelection() { keySelection = null }
    /** TL3 B1: the expanded (twirled-down) layers. UI state only: never in undo history, never saved; pruned on removal. */
    var expandedLayers by mutableStateOf<Set<Long>>(emptySet())
        private set
    fun setExpanded(next: Set<Long>) { expandedLayers = TwirlExpansion.prune(next, state.project) }
    private fun syncPrimary() {
        val p = layerSelection?.primary ?: return
        if (state.selectedLayerId != p) state = EditorReducer.reduce(state, EditorAction.Select(p))
    }
    private data class HistoryEntry(val project: ProjectDocument, val selectedLayerId: Long?)
    private fun EditorState.historyEntry() = HistoryEntry(project, selectedLayerId)
    private val undoStack = mutableListOf<HistoryEntry>()
    private val redoStack = mutableListOf<HistoryEntry>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun undo() {
        cancelGesture()
        if (undoStack.isEmpty()) return
        val prev = undoStack.removeAt(undoStack.lastIndex)
        redoStack.add(state.historyEntry())
        state = state.copy(project = prev.project, selectedLayerId = prev.selectedLayerId, selectedMarkerId = null,
            playheadUs = state.playheadUs.coerceAtMost(prev.project.composition.resolvedDurationUs))
        syncSelection()
    }

    fun redo() {
        cancelGesture()
        if (redoStack.isEmpty()) return
        val next = redoStack.removeAt(redoStack.lastIndex)
        undoStack.add(state.historyEntry())
        state = state.copy(project = next.project, selectedLayerId = next.selectedLayerId, selectedMarkerId = null,
            playheadUs = state.playheadUs.coerceAtMost(next.project.composition.resolvedDurationUs))
        syncSelection()
    }

    fun dispatch(action: EditorAction) {
        cancelGesture()
        val next = EditorReducer.reduce(state, action)
        if (action is EditorAction.Load) {
            projectGeneration++
            copiedLayers = emptyList()
            undoStack.clear()
            redoStack.clear()
        } else if (action !is EditorAction.Seek && action !is EditorAction.Select && next.project != state.project) {
            undoStack.add(state.historyEntry())
            redoStack.clear()
        }
        state = next
        if (action is EditorAction.Load) { layerSelection = null; keySelection = null; keyClipboard = null; expandedLayers = emptySet() } else syncSelection()
    }

    /** Resolve and apply transport navigation from one current snapshot, at click time. */
    fun navigateBoundary(next: Boolean, isInspectorOpen: Boolean, activeProperty: AnimPropertyType?,
        animatorTrack: AnimatorTrackAddress? = null): Long {
        commitGesture()
        val current = state
        val target = if (next) ContextualNavigation.findNext(current.project, current.selectedLayer,
            isInspectorOpen, activeProperty, current.playheadUs, current.selectedMarkerId, animatorTrack)
        else ContextualNavigation.findPrevious(current.project, current.selectedLayer,
            isInspectorOpen, activeProperty, current.playheadUs, current.selectedMarkerId, animatorTrack)
        dispatch(EditorAction.Seek(target))
        return state.playheadUs
    }

    /** Playback progress does not commit project edits or terminate a drag. */
    fun reportPosition(timeUs: Long) { state=EditorReducer.reduce(state,EditorAction.Seek(timeUs)) }
    fun beginGesture() { gestureSerial++;gestureBase=state;gestureAction=null;transientState=state }
    /** Static edits keep SetTransform unchanged; animated channels auto-key at the gesture's playhead. */
    fun editTransform(id: Long, value: Transform, preview: Boolean = false) {
        val base = if (preview) requireNotNull(gestureBase) else state
        val layer = base.project.composition.layers.first { it.id == id }
        val current = layer.evaluatedTransform(base.playheadUs)
        val local = Math.subtractExact(base.playheadUs, layer.resolvedKeyframeAnchorUs)
        val edits = mutableListOf<EditorAction>()
        var static = layer.transform
        val a = layer.animTransform
        if (value.positionX != current.positionX || value.positionY != current.positionY) {
            if (a.position.isAnimated) edits.add(EditorAction.SetKeyframe2D(id, AnimPropertyType.POSITION, local,
                value.positionX, value.positionY, a.position.keyframes.firstOrNull { it.timeUs == local }?.easing ?: EasingPreset.EASY_EASE))
            else static = static.copy(positionX = value.positionX, positionY = value.positionY)
        }
        if (value.scaleX != current.scaleX || value.scaleY != current.scaleY) {
            if (a.scale.isAnimated) edits.add(EditorAction.SetKeyframe2D(id, AnimPropertyType.SCALE, local,
                value.scaleX, value.scaleY, a.scale.keyframes.firstOrNull { it.timeUs == local }?.easing ?: EasingPreset.EASY_EASE))
            else static = static.copy(scaleX = value.scaleX, scaleY = value.scaleY)
        }
        if (value.rotationDegrees != current.rotationDegrees) {
            if (a.rotation.isAnimated) edits.add(EditorAction.SetKeyframe(id, AnimPropertyType.ROTATION, local,
                value.rotationDegrees, a.rotation.keyframes.firstOrNull { it.timeUs == local }?.easing ?: EasingPreset.EASY_EASE))
            else static = static.copy(rotationDegrees = value.rotationDegrees)
        }
        if (value.opacity != current.opacity) {
            if (a.opacity.isAnimated) edits.add(EditorAction.SetKeyframe(id, AnimPropertyType.OPACITY, local,
                value.opacity, a.opacity.keyframes.firstOrNull { it.timeUs == local }?.easing ?: EasingPreset.EASY_EASE))
            else static = static.copy(opacity = value.opacity)
        }
        if (static != layer.transform) edits.add(EditorAction.SetTransform(id, static))
        val action = EditorAction.Batch(edits)
        if (preview) previewGesture(action) else if (edits.isNotEmpty()) dispatch(action)
    }
    fun editAnchor(id: Long, anchorX: Float, anchorY: Float, compensatedTransform: Transform?, preview: Boolean = false) {
        val action = EditorAction.SetAnchorPoint(id, anchorX, anchorY, compensatedTransform)
        if (preview) previewGesture(action) else dispatch(action)
    }
    fun previewGesture(action: EditorAction, gestureId: Long? = null) {
        if (gestureId != null && gestureId != activeGestureId) return
        // Pointer cancellation and lazy-item disposal can precede a queued final drag callback.
        // An ended gesture must not crash or recreate a preview against the committed document.
        val base=gestureBase ?: return
        // TEXT F3.2: the timeline's keyframe drag sends EditTextAnimator(Key MOVE) for an animator-focused field; it was
        // missing here, so the require threw on the main thread (A16 crash 17:47). Failures never crash: an unsupported or
        // invalid preview keeps the last valid one and publishes [gestureError]; the document is untouched.
        if (!transientAllowed(action)) { gestureError = "Unsupported transient edit"; return }
        val next = try { EditorReducer.reduce(base,action) } catch (error: RuntimeException) {
            gestureError = error.message ?: "Edit failed"; return
        }
        transientState=next
        gestureAction=action
    }
    private fun transientAllowed(action: EditorAction) = action is EditorAction.SetAnchorPoint || action is EditorAction.SetColor ||
        action is EditorAction.ReorderLayer || action is EditorAction.Batch || action is EditorAction.Move || action is EditorAction.Trim ||
        action is EditorAction.SetTransform || action is EditorAction.MoveKeyframe || action is EditorAction.SetKeyframe ||
        action is EditorAction.SetKeyframe2D || action is EditorAction.EditTextAnimator || action is EditorAction.SetKeyEasing
    /** The last gesture preview/commit that failed (shown once by the screen, then [clearGestureError]). */
    var gestureError by mutableStateOf<String?>(null)
        private set
    fun clearGestureError() { gestureError = null }
    fun commitGesture(gestureId: Long? = null) {
        if (gestureId != null && gestureId != activeGestureId) return
        val action=gestureAction
        val base=gestureBase
        val changed = transientState?.project != base?.project
        cancelGesture()
        if(action!=null && base!=null && changed) {
            val reduced = try { EditorReducer.reduce(base, action) } catch (error: RuntimeException) {
                gestureError = error.message ?: "Edit failed"; return
            }
            undoStack.add(base.historyEntry())
            redoStack.clear()
            val time = state.playheadUs.coerceAtMost(reduced.project.composition.resolvedDurationUs)
            state = reduced.copy(playheadUs = time,
                selectedMarkerId = reduced.selectedMarkerId.takeIf { time == reduced.playheadUs })
            syncSelection()
        }
    }
    fun cancelGesture(gestureId: Long? = null) {
        if (gestureId != null && gestureId != activeGestureId) return
        transientState=null;gestureBase=null;gestureAction=null
    }

    suspend fun addMedia(repository: MediaRepository,uri: Uri,name: String="",startUs: Long=state.playheadUs) {
        val generation = projectGeneration
        val (asset,type)=repository.reference(uri)
        if (generation != projectGeneration) throw kotlinx.coroutines.CancellationException("Project changed during import")
        val comp=state.project.composition
        val duration=if(type==LayerType.VIDEO) requireNotNull(asset.durationUs) else 5_000_000L
        dispatch(EditorAction.Add(CompositionLayer(EditorReducer.nextLayerId(state.project),type,
            zOrder=(comp.layers.maxOfOrNull { it.zOrder } ?: -1)+1,
            timing=ClipTiming(comp.frameRate.snap(startUs),0,duration),asset=asset,name=name)))
    }
    /** MP1.1 C: how the media browser's bulk add places the clips. */
    enum class BulkPlacement { AT_PLAYHEAD, SEQUENCE, STAGGER }

    /**
     * MP1.1: the media browser's multi-select add as ONE Batch (one undo step). The picked media are resolved one after
     * another off the main thread (in selection order), then every clip is added at [startUs] stacked above the existing
     * layers, and [placement] applies the layer bar's own Sequence / Stagger (MultiLayerEdits, its defaults) to the new
     * clips, computed against the state after the adds. Returns the new layer ids (selection order). Nothing is added if any
     * item fails to resolve. Before MP1.1 each item was its own dispatch (30 undo steps for 30 clips).
     */
    suspend fun addMediaBatch(repository: MediaRepository, items: List<Pair<Uri, String>>, startUs: Long = state.playheadUs,
        placement: BulkPlacement = BulkPlacement.AT_PLAYHEAD): List<Long> {
        if (items.isEmpty()) return emptyList()
        val generation = projectGeneration
        val resolved = items.map { (uri, name) -> repository.reference(uri).let { (asset, type) -> BulkAdd.Item(asset, type, name) } }
        if (generation != projectGeneration) throw kotlinx.coroutines.CancellationException("Project changed during import")
        val (batch, ids) = BulkAdd.batch(state, resolved, startUs, placement)
        dispatch(batch)
        return ids
    }

    fun addSolid(argb: Int=0xff4477cc.toInt(),durationUs: Long=5_000_000L,startUs: Long=state.playheadUs) {
        val comp=state.project.composition
        dispatch(EditorAction.Add(CompositionLayer(EditorReducer.nextLayerId(state.project),LayerType.SOLID,
            zOrder=(comp.layers.maxOfOrNull { it.zOrder } ?: -1)+1,
            timing=ClipTiming(comp.frameRate.snap(startUs),0,durationUs),name="Solid",solidColorArgb=argb)))
    }
    fun addText(startUs: Long = state.playheadUs) {
        val comp = state.project.composition
        val add = EditorAction.Add(CompositionLayer(EditorReducer.nextLayerId(state.project), LayerType.TEXT,
            zOrder = (comp.layers.maxOfOrNull { it.zOrder } ?: -1) + 1,
            timing = ClipTiming(comp.frameRate.snap(startUs), 0, 5_000_000L), name = "Text",
            text = TextProperties(source = AnimatableString(staticValue = "Text"))))
        val edits = mutableListOf<EditorAction>(add)
        if (comp.durationUs != null) edits += EditorAction.SetComposition(comp.width, comp.height, comp.durationUs, comp.frameRate)
        dispatch(EditorAction.Batch(edits))
    }
    suspend fun relink(repository: MediaRepository,assetId: String,uri: Uri) {
        val generation = projectGeneration
        val (replacement,type)=repository.reference(uri,assetId)
        if (generation != projectGeneration) throw kotlinx.coroutines.CancellationException("Project changed during relink")
        val references=state.project.composition.layers.filter { it.asset?.id==assetId }
        require(references.isNotEmpty() && references.all { it.type==type }) { "Choose media of the same type" }
        // Reducer validation rejects shorter replacements that cannot preserve the existing trim.
        dispatch(EditorAction.Relink(assetId,replacement))
    }
}
