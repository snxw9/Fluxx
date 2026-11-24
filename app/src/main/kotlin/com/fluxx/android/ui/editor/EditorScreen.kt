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
    BackHandler { focusManager.clearFocus(); textEditor.finish { leaveSelectionOrEditor() } }
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
                    moveEnabled=selectedLayer?.type==LayerType.TEXT && !textEditor.active,
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
                    onSelectLayer = { id ->
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
                            if (previewState.playing) previewController?.pause()
                            else {
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
                        inspectorSheetVisible = false
                        editor.dispatch(EditorAction.Select(id))
                    },
                    onDismissInspector = { dismissInspector() },
                    onSelectLayer = { id ->
                        // Timeline selection intentionally opens the inspector.
                        editor.dispatch(EditorAction.Select(id))
                        if (id != null) {
                            inspectorSheetVisible = true
                        } else {
                            inspectorSheetVisible = false
                        }
                    },
                    onSeek = { timeUs, snap ->
                        textEditor.finish {
                            editor.dispatch(EditorAction.Seek(timeUs, snap))
                            previewController?.seek(timeUs)
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
                        previewController?.update(editor.state.project, playheadUs, false)
                    },
                    onCancelGesture = {
                        val id = timelineGestureId
                        timelineGestureId = null
                        if (id != null) editor.cancelGesture(id)
                        previewController?.update(editor.state.project, playheadUs, false)
                    },
                    onOpenClipOptions = { id ->
                        editor.dispatch(EditorAction.Select(id))
                        inspectorSheetVisible = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }

            // 4. Layer Inspector Panel (overlay docked at bottom over timeline, preventing workspace resizing)
            AnimatedVisibility(
                visible = isInspectorOpen,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(inspectorHeight)
            ) {
                selectedLayer?.let { layer ->
                    ElementInspectorSheet(
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
                    )
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
