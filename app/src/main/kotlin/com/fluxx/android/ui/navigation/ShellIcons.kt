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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Redone monochrome vector icons conforming to Material Design 3 and Fluxx design tokens.
 * Features consistent rounded caps, joins, and corner radii throughout.
 * Outline style at rest (TextSecondary), filled (Highlight) when active.
 */

@Composable
fun HomeNavIcon(isActive: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.3.dp.toPx()

        if (isActive) {
            // Filled active Home glyph with rounded bottom corners and door cutout
            val fullHouse = Path().apply {
                // Peak
                moveTo(w * 0.50f, h * 0.16f)
                // Right eaves
                lineTo(w * 0.86f, h * 0.44f)
                // Right wall start
                lineTo(w * 0.77f, h * 0.44f)
                // Right wall down
                lineTo(w * 0.77f, h * 0.78f)
                // Bottom-right rounded corner
                quadraticTo(w * 0.77f, h * 0.86f, w * 0.69f, h * 0.86f)
                // Bottom edge to door
                lineTo(w * 0.60f, h * 0.86f)
                // Door right side
                lineTo(w * 0.60f, h * 0.60f)
                // Door top arch
                quadraticTo(w * 0.50f, h * 0.56f, w * 0.40f, h * 0.60f)
                // Door left side
                lineTo(w * 0.40f, h * 0.86f)
                // Bottom edge to left wall
                lineTo(w * 0.31f, h * 0.86f)
                // Bottom-left rounded corner
                quadraticTo(w * 0.23f, h * 0.86f, w * 0.23f, h * 0.78f)
                // Left wall up
                lineTo(w * 0.23f, h * 0.44f)
                // Left eaves
                lineTo(w * 0.14f, h * 0.44f)
                close()
            }
            drawPath(fullHouse, color = color, style = Fill)
        } else {
            // Outlined inactive Home glyph with rounded caps and joins
            val roof = Path().apply {
                moveTo(w * 0.14f, h * 0.44f)
                lineTo(w * 0.50f, h * 0.16f)
                lineTo(w * 0.86f, h * 0.44f)
            }
            drawPath(
                roof,
                color = color,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )

            // Body with rounded bottom corners
            val body = Path().apply {
                moveTo(w * 0.23f, h * 0.42f)
                lineTo(w * 0.23f, h * 0.78f)
                quadraticTo(w * 0.23f, h * 0.86f, w * 0.31f, h * 0.86f)
                lineTo(w * 0.69f, h * 0.86f)
                quadraticTo(w * 0.77f, h * 0.86f, w * 0.77f, h * 0.78f)
                lineTo(w * 0.77f, h * 0.42f)
            }
            drawPath(
                body,
                color = color,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )

            // Inner door arch with rounded top
            val door = Path().apply {
                moveTo(w * 0.40f, h * 0.86f)
                lineTo(w * 0.40f, h * 0.60f)
                quadraticTo(w * 0.50f, h * 0.56f, w * 0.60f, h * 0.60f)
                lineTo(w * 0.60f, h * 0.86f)
            }
            drawPath(
                door,
                color = color,
                style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

@Composable
fun ProjectsNavIcon(isActive: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.3.dp.toPx()
        val corner = 2.5.dp.toPx()

        // 2x2 grid of rounded squares conforming to Material 3 GridView
        val padX = w * 0.14f
        val padY = h * 0.14f
        val gap = w * 0.12f
        val rectW = (w - (padX * 2f) - gap) / 2f
        val rectH = (h - (padY * 2f) - gap) / 2f

        val rects = listOf(
            Offset(padX, padY),
            Offset(padX + rectW + gap, padY),
            Offset(padX, padY + rectH + gap),
            Offset(padX + rectW + gap, padY + rectH + gap)
        )

        for (topLeft in rects) {
            if (isActive) {
                drawRoundRect(
                    color = color,
                    topLeft = topLeft,
                    size = Size(rectW, rectH),
                    cornerRadius = CornerRadius(corner, corner),
                    style = Fill
                )
            } else {
                drawRoundRect(
                    color = color,
                    topLeft = topLeft,
                    size = Size(rectW, rectH),
                    cornerRadius = CornerRadius(corner, corner),
                    style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
    }
}

@Composable
fun UserOutlineIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.3.dp.toPx()

        // Head
        drawCircle(
            color = color,
            radius = w * 0.17f,
            center = Offset(w * 0.50f, h * 0.32f),
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Smooth anatomical shoulders with round caps
        val shoulders = Path().apply {
            moveTo(w * 0.16f, h * 0.84f)
            cubicTo(
                w * 0.18f, h * 0.62f,
                w * 0.34f, h * 0.56f,
                w * 0.50f, h * 0.56f
            )
            cubicTo(
                w * 0.66f, h * 0.56f,
                w * 0.82f, h * 0.62f,
                w * 0.84f, h * 0.84f
            )
        }
        drawPath(
            shoulders,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
fun ShellBackArrowIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.3.dp.toPx()

        // Stem
        drawLine(
            color = color,
            start = Offset(w * 0.22f, h * 0.50f),
            end = Offset(w * 0.78f, h * 0.50f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )

        // Chevron head
        val head = Path().apply {
            moveTo(w * 0.46f, h * 0.24f)
            lineTo(w * 0.22f, h * 0.50f)
            lineTo(w * 0.46f, h * 0.76f)
        }
        drawPath(
            head,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
fun ChevronRightIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.3.dp.toPx()
        val path = Path().apply {
            moveTo(w * 0.36f, h * 0.24f)
            lineTo(w * 0.64f, h * 0.50f)
            lineTo(w * 0.36f, h * 0.76f)
        }
        drawPath(
            path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/**
 * Empty project placeholder: a sleek composition frame with an inner rounded play glyph.
 */
@Composable
fun EmptyProjectGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.3.dp.toPx()
        val corner = 2.5.dp.toPx()

        // Outer composition canvas frame with rounded corners
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.14f, h * 0.18f),
            size = Size(w * 0.72f, h * 0.64f),
            cornerRadius = CornerRadius(corner, corner),
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Centered media play triangle with rounded vertices
        val play = Path().apply {
            moveTo(w * 0.44f, h * 0.38f)
            lineTo(w * 0.62f, h * 0.50f)
            lineTo(w * 0.44f, h * 0.62f)
            close()
        }
        drawPath(
            play,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/**
 * Missing media badge: 14dp rounded amber (#D9A441) warning triangle glyph with dark exclamation point.
 */
@Composable
fun MissingMediaWarningBadge(modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .size(14.dp)
            .semantics {
                contentDescription = "Missing media; open project to relink"
            }
    ) {
        val w = size.width
        val h = size.height
        val amber = com.fluxx.android.ui.theme.MissingMediaWarning

        // Rounded warning triangle with smooth vertices
        val tri = Path().apply {
            moveTo(w * 0.50f, h * 0.12f)
            quadraticTo(w * 0.54f, h * 0.14f, w * 0.88f, h * 0.78f)
            quadraticTo(w * 0.92f, h * 0.86f, w * 0.84f, h * 0.88f)
            lineTo(w * 0.16f, h * 0.88f)
            quadraticTo(w * 0.08f, h * 0.86f, w * 0.12f, h * 0.78f)
            quadraticTo(w * 0.46f, h * 0.14f, w * 0.50f, h * 0.12f)
            close()
        }
        drawPath(tri, color = amber, style = Fill)

        // Inner dark exclamation mark: vertical rounded pill and dot for accessible contrast
        val stroke = 1.3.dp.toPx()
        drawLine(
            color = Color(0xFF121212),
            start = Offset(w * 0.50f, h * 0.38f),
            end = Offset(w * 0.50f, h * 0.60f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawCircle(
            color = Color(0xFF121212),
            radius = stroke * 0.65f,
            center = Offset(w * 0.50f, h * 0.74f)
        )
    }
}
