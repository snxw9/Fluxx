package com.fluxx.android.ui.inspector

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fluxx.android.model.*

/** Functional paged controls; each Apply or menu choice is one existing text-edit transaction. */
@Composable
internal fun CharacterControls(document: TextSourceDocument,start: Int,end: Int,layerFont: String,
    edit: (Int,Int,(TextRunStyle)->TextRunStyle)->Unit,strokeClamp: String?=null) {
    var page by remember { mutableIntStateOf(0) }
    val style=document.runAt(start)?.style ?: TextRunStyle()
    val inheritedFamily=layerFont.removeSuffix(".bold-italic").removeSuffix(".italic").removeSuffix(".bold")
    val labels=listOf("Font","Appearance","Script / kerning","Size / width","Height / baseline","Spacing / stroke","Colours","Manual kerning","Font library","Variable axes")
    fun apply(change: (TextRunStyle)->TextRunStyle)=edit(start,end,change)
    Row(Modifier.fillMaxWidth()) {
        TextButton(onClick={page=(page+labels.lastIndex)%labels.size}) { Text("‹") }
        Text(labels[page],Modifier.weight(1f).padding(top=12.dp),style=MaterialTheme.typography.labelMedium)
        TextButton(onClick={page=(page+1)%labels.size}) { Text("›") }
    }
    Text(if(start==end) "Applies to all text" else "Applies to selected characters",style=MaterialTheme.typography.labelSmall)
    when(page) {
        0 -> {
            Row {
            Box(Modifier.weight(1f)) { CharacterChoice("Family",listOf("Inter","Noto Serif","JetBrains Mono")) { index ->
                val family=listOf("fluxx.sans","fluxx.serif","fluxx.mono")[index]
                apply { it.copy(font=TextFontRef(family,it.font?.style ?: TextFaceStyle.REGULAR)) }
            } }
            Box(Modifier.weight(1f)) { if(style.font?.sha256!=null) {
                TextButton(onClick={page=8}) { Text(style.font.styleName.ifBlank { style.font.postScriptName.ifBlank { style.font.family } },maxLines=1) }
            } else CharacterChoice("Style: ${style.font?.style ?: TextFaceStyle.REGULAR}",TextFaceStyle.entries.map { it.name.replace('_',' ') }) { index ->
                apply { it.copy(font=TextFontRef(it.font?.family ?: inheritedFamily,TextFaceStyle.entries[index])) }
            } }
            }
            TextButton(onClick={apply { it.copy(font=null) }}) { Text("Inherit layer font") }
        }
        1 -> {
            Row {
                TextButton(onClick={apply { it.copy(fauxBold=!style.fauxBold) }}) { Text("Faux bold: ${style.fauxBold}") }
                TextButton(onClick={apply { it.copy(fauxItalic=!style.fauxItalic) }}) { Text("Faux italic: ${style.fauxItalic}") }
            }
            CharacterChoice("Caps: ${style.caps}",TextCaps.entries.map { it.name }) { index -> apply { it.copy(caps=TextCaps.entries[index]) } }
        }
        2 -> {
            CharacterChoice("Script: ${style.script}",TextScriptPosition.entries.map { it.name }) { index -> apply { it.copy(script=TextScriptPosition.entries[index]) } }
            CharacterChoice("Kerning: ${style.kerning}",TextKerning.entries.map { it.name }) { index -> apply { it.copy(kerning=TextKerning.entries[index]) } }
        }
        3 -> CharacterNumbers("Size factor",style.sizeFactor,"Horizontal %",style.horizontalScale) { a,b -> apply { it.copy(sizeFactor=a,horizontalScale=b) } }
        4 -> CharacterNumbers("Vertical %",style.verticalScale,"Baseline delta px",style.baselineDelta) { a,b -> apply { it.copy(verticalScale=a,baselineDelta=b) } }
        5 -> CharacterNumbers("Tracking delta /1000 em",style.trackingDelta,"Stroke delta px",style.strokeDelta,strokeClamp ?: "Apply") { a,b -> apply { it.copy(trackingDelta=a,strokeDelta=b) } }
        6 -> {
            var fill by remember(document,start,end) { mutableStateOf(style.fill?.toUInt()?.toString(16).orEmpty()) }
            var stroke by remember(document,start,end) { mutableStateOf(style.stroke?.toUInt()?.toString(16).orEmpty()) }
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(fill,{fill=it},label={Text("Fill ARGB",maxLines=1)},singleLine=true,modifier=Modifier.weight(1f))
                OutlinedTextField(stroke,{stroke=it},label={Text("Stroke ARGB",maxLines=1)},singleLine=true,modifier=Modifier.weight(1f))
            }
            val valid=(fill.isBlank() || fill.toUIntOrNull(16)!=null) && (stroke.isBlank() || stroke.toUIntOrNull(16)!=null)
            TextButton(enabled=valid,onClick={val f=fill.toUIntOrNull(16)?.toInt();val s=stroke.toUIntOrNull(16)?.toInt();apply { it.copy(fill=f,stroke=s) }}) { Text("Apply; blank inherits") }
        }
        7 -> {
            var value by remember(document,start,end) { mutableStateOf("0") }
            OutlinedTextField(value,{value=it},label={Text("After each selected character /1000 em")},singleLine=true,modifier=Modifier.fillMaxWidth())
            val amount=value.toFloatOrNull()?.takeIf { it.isFinite() && it in -10000f..10000f }
            Row {
            TextButton(enabled=amount!=null,onClick={
                val a=if(start==end) 0 else TextSourceBoundaries.floor(document.boundaries,start)
                val b=if(start==end) document.text.length else TextSourceBoundaries.ceiling(document.boundaries,end)
                val positions=document.boundaries.filter { it in a until b && document.text[it]!='\n' }
                apply { s ->
                    val entries=s.manualKerning.associateBy { it.character }.toMutableMap()
                    for(position in positions) entries[position]=TextManualKerning(position,requireNotNull(amount))
                    s.copy(manualKerning=FrozenList.of(entries.values.sortedBy { it.character }))
                }
            }) { Text("Apply kerning") }
            TextButton(onClick={apply { it.copy(manualKerning=FrozenList.empty()) }}) { Text("Reset selected kerning") }
            }
        }
        8 -> FontLibraryControls { ref -> apply { it.copy(font=ref) } }
        9 -> FontAxisControls(style.font) { ref -> apply { it.copy(font=ref) } }
    }
}

@Composable private fun CharacterChoice(label: String,choices: List<String>,choose: (Int)->Unit) {
    var open by remember { mutableStateOf(false) }
    Box { TextButton(onClick={open=true}) { Text(label,maxLines=1) }
        DropdownMenu(open,{open=false}) { choices.forEachIndexed { index,value -> DropdownMenuItem(text={Text(value)},onClick={open=false;choose(index)}) } }
    }
}
@Composable private fun CharacterNumbers(first: String,a: Float,second: String,b: Float,label: String="Apply",apply: (Float,Float)->Unit) {
    var x by remember(first,a) { mutableStateOf(a.toString()) };var y by remember(second,b) { mutableStateOf(b.toString()) }
    Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(x,{x=it},label={Text(first,maxLines=1,style=MaterialTheme.typography.labelSmall)},singleLine=true,modifier=Modifier.weight(1f))
        OutlinedTextField(y,{y=it},label={Text(second,maxLines=1,style=MaterialTheme.typography.labelSmall)},singleLine=true,modifier=Modifier.weight(1f))
    }
    val xx=x.toFloatOrNull()?.takeIf { it.isFinite() };val yy=y.toFloatOrNull()?.takeIf { it.isFinite() }
    TextButton(enabled=xx!=null && yy!=null,onClick={apply(requireNotNull(xx),requireNotNull(yy))}) { Text(label,maxLines=1,style=MaterialTheme.typography.labelSmall) }
}
