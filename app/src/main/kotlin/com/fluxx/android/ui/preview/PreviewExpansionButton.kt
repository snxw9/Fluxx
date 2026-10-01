package com.fluxx.android.ui.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fluxx.android.ui.theme.Border

@Composable
internal fun PreviewExpansionButton(expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(48.dp).clickable(onClick = onClick).semantics {
        contentDescription = if (expanded) "Collapse preview" else "Expand preview"
    }, contentAlignment = Alignment.Center) {
        Surface(Modifier.size(32.dp), shape = CircleShape, color = Color.Black.copy(alpha = .65f),
            border = BorderStroke(.5.dp, Border), tonalElevation = 2.dp, shadowElevation = 2.dp) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(18.dp)) {
                    for (x in listOf(-1f, 1f)) for (y in listOf(-1f, 1f)) {
                        val corner = center + Offset(x * size.width * (if (expanded) .16f else .4f), y * size.height * (if (expanded) .16f else .4f))
                        val direction = if (expanded) 1f else -1f
                        drawLine(Color.White, corner, corner + Offset(x * direction * size.width * .25f, 0f), 1.5.dp.toPx(), StrokeCap.Round)
                        drawLine(Color.White, corner, corner + Offset(0f, y * direction * size.height * .25f), 1.5.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
        }
    }
}
