package com.fluxx.android.ui.common.colorpicker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.fluxx.android.model.FluxxColor
import com.fluxx.android.model.PaletteRepository

/** Shared no-scroll picker. Optional gesture callbacks let the editor keep one undo entry per drag. */
@Composable fun FluxxColorPicker(color: FluxxColor, onColorChange: (FluxxColor)->Unit,
    config: ColorPickerConfig=ColorPickerConfig(), onEyedropperRequest: (()->Unit)?=null,
    modifier: Modifier=Modifier, onGestureBegin: ()->Unit={}, onGestureCommit: ()->Unit={},
    onGestureCancel: ()->Unit={}, onColorPreview: (FluxxColor)->Unit=onColorChange,
    paletteRepository: PaletteRepository?=null) {
    // Local precision belongs only to the SV/hue gesture, never the RGB/A gesture lifecycle.
    var hue by remember { mutableFloatStateOf(color.toHsv().hue) }
    var saturation by remember { mutableFloatStateOf(color.toHsv().saturation) }
    var value by remember { mutableFloatStateOf(color.toHsv().value) }
    var isDraggingColorControl by remember { mutableStateOf(false) }
    var beforeDrag by remember { mutableStateOf(color.toHsv()) }
    val beginColor = { beforeDrag=FluxxColor.Hsv(hue,saturation,value); isDraggingColorControl=true; onGestureBegin() }
    val commitColor = { onGestureCommit(); isDraggingColorControl=false }
    val cancelColor = { onGestureCancel(); hue=beforeDrag.hue; saturation=beforeDrag.saturation; value=beforeDrag.value; isDraggingColorControl=false }
    LaunchedEffect(color,isDraggingColorControl) {
        if(!isDraggingColorControl) {
            val hsv=color.toHsv()
            value=hsv.value
            if(hsv.value>.01f) {
                if(hsv.saturation>.01f && !(hue==360f && hsv.hue==0f)) hue=hsv.hue
                saturation=hsv.saturation
            }
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val height=if(maxHeight.value.isFinite()) maxHeight else 208.dp
        // Reserve the right column's hex/readout budget before sizing the square.
        val boxSize=minOf(140.dp,height,(maxWidth-26.dp-10.dp-135.dp).coerceAtLeast(40.dp))
        Row(Modifier.fillMaxWidth().height(height),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
            if(config.showColorControl) Row(Modifier.width(boxSize+26.dp).height(boxSize),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                SaturationValueBox(hue,saturation,value,{ s,v -> saturation=s; value=v
                    onColorPreview(FluxxColor.fromHsv(hue,s,v,color.a)) },Modifier.size(boxSize),beginColor,commitColor,cancelColor)
                HueStrip(hue,{ h -> hue=h; onColorPreview(FluxxColor.fromHsv(h,saturation,value,color.a)) },
                    Modifier.width(22.dp).fillMaxHeight(),beginColor,commitColor,cancelColor)
            }
            Column(Modifier.weight(1f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth().height(30.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    ColorPreviewSwatch(color,Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)))
                    if(config.showHexInput) HexColorInput(color,onColorChange,Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                    if(config.showEyedropper && onEyedropperRequest!=null) {
                        val ink=MaterialTheme.colorScheme.onSurface
                        Canvas(Modifier.size(28.dp).clickable(onClick=onEyedropperRequest).semantics { contentDescription="Sample preview colour" }) {
                            val path=Path().apply { moveTo(size.width*.25f,size.height*.75f); lineTo(size.width*.35f,size.height*.5f)
                                lineTo(size.width*.7f,size.height*.15f); lineTo(size.width*.85f,size.height*.3f)
                                lineTo(size.width*.5f,size.height*.65f); close() }
                            drawPath(path,ink,style=Stroke(1.3.dp.toPx(),cap=StrokeCap.Round,join=StrokeJoin.Round))
                        }
                    }
                }
                if(config.showAlpha) ColorChannelSlider("A",color.a,{ onColorPreview(color.copy(a=it)) },Modifier.fillMaxWidth().weight(1f),
                    listOf(color.copy(a=0f).toComposeColor(),color.copy(a=1f).toComposeColor()),true,onGestureBegin,onGestureCommit,onGestureCancel)
                if(config.showRgbSliders) {
                    ColorChannelSlider("R",color.r,{ onColorPreview(color.copy(r=it)) },Modifier.fillMaxWidth().weight(1f),onBegin=onGestureBegin,onCommit=onGestureCommit,onCancel=onGestureCancel)
                    ColorChannelSlider("G",color.g,{ onColorPreview(color.copy(g=it)) },Modifier.fillMaxWidth().weight(1f),onBegin=onGestureBegin,onCommit=onGestureCommit,onCancel=onGestureCancel)
                    ColorChannelSlider("B",color.b,{ onColorPreview(color.copy(b=it)) },Modifier.fillMaxWidth().weight(1f),onBegin=onGestureBegin,onCommit=onGestureCommit,onCancel=onGestureCancel)
                }
                if(config.showPalette && paletteRepository!=null) PaletteRow(paletteRepository,color,onColorChange)
            }
        }
    }
}
