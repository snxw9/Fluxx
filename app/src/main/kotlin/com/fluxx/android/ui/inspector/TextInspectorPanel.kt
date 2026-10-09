package com.fluxx.android.ui.inspector

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import com.fluxx.android.ui.common.colorpicker.*
import kotlin.math.*

/** One fixed inspector surface. Popovers are bounded; only their accessibility fallback scrolls. */
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun TextInspectorPanel(layer: CompositionLayer,time: Long,frameRate: FrameRate,
    onAction: (EditorAction)->Unit,onFocus: (AnimPropertyType?)->Unit,
    onSourceBegin: ()->Unit,onSourceDraft: (String,Int,Int)->Unit,onFinish: ()->Unit,onCancel: ()->Unit,
    onValueBegin: ()->Unit,onPayloadPreview: (TextProperties)->Unit,
    onCharacter: (Int,Int,(TextRunStyle)->TextRunStyle)->Unit,
    onParagraph: (Int,Int,(TextParagraphStyle)->TextParagraphStyle)->Unit,onBox: (Float)->Unit,
    compositionWidth: Int,selection: TextSelectionRange?,onSelection: (TextSelectionRange?)->Unit,
    error: String?,onDismiss: ()->Unit,onTools: ()->Unit,
    onExplodePrepare: (TextExplodeOptions,(Result<TextExplodePlan>)->Unit)->Unit,
    onExplodeCommit: (TextExplodePlan,(String?)->Unit)->Unit,
    palette: PaletteRepository?=null,onEyedropper: (()->Unit)?=null) {
    val local=Math.subtractExact(time,layer.resolvedKeyframeAnchorUs)
    val document=layer.text.source.evaluateDocument(local)
    val editScope=TextEditScope(selection)
    var tabIndex by rememberSaveable(layer.id) {mutableIntStateOf(0)}
    var controlName by rememberSaveable(layer.id) {mutableStateOf(TextControl.SIZE.name)}
    val tab=TextTab.entries[tabIndex];val control=TextControl.valueOf(controlName)
    var mode by remember(layer.id) {mutableStateOf<TextInspectorMode>(TextInspectorMode.Browse)}
    var overflow by remember {mutableStateOf(false)}
    var animationMenu by remember {mutableStateOf(false)}
    var resetAnimation by remember {mutableStateOf(false)}
    var explode by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf<String?>(null)}
    var axisSnapshot by remember {mutableStateOf<Triple<CompositionLayer,Long,TextEditScope>?>(null)}
    val library=com.fluxx.android.fonts.FontLibraryRepository.getInstance(LocalContext.current)
    val fontEntries by library.state.collectAsState()
    var libraryReady by remember {mutableStateOf(false)}
    LaunchedEffect(library) {runCatching {library.load()};libraryReady=true}
    val missingFonts=libraryReady && document.runs.any {run->run.style.font?.let {ref->if(ref.sha256==null) ref.bundledId !in TextFontRef.BUNDLED_IDS else fontEntries.none {entry->entry.sha256==ref.sha256 && entry.faces.any {it.index==ref.faceIndex}}} ?: false}
    val manager=LocalFocusManager.current
    val density=LocalDensity.current
    val owner=if(tab==TextTab.ANIMATE) null else editScope.owner(control)
    val ownerLocal=frameRate.snap(time)-layer.resolvedKeyframeAnchorUs
    val animated=owner?.let {layer.text.animated(it)} ?: false
    val onKey=owner?.let {ownerLocal in layer.propertyKeyTimes(it)} ?: false
    val scopeLabel=if(editScope.selected) "Selection: ${editScope.count(document)} characters" else "Whole layer"
    val a=selection?.start ?: 0;val b=selection?.end ?: 0
    val style=document.runAt(a)?.style ?: TextRunStyle()
    val paragraph=document.paragraphs.getOrNull(document.text.take(a).count {it=='\n'}) ?: TextParagraphStyle()
    fun focus(c: TextControl) {controlName=c.name;onFocus(editScope.owner(c));message=null}
    fun pop(c: TextControl) {focus(c);mode=TextInspectorMode.Popover(c)}
    fun format(change: (TextRunStyle)->TextRunStyle) {onCharacter(a,b,change)}
    fun numeric(c: TextControl) {
        focus(c)
        if(!editScope.available(c)) {message="Clear selection to edit ${c.label}";return}
        onValueBegin();mode=TextInspectorMode.Numeric(TextNumericDraft(layer,time,frameRate,editScope,c))
    }
    fun cancelMode() {onCancel();manager.clearFocus();mode=TextInspectorMode.Browse}
    fun resetValue() {
        when(control) {
            TextControl.SOURCE->message="Source Text is preserved. Use Reset text style for formatting."
            TextControl.SIZE,TextControl.TRACKING,TextControl.LEADING,TextControl.BASELINE,TextControl.STROKE_WIDTH->{
                val d=TextNumericDraft(layer,time,frameRate,editScope,control)
                val value=if(control==TextControl.SIZE) if(editScope.selected) 100f else 72f else 0f
                onValueBegin();onPayloadPreview(d.payload(value));onFinish()
            }
            TextControl.FILL,TextControl.STROKE->if(editScope.selected) format {if(control==TextControl.FILL) it.copy(fill=null) else it.copy(stroke=null)}
                else onAction(TextEdits.colour(layer,time,requireNotNull(owner),if(control==TextControl.FILL) -1 else 0xff000000.toInt()))
            else->format {TextRunStyle()}
        }
    }
    BackHandler(mode!=TextInspectorMode.Browse || editScope.selected || explode) {
        when {explode->explode=false;mode!=TextInspectorMode.Browse->cancelMode();else->onSelection(null)}
    }
    DisposableEffect(layer.id) {onDispose {onCancel()}}
    if(explode) ExplodeTextDialog(layer,time,{explode=false},onExplodePrepare,onExplodeCommit)
    BoxWithConstraints(Modifier.fillMaxSize().testTag("text-inspector").padding(horizontal=8.dp)) {
        val fallback=TextInspectorBudget.needsOverlay(maxHeight.value,density.fontScale)
        val large=density.fontScale>1.2f
        val standard=TextInspectorBudget.bodyHeight(maxHeight.value,density.fontScale)>=156f
        Column(Modifier.fillMaxSize()) {
            if(mode==TextInspectorMode.Source) {
                Row(Modifier.fillMaxWidth().height(48.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("$scopeLabel · Source Text · Hold",Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall)
                    TextButton(onClick={cancelMode()}) {Text("Cancel")}
                    TextButton(onClick={manager.clearFocus();onFinish();mode=TextInspectorMode.Browse}) {Text("Done")}
                }
                SourceInlineEditor(document,selection,onSelection,onSourceDraft,Modifier.weight(1f)) {manager.clearFocus();onFinish();mode=TextInspectorMode.Browse}
            } else {
                Row(Modifier.fillMaxWidth().height(48.dp),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={focus(TextControl.SOURCE);onSourceBegin();mode=TextInspectorMode.Source},modifier=Modifier.weight(1f).fillMaxHeight()) {
                        Icon(TextInspectorIcons.control(TextControl.SOURCE),null,Modifier.size(20.dp))
                        Text(document.text.replace('\n',' ').ifEmpty {"Source Text"},Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text("Hold",style=MaterialTheme.typography.labelSmall)
                    }
                    Box {
                        TextToolIcon("OVERFLOW","Text utilities",{overflow=true})
                        DropdownMenu(overflow,{overflow=false}) {
                            DropdownMenuItem(text={Text("Explode Text")},onClick={overflow=false;onFinish();explode=true})
                            DropdownMenuItem(text={Text("Reset text style ($scopeLabel)")},onClick={overflow=false;format {TextRunStyle()}})
                            DropdownMenuItem(text={Text("Reset paragraph style ($scopeLabel)")},onClick={overflow=false;onParagraph(a,b) {TextParagraphStyle()}})
                            DropdownMenuItem(text={Text("Clear selection")},enabled=editScope.selected,onClick={overflow=false;onSelection(null)})
                            DropdownMenuItem(text={Text("Layer tools")},onClick={overflow=false;onTools()})
                            DropdownMenuItem(text={Text("Done editing text")},onClick={overflow=false;onDismiss()})
                        }
                    }
                }
                Text("$scopeLabel · ${editScope.title(control)} · "+when {
                    tab==TextTab.ANIMATE->"F3 unavailable"
                    editScope.selected->"Current Source Text value · Hold"
                    owner==AnimPropertyType.SOURCE_TEXT->"Source Text Hold · "+if(onKey) "On key" else if(animated) "Between keys" else "Animation off"
                    onKey->"On key";animated->"Animated · between keys";else->"Animation off"
                },Modifier.height((32*density.fontScale).dp).fillMaxWidth(),maxLines=if(large) 2 else 1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall)
                Row(Modifier.fillMaxWidth().height(40.dp)) {
                    TextTab.entries.forEach {t->TextButton(onClick={tabIndex=t.ordinal;onFocus(if(t==TextTab.ANIMATE) null else owner)},modifier=Modifier.weight(1f).fillMaxHeight()) {
                        Text(t.name.lowercase().replaceFirstChar {it.uppercase()},maxLines=1,style=MaterialTheme.typography.labelMedium,
                            color=if(t==tab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }}
                }
                Column(Modifier.weight(1f).fillMaxWidth().testTag("text-inspector-body")) {
                    if(tab==TextTab.ANIMATE) Text("Text animators — coming with F3",style=MaterialTheme.typography.bodySmall)
                    else if(fallback) TextButton(onClick={pop(if(tab==TextTab.CHARACTER) TextControl.FORMAT else TextControl.ALIGNMENT)}) {Text("Open ${tab.name.lowercase()} controls")}
                    else if(tab==TextTab.CHARACTER) {
                        Row(Modifier.height(48.dp).fillMaxWidth()) {
                            TextCell(TextControl.FONT,(if(missingFonts) "Missing · " else "")+(style.font?.let {"${it.family} ${it.styleName}"} ?: layer.text.fontId),Modifier.weight(2f),control==TextControl.FONT) {focus(TextControl.FONT);mode=TextInspectorMode.Fonts}
                            NumericCell(layer,time,frameRate,editScope,TextControl.SIZE,Modifier.weight(1f),control==TextControl.SIZE,::numeric)
                            if(!large) TextCell(TextControl.PAINT,"",Modifier.weight(1f),control==TextControl.PAINT) {pop(TextControl.PAINT)}
                        }
                        Row(Modifier.height(48.dp).fillMaxWidth()) {
                            if(large) TextCell(TextControl.PAINT,"",Modifier.weight(1f),false) {pop(TextControl.PAINT)}
                            NumericCell(layer,time,frameRate,editScope,TextControl.TRACKING,Modifier.weight(1f),control==TextControl.TRACKING,::numeric)
                            if(!large) NumericCell(layer,time,frameRate,editScope,TextControl.LEADING,Modifier.weight(1f),control==TextControl.LEADING,::numeric)
                            if(!large) TextCell(TextControl.CASE,style.caps.name,Modifier.weight(1f),control==TextControl.CASE) {pop(TextControl.CASE)}
                            TextCell(TextControl.FORMAT,"",Modifier.weight(1f),control==TextControl.FORMAT) {pop(TextControl.FORMAT)}
                        }
                        if(standard && !large) Row(Modifier.fillMaxWidth().height(48.dp)) {
                            listOf(TextControl.STROKE_WIDTH,TextControl.H_SCALE,TextControl.V_SCALE,TextControl.BASELINE).forEach {c->NumericCell(layer,time,frameRate,editScope,c,Modifier.weight(1f),control==c,::numeric)}
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().height(48.dp)) {
                            ParagraphAlignment.entries.take(3).forEach {align->
                                val title="Align ${align.name.lowercase()}"
                                TextVectorButton(TextInspectorIcons.alignment(align),title,Modifier.weight(1f),paragraph.alignment==align) {focus(TextControl.ALIGNMENT);onParagraph(a,b) {it.copy(alignment=align)}}
                            }
                            TextCell(TextControl.ALIGNMENT,"Justify",Modifier.weight(1f),false) {pop(TextControl.ALIGNMENT)}
                        }
                        Row(Modifier.fillMaxWidth().height(48.dp)) {
                            listOf(TextControl.BOX,TextControl.INDENTS,TextControl.SPACING,TextControl.DIRECTION).forEach {c->TextCell(c,if(c==TextControl.DIRECTION) paragraph.direction.name else "",Modifier.weight(1f),control==c) {pop(c)}}
                        }
                        if(standard && !large) Row(Modifier.fillMaxWidth().height(48.dp)) {
                            listOf(TextControl.BEFORE,TextControl.AFTER,TextControl.BOX).forEach {c->NumericCell(layer,time,frameRate,editScope,c,Modifier.weight(1f),control==c,::numeric)}
                            TextCell(TextControl.DIRECTION,paragraph.direction.name,Modifier.weight(1f),false) {pop(TextControl.DIRECTION)}
                        }
                    }
                    Text(message ?: error ?: if(tab==TextTab.CHARACTER && layer.text.strokeWidth.evaluate(local)>layer.text.effectiveStrokeWidth(local))
                        "Requested ${layer.text.strokeWidth.evaluate(local)} px · Effective ${layer.text.effectiveStrokeWidth(local)} px · size/SDF limit" else "",
                        maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.height(48.dp).fillMaxWidth().testTag("text-inspector-rail"),horizontalArrangement=Arrangement.SpaceBetween) {
                    fun navigate(next: Boolean) {owner?.let {p->val keys=layer.propertyKeyTimes(p);val key=if(next) keys.firstOrNull {it>ownerLocal} else keys.lastOrNull {it<ownerLocal};key?.let {onAction(EditorAction.Seek(Math.addExact(it,layer.resolvedKeyframeAnchorUs)))}}}
                    TextToolIcon("PREV","Previous ${owner?.name ?: "property"} key",{navigate(false)},owner!=null && animated)
                    val diamondLabel=when {owner==null->"Keyframes unavailable for selection or Animate";!animated->"Enable $owner animation at playhead";onKey->"Remove $owner keyframe at playhead";else->"Add $owner keyframe at playhead"}
                    TextToolIcon("KEY",diamondLabel,{
                        owner?.let {p->onAction(if(!animated) EditorAction.ToggleAnimated(layer.id,p,true,time) else if(onKey) EditorAction.RemoveKeyframe(layer.id,p,ownerLocal)
                            else if(p==AnimPropertyType.SOURCE_TEXT) EditorAction.SetTextPayload(layer.id,TextEdits.document(layer,time,document))
                            else if(p==AnimPropertyType.FILL_COLOUR || p==AnimPropertyType.STROKE_COLOUR) TextEdits.colour(layer,time,p,layer.text.colour(p).evaluate(local))
                            else EditorAction.SetKeyframe(layer.id,p,ownerLocal,layer.text.scalarValue(p,local))) }
                    },owner!=null,animated)
                    TextToolIcon("NEXT","Next ${owner?.name ?: "property"} key",{navigate(true)},owner!=null && animated)
                    Box {
                        TextToolIcon("ACTIONS","Animation actions for ${owner?.name ?: "selection"}",{animationMenu=true},tab!=TextTab.ANIMATE)
                        DropdownMenu(animationMenu,{animationMenu=false}) {
                            DropdownMenuItem(text={Text("Reset ${editScope.title(control)} value")},onClick={animationMenu=false;resetValue()})
                            if(owner!=null) {
                                DropdownMenuItem(text={Text(if(animated) "Freeze at playhead" else "Enable animation")},onClick={animationMenu=false;onAction(EditorAction.ToggleAnimated(layer.id,owner,!animated,time))})
                                if(animated) DropdownMenuItem(text={Text("Reset animation…")},onClick={animationMenu=false;resetAnimation=true})
                                val discrete=owner in setOf(AnimPropertyType.SOURCE_TEXT,AnimPropertyType.STROKE_OVER_FILL,AnimPropertyType.LIGATURES)
                                if(discrete) DropdownMenuItem(text={Text("Hold (discrete)")},enabled=false,onClick={})
                                else if(onKey) EasingPreset.entries.forEach {easing->DropdownMenuItem(text={Text(easing.name)},onClick={animationMenu=false;
                                    onAction(if(owner==AnimPropertyType.FILL_COLOUR || owner==AnimPropertyType.STROKE_COLOUR) EditorAction.SetColourKeyframe(layer.id,ownerLocal,layer.text.colour(owner).evaluate(local),easing,owner)
                                        else EditorAction.SetKeyframe(layer.id,owner,ownerLocal,layer.text.scalarValue(owner,local),easing))})}
                            }
                        }
                    }
                    TextToolIcon("GRAPH","Graph editor unavailable",{},false)
                    TextToolIcon("3D","3D controls unavailable",{},false)
                    TextToolIcon("DONE",if(editScope.selected) "Done selection" else "Done editing text",{if(editScope.selected) onSelection(null) else onDismiss()})
                }
            }
        }
    }
    if(resetAnimation) AlertDialog(onDismissRequest={resetAnimation=false},title={Text("Reset animation?")},text={Text("Remove all ${owner?.name} keys and retain its value at the playhead?")},
        confirmButton={TextButton(onClick={resetAnimation=false;owner?.let {onAction(EditorAction.ToggleAnimated(layer.id,it,false,time))}}) {Text("Reset animation")}},dismissButton={TextButton(onClick={resetAnimation=false}) {Text("Cancel")}})
    when(val current=mode) {
        is TextInspectorMode.Numeric->TextNumericOverlay(current.draft,onPayloadPreview,{onFinish();mode=TextInspectorMode.Browse},::cancelMode)
        is TextInspectorMode.Colour->TextColourOverlay(layer,time,editScope,current.control,onPayloadPreview,onValueBegin,{onFinish();mode=TextInspectorMode.Browse},::cancelMode,palette,onEyedropper)
        TextInspectorMode.Fonts->TextFontsPopup(style.font,layer.text.fontId,scopeLabel,
            {ref->if(axisSnapshot!=null) {onCancel();axisSnapshot=null};format {it.copy(font=ref)}},
            {if(axisSnapshot!=null) {onCancel();axisSnapshot=null};mode=TextInspectorMode.Browse},
            axisBegin={axisSnapshot=Triple(layer,frameRate.snap(time),editScope);onValueBegin()},
            axisPreview={ref->axisSnapshot?.let {(base,capturedTime,capturedScope)->
                val range=capturedScope.range;val doc=base.text.source.evaluateDocument(capturedTime-base.resolvedKeyframeAnchorUs)
                onPayloadPreview(TextEdits.document(base,capturedTime,doc.format(range?.start ?: 0,range?.end ?: 0) {it.copy(font=ref)}))
            }},axisFinish={onFinish();axisSnapshot=null},axisCancel={onCancel();axisSnapshot=null})
        is TextInspectorMode.Popover->TextPropertyOverlay(current.group.label,{mode=TextInspectorMode.Browse}) {
            val numericControls=when(current.group) {
                TextControl.INDENTS->listOf(TextControl.INDENT_LEFT,TextControl.INDENT_RIGHT,TextControl.INDENT_FIRST)
                TextControl.SPACING->listOf(TextControl.BEFORE,TextControl.AFTER)
                TextControl.FORMAT->listOf(TextControl.BASELINE,TextControl.H_SCALE,TextControl.V_SCALE,TextControl.RELATIVE_SIZE,TextControl.MANUAL_KERNING,TextControl.LEADING)
                TextControl.BOX->listOf(TextControl.BOX)
                TextControl.PAINT->listOf(TextControl.STROKE_WIDTH)
                else->emptyList()
            }
            numericControls.chunked(3).forEach {row->Row(Modifier.fillMaxWidth().height(48.dp)) {row.forEach {c->NumericCell(layer,time,frameRate,editScope,c,Modifier.weight(1f),false,::numeric)}}}
            when(current.group) {
                TextControl.FORMAT->{
                    Row {TextCell(TextControl.BOLD,"",Modifier.weight(1f),style.fauxBold) {focus(TextControl.BOLD);format {it.copy(fauxBold=!style.fauxBold)}}
                        TextCell(TextControl.ITALIC,"",Modifier.weight(1f),style.fauxItalic) {focus(TextControl.ITALIC);format {it.copy(fauxItalic=!style.fauxItalic)}}
                        TextCell(TextControl.CASE,"",Modifier.weight(1f),false) {pop(TextControl.CASE)}
                        TextCell(TextControl.KERNING,style.kerning.name,Modifier.weight(1f),false) {pop(TextControl.KERNING)}}
                    Row {listOf(TextControl.SUPER,TextControl.SUB).forEach {c->val target=if(c==TextControl.SUPER) TextScriptPosition.SUPER else TextScriptPosition.SUB
                        TextCell(c,"",Modifier.weight(1f),style.script==target) {focus(c);format {it.copy(script=if(style.script==target) TextScriptPosition.NORMAL else target)}}}
                        TextCell(TextControl.LIGATURES,"",Modifier.weight(1f),layer.text.effectiveLigatures(local)) {focus(TextControl.LIGATURES);if(editScope.available(TextControl.LIGATURES)) onAction(EditorAction.SetTextPayload(layer.id,TextEdits.scalar(layer,time,AnimPropertyType.LIGATURES,if(layer.text.ligatures.evaluate(local)==0f) 1f else 0f))) else message="Clear selection to edit layer ligatures"}}
                    TextButton(onClick={format {it.copy(manualKerning=FrozenList.empty())}}) {Text("Reset selected manual spacing")}
                }
                TextControl.CASE->Row {listOf(TextCaps.NONE,TextCaps.ALL,TextCaps.LOWER,TextCaps.SMALL).forEach {caps->TextVectorButton(TextInspectorIcons.icon(caps.name),caps.name,Modifier.weight(1f),style.caps==caps) {format {it.copy(caps=caps)}}}}
                TextControl.KERNING->Row {TextKerning.entries.forEach {k->TextButton(onClick={format {it.copy(kerning=k)}}) {Text(k.name)}}}
                TextControl.ALIGNMENT->{Row {ParagraphAlignment.entries.drop(3).forEach {align->TextVectorButton(TextInspectorIcons.alignment(align),"Justify ${align.name.lowercase()}",Modifier.weight(1f),paragraph.alignment==align) {onParagraph(a,b) {it.copy(alignment=align)}}}};TextButton(onClick={onParagraph(a,b) {it.copy(alignment=null)}}) {Text("Inherit layer alignment")}}
                TextControl.DIRECTION->{Row {ParagraphDirection.entries.forEach {d->TextVectorButton(TextInspectorIcons.icon(d.name),"$d"+if(d==ParagraphDirection.RTL) " stored only; rendering unsupported" else "",Modifier.weight(1f),paragraph.direction==d) {onParagraph(a,b) {it.copy(direction=d)}}}};Text("RTL is stored only; rendering unsupported",style=MaterialTheme.typography.labelSmall)}
                TextControl.BOX->{Row {TextButton(enabled=!editScope.selected,onClick={onBox(0f)}) {Text("Auto (0)")};TextButton(enabled=!editScope.selected,onClick={runCatching {TextEdits.fillCompositionWidth(layer,time,compositionWidth)}.onSuccess(onBox).onFailure {message=it.message}}) {Text("Fill composition")}}}
                TextControl.PAINT->{Row {listOf(TextControl.FILL,TextControl.STROKE).forEach {c->TextCell(c,editScope.title(c),Modifier.weight(1f),false) {focus(c);onValueBegin();mode=TextInspectorMode.Colour(c)}}}
                    Row {TextCell(TextControl.ORDER,if(layer.text.paintOrder(local)==0) "Fill over stroke" else "Stroke over fill",Modifier.weight(1f),false) {focus(TextControl.ORDER);if(!editScope.selected) onAction(EditorAction.SetTextPayload(layer.id,TextEdits.scalar(layer,time,AnimPropertyType.STROKE_OVER_FILL,if(layer.text.paintOrder(local)==0) 1f else 0f)))}}
                    if(editScope.selected) Row {TextButton(onClick={format {it.copy(fill=null)}}) {Text("Inherit fill")};TextButton(onClick={format {it.copy(stroke=null)}}) {Text("Inherit stroke")}}
                    Text("Requested ${layer.text.strokeWidth.evaluate(local)} px · Effective ${layer.text.effectiveStrokeWidth(local)} px · size/SDF limit. Centred stroke, round joins.",style=MaterialTheme.typography.labelSmall)
                }
                else->Unit
            }
        }
        else->Unit
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun TextToolIcon(name: String,label: String,click: ()->Unit,enabled: Boolean=true,selected: Boolean=false) {
    TextVectorButton(TextInspectorIcons.icon(name),label,Modifier.size(48.dp),selected,enabled,click)
}
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun TextVectorButton(vector: ImageVector,label: String,modifier: Modifier=Modifier,selected: Boolean=false,enabled: Boolean=true,click: ()->Unit) {
    var tooltip by remember {mutableStateOf(false)}
    Box(modifier.height(48.dp).background(if(selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
        .combinedClickable(enabled=enabled,onClick=click,onLongClick={tooltip=true},role=Role.Button,onClickLabel=label)
        .semantics {contentDescription=label;stateDescription=if(!enabled) "Unavailable" else if(selected) "Selected" else "Not selected"},contentAlignment=Alignment.Center) {
        Icon(vector,null,Modifier.size(22.dp),tint=MaterialTheme.colorScheme.onSurface.copy(alpha=if(enabled) 1f else .38f))
        DropdownMenu(tooltip,{tooltip=false}) {DropdownMenuItem(text={Text(label)},onClick={tooltip=false})}
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun TextCell(c: TextControl,value: String,modifier: Modifier,selected: Boolean,click: ()->Unit) {
    var tooltip by remember {mutableStateOf(false)}
    Row(modifier.height(48.dp).background(if(selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
        .combinedClickable(onClick=click,onLongClick={tooltip=true},role=Role.Button).semantics {contentDescription="${c.label} $value"},verticalAlignment=Alignment.CenterVertically) {
        Icon(TextInspectorIcons.control(c),null,Modifier.size(22.dp))
        Text(value,Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall)
        DropdownMenu(tooltip,{tooltip=false}) {DropdownMenuItem(text={Text(c.label)},onClick={tooltip=false})}
    }
}
@Composable internal fun NumericCell(layer: CompositionLayer,time: Long,rate: FrameRate,scope: TextEditScope,c: TextControl,
    modifier: Modifier,selected: Boolean,open: (TextControl)->Unit) {
    val value=runCatching {TextNumericDraft(layer,time,rate,scope,c).initial}.getOrDefault(0f)
    TextCell(c,"${"%.2f".format(java.util.Locale.ROOT,value)}${scope.unit(c)}",modifier,selected) {open(c)}
}
@Composable private fun SourceInlineEditor(document: TextSourceDocument,selection: TextSelectionRange?,onSelection: (TextSelectionRange?)->Unit,
    draft: (String,Int,Int)->Unit,modifier: Modifier,done: ()->Unit) {
    var field by remember {mutableStateOf(TextFieldValue(document.text,TextRange(selection?.start ?: 0,selection?.end ?: 0)))}
    val requester=remember {FocusRequester()}
    LaunchedEffect(Unit) {requester.requestFocus()}
    OutlinedTextField(field,{next->val old=field;field=next;onSelection(TextSelectionRange(next.selection.min,next.selection.max));if(old.text!=next.text) {
        val delta=next.text.length-old.text.length;val start=if(old.selection.collapsed && delta<0) next.selection.min else old.selection.min
        val end=if(old.selection.collapsed && delta<0) start-delta else old.selection.max;draft(next.text,start,end)
    }},modifier.fillMaxWidth().focusRequester(requester).semantics {contentDescription="Source Text"},textStyle=MaterialTheme.typography.bodySmall,
        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={done()}))
}
@Composable internal fun TextPropertyOverlay(title: String,dismiss: ()->Unit,content: @Composable ColumnScope.()->Unit) {
    val density=LocalDensity.current;val configuration=LocalConfiguration.current
    Dialog(dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.widthIn(max=360.dp).fillMaxWidth().heightIn(max=(configuration.screenHeightDp-80).coerceAtLeast(120).dp),shape=MaterialTheme.shapes.large) {
            Column(Modifier.padding(8.dp).then(if(density.fontScale>1.2f) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
                Row(Modifier.fillMaxWidth().height(48.dp),verticalAlignment=Alignment.CenterVertically) {Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall);TextToolIcon("DONE","Close $title",dismiss)}
                content()
            }
        }
    }
}
@Composable private fun TextNumericOverlay(draft: TextNumericDraft,preview: (TextProperties)->Unit,done: ()->Unit,cancel: ()->Unit) {
    var input by remember {mutableStateOf(draft.initial.toString())};var fine by remember {mutableStateOf(false)}
    val value=input.toFloatOrNull();val payload=value?.let {runCatching {draft.payload(it)}.getOrNull()}
    TextPropertyOverlay("${draft.scope.title(draft.control)} · ${draft.scope.unit(draft.control)}",cancel) {
        OutlinedTextField(input,{input=it;it.toFloatOrNull()?.let {v->runCatching {draft.payload(v)}.onSuccess(preview)}},singleLine=true,isError=payload==null,
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal,imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={if(payload!=null) if(value==draft.initial) cancel() else done()}),modifier=Modifier.fillMaxWidth(),label={Text(draft.scope.unit(draft.control))})
        Row {TextButton(onClick={fine=!fine}) {Text(if(fine) "Fine ✓" else "Fine")};listOf(-1f,1f).forEach {step->TextButton(onClick={val v=(value ?: draft.initial)+step*(if(fine) .1f else 1f);input=v.toString();runCatching {draft.payload(v)}.onSuccess(preview)}) {Text(if(step<0) "−" else "+")}}}
        if(payload==null) Text("Enter a finite value within the property's limits",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelSmall)
        Row {TextButton(onClick=cancel) {Text("Cancel")};TextButton(enabled=payload!=null,onClick={if(value==draft.initial) cancel() else done()}) {Text("Done")}}
    }
}
@Composable private fun TextColourOverlay(layer: CompositionLayer,time: Long,scope: TextEditScope,c: TextControl,
    preview: (TextProperties)->Unit,begin: ()->Unit,done: ()->Unit,cancel: ()->Unit,palette: PaletteRepository?,eyedropper: (()->Unit)?) {
    val captured=remember {layer};val capturedTime=remember {time};val doc=remember {captured.text.source.evaluateDocument(time-layer.resolvedKeyframeAnchorUs)}
    val r=scope.range;val s=doc.runAt(r?.start ?: 0)?.style
    val initial=remember {if(c==TextControl.FILL) s?.fill?.takeIf {scope.selected} ?: layer.text.fill.evaluate(time-layer.resolvedKeyframeAnchorUs) else s?.stroke?.takeIf {scope.selected} ?: layer.text.strokeColour.evaluate(time-layer.resolvedKeyframeAnchorUs)}
    var colour by remember {mutableStateOf(FluxxColor.fromArgb(initial))}
    fun update(v: FluxxColor) {
        colour=v;val argb=v.toArgb()
        val next=if(scope.selected) TextEdits.document(captured,capturedTime,doc.format(r!!.start,r.end) {if(c==TextControl.FILL) it.copy(fill=argb) else it.copy(stroke=argb)})
            else {val p=if(c==TextControl.FILL) AnimPropertyType.FILL_COLOUR else AnimPropertyType.STROKE_COLOUR
                val track=captured.text.colour(p);val local=capturedTime-captured.resolvedKeyframeAnchorUs
                captured.text.withColour(p,if(track.isAnimated) track.copy(keyframes=FrozenList.of((track.keyframes.filterNot {it.timeUs==local}+ColourKeyframe(local,argb,track.keyframes.firstOrNull {it.timeUs==local}?.easing ?: EasingPreset.EASY_EASE)).sortedBy {it.timeUs})) else track.copy(staticValue=argb))}
        preview(next)
    }
    Dialog(cancel,properties=DialogProperties(usePlatformDefaultWidth=false)) {Surface(Modifier.fillMaxWidth().heightIn(max=328.dp),shape=MaterialTheme.shapes.large) {Column(Modifier.padding(8.dp)) {
        Text("${scope.title(c)} — ${if(scope.selected) "Selection" else "Whole layer"}",style=MaterialTheme.typography.titleSmall)
        FluxxColorPicker(colour,::update,onEyedropperRequest=eyedropper,modifier=Modifier.fillMaxWidth().height(216.dp),onColorPreview=::update,paletteRepository=palette)
        Row {TextButton(onClick=cancel) {Text("Cancel")};TextButton(onClick={if(colour.toArgb()==initial) cancel() else done()}) {Text("Done")}}
    }}}
}
