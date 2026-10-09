package com.fluxx.android.ui.inspector

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import android.view.HapticFeedbackConstants
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fluxx.android.fonts.*
import com.fluxx.android.model.*
import kotlinx.coroutines.launch

/** Independently virtualized family and axis viewports; never scrolls the inspector. */
@Composable internal fun TextFontsPopup(reference: TextFontRef?,layerFont: String,scopeLabel: String,
    apply: (TextFontRef?)->Unit,dismiss: ()->Unit,
    axisBegin: ()->Unit,axisPreview: (TextFontRef)->Unit,axisFinish: ()->Unit,axisCancel: ()->Unit,busy: Boolean=false,
    baseFont: ((String)->Unit)?=null) {
    val context=LocalContext.current;val repository=remember(context) {FontLibraryRepository.getInstance(context)}
    val entries by repository.state.collectAsState();val scope=rememberCoroutineScope()
    var search by remember {mutableStateOf("")};var error by remember {mutableStateOf<String?>(null)}
    var importing by remember {mutableStateOf(false)};var deletion by remember {mutableStateOf<FontLibraryEntry?>(null)}
    var styles by remember {mutableStateOf(false)};var exactAxis by remember {mutableStateOf<FontAxisMetadata?>(null)}
    var baseMenu by remember {mutableStateOf(false)}
    val selectedEntry=entries.find {it.sha256==reference?.sha256}
    val selectedFace=selectedEntry?.faces?.find {it.index==reference?.faceIndex}
    val rows=remember(entries,search) {entries.flatMap {entry->entry.faces.map {entry to it}}.filter {(_,face)->"${face.family} ${face.style}".contains(search,true)}}
    LaunchedEffect(repository) {runCatching {repository.load()}.onFailure {error=it.message}}
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->if(uri!=null) scope.launch {
        importing=true;error=null
        try {repository.import(uri)} catch(failure: Exception) {error=failure.message ?: "Font import failed"} finally {importing=false}
    }}
    val configuration=LocalConfiguration.current
    val large=LocalDensity.current.fontScale>1.2f || configuration.screenHeightDp<360
    val availableHeight=(configuration.screenHeightDp-64).coerceAtLeast(120).dp
    Dialog(dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.widthIn(max=480.dp).fillMaxWidth().heightIn(max=availableHeight),shape=MaterialTheme.shapes.large) {
            Column(Modifier.padding(8.dp).then(if(large) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
                Row(Modifier.fillMaxWidth().height(48.dp)) {Text("Fonts · $scopeLabel",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall);TextToolIcon("DONE","Close Fonts",dismiss)}
                Row(Modifier.fillMaxWidth().heightIn(min=56.dp)) {
                    OutlinedTextField(search,{search=it},singleLine=true,label={Text("Find font")},modifier=Modifier.weight(1f))
                    TextToolIcon("IMPORT",if(importing) "Importing font" else "Import font",{importer.launch(arrayOf("*/*"))},!importing)
                    TextToolIcon("DELETE","Delete selected font file",{deletion=selectedEntry},selectedEntry!=null && !importing)
                }
                if(reference?.sha256!=null && selectedFace==null) Text("Requested ${reference.family} ${reference.styleName} missing · Inter fallback · identity retained",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelSmall)
                error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelSmall,maxLines=2)}
                LazyColumn(Modifier.fillMaxWidth().heightIn(min=48.dp,max=144.dp).weight(1f,false)) {
                    items(listOf("fluxx.sans" to "Inter","fluxx.serif" to "Noto Serif","fluxx.mono" to "JetBrains Mono").filter {it.second.contains(search,true)},key={it.first}) {(id,name)->
                        TextButton(onClick={apply(TextFontRef(id,reference?.takeIf {it.sha256==null}?.style ?: TextFaceStyle.REGULAR))},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text(name+(if(reference?.family==id && reference.sha256==null || reference==null && layerFont.startsWith(id)) " ✓" else ""))}
                    }
                    items(rows,key={(entry,face)->"${entry.sha256}:${face.index}"}) {(entry,face)->
                        TextButton(onClick={apply(face.reference(entry.sha256))},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("${face.family} · ${face.style} · face ${face.index}"+(if(reference?.sha256==entry.sha256 && reference.faceIndex==face.index) " ✓" else ""))}
                    }
                }
                Box {
                    val instance=selectedFace?.instances?.find {it.index==reference?.namedInstance}
                    val modified=instance!=null && selectedFace.axes.indices.any {index->reference?.axes?.find {it.tag==selectedFace.axes[index].tag}?.value?.staticValue!=instance.values[index]}
                    TextButton(onClick={styles=true},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                        Text("Style: "+(instance?.name?.plus(if(modified) " · modified" else "") ?: selectedFace?.style ?: reference?.style?.name ?: "Regular"))
                    }
                    DropdownMenu(styles,{styles=false}) {
                        if(selectedFace!=null && reference!=null && selectedFace.axes.isNotEmpty()) {
                            DropdownMenuItem(text={Text("Default instance")},onClick={styles=false;apply(selectedFace.selectInstance(reference,-1))})
                            selectedFace.instances.forEach {preset->DropdownMenuItem(text={Text(preset.name)},onClick={styles=false;apply(selectedFace.selectInstance(reference,preset.index))})}
                        } else if(reference?.sha256==null) TextFaceStyle.entries.forEach {style->DropdownMenuItem(text={Text(style.name)},onClick={styles=false;apply(TextFontRef(reference?.family ?: layerFont.removeSuffix(".bold-italic").removeSuffix(".italic").removeSuffix(".bold"),style))})}
                        else DropdownMenuItem(text={Text("Static imported face · choose another face above")},enabled=false,onClick={})
                    }
                }
                if(selectedFace!=null && reference!=null && selectedFace.axes.isNotEmpty()) {
                    Text("Static axes · Source Text Hold",style=MaterialTheme.typography.labelSmall)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(min=48.dp,max=144.dp).weight(1f,false)) {
                        items(selectedFace.axes,key={it.tag}) {axis->
                            val value=reference.axes.find {it.tag==axis.tag}?.value?.staticValue ?: axis.default
                            AxisScrubRow(axis,value,axisBegin,{v->val requested=reference.axes.associate {it.tag to it.value.staticValue}.toMutableMap();requested[axis.tag]=v
                                axisPreview(selectedFace.reference(requireNotNull(reference.sha256),reference.namedInstance,requested))},axisFinish,axisCancel,
                                {val requested=reference.axes.associate {it.tag to it.value.staticValue}.toMutableMap();requested[axis.tag]=axis.default;apply(selectedFace.reference(requireNotNull(reference.sha256),reference.namedInstance,requested))},{exactAxis=axis},!busy)
                        }
                    }
                }
                Row {
                    TextButton(onClick={apply(null)},modifier=Modifier.heightIn(min=48.dp)) {Text("Inherit layer font")}
                    Box {TextButton(enabled=baseFont!=null,onClick={baseMenu=true}) {Text("Layer base font")}
                        DropdownMenu(baseMenu,{baseMenu=false}) {listOf("fluxx.sans" to "Inter","fluxx.serif" to "Noto Serif","fluxx.mono" to "JetBrains Mono").forEach {(id,label)->DropdownMenuItem(text={Text(label)},onClick={baseMenu=false;baseFont?.invoke(id)})}}
                    }
                }
            }
        }
    }
    deletion?.let {entry->AlertDialog(onDismissRequest={deletion=null},title={Text("Delete font file?")},text={Text("Remove all ${entry.faces.size} faces? Projects retain requested identities and use fallback until restored.")},
        confirmButton={TextButton(onClick={deletion=null;scope.launch {runCatching {repository.delete(entry.sha256)}.onFailure {error=it.message}}}) {Text("Delete")}},dismissButton={TextButton(onClick={deletion=null}) {Text("Cancel")}})}
    exactAxis?.let {axis->if(selectedFace!=null && reference!=null) {
        var input by remember(axis.tag,reference) {mutableStateOf((reference.axes.find {it.tag==axis.tag}?.value?.staticValue ?: axis.default).toString())}
        val value=input.toFloatOrNull()?.takeIf {it.isFinite() && it in axis.minimum..axis.maximum}
        AlertDialog(onDismissRequest={exactAxis=null},title={Text("${axis.name} (${axis.tag})")},text={Column {Text("${axis.minimum} … ${axis.maximum}");OutlinedTextField(input,{input=it},singleLine=true,isError=value==null)}},
            confirmButton={TextButton(enabled=value!=null,onClick={val requested=reference.axes.associate {it.tag to it.value.staticValue}.toMutableMap();requested[axis.tag]=requireNotNull(value)
                apply(selectedFace.reference(requireNotNull(reference.sha256),reference.namedInstance,requested));exactAxis=null}) {Text("Done")}},
            dismissButton={TextButton(onClick={exactAxis=null}) {Text("Cancel")};TextButton(onClick={input=axis.default.toString()}) {Text("Default")}})
    }}
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
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp)) {
        Box(Modifier.width(48.dp).height(48.dp).combinedClickable(enabled=enabled,onClick=exact,onLongClick=exact)
            .semantics {contentDescription="Scrub ${axis.name} (${axis.tag}); tap for exact value"}
            .pointerInput(axis.tag,enabled) {if(enabled) detectHorizontalDragGestures(onDragStart={draft=currentValue;dragging=true;lastTime=0;currentBegin()},
                onHorizontalDrag={change,dx->change.consume();val now=change.uptimeMillis;val dt=if(lastTime==0L) 16L else (now-lastTime).coerceAtLeast(1);lastTime=now
                    val old=draft;val dp=dx/density.density
                    draft=(draft+TextScrubTuning.delta(TextControl.BOX,dp,dp*1000/dt)*(axis.maximum-axis.minimum)/TextScrubTuning.AXIS_PERCENT).coerceIn(axis.minimum,axis.maximum);pending=draft
                    if((old-axis.default)*(draft-axis.default)<=0 && now-lastHaptic>=TextScrubTuning.HAPTIC_INTERVAL_MS) {view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);lastHaptic=now}
                },onDragEnd={pending=null;latestPreview(draft);dragging=false;currentFinish()},onDragCancel={pending=null;dragging=false;draft=currentValue;currentCancel()})},contentAlignment=androidx.compose.ui.Alignment.Center) {
            Icon(TextInspectorIcons.icon("AXIS"),null,Modifier.size(22.dp))
        }
        TextButton(enabled=enabled,onClick=exact,modifier=Modifier.weight(1f)) {Text("${axis.name} (${axis.tag})",maxLines=1)}
        TextButton(enabled=enabled,onClick=exact) {Text("%.2f".format(java.util.Locale.ROOT,draft))}
        TextToolIcon("RESET","Reset ${axis.name} to default",reset,enabled)
    }
}
