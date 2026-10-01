package com.fluxx.android.ui.navigation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Glyph identifiers for Fluxx vector icons.
 */
enum class ProjectGlyph {
    Plus,
    More,
    Aspect,
    Resolution,
    Duration,
    FrameRate,
    FileSize,
    Pencil,
    Save,
    Close,
    Cube
}

/**
 * Redone clean monochrome vector icons adhering to Material Design 3 stroke weighting,
 * round caps, round joins, and proportional scaling down to 12dp metadata chips.
 */
@Composable
fun ProjectVectorIcon(glyph: ProjectGlyph, color: Color, modifier: Modifier = Modifier, isActive: Boolean = false) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val minDim = minOf(w, h)
        // Keep the physical dp weight consistent across icon sizes and screen densities.
        val stroke = 1.3.dp.toPx()
        val corner = 2.dp.toPx()

        fun line(x1: Float, y1: Float, x2: Float, y2: Float, strokeWidth: Float = stroke) {
            drawLine(
                color = color,
                start = Offset(w * x1, h * y1),
                end = Offset(w * x2, h * y2),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }

        when (glyph) {
            ProjectGlyph.Plus -> {
                line(0.20f, 0.50f, 0.80f, 0.50f)
                line(0.50f, 0.20f, 0.50f, 0.80f)
            }
            ProjectGlyph.More -> {
                // 3 vertical solid dots for overflow menu
                val dotRadius = 1.2.dp.toPx()
                drawCircle(color, dotRadius, Offset(w * 0.50f, h * 0.22f), style = Fill)
                drawCircle(color, dotRadius, Offset(w * 0.50f, h * 0.50f), style = Fill)
                drawCircle(color, dotRadius, Offset(w * 0.50f, h * 0.78f), style = Fill)
            }
            ProjectGlyph.Close -> {
                line(0.25f, 0.25f, 0.75f, 0.75f)
                line(0.75f, 0.25f, 0.25f, 0.75f)
            }
            ProjectGlyph.Aspect -> {
                // 16:9 widescreen frame with rounded corners
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.10f, h * 0.24f),
                    size = Size(w * 0.80f, h * 0.52f),
                    cornerRadius = CornerRadius(corner, corner),
                    style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                // Dimension measurement ticks: |---|
                line(0.26f, 0.50f, 0.74f, 0.50f, stroke * 0.85f)
                line(0.26f, 0.40f, 0.26f, 0.60f, stroke * 0.85f)
                line(0.74f, 0.40f, 0.74f, 0.60f, stroke * 0.85f)
            }
            ProjectGlyph.Resolution -> {
                // Display screen with rounded corners and stand
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.12f, h * 0.16f),
                    size = Size(w * 0.76f, h * 0.54f),
                    cornerRadius = CornerRadius(corner, corner),
                    style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                line(0.50f, 0.70f, 0.50f, 0.84f)
                line(0.32f, 0.84f, 0.68f, 0.84f)
            }
            ProjectGlyph.Duration -> {
                // Clock ring with rounded hands
                val radius = minDim * 0.38f
                drawCircle(
                    color = color,
                    radius = radius,
                    center = Offset(w * 0.50f, h * 0.50f),
                    style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                line(0.50f, 0.50f, 0.50f, 0.26f)
                line(0.50f, 0.50f, 0.70f, 0.50f)
                drawCircle(color, stroke * 0.75f, Offset(w * 0.50f, h * 0.50f), style = Fill)
            }
            ProjectGlyph.FrameRate -> {
                // Filmstrip frame with 3 cells and sprocket ticks
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.12f, h * 0.18f),
                    size = Size(w * 0.76f, h * 0.64f),
                    cornerRadius = CornerRadius(corner, corner),
                    style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                line(0.37f, 0.18f, 0.37f, 0.82f)
                line(0.63f, 0.18f, 0.63f, 0.82f)
                // Subtle sprocket ticks
                line(0.24f, 0.18f, 0.24f, 0.28f, stroke * 0.85f)
                line(0.50f, 0.18f, 0.50f, 0.28f, stroke * 0.85f)
                line(0.76f, 0.18f, 0.76f, 0.28f, stroke * 0.85f)
                line(0.24f, 0.72f, 0.24f, 0.82f, stroke * 0.85f)
                line(0.50f, 0.72f, 0.50f, 0.82f, stroke * 0.85f)
                line(0.76f, 0.72f, 0.76f, 0.82f, stroke * 0.85f)
            }
            ProjectGlyph.FileSize -> {
                // Document page with folded top-right flap
                val doc = Path().apply {
                    moveTo(w * 0.22f, h * 0.14f)
                    lineTo(w * 0.58f, h * 0.14f)
                    lineTo(w * 0.78f, h * 0.34f)
                    lineTo(w * 0.78f, h * 0.82f)
                    quadraticTo(w * 0.78f, h * 0.86f, w * 0.74f, h * 0.86f)
                    lineTo(w * 0.26f, h * 0.86f)
                    quadraticTo(w * 0.22f, h * 0.86f, w * 0.22f, h * 0.82f)
                    close()
                }
                drawPath(doc, color = color, style = Stroke(stroke, join = StrokeJoin.Round, cap = StrokeCap.Round))
                val fold = Path().apply {
                    moveTo(w * 0.58f, h * 0.14f)
                    lineTo(w * 0.58f, h * 0.34f)
                    lineTo(w * 0.78f, h * 0.34f)
                }
                drawPath(fold, color = color, style = Stroke(stroke, join = StrokeJoin.Round, cap = StrokeCap.Round))
                line(0.34f, 0.58f, 0.66f, 0.58f, stroke * 0.85f)
                line(0.34f, 0.72f, 0.52f, 0.72f, stroke * 0.85f)
            }
            ProjectGlyph.Pencil -> {
                // 45° angled edit pencil
                val body = Path().apply {
                    moveTo(w * 0.20f, h * 0.80f)
                    lineTo(w * 0.22f, h * 0.64f)
                    lineTo(w * 0.64f, h * 0.22f)
                    lineTo(w * 0.78f, h * 0.36f)
                    lineTo(w * 0.36f, h * 0.78f)
                    close()
                }
                drawPath(body, color = color, style = if (isActive) Fill else Stroke(stroke, join = StrokeJoin.Round, cap = StrokeCap.Round))
                if (!isActive) {
                    line(0.56f, 0.30f, 0.70f, 0.44f)
                    line(0.20f, 0.80f, 0.29f, 0.71f)
                }
            }
            ProjectGlyph.Save -> {
                // Save floppy disk with rounded corners
                val disk = Path().apply {
                    moveTo(w * 0.24f, h * 0.14f)
                    lineTo(w * 0.66f, h * 0.14f)
                    lineTo(w * 0.82f, h * 0.30f)
                    lineTo(w * 0.82f, h * 0.82f)
                    quadraticTo(w * 0.82f, h * 0.86f, w * 0.78f, h * 0.86f)
                    lineTo(w * 0.22f, h * 0.86f)
                    quadraticTo(w * 0.18f, h * 0.86f, w * 0.18f, h * 0.82f)
                    lineTo(w * 0.18f, h * 0.18f)
                    quadraticTo(w * 0.18f, h * 0.14f, w * 0.22f, h * 0.14f)
                    close()
                }
                drawPath(disk, color = color, style = Stroke(stroke, join = StrokeJoin.Round, cap = StrokeCap.Round))
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.32f, h * 0.14f),
                    size = Size(w * 0.36f, h * 0.26f),
                    cornerRadius = CornerRadius(corner * 0.5f, corner * 0.5f),
                    style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.28f, h * 0.52f),
                    size = Size(w * 0.44f, h * 0.34f),
                    cornerRadius = CornerRadius(corner * 0.5f, corner * 0.5f),
                    style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
            ProjectGlyph.Cube -> {
                // Isometric 3D wireframe cube with rounded vertices
                val pTop = Offset(w * 0.50f, h * 0.14f)
                val pRight = Offset(w * 0.84f, h * 0.33f)
                val pBottomRight = Offset(w * 0.84f, h * 0.67f)
                val pBottom = Offset(w * 0.50f, h * 0.86f)
                val pBottomLeft = Offset(w * 0.16f, h * 0.67f)
                val pLeft = Offset(w * 0.16f, h * 0.33f)
                val pCenter = Offset(w * 0.50f, h * 0.50f)

                val cubeOutline = Path().apply {
                    moveTo(pTop.x, pTop.y)
                    lineTo(pRight.x, pRight.y)
                    lineTo(pBottomRight.x, pBottomRight.y)
                    lineTo(pBottom.x, pBottom.y)
                    lineTo(pBottomLeft.x, pBottomLeft.y)
                    lineTo(pLeft.x, pLeft.y)
                    close()
                }
                drawPath(cubeOutline, color = color, style = Stroke(stroke, join = StrokeJoin.Round, cap = StrokeCap.Round))

                drawLine(color, pCenter, pBottom, stroke, cap = StrokeCap.Round)
                drawLine(color, pCenter, pLeft, stroke, cap = StrokeCap.Round)
                drawLine(color, pCenter, pRight, stroke, cap = StrokeCap.Round)
            }
        }
    }
}
