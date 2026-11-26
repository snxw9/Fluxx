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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import com.fluxx.android.render.LayerGeometry
import com.fluxx.android.render.TextMetricsRepository
import com.fluxx.android.ui.theme.Border
import com.fluxx.android.ui.theme.Highlight
import com.fluxx.android.ui.theme.SelectorCoverageColour
import com.fluxx.android.editor.AnimatorTrackAddress
import com.fluxx.android.ui.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class OverlaySource(val width: Int, val height: Int, val rotation: Int = 0, val pixelAspect: Float = 1f)

@Composable
internal fun PreviewLayerOverlay(project: ProjectDocument, selected: CompositionLayer?, playheadUs: Long,
    repository: MediaRepository?, settings: ViewSettings, anchorMode: Boolean,
    /** TLP.1: the focused Animate-tab address; with a selector in it the layer box becomes the selector box. */
    focusedSelector: AnimatorTrackAddress? = null) {
    val comp = project.composition
    val context = LocalContext.current
    val textSource = selected?.text?.source?.evaluateDocument(playheadUs - selected.resolvedKeyframeAnchorUs) ?: TextSourceDocument("")
    val ligatures=selected?.let { it.text.effectiveLigatures(playheadUs-it.resolvedKeyframeAnchorUs) } ?: true
    val textMetrics by produceState<TextLayoutMetrics?>(null, selected?.type, selected?.text?.fontId, textSource,ligatures,selected?.text) {
        value = null
        if (selected?.type == LayerType.TEXT) {
            try { value = TextMetricsRepository.get(context).measure(selected.text.fontId, textSource,ligatures,selected.text) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
    }
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
    // TLP.1: the focused selector's coverage box (layout space), computed off the UI thread with the same text metrics.
    // The last box stays while a new one is computed (no flicker to the layer box while scrubbing); it is drawn only
    // while it belongs to the current layer and focused selector.
    val selectorKey = focusedSelector?.takeIf { it.selectorId != null && selected?.type == LayerType.TEXT }?.let { selected!!.id to it }
    var selectorBox by remember { mutableStateOf<Pair<Pair<Long, AnimatorTrackAddress>, TextBounds>?>(null) }
    val selectorLocal = selected?.let { playheadUs - it.resolvedKeyframeAnchorUs }
    LaunchedEffect(selectorKey, textMetrics, selected?.text, selectorLocal) {
        val key = selectorKey; val metrics = textMetrics; val layer = selected
        if (key == null || metrics == null || layer == null || selectorLocal == null) { selectorBox = null; return@LaunchedEffect }
        val box = withContext(Dispatchers.Default) {
            try { metrics.selectorBox(layer.text, selectorLocal, key.second.animatorId, requireNotNull(key.second.selectorId)) }
            catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        }
        selectorBox = box?.let { key to it }
    }
    val shownSelectorBox = selectorBox?.takeIf { it.first == selectorKey && selected?.visible == true && selected.timing.isActive(playheadUs) }?.second?.takeIf { !it.empty }
    val matrix = remember { FloatArray(16) }
    Canvas(Modifier.fillMaxSize().testTag("preview-layer-overlay").semantics {
        stateDescription = if (shownSelectorBox != null) "Selector box" else "Layer box" }) {
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
        val textSize = layer.text.sizeAt(playheadUs - layer.resolvedKeyframeAnchorUs)
        val textBounds = textMetrics?.bounds(layer.text, playheadUs - layer.resolvedKeyframeAnchorUs)
        val hasGeometry = if (layer.type == LayerType.TEXT) textBounds?.empty == false else
            geometry != null && geometry.width > 0 && geometry.height > 0 && geometry.pixelAspect.isFinite() && geometry.pixelAspect > 0f
        if (hasGeometry &&
            (settings.showBoundingBoxes || settings.showSelectionHandles)) {
            if (layer.type == LayerType.TEXT) LayerGeometry.textMatrix(matrix, transform, requireNotNull(textMetrics).logical(layer.text, playheadUs - layer.resolvedKeyframeAnchorUs),
                comp.width, comp.height, rw, rh, layer.anchorX, layer.anchorY)
            else LayerGeometry.matrix(matrix, transform, comp.width, comp.height, requireNotNull(geometry).width, geometry.height,
                geometry.rotation, geometry.pixelAspect, referenceWidth = rw, referenceHeight = rh,
                anchorX = layer.anchorX, anchorY = layer.anchorY)
            fun corner(x: Float, y: Float) = Offset(
                (matrix[0] * x + matrix[4] * y + matrix[12] + 1f) * size.width / 2f,
                (matrix[1] * x + matrix[5] * y + matrix[13] + 1f) * size.height / 2f)
            // TLP.1: while a selector is focused and covers something, its box replaces the layer box (same matrix).
            fun box(b: TextBounds) = listOf(corner(b.left, b.top), corner(b.left + b.width, b.top), corner(b.left + b.width, b.top + b.height), corner(b.left, b.top + b.height))
            val layerCorners = if (layer.type == LayerType.TEXT) box(requireNotNull(textBounds)) else listOf(corner(-1f, -1f), corner(1f, -1f), corner(1f, 1f), corner(-1f, 1f))
            val corners = if (layer.type == LayerType.TEXT && shownSelectorBox != null) box(shownSelectorBox) else layerCorners
            if (settings.showBoundingBoxes) {
                val outline = Path().apply {
                    moveTo(corners[0].x, corners[0].y)
                    corners.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                drawPath(outline, Color.Black, style = Stroke(2.5.dp.toPx()))
                drawPath(outline, if (shownSelectorBox != null && layer.type == LayerType.TEXT) SelectorCoverageColour else Highlight, style = Stroke(1.dp.toPx()))
            }
            if (settings.showSelectionHandles) {
                // The handles stay on the layer box (the selector box is display only; gestures are unchanged).
                val handles = layerCorners + layerCorners.indices.map { (layerCorners[it] + layerCorners[(it + 1) % 4]) / 2f }
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
