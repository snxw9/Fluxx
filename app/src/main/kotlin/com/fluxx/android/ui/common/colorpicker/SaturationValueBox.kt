package com.fluxx.android.ui.common.colorpicker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.fluxx.android.model.FluxxColor

@Composable internal fun Modifier.colorControlDrag(onChange: (Float,Float)->Unit,
    onBegin: ()->Unit,onCommit: ()->Unit,onCancel: ()->Unit): Modifier {
    val change by rememberUpdatedState(onChange)
    val begin by rememberUpdatedState(onBegin); val commit by rememberUpdatedState(onCommit); val cancel by rememberUpdatedState(onCancel)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down=awaitFirstDown(); down.consume(); begin()
            var completed=false
            fun update(point: Offset) = change((point.x/size.width.coerceAtLeast(1)).coerceIn(0f,1f),
                (point.y/size.height.coerceAtLeast(1)).coerceIn(0f,1f))
            try {
                update(down.position)
                val released=drag(down.id) { it.consume(); update(it.position) }
                completed=true
                if(released) commit() else cancel()
            } finally { if(!completed) cancel() }
        }
    }
}

@Composable internal fun SaturationValueBox(hue: Float,saturation: Float,value: Float,
    onChange: (Float,Float)->Unit,modifier: Modifier=Modifier,onBegin: ()->Unit,onCommit: ()->Unit,onCancel: ()->Unit) {
    Canvas(modifier.semantics { contentDescription="Saturation and brightness" }
        .colorControlDrag({ x,y -> onChange(x,1f-y) },onBegin,onCommit,onCancel)) {
        drawRect(Brush.horizontalGradient(listOf(Color.White,FluxxColor.fromHsv(hue,1f,1f).toComposeColor())))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent,Color.Black)),blendMode=BlendMode.Multiply)
        val selector=Offset(saturation*size.width,(1f-value)*size.height)
        drawCircle(Color.Black,5.dp.toPx(),selector,style=Stroke(3.dp.toPx()))
        drawCircle(Color.White,5.dp.toPx(),selector,style=Stroke(1.3.dp.toPx()))
    }
}
