package com.fluxx.android.ui.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import com.fluxx.android.render.LayerGeometry
import com.fluxx.android.ui.theme.Border
import com.fluxx.android.ui.theme.Highlight
import com.fluxx.android.ui.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class OverlaySource(val width: Int, val height: Int, val rotation: Int = 0, val pixelAspect: Float = 1f)

@Composable
internal fun PreviewLayerOverlay(project: ProjectDocument, selected: CompositionLayer?, playheadUs: Long,
    repository: MediaRepository?, settings: ViewSettings, anchorMode: Boolean) {
    val comp = project.composition
    val source by produceState<OverlaySource?>(null, selected?.type, selected?.asset, repository) {
        value = null
        val uri = selected?.asset?.uri
        if (selected != null && uri != null && repository != null && (selected.type == LayerType.VIDEO || selected.type == LayerType.IMAGE)) {
            value = withContext(Dispatchers.IO) {
                try {
                    if (selected.type == LayerType.VIDEO) repository.video(uri).let { OverlaySource(it.width, it.height, it.rotation, it.pixelAspect) }
                    else repository.image(uri).let { bitmap ->
                        try { OverlaySource(bitmap.width, bitmap.height) } finally { bitmap.recycle() }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            }
        }
    }
    val matrix = remember { FloatArray(16) }
    Canvas(Modifier.fillMaxSize()) {
        if (settings.showGrid) {
            val gridColor = Border.copy(alpha = .35f)
            for (i in 1..7) {
                drawLine(gridColor, Offset(size.width * i / 8, 0f), Offset(size.width * i / 8, size.height), 1f)
                drawLine(gridColor, Offset(0f, size.height * i / 8), Offset(size.width, size.height * i / 8), 1f)
            }
            for (i in 1..2) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                drawLine(TextSecondary.copy(alpha = .4f), Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), 1f, pathEffect = dash)
                drawLine(TextSecondary.copy(alpha = .4f), Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1f, pathEffect = dash)
            }
        }
        val layer = selected?.takeIf { it.visible && it.timing.isActive(playheadUs) } ?: return@Canvas
        val transform = layer.evaluatedTransform(playheadUs)
        val rw = layer.referenceWidth.takeIf { it > 0 } ?: comp.width
        val rh = layer.referenceHeight.takeIf { it > 0 } ?: comp.height
        val geometry = if (layer.type == LayerType.SOLID) OverlaySource(rw, rh) else source
        if (geometry != null && geometry.width > 0 && geometry.height > 0 &&
            geometry.pixelAspect.isFinite() && geometry.pixelAspect > 0f &&
            (settings.showBoundingBoxes || settings.showSelectionHandles)) {
            LayerGeometry.matrix(matrix, transform, comp.width, comp.height, geometry.width, geometry.height,
                geometry.rotation, geometry.pixelAspect, referenceWidth = rw, referenceHeight = rh,
                anchorX = layer.anchorX, anchorY = layer.anchorY)
            fun corner(x: Float, y: Float) = Offset(
                (matrix[0] * x + matrix[4] * y + matrix[12] + 1f) * size.width / 2f,
                (matrix[1] * x + matrix[5] * y + matrix[13] + 1f) * size.height / 2f)
            val corners = listOf(corner(-1f, -1f), corner(1f, -1f), corner(1f, 1f), corner(-1f, 1f))
            if (settings.showBoundingBoxes) {
                val outline = Path().apply {
                    moveTo(corners[0].x, corners[0].y)
                    corners.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                drawPath(outline, Color.Black, style = Stroke(2.5.dp.toPx()))
                drawPath(outline, Highlight, style = Stroke(1.dp.toPx()))
            }
            if (settings.showSelectionHandles) {
                val handles = corners + corners.indices.map { (corners[it] + corners[(it + 1) % 4]) / 2f }
                handles.forEach { p ->
                    drawCircle(Color.Black, 4.dp.toPx(), p)
                    drawCircle(Highlight, 2.5.dp.toPx(), p)
                }
            }
        }
        if (settings.anchorVisible(anchorMode)) {
            val p = Offset((.5f + transform.positionX * rw / (2f * comp.width)) * size.width,
                (.5f + transform.positionY * rh / (2f * comp.height)) * size.height)
            val color = if (anchorMode) Highlight else TextSecondary.copy(alpha = .8f)
            drawCircle(Color.Black, 7.dp.toPx(), p, style = Stroke(3.dp.toPx()))
            drawCircle(color, 7.dp.toPx(), p, style = Stroke(1.3.dp.toPx()))
            drawLine(color, p - Offset(11.dp.toPx(), 0f), p + Offset(11.dp.toPx(), 0f), 1.3.dp.toPx())
            drawLine(color, p - Offset(0f, 11.dp.toPx()), p + Offset(0f, 11.dp.toPx()), 1.3.dp.toPx())
        }
    }
}
