package com.fluxx.android.ui.common.colorpicker

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fluxx.android.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable internal fun PaletteRow(repository: PaletteRepository,color: FluxxColor,onColorChange: (FluxxColor)->Unit) {
    val library by repository.state.collectAsState()
    val active=library.activePalette
    var expanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    fun perform(action: suspend ()->Unit) {
        if(busy) return
        busy=true
        scope.launch {
            try { action() }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { Toast.makeText(context,e.message ?: "Could not save palettes",Toast.LENGTH_LONG).show() }
            finally { busy=false }
        }
    }
    Row(Modifier.fillMaxWidth().height(22.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
        Box {
            PaletteGlyph("browser","Open palette browser",onClick={expanded=true})
            PaletteBrowser(expanded,{expanded=false},library.palettes,library.activePaletteId,busy,
                onSelectPalette={id -> perform { repository.setActivePalette(id); expanded=false }},
                onCreatePalette={perform { repository.createPalette() }},
                onDeletePalette={id -> perform { repository.deletePalette(id) }},
                onRenamePalette={id,name ->
                    // Rename is committed by the browser, never on a text keystroke.
                    scope.launch {
                        try { repository.renamePalette(id,name) }
                        catch(e:CancellationException) { throw e }
                        catch(e:Exception) { Toast.makeText(context,e.message ?: "Could not rename palette",Toast.LENGTH_LONG).show() }
                    }
                },
                onAddSwatch={id,sw -> perform { repository.addSwatch(id,sw) }},
                onRemoveSwatch={id,index -> perform { repository.removeSwatch(id,index) }},
                onReplaceSwatch={id,index,sw -> perform { repository.replaceSwatch(id,index,sw) }})
        }
        LazyRow(Modifier.weight(1f).fillMaxHeight(),horizontalArrangement=Arrangement.spacedBy(3.dp),verticalAlignment=Alignment.CenterVertically) {
            itemsIndexed(active?.swatches.orEmpty()) { _,swatch ->
                ColorPreviewSwatch(swatch,Modifier.size(18.dp).clip(RoundedCornerShape(3.dp)).clickable { onColorChange(swatch) }
                    .semantics { contentDescription="Colour ${swatch.toHexString(true)}" })
            }
        }
        val full=active!=null && active.swatches.size>=ColorPalette.MAX_SWATCHES
        PaletteGlyph("add",if(full) "Palette full (24/24)" else "Save colour to active palette",
            enabled=active!=null && !full && !busy,onClick={active?.let { perform { repository.addSwatch(it.id,color) } }})
    }
}

@Composable internal fun PaletteBrowser(expanded: Boolean,onDismiss: ()->Unit,palettes: List<ColorPalette>,
    activePaletteId: String?,busy: Boolean,onSelectPalette: (String)->Unit,onCreatePalette: ()->Unit,
    onDeletePalette: (String)->Unit,onRenamePalette: (String,String?)->Unit,
    onAddSwatch: (String,FluxxColor)->Unit,onRemoveSwatch: (String,Int)->Unit,
    onReplaceSwatch: (String,Int,FluxxColor)->Unit) {
    var editingId by remember { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var pickerId by remember { mutableStateOf<String?>(null) }
    var pickerIndex by remember { mutableStateOf<Int?>(null) }
    var pickerColor by remember { mutableStateOf(FluxxColor(1f,1f,1f)) }
    var nameText by remember { mutableStateOf("") }
    var nameFocused by remember { mutableStateOf(false) }
    var submittedName by remember { mutableStateOf<String?>(null) }
    val focus=LocalFocusManager.current
    val editing=palettes.firstOrNull { it.id==editingId }
    val picking=palettes.firstOrNull { it.id==pickerId }
    fun commitRename() {
        val palette=editing ?: return
        val name=nameText.trim().ifBlank { null }
        if(name!=submittedName) { submittedName=name; onRenamePalette(palette.id,name) }
    }
    fun dismiss() { commitRename(); focus.clearFocus(); editingId=null; pickerId=null; deleteId=null; onDismiss() }
    LaunchedEffect(expanded) { if(!expanded) { editingId=null; pickerId=null; deleteId=null } }
    DropdownMenu(expanded,onDismissRequest={dismiss()},modifier=Modifier.width(300.dp).heightIn(max=440.dp),
        containerColor=MaterialTheme.colorScheme.surface) {
        when {
            picking!=null -> {
                Text(if(pickerIndex==null) "Add swatch" else "Edit swatch",style=MaterialTheme.typography.titleSmall,
                    modifier=Modifier.padding(horizontal=12.dp,vertical=6.dp))
                // Explicit dimensions also bound intrinsic measurement inside DropdownMenu.
                FluxxColorPicker(pickerColor,{pickerColor=it},ColorPickerConfig(showEyedropper=false,showPalette=false),
                    modifier=Modifier.width(284.dp).height(170.dp).padding(horizontal=8.dp),paletteRepository=null)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                    TextButton(onClick={pickerId=null}) { Text("Cancel") }
                    TextButton(enabled=!busy && (pickerIndex!=null || picking.swatches.size<ColorPalette.MAX_SWATCHES),onClick={
                        val index=pickerIndex
                        if(index==null) onAddSwatch(picking.id,pickerColor) else onReplaceSwatch(picking.id,index,pickerColor)
                        pickerId=null
                    }) { Text(if(pickerIndex==null) "Add" else "Save") }
                }
            }
            editing!=null -> {
                Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    PaletteGlyph("back","Back to palettes",onClick={commitRename(); focus.clearFocus(); editingId=null})
                    BasicTextField(nameText,{nameText=it},singleLine=true,
                        textStyle=MaterialTheme.typography.bodyMedium.copy(color=MaterialTheme.colorScheme.onSurface),
                        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),
                        keyboardActions=KeyboardActions(onDone={commitRename(); focus.clearFocus()}),
                        modifier=Modifier.weight(1f).padding(horizontal=8.dp).onFocusChanged {
                            if(nameFocused && !it.isFocused) commitRename(); nameFocused=it.isFocused
                        }.semantics { contentDescription="Palette name (blank means Untitled)" })
                }
                LazyRow(Modifier.width(276.dp).height(58.dp).padding(horizontal=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(editing.swatches) { index,swatch ->
                        Column(horizontalAlignment=Alignment.CenterHorizontally) {
                            ColorPreviewSwatch(swatch,Modifier.size(26.dp).clip(RoundedCornerShape(3.dp)).clickable(enabled=!busy) {
                                commitRename(); focus.clearFocus(); pickerColor=swatch; pickerIndex=index; pickerId=editing.id
                            }.semantics { contentDescription="Edit colour ${swatch.toHexString(true)}" })
                            PaletteGlyph("remove","Remove swatch ${index+1}",enabled=!busy,onClick={onRemoveSwatch(editing.id,index)})
                        }
                    }
                }
                TextButton(enabled=!busy && editing.swatches.size<ColorPalette.MAX_SWATCHES,onClick={
                    commitRename(); focus.clearFocus(); pickerColor=FluxxColor(1f,1f,1f); pickerIndex=null; pickerId=editing.id
                }) { Text("Add swatch (${editing.swatches.size}/${ColorPalette.MAX_SWATCHES})") }
            }
            else -> {
                Text("Palettes",style=MaterialTheme.typography.titleSmall,modifier=Modifier.padding(12.dp))
                palettes.forEach { palette ->
                    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable(enabled=!busy) { onSelectPalette(palette.id) }) {
                            Text(palette.displayName,maxLines=1,overflow=TextOverflow.Ellipsis,
                                style=if(palette.id==activePaletteId) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
                            Text("${palette.swatches.size} swatches",style=MaterialTheme.typography.labelSmall)
                        }
                        PaletteGlyph("edit","Edit ${palette.displayName}",enabled=!busy,onClick={
                            editingId=palette.id; nameText=palette.name.orEmpty(); submittedName=palette.name; deleteId=null
                        })
                        Spacer(Modifier.width(10.dp))
                        PaletteGlyph("delete","Delete ${palette.displayName}",enabled=!busy && palettes.size>1,onClick={deleteId=palette.id})
                    }
                    if(deleteId==palette.id) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                        TextButton(onClick={deleteId=null}) { Text("Cancel") }
                        TextButton(enabled=!busy && palettes.size>1,onClick={onDeletePalette(palette.id); deleteId=null}) { Text("Confirm delete",color=MaterialTheme.colorScheme.error) }
                    }
                }
                HorizontalDivider()
                DropdownMenuItem(text={Text("New palette")},enabled=!busy,onClick=onCreatePalette)
            }
        }
    }
}

@Composable private fun PaletteGlyph(kind: String,label: String,enabled: Boolean=true,onClick: ()->Unit) {
    val ink=MaterialTheme.colorScheme.onSurface.copy(alpha=if(enabled) 1f else .38f)
    Canvas(Modifier.size(20.dp).clickable(enabled=enabled,role=Role.Button,onClick=onClick)
        .semantics { contentDescription=label; if(!enabled) disabled() }) {
        fun p(x:Float,y:Float)=Offset(size.width*x/20f,size.height*y/20f)
        fun line(x:Float,y:Float,xx:Float,yy:Float)=drawLine(ink,p(x,y),p(xx,yy),1.3.dp.toPx(),StrokeCap.Round)
        when(kind) {
            "add" -> { line(4f,10f,16f,10f); line(10f,4f,10f,16f) }
            "remove" -> { line(5f,5f,15f,15f); line(5f,15f,15f,5f) }
            "back" -> { line(13f,4f,7f,10f); line(7f,10f,13f,16f) }
            "edit" -> { line(4f,16f,6f,11f); line(6f,11f,14f,3f);line(14f,3f,17f,6f);line(17f,6f,9f,14f);line(9f,14f,4f,16f) }
            "delete" -> { line(4f,5f,16f,5f);line(8f,2f,12f,2f);line(6f,5f,6f,17f);line(6f,17f,14f,17f);line(14f,17f,14f,5f) }
            else -> for(y in listOf(3f,11f)) for(x in listOf(3f,11f))
                drawRect(ink,p(x,y),Size(size.width*.3f,size.height*.3f),style=Stroke(1.3.dp.toPx()))
        }
    }
}
