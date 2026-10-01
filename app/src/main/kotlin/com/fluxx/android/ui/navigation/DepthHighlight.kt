package com.fluxx.android.ui.navigation

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.*

/**
 * Surface lighting effect from the bottom navigation bar applied app-wide:
 * - Subtle specular top sheen (White with topHighlightAlpha)
 * - Subtle ambient bottom shadow (Black with bottomShadowAlpha)
 * - 1dp upper rim catch-light (White with upperRimAlpha)
 *
 * Works with any Compose Shape (RoundedCornerShape, CircleShape, CradleShape, RectangleShape).
 */
fun Modifier.depthHighlight(
    shape: Shape = CradleShape(),
    topHighlightAlpha: Float = 0.06f,
    bottomShadowAlpha: Float = 0.15f,
    upperRimAlpha: Float = 0.12f
): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)

    val clipPath: Path
    val upperRimPath: Path?

    when (outline) {
        is Outline.Rectangle -> {
            clipPath = Path().apply { addRect(outline.rect) }
            upperRimPath = if (upperRimAlpha > 0f) {
                Path().apply {
                    moveTo(outline.rect.left, outline.rect.top)
                    lineTo(outline.rect.right, outline.rect.top)
                }
            } else null
        }
        is Outline.Rounded -> {
            val rr = outline.roundRect
            clipPath = Path().apply { addRoundRect(rr) }
            upperRimPath = if (upperRimAlpha > 0f) {
                Path().apply {
                    val tlX = rr.topLeftCornerRadius.x
                    val tlY = rr.topLeftCornerRadius.y
                    val trX = rr.topRightCornerRadius.x
                    val trY = rr.topRightCornerRadius.y

                    if (tlX > 0f && tlY > 0f) {
                        moveTo(rr.left, rr.top + tlY)
                        arcTo(
                            rect = Rect(rr.left, rr.top, rr.left + tlX * 2f, rr.top + tlY * 2f),
                            startAngleDegrees = 180f,
                            sweepAngleDegrees = 90f,
                            forceMoveTo = false
                        )
                    } else {
                        moveTo(rr.left, rr.top)
                    }

                    if (trX > 0f && trY > 0f) {
                        lineTo(rr.right - trX, rr.top)
                        arcTo(
                            rect = Rect(rr.right - trX * 2f, rr.top, rr.right, rr.top + trY * 2f),
                            startAngleDegrees = 270f,
                            sweepAngleDegrees = 90f,
                            forceMoveTo = false
                        )
                    } else {
                        lineTo(rr.right, rr.top)
                    }
                }
            } else null
        }
        is Outline.Generic -> {
            clipPath = outline.path
            upperRimPath = if (shape is CradleShape) {
                shape.path(size, upperOnly = true)
            } else null
        }
    }

    val gradientBrush = Brush.verticalGradient(
        0f to Color.White.copy(alpha = topHighlightAlpha),
        0.30f to Color.Transparent,
        0.70f to Color.Transparent,
        1f to Color.Black.copy(alpha = bottomShadowAlpha)
    )
    val rimColor = Color.White.copy(alpha = upperRimAlpha)
    val rimStroke = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round)

    onDrawWithContent {
        drawContent()
        clipPath(clipPath) {
            drawRect(brush = gradientBrush)
            if (upperRimPath != null && upperRimAlpha > 0f) {
                drawPath(upperRimPath, color = rimColor, style = rimStroke)
            }
        }
    }
}

/** Notch center lies on the stadium's top edge; radius is in pixels. */
data class CradleShape(val notchRadius: Float = 0f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(path(size))

    fun path(size: Size, upperOnly: Boolean = false): Path = Path().apply {
        val r = minOf(size.height / 2, size.width / 2)
        val cx = size.width / 2
        val notch = notchRadius.coerceIn(0f, (cx - r).coerceAtLeast(0f))
        moveTo(0f, r)
        arcTo(Rect(0f, 0f, r * 2, r * 2), 180f, 90f, false)
        lineTo(cx - notch, 0f)
        if (notch > 0f) arcTo(Rect(cx - notch, -notch, cx + notch, notch), 180f, -180f, false)
        lineTo(size.width - r, 0f)
        arcTo(Rect(size.width - r * 2, 0f, size.width, r * 2), 270f, 90f, false)
        if (!upperOnly) {
            lineTo(size.width, size.height - r)
            arcTo(Rect(size.width - r * 2, size.height - r * 2, size.width, size.height), 0f, 90f, false)
            lineTo(r, size.height)
            arcTo(Rect(0f, size.height - r * 2, r * 2, size.height), 90f, 90f, false)
            close()
        }
    }
}
