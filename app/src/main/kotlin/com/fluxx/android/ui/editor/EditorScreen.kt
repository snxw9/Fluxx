package com.fluxx.android.ui.editor

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.fluxx.android.editor.*
import com.fluxx.android.export.ExportService
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import com.fluxx.android.render.PreviewController
import com.fluxx.android.render.PreviewState
import com.fluxx.android.ui.browser.AddContentSheet
import com.fluxx.android.ui.dialogs.CompositionSettingsDialog
import com.fluxx.android.ui.dialogs.ExportDialog
import com.fluxx.android.ui.inspector.ElementInspectorSheet
import com.fluxx.android.ui.inspector.InspectorPanel
import com.fluxx.android.ui.inspector.InspectorSheetBudget
import com.fluxx.android.ui.preview.CompositionPreview
import com.fluxx.android.ui.theme.*
import com.fluxx.android.ui.timeline.TimelineView
import com.fluxx.android.ui.timeline.TimelineInspectorAnchorHeight
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.fluxx.android.ui.common.MediaInfoDialog
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.fluxx.android.ui.preview.ViewSettings
import com.fluxx.android.render.PreviewResolution
import com.fluxx.android.render.TextMetricsRepository
import com.fluxx.android.render.TextLayerErrors

private data class PreviewColorRequest(val x: Float,val y: Float,val released: Boolean,val serial: Long)

/**
 * Reconstructed Motion Graphics Editor UI matching the Alight Motion reference screenshot:
 * - Top Bar: Exit arrow, project title, Save, Load, Settings (gear), Export (share)
 * - Canvas Preview: Aspect-ratio letterboxed SurfaceView preview, status badge, divider with '^' toggle
 * - Action Bar: 7-icon horizontal toolbar directly beneath preview (Undo, Redo, Prev, Play/Pause, Next, Layer, View)
 * - Timeline: Fixed pill track column, ruler, stacked clips, playhead with anchored time bubble
 * - Floating Add FAB: Elevated circular container at bottom-right over the timeline
 */
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun EditorScreen(
    editor: EditorViewModel,
    mediaRepository: MediaRepository,
    previewController: PreviewController?,
    previewMessage: String?,
    hasMediaPermission: Boolean,
    isLimitedAccess: Boolean,
    onSurfaceAvailable: (android.view.Surface, Int, Int) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onSurfaceResized: (Int, Int) -> Unit,
    onRequestMediaPermission: () -> Unit,
    onOpenDocumentPicker: () -> Unit,
    onSaveProject: () -> Unit,
    onLoadProject: () -> Unit,
    onExportStart: () -> Unit,
    onExportCancel: () -> Unit,
    onOpenExportedVideo: (Uri) -> Unit,
    onExitClick: () -> Unit = {},
    paletteRepository: PaletteRepository? = null
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var textTyping by remember { mutableStateOf(false) }
    val fontLibrary=remember(context) { com.fluxx.android.fonts.FontLibraryRepository.getInstance(context) }
    val libraryFonts by fontLibrary.state.collectAsState()
    val metricsService = remember(context) { TextMetricsRepository.get(context) }
    val latestPreview by rememberUpdatedState(previewController)
    val textEditor = remember(editor, scope, metricsService) {
        TextEditController(editor, scope, metricsService::measure,
            changed = { latestPreview?.update(editor.displayedState.project, editor.displayedState.playheadUs, false) },
            typingChanged = { textTyping = it },measureWithLigatures=metricsService::measure,measureDocument=metricsService::measure,measureComplete=metricsService::measure)
    }
    DisposableEffect(textEditor) { onDispose { textEditor.cancel() } }
    val textErrors by TextLayerErrors.state.collectAsState()
    val characterErrors by TextLayerErrors.characters.collectAsState()
    // TEXT F3.2: a gesture preview/commit the reducer refused (e.g. a keyframe drag) is shown, never thrown.
    val gestureError = editor.gestureError
    LaunchedEffect(gestureError) {
        if (gestureError != null) { android.widget.Toast.makeText(context, gestureError, android.widget.Toast.LENGTH_SHORT).show(); editor.clearGestureError() }
    }
    val displayedState = editor.displayedState
    val project = displayedState.project
    val composition = project.composition
    val playheadUs = displayedState.playheadUs
    val selectedLayer = displayedState.selectedLayer
    var textSelection by rememberSaveable(selectedLayer?.id,saver=Saver<MutableState<TextSelectionRange?>,IntArray>(
        save={it.value?.let {r->intArrayOf(r.start,r.end)} ?: intArrayOf(-1,-1)},
        restore={mutableStateOf(if(it[0]<0) null else TextSelectionRange(it[0],it[1]))})) { mutableStateOf<TextSelectionRange?>(null) }
    var previewMoveGesture by remember {mutableStateOf<Long?>(null)}
    val selectedTextSource = selectedLayer?.text?.source?.evaluateDocument(playheadUs - selectedLayer.resolvedKeyframeAnchorUs) ?: TextSourceDocument("")
    val selectedLigatures=selectedLayer?.let { it.text.effectiveLigatures(playheadUs-it.resolvedKeyframeAnchorUs) } ?: true
    val selectedTextMetrics by produceState<TextLayoutMetrics?>(null, selectedLayer?.type, selectedLayer?.text?.fontId, selectedTextSource,selectedLigatures,libraryFonts,selectedLayer?.text) {
        value = null
        if (selectedLayer?.type == LayerType.TEXT) try { value = metricsService.measure(selectedLayer.text.fontId, selectedTextSource,selectedLigatures,selectedLayer.text) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { }
    }
    val textCanFit = selectedLayer?.let { layer -> selectedTextMetrics?.bounds(
        layer.text, playheadUs - layer.resolvedKeyframeAnchorUs)?.empty == false } ?: false

    val previewStateFlow = remember(previewController) {
        previewController?.state ?: MutableStateFlow(PreviewState())
    }
    val previewState by previewStateFlow.collectAsState()
    val exportState by ExportService.state.collectAsState()

    var mediaInfoEntry by remember { mutableStateOf<MediaRepository.Entry?>(null) }
    var fitting by remember { mutableStateOf(false) }
    var timelineGestureId by remember { mutableStateOf<Long?>(null) }
    fun applyLayerAction(action: EditorAction) {
        previewController?.pause()
        textEditor.finish {
            editor.dispatch(action)
            previewController?.update(editor.state.project, editor.state.playheadUs, false)
        }
    }
    fun fitLayer(mode: Int) {
        if (fitting) return
        val layer = editor.displayedState.selectedLayer ?: return
        val uri = layer.asset?.uri
        if (uri == null && layer.type != LayerType.TEXT) {
            android.widget.Toast.makeText(context, "Source media unavailable", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        previewController?.pause()
        val original = editor.state.project
        val atTime = editor.state.playheadUs
        fitting = true
        scope.launch {
            try {
                val action = if (layer.type == LayerType.TEXT) {
                    val local = atTime - layer.resolvedKeyframeAnchorUs
                    val metrics = metricsService.measure(layer.text.fontId, layer.text.source.evaluateDocument(local),layer.text.effectiveLigatures(local),layer.text)
                    EditorAction.FitText(layer.id, atTime, metrics.bounds(layer.text, local), mode)
                } else withContext(Dispatchers.IO) {
                    val width: Int
                    val height: Int
                    var rotation = 0
                    var pixelAspect = 1f
                    if (layer.type == LayerType.VIDEO) {
                        val source = mediaRepository.video(requireNotNull(uri))
                        width = source.width; height = source.height
                        rotation = source.rotation; pixelAspect = source.pixelAspect
                    } else {
                        val bitmap = mediaRepository.image(requireNotNull(uri))
                        try { width = bitmap.width; height = bitmap.height } finally { bitmap.recycle() }
                    }
                    require(width > 0 && height > 0)
                    when (mode) {
                        0 -> EditorAction.FitToWidth(layer.id, atTime, width, height, rotation, pixelAspect)
                        1 -> EditorAction.FitToHeight(layer.id, atTime, width, height, rotation, pixelAspect)
                        else -> EditorAction.StretchToComposition(layer.id, atTime, width, height, rotation, pixelAspect)
                    }
                }
                // A metadata read must not apply to a replaced project, another selection, or an active edit.
                if (editor.state.project === original && editor.state.selectedLayerId == layer.id &&
                    editor.state.playheadUs == atTime && editor.transientState == null) applyLayerAction(action)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) {
                android.widget.Toast.makeText(context, "Source geometry unavailable", android.widget.Toast.LENGTH_SHORT).show()
            } finally { fitting = false }
        }
    }
    mediaInfoEntry?.let { entry -> MediaInfoDialog(mediaRepository, entry) { mediaInfoEntry = null } }

    var projectName by remember { mutableStateOf("Project 1") }
    var isExpandedPreview by rememberSaveable { mutableStateOf(true) }
    var viewSettings by remember { mutableStateOf(ViewSettings()) }
    var previewResolution by remember { mutableStateOf(PreviewResolution.FULL) }
    var previewFitScale by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(previewController, previewResolution) {
        previewController?.setPreviewResolution(previewResolution)
    }
    var addContentSheetVisible by remember { mutableStateOf(false) }
    var addContentHeight by remember { mutableStateOf(0.dp) }
    var addContentError by remember { mutableStateOf<String?>(null) }
    var inspectorSheetVisible by rememberSaveable { mutableStateOf(false) }
    var textEditOpen by remember { mutableStateOf(false) }
    val backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    var focusedProperty by remember { mutableStateOf<AnimPropertyType?>(null) }
    // Animate tab (TEXT F3): the focused animator field owns timeline focus and Prev/Next, like focusedProperty.
    var focusedAnimatorTrack by remember { mutableStateOf<AnimatorTrackAddress?>(null) }
    var anchorMode by remember { mutableStateOf(false) }
    var eyedropperLayerId by remember { mutableStateOf<Long?>(null) }
    var sampledColor by remember { mutableStateOf<Int?>(null) }
    var textSampleReply by remember {mutableStateOf<((Int?)->Unit)?>(null)}
    var sampleSerial by remember { mutableLongStateOf(0) }
    val sampleRequests=remember(eyedropperLayerId) { Channel<PreviewColorRequest>(Channel.CONFLATED) }
    DisposableEffect(sampleRequests) { onDispose { sampleRequests.close() } }
    var exportDialogVisible by remember { mutableStateOf(false) }
    var compSettingsVisible by remember { mutableStateOf(false) }
    var relinkingAssetId by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    fun dismissInspector() {
        eyedropperLayerId=null
        focusManager.clearFocus()
        textEditor.finish {
            editor.cancelGesture()
            inspectorSheetVisible = false
        }
    }
    fun leaveSelectionOrEditor() {
        focusManager.clearFocus()
        if (eyedropperLayerId != null) { eyedropperLayerId=null; return }
        if (editor.state.selectedLayerId != null) {
            dismissInspector()
            editor.dispatch(EditorAction.Select(null))
        } else onExitClick()
    }
    /** TL3 B1: scroll requests (layer to the top on expand) and inspector page requests (name long-press). */
    var scrollToLayer by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    var openPage by remember { mutableStateOf<Pair<GraphTarget, Int>?>(null) }
    var openGraph by remember { mutableStateOf<Pair<GraphTarget, Int>?>(null) }
    LaunchedEffect(inspectorSheetVisible) { if (!inspectorSheetVisible) { openPage = null; openGraph = null } }
    // TL3 B2: opening the inspector always collapses every twirl-down (the direct paths do it in the same frame).
    LaunchedEffect(inspectorSheetVisible) { if (inspectorSheetVisible && editor.expandedLayers.isNotEmpty()) editor.setExpanded(emptySet()) }
    var twirlSerial by remember { mutableIntStateOf(0) }
    /**
     * TL3 B1 / B2: the arrow on [id] (in selection mode on a selected layer: every selected layer). Expanding while the
     * inspector sheet is open closes the sheet WHILE the layer expands (one shared motion spec); the layer scrolls to the top.
     */
    fun toggleTwirl(id: Long) {
        val before = editor.expandedLayers
        val next = TwirlExpansion.arrow(before, id, editor.layerSelection)
        val expanding = id in next && id !in before
        if (expanding) { scrollToLayer = id to ++twirlSerial; if (inspectorSheetVisible) dismissInspector() }
        editor.setExpanded(next)
    }
    /** TL3 B2: opening the inspector always collapses every twirl-down (on the same shared transition). */
    fun collapseTwirls() { if (editor.expandedLayers.isNotEmpty()) editor.setExpanded(emptySet()) }
    /** TLP T1: one D-button / body / empty-space effect (rules in [DButtonRules]); multi-layer edits are one Batch. */
    fun selectionEffect(effect: DEffect?, id: Long?) {
        when (effect) {
            null, DEffect.NONE -> {}
            DEffect.TOGGLE_VISIBILITY -> id?.let { layerId -> editor.state.project.composition.layers.firstOrNull { it.id == layerId }?.let {
                editor.dispatch(EditorAction.SetVisibility(layerId, !it.visible))
                previewController?.update(editor.state.project, editor.state.playheadUs, false) } }
            DEffect.BULK_VISIBILITY -> { val sel = editor.layerSelection; if (sel != null && id != null)
                MultiLayerEdits.visibility(editor.state.project, sel, id)?.let { applyLayerAction(it) } }
            DEffect.ENTER -> id?.let { textEditor.finish { dismissInspector(); editor.enterSelection(it) } }
            DEffect.ADD -> { val sel = editor.layerSelection; if (sel != null && id != null) editor.setSelection(sel.add(id)) }
            DEffect.REMOVE -> { val sel = editor.layerSelection; if (sel != null && id != null) {
                DButtonRules.afterRemove(sel, id)?.let { editor.setSelection(it) } ?: editor.exitSelection() } }
            DEffect.EXIT -> editor.exitSelection()
            DEffect.EXPAND -> id?.let { toggleTwirl(it) }
        }
    }
    BackHandler { focusManager.clearFocus(); textEditor.finish { when {
        editor.selectionMode -> editor.exitSelection()
        editor.keySelection != null -> editor.exitKeySelection()
        else -> leaveSelectionOrEditor() } } }
    // TLP.1: the moved selection, set by the timeline just before it commits a group key move; applied after the commit.
    var pendingKeyFollow by remember { mutableStateOf<KeySelectionSet?>(null) }
    // TLP.2 part 5: the live timeline hold-drag (readouts over the action bar); UI only.
    var timelineDrag by remember { mutableStateOf<com.fluxx.android.editor.TimelineDragInfo?>(null) }
    // TLP T5: the active property on the open property page (the sheet's key scope).
    val keyTarget: GraphTarget? = if (!inspectorSheetVisible || selectedLayer == null) null
        else focusedProperty?.let { GraphTarget(property = it) } ?: focusedAnimatorTrack?.let { GraphTarget(animator = it) }
    // TL3 B3 selection invariants (KeySelectionRules): a selected key belongs to an expanded layer or to the inspected
    // layer; a property page narrows to its track; closing the sheet with no twirl-down open ends key mode.
    val inspectedLayerId = if (inspectorSheetVisible) selectedLayer?.id else null
    LaunchedEffect(keyTarget, inspectedLayerId, editor.expandedLayers) {
        val k = editor.keySelection ?: return@LaunchedEffect
        val next = KeySelectionRules.constrain(k, inspectedLayerId, keyTarget, editor.expandedLayers)
        if (next != k) { if (next == null) editor.exitKeySelection() else editor.selectKeys(next) }
    }
    fun applyKeys(moved: KeyEdits.Moved?, target: GraphTarget? = null) {
        if (moved == null) return
        previewController?.pause()
        textEditor.finish {
            val sel = editor.keySelection
            val layerId = editor.state.selectedLayerId
            editor.dispatch(moved.action)
            // The selection follows its keys (a paste selects the pasted keys while key mode is on).
            if (sel != null && layerId != null && target != null) editor.selectKeys(sel.withTrack(KeyframeSelection(layerId, target, moved.times)))
            previewController?.update(editor.state.project, editor.state.playheadUs, false)
        }
    }
    fun applyMultiKeys(moved: MultiKeyEdits.Moved?) {
        if (moved == null) return
        previewController?.pause()
        textEditor.finish {
            editor.dispatch(moved.action)
            editor.selectKeys(moved.selection)
            previewController?.update(editor.state.project, editor.state.playheadUs, false)
        }
    }
    /** TL3 B3: the tracks the key bars act on: the property page's track, or every keyed row of the expanded layers. */
    fun keyScope(): List<Pair<Long, GraphTarget>> {
        val layer = editor.state.selectedLayer
        if (inspectorSheetVisible && layer != null) return keyTarget?.let { listOf(layer.id to it) }.orEmpty()
        val doc = editor.state.project
        return editor.expandedLayers.flatMap { id -> doc.composition.layers.firstOrNull { it.id == id }?.let { l ->
            TwirlRows.rows(l, false).filterIsInstance<TwirlRow.Property>().map { l.id to it.target } }.orEmpty() }
    }
    /**
     * TLP.1: Copy keys / Paste keys, offered by the property page's ⋮ (the shared rail) for that rail's property. Copy
     * takes the selected keys, or every key of the property when none are selected; Paste needs a same-kind clipboard
     * and works with or without a key selection. T5 semantics (one Batch, one undo).
     */
    val keyClipboardHost = com.fluxx.android.ui.inspector.KeyClipboardHost(
        canCopy = { target -> editor.state.selectedLayer?.let { KeyEdits.copyScope(it, target, editor.keySelection?.track(it.id, target)) } != null },
        canPaste = { target -> editor.state.selectedLayer?.let { KeyEdits.canPaste(it, target, editor.keyClipboard) } == true },
        copy = { target ->
            val layer = editor.state.selectedLayer
            val scope = layer?.let { KeyEdits.copyScope(it, target, editor.keySelection?.track(it.id, target)) }
            if (layer != null && scope != null) KeyEdits.copy(layer, scope)?.let { clip -> editor.keyClipboard = clip
                android.widget.Toast.makeText(context, if (clip.keys.size == 1) "Keyframe copied" else "${clip.keys.size} keyframes copied", android.widget.Toast.LENGTH_SHORT).show() }
        },
        paste = { target ->
            val layer = editor.state.selectedLayer; val clip = editor.keyClipboard
            if (layer != null && clip != null) applyKeys(KeyEdits.paste(editor.state.project, layer, target, clip, editor.state.playheadUs), target)
        })
    LaunchedEffect(selectedLayer?.id, inspectorSheetVisible, previewState.playing) {
        if (!inspectorSheetVisible || selectedLayer?.id != eyedropperLayerId || previewState.playing) eyedropperLayerId=null
    }
    LaunchedEffect(eyedropperLayerId,previewController) {
        sampledColor=null
        val id=eyedropperLayerId ?: run {val reply=textSampleReply;textSampleReply=null;reply?.invoke(null);return@LaunchedEffect}
        val pc=previewController ?: return@LaunchedEffect
        val original=editor.state.project
        for(request in sampleRequests) {
            if(editor.state.project!==original || editor.state.selectedLayerId!=id) { eyedropperLayerId=null; break }
            try {
                val argb=pc.sampleColorAt(request.x,request.y)
                if(request.serial!=sampleSerial || eyedropperLayerId!=id) continue
                if(editor.state.project!==original || editor.state.selectedLayerId!=id) { eyedropperLayerId=null; break }
                sampledColor=argb
                if(request.released) {
                    if(argb!=null) {
                        val reply=textSampleReply
                        if(reply!=null) {textSampleReply=null;eyedropperLayerId=null;reply(argb);break}
                        // Keep the source frame unchanged throughout the drag. One release = one edit.
                        val sampledLayer = editor.state.project.composition.layers.first { it.id == id }
                        editor.dispatch(if (sampledLayer.type == LayerType.TEXT) TextEdits.colour(sampledLayer,
                            editor.state.project.composition.frameRate.snap(editor.state.playheadUs),
                            if (focusedProperty == AnimPropertyType.STROKE_COLOUR) AnimPropertyType.STROKE_COLOUR else AnimPropertyType.FILL_COLOUR, argb) else EditorAction.SetColor(id,argb))
                        pc.update(editor.state.project,editor.state.playheadUs,false)
                        eyedropperLayerId=null
                        break
                    } else android.widget.Toast.makeText(context,"Release inside the composition",android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch(e:kotlinx.coroutines.CancellationException) {
                currentCoroutineContext().ensureActive()
                if(request.serial==sampleSerial) sampleRequests.trySend(request)
            } catch(e:Exception) {
                android.widget.Toast.makeText(context,e.message ?: "Colour sampling failed",android.widget.Toast.LENGTH_SHORT).show()
                eyedropperLayerId=null
                break
            }
        }
    }

    LaunchedEffect(previewController, project,libraryFonts) {
        previewController?.let { pc ->
            if (!pc.state.value.playing) {
                pc.update(project, playheadUs, false)
            }
        }
    }

    // TEXT F3.2: the done dialog opens for a NEW completion only (keyed to its completion id), never for a stale output
    // left in the process-global export state when a project is reopened.
    var seenExport by androidx.compose.runtime.saveable.rememberSaveable { mutableLongStateOf(exportState.completion) }
    LaunchedEffect(exportState.completion) {
        if (com.fluxx.android.export.ExportRules.showDone(exportState, seenExport)) exportDialogVisible = true
        if (exportState.completion != 0L) seenExport = exportState.completion
    }

    Scaffold(
        topBar = {
            val layerSel = editor.layerSelection
            if (layerSel != null) {
                // TLP T3: the selection top bar replaces the editor top bar; Back exits selection mode, not the project.
                val all = editor.state.project.composition.layers
                SelectionTopBar(if (layerSel.size == 1) "1 layer selected" else "${layerSel.size} layers selected",
                    onBack = { editor.exitSelection() }) {
                    BarPopoverIcon("GROUP", "Group", "sel-group", "selection-group") { _ ->
                        PopoverRow("Precompose", enabled = false); PopoverRow("Group and mask", enabled = false); PopoverRow("Group and exclude", enabled = false)
                    }
                    BarIcon("SELECT_ALL", "Select all layers", testTag = "selection-select-all") { editor.setSelection(LayerOrder.all(all)) }
                    BarPopoverIcon("ODD_EVEN", "Select odd or even layers", "sel-odd-even", "selection-odd-even") { close ->
                        PopoverRow("Select Odd", testTag = "selection-odd") { editor.setSelection(LayerOrder.odd(editor.state.project.composition.layers)); close() }
                        PopoverRow("Select Even", testTag = "selection-even") { editor.setSelection(LayerOrder.even(editor.state.project.composition.layers)); close() }
                    }
                    BarIcon("DELETE", "Delete the selected layers", testTag = "selection-delete") {
                        MultiLayerEdits.delete(layerSel)?.let { applyLayerAction(it) }
                    }
                }
            } else if (editor.keySelection != null) {
                // TLP T5 / TL3 B3: the key selection top bar ("N keyframes selected · M layers"). Select all / Odd / Even act on
                // the displayed rows (the property page's track, or the expanded layers' keyed rows), counted per track.
                val keySel = editor.keySelection!!
                SelectionTopBar(keySel.title(), onBack = { editor.exitKeySelection() }, testTag = "key-top-bar") {
                    BarIcon("SELECT_ALL", "Select all keyframes", testTag = "key-select-all") { editor.selectKeys(MultiKeyEdits.selectAll(editor.state.project, keyScope())) }
                    BarPopoverIcon("ODD_EVEN", "Select odd or even keyframes", "key-odd-even", "key-odd-even") { close ->
                        PopoverRow("Select Odd", testTag = "key-odd") { editor.selectKeys(MultiKeyEdits.odd(editor.state.project, keyScope())); close() }
                        PopoverRow("Select Even", testTag = "key-even") { editor.selectKeys(MultiKeyEdits.even(editor.state.project, keyScope())); close() }
                    }
                    BarIcon("DELETE", "Delete the selected keyframes", testTag = "key-delete") {
                        MultiKeyEdits.delete(editor.state.project, keySel)?.let { applyLayerAction(it) }
                    }
                }
            } else
            EditorTopBar(
                projectName = projectName,
                onProjectNameChange = { projectName = it },
                onExitClick = {
                    // Edit Text owns Back: the arrow dispatches the same event as system Back, so it leaves the
                    // innermost transient state first and otherwise returns to the layer inspector grid.
                    if (inspectorSheetVisible && textEditOpen && backDispatcher != null) backDispatcher.onBackPressed()
                    else { focusManager.clearFocus(); textEditor.finish { leaveSelectionOrEditor() } }
                },
                backDescription = if (inspectorSheetVisible && textEditOpen) "Back: close the open text control, or return to layer tools" else null,
                onCompSettingsClick = { focusManager.clearFocus(); textEditor.finish { compSettingsVisible = true } },
                onExportClick = { focusManager.clearFocus(); textEditor.finish { exportDialogVisible = true } },
                onSaveClick = { focusManager.clearFocus(); textEditor.finish(onSaveProject) },
                onLoadClick = { focusManager.clearFocus(); textEditor.finish(onLoadProject) },
                selectedLayerId = displayedState.selectedLayerId,
                selectedLayerName = selectedLayer?.name.orEmpty(),
                onLayerNameChange = { name ->
                    selectedLayer?.let { layer -> textEditor.finish { editor.dispatch(EditorAction.Rename(layer.id, name)) } }
                }
            )
        },
        containerColor = Background
    ) { innerPadding ->
        // No imePadding: the keyboard overlays the editor. The window, preview, timeline and inspector never resize
        // for the IME; only the focused popover or inline field lifts above it (see AnchoredPopover).
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val isInspectorOpen = inspectorSheetVisible && selectedLayer != null
            // Reserve ruler + track gap + one row + inspector separation in BOTH preview modes.
            // Expansion trades inspector height for canvas height; opening the inspector never moves the preview.
            val available = (maxHeight - EditorActionBarHeight - TimelineInspectorAnchorHeight).coerceAtLeast(0.dp)
            // One shared height for every inspector page (Edit Text included): the original 268 / 328dp, font-scaled.
            val targetInspectorHeight = InspectorSheetBudget.height(isExpandedPreview, density.fontScale).dp
            val minimumPreview=if(available>=264.dp) 96.dp else 48.dp
            val desiredInspectorHeight=if(isInspectorOpen && selectedLayer?.type==LayerType.TEXT)
                targetInspectorHeight.coerceAtMost((available-minimumPreview).coerceAtLeast(0.dp)) else targetInspectorHeight
            // TEXT PERF 11: short windows (landscape phone, split screen, resized window) kept the full inspector budget
            // and left the canvas no height at all (A16 landscape: 0 dp). Below 560dp of window height the preview keeps at
            // least 45% of the space; portrait phone windows (~780dp+) never reach this branch and are unchanged.
            val shortWindowPreview = if (maxHeight < 560.dp) available * 0.45f else 0.dp
            val previewHeight = (available - desiredInspectorHeight).coerceAtLeast(shortWindowPreview).coerceAtMost(available)
            val inspectorHeight = available - previewHeight
            // Match the real space below the preview, including the bottom system inset.
            SideEffect { addContentHeight = maxHeight - previewHeight + innerPadding.calculateBottomPadding() }

            Column(modifier = Modifier.fillMaxSize()) {
                // 1. Upper Canvas Preview Area (~50% of vertical space)
                // View-only expansion: never dispatch composition or layer edits here.
                CompositionPreview(
                    moveEnabled=selectedLayer?.type==LayerType.TEXT && !textEditor.active && !editor.selectionMode,
                    onMoveBegin={previewController?.pause();editor.beginGesture();previewMoveGesture=editor.activeGestureId},
                    onMovePreview={transform -> if(previewMoveGesture==editor.activeGestureId) selectedLayer?.let {
                        editor.editTransform(it.id,transform,true);previewController?.update(editor.displayedState.project,playheadUs,false)
                    }},
                    onMoveCommit={previewMoveGesture?.let {editor.commitGesture(it)};previewMoveGesture=null},
                    onMoveCancel={previewMoveGesture?.let {editor.cancelGesture(it)};previewMoveGesture=null},
                    textSelection=textSelection,
                    onTextSelection={ range -> focusManager.clearFocus();previewController?.pause();textSelection=range },
                    mediaRepository = mediaRepository,
                    viewSettings = viewSettings,
                    isExpanded = isExpandedPreview,
                    onFitScaleChanged = { previewFitScale = it },
                    anchorMode = anchorMode && isInspectorOpen,
                    boxWidthEditing = isInspectorOpen && selectedLayer?.type==LayerType.TEXT && !textTyping,
                    onBoxBegin = { selectedLayer?.let { previewController?.pause();textEditor.begin(it.id,false) } },
                    onBoxPreview = textEditor::boxWidth,
                    onBoxCommit = { textEditor.finish() },onBoxCancel = textEditor::cancel,
                    eyedropperActive = eyedropperLayerId != null,
                    sampledColorArgb = sampledColor,
                    onSampleColor = { x,y,released ->
                        sampleSerial++
                        sampleRequests.trySend(PreviewColorRequest(x,y,released,sampleSerial))
                    },
                    onSampleCancel = {
                        sampleSerial++
                        sampleRequests.tryReceive()
                        sampledColor=null
                    },
                    project = project,
                    previewController = previewController,
                    previewMessage = previewMessage ?: previewState.message,
                    playheadUs = playheadUs,
                    selectedLayerId = displayedState.selectedLayerId,
                    // TLP.1: a focused animator selector (or one of its Range fields) shows its coverage box.
                    focusedSelector = focusedAnimatorTrack?.takeIf { it.selectorId != null && isInspectorOpen },
                    onSelectLayer = { id ->
                        // TLP T0: the on-preview selector is inert in selection mode (it must not fork the set).
                        if (editor.selectionMode) return@CompositionPreview
                        textSelection=null
                        // Preview selection must never open the inspector. Keep this callback separate.
                        focusManager.clearFocus()
                        textEditor.finish { dismissInspector(); editor.dispatch(EditorAction.Select(id)) }
                    },
                    onSurfaceAvailable = onSurfaceAvailable,
                    onSurfaceDestroyed = onSurfaceDestroyed,
                    onSurfaceResized = onSurfaceResized,
                    onToggleExpanded = { isExpandedPreview = !isExpandedPreview },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(previewHeight)
                )

                // 2. Editor Action Bar (7 actions: Undo, Redo, Prev, Play/Pause, Next, Layer, View)
                Box(Modifier.fillMaxWidth().testTag("editor-action-bar-strip")) {
                EditorActionBar(
                    hasTimelineContent = project.composition.resolvedDurationUs > 0,
                    isPlaying = previewState.playing,
                    canUndo = editor.canUndo,
                    canRedo = editor.canRedo,
                    hasClipboard = editor.hasCopiedLayer,
                    selectedLayer = selectedLayer,
                    textCanFit = textCanFit,
                    onFlipHorizontal = { selectedLayer?.let { applyLayerAction(EditorAction.FlipHorizontal(it.id, editor.state.playheadUs)) } },
                    onFlipVertical = { selectedLayer?.let { applyLayerAction(EditorAction.FlipVertical(it.id, editor.state.playheadUs)) } },
                    onFitToWidth = { fitLayer(0) },
                    onFitToHeight = { fitLayer(1) },
                    onStretchToArea = { fitLayer(2) },
                    onMediaInfo = {
                        selectedLayer?.let { layer ->
                            val uri = layer.asset?.uri
                            if (uri != null) mediaInfoEntry = MediaRepository.Entry(uri, layer.name, layer.type,
                                durationMs = layer.asset.durationUs?.div(1000))
                            else android.widget.Toast.makeText(context, "Source media unavailable", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    onUndo = {
                        textEditor.finish {
                            editor.undo()
                            previewController?.update(editor.state.project, playheadUs, false)
                        }
                    },
                    onRedo = {
                        textEditor.finish {
                            editor.redo()
                            previewController?.update(editor.state.project, playheadUs, false)
                        }
                    },
                    onPrevBoundary = {
                        textEditor.finish {
                            val prevUs = editor.navigateBoundary(false,
                                inspectorSheetVisible && editor.state.selectedLayerId != null, focusedProperty, focusedAnimatorTrack)
                            previewController?.update(editor.state.project, prevUs, false)
                        }
                    },
                    onPlayPauseToggle = {
                        textEditor.finish {
                            if (previewState.playing) {
                                previewController?.pause()
                                // TL1: rest on the frame that was on screen (floor). Read live state (never a captured value);
                                // the seek starts a new controller generation, so older position reports are dropped (no echo).
                                val rate = editor.state.project.composition.frameRate
                                val shown = rate.frameTimeUs(rate.floorFrame(editor.state.playheadUs))
                                if (shown != editor.state.playheadUs) {
                                    editor.dispatch(EditorAction.Seek(shown))
                                    previewController?.seek(editor.state.playheadUs)
                                }
                            } else {
                                editor.dispatch(EditorAction.SelectMarker(null))
                                previewController?.play()
                            }
                        }
                    },
                    onNextBoundary = {
                        textEditor.finish {
                            val nextUs = editor.navigateBoundary(true,
                                inspectorSheetVisible && editor.state.selectedLayerId != null, focusedProperty, focusedAnimatorTrack)
                            previewController?.update(editor.state.project, nextUs, false)
                        }
                    },
                    onPasteLayer = {
                        focusManager.clearFocus()
                        textEditor.finish {
                            previewController?.pause()
                            editor.pasteLayer()
                            previewController?.update(editor.state.project, editor.state.playheadUs, false)
                        }
                    },
                    // TLP T4: the 6th icon is context-aware.
                    selectionMode = editor.selectionMode,
                    onSelectAll = {
                        val all = LayerOrder.all(editor.state.project.composition.layers)
                        if (!all.isEmpty) textEditor.finish {
                            if (!editor.selectionMode) { dismissInspector(); editor.enterSelection(all.ids.first()) }
                            editor.setSelection(all)
                        }
                    },
                    onInvertSelection = { editor.layerSelection?.let { editor.setSelection(LayerOrder.invert(editor.state.project.composition.layers, it)) } },
                    // TL3.1 B4: keys are displayed (twirl-down rows, or the open property page's keys): Select all asks.
                    keysDisplayed = keyScope().any { (id, t) -> editor.state.project.composition.layers.firstOrNull { it.id == id }
                        ?.let { KeyEdits.times(it, t).isNotEmpty() } == true },
                    onSelectAllKeys = { textEditor.finish {
                        if (editor.selectionMode) editor.exitSelection()
                        editor.selectKeys(MultiKeyEdits.selectAll(editor.state.project, keyScope())) } },
                    onDuplicateSelection = { n -> editor.layerSelection?.let { sel ->
                        previewController?.pause(); editor.duplicateLayers(sel.ids, n)
                        previewController?.update(editor.state.project, editor.state.playheadUs, false) } },
                    onCopySelection = { editor.layerSelection?.let { sel -> if (editor.copyLayers(sel.ids))
                        android.widget.Toast.makeText(context, if (sel.size == 1) "Layer copied" else "${sel.size} layers copied", android.widget.Toast.LENGTH_SHORT).show() } },
                    viewSettings = viewSettings,
                    onViewSettingsChange = { viewSettings = it },
                    previewResolution = previewResolution,
                    onPreviewResolutionChange = {
                        previewController?.setPreviewResolution(it)
                        previewResolution = it
                    },
                    previewFitScale = previewFitScale,
                    modifier = Modifier.fillMaxWidth()
                )
                // TLP.2 part 5: the drag readouts over the action bar's strip while a timeline hold-drag runs (no layout change).
                timelineDrag?.let { DragReadoutOverlay(it, project.composition.startTimecodeUs, project.composition.frameRate, Modifier.matchParentSize()) }
                }

                // 3. Lower Timeline Workspace (unconditionally weight(1f) to prevent kick on sheet toggle)
                TimelineView(
                    project = project,
                    mediaRepository = mediaRepository,
                    playheadUs = playheadUs,
                    selectedLayerId = displayedState.selectedLayerId,
                    inspectorVisible = isInspectorOpen,
                    activeProperty = focusedProperty.takeIf { isInspectorOpen },
                    activeAnimatorTrack = focusedAnimatorTrack.takeIf { isInspectorOpen },
                    onMarkerAction = { editor.dispatch(it) },
                    onMarkerConfigure = { previewController?.pause() },
                    onMarkerSelect = { owner, marker ->
                        // TEXT F3.3: tapping or creating a marker never closes the inspector or changes its page / focus
                        // (it used to call dismissInspector, so adding a marker closed the sheet). Only a marker on a
                        // different layer moves the selection there.
                        previewController?.pause()
                        textEditor.finish {
                            val target = com.fluxx.android.ui.timeline.MarkerEditing.selectionAfterMarker(editor.state.selectedLayerId, owner)
                            if (target != editor.state.selectedLayerId) {
                                editor.cancelGesture()
                                focusedProperty = null
                                focusedAnimatorTrack = null
                                editor.dispatch(EditorAction.Select(target))
                            }
                            val anchor = if (owner == null) 0L else editor.state.project.composition.layers.firstOrNull { it.id == owner }?.resolvedKeyframeAnchorUs ?: 0L
                            val time = Math.addExact(anchor, marker.timeUs)
                            editor.dispatch(EditorAction.Seek(time))
                            editor.dispatch(EditorAction.SelectMarker(marker.id))
                            previewController?.seek(time)
                        }
                    },
                    onDragSelect = { id ->
                        // TLP: in selection mode a hold-drag never changes the selection.
                        if (!editor.selectionMode) {
                            inspectorSheetVisible = false
                            editor.dispatch(EditorAction.Select(id))
                        }
                    },
                    onDismissInspector = { dismissInspector() },
                    selection = editor.layerSelection,
                    // TLP.2: only the layer selection bar overlays the timeline (the key bar is in the sheet header).
                    extraBottomPadding = if (editor.selectionMode || (editor.keySelection != null && !isInspectorOpen)) SelectionBarHeight else 0.dp,
                    selectedKeys = editor.keySelection?.let { s -> val l = selectedLayer; val t = keyTarget
                        if (l != null && t != null) s.timesOf(l.id, t) else emptySet() },
                    onDragInfo = { timelineDrag = it },
                    onKeysMoved = { moved -> val l = editor.state.selectedLayer; val t = keyTarget
                        pendingKeyFollow = editor.keySelection?.let { s -> if (l == null || t == null) s else s.track(l.id, t)?.let { tr ->
                            s.withTrack(tr.copy(times = tr.times.map { moved[it] ?: it })) } ?: s } },
                    onKeyDoubleTap = { t -> val layer = editor.state.selectedLayer; val target = keyTarget
                        if (layer != null && target != null && t in KeyEdits.times(layer, target)) editor.selectKeys(KeyframeSelection(layer.id, target, listOf(t))) },
                    onKeyToggle = { t -> val l = editor.state.selectedLayer; val target = keyTarget
                        if (l != null && target != null) editor.keySelection?.let { sel -> val next = sel.toggle(l.id, target, t)
                            if (next == null) editor.exitKeySelection() else editor.selectKeys(next) } },
                    onDTap = { id, zone -> selectionEffect(DButtonRules.tap(editor.selectionMode, editor.layerSelection?.contains(id) == true, zone), id) },
                    onDLongPress = { id -> selectionEffect(DButtonRules.longPress(editor.selectionMode, editor.layerSelection?.contains(id) == true), id) },
                    onSelectLayer = { id ->
                        if (editor.keySelection != null && !(id != null && !inspectorSheetVisible && editor.expandedLayers.isNotEmpty())) {
                            // TLP T5: a tap off the keys (body, empty space) deselects all keys and exits key selection mode;
                            // nothing seeks, scrolls or changes the layer selection.
                            editor.exitKeySelection()
                        } else if (editor.selectionMode) {
                            // TLP T1: a body tap adds / removes; an empty-space tap (id == null) deselects all and exits.
                            // Neither dispatches a Seek or scrolls, and the inspector stays closed.
                            selectionEffect(if (id == null) DButtonRules.empty(true) else DButtonRules.body(true, editor.layerSelection?.contains(id) == true), id)
                        } else {
                        // Timeline selection intentionally opens the inspector. TL3 B2: it collapses every twirl-down WHILE the
                        // sheet slides up (shared motion); key mode stays on and keeps that layer's keys (KeySelectionRules).
                        editor.dispatch(EditorAction.Select(id))
                        if (id != null) {
                            collapseTwirls()
                            inspectorSheetVisible = true
                        } else {
                            inspectorSheetVisible = false
                        }
                        }
                    },
                    expandedLayers = editor.expandedLayers,
                    keySelection = editor.keySelection,

                    scrollToLayer = scrollToLayer,
                    // TL3 B3: twirl-row keys: tap / hold / drag, the value popover and bulk value edits (one undo each).
                    twirlHost = com.fluxx.android.ui.timeline.TwirlKeyHost(
                        onEffect = { id, target, t, effect -> when (effect) {
                            KeyGestureEffect.SELECT -> editor.selectKeys(KeySelectionSet.of(KeyframeSelection(id, target, listOf(t))))
                            KeyGestureEffect.TOGGLE, KeyGestureEffect.ADD -> {
                                // TL3.1 A1: toggling the LAST selected key exits key mode (it used to fall back to selecting it again).
                                val sel = editor.keySelection
                                val next = if (sel != null) sel.toggle(id, target, t) else KeySelectionSet.of(KeyframeSelection(id, target, listOf(t)))
                                if (next == null) editor.exitKeySelection() else editor.selectKeys(next)
                            }
                            else -> {}
                        } },
                        onMoved = { pendingKeyFollow = it },
                        onValue = { scope, axis, v -> MultiKeyEdits.setValue(editor.state.project, scope, axis, v)?.let { applyLayerAction(it) } },
                        // TL3 B4: add a colour key on the bar, keeping its look (exact easing split; one undo).
                        onAddColourKey = { id, target, local -> editor.state.project.composition.layers.firstOrNull { it.id == id }
                            ?.let { ColourKeyAdd.action(it, target, local) }?.let { applyLayerAction(it) } }),
                    onTwirlNameTap = { id, target -> editor.state.project.composition.layers.firstOrNull { it.id == id }?.let { l ->
                        // TL3 B1: select every key of the property (all selected already: deselect them; none left exits).
                        val all = KeyEdits.selectAll(l, target)
                        val sel = editor.keySelection
                        val current = sel?.track(id, target)
                        if (current != null && current.times.toSet() == all.times.toSet()) editor.selectKeys(sel.withTrack(all.copy(times = emptyList())))
                        else editor.selectKeys(sel?.withTrack(all) ?: KeySelectionSet.of(all))
                    } },
                    // TL3.1 B7: a key at the playhead that keeps the motion (exact easing split); one undo; no seek.
                    onTwirlAddKey = { id, target -> editor.state.project.composition.layers.firstOrNull { it.id == id }
                        ?.let { KeyAdd.action(editor.state.project, it, target, editor.state.playheadUs) }?.let { applyLayerAction(it) } },
                    onTwirlGraph = { id, target ->
                        // TL3.1 B8: the name cell's graph icon: the sheet opens on that property's Graph page (collapsing every
                        // twirl-down on the shared transition; the key selection narrows to the property, as on any page).
                        textEditor.finish {
                            editor.dispatch(EditorAction.Select(id))
                            collapseTwirls()
                            openGraph = target to ++twirlSerial
                            focusedProperty = target.property; focusedAnimatorTrack = target.animator
                            inspectorSheetVisible = true
                        }
                    },
                    onTwirlNameLongPress = { id, target ->
                        // TL3 B1: open that property's inspector page (collapses every twirl-down; the sheet opens on the page).
                        textEditor.finish {
                            editor.dispatch(EditorAction.Select(id))
                            collapseTwirls()
                            openPage = target to ++twirlSerial
                            focusedProperty = target.property; focusedAnimatorTrack = target.animator
                            inspectorSheetVisible = true
                        }
                    },
                    onSeek = { timeUs, snap ->
                        textEditor.finish {
                            editor.dispatch(EditorAction.Seek(timeUs, snap))
                            // TL1: the preview gets the time the reducer kept (frame-snapped when [snap]), never the raw one.
                            previewController?.seek(editor.state.playheadUs)
                        }
                    },
                    onToggleVisibility = { id, vis ->
                        editor.dispatch(EditorAction.SetVisibility(id, vis))
                        previewController?.update(editor.state.project, playheadUs, false)
                    },
                    onBeginGesture = {
                        previewController?.pause()
                        editor.beginGesture()
                        timelineGestureId = editor.activeGestureId
                    },
                    onPreviewGesture = { action ->
                        timelineGestureId?.let { id ->
                            editor.previewGesture(action, id)
                            previewController?.update(editor.displayedState.project, playheadUs, false)
                        }
                    },
                    onCommitGesture = {
                        val id = timelineGestureId
                        timelineGestureId = null
                        if (id != null) editor.commitGesture(id)
                        // TLP.1: the key selection follows a group key move (commit prunes it first).
                        pendingKeyFollow?.let { editor.selectKeys(it) }; pendingKeyFollow = null
                        previewController?.update(editor.state.project, playheadUs, false)
                    },
                    onCancelGesture = {
                        val id = timelineGestureId
                        timelineGestureId = null
                        if (id != null) editor.cancelGesture(id)
                        pendingKeyFollow = null
                        previewController?.update(editor.state.project, playheadUs, false)
                    },
                    onOpenClipOptions = { id -> if (editor.selectionMode) return@TimelineView
                        editor.dispatch(EditorAction.Select(id))
                        inspectorSheetVisible = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }

            // TLP.2 part 3: the key bar lives in the inspector sheet's HEADER while key selection is active (LocalKeyHeaderBar,
            // provided around the sheet below); it replaces the header's contents at the header's height. Nothing under the sheet.
            // TL3 B3: over a selection that can span tracks and layers: easing on every selected key's departing segment;
            // Stagger / Sequence per track (each anchored on its own first selected key). One undo each.
            val keyHeaderBar: (@Composable RowScope.() -> Unit)? = editor.keySelection?.let { keySel ->
                val bar: @Composable RowScope.() -> Unit = {
                    val easable = MultiKeyEdits.easable(keySel)
                    fun ease(c: EasingCurve) { MultiKeyEdits.ease(editor.state.project, keySel, c)?.let { applyLayerAction(it) } }
                    BarIcon("EASE_HOLD", "Hold", enabled = easable, testTag = "key-hold") { ease(EasingCurve.HOLD) }
                    BarIcon("EASE_LINEAR", "Linear", enabled = easable, testTag = "key-linear") { ease(EasingCurve.LINEAR) }
                    BarIcon("EASE_EASY", "Easy Ease", enabled = easable, testTag = "key-easy-ease") { ease(EasingCurve.EASY_EASE) }
                    // TLP.3 A1: a non-modal curve popover (the library's grid cells); it stays open after a tap, like the library.
                    BarPopoverIcon("GRAPH_LIBRARY", "Curves", "key-more", "key-more", enabled = easable) { _ ->
                        val library by remember(context) { CurveLibraryStore.get(context) }.state.collectAsState()
                        com.fluxx.android.ui.inspector.KeyCurveGrid(library) { ease(it) }
                    }
                    BarPopoverIcon("STAGGER", "Stagger the selected keyframes", "key-stagger", "key-stagger", enabled = keySel.size > 1) { close ->
                        FramesPopoverContent("Stagger", "frames", 2, false, {}, { n ->
                            applyMultiKeys(MultiKeyEdits.pack(editor.state.project, keySel, n)) }, close, "key-stagger", showReverse = false, minimum = 1)
                    }
                    BarIcon("SEQUENCE", "Sequence the selected keyframes one frame apart", enabled = keySel.size > 1, testTag = "key-sequence") {
                        applyMultiKeys(MultiKeyEdits.pack(editor.state.project, keySel, 1))
                    }
                }
                bar
            }

            // TL3 B3: with no sheet (twirl-downs) the key bar is a bottom overlay above the timeline with the layer bar's rules
            // (the top bar's size, no timeline resize, extra bottom scroll padding). It slides on the shared motion spec while the
            // sheet takes over (its header / landing name row then shows the bar), so key mode stays on through the transition.
            var lastKeyBar by remember { mutableStateOf<(@Composable RowScope.() -> Unit)?>(null) }
            if (keyHeaderBar != null) lastKeyBar = keyHeaderBar
            AnimatedVisibility(visible = keyHeaderBar != null && !isInspectorOpen && !editor.selectionMode,
                enter = slideInVertically(com.fluxx.android.ui.timeline.TL3Motion.spec(), initialOffsetY = { it }) + fadeIn(com.fluxx.android.ui.timeline.TL3Motion.spec()),
                exit = slideOutVertically(com.fluxx.android.ui.timeline.TL3Motion.spec(), targetOffsetY = { it }) + fadeOut(com.fluxx.android.ui.timeline.TL3Motion.spec()),
                modifier = Modifier.align(Alignment.BottomCenter)) {
                lastKeyBar?.let { bar -> SelectionBottomBar(testTag = "key-bottom-bar", content = bar) }
            }

            // TLP T3: the selection bottom bar overlays the timeline (it never resizes it; the timeline has the same bottom
            // scroll padding). The primary layer's trim / move set at the playhead, then Stagger and Sequence (T4).
            editor.layerSelection?.let { layerSel ->
                SelectionBottomBar(Modifier.align(Alignment.BottomCenter)) {
                    val doc = editor.state.project
                    for (kind in MultiLayerEdits.quickSet(doc, layerSel, editor.state.playheadUs)) {
                        val applied = MultiLayerEdits.quick(kind, doc, layerSel, editor.state.playheadUs)
                        BarIcon(kind.name, kind.label, enabled = applied.action != null, testTag = "selection-quick-${kind.name}") {
                            val now = MultiLayerEdits.quick(kind, editor.state.project, layerSel, editor.state.playheadUs)
                            now.action?.let { applyLayerAction(it) }
                            if (now.skipped > 0) android.widget.Toast.makeText(context, "Skipped ${now.skipped} layer${if (now.skipped == 1) "" else "s"}",
                                android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    // TLP.1: each popover's Reverse is remembered inside its content, which leaves composition when the
                    // popover closes, so every open starts from the default (Stagger ON, Sequence OFF); never persisted.
                    BarPopoverIcon("STAGGER", "Stagger the selected layers", "sel-stagger", "selection-stagger", enabled = layerSel.size > 1) { close ->
                        var reverse by remember { mutableStateOf(MultiLayerEdits.STAGGER_REVERSE_DEFAULT) }
                        FramesPopoverContent("Stagger", "frames", 2, reverse, { reverse = !reverse }, { n ->
                            MultiLayerEdits.stagger(editor.state.project, layerSel, n, reverse)?.let { applyLayerAction(it) } }, close, "selection-stagger")
                    }
                    BarPopoverIcon("SEQUENCE", "Sequence the selected layers end to end", "sel-sequence", "selection-sequence", enabled = layerSel.size > 1) { close ->
                        var reverse by remember { mutableStateOf(MultiLayerEdits.SEQUENCE_REVERSE_DEFAULT) }
                        FramesPopoverContent("Gap", "frames", 0, reverse, { reverse = !reverse }, { n ->
                            MultiLayerEdits.sequence(editor.state.project, layerSel, n, reverse)?.let { applyLayerAction(it) } }, close, "selection-sequence", allowNegative = true)
                    }
                }
            }

            // 4. Layer Inspector Panel (overlay docked at bottom over timeline, preventing workspace resizing)
            AnimatedVisibility(
                visible = isInspectorOpen,
                // TL3 B2: the sheet slides on the SAME motion spec as the twirl-downs, the D and the timeline viewport.
                enter = slideInVertically(com.fluxx.android.ui.timeline.TL3Motion.spec(), initialOffsetY = { it }) + fadeIn(com.fluxx.android.ui.timeline.TL3Motion.spec()),
                exit = slideOutVertically(com.fluxx.android.ui.timeline.TL3Motion.spec(), targetOffsetY = { it }) + fadeOut(com.fluxx.android.ui.timeline.TL3Motion.spec()),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(inspectorHeight)
            ) {
                selectedLayer?.let { layer ->
                    androidx.compose.runtime.CompositionLocalProvider(com.fluxx.android.ui.inspector.LocalKeyClipboardHost provides keyClipboardHost, LocalKeyHeaderBar provides keyHeaderBar,
                        com.fluxx.android.ui.inspector.LocalTransformOpenMode provides com.fluxx.android.ui.inspector.InspectorOpenPage.mode(openPage?.first)) { ElementInspectorSheet(
                        openPage = openPage, openGraph = openGraph,
                        onExplodePrepare={options,reply->
                            focusManager.clearFocus();previewController?.pause()
                            textEditor.finish {
                                val captured=editor.state
                                scope.launch {
                                    try {
                                        val source=requireNotNull(captured.selectedLayer)
                                        val local=Math.subtractExact(TextExplode.sampleTime(source,captured.playheadUs),source.resolvedKeyframeAnchorUs)
                                        val plan=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                            TextExplode.prepare(source,captured.project.composition,captured.playheadUs,
                                                EditorReducer.nextLayerId(captured.project),options,
                                                com.fluxx.android.render.TextJoiningPreparation.words(source.text.source.evaluateDocument(local))) {text->metricsService.geometry(text,local)}
                                        }
                                        require(editor.state.project===captured.project && editor.state.selectedLayerId==source.id) {"The project changed. Prepare Explode Text again."}
                                        reply(Result.success(plan))
                                    } catch(cancelled: kotlinx.coroutines.CancellationException) {throw cancelled}
                                    catch(failure: Exception) {reply(Result.failure(failure))}
                                }
                            }
                        },
                        onExplodeCommit={plan,reply->textEditor.finish {
                            try {
                                editor.dispatch(EditorAction.ExplodeText(plan));textSelection=null
                                previewController?.update(editor.state.project,editor.state.playheadUs,false);reply(null)
                            } catch(failure: IllegalArgumentException) {reply(failure.message ?: "Explode failed")}
                        }},
                        textSelection=textSelection,onTextSelection={ range ->
                            textSelection=if(!textEditor.typing && range!=null && !range.collapsed) selectedTextMetrics?.geometry(layer.text,playheadUs-layer.resolvedKeyframeAnchorUs)
                                ?.safeRange(range.start,range.end) ?: range else range
                        },
                        textTyping = textTyping,
                        textError = textEditor.error ?: textErrors?.takeIf { it.project === project && layer.id in it.layerIds }?.message ?: characterErrors[layer.id],
                        onTextBegin = { previewController?.pause(); textEditor.begin(layer.id) },
                        onTextDraft = textEditor::source,
                        onTextDraftSelection = textEditor::sourceSelection,
                        onTextFinish = { textEditor.finish() },
                        onTextCancel = textEditor::cancel,
                        onTextStyle = { textEditor.edit(layer.id, it) },
                        onTextCharacter = { start,end,change -> previewController?.pause();textEditor.format(layer.id,start,end,change) },
                        onTextParagraph = { start,end,change -> previewController?.pause();textEditor.paragraph(layer.id,start,end,change) },
                        onTextBoxWidth = { width -> previewController?.pause();textEditor.editBoxWidth(layer.id,width) },
                        onTextSizeBegin = { previewController?.pause()
                            if (textEditor.typing) textEditor.finish { textEditor.begin(layer.id, false) } else textEditor.begin(layer.id, false) },
                        onTextSizePreview = { value ->
                            if (textEditor.typing) textEditor.finish { textEditor.begin(layer.id, false); textEditor.size(value) } else textEditor.size(value) },
                        onTextSizeCommit = { if (textEditor.typing) textEditor.finish { textEditor.finish() } else textEditor.finish() },
                        onTextScalarPreview = { property, value ->
                            val base = editor.state.project.composition.layers.first { it.id == layer.id }
                            textEditor.preview(TextEdits.scalar(base, composition.frameRate.snap(playheadUs), property, value))
                        },
                        onTextPayloadPreview = textEditor::preview,
                        textFrameRate = composition.frameRate,
                        compositionStartTimecodeUs = composition.startTimecodeUs,
                        textFinishing = textEditor.finishing,
                        onTextSampleColour = {reply->focusManager.clearFocus();previewController?.pause();textSampleReply=reply;eyedropperLayerId=layer.id},
                        onTextCancelSample = {textSampleReply=null;eyedropperLayerId=null;sampledColor=null},
                        onTextSourceSelection = {value,range->textSelection=if(value==selectedTextSource.text && !range.collapsed)
                            selectedTextMetrics?.geometry(layer.text,playheadUs-layer.resolvedKeyframeAnchorUs)?.safeRange(range.start,range.end) ?: range else range},
                        textStrokeClamp = runCatching {selectedTextMetrics?.strokeClampLabel(layer.text,playheadUs-layer.resolvedKeyframeAnchorUs,textSelection?.start ?: 0,textSelection?.end ?: 0)}.getOrNull(),
                        onTextBeginDraft = {typing,ready->
                            previewController?.pause()
                            textEditor.finish {
                                val fresh=editor.state.project.composition.layers.firstOrNull {it.id==layer.id}
                                if(fresh!=null && editor.state.selectedLayerId==layer.id) {textEditor.begin(layer.id,typing);ready(fresh)}
                            }
                        },
                        onTextColourChange = { property, argb -> textEditor.finish {
                            val base = editor.state.project.composition.layers.first { it.id == layer.id }
                            editor.dispatch(TextEdits.colour(base, composition.frameRate.snap(playheadUs), property, argb))
                            previewController?.update(editor.state.project, playheadUs, false)
                        } },
                        onTextColourPreview = { property, argb -> textEditor.finish {
                            val base = editor.state.project.composition.layers.first { it.id == layer.id }
                            editor.previewGesture(EditorAction.Batch(listOf(TextEdits.colour(base, composition.frameRate.snap(playheadUs), property, argb))))
                            previewController?.update(editor.displayedState.project, playheadUs, false)
                        } },
                        mediaRepository = mediaRepository,
                        onAnchorModeChange = { anchorMode = it },
                        onAnchorPreview = { action ->
                            editor.editAnchor(action.id, action.anchorX, action.anchorY, action.newPosition, preview = true)
                            previewController?.update(editor.displayedState.project, playheadUs, false)
                        },
                        paletteRepository = paletteRepository,
                        layer = layer,
                        compositionWidth = editor.state.project.composition.width,
                        compositionHeight = editor.state.project.composition.height,
                        compositionDurationUs = editor.state.project.composition.durationUs,
                        playheadUs = playheadUs,
                        onPropertyFocus = {
                            // Inspector reports focus from SideEffect; only a real focus transition
                            // should clear selection/cancel gestures, never every recomposition.
                            if (inspectorSheetVisible && focusedProperty != it) {
                                focusedProperty = it
                                if (it != null && !textEditor.active) editor.dispatch(EditorAction.SelectMarker(null))
                            }
                        },
                        onAnimatorFocus = {
                            if (focusedAnimatorTrack != it) {
                                focusedAnimatorTrack = it.takeIf { inspectorSheetVisible }
                                if (it != null && !textEditor.active) editor.dispatch(EditorAction.SelectMarker(null))
                            }
                        },
                        onAction = { action ->
                            previewController?.pause()
                            textEditor.finish {
                                editor.dispatch(action)
                                previewController?.update(editor.state.project, editor.state.playheadUs, false)
                            }
                        },
                        onRename = { newName ->
                            textEditor.finish { editor.dispatch(EditorAction.Rename(layer.id, newName)) }
                        },
                        onDuplicate = {
                            focusManager.clearFocus()
                            previewController?.pause()
                            textEditor.finish { editor.duplicateLayer(layer.id)
                                previewController?.update(editor.state.project, editor.state.playheadUs, false) }
                        },
                        onCopyLayer = {
                            focusManager.clearFocus()
                            textEditor.finish { if (editor.copyLayer(layer.id)) {
                                android.widget.Toast.makeText(context, "Layer copied", android.widget.Toast.LENGTH_SHORT).show()
                            } }
                        },
                        onDelete = {
                            focusManager.clearFocus()
                            textEditor.finish { editor.dispatch(EditorAction.Remove(layer.id))
                            previewController?.update(editor.state.project, playheadUs, false)
                            inspectorSheetVisible = false }
                        },
                        onDismiss = { dismissInspector() },
                        onTransformBegin = {
                            previewController?.pause()
                            textEditor.finish { editor.beginGesture() }
                        },
                        onTransformPreview = { t ->
                            textEditor.finish { editor.editTransform(layer.id, t, preview = true)
                            previewController?.update(editor.displayedState.project, playheadUs, false)
                            }
                        },
                        // GRAPH G1.1: read the editor's playhead when the callback runs, never a value captured at composition
                        // (a stale capture made the preview controller echo an old time back as the playhead).
                        onTransformCommit = {
                            textEditor.finish { editor.commitGesture()
                            previewController?.update(editor.state.project, editor.state.playheadUs, false)
                            }
                        },
                        onTransformCancel = {
                            textEditor.cancel()
                            editor.cancelGesture()
                            previewController?.update(editor.state.project, editor.state.playheadUs, false)
                        },
                        onTransformEdit = { transform ->
                            previewController?.pause()
                            textEditor.finish { editor.editTransform(layer.id, transform)
                            previewController?.update(editor.state.project, playheadUs, false)
                            }
                        },
                        onTimingChange = { timing ->
                            applyLayerAction(EditorAction.SetTiming(layer.id, timing))
                        },
                        onAudioChange = { muted, gain ->
                            editor.dispatch(EditorAction.SetAudio(layer.id, muted, gain))
                            previewController?.update(editor.state.project, playheadUs, false)
                        },
                        onColorChange = { argb ->
                            textEditor.finish { editor.dispatch(if (layer.type == LayerType.TEXT) TextEdits.fill(
                                editor.state.project.composition.layers.first { it.id == layer.id }, composition.frameRate.snap(playheadUs), argb)
                                else EditorAction.SetColor(layer.id, argb))
                            previewController?.update(editor.state.project, playheadUs, false)
                            }
                        },
                        onColorPreview = { argb ->
                            textEditor.finish {
                            val originalLayer = editor.state.project.composition.layers.first { it.id == layer.id }
                            val action = if (layer.type == LayerType.TEXT) TextEdits.fill(originalLayer, composition.frameRate.snap(editor.state.playheadUs), argb)
                                else EditorAction.SetColor(layer.id,argb)
                            editor.previewGesture(EditorAction.Batch(listOf(action)))
                            previewController?.update(editor.displayedState.project,playheadUs,false)
                            }
                        },
                        onEyedropperRequest = {
                            focusManager.clearFocus()
                            textEditor.finish {
                            editor.commitGesture()
                            previewController?.update(editor.state.project,editor.state.playheadUs,false)
                            eyedropperLayerId=layer.id
                            android.widget.Toast.makeText(context,"Drag the crosshair and release to sample; Back cancels",android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        onTextEditVisible = { textEditOpen = it },
                        // GRAPH G1: Graph page drags preview SetKeyEasing inside the existing begin/commit/cancel gesture.
                        onGesturePreview = { action ->
                            textEditor.finish { editor.previewGesture(action)
                            previewController?.update(editor.displayedState.project, editor.displayedState.playheadUs, false)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    ) }
                }
            }

            // 5. Floating Add FAB at bottom-right over the timeline (hidden when inspector is open)
            if (!inspectorSheetVisible) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 20.dp, bottom = 20.dp)
                        .size(54.dp)
                        .shadow(elevation = 6.dp, shape = CircleShape)
                        .clip(CircleShape)
                        .background(SurfaceHigh)
                        .border(width = 1.dp, color = Highlight, shape = CircleShape)
                        .clickable {
                            editor.commitGesture()
                            relinkingAssetId = null
                            addContentError = null
                            previewController?.pause()
                            addContentSheetVisible = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+",
                        color = TextPrimary,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }

    // One in-app browser for compact browsing, expanded preview and media import.
    if (addContentSheetVisible) {
        ModalBottomSheet(
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            dragHandle = null,
            contentWindowInsets = { WindowInsets(0) },
            onDismissRequest = {
                addContentSheetVisible = false
                addContentError = null
            },
            containerColor = Surface
        ) {
            AddContentSheet(
                paletteRepository = paletteRepository,
                modifier = Modifier.height(addContentHeight).navigationBarsPadding(),
                repository = mediaRepository,
                hasPermission = hasMediaPermission,
                isLimitedAccess = isLimitedAccess,
                errorMessage = addContentError,
                onRequestPermission = onRequestMediaPermission,
                allowMultiSelect = relinkingAssetId == null,
                onSelectMedia = { uri, name ->
                    val assetIdToRelink = relinkingAssetId
                    if (assetIdToRelink != null) {
                        editor.relink(mediaRepository, assetIdToRelink, uri)
                    } else {
                        editor.addMedia(mediaRepository, uri, name, playheadUs)
                    }
                    previewController?.update(editor.state.project, playheadUs, false)
                },
                onImportFinished = {
                    addContentSheetVisible = false
                    addContentError = null
                },
                onAddSolid = { argb ->
                    try {
                        editor.addSolid(argb = argb, startUs = playheadUs)
                        previewController?.update(editor.state.project, playheadUs, false)
                        addContentSheetVisible = false
                        addContentError = null
                    } catch (e: Exception) {
                        addContentError = e.message ?: "Failed to add solid"
                    }
                },
                onAddText = {
                    editor.addText(playheadUs)
                    previewController?.update(editor.state.project, editor.state.playheadUs, false)
                    addContentSheetVisible = false
                    inspectorSheetVisible = true
                },
                onDismiss = {
                    addContentSheetVisible = false
                    addContentError = null
                }
            )
        }
    }



    // Export Dialog
    if (exportDialogVisible) {
        ExportDialog(
            exportState = exportState,
            onStartExport = {
                onExportStart()
            },
            onCancelExport = onExportCancel,
            onOpenExportedVideo = onOpenExportedVideo,
            onDismiss = {
                exportDialogVisible = false
                // TEXT F3.2: dismissing a finished export returns the dialog to its Start state.
                if (!exportState.running) com.fluxx.android.export.ExportService.reset()
            }
        )
    }

    // Composition Settings Dialog
    if (compSettingsVisible) {
        CompositionSettingsDialog(
            composition = composition,
            onSaveSettings = { w, h, durUs, rate, start ->
                editor.dispatch(EditorAction.SetComposition(w, h, durUs, rate, start))
                previewController?.update(editor.state.project, playheadUs, false)
            },
            onDismiss = { compSettingsVisible = false }
        )
    }
}

@UnstableApi
@Preview(showBackground = true)
@Preview(name = "Dark Mode", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun EditorScreenPreview() {
    val context = LocalContext.current
    val editor = remember { EditorViewModel() }
    val repository = remember { MediaRepository(context) }
    FluxxTheme {
        EditorScreen(
            editor = editor,
            mediaRepository = repository,
            previewController = null,
            previewMessage = null,
            hasMediaPermission = true,
            isLimitedAccess = false,
            onSurfaceAvailable = { _, _, _ -> },
            onSurfaceDestroyed = {},
            onSurfaceResized = { _, _ -> },
            onRequestMediaPermission = {},
            onOpenDocumentPicker = {},
            onSaveProject = {},
            onLoadProject = {},
            onExportStart = {},
            onExportCancel = {},
            onOpenExportedVideo = {}
        )
    }
}
