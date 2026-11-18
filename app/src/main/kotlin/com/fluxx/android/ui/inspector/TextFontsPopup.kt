package com.fluxx.android.ui.inspector

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fluxx.android.fonts.*
import com.fluxx.android.model.*
import com.fluxx.android.ui.theme.*
import kotlinx.coroutines.launch

/** Left-column entries of the Fonts page. "Default" merges the old Inherit and Layer base entries. */
internal sealed interface FontFamilyItem {
    val name: String
    data object Default: FontFamilyItem { override val name="Default" }
    data class Bundled(val id: String,override val name: String): FontFamilyItem
    data class Imported(override val name: String,val faces: List<Pair<FontLibraryEntry,FontFaceMetadata>>): FontFamilyItem
}

/** One status line on the Fonts page; errors are tappable to dismiss. */
internal data class FontStatusLine(val text: String,val kind: Kind,val dismissible: Boolean) {enum class Kind {ERROR,WORKING,DONE,MISSING}}

internal object TextFontsModel {
    /** How long the "Imported …" confirmation stays. */
    const val CONFIRMATION_MS=4_000L
    /**
     * Status lines in priority order: page errors (load/delete), import failure, import progress / confirmation, then the
     * missing-font notice. Nothing hides an error any more (the old ordered `when` let the missing line win).
     */
    fun statusLines(error: String?,status: FontImportStatus,missing: String?): List<FontStatusLine> = buildList {
        if(error!=null) add(FontStatusLine(error,FontStatusLine.Kind.ERROR,true))
        when(status) {
            is FontImportStatus.Failed->add(FontStatusLine(status.message,FontStatusLine.Kind.ERROR,true))
            FontImportStatus.Importing->add(FontStatusLine("Importing font…",FontStatusLine.Kind.WORKING,false))
            is FontImportStatus.Imported->add(FontStatusLine("Imported ${status.family} (${status.faces} ${if(status.faces==1) "face" else "faces"})",FontStatusLine.Kind.DONE,true))
            FontImportStatus.Idle->Unit
        }
        if(missing!=null) add(FontStatusLine(missing,FontStatusLine.Kind.MISSING,false))
    }
    val BUNDLED=listOf("fluxx.sans" to "Inter","fluxx.serif" to "Noto Serif","fluxx.mono" to "JetBrains Mono")
    fun bundledBase(id: String)=id.removeSuffix(".bold-italic").removeSuffix(".italic").removeSuffix(".bold")
    /** Default first, then bundled, then imported faces grouped by family name; filtered by the search text. */
    fun families(entries: List<FontLibraryEntry>,search: String): List<FontFamilyItem> {
        val imported=entries.flatMap {e->e.faces.map {e to it}}.groupBy {it.second.family}.map {(name,faces)->FontFamilyItem.Imported(name,faces)}.sortedBy {it.name.lowercase()}
        return (listOf(FontFamilyItem.Default)+BUNDLED.map {FontFamilyItem.Bundled(it.first,it.second)}+imported).filter {search.isBlank() || it.name.contains(search.trim(),true)}
    }
    fun selected(reference: TextFontRef?,entries: List<FontLibraryEntry>): FontFamilyItem = when {
        reference==null->FontFamilyItem.Default
        reference.sha256==null->BUNDLED.firstOrNull {it.first==bundledBase(reference.family)}?.let {FontFamilyItem.Bundled(it.first,it.second)} ?: FontFamilyItem.Default
        else->entries.flatMap {e->e.faces.map {e to it}}.filter {it.first.sha256==reference.sha256}.firstOrNull {it.second.index==reference.faceIndex}
            ?.let {hit->FontFamilyItem.Imported(hit.second.family,entries.flatMap {e->e.faces.map {e to it}}.filter {it.second.family==hit.second.family})}
            ?: FontFamilyItem.Default
    }
}

/**
 * Fonts as a page inside the inspector frame (no popup, no sheet in a sheet): `←` returns to the tab. Two columns:
 * families (search, import, delete for imported files) and the selected family's styles / named instances. A
 * row of axis tiles appears for variable faces; each axis opens the shared slider popover. Library hashing,
 * isolated-process import and missing-font identity are unchanged (all in [FontLibraryRepository]).
 */
@Composable internal fun TextFontsPage(reference: TextFontRef?,layerFont: String,scopeLabel: String,
    apply: (TextFontRef?)->Unit,
    axisBegin: ()->Unit,axisPreview: (TextFontRef)->Unit,axisFinish: ()->Unit,axisCancel: ()->Unit,busy: Boolean=false,
    baseFont: ((String)->Unit)?=null,hit: androidx.compose.ui.unit.Dp=40.dp,modifier: Modifier=Modifier) {
    val context=LocalContext.current;val repository=remember(context) {FontLibraryRepository.getInstance(context)}
    val entries by repository.state.collectAsState();val scope=rememberCoroutineScope()
    var search by remember {mutableStateOf("")};var error by remember {mutableStateOf<String?>(null)}
    val importStatus by repository.importStatus.collectAsState();val importing=importStatus is FontImportStatus.Importing
    var deletion by remember {mutableStateOf<List<FontLibraryEntry>?>(null)}
    var browsing by remember {mutableStateOf<FontFamilyItem?>(null)}
    var axisOpen by remember {mutableStateOf<String?>(null)}
    val applied=TextFontsModel.selected(reference,entries)
    val family=browsing ?: applied
    val families=remember(entries,search) {TextFontsModel.families(entries,search)}
    val selectedEntry=entries.find {it.sha256==reference?.sha256}
    val selectedFace=selectedEntry?.faces?.find {it.index==reference?.faceIndex}
    LaunchedEffect(repository) {runCatching {repository.load()}.onFailure {error=FontImportErrors.message(it)}}
    // The import runs in the repository scope: leaving this page or recomposing can never cancel it or lose its result.
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->if(uri!=null) repository.startImport(uri)}
    // A success confirmation is brief; errors stay until tapped or the next action.
    LaunchedEffect(importStatus) {if(importStatus is FontImportStatus.Imported) {kotlinx.coroutines.delay(TextFontsModel.CONFIRMATION_MS);repository.dismissImportStatus()}}
    Column(modifier.testTag("text-fonts")) {
        Row(Modifier.fillMaxWidth().height(hit),verticalAlignment=Alignment.CenterVertically) {
            Row(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(10.dp)).background(SurfaceHigh).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                Icon(InspectorIcons.icon("SEARCH"),null,Modifier.size(16.dp),tint=TextSecondary)
                BasicTextField(search,{search=it},singleLine=true,textStyle=MaterialTheme.typography.bodySmall.copy(color=TextPrimary),
                    cursorBrush=SolidColor(TextPrimary),modifier=Modifier.weight(1f).padding(start=6.dp).semantics {contentDescription="Find font, $scopeLabel"},
                    decorationBox={inner->Box(contentAlignment=Alignment.CenterStart) {
                        if(search.isEmpty()) Text("Find font",style=MaterialTheme.typography.bodySmall,color=TextSecondary,maxLines=1)
                        inner()}})
            }
            InspectorIconButton(InspectorIcons.icon("IMPORT"),if(importing) "Importing font" else "Import font",enabled=!importing) {error=null;repository.dismissImportStatus();importer.launch(arrayOf("*/*"))}
            val imported=family as? FontFamilyItem.Imported
            InspectorIconButton(InspectorIcons.icon("DELETE"),"Delete imported font file",enabled=imported!=null && !importing) {error=null;repository.dismissImportStatus();deletion=imported?.faces?.map {it.first}?.distinctBy {it.sha256}}
        }
        when {
            deletion!=null -> Row(Modifier.fillMaxWidth().height(hit),verticalAlignment=Alignment.CenterVertically) {
                val files=requireNotNull(deletion)
                Text("Delete ${files.size} file(s), ${files.sumOf {it.faces.size}} faces? Projects keep the request.",Modifier.weight(1f),color=TextPrimary,
                    maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall)
                TextButton(onClick={deletion=null}) {Text("Cancel",color=TextSecondary)}
                TextButton(onClick={deletion=null;browsing=null;scope.launch {files.forEach {f->try {repository.delete(f.sha256)} catch(failure: Throwable) {if(failure is kotlinx.coroutines.CancellationException) throw failure;error=FontImportErrors.message(failure)}}}}) {Text("Delete",color=TextPrimary)}
            }
        }
        // Every status line is shown, errors first: the missing-font line can no longer hide an import/delete/load error.
        val missing=if(reference?.sha256!=null && selectedFace==null) "Requested ${reference.family} ${reference.styleName} missing · Inter fallback · identity retained" else null
        for(line in TextFontsModel.statusLines(error,importStatus,missing)) Text(line.text,
            Modifier.fillMaxWidth().testTag("text-fonts-status-${line.kind.name.lowercase()}").then(if(line.dismissible) Modifier.clickable(onClickLabel="Dismiss") {
                if(line.text==error) error=null else repository.dismissImportStatus()} else Modifier),
            color=if(line.kind==FontStatusLine.Kind.ERROR) MaterialTheme.colorScheme.error else TextSecondary,
            style=MaterialTheme.typography.labelSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
        Row(Modifier.weight(1f).fillMaxWidth().padding(top=2.dp)) {
            LazyColumn(Modifier.weight(1.1f).fillMaxHeight().testTag("text-fonts-families")) {
                items(families,key={it.name+(it as? FontFamilyItem.Bundled)?.id}) {item->
                    val on=item==applied || (item is FontFamilyItem.Imported && applied is FontFamilyItem.Imported && item.name==applied.name)
                    val browsed=item==family || (item is FontFamilyItem.Imported && family is FontFamilyItem.Imported && item.name==family.name)
                    FontRow(item.name,on,browsed,when(item) {is FontFamilyItem.Bundled->when(item.id) {"fluxx.serif"->FontFamily.Serif;"fluxx.mono"->FontFamily.Monospace;else->FontFamily.SansSerif};else->null}) {
                        browsing=item
                        when(item) {
                            FontFamilyItem.Default->apply(null)
                            is FontFamilyItem.Bundled->apply(TextFontRef(item.id,reference?.takeIf {it.sha256==null}?.style ?: TextFaceStyle.REGULAR))
                            is FontFamilyItem.Imported->if(!on) item.faces.firstOrNull()?.let {(e,f)->apply(f.reference(e.sha256))}
                        }
                    }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Border))
            LazyColumn(Modifier.weight(1f).fillMaxHeight().padding(start=4.dp).testTag("text-fonts-styles")) {
                when(val f=family) {
                    FontFamilyItem.Default->{
                        item {Text("Layer base",Modifier.padding(4.dp),color=TextSecondary,style=MaterialTheme.typography.labelSmall)}
                        items(TextFontsModel.BUNDLED,key={it.first}) {(id,name)->
                            FontRow(name,TextFontsModel.bundledBase(layerFont)==id,false,null,enabled=baseFont!=null) {baseFont?.invoke(id)}
                        }
                        if(baseFont==null) item {Text("Clear the selection to change the layer base font",Modifier.padding(4.dp),color=TextSecondary,style=MaterialTheme.typography.labelSmall)}
                    }
                    is FontFamilyItem.Bundled->items(TextFaceStyle.entries) {style->
                        FontRow(style.name.lowercase().replace('_',' ').replaceFirstChar {it.uppercase()},reference?.sha256==null && reference?.family==f.id && reference.style==style,false,null) {
                            apply(TextFontRef(f.id,style))}
                    }
                    is FontFamilyItem.Imported->{
                        items(f.faces,key={(e,face)->"${e.sha256}:${face.index}"}) {(e,face)->
                            FontRow(face.style+if(e.faces.size>1) " · face ${face.index}" else "",reference?.sha256==e.sha256 && reference.faceIndex==face.index,false,null) {apply(face.reference(e.sha256))}
                        }
                        if(selectedFace!=null && reference!=null && selectedFace.instances.isNotEmpty() && f.faces.any {it.second==selectedFace}) {
                            val instance=selectedFace.instances.find {it.index==reference.namedInstance}
                            val modified=instance!=null && selectedFace.axes.indices.any {i->reference.axes.find {it.tag==selectedFace.axes[i].tag}?.value?.staticValue!=instance.values[i]}
                            item {Text("Instances",Modifier.padding(4.dp),color=TextSecondary,style=MaterialTheme.typography.labelSmall)}
                            item {FontRow("Default instance",reference.namedInstance<0,false,null) {apply(selectedFace.selectInstance(reference,-1))}}
                            items(selectedFace.instances,key={"i${it.index}"}) {preset->
                                FontRow(preset.name+if(preset.index==reference.namedInstance && modified) " · modified" else "",preset.index==reference.namedInstance && !modified,false,null) {
                                    apply(selectedFace.selectInstance(reference,preset.index))}
                            }
                        } else if(selectedFace!=null && selectedFace.axes.isEmpty() && f.faces.size==1) item {
                            Text("Static face: other styles are separate files",Modifier.padding(4.dp),color=TextSecondary,style=MaterialTheme.typography.labelSmall)}
                    }
                }
            }
        }
        // Variable faces: one row of axis tiles; each opens the shared slider popover (Source Text Hold).
        if(selectedFace!=null && reference!=null && selectedFace.axes.isNotEmpty()) {
            LazyRow(Modifier.fillMaxWidth().height(hit).testTag("text-fonts-axes"),horizontalArrangement=Arrangement.spacedBy(2.dp),verticalAlignment=Alignment.CenterVertically) {
                items(selectedFace.axes.filter {it.maximum>it.minimum},key={it.tag}) {axis->
                    val value=reference.axes.find {it.tag==axis.tag}?.value?.staticValue ?: axis.default
                    val limit=remember(axis) {PropertyLimit(axis.name,"",axis.minimum,axis.maximum,axis.minimum,axis.maximum,axis.minimum,axis.maximum,
                        axis.default.coerceIn(axis.minimum,axis.maximum),detentStep=(axis.maximum-axis.minimum)/10f,step=PropertyLimits.AXIS_STEP)}
                    fun ref(v: Float): TextFontRef {val requested=reference.axes.associate {it.tag to it.value.staticValue}.toMutableMap();requested[axis.tag]=v
                        return selectedFace.reference(requireNotNull(reference.sha256),reference.namedInstance,requested)}
                    InspectorTile(InspectorIcons.icon("AXIS"),"${axis.name} (${axis.tag})",Modifier.width(76.dp),hit=hit,active=axisOpen==axis.tag,enabled=!busy,
                        text=axis.tag,caption=PropertyLimits.format(limit,value),popover={
                            AnchoredPopover(axisOpen==axis.tag,{axisOpen=null},"text-axis-popover") {
                                SliderPopoverContent(limit,value,false,"${axis.name} (${axis.tag})",onReset={apply(ref(limit.default))},
                                    onBegin=axisBegin,onPreview={axisPreview(ref(it))},onFinish=axisFinish,onCancel=axisCancel,onTyped={apply(ref(it))},enabled=!busy)
                            }
                        }) {axisOpen=axis.tag}
                }
            }
        }
    }
}

@Composable private fun FontRow(name: String,applied: Boolean,browsed: Boolean,family: FontFamily?,enabled: Boolean=true,click: ()->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=40.dp).clip(RoundedCornerShape(8.dp)).background(if(browsed) SurfaceHigh else androidx.compose.ui.graphics.Color.Transparent)
        .clickable(enabled=enabled,role=Role.Button,onClick=click).semantics {contentDescription=name;stateDescription=if(applied) "Applied" else "Not applied"}
        .padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(name,Modifier.weight(1f),color=if(!enabled) TextDisabled else if(applied) TextPrimary else TextSecondary,fontFamily=family,
            style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
        if(applied) Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(TextPrimary))
    }
}
