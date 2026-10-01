package com.fluxx.android.ui.common.colorpicker

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.fluxx.android.model.FluxxColor

@Composable internal fun HueStrip(hue: Float,onChange: (Float)->Unit,modifier: Modifier=Modifier,
    onBegin: ()->Unit,onCommit: ()->Unit,onCancel: ()->Unit) {
    Canvas(modifier.semantics { contentDescription="Hue"; progressBarRangeInfo=ProgressBarRangeInfo(hue,0f..360f)
        setProgress { onBegin(); onChange(it.coerceIn(0f,360f)); onCommit(); true }
    }.colorControlDrag({ _,y -> onChange(y*360f) },onBegin,onCommit,onCancel)) {
        drawRect(Brush.verticalGradient((0..6).map { FluxxColor.fromHsv(it*60f,1f,1f).toComposeColor() }))
        val y=(hue/360f)*size.height
        drawLine(Color.Black,Offset(0f,y),Offset(size.width,y),4.dp.toPx())
        drawLine(Color.White,Offset(0f,y),Offset(size.width,y),1.3.dp.toPx())
    }
}
