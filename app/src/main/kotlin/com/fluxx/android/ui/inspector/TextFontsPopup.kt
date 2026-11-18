package com.fluxx.android.ui.inspector

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import android.view.HapticFeedbackConstants
import androidx.compose.ui.unit.dp
import com.fluxx.android.fonts.*
import com.fluxx.android.model.*
import kotlinx.coroutines.launch

/**
 * In-sheet Fonts surface. It is laid out inside the Edit Text sheet (which never overlaps the preview), so it
 * cannot hide the edited layer. Family, style and axis lists scroll independently; the inspector never scrolls.
 */
@Composable internal fun TextFontsPopup(reference: TextFontRef?,layerFont: String,scopeLabel: String,
    apply: (TextFontRef?)->Unit,dismiss: ()->Unit,
    axisBegin: ()->Unit,axisPreview: (TextFontRef)->Unit,axisFinish: ()->Unit,axisCancel: ()->Unit,busy: Boolean=false,
    baseFont: ((String)->Unit)?=null,modifier: Modifier=Modifier) {
    val context=LocalContext.current;val repository=remember(context) {FontLibraryRepository.getInstance(context)}
    val entries by repository.state.collectAsState();val scope=rememberCoroutineScope()
    var search by remember {mutableStateOf("")};var error by remember {mutableStateOf<String?>(null)}
    var importing by remember {mutableStateOf(false)};var deletion by remember {mutableStateOf<FontLibraryEntry?>(null)}
    var exactAxis by remember {mutableStateOf<FontAxisMetadata?>(null)}
    var baseMenu by remember {mutableStateOf(false)}
    val selectedEntry=entries.find {it.sha256==reference?.sha256}
    val selectedFace=selectedEntry?.faces?.find {it.index==reference?.faceIndex}
    val rows=remember(entries,search) {entries.flatMap {entry->entry.faces.map {entry to it}}.filter {(_,face)->"${face.family} ${face.style}".contains(search,true)}}
    LaunchedEffect(repository) {runCatching {repository.load()}.onFailure {error=it.message}}
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->if(uri!=null) scope.launch {
        importing=true;error=null
        try {repository.import(uri)} catch(failure: Exception) {error=failure.message ?: "Font import failed"} finally {importing=false}
    }}
    Surface(modifier.testTag("text-fonts"),shape=MaterialTheme.shapes.large,tonalElevation=3.dp,shadowElevation=6.dp,
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxSize().padding(horizontal=4.dp)) {
            Row(Modifier.fillMaxWidth().height(48.dp),verticalAlignment=Alignment.CenterVertically) {
                BasicTextField(search,{search=it},singleLine=true,textStyle=MaterialTheme.typography.bodyMedium.copy(color=MaterialTheme.colorScheme.onSurface),
                    cursorBrush=SolidColor(MaterialTheme.colorScheme.primary),
                    modifier=Modifier.weight(1f).fillMaxHeight().padding(vertical=4.dp).border(1.dp,MaterialTheme.colorScheme.outline,MaterialTheme.shapes.small)
                        .semantics {contentDescription="Find font, $scopeLabel"},
                    decorationBox={inner->Box(Modifier.fillMaxSize().padding(horizontal=8.dp),contentAlignment=Alignment.CenterStart) {
                        if(search.isEmpty()) Text("Find font · $scopeLabel",maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        inner()
                    }})
                TextToolIcon("IMPORT",if(importing) "Importing font" else "Import font",{importer.launch(arrayOf("*/*"))},!importing)
                TextToolIcon("DELETE","Delete selected font file",{deletion=selectedEntry},selectedEntry!=null && !importing)
                TextToolIcon("DONE","Close Fonts",dismiss)
            }
            if(reference?.sha256!=null && selectedFace==null) Text("Requested ${reference.family} ${reference.styleName} missing · Inter fallback · identity retained",
                maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelSmall)
            error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis)}
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("text-fonts-families")) {
                items(listOf("fluxx.sans" to "Inter","fluxx.serif" to "Noto Serif","fluxx.mono" to "JetBrains Mono").filter {it.second.contains(search,true)},key={it.first}) {(id,name)->
                    TextButton(onClick={apply(TextFontRef(id,reference?.takeIf {it.sha256==null}?.style ?: TextFaceStyle.REGULAR))},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                        Text(name+(if(reference?.family==id && reference.sha256==null || reference==null && layerFont.startsWith(id)) " ✓" else ""),Modifier.fillMaxWidth(),maxLines=1,overflow=TextOverflow.Ellipsis)}
                }
                items(rows,key={(entry,face)->"${entry.sha256}:${face.index}"}) {(entry,face)->
                    TextButton(onClick={apply(face.reference(entry.sha256))},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                        Text("${face.family} · ${face.style} · face ${face.index}"+(if(reference?.sha256==entry.sha256 && reference.faceIndex==face.index) " ✓" else ""),Modifier.fillMaxWidth(),maxLines=1,overflow=TextOverflow.Ellipsis)}
                }
            }
            // Style / named instance: one horizontally scrolling choice row (no dropdown that could leave the sheet).
            val instance=selectedFace?.instances?.find {it.index==reference?.namedInstance}
            val modified=instance!=null && selectedFace.axes.indices.any {index->reference?.axes?.find {it.tag==selectedFace.axes[index].tag}?.value?.staticValue!=instance.values[index]}
            LazyRow(Modifier.fillMaxWidth().height(48.dp).semantics {contentDescription="Style: "+(instance?.name?.plus(if(modified) ", modified" else "") ?: selectedFace?.style ?: reference?.style?.name ?: "Regular")},
                verticalAlignment=Alignment.CenterVertically) {
                if(selectedFace!=null && reference!=null && selectedFace.axes.isNotEmpty()) {
                    item {TextButton(onClick={apply(selectedFace.selectInstance(reference,-1))},Modifier.heightIn(min=48.dp)) {Text("Default"+if(reference.namedInstance<0) " ✓" else "")}}
                    items(selectedFace.instances,key={it.index}) {preset->TextButton(onClick={apply(selectedFace.selectInstance(reference,preset.index))},Modifier.heightIn(min=48.dp)) {
                        Text(preset.name+if(preset.index==reference.namedInstance) (if(modified) " · modified" else " ✓") else "")}}
                } else if(reference?.sha256==null) items(TextFaceStyle.entries) {style->
                    TextButton(onClick={apply(TextFontRef(reference?.family ?: layerFont.removeSuffix(".bold-italic").removeSuffix(".italic").removeSuffix(".bold"),style))},Modifier.heightIn(min=48.dp)) {
                        Text(style.name.lowercase().replace('_',' ').replaceFirstChar {it.uppercase()}+if((reference?.style ?: TextFaceStyle.REGULAR)==style) " ✓" else "")}
                } else item {Text("Static imported face · choose another face above",style=MaterialTheme.typography.labelSmall)}
            }
            if(selectedFace!=null && reference!=null && selectedFace.axes.isNotEmpty()) {
                Text("Static axes · Source Text Hold",style=MaterialTheme.typography.labelSmall)
                LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("text-fonts-axes")) {
                    items(selectedFace.axes,key={it.tag}) {axis->
                        val value=reference.axes.find {it.tag==axis.tag}?.value?.staticValue ?: axis.default
                        AxisScrubRow(axis,value,axisBegin,{v->val requested=reference.axes.associate {it.tag to it.value.staticValue}.toMutableMap();requested[axis.tag]=v
                            axisPreview(selectedFace.reference(requireNotNull(reference.sha256),reference.namedInstance,requested))},axisFinish,axisCancel,
                            {val requested=reference.axes.associate {it.tag to it.value.staticValue}.toMutableMap();requested[axis.tag]=axis.default;apply(selectedFace.reference(requireNotNull(reference.sha256),reference.namedInstance,requested))},{exactAxis=axis},!busy)
                    }
                }
            }
            val pendingDeletion=deletion
            val pendingAxis=exactAxis?.takeIf {selectedFace!=null && reference!=null}
            when {
                pendingDeletion!=null -> Row(Modifier.fillMaxWidth().height(48.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("Delete file and all ${pendingDeletion.faces.size} faces? Projects keep the request.",Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall)
                    TextButton(onClick={deletion=null}) {Text("Cancel")}
                    TextButton(onClick={deletion=null;scope.launch {runCatching {repository.delete(pendingDeletion.sha256)}.onFailure {error=it.message}}}) {Text("Delete")}
                }
                pendingAxis!=null && selectedFace!=null && reference!=null -> {
                    var input by remember(pendingAxis.tag,reference) {mutableStateOf((reference.axes.find {it.tag==pendingAxis.tag}?.value?.staticValue ?: pendingAxis.default).toString())}
                    val value=input.toFloatOrNull()?.takeIf {it.isFinite() && it in pendingAxis.minimum..pendingAxis.maximum}
                    Row(Modifier.fillMaxWidth().height(48.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("${pendingAxis.name} (${pendingAxis.tag}) ${pendingAxis.minimum}…${pendingAxis.maximum}",Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall)
                        BasicTextField(input,{input=it},singleLine=true,textStyle=MaterialTheme.typography.bodyMedium.copy(color=MaterialTheme.colorScheme.onSurface),
                            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),cursorBrush=SolidColor(MaterialTheme.colorScheme.primary),
                            modifier=Modifier.width(96.dp).fillMaxHeight().padding(vertical=4.dp)
                                .border(1.dp,if(value==null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,MaterialTheme.shapes.small)
                                .semantics {contentDescription="${pendingAxis.name} exact value";if(value==null) error("Outside axis range")},
                            decorationBox={inner->Box(Modifier.fillMaxSize().padding(horizontal=8.dp),contentAlignment=Alignment.CenterStart) {inner()}})
                    }
                    Row(Modifier.fillMaxWidth().height(48.dp),horizontalArrangement=Arrangement.End) {
                        TextButton(onClick={input=pendingAxis.default.toString()}) {Text("Default")}
                        TextButton(onClick={exactAxis=null}) {Text("Cancel")}
                        TextButton(enabled=value!=null,onClick={val requested=reference.axes.associate {it.tag to it.value.staticValue}.toMutableMap();requested[pendingAxis.tag]=requireNotNull(value)
                            apply(selectedFace.reference(requireNotNull(reference.sha256),reference.namedInstance,requested));exactAxis=null}) {Text("Done")}
                    }
                }
                else -> Row(Modifier.fillMaxWidth().height(48.dp),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={apply(null)},modifier=Modifier.heightIn(min=48.dp).semantics {contentDescription="Inherit layer font"}) {Text("Inherit layer font",maxLines=1,overflow=TextOverflow.Ellipsis)}
                    Box {TextButton(enabled=baseFont!=null,onClick={baseMenu=true},modifier=Modifier.heightIn(min=48.dp)) {Text("Layer base font ▾",maxLines=1,overflow=TextOverflow.Ellipsis)}
                        DropdownMenu(baseMenu,{baseMenu=false}) {listOf("fluxx.sans" to "Inter","fluxx.serif" to "Noto Serif","fluxx.mono" to "JetBrains Mono").forEach {(id,label)->DropdownMenuItem(text={Text(label)},onClick={baseMenu=false;baseFont?.invoke(id)})}}
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun AxisScrubRow(axis: FontAxisMetadata,value: Float,begin: ()->Unit,preview: (Float)->Unit,finish: ()->Unit,cancel: ()->Unit,reset: ()->Unit,exact: ()->Unit,enabled: Boolean) {
    // A drag publishes at most once per display frame; a preset/reference change cancels pending work.
    var draft by remember(axis.tag) {mutableFloatStateOf(value)}
    var pending by remember(axis.tag) {mutableStateOf<Float?>(null)}
    var dragging by remember(axis.tag) {mutableStateOf(false)}
    val latestPreview by rememberUpdatedState(preview);val currentBegin by rememberUpdatedState(begin)
    val currentFinish by rememberUpdatedState(finish);val currentCancel by rememberUpdatedState(cancel)
    val currentValue by rememberUpdatedState(value)
    val density=LocalDensity.current;val view=LocalView.current
    LaunchedEffect(value) {if(!dragging) draft=value}
    LaunchedEffect(dragging) {while(dragging) {withFrameNanos { };pending?.let {pending=null;latestPreview(it)}}}
    var lastTime by remember {mutableLongStateOf(0L)};var lastHaptic by remember {mutableLongStateOf(0L)}
    DisposableEffect(axis.tag) {onDispose {if(dragging) currentCancel()}}
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.width(48.dp).height(48.dp).combinedClickable(enabled=enabled,onClick=exact,onLongClick=exact)
            .semantics {contentDescription="Scrub ${axis.name} (${axis.tag}); tap for exact value"}
            .pointerInput(axis.tag,enabled) {if(enabled) detectHorizontalDragGestures(onDragStart={draft=currentValue;dragging=true;lastTime=0;currentBegin()},
                onHorizontalDrag={change,dx->change.consume();val now=change.uptimeMillis;val dt=if(lastTime==0L) 16L else (now-lastTime).coerceAtLeast(1);lastTime=now
                    val old=draft;val dp=dx/density.density
                    draft=(draft+TextScrubTuning.delta(TextControl.BOX,dp,dp*1000/dt)*(axis.maximum-axis.minimum)/TextScrubTuning.AXIS_PERCENT).coerceIn(axis.minimum,axis.maximum);pending=draft
                    if((old-axis.default)*(draft-axis.default)<=0 && now-lastHaptic>=TextScrubTuning.HAPTIC_INTERVAL_MS) {view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);lastHaptic=now}
                },onDragEnd={pending=null;latestPreview(draft);dragging=false;currentFinish()},onDragCancel={pending=null;dragging=false;draft=currentValue;currentCancel()})},contentAlignment=Alignment.Center) {
            Icon(TextInspectorIcons.icon("AXIS"),null,Modifier.size(22.dp))
        }
        TextButton(enabled=enabled,onClick=exact,modifier=Modifier.weight(1f)) {Text("${axis.name} (${axis.tag})",maxLines=1,overflow=TextOverflow.Ellipsis)}
        TextButton(enabled=enabled,onClick=exact) {Text("%.2f".format(java.util.Locale.ROOT,draft))}
        TextToolIcon("RESET","Reset ${axis.name} to default",reset,enabled)
    }
}
