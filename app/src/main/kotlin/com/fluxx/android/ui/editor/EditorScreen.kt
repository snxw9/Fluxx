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
    val displayedState = editor.displayedState
    val project = displayedState.project
    val composition = project.composition
    val playheadUs = displayedState.playheadUs
    val selectedLayer = displayedState.selectedLayer

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
        editor.dispatch(action)
        previewController?.update(editor.state.project, editor.state.playheadUs, false)
    }
    fun fitLayer(mode: Int) {
        if (fitting) return
        val layer = editor.displayedState.selectedLayer ?: return
        val uri = layer.asset?.uri
        if (uri == null) {
            android.widget.Toast.makeText(context, "Source media unavailable", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        previewController?.pause()
        val original = editor.state.project
        val atTime = editor.state.playheadUs
        fitting = true
        scope.launch {
            try {
                val action = withContext(Dispatchers.IO) {
                    val width: Int
                    val height: Int
                    var rotation = 0
                    var pixelAspect = 1f
                    if (layer.type == LayerType.VIDEO) {
                        val source = mediaRepository.video(uri)
                        width = source.width; height = source.height
                        rotation = source.rotation; pixelAspect = source.pixelAspect
                    } else {
                        val bitmap = mediaRepository.image(uri)
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
    var isExpandedPreview by remember { mutableStateOf(true) }
    var viewSettings by remember { mutableStateOf(ViewSettings()) }
    var previewResolution by remember { mutableStateOf(PreviewResolution.FULL) }
    var previewFitScale by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(previewController, previewResolution) {
        previewController?.setPreviewResolution(previewResolution)
    }
    var addContentSheetVisible by remember { mutableStateOf(false) }
    var addContentHeight by remember { mutableStateOf(0.dp) }
    var addContentError by remember { mutableStateOf<String?>(null) }
    var inspectorSheetVisible by remember { mutableStateOf(false) }
    var focusedProperty by remember { mutableStateOf<AnimPropertyType?>(null) }
    var anchorMode by remember { mutableStateOf(false) }
    var eyedropperLayerId by remember { mutableStateOf<Long?>(null) }
    var sampledColor by remember { mutableStateOf<Int?>(null) }
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
        editor.cancelGesture()
        inspectorSheetVisible = false
    }
    fun leaveSelectionOrEditor() {
        focusManager.clearFocus()
        if (eyedropperLayerId != null) { eyedropperLayerId=null; return }
        if (editor.state.selectedLayerId != null) {
            dismissInspector()
            editor.dispatch(EditorAction.Select(null))
        } else onExitClick()
    }
    BackHandler { leaveSelectionOrEditor() }
    LaunchedEffect(selectedLayer?.id, inspectorSheetVisible, previewState.playing) {
        if (!inspectorSheetVisible || selectedLayer?.id != eyedropperLayerId || previewState.playing) eyedropperLayerId=null
    }
    LaunchedEffect(eyedropperLayerId,previewController) {
        sampledColor=null
        val id=eyedropperLayerId ?: return@LaunchedEffect
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
                        // Keep the source frame unchanged throughout the drag. One release = one edit.
                        editor.dispatch(EditorAction.SetColor(id,argb))
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

    LaunchedEffect(previewController, project) {
        previewController?.let { pc ->
            if (!pc.state.value.playing) {
                pc.update(project, playheadUs, false)
            }
        }
    }

    LaunchedEffect(exportState.output) {
        if (exportState.output != null) {
            exportDialogVisible = true
        }
    }

    Scaffold(
        topBar = {
            EditorTopBar(
                projectName = projectName,
                onProjectNameChange = { projectName = it },
                onExitClick = {
                    leaveSelectionOrEditor()
                },
                onCompSettingsClick = { compSettingsVisible = true },
                onExportClick = { exportDialogVisible = true },
                onSaveClick = onSaveProject,
                onLoadClick = onLoadProject,
                selectedLayerId = displayedState.selectedLayerId,
                selectedLayerName = selectedLayer?.name.orEmpty(),
                onLayerNameChange = { name ->
                    selectedLayer?.let { editor.dispatch(EditorAction.Rename(it.id, name)) }
                }
            )
        },
        containerColor = Background
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val isInspectorOpen = inspectorSheetVisible && selectedLayer != null
            // Reserve ruler + track gap + one row + inspector separation in BOTH preview modes.
            // Expansion trades inspector height for canvas height; opening the inspector never moves the preview.
            val available = (maxHeight - EditorActionBarHeight - TimelineInspectorAnchorHeight).coerceAtLeast(0.dp)
            val desiredInspectorHeight = (if (isExpandedPreview) 268.dp else 328.dp) * density.fontScale.coerceAtLeast(1f)
            val previewHeight = (available - desiredInspectorHeight).coerceAtLeast(0.dp)
            val inspectorHeight = available - previewHeight
            // Match the real space below the preview, including the bottom system inset.
            SideEffect { addContentHeight = maxHeight - previewHeight + innerPadding.calculateBottomPadding() }

            Column(modifier = Modifier.fillMaxSize()) {
                // 1. Upper Canvas Preview Area (~50% of vertical space)
                // View-only expansion: never dispatch composition or layer edits here.
                CompositionPreview(
                    mediaRepository = mediaRepository,
                    viewSettings = viewSettings,
                    isExpanded = isExpandedPreview,
                    onFitScaleChanged = { previewFitScale = it },
                    anchorMode = anchorMode && isInspectorOpen,
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
                        // Preview selection must never open the inspector. Keep this callback separate.
                        dismissInspector()
                        editor.dispatch(EditorAction.Select(id))
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
                        editor.undo()
                        previewController?.update(editor.state.project, playheadUs, false)
                    },
                    onRedo = {
                        editor.redo()
                        previewController?.update(editor.state.project, playheadUs, false)
                    },
                    onPrevBoundary = {
                        val prevUs = editor.navigateBoundary(false,
                            inspectorSheetVisible && editor.state.selectedLayerId != null, focusedProperty)
                        previewController?.update(editor.state.project, prevUs, false)
                    },
                    onPlayPauseToggle = {
                        if (previewState.playing) previewController?.pause()
                        else {
                            editor.dispatch(EditorAction.SelectMarker(null))
                            previewController?.play()
                        }
                    },
                    onNextBoundary = {
                        val nextUs = editor.navigateBoundary(true,
                            inspectorSheetVisible && editor.state.selectedLayerId != null, focusedProperty)
                        previewController?.update(editor.state.project, nextUs, false)
                    },
                    onPasteLayer = {
                        focusManager.clearFocus()
                        previewController?.pause()
                        editor.pasteLayer()
                        previewController?.update(editor.state.project, editor.state.playheadUs, false)
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
                    onMarkerAction = { editor.dispatch(it) },
                    onMarkerConfigure = { previewController?.pause() },
                    onMarkerSelect = { owner, marker ->
                        previewController?.pause()
                        dismissInspector()
                        focusedProperty = null
                        editor.dispatch(EditorAction.Select(owner))
                        val layer = editor.state.selectedLayer
                        val time = Math.addExact(layer?.resolvedKeyframeAnchorUs ?: 0L, marker.timeUs)
                        editor.dispatch(EditorAction.Seek(time))
                        editor.dispatch(EditorAction.SelectMarker(marker.id))
                        previewController?.seek(time)
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
                        editor.dispatch(EditorAction.Seek(timeUs, snap))
                        previewController?.seek(timeUs)
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
                                if (it != null) editor.dispatch(EditorAction.SelectMarker(null))
                            }
                        },
                        onAction = { action ->
                            previewController?.pause()
                            editor.dispatch(action)
                            previewController?.update(editor.state.project, playheadUs, false)
                        },
                        onRename = { newName ->
                            editor.dispatch(EditorAction.Rename(layer.id, newName))
                        },
                        onDuplicate = {
                            focusManager.clearFocus()
                            previewController?.pause()
                            editor.duplicateLayer(layer.id)
                            previewController?.update(editor.state.project, editor.state.playheadUs, false)
                        },
                        onCopyLayer = {
                            focusManager.clearFocus()
                            if (editor.copyLayer(layer.id)) {
                                android.widget.Toast.makeText(context, "Layer copied", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDelete = {
                            editor.dispatch(EditorAction.Remove(layer.id))
                            previewController?.update(editor.state.project, playheadUs, false)
                            inspectorSheetVisible = false
                        },
                        onDismiss = { dismissInspector() },
                        onTransformBegin = {
                            previewController?.pause()
                            editor.beginGesture()
                        },
                        onTransformPreview = { t ->
                            editor.editTransform(layer.id, t, preview = true)
                            previewController?.update(editor.displayedState.project, playheadUs, false)
                        },
                        onTransformCommit = {
                            editor.commitGesture()
                            previewController?.update(editor.state.project, playheadUs, false)
                        },
                        onTransformCancel = {
                            editor.cancelGesture()
                            previewController?.update(editor.state.project, playheadUs, false)
                        },
                        onTransformEdit = { transform ->
                            previewController?.pause()
                            editor.editTransform(layer.id, transform)
                            previewController?.update(editor.state.project, playheadUs, false)
                        },
                        onTimingChange = { timing ->
                            editor.dispatch(EditorAction.SetTiming(layer.id, timing))
                            previewController?.update(editor.state.project, playheadUs, false)
                        },
                        onAudioChange = { muted, gain ->
                            editor.dispatch(EditorAction.SetAudio(layer.id, muted, gain))
                            previewController?.update(editor.state.project, playheadUs, false)
                        },
                        onColorChange = { argb ->
                            editor.dispatch(EditorAction.SetColor(layer.id, argb))
                            previewController?.update(editor.state.project, playheadUs, false)
                        },
                        onColorPreview = { argb ->
                            editor.previewGesture(EditorAction.SetColor(layer.id,argb))
                            previewController?.update(editor.displayedState.project,playheadUs,false)
                        },
                        onEyedropperRequest = {
                            focusManager.clearFocus()
                            editor.commitGesture()
                            previewController?.update(editor.state.project,editor.state.playheadUs,false)
                            eyedropperLayerId=layer.id
                            android.widget.Toast.makeText(context,"Drag the crosshair and release to sample; Back cancels",android.widget.Toast.LENGTH_SHORT).show()
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
            onDismiss = { exportDialogVisible = false }
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
