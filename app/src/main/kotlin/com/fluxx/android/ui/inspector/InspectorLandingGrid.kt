package com.fluxx.android.ui.inspector


import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
        Triple(InspectorSection.CONTEXTUAL_EDIT,when(type) { LayerType.SOLID -> "Edit Solid"; LayerType.TEXT -> "Edit Text"; else -> "Edit Footage" },type==LayerType.SOLID || type==LayerType.TEXT),
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
                        InspectorCategoryIcon(section,ink,type)
                        Spacer(Modifier.height(3.dp))
                        Text(label,color=ink,style=MaterialTheme.typography.labelSmall,textAlign=TextAlign.Center,maxLines=2)
                    }
                }
                repeat(columns-row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Category glyphs from the shared [InspectorIcons] family (no Canvas). */
internal fun categoryIconName(section: InspectorSection, type: LayerType): String = when (section) {
    InspectorSection.TRANSFORM -> "TRANSFORM"; InspectorSection.TIMING -> "TIMING"; InspectorSection.BLENDING_OPACITY -> "BLENDING_OPACITY"
    InspectorSection.CONTEXTUAL_EDIT -> when (type) { LayerType.SOLID -> "EDIT_SOLID"; LayerType.TEXT -> "EDIT_TEXT"; else -> "EDIT_FOOTAGE" }
    InspectorSection.EFFECTS -> "EFFECTS"; InspectorSection.LAYER_STYLES -> "LAYER_STYLES"; InspectorSection.TRACK_MATTES -> "TRACK_MATTES"
    InspectorSection.MOTION_BLUR -> "MOTION_BLUR"; InspectorSection.AUDIO -> "AUDIO"
}
@Composable internal fun InspectorCategoryIcon(section: InspectorSection, color: Color, type: LayerType) {
    Icon(InspectorIcons.icon(categoryIconName(section, type)), null, Modifier.size(InspectorIcons.SIZE), tint = color)
}
