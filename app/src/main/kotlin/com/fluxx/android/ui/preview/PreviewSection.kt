package com.fluxx.android.ui.preview

import android.content.res.Configuration
import android.view.SurfaceHolder
import android.view.SurfaceView
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.fluxx.android.model.FrameRate
import com.fluxx.android.model.ProjectDocument
import com.fluxx.android.render.PreviewController
import com.fluxx.android.ui.common.TimeFormat
import com.fluxx.android.ui.theme.*

@Composable
fun PreviewSection(
    project: ProjectDocument,
    playheadUs: Long,
    previewController: PreviewController?,
    previewMessage: String?,
    isPlaying: Boolean,
    onSurfaceAvailable: (android.view.Surface, Int, Int) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onSurfaceResized: (Int, Int) -> Unit,
    onPlayPauseToggle: () -> Unit,
    onPreviousFrame: () -> Unit,
    onNextFrame: () -> Unit,
    onReturnToStart: () -> Unit,
    onFitComp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val comp = project.composition
    val compAspect = comp.width.toFloat() / comp.height.toFloat()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Background),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Main canvas viewport with letterboxing / pillarboxing
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            // Container with composition aspect ratio
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .aspectRatio(compAspect, matchHeightConstraintsFirst = true)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black)
                    .border(1.dp, Border, RoundedCornerShape(4.dp))
            ) {
                // SurfaceView hosting Vulkan rendering
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        SurfaceView(context).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    onSurfaceAvailable(holder.surface, width, height)
                                }

                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                                    onSurfaceResized(w, h)
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    onSurfaceDestroyed()
                                }
                            })
                        }
                    }
                )

                // Empty state overlay if composition has no layers
                if (comp.layers.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.75f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "Empty composition",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${comp.width} × ${comp.height} • ${com.fluxx.android.model.CompositionTimecode.frameRateLabel(comp.frameRate)} fps",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Tap + to add media or solid",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }

                // Error or status indicator overlay (e.g. "Preparing audio" or recoverable limitation)
                previewMessage?.let { msg ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Surface)
                            .border(1.dp, Border, RoundedCornerShape(4.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = msg,
                            color = TextPrimary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // Composition bounds badge
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        "${comp.width}×${comp.height}",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Transport Controls Bar
        TransportBar(
            playheadUs = Math.addExact(comp.startTimecodeUs, playheadUs),
            durationUs = comp.endTimecodeUs,
            frameRate = comp.frameRate,
            isPlaying = isPlaying,
            onPlayPauseToggle = onPlayPauseToggle,
            onPreviousFrame = onPreviousFrame,
            onNextFrame = onNextFrame,
            onReturnToStart = onReturnToStart,
            onFitComp = onFitComp
        )
    }
}

@Preview(showBackground = true)
@Preview(name = "Dark Mode", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun PreviewSectionPreview() {
    FluxxTheme {
        PreviewSection(
            project = ProjectDocument(),
            playheadUs = 2_000_000L,
            previewController = null,
            previewMessage = null,
            isPlaying = false,
            onSurfaceAvailable = { _, _, _ -> },
            onSurfaceDestroyed = {},
            onSurfaceResized = { _, _ -> },
            onPlayPauseToggle = {},
            onPreviousFrame = {},
            onNextFrame = {},
            onReturnToStart = {},
            onFitComp = {}
        )
    }
}

@Composable
fun TransportBar(
    playheadUs: Long,
    durationUs: Long,
    frameRate: com.fluxx.android.model.FrameRate,
    isPlaying: Boolean,
    onPlayPauseToggle: () -> Unit,
    onPreviousFrame: () -> Unit,
    onNextFrame: () -> Unit,
    onReturnToStart: () -> Unit,
    onFitComp: () -> Unit
) {
    Surface(
        color = Surface,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .border(width = 0.5.dp, color = Border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Timecode readout
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = TimeFormat.formatTimecode(playheadUs, frameRate),
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = " / " + TimeFormat.formatTimecode(durationUs, frameRate),
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Center: Minimal monochrome playback controls (minimum 44dp touch targets)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Jump to Start button
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable { onReturnToStart() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("|<", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }

                // Step 1 Frame Back
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable { onPreviousFrame() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("<", color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
                }

                // Play / Pause main button: Outline style at rest, filled white when active
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (isPlaying) Highlight else SurfaceVariant)
                        .border(1.dp, if (isPlaying) Highlight else Border, CircleShape)
                        .clickable { onPlayPauseToggle() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (isPlaying) "||" else "▶",
                        color = if (isPlaying) Color.Black else TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Step 1 Frame Forward
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable { onNextFrame() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(">", color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
                }
            }

            // Right: Fit viewport button
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(SurfaceVariant)
                    .border(1.dp, Border, RoundedCornerShape(4.dp))
                    .clickable { onFitComp() }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Fit", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Preview(showBackground = true)
@Preview(name = "Dark Mode", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun TransportBarPreview() {
    FluxxTheme {
        TransportBar(
            playheadUs = 3_000_000L,
            durationUs = 10_000_000L,
            frameRate = FrameRate(),
            isPlaying = true,
            onPlayPauseToggle = {},
            onPreviousFrame = {},
            onNextFrame = {},
            onReturnToStart = {},
            onFitComp = {}
        )
    }
}
