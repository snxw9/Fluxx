package com.fluxx.android.ui.common.colorpicker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable internal fun ColorChannelSlider(label: String, value: Float, onChange: (Float)->Unit,
    modifier: Modifier=Modifier, gradient: List<Color>?=null, percent: Boolean=false,
    onBegin: ()->Unit={}, onCommit: ()->Unit={}, onCancel: ()->Unit={}) {
    val change by rememberUpdatedState(onChange)
    val begin by rememberUpdatedState(onBegin); val commit by rememberUpdatedState(onCommit); val cancel by rememberUpdatedState(onCancel)
    val ink=MaterialTheme.colorScheme.onSurface
    Row(modifier,verticalAlignment=Alignment.CenterVertically) {
        Text(label,color=ink,fontSize=11.sp,modifier=Modifier.width(15.dp))
        Canvas(Modifier.weight(1f).fillMaxHeight().semantics {
            contentDescription=label; progressBarRangeInfo=ProgressBarRangeInfo(value,0f..1f)
            setProgress { begin(); change(it.coerceIn(0f,1f)); commit(); true }
        }.pointerInput(Unit) {
            awaitEachGesture {
                val down=awaitFirstDown(); down.consume(); begin()
                fun update(x:Float) { change((x/size.width).coerceIn(0f,1f)) }
                try {
                    update(down.position.x)
                    if(drag(down.id) { it.consume(); update(it.position.x) }) commit() else cancel()
                } catch(e:kotlinx.coroutines.CancellationException) { cancel(); throw e }
            }
        }) {
            val y=center.y
            if(gradient!=null) {
                clipRect(0f,y-3.dp.toPx(),size.width,y+3.dp.toPx()) { checkerboard() }
                drawRect(Brush.horizontalGradient(gradient),Offset(0f,y-3.dp.toPx()),Size(size.width,6.dp.toPx()))
            } else drawLine(ink.copy(alpha=.25f),Offset(0f,y),Offset(size.width,y),2.dp.toPx())
            drawCircle(ink,4.dp.toPx(),Offset(value*size.width,y))
        }
        Text(if(percent) "${(value*100).roundToInt()}%" else "${(value*255).roundToInt()}",
            color=ink,fontSize=10.sp,modifier=Modifier.width(32.dp).padding(start=3.dp))
    }
}
