package com.fluxx.android.ui.inspector

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fluxx.android.model.*

@Composable
internal fun ExplodeTextDialog(layer: CompositionLayer,time: Long,onDismiss: ()->Unit,
    prepare: (TextExplodeOptions,(Result<TextExplodePlan>)->Unit)->Unit,
    commit: (TextExplodePlan,(String?)->Unit)->Unit) {
    var options by remember { mutableStateOf(TextExplodeOptions()) }
    var plan by remember { mutableStateOf<TextExplodePlan?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmedLarge by remember { mutableStateOf(false) }
    fun change(value: TextExplodeOptions) {options=value;plan=null;error=null;confirmedLarge=false}
    AlertDialog(onDismissRequest={if(!busy) onDismiss()},title={Text("Explode Text")},text={
        Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text("Split by",style=MaterialTheme.typography.labelLarge)
            TextSplit.entries.chunked(2).forEach {row->Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                row.forEach {split->FilterChip(selected=options.split==split,enabled=!busy,onClick={change(options.copy(split=split))},
                    label={Text(when(split) {TextSplit.CHARACTERS->"Characters";TextSplit.WORDS->"Words";TextSplit.LINES->"Lines";TextSplit.PARAGRAPHS->"Paragraphs";TextSplit.STYLE_RUNS->"Style runs"})}) }
            } }
            Row {Checkbox(options.skipSpaces,{change(options.copy(skipSpaces=it))},enabled=!busy);Text("Skip spaces",Modifier.padding(top=12.dp))}
            Row {Checkbox(options.keepOriginal,{change(options.copy(keepOriginal=it))},enabled=!busy);Text("Keep original (hidden)",Modifier.padding(top=12.dp))}
            Text("Pivot",style=MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {TextPiecePivot.entries.forEach {pivot->
                FilterChip(selected=options.pivot==pivot,enabled=!busy,onClick={change(options.copy(pivot=pivot))},label={Text(if(pivot==TextPiecePivot.GROUP) "Group" else "Each piece")})
            } }
            Text(TextExplode.summary(layer,time),style=MaterialTheme.typography.bodySmall)
            Text("More layers increase preparation and rendering cost. Text draws remain batched four at a time.",style=MaterialTheme.typography.bodySmall)
            plan?.let {ready->
                Text("${ready.pieces.size} pieces ready",style=MaterialTheme.typography.labelLarge)
                if(ready.pieces.size>200) Row {Checkbox(confirmedLarge,{confirmedLarge=it},enabled=!busy);Text("Create more than 200 layers",Modifier.padding(top=12.dp))}
            }
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        }
    },dismissButton={TextButton(enabled=!busy,onClick=onDismiss) {Text("Cancel")}},confirmButton={
        val ready=plan
        Button(enabled=!busy && (ready==null || ready.pieces.size<=200 || confirmedLarge),onClick={
            busy=true;error=null
            if(ready==null) prepare(options) {result->busy=false;plan=result.getOrNull();error=result.exceptionOrNull()?.message}
            else commit(ready) {message->busy=false;if(message==null) onDismiss() else {error=message;plan=null}}
        }) {Text(if(ready==null) "Prepare" else "Explode")}
    })
}
