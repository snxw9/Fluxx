package com.fluxx.android.ui.browser

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun BrowserIcon(name: String, color: Color, modifier: Modifier = Modifier) {
    if (name == "Text") { Text("T", modifier, color = color, fontSize = 22.sp); return }
    Canvas(modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(1.3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x: Float, y: Float, xx: Float, yy: Float) = drawLine(color,
            Offset(w*x, h*y), Offset(w*xx, h*yy), stroke.width, StrokeCap.Round)
        when (name) {
            "Back" -> { line(.7f,.5f,.25f,.5f); line(.25f,.5f,.45f,.3f); line(.25f,.5f,.45f,.7f) }
            "Browse" -> {
                listOf(.18f,.56f).forEach { x -> listOf(.18f,.56f).forEach { y ->
                    drawRoundRect(color,Offset(w*x,h*y),Size(w*.26f,h*.26f),CornerRadius(2.dp.toPx()),style=stroke)
                } }
            }
            "AddToTimeline" -> {
                line(.15f,.8f,.85f,.8f); line(.15f,.68f,.15f,.9f); line(.85f,.68f,.85f,.9f)
                line(.5f,.12f,.5f,.6f); line(.35f,.45f,.5f,.6f); line(.5f,.6f,.65f,.45f)
            }
            "Close" -> { line(.25f,.25f,.75f,.75f); line(.75f,.25f,.25f,.75f) }
            "Add" -> { line(.2f,.5f,.8f,.5f); line(.5f,.2f,.5f,.8f) }
            "Audio" -> {
                line(.4f,.2f,.4f,.7f); line(.4f,.2f,.8f,.12f); line(.8f,.12f,.8f,.62f)
                drawOval(color, Offset(w*.14f,h*.64f), Size(w*.26f,h*.18f))
                drawOval(color, Offset(w*.54f,h*.56f), Size(w*.26f,h*.18f))
            }
            "Expand", "Collapse" -> {
                val outer = if (name == "Expand") .15f else .35f
                val inner = if (name == "Expand") .38f else .12f
                line(outer,inner,outer,outer); line(outer,outer,inner,outer)
                line(1-outer,1-inner,1-outer,1-outer); line(1-outer,1-outer,1-inner,1-outer)
                line(.35f,.65f,.65f,.35f)
            }
            "Shape" -> drawCircle(color, size.minDimension*.33f, center, style = stroke)
            "Adjustment" -> {
                listOf(.25f,.5f,.75f).forEachIndexed { i, x ->
                    line(x,.15f,x,.85f)
                    drawCircle(color,w*.09f,Offset(w*x,h*(if(i==1) .65f else .35f)),style=stroke)
                }
            }
            "Null" -> {
                drawRoundRect(color,Offset(w*.2f,h*.2f),Size(w*.6f,h*.6f),CornerRadius(2.dp.toPx()),style=stroke)
                line(.35f,.5f,.65f,.5f); line(.5f,.35f,.5f,.65f)
            }
            else -> {
                drawRoundRect(color,Offset(w*.12f,h*.2f),Size(w*.76f,h*.6f),CornerRadius(2.dp.toPx()),
                    style = if(name=="Solid") androidx.compose.ui.graphics.drawscope.Fill else stroke)
                when(name) {
                    "Media", "Video" -> drawPath(Path().apply {
                        moveTo(w*.42f,h*.34f); lineTo(w*.66f,h*.5f); lineTo(w*.42f,h*.66f); close()
                    },color,style=stroke)
                    "Camera" -> { drawCircle(color,w*.17f,center,style=stroke); line(.32f,.2f,.38f,.1f); line(.38f,.1f,.62f,.1f) }
                    "Image" -> { line(.22f,.68f,.45f,.43f); line(.45f,.43f,.75f,.68f); drawCircle(color,w*.06f,Offset(w*.7f,h*.34f)) }
                }
            }
        }
    }
}
