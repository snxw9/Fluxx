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
    private var copiedLayer by mutableStateOf<CompositionLayer?>(null)
    val hasCopiedLayer: Boolean get() = copiedLayer != null

    fun copyLayer(id: Long): Boolean {
        commitGesture()
        val layer = state.project.composition.layers.firstOrNull { it.id == id } ?: return false
        val comp = state.project.composition
        copiedLayer = layer.withReferenceCanvas(comp.width, comp.height)
        return true
    }

    fun duplicateLayer(id: Long) {
        commitGesture()
        val layer = state.project.composition.layers.firstOrNull { it.id == id } ?: return
        insertLayerCopy(layer, layer.timing.startUs, directlyAboveSource = true)
    }

    fun pasteLayer() {
        val layer = copiedLayer ?: return
        commitGesture()
        insertLayerCopy(layer, state.project.composition.frameRate.snap(state.playheadUs))
    }

    private fun insertLayerCopy(source: CompositionLayer, startUs: Long, directlyAboveSource: Boolean = false) {
        val comp = state.project.composition
        val id = EditorReducer.nextLayerId(state.project)
        // A copied media snapshot may outlive a relink. Avoid giving two different references one asset ID.
        val asset = source.asset?.let { snapshot ->
            val current = comp.layers.firstOrNull { it.asset?.id == snapshot.id }?.asset
            if (current != null && current != snapshot) snapshot.copy(id = java.util.UUID.randomUUID().toString()) else snapshot
        }
        val add = EditorAction.Add(source.movedTo(startUs).copy(
            id = id,
            // The fresh ID sorts first among tied z-orders, without risking Int overflow.
            zOrder = comp.layers.maxOfOrNull { it.zOrder } ?: 0,
            name = if (source.name.isNotBlank()) "${source.name} Copy" else "${source.type.name} $id",
            asset = asset
        ))
        val ordered = comp.layers.sortedWith(compareByDescending<CompositionLayer> { it.zOrder }.thenByDescending { it.id })
        val sourceIndex = ordered.indexOfFirst { it.id == source.id }
        if (directlyAboveSource && sourceIndex > 0) {
            // Reorder targets are indices before removal. From the top, moving to the source's
            // predecessor inserts immediately above the source. Batch keeps creation+order atomic.
            dispatch(EditorAction.Batch(listOf(add, EditorAction.ReorderLayer(id, ordered[sourceIndex - 1].id))))
        } else dispatch(add)
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
    }

    fun redo() {
        cancelGesture()
        if (redoStack.isEmpty()) return
        val next = redoStack.removeAt(redoStack.lastIndex)
        undoStack.add(state.historyEntry())
        state = state.copy(project = next.project, selectedLayerId = next.selectedLayerId, selectedMarkerId = null,
            playheadUs = state.playheadUs.coerceAtMost(next.project.composition.resolvedDurationUs))
    }

    fun dispatch(action: EditorAction) {
        cancelGesture()
        val next = EditorReducer.reduce(state, action)
        if (action is EditorAction.Load) {
            projectGeneration++
            copiedLayer = null
            undoStack.clear()
            redoStack.clear()
        } else if (action !is EditorAction.Seek && action !is EditorAction.Select && next.project != state.project) {
            undoStack.add(state.historyEntry())
            redoStack.clear()
        }
        state = next
    }

    /** Resolve and apply transport navigation from one current snapshot, at click time. */
    fun navigateBoundary(next: Boolean, isInspectorOpen: Boolean, activeProperty: AnimPropertyType?): Long {
        commitGesture()
        val current = state
        val target = if (next) ContextualNavigation.findNext(current.project, current.selectedLayer,
            isInspectorOpen, activeProperty, current.playheadUs, current.selectedMarkerId)
        else ContextualNavigation.findPrevious(current.project, current.selectedLayer,
            isInspectorOpen, activeProperty, current.playheadUs, current.selectedMarkerId)
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
        require(action is EditorAction.SetAnchorPoint || action is EditorAction.SetColor || action is EditorAction.ReorderLayer || action is EditorAction.Batch || action is EditorAction.Move || action is EditorAction.Trim || action is EditorAction.SetTransform ||
            action is EditorAction.MoveKeyframe || action is EditorAction.SetKeyframe || action is EditorAction.SetKeyframe2D) { "Unsupported transient edit" }
        transientState=EditorReducer.reduce(base,action)
        gestureAction=action
    }
    fun commitGesture(gestureId: Long? = null) {
        if (gestureId != null && gestureId != activeGestureId) return
        val action=gestureAction
        val base=gestureBase
        val changed = transientState?.project != base?.project
        cancelGesture()
        if(action!=null && base!=null && changed) {
            undoStack.add(base.historyEntry())
            redoStack.clear()
            val reduced = EditorReducer.reduce(base, action)
            val time = state.playheadUs.coerceAtMost(reduced.project.composition.resolvedDurationUs)
            state = reduced.copy(playheadUs = time,
                selectedMarkerId = reduced.selectedMarkerId.takeIf { time == reduced.playheadUs })
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
