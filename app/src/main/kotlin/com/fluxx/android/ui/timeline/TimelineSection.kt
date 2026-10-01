package com.fluxx.android.ui.timeline

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxx.android.editor.EditorAction
import com.fluxx.android.editor.TimelineEdits
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import com.fluxx.android.ui.theme.*
import kotlin.math.roundToLong

private const val ROW_HEIGHT_DP = 48
private const val HEADER_WIDTH_DP = 130
private const val SECTION_RULER_HEIGHT_DP = 28

@Composable
fun TimelineSection(
    project: ProjectDocument,
    mediaRepository: MediaRepository,
    playheadUs: Long,
    selectedLayerId: Long?,
    onSelectLayer: (Long?) -> Unit,
    onSeek: (Long, Boolean) -> Unit,
    onToggleVisibility: (Long, Boolean) -> Unit,
    onMoveLayer: (Long, towardFront: Boolean) -> Unit,
    onBeginGesture: () -> Unit,
    onPreviewGesture: (EditorAction) -> Unit,
    onCommitGesture: () -> Unit,
    onCancelGesture: () -> Unit,
    onAddMediaClick: () -> Unit,
    onAddSolidClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val comp = project.composition
    val durationUs = comp.resolvedDurationUs

    // Pixels per microsecond zoom factor
    val density = LocalDensity.current
    var pxPerSecond by remember { mutableStateOf(100f) }
    val pxPerUs = (pxPerSecond * density.density) / 1_000_000.0

    // Visual list displays layers from top (frontmost) to bottom (backmost)
    val sortedLayers = remember(comp.layers) {
        comp.layers.sortedWith(compareByDescending<CompositionLayer> { it.zOrder }.thenByDescending { it.id })
    }

    // Shared vertical scroll between layer label header column and clip tracks
    val verticalScrollState = rememberLazyListState()
    // Horizontal scroll for ruler and timeline track area
    val horizontalScrollState = rememberScrollState()

    val totalWidthPx = (durationUs * pxPerUs).toFloat()
    val totalWidthDp = with(density) { totalWidthPx.toDp() }.coerceAtLeast(400.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Background)
    ) {
        // Timeline Header Controls (Add Solid, Add Media, Zoom in/out)
        TimelineToolbar(
            onAddMediaClick = onAddMediaClick,
            onAddSolidClick = onAddSolidClick,
            pxPerSecond = pxPerSecond,
            onZoomChange = { pxPerSecond = it.coerceIn(20f, 600f) }
        )

        // Ruler and Timeline Body
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Left column: Static layer header list (outline glyphs, sentence case)
                LayerHeadersColumn(
                    layers = sortedLayers,
                    playheadUs = playheadUs,
                    selectedLayerId = selectedLayerId,
                    onSelectLayer = onSelectLayer,
                    onToggleVisibility = onToggleVisibility,
                    onMoveLayer = onMoveLayer,
                    scrollState = verticalScrollState,
                    modifier = Modifier.width(HEADER_WIDTH_DP.dp)
                )

                // Divider between headers and timeline tracks
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(Border)
                )

                // Right: Horizontal scrollable tracks area with ruler
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .horizontalScroll(horizontalScrollState)
                ) {
                    Column(modifier = Modifier.width(totalWidthDp)) {
                        // Time ruler
                        TimeRuler(
                            durationUs = durationUs,
                            frameRate = comp.frameRate,
                            pxPerUs = pxPerUs,
                            onSeek = { onSeek(it, false) }
                        )

                        // Clip Rows List (shares vertical scroll with header)
                        LazyColumn(
                            state = verticalScrollState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            items(sortedLayers, key = { it.id }) { layer ->
                                ClipRow(
                                    project = project,
                                    playheadUs = playheadUs,
                                    mediaRepository = mediaRepository,
                                    layer = layer,
                                    isSelected = layer.id == selectedLayerId,
                                    pxPerUs = pxPerUs,
                                    onSelect = { onSelectLayer(layer.id) },
                                    onBeginGesture = onBeginGesture,
                                    onPreviewGesture = onPreviewGesture,
                                    onCommitGesture = onCommitGesture,
                                    onCancelGesture = onCancelGesture
                                )
                            }
                        }
                    }

                    // Pure white Playhead line spanning top to bottom of timeline
                    PlayheadIndicator(
                        playheadUs = playheadUs,
                        pxPerUs = pxPerUs,
                        durationUs = durationUs,
                        onScrub = { onSeek(it, false) }
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Preview(name = "Dark Mode", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun TimelineSectionPreview() {
    val context = LocalContext.current
    FluxxTheme {
        TimelineSection(
            project = ProjectDocument(),
            mediaRepository = MediaRepository(context),
            playheadUs = 1_500_000L,
            selectedLayerId = null,
            onSelectLayer = {},
            onSeek = { _, _ -> },
            onToggleVisibility = { _, _ -> },
            onMoveLayer = { _, _ -> },
            onBeginGesture = {},
            onPreviewGesture = {},
            onCommitGesture = {},
            onCancelGesture = {},
            onAddMediaClick = {},
            onAddSolidClick = {}
        )
    }
}

@Composable
private fun TimelineToolbar(
    onAddMediaClick: () -> Unit,
    onAddSolidClick: () -> Unit,
    pxPerSecond: Float,
    onZoomChange: (Float) -> Unit
) {
    Surface(
        color = Surface,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .border(width = 0.5.dp, color = Border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Add Media Button (outline style at rest, min 44dp target)
                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(SurfaceVariant)
                        .border(1.dp, Border, RoundedCornerShape(4.dp))
                        .clickable { onAddMediaClick() }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("+ Media", color = TextPrimary, style = MaterialTheme.typography.bodySmall)
                }

                // Add Solid Button
                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(SurfaceVariant)
                        .border(1.dp, Border, RoundedCornerShape(4.dp))
                        .clickable { onAddSolidClick() }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("+ Solid", color = TextPrimary, style = MaterialTheme.typography.bodySmall)
                }
            }

            // Zoom In / Out Controls
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable { onZoomChange(pxPerSecond * 0.75f) },
                    contentAlignment = Alignment.Center
                ) {
                    Text("-", color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
                }
                Text("Zoom", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable { onZoomChange(pxPerSecond * 1.33f) },
                    contentAlignment = Alignment.Center
                ) {
                    Text("+", color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun LayerHeadersColumn(
    layers: List<CompositionLayer>,
    playheadUs: Long,
    selectedLayerId: Long?,
    onSelectLayer: (Long?) -> Unit,
    onToggleVisibility: (Long, Boolean) -> Unit,
    onMoveLayer: (Long, towardFront: Boolean) -> Unit,
    scrollState: LazyListState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Surface)
    ) {
        // Space corresponding to the time ruler height
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SECTION_RULER_HEIGHT_DP.dp)
                .background(SurfaceVariant)
                .border(width = 0.5.dp, color = Border)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                "Layers (${layers.size})",
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall
            )
        }

        LazyColumn(
            state = scrollState,
            modifier = Modifier.fillMaxSize()
        ) {
            items(layers, key = { it.id }) { layer ->
                val isSelected = layer.id == selectedLayerId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ROW_HEIGHT_DP.dp)
                        .background(Surface)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) Highlight else Border
                        )
                        .clickable { onSelectLayer(layer.id) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Small distinct outline type glyph
                    val typeGlyph = when (layer.type) {
                        LayerType.VIDEO -> "▷"
                        LayerType.IMAGE -> "□"
                        LayerType.SOLID -> "■"
                        LayerType.TEXT -> "T"
                    }
                    Text(
                        text = typeGlyph,
                        color = if (isSelected) Highlight else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                    )

                    Spacer(Modifier.width(6.dp))

                    // Layer Name in sentence case
                    val displayName = if (layer.type == LayerType.TEXT) layer.text.source.evaluate(playheadUs - layer.resolvedKeyframeAnchorUs)
                        .replace('\n', ' ').ifBlank { "Text" } else layer.name.ifBlank {
                        when (layer.type) {
                            LayerType.VIDEO -> "Video ${layer.id}"
                            LayerType.IMAGE -> "Image ${layer.id}"
                            LayerType.SOLID -> "Solid ${layer.id}"
                            LayerType.TEXT -> "Text ${layer.id}"
                        }
                    }
                    Text(
                        text = displayName,
                        color = if (isSelected) TextPrimary else TextSecondary,
                        style = if (isSelected) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    // Reorder / Eye outline buttons
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (layer.visible) "○" else "—",
                            color = if (layer.visible) (if (isSelected) Highlight else TextSecondary) else BorderFocused,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clickable { onToggleVisibility(layer.id, !layer.visible) }
                                .padding(4.dp)
                        )
                        Text(
                            text = "▲",
                            color = TextSecondary,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .clickable { onMoveLayer(layer.id, true) }
                                .padding(horizontal = 3.dp)
                        )
                        Text(
                            text = "▼",
                            color = TextSecondary,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .clickable { onMoveLayer(layer.id, false) }
                                .padding(horizontal = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeRuler(
    durationUs: Long,
    frameRate: FrameRate,
    pxPerUs: Double,
    onSeek: (Long) -> Unit
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(SECTION_RULER_HEIGHT_DP.dp)
            .background(SurfaceVariant)
            .pointerInput(durationUs, pxPerUs) {
                detectTapGestures { offset ->
                    val targetUs = (offset.x / pxPerUs).toLong().coerceIn(0L, durationUs)
                    onSeek(targetUs)
                }
            }
    ) {
        val totalSecs = (durationUs / 1_000_000L).toInt() + 1
        val stepSec = when {
            pxPerUs > 0.0008 -> 1
            pxPerUs > 0.0002 -> 5
            else -> 10
        }

        for (sec in 0..totalSecs step stepSec) {
            val x = (sec * 1_000_000L * pxPerUs).toFloat()
            if (x > size.width) break

            // Major tick
            drawLine(
                color = TextSecondary,
                start = Offset(x, size.height * 0.55f),
                end = Offset(x, size.height),
                strokeWidth = 1f
            )

            val subSteps = 4
            for (sub in 1 until subSteps) {
                val subX = x + (sub.toFloat() / subSteps) * (stepSec * 1_000_000L * pxPerUs).toFloat()
                if (subX <= size.width) {
                    drawLine(
                        color = Border,
                        start = Offset(subX, size.height * 0.75f),
                        end = Offset(subX, size.height),
                        strokeWidth = 0.5f
                    )
                }
            }
        }

        drawLine(
            color = Border,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1f
        )
    }
}

@Composable
private fun ClipRow(
    project: ProjectDocument,
    playheadUs: Long,
    mediaRepository: MediaRepository,
    layer: CompositionLayer,
    isSelected: Boolean,
    pxPerUs: Double,
    onSelect: () -> Unit,
    onBeginGesture: () -> Unit,
    onPreviewGesture: (EditorAction) -> Unit,
    onCommitGesture: () -> Unit,
    onCancelGesture: () -> Unit
) {
    val timing = layer.timing
    val startUs = timing.startUs
    val durationUs = timing.durationUs ?: 5_000_000L

    val startX = (startUs * pxPerUs).toFloat()
    val widthPx = (durationUs * pxPerUs).toFloat().coerceAtLeast(24f)

    val density = LocalDensity.current
    val startDp = with(density) { startX.toDp() }
    val widthDp = with(density) { widthPx.toDp() }

    // Load decoded thumbnail if video/image asset available
    var thumbnail by remember(layer.asset?.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(layer.asset?.uri) {
        val uri = layer.asset?.uri
        if (uri != null) {
            thumbnail = mediaRepository.thumbnail(uri)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT_DP.dp)
            .border(width = 0.5.dp, color = Border)
    ) {
        // Flat surface color clip, selected state = white 1dp border added
        Box(
            modifier = Modifier
                .offset(x = startDp)
                .width(widthDp)
                .fillMaxHeight()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Surface)
                .border(
                    width = 1.dp,
                    color = if (isSelected) Highlight else Border,
                    shape = RoundedCornerShape(4.dp)
                )
        ) {
            // Real decoded thumbnail where available, rendered as-is (no colored wrapper bar)
            thumbnail?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(4.dp))
                        .padding(horizontal = 16.dp)
                )
            }

            // Left Trim Handle
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(18.dp)
                    .background(if (isSelected) SurfaceVariant else Color.Transparent)
                    .pointerInput(layer.id) {
                        detectDragGestures(
                            onDragStart = { onBeginGesture() },
                            onDragEnd = { onCommitGesture() },
                            onDragCancel = { onCancelGesture() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val deltaUs = (dragAmount.x / pxPerUs).roundToLong()
                                val action = TimelineEdits.trimStart(project, layer, timing.startUs + deltaUs)
                                onPreviewGesture(action)
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("[", color = if (isSelected) Highlight else TextSecondary, fontSize = 12.sp)
            }

            // Center: Drag move gesture
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp)
                    .pointerInput(layer.id) {
                        detectTapGestures { onSelect() }
                    }
                    .pointerInput(layer.id) {
                        detectDragGestures(
                            onDragStart = {
                                onSelect()
                                onBeginGesture()
                            },
                            onDragEnd = { onCommitGesture() },
                            onDragCancel = { onCancelGesture() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val deltaUs = (dragAmount.x / pxPerUs).roundToLong()
                                val action = TimelineEdits.move(project, layer, timing.startUs + deltaUs)
                                onPreviewGesture(action)
                            }
                        )
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                // Outline type glyph + sentence case layer name
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val glyph = when (layer.type) {
                        LayerType.VIDEO -> "▷"
                        LayerType.IMAGE -> "□"
                        LayerType.SOLID -> "■"
                        LayerType.TEXT -> "T"
                    }
                    Text(glyph, color = if (isSelected) Highlight else TextSecondary, fontSize = 11.sp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (layer.type == LayerType.TEXT) layer.text.source.evaluate(playheadUs - layer.resolvedKeyframeAnchorUs)
                            .replace('\n', ' ').ifBlank { "Text" } else layer.name.ifBlank {
                            when (layer.type) {
                                LayerType.VIDEO -> "Video"
                                LayerType.IMAGE -> "Image"
                                LayerType.SOLID -> "Solid"
                                LayerType.TEXT -> "Text"
                            }
                        },
                        color = if (isSelected) TextPrimary else TextSecondary,
                        style = if (isSelected) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Right Trim Handle
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(18.dp)
                    .background(if (isSelected) SurfaceVariant else Color.Transparent)
                    .pointerInput(layer.id) {
                        detectDragGestures(
                            onDragStart = { onBeginGesture() },
                            onDragEnd = { onCommitGesture() },
                            onDragCancel = { onCancelGesture() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val deltaUs = (dragAmount.x / pxPerUs).roundToLong()
                                val action = TimelineEdits.trimEnd(project, layer, (timing.endUs ?: (startUs + durationUs)) + deltaUs)
                                onPreviewGesture(action)
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("]", color = if (isSelected) Highlight else TextSecondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun PlayheadIndicator(
    playheadUs: Long,
    pxPerUs: Double,
    durationUs: Long,
    onScrub: (Long) -> Unit
) {
    val playheadX = (playheadUs * pxPerUs).toFloat()
    val density = LocalDensity.current
    val playheadDp = with(density) { playheadX.toDp() }

    Box(
        modifier = Modifier
            .offset(x = playheadDp - 14.dp)
            .fillMaxHeight()
            .width(28.dp)
            .pointerInput(durationUs, pxPerUs) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val deltaUs = (dragAmount.x / pxPerUs).roundToLong()
                        onScrub((playheadUs + deltaUs).coerceIn(0L, durationUs))
                    }
                )
            }
    ) {
        // Functional highlight: pure white playhead line
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxHeight()
                .width(1.5.dp)
                .background(Highlight)
        )

        // Top scrub flag / handle in pure white
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(12.dp)
                .clip(CircleShape)
                .background(Highlight)
        )
    }
}
