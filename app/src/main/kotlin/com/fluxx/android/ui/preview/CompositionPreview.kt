package com.fluxx.android.ui.preview

import android.content.res.Configuration
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import com.fluxx.android.media.MediaRepository
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import com.fluxx.android.model.CompositionLayer
import com.fluxx.android.model.ProjectDocument
import com.fluxx.android.model.LayerType
import com.fluxx.android.model.TextLayoutMetrics
import com.fluxx.android.render.TextMetricsRepository
import com.fluxx.android.render.LayerGeometry
import com.fluxx.android.render.PreviewController
import com.fluxx.android.ui.theme.*

@Composable
fun CompositionPreview(
    project: ProjectDocument,
    previewController: PreviewController?,
    previewMessage: String?,
    playheadUs: Long = 0L,
    selectedLayerId: Long? = null,
    /** TLP.1: the focused Animate-tab address (a selector in it shows the selector coverage box). */
    focusedSelector: com.fluxx.android.editor.AnimatorTrackAddress? = null,
    onSelectLayer: ((Long?) -> Unit)? = null,
    onSurfaceAvailable: (android.view.Surface, Int, Int) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onSurfaceResized: (Int, Int) -> Unit,
    onToggleExpanded: () -> Unit,
    eyedropperActive: Boolean = false,
    onSampleColor: (Float, Float, Boolean) -> Unit = { _, _, _ -> },
    onSampleCancel: () -> Unit = {},
    sampledColorArgb: Int? = null,
    modifier: Modifier = Modifier,
    anchorMode: Boolean = false,
    isExpanded: Boolean = true,
    viewSettings: ViewSettings = ViewSettings(),
    mediaRepository: MediaRepository? = null,
    onFitScaleChanged: (Float) -> Unit = {},
    boxWidthEditing: Boolean = false,onBoxBegin: ()->Unit = {},onBoxPreview: (Float)->Unit = {},
    onBoxCommit: ()->Unit = {},onBoxCancel: ()->Unit = {},
    textSelection: com.fluxx.android.model.TextSelectionRange?=null,
    onTextSelection: (com.fluxx.android.model.TextSelectionRange?)->Unit = {},
    moveEnabled: Boolean=false,onMoveBegin: ()->Unit = {},onMovePreview: (com.fluxx.android.model.Transform)->Unit = {},
    onMoveCommit: ()->Unit = {},onMoveCancel: ()->Unit = {}
) {
    val density = LocalDensity.current
    val comp = project.composition
    val context = LocalContext.current
    val fontLibrary=remember(context) { com.fluxx.android.fonts.FontLibraryRepository.getInstance(context) }
    val libraryFonts by fontLibrary.state.collectAsState()
    val textRequests = comp.layers.filter { it.type == LayerType.TEXT && it.visible && it.timing.isActive(playheadUs) }
          .associate { val local=playheadUs-it.resolvedKeyframeAnchorUs;it.id to Triple(it.text.fontId,it.text.source.evaluateDocument(local),it.text.effectiveLigatures(local)) }
    val textHitMetrics by produceState<Map<Long, TextLayoutMetrics>>(emptyMap(), textRequests,libraryFonts,comp.layers.map { it.text }) {
        val result = mutableMapOf<Long, TextLayoutMetrics>()
        for ((id, request) in textRequests) try { result[id] = TextMetricsRepository.get(context).measure(request.first, request.second,request.third,comp.layers.first { it.id==id }.text) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { }
        value = result
    }
    val compAspect = comp.width.toFloat() / comp.height.toFloat()

    val currentOnSurfaceAvailable by rememberUpdatedState(onSurfaceAvailable)
    val currentOnSurfaceResized by rememberUpdatedState(onSurfaceResized)
    val currentOnSurfaceDestroyed by rememberUpdatedState(onSurfaceDestroyed)
    val sample by rememberUpdatedState(onSampleColor)
    val cancelSample by rememberUpdatedState(onSampleCancel)
    val movingLayer by rememberUpdatedState(comp.layers.firstOrNull {it.id==selectedLayerId})
    val movingTime by rememberUpdatedState(playheadUs)
    val moveBegin by rememberUpdatedState(onMoveBegin);val movePreview by rememberUpdatedState(onMovePreview)
    val moveCommit by rememberUpdatedState(onMoveCommit);val moveCancel by rememberUpdatedState(onMoveCancel)
    val currentSelection by rememberUpdatedState(textSelection)
    var samplePoint by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Background)
            // TEXT F3.2: a tap in the preview closes non-modal slider popovers (observed on the Initial pass, never consumed).
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(false, PointerEventPass.Initial)
                    val up = waitForUpOrCancellation(PointerEventPass.Initial)
                    if (up != null && (up.position - down.position).getDistance() < viewConfiguration.touchSlop)
                        com.fluxx.android.ui.inspector.PopoverDismissal.request()
                }
            }
    ) {
        // Main canvas workspace with letterboxing exactly like the Alight Motion reference
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds()
                .background(Background)
                .pointerInput(Unit) {
                    detectTapGestures {
                        onSelectLayer?.invoke(null)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val containerWidth = maxWidth
            val containerHeight = maxHeight
            val containerAspect = containerWidth.value / containerHeight.value.coerceAtLeast(1f)

            val (boxWidth, boxHeight) = if (compAspect > containerAspect) {
                // Wider than container (e.g. 16:9): constrained by container width
                containerWidth to (containerWidth / compAspect)
            } else {
                // Taller than container (e.g. 9:16): constrained by container height
                (containerHeight * compAspect) to containerHeight
            }

            val fitScale = with(density) { minOf(boxWidth.toPx() / comp.width, boxHeight.toPx() / comp.height) }.coerceAtLeast(.00001f)
            SideEffect { onFitScaleChanged(fitScale) }
            val viewportScale = viewSettings.viewportScale(fitScale)
            // Scale the stable full-workspace surface and composition overlays together.
            // Surface layout/buffer dimensions do not change when only zoom changes.
            Box(Modifier.fillMaxSize().graphicsLayer {
                scaleX = viewportScale; scaleY = viewportScale
                translationX = viewSettings.panX; translationY = viewSettings.panY
            }, contentAlignment = Alignment.Center) {
                // The native surface belongs to the workspace, not the composition-shaped box.
                // Reshaping a SurfaceView stretches its last buffer before the asynchronous
                // producer catches up. Vulkan alone fits the composition into this stable surface.
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        // SurfaceView owns a separate surface. Crop it explicitly as well as clipping
                        // Compose, so zoomed pixels cannot cover the toolbar or inspector.
                        val w = with(density) { containerWidth.toPx() }
                        val h = with(density) { containerHeight.toPx() }
                        val (left, top) = viewSettings.unzoom(0f, 0f, w, h, fitScale)
                        val (right, bottom) = viewSettings.unzoom(w, h, w, h, fitScale)
                        view.clipBounds = android.graphics.Rect(
                            kotlin.math.ceil(left.coerceIn(0f, w)).toInt(), kotlin.math.ceil(top.coerceIn(0f, h)).toInt(),
                            kotlin.math.floor(right.coerceIn(0f, w)).toInt(), kotlin.math.floor(bottom.coerceIn(0f, h)).toInt())
                    },
                    factory = { context ->
                        SurfaceView(context).apply {
                            setZOrderMediaOverlay(true)
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    val w = if (width > 0) width else 1
                                    val h = if (height > 0) height else 1
                                    currentOnSurfaceAvailable(holder.surface, w, h)
                                }

                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                                    if (w > 0 && h > 0) {
                                        currentOnSurfaceResized(w, h)
                                    }
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    currentOnSurfaceDestroyed()
                                }
                            })
                        }
                    }
                )

                // Mask the workspace outside the composition without resizing the native surface.
                Canvas(Modifier.fillMaxSize()) {
                    val canvasWidth = boxWidth.toPx()
                    val canvasHeight = boxHeight.toPx()
                    val left = (size.width - canvasWidth) / 2f
                    val top = (size.height - canvasHeight) / 2f
                    val mask = androidx.compose.ui.graphics.Path().apply {
                        fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                        addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                        addRect(androidx.compose.ui.geometry.Rect(left, top, left + canvasWidth, top + canvasHeight))
                    }
                    drawPath(mask, Background)
                }

                // Only overlays follow the composition bounds. Layer pixels are already fitted
                // uniformly by VulkanRenderer::render, including while paused after a resize.
                Box(
                    modifier = Modifier
                        .size(boxWidth, boxHeight)
                        .clip(RoundedCornerShape(2.dp))
                        .pointerInput(selectedLayerId,moveEnabled,eyedropperActive) {
                            if(moveEnabled && !eyedropperActive) {
                                var initial: com.fluxx.android.model.Transform?=null;var dx=0f;var dy=0f
                                detectDragGestures(onDragStart={
                                    initial=movingLayer?.evaluatedTransform(movingTime);dx=0f;dy=0f
                                    if(initial!=null) moveBegin()
                                },onDragEnd={if(initial!=null) moveCommit();initial=null},
                                    onDragCancel={if(initial!=null) moveCancel();initial=null}) {pointer,amount ->
                                    val layer=movingLayer;val base=initial
                                    if(layer!=null && base!=null) {
                                        pointer.consume();dx+=amount.x;dy+=amount.y
                                        movePreview(base.copy(positionX=base.positionX+dx*2/size.width*comp.width/(layer.referenceWidth.takeIf {it>0} ?: comp.width),
                                            positionY=base.positionY+dy*2/size.height*comp.height/(layer.referenceHeight.takeIf {it>0} ?: comp.height)))
                                    }
                                }
                            }
                        }
                        .pointerInput(comp.layers, playheadUs, selectedLayerId, textHitMetrics) {
                            fun selectText(point: androidx.compose.ui.geometry.Offset,word: Boolean) {
                                val layer=comp.layers.firstOrNull { it.id==selectedLayerId && it.type==LayerType.TEXT } ?: return
                                val metrics=textHitMetrics[layer.id] ?: return
                                val local=playheadUs-layer.resolvedKeyframeAnchorUs
                                val geometry=metrics.geometry(layer.text,local) ?: return
                                val m=FloatArray(16)
                                LayerGeometry.textMatrix(m,layer.evaluatedTransform(playheadUs),metrics.logical(layer.text,local),comp.width,comp.height,
                                    layer.referenceWidth.takeIf {it>0} ?: comp.width,layer.referenceHeight.takeIf {it>0} ?: comp.height,layer.anchorX,layer.anchorY)
                                val determinant=m[0]*m[5]-m[1]*m[4];if(kotlin.math.abs(determinant)<1e-12f) return
                                val x=point.x*2/size.width-1-m[12];val y=point.y*2/size.height-1-m[13]
                                val hit=geometry.hit((x*m[5]-y*m[4])/determinant,(y*m[0]-x*m[1])/determinant)
                                if(hit>=0) onTextSelection(geometry.range(hit,word))
                            }
                            detectTapGestures(onLongPress={selectText(it,false)},onDoubleTap={selectText(it,true)},onTap={ tap ->
                                if(currentSelection!=null) { onTextSelection(null);return@detectTapGestures }
                                if (onSelectLayer != null) {
                                    // Find active visible layers at current playhead position
                                    val activeLayers = comp.layers.filter { layer ->
                                        val start = layer.timing.startUs
                                        val dur = layer.timing.durationUs
                                        val active = layer.visible && playheadUs >= start && (dur == null || playheadUs < start + dur)
                                        if (!active || layer.type != LayerType.TEXT) active else {
                                            val metrics = textHitMetrics[layer.id]
                                            if (metrics == null) false else {
                                                val textSize = layer.text.sizeAt(playheadUs - layer.resolvedKeyframeAnchorUs)
                                                val bounds = metrics.bounds(layer.text, playheadUs - layer.resolvedKeyframeAnchorUs)
                                                val matrix = FloatArray(16)
                                                LayerGeometry.textMatrix(matrix, layer.evaluatedTransform(playheadUs), metrics.logical(layer.text, playheadUs - layer.resolvedKeyframeAnchorUs), comp.width, comp.height,
                                                    layer.referenceWidth.takeIf { it > 0 } ?: comp.width, layer.referenceHeight.takeIf { it > 0 } ?: comp.height,
                                                    layer.anchorX, layer.anchorY)
                                                val x = tap.x * 2f / size.width - 1f; val y = tap.y * 2f / size.height - 1f
                                                if (bounds.empty) kotlin.math.abs(x - matrix[12]) <= 14.dp.toPx() / size.width &&
                                                    kotlin.math.abs(y - matrix[13]) <= 14.dp.toPx() / size.height
                                                else LayerGeometry.textContains(matrix, bounds, x, y)
                                            }
                                        }
                                    }.sortedWith(compareByDescending<CompositionLayer> { it.zOrder }.thenByDescending { it.id })

                                    if (activeLayers.isNotEmpty()) {
                                        val currentIndex = activeLayers.indexOfFirst { it.id == selectedLayerId }
                                        val nextLayerId = when {
                                            currentIndex == -1 -> activeLayers.first().id
                                            currentIndex in 0 until activeLayers.lastIndex -> activeLayers[currentIndex + 1].id
                                            else -> null
                                        }
                                        onSelectLayer(nextLayerId)
                                    } else {
                                        onSelectLayer(null)
                                    }
                                }
                            })
                        }
                ) {
                    if (!eyedropperActive) PreviewLayerOverlay(project,
                        comp.layers.firstOrNull { it.id == selectedLayerId }, playheadUs,
                        mediaRepository, viewSettings, anchorMode, focusedSelector)
                    val selectedText=comp.layers.firstOrNull { it.id==selectedLayerId && it.type==LayerType.TEXT }
                    val selectedMetrics=selectedText?.let { textHitMetrics[it.id] }
                    if(!eyedropperActive && selectedText!=null && selectedMetrics!=null && textSelection!=null && !textSelection.collapsed)
                        TextSelectionOverlay(project,selectedText,playheadUs,selectedMetrics,textSelection,onTextSelection)
                    if(boxWidthEditing && (textSelection==null || textSelection.collapsed) && !eyedropperActive && selectedText!=null && selectedMetrics!=null)
                        TextBoxWidthHandle(project,selectedText,playheadUs,selectedMetrics,onBoxBegin,onBoxPreview,onBoxCommit,onBoxCancel)
                    // Empty state overlay if composition has no layers
                    if (comp.layers.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.8f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "Empty project",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${comp.width} × ${comp.height} • ${com.fluxx.android.model.CompositionTimecode.frameRateLabel(comp.frameRate)} fps",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Tap + to add media or solid",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }

                }
            }
            previewMessage?.let { msg ->
                Surface(modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                    shape = RoundedCornerShape(4.dp), color = com.fluxx.android.ui.theme.Surface) {
                    Text(msg, Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        color = TextPrimary, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (eyedropperActive) Canvas(Modifier.fillMaxSize().pointerInput(viewSettings, fitScale) {
                awaitEachGesture {
                    val down=awaitFirstDown(); down.consume()
                    var point=down.position
                    var complete=false
                    fun update(release: Boolean) {
                        samplePoint=point
                        val (x, y) = viewSettings.unzoom(point.x, point.y, size.width.toFloat(), size.height.toFloat(), fitScale)
                        sample(x/size.width, y/size.height, release)
                    }
                    try {
                        update(false)
                        val released=drag(down.id) { it.consume(); point=it.position; update(false) }
                        complete=true
                        if(released) update(true) else cancelSample()
                    } finally { if(!complete) cancelSample() }
                }
            }) {
                val p=samplePoint ?: center
                sampledColorArgb?.let { drawCircle(Color(it),7.dp.toPx(),p) }
                drawCircle(Color.White,10.dp.toPx(),p,style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                drawLine(Color.White,p-androidx.compose.ui.geometry.Offset(15.dp.toPx(),0f),p+androidx.compose.ui.geometry.Offset(15.dp.toPx(),0f),1.dp.toPx())
                drawLine(Color.White,p-androidx.compose.ui.geometry.Offset(0f,15.dp.toPx()),p+androidx.compose.ui.geometry.Offset(0f,15.dp.toPx()),1.dp.toPx())
            }
            if (!eyedropperActive) PreviewExpansionButton(isExpanded, onToggleExpanded,
                Modifier.align(Alignment.BottomEnd))
        }

        // Expansion now belongs to the viewport; the divider is decorative only.
        Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
    }
}

@Preview(showBackground = true)
@Preview(name = "Dark Mode", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun CompositionPreviewPreview() {
    FluxxTheme {
        CompositionPreview(
            project = ProjectDocument(),
            previewController = null,
            previewMessage = "Preview Ready",
            playheadUs = 1_000_000L,
            onSurfaceAvailable = { _, _, _ -> },
            onSurfaceDestroyed = {},
            onSurfaceResized = { _, _ -> },
            onToggleExpanded = {}
        )
    }
}
