package com.fluxx.android.ui.inspector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fluxx.android.model.LayerType
import com.fluxx.android.ui.theme.*

@Composable internal fun InspectorLandingGrid(type: LayerType, onOpen: (InspectorSection)->Unit) {
    val cells=listOf(
        Triple(InspectorSection.TIMING,"Timing",true),
        Triple(InspectorSection.TRACK_MATTES,"Mattes &\nMasks",false),
        Triple(InspectorSection.LAYER_STYLES,"Layer Styles",false),
        Triple(InspectorSection.BLENDING_OPACITY,"Blending &\nOpacity",true),
        Triple(InspectorSection.TRANSFORM,"Transform",true),
        Triple(InspectorSection.CONTEXTUAL_EDIT,if(type==LayerType.SOLID) "Edit Solid" else "Edit Footage",type==LayerType.SOLID),
        Triple(InspectorSection.MOTION_BLUR,"Motion Blur",false),
        Triple(InspectorSection.EFFECTS,"Effects",false))
    val columns=4
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        cells.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                row.forEach { (section,label,enabled) ->
                    val ink=if(enabled) TextPrimary else TextDisabled
                    Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(SurfaceHigh)
                        .clickable(enabled=enabled,role=Role.Button) { onOpen(section) }
                        .semantics { contentDescription=label.replace('\n',' '); if(!enabled) disabled() }
                        .padding(horizontal=2.dp,vertical=3.dp),horizontalAlignment=Alignment.CenterHorizontally,
                        verticalArrangement=Arrangement.Center) {
                        InspectorCategoryIcon(section,ink,solid=type==LayerType.SOLID)
                        Spacer(Modifier.height(3.dp))
                        Text(label,color=ink,style=MaterialTheme.typography.labelSmall,textAlign=TextAlign.Center,maxLines=2)
                    }
                }
                repeat(columns-row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Monochrome family: rounded 1.3dp strokes, no emoji or font-dependent glyphs. */
@Composable internal fun InspectorCategoryIcon(section: InspectorSection, color: Color, cube: Boolean=false, solid: Boolean=false) {
    Canvas(Modifier.size(22.dp)) {
        fun p(x:Float,y:Float)=Offset(x*size.width/24,y*size.height/24)
        fun line(x:Float,y:Float,xx:Float,yy:Float)=drawLine(color,p(x,y),p(xx,yy),1.3.dp.toPx(),StrokeCap.Round)
        val stroke=Stroke(1.3.dp.toPx(),cap=StrokeCap.Round,join=StrokeJoin.Round)
        if(cube) {
            val path=Path().apply { moveTo(p(12f,2f).x,p(12f,2f).y)
                for(point in listOf(p(21f,7f),p(21f,17f),p(12f,22f),p(3f,17f),p(3f,7f))) lineTo(point.x,point.y)
                close() }
            drawPath(path,color,style=stroke); line(3f,7f,12f,12f);line(21f,7f,12f,12f);line(12f,12f,12f,22f)
        } else when(section) {
            InspectorSection.TRANSFORM -> {
                line(3f,12f,21f,12f);line(12f,3f,12f,21f)
                line(3f,12f,6f,9f);line(3f,12f,6f,15f);line(21f,12f,18f,9f);line(21f,12f,18f,15f)
                line(12f,3f,9f,6f);line(12f,3f,15f,6f);line(12f,21f,9f,18f);line(12f,21f,15f,18f)
            }
            InspectorSection.TIMING -> { drawCircle(color,size.width*.4f,style=stroke);line(12f,6f,12f,12f);line(12f,12f,17f,15f) }
            InspectorSection.BLENDING_OPACITY -> { drawCircle(color,size.width*.3f,p(9f,12f),style=stroke);drawCircle(color.copy(alpha=.5f),size.width*.3f,p(15f,12f)) }
            InspectorSection.CONTEXTUAL_EDIT -> {
                if(solid) {
                    drawCircle(color,size.width*.4f,style=stroke)
                    for(point in listOf(p(8f,7f),p(15f,7f),p(6f,13f),p(12f,17f))) drawCircle(color,1.4.dp.toPx(),point)
                } else {
                    drawRoundRect(color,p(3f,4f),Size(size.width*.75f,size.height*.67f),androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),style=stroke)
                    line(7f,4f,7f,20f);line(17f,4f,17f,20f);line(3f,9f,7f,9f);line(17f,15f,21f,15f)
                }
            }
            InspectorSection.EFFECTS -> { line(12f,3f,12f,21f);line(3f,12f,21f,12f);line(6f,6f,18f,18f);line(6f,18f,18f,6f) }
            InspectorSection.LAYER_STYLES -> { for(y in listOf(6f,11f,16f)) {line(3f,y,12f,y+5);line(12f,y+5,21f,y)} }
            InspectorSection.TRACK_MATTES -> { drawRect(color,p(3f,3f),Size(size.width*.55f,size.height*.55f),style=stroke);drawCircle(color,size.width*.3f,p(16f,16f),style=stroke) }
            InspectorSection.MOTION_BLUR -> { for(y in listOf(6f,12f,18f)) line(3f,y,14f,y);drawCircle(color,size.width*.27f,p(17f,12f),style=stroke) }
            else -> Unit
        }
    }
}
