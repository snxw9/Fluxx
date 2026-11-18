package com.fluxx.android.ui.inspector

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import com.fluxx.android.ui.common.colorpicker.*
import kotlin.math.*

/**
 * One fixed, non-scrolling Edit Text surface. Every common control sits on its tab face. The only overlays are
 * real choices (Case, Justify, Box width), exact numeric entry, Fonts and the colour picker; all are laid out
 * inside this sheet, which is docked below the preview, so none can hide the layer being edited.
 */
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
    palette: PaletteRepository?=null,onEyedropper: (()->Unit)?=null,
    onBeginDraft: (Boolean,(CompositionLayer)->Unit)->Unit={_,ready->onValueBegin();ready(layer)},finishing: Boolean=false,strokeClamp: String?=null,
    sampleColour: (((Int?)->Unit)->Unit)?=null,cancelSample: ()->Unit={},
    onSourceSelection: (String,TextSelectionRange)->Unit={_,r->onSelection(r)}) {
    val local=Math.subtractExact(time,layer.resolvedKeyframeAnchorUs)
    val document=layer.text.source.evaluateDocument(local)
    val editScope=TextEditScope(selection)
    var tabIndex by rememberSaveable(layer.id) {mutableIntStateOf(0)}
    var page by rememberSaveable(layer.id) {mutableIntStateOf(0)}
    var controlName by rememberSaveable(layer.id) {mutableStateOf(TextControl.SIZE.name)}
    val tab=TextTab.entries[tabIndex];val control=TextControl.valueOf(controlName)
    var mode by rememberSaveable(layer.id,stateSaver=TextInspectorModeCodec.saver) {mutableStateOf<TextInspectorMode>(TextInspectorMode.Browse)}
    var overflow by remember {mutableStateOf(false)}
    var animationMenu by remember {mutableStateOf(false)}
    var resetAnimation by remember {mutableStateOf(false)}
    var explode by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf<String?>(null)}
    var numericNote by remember {mutableStateOf<String?>(null)}
    var colourSampling by remember {mutableStateOf(false)}
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
    val effectiveAlignment=paragraph.alignment ?: ParagraphAlignment.valueOf(layer.text.alignment.name)
    fun focus(c: TextControl) {controlName=c.name;onFocus(editScope.owner(c));message=null}
    fun pop(c: TextControl) {focus(c);mode=TextInspectorMode.Popover(c)}
    fun format(change: (TextRunStyle)->TextRunStyle) {onCharacter(a,b,change)}
    fun direction(value: ParagraphDirection) {focus(TextControl.DIRECTION);onAction(EditorAction.SetTextPayload(layer.id,TextEdits.document(layer,frameRate.snap(time),document.formatParagraphs(a,b) {it.copy(direction=value)})))}
    fun align(c: TextControl,value: ParagraphAlignment?) {focus(c);onParagraph(a,b) {it.copy(alignment=value)}}
    fun layerToggle(c: TextControl,property: AnimPropertyType,on: Boolean) {
        focus(c)
        if(editScope.available(c)) onAction(EditorAction.SetTextPayload(layer.id,TextEdits.scalar(layer,time,property,if(on) 0f else 1f)))
        else message="Clear selection to edit layer ${c.label.lowercase()}"
    }
    fun numeric(c: TextControl) {
        focus(c)
        if(!editScope.available(c)) {message="Clear selection to edit ${c.label}";return}
        onBeginDraft(false) {fresh->mode=TextInspectorMode.Numeric(TextNumericDraft(fresh,time,frameRate,editScope,c))}
    }
    fun leaveMode() {mode=TextInspectorMode.Browse}
    fun cancelMode() {if(mode is TextInspectorMode.Numeric || mode is TextInspectorMode.Colour || mode==TextInspectorMode.Source || axisSnapshot!=null) onCancel();axisSnapshot=null;manager.clearFocus();leaveMode()}
    fun colour(c: TextControl) {focus(c);onBeginDraft(false) {fresh->mode=TextInspectorMode.Colour(c,fresh,frameRate.snap(time),editScope)}}
    fun sourceDone() {manager.clearFocus();onFinish();onSelection(null);mode=TextInspectorMode.Browse}
    fun closeFonts() {if(axisSnapshot!=null) {onCancel();axisSnapshot=null};mode=TextInspectorMode.Browse}
    LaunchedEffect(owner,tab,control) {onFocus(owner)}
    val lifecycle=LocalLifecycleOwner.current
    val context=LocalContext.current
    val currentCancel by rememberUpdatedState(::cancelMode)
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event->
            var current: android.content.Context?=context
            while(current is android.content.ContextWrapper && current !is android.app.Activity) current=current.baseContext
            if(event==Lifecycle.Event.ON_STOP && (current as? android.app.Activity)?.isChangingConfigurations!=true) {currentCancel()}
        }
        lifecycle.lifecycle.addObserver(observer);onDispose {lifecycle.lifecycle.removeObserver(observer)}
    }
    var keyboardWasVisible by remember {mutableStateOf(false)}
    val imeBottom=WindowInsets.ime.getBottom(density)
    LaunchedEffect(imeBottom) {if(keyboardWasVisible && imeBottom==0 && (mode==TextInspectorMode.Source || mode is TextInspectorMode.Numeric || mode is TextInspectorMode.Colour && !colourSampling)) cancelMode();keyboardWasVisible=imeBottom>0}
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
            TextControl.H_SCALE->format {it.copy(horizontalScale=100f)}
            TextControl.V_SCALE->format {it.copy(verticalScale=100f)}
            TextControl.RELATIVE_SIZE->format {it.copy(sizeFactor=1f)}
            TextControl.FONT->format {it.copy(font=null)}
            TextControl.MANUAL_KERNING->format {it.copy(manualKerning=FrozenList.empty())}
            TextControl.BOLD->format {it.copy(fauxBold=false)};TextControl.ITALIC->format {it.copy(fauxItalic=false)}
            TextControl.CASE->format {it.copy(caps=TextCaps.NONE)}
            TextControl.SUPER,TextControl.SUB->format {it.copy(script=TextScriptPosition.NORMAL)}
            TextControl.KERNING->format {it.copy(kerning=TextKerning.METRICS)}
            TextControl.ORDER,TextControl.LIGATURES->if(!editScope.selected) onAction(EditorAction.SetTextPayload(layer.id,TextEdits.scalar(layer,time,requireNotNull(owner),if(control==TextControl.LIGATURES) 1f else 0f)))
            TextControl.BOX->if(!editScope.selected) onBox(0f)
            TextControl.ALIGNMENT,TextControl.ALIGN_LEFT,TextControl.ALIGN_CENTRE,TextControl.ALIGN_RIGHT,TextControl.JUSTIFY->onParagraph(a,b) {it.copy(alignment=null)}
            TextControl.INDENT_LEFT->onParagraph(a,b) {it.copy(indentLeft=0f)}
            TextControl.INDENT_RIGHT->onParagraph(a,b) {it.copy(indentRight=0f)}
            TextControl.INDENT_FIRST->onParagraph(a,b) {it.copy(indentFirst=0f)}
            TextControl.BEFORE->onParagraph(a,b) {it.copy(spaceBefore=0f)}
            TextControl.AFTER->onParagraph(a,b) {it.copy(spaceAfter=0f)}
            TextControl.DIRECTION->direction(ParagraphDirection.LTR)
            else->message="Choose a specific property to reset"
        }
    }
    BackHandler(mode!=TextInspectorMode.Browse || editScope.selected || explode) {
        when {explode->explode=false;mode==TextInspectorMode.Fonts->closeFonts();mode!=TextInspectorMode.Browse->cancelMode();else->onSelection(null)}
    }
    DisposableEffect(layer.id) {onDispose {onCancel()}}
    if(explode) ExplodeTextDialog(layer,time,{explode=false},onExplodePrepare,onExplodeCommit)
    CompositionLocalProvider(LocalTextNumericBindings provides TextNumericBindings(
        {c->focus(c);onValueBegin();TextNumericDraft(layer,time,frameRate,editScope,c)},onPayloadPreview,onFinish,onCancel,::focus,!finishing)) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("text-inspector").padding(horizontal=TextInspectorBudget.SIDE.dp).onPreviewKeyEvent {if(it.key==Key.Escape && it.type==KeyEventType.KeyUp && mode!=TextInspectorMode.Browse) {cancelMode();true} else false}) {
        val fontScale=density.fontScale
        val large=TextInspectorBudget.large(fontScale)
        val statusHeight=TextInspectorBudget.statusHeight(fontScale)
        val feedbackHeight=TextInspectorBudget.feedbackHeight(fontScale)
        val bodyTop=TextInspectorBudget.SOURCE+statusHeight+TextInspectorBudget.TABS
        val pages=TextInspectorLayout.pages(TextInspectorLayout.rows(tab,fontScale),TextInspectorBudget.bodyRows(maxHeight.value,fontScale))
        val pageIndex=page.coerceIn(0,pages.lastIndex)
        val rows=pages[pageIndex]
        val requestedStroke=layer.text.strokeWidth.evaluate(local)+if(editScope.selected) style.strokeDelta else 0f
        val feedback=TextFeedback.line(error,message,numericNote,TextFeedback.strokeClamp(requestedStroke,strokeClamp,fontScale))
        val feedbackSpoken=TextFeedback.line(error,message,numericNote,TextFeedback.strokeClampFull(requestedStroke,strokeClamp))

        @Composable fun RowScope.Cell(cell: TextGridCell) {
            val m=Modifier.weight(cell.weight)
            val c=cell.control ?: run {Spacer(m);return}
            val label=if(large) shortLabel(c) else null
            when(c) {
                TextControl.FONT->TextCell(c,(if(missingFonts) "Missing · " else "")+(style.font?.let {"${it.family} ${it.styleName}"} ?: layer.text.fontId),m,control==c) {focus(c);mode=TextInspectorMode.Fonts}
                TextControl.PAINT->PaintSwatches(style.fill?.takeIf {editScope.selected} ?: layer.text.fill.evaluate(local),
                    style.stroke?.takeIf {editScope.selected} ?: layer.text.strokeColour.evaluate(local),scopeLabel,m,control,fontScale<=1.6f,::colour)
                TextControl.BOX->NumericCell(layer,time,frameRate,editScope,c,m,control==c,{pop(c)},::numeric)
                TextControl.KERNING->TextCell(c,if(style.kerning==TextKerning.METRICS) "Metrics" else "Off",m,control==c) {
                    focus(c);format {it.copy(kerning=if(style.kerning==TextKerning.METRICS) TextKerning.OFF else TextKerning.METRICS)}}
                TextControl.ORDER->{val strokeOver=layer.text.paintOrder(local)!=0
                    TextCell(c,if(strokeOver) "Stroke over" else "Fill over",m,control==c,editScope.available(c)) {layerToggle(c,AnimPropertyType.STROKE_OVER_FILL,strokeOver)}}
                TextControl.DIRECTION->TextCell(c,if(paragraph.direction==ParagraphDirection.RTL) "RTL (stored)" else "LTR",m,control==c,vector=TextInspectorIcons.icon(paragraph.direction.name)) {
                    val next=if(paragraph.direction==ParagraphDirection.LTR) ParagraphDirection.RTL else ParagraphDirection.LTR
                    direction(next);if(next==ParagraphDirection.RTL) message="RTL direction stored only; RTL rendering unsupported"}
                TextControl.BOLD->TextVectorButton(TextInspectorIcons.control(c),c.label,m,style.fauxBold,text=label) {focus(c);format {it.copy(fauxBold=!style.fauxBold)}}
                TextControl.ITALIC->TextVectorButton(TextInspectorIcons.control(c),c.label,m,style.fauxItalic,text=label) {focus(c);format {it.copy(fauxItalic=!style.fauxItalic)}}
                TextControl.CASE->TextVectorButton(TextInspectorIcons.icon(style.caps.name),"Case: ${caseLabel(style.caps)}; choices",m,control==c,text=label) {pop(c)}
                TextControl.SUPER,TextControl.SUB->{val target=if(c==TextControl.SUPER) TextScriptPosition.SUPER else TextScriptPosition.SUB
                    TextVectorButton(TextInspectorIcons.control(c),c.label,m,style.script==target,text=label) {focus(c);format {it.copy(script=if(style.script==target) TextScriptPosition.NORMAL else target)}}}
                TextControl.LIGATURES->{val on=layer.text.ligatures.evaluate(local)!=0f
                    TextVectorButton(TextInspectorIcons.control(c),c.label+if(layer.text.effectiveLigatures(local)) ", active" else if(on) ", suppressed by tracking" else ", off",m,on,editScope.available(c),text=label) {
                        layerToggle(c,AnimPropertyType.LIGATURES,on)}}
                TextControl.ALIGN_LEFT,TextControl.ALIGN_CENTRE,TextControl.ALIGN_RIGHT->{
                    val value=when(c) {TextControl.ALIGN_LEFT->ParagraphAlignment.LEFT;TextControl.ALIGN_CENTRE->ParagraphAlignment.CENTRE;else->ParagraphAlignment.RIGHT}
                    TextVectorButton(TextInspectorIcons.alignment(value),alignmentLabel(value)+if(paragraph.alignment==null && effectiveAlignment==value) ", inherited" else "",m,effectiveAlignment==value,text=label) {align(c,value)}}
                TextControl.JUSTIFY->TextVectorButton(TextInspectorIcons.alignment(effectiveAlignment.takeIf {it.ordinal>=3} ?: ParagraphAlignment.JUSTIFY_LAST_LEFT),
                    "Justify choices"+(effectiveAlignment.takeIf {it.ordinal>=3}?.let {", ${alignmentLabel(it)}"} ?: ""),m,effectiveAlignment.ordinal>=3,text=label) {pop(c)}
                else->NumericCell(layer,time,frameRate,editScope,c,m,control==c,null,::numeric)
            }
        }

        Column(Modifier.fillMaxSize()) {
            if(mode==TextInspectorMode.Source) {
                Row(Modifier.fillMaxWidth().height(48.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("$scopeLabel · Source Text · Hold",Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall)
                    TextButton(onClick={cancelMode()}) {Text("Cancel")}
                    TextButton(onClick=::sourceDone) {Text("Done")}
                }
                SourceInlineEditor(document,selection,onSourceSelection,onSourceDraft,Modifier.weight(1f),{onSourceBegin()},::sourceDone)
            } else {
                Row(Modifier.fillMaxWidth().height(TextInspectorBudget.SOURCE.dp),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={focus(TextControl.SOURCE);onBeginDraft(true) {mode=TextInspectorMode.Source}},contentPadding=PaddingValues(horizontal=4.dp),modifier=Modifier.weight(1f).fillMaxHeight()) {
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
                            DropdownMenuItem(text={Text("Manual kerning")},onClick={overflow=false;numeric(TextControl.MANUAL_KERNING)})
                            DropdownMenuItem(text={Text("Relative size")},onClick={overflow=false;numeric(TextControl.RELATIVE_SIZE)})
                            DropdownMenuItem(text={Text("Animate · F3 unavailable")},enabled=false,onClick={})
                            DropdownMenuItem(text={Text("Clear selection")},enabled=editScope.selected,onClick={overflow=false;onSelection(null)})
                            DropdownMenuItem(text={Text("Layer tools")},onClick={overflow=false;onTools()})
                            DropdownMenuItem(text={Text("Done editing text")},onClick={overflow=false;onDismiss()})
                        }
                    }
                }
                Text("$scopeLabel · ${editScope.title(control)}"+(if(editScope.unit(control).isNotEmpty()) " (${editScope.unit(control)})" else "")+" · "+when {
                    tab==TextTab.ANIMATE->"F3 unavailable"
                    editScope.selected->"Current Source Text value · Hold"
                    owner==null->"Static layer property"
                    owner==AnimPropertyType.SOURCE_TEXT->"Source Text Hold · "+if(onKey) "On key" else if(animated) "Between keys" else "Animation off"
                    onKey->"On key at ${CompositionTimecode.format(time,frameRate)}";animated->"Animated · between keys";else->"Animation off"
                },Modifier.height(statusHeight.dp).fillMaxWidth(),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall)
                TextTabsRow(tab,pages.size,pageIndex,!finishing,{t->tabIndex=t.ordinal;page=0;onFocus(if(t==TextTab.ANIMATE) null else owner)},{page=(pageIndex+1)%pages.size})
                Column(Modifier.weight(1f).fillMaxWidth().testTag("text-inspector-body")) {
                    if(tab==TextTab.ANIMATE) Text("Text animators arrive with F3. No animator controls are available yet.",Modifier.padding(8.dp).semantics {disabled()},
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.6f))
                    else rows.forEachIndexed {index,row->Row(Modifier.fillMaxWidth().height(TextInspectorBudget.ROW.dp).testTag("text-row-$index")) {row.forEach {Cell(it)}}}
                }
                Box(Modifier.fillMaxWidth().height(feedbackHeight.dp).testTag("text-inspector-feedback"),contentAlignment=Alignment.CenterStart) {
                    if(feedback!=null) Text(feedback,Modifier.fillMaxWidth().semantics {liveRegion=LiveRegionMode.Polite;contentDescription=feedbackSpoken ?: feedback},maxLines=1,overflow=TextOverflow.Ellipsis,
                        style=MaterialTheme.typography.labelSmall,color=if(error!=null || numericNote!=null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
                Row(Modifier.height(TextInspectorBudget.RAIL.dp).fillMaxWidth().testTag("text-inspector-rail"),horizontalArrangement=Arrangement.SpaceBetween) {
                    fun navigate(next: Boolean) {owner?.let {p->val keys=layer.propertyKeyTimes(p);val key=if(next) keys.firstOrNull {it>ownerLocal} else keys.lastOrNull {it<ownerLocal};key?.let {onAction(EditorAction.Seek(Math.addExact(it,layer.resolvedKeyframeAnchorUs)))}}}
                    if(editScope.selected) Spacer(Modifier.size(48.dp)) else TextToolIcon("PREV","Previous ${owner?.name ?: "property"} key",{navigate(false)},owner!=null && animated && !finishing)
                    val diamondLabel=when {owner==null->"Keyframes unavailable for selection or Animate";!animated->"Enable $owner animation at playhead";onKey->"Remove $owner keyframe at playhead";else->"Add $owner keyframe at playhead"}
                    if(editScope.selected) Spacer(Modifier.size(48.dp)) else TextToolIcon("KEY",diamondLabel,{
                        owner?.let {p->onAction(if(!animated) EditorAction.ToggleAnimated(layer.id,p,true,time) else if(onKey) EditorAction.RemoveKeyframe(layer.id,p,ownerLocal)
                            else if(p==AnimPropertyType.SOURCE_TEXT) EditorAction.SetTextPayload(layer.id,TextEdits.document(layer,time,document))
                            else if(p==AnimPropertyType.FILL_COLOUR || p==AnimPropertyType.STROKE_COLOUR) TextEdits.colour(layer,time,p,layer.text.colour(p).evaluate(local))
                            else EditorAction.SetKeyframe(layer.id,p,ownerLocal,layer.text.scalarValue(p,local))) }
                    },owner!=null && !finishing,animated)
                    if(editScope.selected) Spacer(Modifier.size(48.dp)) else TextToolIcon("NEXT","Next ${owner?.name ?: "property"} key",{navigate(true)},owner!=null && animated && !finishing)
                    Box {
                        TextToolIcon("ACTIONS","Animation actions for ${owner?.name ?: "selection"}",{animationMenu=true},tab!=TextTab.ANIMATE && !finishing)
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

        // Overlays: an invisible panel scrim takes outside taps; every surface stays inside this sheet.
        val current=mode
        val anchored=current is TextInspectorMode.Popover || current is TextInspectorMode.Numeric
        if(anchored || current==TextInspectorMode.Fonts || current is TextInspectorMode.Colour || resetAnimation)
            Box(Modifier.fillMaxSize().testTag("text-inspector-scrim").pointerInput(current,resetAnimation) {detectTapGestures {
                when {resetAnimation->resetAnimation=false;current is TextInspectorMode.Popover->leaveMode();current==TextInspectorMode.Fonts->closeFonts();else->cancelMode()}
            }})
        if(anchored) {
            val anchorControl=when(current) {
                is TextInspectorMode.Popover->if(current.group==TextControl.BASE_ALIGNMENT) TextControl.JUSTIFY else current.group
                is TextInspectorMode.Numeric->current.draft.control
            }
            val height=if(current is TextInspectorMode.Numeric) 2 else 1
            val targetRow=TextInspectorLayout.overlayRow(anchorControl?.let {TextInspectorLayout.rowOf(rows,it)},height,rows.size)
            val overlay=Modifier.fillMaxWidth().offset(y=(bodyTop+targetRow*TextInspectorBudget.ROW).dp).height((height*TextInspectorBudget.ROW).dp)
            if(current is TextInspectorMode.Numeric) TextNumericStrip(current.draft,onPayloadPreview,{onFinish();leaveMode()},::cancelMode,onValueBegin,{numericNote=it},
                if(current.draft.control==TextControl.MANUAL_KERNING) ({cancelMode();format {it.copy(manualKerning=FrozenList.empty())}}) else null,overlay)
            else if(current is TextInspectorMode.Popover) TextChoiceRow(overlay) {
                fun choose(action: ()->Unit) {action();leaveMode()}
                when(current.group) {
                    TextControl.CASE->listOf(TextCaps.NONE,TextCaps.ALL,TextCaps.LOWER,TextCaps.SMALL).forEach {caps->
                        TextVectorButton(TextInspectorIcons.icon(caps.name),caseLabel(caps),Modifier.weight(1f),style.caps==caps,text=if(large) caseLabel(caps) else null) {choose {format {it.copy(caps=caps)}}}}
                    TextControl.JUSTIFY->{
                        ParagraphAlignment.entries.drop(3).forEach {value->TextVectorButton(TextInspectorIcons.alignment(value),alignmentLabel(value),Modifier.weight(1f),paragraph.alignment==value) {choose {align(TextControl.JUSTIFY,value)}}}
                        TextVectorButton(TextInspectorIcons.icon("INHERIT"),"Inherit layer alignment",Modifier.weight(1f),paragraph.alignment==null,text="Inherit") {choose {align(TextControl.ALIGNMENT,null)}}
                        TextVectorButton(TextInspectorIcons.icon("NEXT"),if(editScope.selected) "Layer base alignment; clear selection to edit" else "Layer base alignment choices",Modifier.weight(1f),false,!editScope.selected,text="Base") {
                            focus(TextControl.BASE_ALIGNMENT);mode=TextInspectorMode.Popover(TextControl.BASE_ALIGNMENT)}
                    }
                    TextControl.BASE_ALIGNMENT->{
                        TextVectorButton(TextInspectorIcons.icon("PREV"),"Back to justify choices",Modifier.weight(1f)) {mode=TextInspectorMode.Popover(TextControl.JUSTIFY)}
                        TextAlignment.entries.forEach {value->val p=ParagraphAlignment.valueOf(value.name)
                            TextVectorButton(TextInspectorIcons.alignment(p),"Layer base: ${alignmentLabel(p)}",Modifier.weight(1f),layer.text.alignment==value,text="Base") {
                                choose {onBeginDraft(false) {fresh->onPayloadPreview(fresh.text.copy(alignment=value));onFinish()}}}}
                    }
                    TextControl.BOX->{
                        TextButton(enabled=!editScope.selected,onClick={choose {onBox(0f)}},modifier=Modifier.weight(1f).fillMaxHeight()) {Text("Auto (0)",maxLines=1)}
                        TextButton(enabled=!editScope.selected,onClick={choose {runCatching {TextEdits.fillCompositionWidth(layer,time,compositionWidth)}.onSuccess(onBox).onFailure {message=it.message}}},
                            modifier=Modifier.weight(2f).fillMaxHeight()) {Text("Fill composition",maxLines=1,overflow=TextOverflow.Ellipsis)}
                        if(editScope.selected) Text("Box width is layer-only",Modifier.weight(1f),style=MaterialTheme.typography.labelSmall)
                    }
                    else->Unit
                }
            }
        }
        if(resetAnimation) TextChoiceRow(Modifier.align(Alignment.BottomCenter).padding(bottom=(TextInspectorBudget.RAIL+feedbackHeight).dp).fillMaxWidth().height(TextInspectorBudget.ROW.dp)) {
            Text("Remove all ${owner?.name} keys, keep the value at the playhead?",Modifier.weight(1f).padding(horizontal=8.dp),maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall)
            TextButton(onClick={resetAnimation=false}) {Text("Cancel")}
            TextButton(onClick={resetAnimation=false;owner?.let {onAction(EditorAction.ToggleAnimated(layer.id,it,false,time))}}) {Text("Reset")}
        }
        when(current) {
            is TextInspectorMode.Colour->TextColourOverlay(current.layer,current.time,current.scope,current.control,onPayloadPreview,onValueBegin,{onFinish();leaveMode()},::cancelMode,palette,onEyedropper,sampleColour,cancelSample,{colourSampling=it},
                if(current.scope.selected) ({cancelMode();format {if(current.control==TextControl.FILL) it.copy(fill=null) else it.copy(stroke=null)}}) else null,Modifier.fillMaxSize())
            TextInspectorMode.Fonts->{
                val cap=max(LocalConfiguration.current.screenHeightDp*FONTS_WINDOW_FRACTION,FONTS_MIN_HEIGHT).dp
                Box(Modifier.fillMaxSize(),contentAlignment=Alignment.BottomCenter) {
                    TextFontsPopup(style.font,layer.text.fontId,scopeLabel,
                        {ref->if(axisSnapshot!=null) {onCancel();axisSnapshot=null};format {it.copy(font=ref)}},::closeFonts,
                        axisBegin={axisSnapshot=Triple(layer,frameRate.snap(time),editScope);onValueBegin()},
                        axisPreview={ref->axisSnapshot?.let {(base,capturedTime,capturedScope)->
                            val range=capturedScope.range;val doc=base.text.source.evaluateDocument(capturedTime-base.resolvedKeyframeAnchorUs)
                            onPayloadPreview(TextEdits.document(base,capturedTime,doc.format(range?.start ?: 0,range?.end ?: 0) {it.copy(font=ref)}))
                        }},axisFinish={onFinish();axisSnapshot=null},axisCancel={onCancel();axisSnapshot=null},busy=finishing,
                        baseFont=if(editScope.selected) null else {id->focus(TextControl.BASE_FONT);onBeginDraft(false) {fresh->onPayloadPreview(fresh.text.copy(fontId=id));onFinish()}},
                        modifier=Modifier.fillMaxWidth().heightIn(max=cap).fillMaxHeight())
                }
            }
            else->Unit
        }
    }
    }
}

/** Fonts sheet height cap: about 40% of the window, never taller than the Edit Text sheet itself. */
internal const val FONTS_WINDOW_FRACTION=.4f
internal const val FONTS_MIN_HEIGHT=240f

@Composable private fun TextTabsRow(tab: TextTab,pages: Int,page: Int,enabled: Boolean,select: (TextTab)->Unit,nextPage: ()->Unit) {
    Row(Modifier.fillMaxWidth().height(TextInspectorBudget.TABS.dp).testTag("text-inspector-tabs")) {
        TextTab.entries.forEach {t->TextButton(contentPadding=PaddingValues(0.dp),enabled=enabled,onClick={select(t)},
            modifier=Modifier.weight(1f).fillMaxHeight().semantics {role=Role.Tab;selected=t==tab;if(t==TextTab.ANIMATE) stateDescription="Animator controls not yet available"}) {
            Text(t.name.lowercase().replaceFirstChar {it.uppercase()},maxLines=1,style=MaterialTheme.typography.labelMedium,
                color=if(t==tab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha=if(t==TextTab.ANIMATE) .6f else 1f))
        }}
        // Short-window fallback only: switch whole rows instead of scrolling the sheet.
        if(pages>1) TextButton(onClick=nextPage,contentPadding=PaddingValues(0.dp),modifier=Modifier.width(48.dp).fillMaxHeight()
            .semantics {contentDescription="More controls, page ${page+1} of $pages"}) {Text("${page+1}/$pages",style=MaterialTheme.typography.labelMedium)}
    }
}

@Composable private fun TextChoiceRow(modifier: Modifier,content: @Composable RowScope.()->Unit) {
    Surface(modifier.testTag("text-choice-row"),shape=MaterialTheme.shapes.medium,tonalElevation=3.dp,shadowElevation=6.dp,
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxSize(),verticalAlignment=Alignment.CenterVertically,content=content)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun PaintSwatches(fill: Int,stroke: Int,scope: String,modifier: Modifier,focused: TextControl,labels: Boolean,open: (TextControl)->Unit) {
    Row(modifier.height(TextInspectorBudget.ROW.dp)) {
        listOf(TextControl.FILL to fill,TextControl.STROKE to stroke).forEach {(c,argb)->
            var tooltip by remember {mutableStateOf(false)}
            val name=if(c==TextControl.FILL) "Fill" else "Stroke"
            val description="$name colour, $scope, #${"%08X".format(argb)}"
            val shape=RoundedCornerShape(4.dp)
            Column(Modifier.weight(1f).fillMaxHeight().background(if(focused==c) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                .combinedClickable(role=Role.Button,onClickLabel="Edit $name colour",onClick={open(c)},onLongClick={tooltip=true})
                .semantics {contentDescription=description},horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                // Fill is a solid chip, Stroke a ring: the two never depend on hue alone.
                if(c==TextControl.FILL) Box(Modifier.size(20.dp).background(Color(argb),shape).border(1.dp,MaterialTheme.colorScheme.onSurface,shape))
                else Box(Modifier.size(20.dp).border(1.dp,MaterialTheme.colorScheme.onSurface,shape).padding(1.dp).border(4.dp,Color(argb),shape))
                if(labels) Text(name,maxLines=1,style=MaterialTheme.typography.labelSmall)
                DropdownMenu(tooltip,{tooltip=false}) {DropdownMenuItem(text={Text(description)},onClick={tooltip=false})}
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun TextToolIcon(name: String,label: String,click: ()->Unit,enabled: Boolean=true,selected: Boolean=false) {
    TextVectorButton(TextInspectorIcons.icon(name),label,Modifier.size(48.dp),selected,enabled,click=click)
}
private fun caseLabel(caps: TextCaps)=when(caps) {TextCaps.NONE->"Normal case";TextCaps.ALL->"All caps";TextCaps.LOWER->"Lowercase";TextCaps.SMALL->"Small caps"}
private fun alignmentLabel(a: ParagraphAlignment)=when(a) {
    ParagraphAlignment.LEFT->"Align left";ParagraphAlignment.CENTRE->"Align centre";ParagraphAlignment.RIGHT->"Align right"
    ParagraphAlignment.JUSTIFY_LAST_LEFT->"Justify, last line left";ParagraphAlignment.JUSTIFY_LAST_CENTRE->"Justify, last line centre"
    ParagraphAlignment.JUSTIFY_LAST_RIGHT->"Justify, last line right";ParagraphAlignment.JUSTIFY_ALL->"Justify all lines"
}
/** Labels for the 3-column large-font fallback; full names remain in accessibility and long-press labels. */
internal fun shortLabel(c: TextControl)=when(c) {
    TextControl.BOLD->"Bold";TextControl.ITALIC->"Italic";TextControl.CASE->"Case";TextControl.SUPER->"Super";TextControl.SUB->"Sub"
    TextControl.LIGATURES->"Liga";TextControl.ALIGN_LEFT->"Left";TextControl.ALIGN_CENTRE->"Centre";TextControl.ALIGN_RIGHT->"Right"
    TextControl.JUSTIFY->"Justify";else->c.label
}
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun TextVectorButton(vector: ImageVector,label: String,modifier: Modifier=Modifier,selected: Boolean=false,enabled: Boolean=true,text: String?=null,click: ()->Unit) {
    var tooltip by remember {mutableStateOf(false)}
    Row(modifier.height(48.dp).background(if(selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
        .combinedClickable(enabled=enabled,onClick=click,onLongClick={tooltip=true},role=Role.Button,onClickLabel=label)
        .semantics(mergeDescendants=true) {contentDescription=label;stateDescription=if(!enabled) "Unavailable" else if(selected) "Selected" else "Not selected"},
        horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
        val tint=MaterialTheme.colorScheme.onSurface.copy(alpha=if(enabled) 1f else .38f)
        Icon(vector,null,Modifier.size(22.dp),tint=tint)
        if(text!=null) Text(text,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall,color=tint)
        DropdownMenu(tooltip,{tooltip=false}) {DropdownMenuItem(text={Text(label)},onClick={tooltip=false})}
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun TextCell(c: TextControl,value: String,modifier: Modifier,selected: Boolean,enabled: Boolean=true,vector: ImageVector?=null,click: ()->Unit) {
    var tooltip by remember {mutableStateOf(false)}
    val tint=MaterialTheme.colorScheme.onSurface.copy(alpha=if(enabled) 1f else .38f)
    Row(modifier.height(48.dp).background(if(selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
        .combinedClickable(enabled=enabled,onClick=click,onLongClick={tooltip=true},role=Role.Button)
        .semantics {contentDescription="${c.label} $value";if(!enabled) stateDescription="Clear selection to edit layer property"},verticalAlignment=Alignment.CenterVertically) {
        Icon(vector ?: TextInspectorIcons.control(c),null,Modifier.size(22.dp),tint=tint)
        Text(value,Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall,color=tint)
        DropdownMenu(tooltip,{tooltip=false}) {DropdownMenuItem(text={Text(c.label)},onClick={tooltip=false})}
    }
}
@Composable internal fun NumericCell(layer: CompositionLayer,time: Long,rate: FrameRate,scope: TextEditScope,c: TextControl,
    modifier: Modifier,selected: Boolean,iconClick: (()->Unit)?,open: (TextControl)->Unit) {
    TextScrubCell(TextNumericDraft(layer,time,rate,scope,c),modifier,selected,iconClick) {open(c)}
}
@Composable private fun SourceInlineEditor(document: TextSourceDocument,selection: TextSelectionRange?,onSelection: (String,TextSelectionRange)->Unit,
    draft: (String,Int,Int)->Unit,modifier: Modifier,begin: ()->Unit,done: ()->Unit) {
    var field by rememberSaveable(stateSaver=TextInspectorModeCodec.sourceSaver) {mutableStateOf(TextFieldValue(document.text,TextRange(selection?.start ?: 0,selection?.end ?: 0)))}
    val requester=remember {FocusRequester()}
    LaunchedEffect(Unit) {begin();if(field.text!=document.text) draft(field.text,-1,-1);requester.requestFocus()}
    LaunchedEffect(selection) {val r=selection ?: TextSelectionRange(0,0);field=field.copy(selection=TextRange(r.start.coerceAtMost(field.text.length),r.end.coerceAtMost(field.text.length)))}
    OutlinedTextField(field,{next->val old=field;val edges=TextSourceBoundaries.of(next.text)
        val start=TextSourceBoundaries.floor(edges,next.selection.min);val end=TextSourceBoundaries.ceiling(edges,next.selection.max)
        field=next.copy(selection=TextRange(start,end));onSelection(next.text,TextSelectionRange(start,end));if(old.text!=next.text) {
        val delta=next.text.length-old.text.length;val start=if(old.selection.collapsed && delta<0) next.selection.min else old.selection.min
        val end=if(old.selection.collapsed && delta<0) start-delta else old.selection.max;draft(next.text,start,end)
    }},modifier.fillMaxWidth().focusRequester(requester).semantics {contentDescription="Source Text"},textStyle=MaterialTheme.typography.bodySmall,
        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={done()}))
}
/** Exact entry: two in-sheet rows beside the anchor cell. Validation reports through the feedback strip. */
@Composable private fun TextNumericStrip(draft: TextNumericDraft,preview: (TextProperties)->Unit,done: ()->Unit,cancel: ()->Unit,begin: ()->Unit,
    note: (String?)->Unit,resetSpacing: (()->Unit)?,modifier: Modifier) {
    var input by rememberSaveable(draft.control,draft.layer.id) {mutableStateOf(if(draft.mixed) "" else draft.initial.toString())};var fine by rememberSaveable {mutableStateOf(false)}
    var touched by rememberSaveable {mutableStateOf(false)}
    val value=input.replace(',','.').toFloatOrNull();val payload=value?.let {runCatching {draft.payload(it)}.getOrNull()}
    val invalid=payload==null
    val requester=remember {FocusRequester()}
    val manager=LocalFocusManager.current
    LaunchedEffect(Unit) {begin();if(touched && payload!=null) preview(payload);requester.requestFocus()}
    LaunchedEffect(invalid) {note(if(invalid) "Enter a finite value within the property's limits" else null)}
    DisposableEffect(Unit) {onDispose {note(null)}}
    fun commit() {if(payload!=null) {manager.clearFocus();if(!touched) cancel() else done()}}
    val title="${draft.scope.title(draft.control)} · ${draft.scope.unit(draft.control)}"
    Surface(modifier.testTag("text-numeric-entry"),shape=MaterialTheme.shapes.medium,tonalElevation=3.dp,shadowElevation=6.dp,
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Column {
            Row(Modifier.fillMaxWidth().height(TextInspectorBudget.ROW.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(title,Modifier.weight(1f).padding(horizontal=8.dp),maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall)
                BasicTextField(input,{touched=true;input=it;it.replace(',','.').toFloatOrNull()?.let {v->runCatching {draft.payload(v)}.onSuccess(preview)}},singleLine=true,
                    textStyle=MaterialTheme.typography.bodyMedium.copy(color=MaterialTheme.colorScheme.onSurface),cursorBrush=SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal,imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={commit()}),
                    modifier=Modifier.weight(1f).fillMaxHeight().padding(4.dp)
                        .border(1.dp,if(invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,MaterialTheme.shapes.small)
                        .focusRequester(requester).semantics {contentDescription="$title, exact value";if(invalid) error("Enter a finite value within the property's limits")},
                    decorationBox={inner->Box(Modifier.fillMaxSize().padding(horizontal=8.dp),contentAlignment=Alignment.CenterStart) {inner()}})
            }
            Row(Modifier.fillMaxWidth().height(TextInspectorBudget.ROW.dp),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={fine=!fine},modifier=Modifier.heightIn(min=48.dp).semantics {stateDescription=if(fine) "Fine steps on" else "Fine steps off"}) {Text(if(fine) "Fine ✓" else "Fine")}
                listOf(-1f,1f).forEach {step->TextToolIcon(if(step<0) "MINUS" else "PLUS",if(step<0) "Decrease" else "Increase",{
                    touched=true;val v=(value ?: draft.initial)+step*TextScrubTuning.EXACT_STEP*(if(fine) TextScrubTuning.FINE else 1f);input=v.toString();runCatching {draft.payload(v)}.onSuccess(preview)})}
                if(resetSpacing!=null) TextToolIcon("RESET","Reset selected manual spacing",resetSpacing)
                Spacer(Modifier.weight(1f))
                TextToolIcon("CANCEL","Cancel ${draft.scope.title(draft.control)}",cancel)
                TextToolIcon("DONE","Done ${draft.scope.title(draft.control)}",::commit,!invalid)
            }
        }
    }
}
/** Global Colour Picker inside the sheet, labelled Fill or Stroke with its scope. */
@Composable private fun TextColourOverlay(layer: CompositionLayer,time: Long,scope: TextEditScope,c: TextControl,
    preview: (TextProperties)->Unit,begin: ()->Unit,done: ()->Unit,cancel: ()->Unit,palette: PaletteRepository?,eyedropper: (()->Unit)?,
    sample: (((Int?)->Unit)->Unit)?,cancelSample: ()->Unit,samplingChanged: (Boolean)->Unit,inherit: (()->Unit)?,modifier: Modifier) {
    val captured=remember {layer};val capturedTime=remember {time};val doc=remember {captured.text.source.evaluateDocument(time-layer.resolvedKeyframeAnchorUs)}
    val r=scope.range;val s=doc.runAt(r?.start ?: 0)?.style
    val initial=remember {if(c==TextControl.FILL) s?.fill?.takeIf {scope.selected} ?: layer.text.fill.evaluate(time-layer.resolvedKeyframeAnchorUs) else s?.stroke?.takeIf {scope.selected} ?: layer.text.strokeColour.evaluate(time-layer.resolvedKeyframeAnchorUs)}
    var savedColour by rememberSaveable {mutableIntStateOf(initial)}
    var touched by rememberSaveable {mutableStateOf(false)}
    var sampling by remember {mutableStateOf(false)}
    var beforeGesture by remember {mutableIntStateOf(savedColour)}
    var touchedBeforeGesture by remember {mutableStateOf(false)}
    val colour=FluxxColor.fromArgb(savedColour)
    fun update(v: FluxxColor) {
        touched=true;savedColour=v.toArgb();val argb=savedColour
        val next=if(scope.selected) TextEdits.document(captured,capturedTime,doc.format(r!!.start,r.end) {if(c==TextControl.FILL) it.copy(fill=argb) else it.copy(stroke=argb)})
            else {val p=if(c==TextControl.FILL) AnimPropertyType.FILL_COLOUR else AnimPropertyType.STROKE_COLOUR
                val track=captured.text.colour(p);val local=capturedTime-captured.resolvedKeyframeAnchorUs
                captured.text.withColour(p,if(track.isAnimated) track.copy(keyframes=FrozenList.of((track.keyframes.filterNot {it.timeUs==local}+ColourKeyframe(local,argb,track.keyframes.firstOrNull {it.timeUs==local}?.easing ?: EasingPreset.EASY_EASE)).sortedBy {it.timeUs})) else track.copy(staticValue=argb))}
        preview(next)
    }
    LaunchedEffect(Unit) {begin();if(touched) update(colour)}
    DisposableEffect(Unit) {onDispose {if(sampling) cancelSample();samplingChanged(false)}}
    val title="${scope.title(c)} — ${if(scope.selected) "Selection: ${scope.count(doc)} characters" else "Whole layer"}"
    Surface(modifier.testTag("text-colour"),shape=MaterialTheme.shapes.large,tonalElevation=3.dp,shadowElevation=6.dp,
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxSize().padding(horizontal=4.dp)) {
            Row(Modifier.fillMaxWidth().height(TextInspectorBudget.ROW.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(title,Modifier.weight(1f).padding(horizontal=4.dp).semantics {heading()},maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
                if(inherit!=null) TextButton(onClick=inherit,modifier=Modifier.heightIn(min=48.dp)) {Text("Inherit")}
                TextToolIcon("CANCEL","Cancel $title",cancel)
                TextToolIcon("DONE","Done $title",{if(!touched) cancel() else done()})
            }
            FluxxColorPicker(colour,::update,onEyedropperRequest=if(sample!=null) ({sampling=true;samplingChanged(true);sample {argb->if(argb!=null) update(FluxxColor.fromArgb(argb));sampling=false;samplingChanged(false)}}) else eyedropper,
                modifier=Modifier.fillMaxWidth().weight(1f),onColorPreview=::update,paletteRepository=palette,
                onGestureBegin={beforeGesture=savedColour;touchedBeforeGesture=touched},
                onGestureCancel={update(FluxxColor.fromArgb(beforeGesture));touched=touchedBeforeGesture})
        }
    }
}
