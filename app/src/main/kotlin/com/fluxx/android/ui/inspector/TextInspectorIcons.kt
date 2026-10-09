package com.fluxx.android.ui.inspector

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Cached monochrome vectors. UI tint comes exclusively from MaterialTheme. */
internal object TextInspectorIcons {
    private val cache=mutableMapOf<String,ImageVector>()
    private const val A="M4,20 L10,4 L16,20 M6,14 L14,14"
    fun control(control: TextControl)=icon(control.name)
    fun icon(name: String): ImageVector=cache.getOrPut(name) {
        val data=when(name) {
            "FONT"->"M7,21 L7,3 L19,3 M7,11 L16,11"
            "AXIS"->"M3,6 L21,6 M3,18 L21,18 M8,3 L8,9 M16,15 L16,21"
            "SIZE","RELATIVE_SIZE"->A+" M21,4 L21,20 M19,6 L21,4 L23,6 M19,18 L21,20 L23,18"
            "FILL","PAINT"->"M4,20 L10,4 L16,20 L13,20 L11,15 L8,15 L6,20 Z M8,12 L11,12 L10,8 Z"
            "STROKE","STROKE_WIDTH"->A+" M19,8 L23,8 M19,16 L23,16 M21,8 L21,16"
            "TRACKING","SPACING"->"M2,15 L5,5 L8,15 M3,11 L7,11 M10,5 L14,15 L18,5 M3,21 L21,21 M3,21 L6,18 M3,21 L6,24 M21,21 L18,18 M21,21 L18,24"
            "KERNING","MANUAL_KERNING"->"M2,18 L6,5 L10,18 M4,13 L8,13 M13,5 L17,18 L21,5 M11,3 L11,21 M8,21 L14,21"
            "LEADING"->"M7,5 L22,5 M7,11 L19,11 M7,17 L22,17 M3,3 L3,20 M1,5 L3,3 L5,5 M1,18 L3,20 L5,18"
            "BASELINE"->A+" M2,21 L23,21 M21,7 L21,18 M19,9 L21,7 L23,9"
            "H_SCALE"->A+" M2,23 L22,23 M2,23 L5,20 M22,23 L19,20"
            "V_SCALE"->A+" M21,2 L21,22 M18,5 L21,2 L24,5 M18,19 L21,22 L24,19"
            "BOLD"->"M6,3 L6,21 L13,21 Q21,21 19,15 Q18,12 14,12 Q20,10 18,6 Q17,3 12,3 Z M9,6 L12,6 Q16,6 15,9 L9,9 Z M9,14 L13,14 Q17,14 16,18 L9,18 Z"
            "ITALIC"->"M10,3 L21,3 M3,21 L14,21 M16,3 L8,21"
            "CASE","NONE"->A+" M19,13 Q23,10 23,17 L23,21 M23,17 Q18,14 18,18 Q18,22 23,20"
            "ALL"->"M2,20 L7,4 L12,20 M4,14 L10,14 M13,20 L18,4 L23,20 M15,14 L21,14"
            "LOWER"->"M3,13 Q10,9 10,17 L10,21 M10,17 Q2,14 2,18 Q2,23 10,20 M15,13 Q22,9 22,17 L22,21 M22,17 Q14,14 14,18 Q14,23 22,20"
            "SMALL"->A+" M16,20 L20,10 L24,20 M18,16 L22,16"
            "SUPER"->"M3,12 L12,21 M3,21 L12,12 M16,4 Q23,1 22,6 L16,10 L23,10"
            "SUB"->"M3,3 L12,12 M3,12 L12,3 M16,15 Q23,12 22,17 L16,21 L23,21"
            "LIGATURES"->"M7,22 L7,7 Q7,2 13,3 M3,10 L17,10 M17,10 L17,22 M16,5 L18,5"
            "ORDER"->"M3,3 L16,3 L16,16 L3,16 Z M9,18 L9,22 L22,22 L22,9 L18,9"
            "INDENT_LEFT","INDENTS"->"M3,3 L3,21 M11,5 L23,5 M11,11 L21,11 M11,17 L23,17 M5,8 L9,11 L5,14"
            "INDENT_RIGHT"->"M21,3 L21,21 M1,5 L13,5 M3,11 L13,11 M1,17 L13,17 M19,8 L15,11 L19,14"
            "INDENT_FIRST"->"M3,3 L3,21 M13,5 L23,5 M7,11 L21,11 M7,17 L23,17 M6,2 L10,5 L6,8"
            "BEFORE"->"M4,11 L21,11 M4,16 L18,16 M4,21 L21,21 M12,2 L12,8 M9,5 L12,2 L15,5"
            "AFTER"->"M4,3 L21,3 M4,8 L18,8 M4,13 L21,13 M12,16 L12,23 M9,20 L12,23 L15,20"
            "BOX"->"M3,3 L21,3 L21,21 L3,21 Z M5,12 L19,12 M5,12 L8,9 M5,12 L8,15 M19,12 L16,9 M19,12 L16,15"
            "DIRECTION","LTR"->"M11,3 L21,3 M15,3 L15,14 M19,3 L19,14 M11,3 Q3,3 3,8 Q3,12 11,12 Z M3,20 L21,20 M17,16 L21,20 L17,24"
            "RTL"->"M11,3 L21,3 M15,3 L15,14 M19,3 L19,14 M11,3 Q3,3 3,8 Q3,12 11,12 Z M3,20 L21,20 M7,16 L3,20 L7,24"
            "SOURCE"->"M3,4 L21,4 M3,10 L18,10 M3,16 L21,16 M3,22 L13,22"
            "PREV"->"M16,4 L8,12 L16,20 M4,4 L4,20"
            "NEXT"->"M8,4 L16,12 L8,20 M20,4 L20,20"
            "KEY","ANIMATE"->"M12,3 L21,12 L12,21 L3,12 Z"
            "GRAPH"->"M3,3 L3,21 L22,21 M5,18 Q12,18 12,12 Q12,5 21,5"
            "3D"->"M12,2 L22,7 L22,18 L12,23 L2,18 L2,7 Z M2,7 L12,12 L22,7 M12,12 L12,23"
            "OVERFLOW","ACTIONS","FORMAT"->"M12,3 L12,5 M12,11 L12,13 M12,19 L12,21"
            "DONE"->"M3,12 L9,18 L21,5"
            "CANCEL","DELETE"->"M5,5 L19,19 M19,5 L5,19"
            "RESET","INHERIT"->"M4,8 Q18,-1 21,12 Q23,23 9,21 M4,3 L4,9 L10,9"
            "IMPORT"->"M12,2 L12,17 M7,12 L12,17 L17,12 M3,17 L3,22 L21,22 L21,17"
            "MISSING"->"M12,2 L23,22 L1,22 Z M12,8 L12,14 M12,18 L12,19"
            "EXPLODE"->"M2,5 L8,5 L8,11 L2,11 Z M16,3 L22,3 L22,9 L16,9 Z M9,16 L15,16 L15,22 L9,22 Z"
            else->A
        }
        ImageVector.Builder(name,24.dp,24.dp,24f,24f).addPath(PathParser().parsePathString(data).toNodes(),
            fill=if(name=="FILL" || name=="BOLD") SolidColor(Color.Black) else null,
            stroke=SolidColor(Color.Black),strokeLineWidth=1.4f,strokeLineCap=StrokeCap.Round,strokeLineJoin=StrokeJoin.Round).build()
    }
    fun alignment(value: com.fluxx.android.model.ParagraphAlignment): ImageVector=cache.getOrPut("align.$value") {
        val builder=ImageVector.Builder("align.$value",24.dp,24.dp,24f,24f)
        val justify=value.ordinal>=3
        val last=when(value) {com.fluxx.android.model.ParagraphAlignment.RIGHT,com.fluxx.android.model.ParagraphAlignment.JUSTIFY_LAST_RIGHT->"M10,20 L22,20"
            com.fluxx.android.model.ParagraphAlignment.CENTRE,com.fluxx.android.model.ParagraphAlignment.JUSTIFY_LAST_CENTRE->"M6,20 L18,20"
            com.fluxx.android.model.ParagraphAlignment.JUSTIFY_ALL->"M2,20 L22,20";else->"M2,20 L14,20"}
        val lines=if(justify) "M2,4 L22,4 M2,9 L22,9 M2,14 L22,14 " else when(value) {
            com.fluxx.android.model.ParagraphAlignment.RIGHT->"M2,4 L22,4 M8,9 L22,9 M4,14 L22,14 "
            com.fluxx.android.model.ParagraphAlignment.CENTRE->"M2,4 L22,4 M5,9 L19,9 M3,14 L21,14 "
            else->"M2,4 L22,4 M2,9 L16,9 M2,14 L20,14 "}
        builder.addPath(PathParser().parsePathString(lines+last).toNodes(),stroke=SolidColor(Color.Black),strokeLineWidth=1.4f).build()
    }
}
