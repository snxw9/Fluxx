package com.fluxx.android.ui.common.colorpicker

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.fluxx.android.model.FluxxColor

internal fun DrawScope.checkerboard() {
    val step = 5.dp.toPx()
    for (y in 0..(size.height/step).toInt()) for (x in 0..(size.width/step).toInt())
        drawRect(if ((x+y)%2==0) Color.LightGray else Color.DarkGray, Offset(x*step,y*step), Size(step,step))
}
@Composable fun ColorPreviewSwatch(color: FluxxColor, modifier: Modifier = Modifier) {
    Canvas(modifier) { checkerboard(); drawRect(color.toComposeColor()) }
}
