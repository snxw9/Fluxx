package com.fluxx.android.ui.inspector

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fluxx.android.engine.PreparedTextData
import com.fluxx.android.fonts.*
import com.fluxx.android.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One library face at a time, fixed height; no scrolling sheet or Android Typeface parsing. */
@Composable
internal fun FontLibraryControls(apply: (TextFontRef)->Unit) {
    val context=LocalContext.current
    val repository=remember(context) { FontLibraryRepository.getInstance(context) }
    val entries by repository.state.collectAsState()
    val scope=rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var index by remember { mutableIntStateOf(0) }
    var deletion by remember { mutableStateOf<FontLibraryEntry?>(null) }
    LaunchedEffect(repository) { runCatching { repository.load() }.onFailure { error=it.message } }
    val faces=remember(entries) { entries.flatMap { entry -> entry.faces.map { entry to it } } }
    val selected=faces.getOrNull(index.coerceIn(0,maxOf(0,faces.lastIndex)))
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) scope.launch {
            busy=true;error=null
            try { repository.import(uri) }
            catch(e: Exception) { error=e.message ?: "Font import failed" }
            finally { busy=false }
        }
    }
    Row(Modifier.fillMaxWidth()) {
        TextButton(enabled=!busy,onClick={importer.launch(arrayOf("*/*"))}) { Text(if(busy) "Importing…" else "Import") }
        TextButton(enabled=selected!=null && !busy,onClick={selected?.let { apply(it.second.reference(it.first.sha256)) }}) { Text("Use face") }
        TextButton(enabled=selected!=null && !busy,onClick={deletion=selected?.first}) { Text("Delete") }
    }
    if(selected!=null) {
        val entry=selected.first;val face=selected.second
        Row(Modifier.fillMaxWidth()) {
            TextButton(enabled=index>0,onClick={index--}) { Text("‹") }
            Text("${face.family} · ${face.style}",Modifier.weight(1f).padding(top=10.dp),maxLines=1,style=MaterialTheme.typography.labelMedium)
            TextButton(enabled=index<faces.lastIndex,onClick={index++}) { Text("›") }
        }
        val colour=MaterialTheme.colorScheme.onSurface.toArgb()
        val preview by produceState<Bitmap?>(null,entry.sha256,face.index,colour) {
            value=withContext(Dispatchers.IO) {
                runCatching {
                    val reference=face.reference(entry.sha256)
                    val document=TextSourceDocument(face.preview,FrozenList.of(listOf(TextStyleRun(0,face.preview.length,TextRunStyle(font=reference)))))
                    val packet=ImportedTextPreparation.prepare(context,"fluxx.sans",document,true).packet
                    Bitmap.createBitmap(PreparedTextData.preview(packet,240,48,colour),240,48,Bitmap.Config.ARGB_8888)
                }.onFailure { error=it.message }.getOrNull()
            }
        }
        Box(Modifier.fillMaxWidth().height(40.dp)) {
            if(error!=null) Text(error.orEmpty(),maxLines=2,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelSmall)
            else preview?.let { Image(it.asImageBitmap(),contentDescription="${face.family} ${face.style} preview",modifier=Modifier.fillMaxWidth().height(40.dp)) }
        }
    } else Text("Import a monochrome outline font",style=MaterialTheme.typography.labelSmall)
    if(selected==null) error?.let { Text(it,maxLines=2,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelSmall) }
    deletion?.let { entry ->
        AlertDialog(onDismissRequest={deletion=null},title={Text("Delete font?")},
            text={Text("Remove all ${entry.faces.size} faces in this library file? Projects keep their requested reference and use Inter until the font is restored.")},
            confirmButton={TextButton(onClick={deletion=null;scope.launch { runCatching { repository.delete(entry.sha256) }.onFailure { error=it.message } }}) { Text("Delete") }},
            dismissButton={TextButton(onClick={deletion=null}) { Text("Cancel") }})
    }
}

@Composable
internal fun FontAxisControls(reference: TextFontRef?,apply: (TextFontRef)->Unit) {
    val context=LocalContext.current
    val repository=remember(context) { FontLibraryRepository.getInstance(context) }
    val entries by repository.state.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(repository) { runCatching { repository.load() }.onFailure { error=it.message } }
    val entry=entries.find { it.sha256==reference?.sha256 }
    val face=entry?.faces?.find { it.index==reference?.faceIndex }
    var axisIndex by remember(reference?.sha256,reference?.faceIndex) { mutableIntStateOf(0) }
    if(face==null || reference==null || face.axes.isEmpty()) {
        Text(error ?: "Select an imported variable font to edit static axes",style=MaterialTheme.typography.labelSmall,maxLines=2);return
    }
    val instanceIndex=face.instances.indexOfFirst { it.index==reference.namedInstance }
    fun chooseInstance(index: Int) {
        apply(face.selectInstance(reference,face.instances.getOrNull(index)?.index ?: -1))
    }
    Row {
        TextButton(enabled=instanceIndex>=0,onClick={chooseInstance(instanceIndex-1)}) { Text("‹") }
        val named=face.instances.getOrNull(instanceIndex)
        TextButton(onClick={chooseInstance(instanceIndex)}) { Text(named?.name ?: "Default instance",maxLines=1) }
        TextButton(enabled=instanceIndex<face.instances.lastIndex,onClick={chooseInstance(instanceIndex+1)}) { Text("›") }
    }
    val axis=face.axes[axisIndex.coerceIn(0,face.axes.lastIndex)]
    val requested=reference.axes.find { it.tag==axis.tag }?.value?.staticValue ?: axis.default
    var value by remember(reference,axis.tag) { mutableFloatStateOf(requested.coerceIn(axis.minimum,axis.maximum)) }
    Row {
        TextButton(enabled=axisIndex>0,onClick={axisIndex--}) { Text("‹") }
        Text("${axis.name}: $value",Modifier.weight(1f).padding(top=10.dp),maxLines=1,style=MaterialTheme.typography.labelSmall)
        TextButton(enabled=axisIndex<face.axes.lastIndex,onClick={axisIndex++}) { Text("›") }
    }
    fun commit() {
        val values=reference.axes.associate { it.tag to it.value.staticValue }.toMutableMap();values[axis.tag]=value
        apply(face.reference(requireNotNull(reference.sha256),reference.namedInstance,values))
    }
    // Keep the existing axis page/slider. Last value wins on each display frame.
    var pending by remember(reference,axis.tag) { mutableStateOf<Float?>(null) }
    val currentCommit by rememberUpdatedState(::commit)
    LaunchedEffect(reference,pending) {
        if(pending!=null) {withFrameNanos { };pending=null;currentCommit()}
    }
    if(axis.minimum<axis.maximum) Slider(value,onValueChange={value=it;pending=it},onValueChangeFinished={pending=null;commit()},valueRange=axis.minimum..axis.maximum)
}
