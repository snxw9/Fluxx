package com.fluxx.android.ui.inspector

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fluxx.android.model.*

/** Fixed pages; each Apply is one held Source Text edit using the existing undo protocol. */
@Composable
internal fun ParagraphControls(document: TextSourceDocument,start: Int,end: Int,layerAlignment: TextAlignment,
    edit: (Int,Int,(TextParagraphStyle)->TextParagraphStyle)->Unit,box: (Float)->Unit) {
    var page by remember { mutableIntStateOf(0) }
    val labels=listOf("Alignment","Left / right indent","First / before","After / box","Direction")
    val index=if(start==end) 0 else document.text.take(start.coerceAtMost(document.text.length)).count { it=='\n' }
    val style=document.paragraphs.getOrNull(index) ?: TextParagraphStyle()
    fun apply(change: (TextParagraphStyle)->TextParagraphStyle)=edit(start,end,change)
    Row(Modifier.fillMaxWidth()) {
        TextButton(onClick={page=(page+labels.lastIndex)%labels.size}) { Text("‹") }
        Text(labels[page],Modifier.weight(1f).padding(top=12.dp),style=MaterialTheme.typography.labelMedium)
        TextButton(onClick={page=(page+1)%labels.size}) { Text("›") }
    }
    Text(if(start==end) "All paragraphs" else "Selected paragraphs",style=MaterialTheme.typography.labelSmall)
    when(page) {
        0 -> {
            CharacterChoice("Alignment: ${style.alignment ?: layerAlignment}",listOf("Inherit layer")+ParagraphAlignment.entries.map { it.name.replace('_',' ') }) {
                selected -> apply { it.copy(alignment=if(selected==0) null else ParagraphAlignment.entries[selected-1]) }
            }
            Text("Justification expands word spaces; overflow is visible",style=MaterialTheme.typography.labelSmall)
        }
        1 -> CharacterNumbers("Left indent px",style.indentLeft,"Right indent px",style.indentRight) { a,b -> apply { it.copy(indentLeft=a,indentRight=b) } }
        2 -> CharacterNumbers("First indent px",style.indentFirst,"Space before px",style.spaceBefore) { a,b -> apply { it.copy(indentFirst=a,spaceBefore=b) } }
        3 -> {
            var after by remember(style.spaceAfter) { mutableStateOf(style.spaceAfter.toString()) }
            var width by remember(document.boxWidth) { mutableStateOf(document.boxWidth.toString()) }
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(after,{after=it},singleLine=true,label={Text("After px")},modifier=Modifier.weight(1f))
                OutlinedTextField(width,{width=it},singleLine=true,label={Text("Box px; 0=point")},modifier=Modifier.weight(1f))
            }
            Row {
                val a=after.toFloatOrNull()?.takeIf { it.isFinite() }
                val w=width.toFloatOrNull()?.takeIf { it.isFinite() && it>=0 }
                TextButton(enabled=a!=null,onClick={apply { it.copy(spaceAfter=requireNotNull(a)) }}) { Text("Apply after") }
                TextButton(enabled=w!=null,onClick={box(requireNotNull(w))}) { Text("Apply box") }
            }
        }
        4 -> {
            CharacterChoice("Direction: ${style.direction}",ParagraphDirection.entries.map { it.name }) { selected -> apply { it.copy(direction=ParagraphDirection.entries[selected]) } }
            Text("Physical alignment; explicit direction. RTL awaits T5.",style=MaterialTheme.typography.labelSmall)
        }
    }
}
