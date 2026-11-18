package com.fluxx.android.ui.inspector

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import com.fluxx.android.ui.common.colorpicker.*
import com.fluxx.android.ui.theme.*
import kotlinx.coroutines.delay

/**
 * Edit Text as an ordinary inspector page: same frame and height as Transform, one header row
 * (← | Character | Paragraph | Animate | ⋮), the shared left utility rail, icon tiles whose values change through
 * small anchored slider popovers, and a bottom bar (font, size, text). Fonts and Custom colour are pages in the
 * same frame. The page never scrolls; the keyboard overlays it and only the focused popover lifts above it.
 */
@Composable internal fun TextInspectorPanel(layer: CompositionLayer,time: Long,frameRate: FrameRate,
    onAction: (EditorAction)->Unit,onFocus: (AnimPropertyType?)->Unit,
    onSourceBegin: ()->Unit,onSourceDraft: (String,Int,Int)->Unit,onFinish: ()->Unit,onCancel: ()->Unit,
    onValueBegin: ()->Unit,onPayloadPreview: (TextProperties)->Unit,
    onCharacter: (Int,Int,(TextRunStyle)->TextRunStyle)->Unit,
    onParagraph: (Int,Int,(TextParagraphStyle)->TextParagraphStyle)->Unit,onBox: (Float)->Unit,
    compositionWidth: Int,selection: TextSelectionRange?,onSelection: (TextSelectionRange?)->Unit,
    error: String?,onTools: ()->Unit,
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
    var popoverName by rememberSaveable(layer.id) {mutableStateOf<String?>(null)}
    var linkScale by rememberSaveable(layer.id) {mutableStateOf(false)}
    val tab=TextTab.entries[tabIndex];val control=TextControl.valueOf(controlName)
    val popover=popoverName?.let {runCatching {TextControl.valueOf(it)}.getOrNull()}
    var mode by rememberSaveable(layer.id,stateSaver=TextInspectorModeCodec.saver) {mutableStateOf<TextInspectorMode>(TextInspectorMode.Browse)}
    var overflow by remember {mutableStateOf(false)}
    var resetAnimation by remember {mutableStateOf(false)}
    var explode by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf<String?>(null)}
    var feedbackOpen by remember {mutableStateOf(false)}
    var colourTouched by remember {mutableStateOf(false)}
    var axisSnapshot by remember {mutableStateOf<Triple<CompositionLayer,Long,TextEditScope>?>(null)}
    var activeDraft by remember {mutableStateOf<TextNumericDraft?>(null)}
    val library=com.fluxx.android.fonts.FontLibraryRepository.getInstance(LocalContext.current)
    val fontEntries by library.state.collectAsState()
    var libraryReady by remember {mutableStateOf(false)}
    LaunchedEffect(library) {runCatching {library.load()};libraryReady=true}
    LaunchedEffect(message) {if(message!=null) {delay(5000);message=null}}
    val missingFonts=libraryReady && document.runs.any {run->run.style.font?.let {ref->if(ref.sha256==null) ref.bundledId !in TextFontRef.BUNDLED_IDS else fontEntries.none {entry->entry.sha256==ref.sha256 && entry.faces.any {it.index==ref.faceIndex}}} ?: false}
    val manager=LocalFocusManager.current
    val density=LocalDensity.current
    val owner=TextFocus.owner(editScope,tab,control,popover)
    val ownerLocal=frameRate.snap(time)-layer.resolvedKeyframeAnchorUs
    val animated=owner?.let {layer.text.animated(it)} ?: false
    val onKey=owner?.let {ownerLocal in layer.propertyKeyTimes(it)} ?: false
    val scopeLabel=if(editScope.selected) "Selection: ${editScope.count(document)} characters" else "Whole layer"
    val a=selection?.start ?: 0;val b=selection?.end ?: 0
    val style=document.runAt(a)?.style ?: TextRunStyle()
    val paragraph=document.paragraphs.getOrNull(document.text.take(a).count {it=='\n'}) ?: TextParagraphStyle()
    val effectiveAlignment=paragraph.alignment ?: ParagraphAlignment.valueOf(layer.text.alignment.name)
    // The action bar's Prev/Next keyframe buttons follow this owner (last touched control or the open popover).
    LaunchedEffect(owner) {onFocus(owner)}

    fun focus(c: TextControl) {controlName=c.name;message=null}
    fun format(change: (TextRunStyle)->TextRunStyle) {onCharacter(a,b,change)}
    fun direction(value: ParagraphDirection) {focus(TextControl.DIRECTION);onAction(EditorAction.SetTextPayload(layer.id,TextEdits.document(layer,frameRate.snap(time),document.formatParagraphs(a,b) {it.copy(direction=value)})))}
    fun align(c: TextControl,value: ParagraphAlignment?) {focus(c);onParagraph(a,b) {it.copy(alignment=value)}}
    fun layerToggle(c: TextControl,property: AnimPropertyType,on: Boolean) {
        focus(c)
        if(editScope.available(c)) onAction(EditorAction.SetTextPayload(layer.id,TextEdits.scalar(layer,time,property,if(on) 0f else 1f)))
        else message="Clear selection to edit layer ${c.label.lowercase()}"
    }
    fun openPopover(c: TextControl) {
        if(!editScope.available(c)) {focus(c);message="Clear selection to edit ${c.label.lowercase()}";return}
        if(c!=TextControl.PAINT) focus(c);popoverName=c.name
    }
    // Slider drags: one transaction per drag; typed commits and resets: one transaction each.
    fun numericBegin(c: TextControl) {focus(c);onValueBegin();activeDraft=TextNumericDraft(layer,time,frameRate,editScope,c)}
    fun numericPreview(v: Float) {activeDraft?.let {d->runCatching {d.payload(d.scrubValue(v),linkScale)}.onSuccess(onPayloadPreview).onFailure {message=it.message}}}
    fun numericFinish() {if(activeDraft!=null) {activeDraft=null;onFinish()}}
    fun numericCancel() {if(activeDraft!=null) {activeDraft=null;onCancel()}}
    fun numericSet(c: TextControl,value: Float) {
        focus(c)
        if(!editScope.available(c)) {message="Clear selection to edit ${c.label}";return}
        onBeginDraft(false) {fresh->val d=TextNumericDraft(fresh,time,frameRate,editScope,c)
            runCatching {d.payload(d.scrubValue(value),linkScale)}.onSuccess {onPayloadPreview(it);onFinish()}.onFailure {onCancel();message=it.message}}
    }
    fun closePopover() {numericCancel();if(axisSnapshot!=null) {onCancel();axisSnapshot=null};popoverName=null}
    fun colourPage(c: TextControl) {focus(c);popoverName=null;colourTouched=false;onBeginDraft(false) {fresh->mode=TextInspectorMode.Colour(c,fresh,frameRate.snap(time),editScope)}}
    fun colourOnce(c: TextControl,argb: Int) {
        focus(c);onBeginDraft(false) {fresh->onPayloadPreview(textColourPayload(fresh,frameRate.snap(time),editScope,c,argb));onFinish()}
    }
    fun sourceDone() {manager.clearFocus();if(mode==TextInspectorMode.Source) {onFinish();mode=TextInspectorMode.Browse}}
    fun sourceCancel() {manager.clearFocus();if(mode==TextInspectorMode.Source) {onCancel();mode=TextInspectorMode.Browse}}
    fun closePage() {
        when(mode) {
            // Colour edits apply live; leaving the page commits them once (or discards an untouched draft).
            is TextInspectorMode.Colour->{if(colourTouched) onFinish() else onCancel();colourTouched=false}
            TextInspectorMode.Fonts->if(axisSnapshot!=null) {onCancel();axisSnapshot=null}
            else->Unit
        }
        mode=TextInspectorMode.Browse
    }
    fun cancelAll() {
        numericCancel();popoverName=null
        if(mode is TextInspectorMode.Colour || mode==TextInspectorMode.Source || axisSnapshot!=null) onCancel()
        axisSnapshot=null;manager.clearFocus();mode=TextInspectorMode.Browse
    }
    val lifecycle=LocalLifecycleOwner.current
    val context=LocalContext.current
    val currentCancel by rememberUpdatedState(::cancelAll)
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event->
            var current: android.content.Context?=context
            while(current is android.content.ContextWrapper && current !is android.app.Activity) current=current.baseContext
            if(event==Lifecycle.Event.ON_STOP && (current as? android.app.Activity)?.isChangingConfigurations!=true) {currentCancel()}
        }
        lifecycle.lifecycle.addObserver(observer);onDispose {lifecycle.lifecycle.removeObserver(observer)}
    }
    fun resetValue() {
        when(control) {
            TextControl.SOURCE->message="Source Text is preserved. Use Reset text style for formatting."
            TextControl.SIZE,TextControl.TRACKING,TextControl.LEADING,TextControl.BASELINE,TextControl.STROKE_WIDTH,TextControl.H_SCALE,TextControl.V_SCALE,
            TextControl.INDENT_LEFT,TextControl.INDENT_RIGHT,TextControl.INDENT_FIRST,TextControl.BEFORE,TextControl.AFTER->
                numericSet(control,TextNumericDraft(layer,time,frameRate,editScope,control).default)
            TextControl.FILL,TextControl.STROKE,TextControl.PAINT->{val c=if(control==TextControl.STROKE) TextControl.STROKE else TextControl.FILL
                if(editScope.selected) format {if(c==TextControl.FILL) it.copy(fill=null) else it.copy(stroke=null)}
                else onAction(TextEdits.colour(layer,time,requireNotNull(editScope.owner(c)),if(c==TextControl.FILL) -1 else 0xff000000.toInt()))}
            TextControl.RELATIVE_SIZE->format {it.copy(sizeFactor=1f)}
            TextControl.FONT->format {it.copy(font=null)}
            TextControl.MANUAL_KERNING->format {it.copy(manualKerning=FrozenList.empty())}
            TextControl.BOLD->format {it.copy(fauxBold=false)};TextControl.ITALIC->format {it.copy(fauxItalic=false)}
            TextControl.CASE,TextControl.ALL_CAPS,TextControl.LOWERCASE,TextControl.SMALL_CAPS->format {it.copy(caps=TextCaps.NONE)}
            TextControl.SUPER,TextControl.SUB->format {it.copy(script=TextScriptPosition.NORMAL)}
            TextControl.KERNING->format {it.copy(kerning=TextKerning.METRICS)}
            TextControl.ORDER,TextControl.LIGATURES->if(!editScope.selected) onAction(EditorAction.SetTextPayload(layer.id,TextEdits.scalar(layer,time,requireNotNull(owner),if(control==TextControl.LIGATURES) 1f else 0f)))
            TextControl.BOX,TextControl.BOX_MODE->if(!editScope.selected) onBox(0f)
            TextControl.ALIGNMENT,TextControl.ALIGN_LEFT,TextControl.ALIGN_CENTRE,TextControl.ALIGN_RIGHT,TextControl.JUSTIFY->onParagraph(a,b) {it.copy(alignment=null)}
            TextControl.DIRECTION->direction(ParagraphDirection.LTR)
            else->message="Choose a specific property to reset"
        }
    }
    // System Back, Escape and the editor's top-left arrow share one state machine.
    fun back() {
        when(TextInspectorBack.step(mode,popover,explode,resetAnimation)) {
            TextBackStep.CLOSE_EXPLODE->explode=false
            TextBackStep.CLOSE_CONFIRM->resetAnimation=false
            TextBackStep.PARENT_CHOICE->popoverName=TextControl.JUSTIFY.name
            TextBackStep.CLOSE_POPOVER->closePopover()
            TextBackStep.FINISH_SOURCE->sourceDone()
            TextBackStep.CLOSE_PAGE->closePage()
            TextBackStep.RETURN_TO_GRID->{manager.clearFocus();onSelection(null);onTools()}
        }
    }
    BackHandler {back()}
    DisposableEffect(layer.id) {onDispose {onCancel()}}
    if(explode) ExplodeTextDialog(layer,time,{explode=false},onExplodePrepare,onExplodeCommit)

    val hitSize=LocalMinimumInteractiveComponentSize.current.let {if(it==Dp.Unspecified) 48.dp else it}
    val compact=hitSize<48.dp
    val pitch=TextInspectorBudget.pitch(compact).dp
    val fontScale=density.fontScale
    val large=TextInspectorBudget.large(fontScale)
    val requestedStroke=layer.text.strokeWidth.evaluate(local)+if(editScope.selected) style.strokeDelta else 0f
    val strokeNote=TextFeedback.strokeClampFull(requestedStroke,strokeClamp)
    val feedback=error ?: message
    val fillArgb=style.fill?.takeIf {editScope.selected} ?: layer.text.fill.evaluate(local)
    val strokeArgb=style.stroke?.takeIf {editScope.selected} ?: layer.text.strokeColour.evaluate(local)

    // ---- Tiles ------------------------------------------------------------------------------------------------
    @Composable fun NumericTile(c: TextControl,modifier: Modifier,text: String?=null) {
        val d=TextNumericDraft(layer,time,frameRate,editScope,c)
        val limit=requireNotNull(d.limit)
        val enabled=editScope.available(c) && !finishing
        val title=editScope.title(c)
        InspectorTile(TextInspectorIcons.control(c),"$title, ${editScope.unit(c)}",modifier,pitch,popover==c,enabled,
            caption=if(d.mixed) "—" else tileCaption(limit,d.initial),text=text,testTag="text-tile-${c.name}",popover={
                AnchoredPopover(popover==c,::closePopover,"text-popover-${c.name}") {
                    SliderPopoverContent(limit,d.initial,d.mixed,title,onReset={numericSet(c,d.default)},onBegin={numericBegin(c)},
                        onPreview=::numericPreview,onFinish=::numericFinish,onCancel=::numericCancel,onTyped={numericSet(c,it)},enabled=enabled,
                        extras=when(c) {
                            TextControl.BOX->({
                                TextButton(enabled=!editScope.selected,onClick={closePopover();onBox(0f)}) {Text("Auto (point)",color=TextPrimary)}
                                TextButton(enabled=!editScope.selected,onClick={closePopover();runCatching {TextEdits.fillCompositionWidth(layer,time,compositionWidth)}.onSuccess(onBox).onFailure {message=it.message}}) {
                                    Text("Fill composition",color=TextPrimary,maxLines=1)}
                            })
                            TextControl.STROKE_WIDTH->strokeNote?.let {note->{Text(note,Modifier.padding(horizontal=8.dp).semantics {liveRegion=LiveRegionMode.Polite},
                                color=TextSecondary,style=MaterialTheme.typography.labelSmall,maxLines=2,overflow=TextOverflow.Ellipsis)}}
                            else->null
                        })
                }
            }) {openPopover(c)}
    }
    @Composable fun ToggleTile(c: TextControl,modifier: Modifier,on: Boolean,text: String?,enabled: Boolean=true,vector: androidx.compose.ui.graphics.vector.ImageVector=TextInspectorIcons.control(c),
        caption: String?=null,state: String?=null,click: ()->Unit) {
        InspectorTile(vector,c.label,modifier,pitch,on,enabled && !finishing,caption=caption,text=text,state=state,testTag="text-tile-${c.name}") {click()}
    }
    @Composable fun Tile(c: TextControl,modifier: Modifier,labelled: Boolean) {
        val label=if(labelled) shortLabel(c) else null
        when(c) {
            TextControl.PAINT->PaintTile(fillArgb,strokeArgb,scopeLabel,modifier,pitch,popover==TextControl.PAINT,label,!finishing,{focus(if(control==TextControl.STROKE) TextControl.STROKE else TextControl.FILL);openPopover(TextControl.PAINT)}) {
                AnchoredPopover(popover==TextControl.PAINT,::closePopover,"text-colour-popover") {
                    TextColourPopoverContent(target=if(control==TextControl.STROKE) TextControl.STROKE else TextControl.FILL,fill=fillArgb,stroke=strokeArgb,
                        selected=editScope.selected,palette=palette,onTarget={focus(it)},onSwatch={t,argb->colourOnce(t,argb)},
                        onAlphaBegin={t->focus(t);onValueBegin();activeDraft=null;axisSnapshot=Triple(layer,frameRate.snap(time),editScope)},
                        onAlphaPreview={t,argb->axisSnapshot?.let {(base,captured,scope)->onPayloadPreview(textColourPayload(base,captured,scope,t,argb))}},
                        onAlphaFinish={if(axisSnapshot!=null) {axisSnapshot=null;onFinish()}},onAlphaCancel={if(axisSnapshot!=null) {axisSnapshot=null;onCancel()}},
                        onInherit={t->closePopover();format {if(t==TextControl.FILL) it.copy(fill=null) else it.copy(stroke=null)}},
                        onCustom={t->colourPage(t)})
                }
            }
            TextControl.ORDER->{val strokeOver=layer.text.paintOrder(local)!=0
                ToggleTile(c,modifier,!strokeOver,label,editScope.available(c),caption=if(strokeOver) "S" else "F",state=if(strokeOver) "Stroke over fill" else "Fill over stroke") {
                    layerToggle(c,AnimPropertyType.STROKE_OVER_FILL,strokeOver)}}
            TextControl.SCALE_LINK->ToggleTile(c,modifier,linkScale,label,vector=TextInspectorIcons.icon(if(linkScale) "SCALE_LINK" else "LINK_OFF"),
                state=if(linkScale) "Linked: H and V scale together" else "Unlinked") {focus(c);linkScale=!linkScale}
            TextControl.BOLD->ToggleTile(c,modifier,style.fauxBold,label) {focus(c);format {it.copy(fauxBold=!style.fauxBold)}}
            TextControl.ITALIC->ToggleTile(c,modifier,style.fauxItalic,label) {focus(c);format {it.copy(fauxItalic=!style.fauxItalic)}}
            // No underline/strikethrough in the document model yet; shown disabled (schema change required).
            TextControl.UNDERLINE,TextControl.STRIKE->ToggleTile(c,modifier,false,label,enabled=false,state="Unavailable: needs a document format change") {}
            TextControl.SUPER,TextControl.SUB->{val target=if(c==TextControl.SUPER) TextScriptPosition.SUPER else TextScriptPosition.SUB
                ToggleTile(c,modifier,style.script==target,label) {focus(c);format {it.copy(script=if(style.script==target) TextScriptPosition.NORMAL else target)}}}
            TextControl.ALL_CAPS,TextControl.LOWERCASE,TextControl.SMALL_CAPS->{val target=when(c) {TextControl.ALL_CAPS->TextCaps.ALL;TextControl.LOWERCASE->TextCaps.LOWER;else->TextCaps.SMALL}
                ToggleTile(c,modifier,style.caps==target,label) {focus(c);format {it.copy(caps=if(style.caps==target) TextCaps.NONE else target)}}}
            TextControl.LIGATURES->{val on=layer.text.ligatures.evaluate(local)!=0f
                ToggleTile(c,modifier,on,label,editScope.available(c),state=if(layer.text.effectiveLigatures(local)) "On" else if(on) "On, split while tracking is not zero" else "Off") {
                    layerToggle(c,AnimPropertyType.LIGATURES,on)}}
            TextControl.KERNING->ToggleTile(c,modifier,style.kerning==TextKerning.METRICS,label,caption=if(style.kerning==TextKerning.METRICS) "Met" else "Off",
                state=if(style.kerning==TextKerning.METRICS) "Metrics" else "Off") {focus(c);format {it.copy(kerning=if(style.kerning==TextKerning.METRICS) TextKerning.OFF else TextKerning.METRICS)}}
            TextControl.ALIGN_LEFT,TextControl.ALIGN_CENTRE,TextControl.ALIGN_RIGHT->{
                val value=when(c) {TextControl.ALIGN_LEFT->ParagraphAlignment.LEFT;TextControl.ALIGN_CENTRE->ParagraphAlignment.CENTRE;else->ParagraphAlignment.RIGHT}
                ToggleTile(c,modifier,effectiveAlignment==value,label,vector=TextInspectorIcons.alignment(value),state=if(paragraph.alignment==null && effectiveAlignment==value) "On, inherited" else null) {align(c,value)}}
            TextControl.JUSTIFY->InspectorTile(TextInspectorIcons.alignment(effectiveAlignment.takeIf {it.ordinal>=3} ?: ParagraphAlignment.JUSTIFY_LAST_LEFT),
                "Justify choices"+(effectiveAlignment.takeIf {it.ordinal>=3}?.let {", ${alignmentLabel(it)}"} ?: ""),modifier,pitch,
                popover==TextControl.JUSTIFY || popover==TextControl.BASE_ALIGNMENT || effectiveAlignment.ordinal>=3,!finishing,text=label,testTag="text-tile-JUSTIFY",popover={
                    AnchoredPopover(popover==TextControl.JUSTIFY || popover==TextControl.BASE_ALIGNMENT,::closePopover,"text-choice-popover") {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            fun choose(action: ()->Unit) {action();popoverName=null}
                            if(popover==TextControl.BASE_ALIGNMENT) {
                                InspectorIconButton(TextInspectorIcons.icon("BACK"),"Back to justify choices") {popoverName=TextControl.JUSTIFY.name}
                                TextAlignment.entries.forEach {value->val p=ParagraphAlignment.valueOf(value.name)
                                    InspectorIconButton(TextInspectorIcons.alignment(p),"Layer base: ${alignmentLabel(p)}",selected=layer.text.alignment==value) {
                                        choose {onBeginDraft(false) {fresh->onPayloadPreview(fresh.text.copy(alignment=value));onFinish()}}}}
                            } else {
                                ParagraphAlignment.entries.drop(3).forEach {value->InspectorIconButton(TextInspectorIcons.alignment(value),alignmentLabel(value),selected=paragraph.alignment==value) {choose {align(TextControl.JUSTIFY,value)}}}
                                InspectorIconButton(TextInspectorIcons.icon("INHERIT"),"Inherit layer alignment",selected=paragraph.alignment==null) {choose {align(TextControl.ALIGNMENT,null)}}
                                InspectorIconButton(TextInspectorIcons.icon("NEXT"),if(editScope.selected) "Layer base alignment; clear selection to edit" else "Layer base alignment choices",enabled=!editScope.selected) {
                                    popoverName=TextControl.BASE_ALIGNMENT.name}
                            }
                        }
                    }
                }) {openPopover(TextControl.JUSTIFY)}
            TextControl.BOX_MODE->{val point=document.boxWidth==0f
                ToggleTile(c,modifier,!point,label,editScope.available(c),vector=TextInspectorIcons.icon(if(point) "POINT" else "AREA"),state=if(point) "Point text" else "Box text") {
                    focus(c);if(!editScope.available(c)) message="Box width is layer-only"
                    else if(point) runCatching {TextEdits.fillCompositionWidth(layer,time,compositionWidth)}.onSuccess(onBox).onFailure {message=it.message} else onBox(0f)}}
            TextControl.DIRECTION->ToggleTile(c,modifier,paragraph.direction==ParagraphDirection.RTL,label,vector=TextInspectorIcons.icon(paragraph.direction.name),
                state=if(paragraph.direction==ParagraphDirection.RTL) "Right to left, stored only; RTL rendering unsupported" else "Left to right") {
                val next=if(paragraph.direction==ParagraphDirection.LTR) ParagraphDirection.RTL else ParagraphDirection.LTR
                direction(next);if(next==ParagraphDirection.RTL) message="RTL direction stored only; RTL rendering unsupported"}
            else->if(PropertyLimits.forControl(c,editScope.selected)!=null) NumericTile(c,modifier,label) else Spacer(modifier)
        }
    }

    Column(Modifier.fillMaxSize().testTag("text-inspector").onPreviewKeyEvent {
        if(it.key==Key.Escape && it.type==KeyEventType.KeyUp && (mode!=TextInspectorMode.Browse || popover!=null || resetAnimation)) {back();true} else false}) {
        // ---- Header: ← | Character | Paragraph | Animate | (!) | ⋮ ----------------------------------------------
        val subPage=mode==TextInspectorMode.Fonts || mode is TextInspectorMode.Colour
        Row(Modifier.fillMaxWidth().height(TextInspectorBudget.HEADER.dp).testTag("text-inspector-header"),verticalAlignment=Alignment.CenterVertically) {
            InspectorIconButton(TextInspectorIcons.icon("BACK"),if(subPage) "Back to ${tab.name.lowercase()}" else "Back to layer tools") {back()}
            if(subPage) {
                val title=when(val m=mode) {is TextInspectorMode.Colour->"${editScope.title(m.control)} · ${if(m.scope.selected) "Selection" else "Whole layer"}";else->"Fonts · $scopeLabel"}
                Text(title,Modifier.weight(1f).padding(horizontal=8.dp).semantics {heading()},color=TextPrimary,style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
            } else TextTab.entries.forEach {t->
                val selectedTab=t==tab
                Box(Modifier.weight(1f).fillMaxHeight().clickable(enabled=!finishing,role=Role.Tab) {
                    closePopover();tabIndex=t.ordinal;page=0
                }.semantics {selected=selectedTab;if(t==TextTab.ANIMATE) stateDescription="Animator controls not yet available"},contentAlignment=Alignment.Center) {
                    Text(t.name.lowercase().replaceFirstChar {it.uppercase()},color=if(selectedTab) TextPrimary else TextSecondary,
                        style=MaterialTheme.typography.labelMedium,maxLines=1)
                    if(selectedTab) Box(Modifier.align(Alignment.BottomCenter).padding(bottom=4.dp).size(24.dp,2.dp).background(TextPrimary,RoundedCornerShape(1.dp)))
                }
            }
            if(feedback!=null) Box {
                InspectorIconButton(TextInspectorIcons.icon("WARNING"),"Message: $feedback",Modifier.semantics {liveRegion=LiveRegionMode.Polite}) {feedbackOpen=true}
                AnchoredPopover(feedbackOpen,{feedbackOpen=false},"text-feedback") {
                    Text(feedback,Modifier.widthIn(max=280.dp).padding(8.dp),color=if(error!=null) MaterialTheme.colorScheme.error else TextPrimary,style=MaterialTheme.typography.labelMedium)
                }
            }
            Box {
                InspectorIconButton(TextInspectorIcons.icon("OVERFLOW"),"Text menu",Modifier.testTag("text-menu")) {overflow=true}
                DropdownMenu(overflow,{overflow=false}) {
                    TextHeaderMenu.items.forEach {item->DropdownMenuItem(text={Text(TextHeaderMenu.label(item,tab,scopeLabel))},enabled=TextHeaderMenu.enabled(item,tab),onClick={
                        overflow=false
                        when(item) {
                            TextMenuItem.EXPLODE->{onFinish();explode=true}
                            // One undo entry: one format/paragraph transaction for the current scope.
                            TextMenuItem.RESET_STYLE->if(tab==TextTab.PARAGRAPH) onParagraph(a,b) {TextParagraphStyle()} else format {TextRunStyle()}
                            TextMenuItem.MANUAL_KERNING->openPopover(TextControl.MANUAL_KERNING)
                            TextMenuItem.RELATIVE_SIZE->openPopover(TextControl.RELATIVE_SIZE)
                        }
                    })}
                }
                for(c in listOf(TextControl.MANUAL_KERNING,TextControl.RELATIVE_SIZE)) {
                    val d=TextNumericDraft(layer,time,frameRate,editScope,c)
                    AnchoredPopover(popover==c,::closePopover,"text-popover-${c.name}") {
                        SliderPopoverContent(requireNotNull(d.limit),d.initial,d.mixed,editScope.title(c),onReset={popoverName=null;resetValue()},onBegin={numericBegin(c)},
                            onPreview=::numericPreview,onFinish=::numericFinish,onCancel=::numericCancel,onTyped={numericSet(c,it)},enabled=!finishing,
                            extras=if(c==TextControl.MANUAL_KERNING) ({TextButton(onClick={closePopover();format {it.copy(manualKerning=FrozenList.empty())}}) {
                                Text("Reset selected manual spacing",color=TextPrimary)}}) else null)
                    }
                }
            }
        }
        // ---- Body: shared rail + page --------------------------------------------------------------------------
        Row(Modifier.weight(1f).fillMaxWidth()) {
            val discrete=owner in TextKeyState.HOLD_OWNERS
            InspectorUtilityRail(resetLabel="Reset ${editScope.title(TextFocus.control(control,popover))} value",onReset=::resetValue,
                isAnimated=animated,hasKeyframeAtPlayhead=onKey,
                onToggleKeyframe=if(owner==null || finishing) null else ({
                    onAction(if(!animated) EditorAction.ToggleAnimated(layer.id,owner,true,time) else if(onKey) EditorAction.RemoveKeyframe(layer.id,owner,ownerLocal)
                        else if(owner==AnimPropertyType.SOURCE_TEXT) EditorAction.SetTextPayload(layer.id,TextEdits.document(layer,time,document))
                        else if(owner==AnimPropertyType.FILL_COLOUR || owner==AnimPropertyType.STROKE_COLOUR) TextEdits.colour(layer,time,owner,layer.text.colour(owner).evaluate(local))
                        else EditorAction.SetKeyframe(layer.id,owner,ownerLocal,layer.text.scalarValue(owner,local)))
                }),
                show3D=false,keyIcon=TextInspectorIcons.icon(TextKeyState.icon(owner,animated,onKey)),
                keyLabel=when {owner==null->"Keyframes unavailable for selection or Animate";!animated->"Enable $owner animation at playhead";onKey->"Remove $owner keyframe at playhead";else->"Add $owner keyframe at playhead"},
                keyState=TextKeyState.description(owner,animated,onKey,CompositionTimecode.format(time,frameRate)),
                extraItems=if(owner==null) emptyList() else buildList {
                    add(RailMenuItem(if(animated) "Freeze at playhead" else "Enable animation") {onAction(EditorAction.ToggleAnimated(layer.id,owner,!animated,time))})
                    if(animated) add(RailMenuItem("Reset animation…") {resetAnimation=true})
                    if(discrete) add(RailMenuItem("Hold (discrete)",enabled=false) {})
                    else if(onKey) EasingPreset.entries.forEach {easing->add(RailMenuItem(easing.name) {
                        onAction(if(owner==AnimPropertyType.FILL_COLOUR || owner==AnimPropertyType.STROKE_COLOUR) EditorAction.SetColourKeyframe(layer.id,ownerLocal,layer.text.colour(owner).evaluate(local),easing,owner)
                            else EditorAction.SetKeyframe(layer.id,owner,ownerLocal,layer.text.scalarValue(owner,local),easing))})}
                },
                confirm=if(resetAnimation) "Remove all ${owner?.name} keys, keep the value at the playhead?" to {if(owner!=null) onAction(EditorAction.ToggleAnimated(layer.id,owner,false,time))} else null,
                onConfirmDismiss={resetAnimation=false})
            Spacer(Modifier.width(TextInspectorBudget.RAIL_GAP.dp))
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().testTag("text-inspector-body")) {
                val capacity=kotlin.math.floor(maxHeight.value/pitch.value+1e-3f).toInt()
                when(val m=mode) {
                    TextInspectorMode.Fonts->TextFontsPage(style.font,layer.text.fontId,scopeLabel,
                        {ref->if(axisSnapshot!=null) {onCancel();axisSnapshot=null};focus(TextControl.FONT);format {it.copy(font=ref)}},
                        axisBegin={focus(TextControl.FONT);axisSnapshot=Triple(layer,frameRate.snap(time),editScope);onValueBegin()},
                        axisPreview={ref->axisSnapshot?.let {(base,capturedTime,capturedScope)->
                            val range=capturedScope.range;val doc=base.text.source.evaluateDocument(capturedTime-base.resolvedKeyframeAnchorUs)
                            onPayloadPreview(TextEdits.document(base,capturedTime,doc.format(range?.start ?: 0,range?.end ?: 0) {it.copy(font=ref)}))
                        }},axisFinish={if(axisSnapshot!=null) {onFinish();axisSnapshot=null}},axisCancel={if(axisSnapshot!=null) {onCancel();axisSnapshot=null}},busy=finishing,
                        baseFont=if(editScope.selected) null else {id->focus(TextControl.BASE_FONT);onBeginDraft(false) {fresh->onPayloadPreview(fresh.text.copy(fontId=id));onFinish()}},
                        hit=pitch,modifier=Modifier.fillMaxSize())
                    is TextInspectorMode.Colour->TextColourPage(m.layer,m.time,m.scope,m.control,onPayloadPreview,onValueBegin,{colourTouched=true},palette,onEyedropper,sampleColour,cancelSample,Modifier.fillMaxSize())
                    else->Column(Modifier.fillMaxSize()) {
                        if(tab==TextTab.ANIMATE) Text("Text animators arrive with F3. No animator controls are available yet.",Modifier.padding(8.dp).semantics {disabled()},
                            style=MaterialTheme.typography.bodySmall,color=TextSecondary)
                        else if(large) {
                            // Large font scales: labelled 3-column grid of the same tiles; whole rows page from the header.
                            val rows=TextInspectorLayout.labelledRows(tab)
                            val pages=TextInspectorLayout.pages(rows,(capacity-1).coerceAtLeast(1))
                            val index=page.coerceIn(0,pages.lastIndex)
                            Row(Modifier.fillMaxWidth().weight(1f)) {
                                Column(Modifier.weight(1f)) {
                                    pages[index].forEachIndexed {i,row->Row(Modifier.fillMaxWidth().height(pitch).testTag("text-row-$i")) {
                                        row.forEach {cell->val cm=Modifier.weight(cell.weight).height(pitch);cell.control?.let {Tile(it,cm,true)} ?: Spacer(cm)}}}
                                }
                                if(pages.size>1) TextButton(onClick={page=(index+1)%pages.size},modifier=Modifier.width(48.dp).semantics {contentDescription="More controls, page ${index+1} of ${pages.size}"}) {
                                    Text("${index+1}/${pages.size}",color=TextPrimary,style=MaterialTheme.typography.labelMedium)}
                            }
                        } else {
                            TextInspectorLayout.faceRows(tab).forEachIndexed {i,row->
                                val groupStart=tab==TextTab.CHARACTER && i<=2 && i>0 || tab==TextTab.PARAGRAPH && i>0
                                Row(Modifier.fillMaxWidth().height(pitch).testTag("text-row-$i").then(if(groupStart) Modifier.hairlineTop() else Modifier),verticalAlignment=Alignment.CenterVertically) {
                                    if(tab==TextTab.CHARACTER && i==0) {
                                        Tile(TextControl.PAINT,Modifier.size(pitch),false);Tile(TextControl.STROKE_WIDTH,Modifier.size(pitch),false)
                                        Spacer(Modifier.weight(1f))
                                        val strokeOver=layer.text.paintOrder(local)!=0
                                        Text("Fill over stroke",color=if(editScope.available(TextControl.ORDER)) TextSecondary else TextDisabled,style=MaterialTheme.typography.labelSmall,maxLines=1)
                                        Switch(!strokeOver,{focus(TextControl.ORDER);layerToggle(TextControl.ORDER,AnimPropertyType.STROKE_OVER_FILL,strokeOver)},
                                            enabled=editScope.available(TextControl.ORDER) && !finishing,
                                            colors=SwitchDefaults.colors(checkedThumbColor=Surface,checkedTrackColor=TextPrimary,uncheckedThumbColor=TextSecondary,uncheckedTrackColor=SurfaceHigh,uncheckedBorderColor=Border),
                                            modifier=Modifier.padding(start=6.dp).semantics {contentDescription="Fill over stroke";if(!editScope.available(TextControl.ORDER)) stateDescription="Clear selection to edit"}.testTag("text-order-switch"))
                                    } else {
                                        val extrasAt=if(tab==TextTab.CHARACTER && i==2) 3 else -1
                                        row.forEachIndexed {j,c->
                                            if(j==extrasAt) Box(Modifier.padding(horizontal=4.dp).size(1.dp,TextInspectorBudget.TILE.dp).background(Border))
                                            Tile(c,Modifier.size(pitch),false)
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                        }
                        // ---- Bottom bar: font chip, size, inline text --------------------------------------------
                        if(TextInspectorLayout.hasBottomBar(tab)) Row(Modifier.fillMaxWidth().height(pitch).hairlineTop().testTag("text-bottom-bar"),verticalAlignment=Alignment.CenterVertically) {
                            val fontName=(if(missingFonts) "Missing · " else "")+fontLabel(style.font)
                            InspectorTile(TextInspectorIcons.icon(if(missingFonts) "MISSING" else "FONT"),"Font: $fontName; open Fonts",Modifier.width(pitch*2).height(pitch),pitch,
                                mode==TextInspectorMode.Fonts,!finishing,text=fontName,testTag="text-font-chip") {closePopover();focus(TextControl.FONT);mode=TextInspectorMode.Fonts}
                            NumericTile(TextControl.SIZE,Modifier.size(pitch))
                            SourceField(document,selection,editScope,mode==TextInspectorMode.Source,!finishing,Modifier.weight(1f).height(pitch),
                                {focus(TextControl.SOURCE);closePopover();onBeginDraft(true) {mode=TextInspectorMode.Source}},onSelection,onSourceSelection,onSourceDraft,onSourceBegin,::sourceDone,::sourceCancel)
                        }
                    }
                }
            }
        }
    }
}

/** Font chip text: bundled family display name plus style, imported family plus style name, or Default (inherited). */
internal fun fontLabel(font: TextFontRef?): String {
    font ?: return "Default"
    if(font.sha256!=null) return "${font.family} ${font.styleName}".trim()
    val name=TextFontsModel.BUNDLED.firstOrNull {it.first==TextFontsModel.bundledBase(font.family)}?.second ?: font.family
    return if(font.style==TextFaceStyle.REGULAR) name else "$name ${font.style.name.lowercase().replace('_',' ')}"
}

private fun Modifier.hairlineTop()=drawBehind {drawLine(Border,Offset(0f,0f),Offset(size.width,0f),1.dp.toPx())}

/** Tiny corner caption: at most five characters (1000 → 1k). */
internal fun tileCaption(limit: PropertyLimit,value: Float): String {
    if(kotlin.math.abs(value)>=1000f) return "${(value/1000f).let {kotlin.math.round(it*10)/10}}k".replace(".0k","k")
    val text=PropertyLimits.format(limit,value)
    return if(text.length<=5) text else text.take(5)
}

/** Fill / Stroke dual-colour tile (front square = fill, back square = stroke), like the action bar's layer icon. */
@OptIn(ExperimentalFoundationApi::class)
@Composable private fun PaintTile(fill: Int,stroke: Int,scope: String,modifier: Modifier,hit: Dp,active: Boolean,label: String?,enabled: Boolean,open: ()->Unit,popover: @Composable ()->Unit) {
    val description="Fill #${"%08X".format(fill)}, stroke #${"%08X".format(stroke)}, $scope"
    InspectorTile(null,"Fill and stroke colour",modifier,hit,active,enabled,text=label,state=description,testTag="text-tile-PAINT",
        iconContent={DualColourIcon(fill,stroke)},popover=popover,click=open)
}

@Composable private fun DualColourIcon(fill: Int,stroke: Int) {
    Canvas(Modifier.size(20.dp)) {
        val s=size.minDimension*.62f;val r=androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
        drawRoundRect(Color(stroke),Offset(size.width-s,0f),Size(s,s),r,style=Stroke(3.dp.toPx()))
        drawRoundRect(TextSecondary,Offset(size.width-s,0f),Size(s,s),r,style=Stroke(.8.dp.toPx()))
        drawRoundRect(Color(fill),Offset(0f,size.height-s),Size(s,s),r)
        drawRoundRect(TextPrimary,Offset(0f,size.height-s),Size(s,s),r,style=Stroke(.8.dp.toPx()))
    }
}

/** One colour edit for Fill or Stroke in the captured scope: run override with a selection, layer paint otherwise. */
internal fun textColourPayload(layer: CompositionLayer,time: Long,scope: TextEditScope,c: TextControl,argb: Int): TextProperties {
    val local=time-layer.resolvedKeyframeAnchorUs
    if(scope.selected) {val r=requireNotNull(scope.range);val doc=layer.text.source.evaluateDocument(local)
        return TextEdits.document(layer,time,doc.format(r.start,r.end) {if(c==TextControl.FILL) it.copy(fill=argb) else it.copy(stroke=argb)})}
    val p=if(c==TextControl.FILL) AnimPropertyType.FILL_COLOUR else AnimPropertyType.STROKE_COLOUR
    val track=layer.text.colour(p)
    return layer.text.withColour(p,if(track.isAnimated) track.copy(keyframes=FrozenList.of((track.keyframes.filterNot {it.timeUs==local}+
        ColourKeyframe(local,argb,track.keyframes.firstOrNull {it.timeUs==local}?.easing ?: EasingPreset.EASY_EASE)).sortedBy {it.timeUs})) else track.copy(staticValue=argb))
}

internal val TEXT_DEFAULT_SWATCHES=listOf(0xFFFFFFFF,0xFFBDBDBD,0xFF757575,0xFF212121,0xFF000000,0xFFE53935,0xFFFB8C00,0xFFFDD835,0xFF43A047,0xFF1E88E5,0xFF8E24AA,0xFFD81B60).map {it.toInt()}

/** Anchored colour popover: Fill/Stroke target, palette swatches, alpha slider, Custom… (opens the Colour page). Live. */
@Composable private fun TextColourPopoverContent(target: TextControl,fill: Int,stroke: Int,selected: Boolean,palette: PaletteRepository?,
    onTarget: (TextControl)->Unit,onSwatch: (TextControl,Int)->Unit,onAlphaBegin: (TextControl)->Unit,onAlphaPreview: (TextControl,Int)->Unit,
    onAlphaFinish: ()->Unit,onAlphaCancel: ()->Unit,onInherit: (TextControl)->Unit,onCustom: (TextControl)->Unit) {
    val library=palette?.state?.collectAsState()?.value
    val swatches=(library?.activePalette ?: library?.palettes?.firstOrNull())?.swatches?.map {it.toArgb()}?.takeIf {it.isNotEmpty()}?.take(12) ?: TEXT_DEFAULT_SWATCHES
    val current=if(target==TextControl.FILL) fill else stroke
    Row(Modifier.height(44.dp),verticalAlignment=Alignment.CenterVertically) {
        InspectorIconButton(TextInspectorIcons.icon("FILL"),"Edit fill colour",selected=target==TextControl.FILL) {onTarget(TextControl.FILL)}
        InspectorIconButton(TextInspectorIcons.icon("STROKE"),"Edit stroke colour",selected=target==TextControl.STROKE) {onTarget(TextControl.STROKE)}
        Spacer(Modifier.weight(1f))
        if(selected) InspectorIconButton(TextInspectorIcons.icon("INHERIT"),"Inherit ${if(target==TextControl.FILL) "fill" else "stroke"} from the layer") {onInherit(target)}
        TextButton(onClick={onCustom(target)}) {Text("Custom…",color=TextPrimary)}
    }
    val opaque=0xFF000000.toInt()
    swatches.chunked(6).forEach {row->Row(Modifier.padding(horizontal=4.dp,vertical=2.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        row.forEach {argb->Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(Color(argb))
            .border(1.dp,if((argb or opaque)==(current or opaque)) TextPrimary else Border,RoundedCornerShape(8.dp))
            // A swatch sets the colour and keeps the current alpha (a fully transparent colour becomes opaque).
            .clickable(role=Role.Button) {onSwatch(target,withAlpha(argb,(current ushr 24).takeIf {it>0} ?: 255))}
            .semantics {contentDescription="Colour #${"%06X".format(argb and 0xFFFFFF)}"})}
    }}
    val alpha=(current ushr 24)*100f/255f
    SliderPopoverContent(PropertyLimits.OPACITY,alpha,false,"${if(target==TextControl.FILL) "Fill" else "Stroke"} alpha",
        onReset={onSwatch(target,withAlpha(current,255))},onBegin={onAlphaBegin(target)},
        onPreview={v->onAlphaPreview(target,withAlpha(current,kotlin.math.round(v/100f*255f).toInt()))},
        onFinish=onAlphaFinish,onCancel=onAlphaCancel,onTyped={v->onSwatch(target,withAlpha(current,kotlin.math.round(v/100f*255f).toInt()))})
}
internal fun withAlpha(argb: Int,alpha: Int)=(argb and 0x00FFFFFF) or (alpha.coerceIn(0,255) shl 24)

/** Colour page: the global colour picker in the inspector frame. No Done: edits apply live; `←` leaves and commits. */
@Composable private fun TextColourPage(layer: CompositionLayer,time: Long,scope: TextEditScope,c: TextControl,
    preview: (TextProperties)->Unit,begin: ()->Unit,touched: ()->Unit,palette: PaletteRepository?,eyedropper: (()->Unit)?,
    sample: (((Int?)->Unit)->Unit)?,cancelSample: ()->Unit,modifier: Modifier) {
    val captured=remember {layer};val capturedTime=remember {time}
    val doc=remember {captured.text.source.evaluateDocument(time-layer.resolvedKeyframeAnchorUs)}
    val s=doc.runAt(scope.range?.start ?: 0)?.style
    val initial=remember {if(c==TextControl.FILL) s?.fill?.takeIf {scope.selected} ?: layer.text.fill.evaluate(time-layer.resolvedKeyframeAnchorUs) else s?.stroke?.takeIf {scope.selected} ?: layer.text.strokeColour.evaluate(time-layer.resolvedKeyframeAnchorUs)}
    var savedColour by rememberSaveable {mutableIntStateOf(initial)}
    var changed by rememberSaveable {mutableStateOf(false)}
    var sampling by remember {mutableStateOf(false)}
    var beforeGesture by remember {mutableIntStateOf(savedColour)}
    fun update(v: FluxxColor) {changed=true;touched();savedColour=v.toArgb();preview(textColourPayload(captured,capturedTime,scope,c,savedColour))}
    LaunchedEffect(Unit) {begin();if(changed) update(FluxxColor.fromArgb(savedColour))}
    DisposableEffect(Unit) {onDispose {if(sampling) cancelSample()}}
    FluxxColorPicker(FluxxColor.fromArgb(savedColour),::update,onEyedropperRequest=if(sample!=null) ({sampling=true;sample {argb->if(argb!=null) update(FluxxColor.fromArgb(argb));sampling=false}}) else eyedropper,
        modifier=modifier.testTag("text-colour"),onColorPreview=::update,paletteRepository=palette,
        onGestureBegin={beforeGesture=savedColour},onGestureCancel={update(FluxxColor.fromArgb(beforeGesture))})
}

/**
 * Bottom-bar text field. Tap to edit: the editor lifts above the keyboard in an anchored popover (the window is
 * never resized). Tap outside or Back (after the keyboard hides) commits; hardware Escape cancels.
 */
@Composable private fun SourceField(document: TextSourceDocument,selection: TextSelectionRange?,scope: TextEditScope,editing: Boolean,enabled: Boolean,modifier: Modifier,
    open: ()->Unit,onSelection: (TextSelectionRange?)->Unit,onSourceSelection: (String,TextSelectionRange)->Unit,draft: (String,Int,Int)->Unit,begin: ()->Unit,done: ()->Unit,cancel: ()->Unit) {
    Box(modifier.padding(start=4.dp),contentAlignment=Alignment.CenterStart) {
        Row(Modifier.fillMaxWidth().height(TextInspectorBudget.TILE.dp).clip(RoundedCornerShape(10.dp)).background(SurfaceHigh)
            .border(1.dp,if(editing) TextPrimary else Color.Transparent,RoundedCornerShape(10.dp))
            .clickable(enabled=enabled,role=Role.Button,onClickLabel="Edit text") {open()}
            .semantics(mergeDescendants=true) {contentDescription="Text: ${document.text.take(80)}"}
            .padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Icon(TextInspectorIcons.control(TextControl.SOURCE),null,Modifier.size(16.dp),tint=TextSecondary)
            Text(document.text.replace('\n',' ').ifEmpty {"Text"},Modifier.weight(1f).padding(start=6.dp),color=TextPrimary,
                style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
            if(scope.selected) {
                val count=scope.count(document)
                Row(Modifier.clip(RoundedCornerShape(8.dp)).background(Surface).clickable(role=Role.Button,onClickLabel="Clear selection") {onSelection(null)}
                    .semantics(mergeDescendants=true) {contentDescription="Selection: $count characters. Clear selection";liveRegion=LiveRegionMode.Polite}
                    .testTag("text-scope-chip").padding(horizontal=6.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("$count",color=TextPrimary,style=MaterialTheme.typography.labelSmall)
                    Icon(TextInspectorIcons.icon("CANCEL"),null,Modifier.padding(start=2.dp).size(12.dp),tint=TextSecondary)
                }
            }
        }
        AnchoredPopover(editing,done,"text-source-editor") {
            var field by remember {mutableStateOf(TextFieldValue(document.text,TextRange(selection?.start ?: 0,selection?.end ?: 0)))}
            val requester=remember {FocusRequester()}
            LaunchedEffect(Unit) {begin();requester.requestFocus()}
            LaunchedEffect(selection) {val r=selection ?: TextSelectionRange(0,0);field=field.copy(selection=TextRange(r.start.coerceAtMost(field.text.length),r.end.coerceAtMost(field.text.length)))}
            BasicTextField(field,{next->val old=field;val edges=TextSourceBoundaries.of(next.text)
                val start=TextSourceBoundaries.floor(edges,next.selection.min);val end=TextSourceBoundaries.ceiling(edges,next.selection.max)
                field=next.copy(selection=TextRange(start,end));onSourceSelection(next.text,TextSelectionRange(start,end));if(old.text!=next.text) {
                    val delta=next.text.length-old.text.length;val s=if(old.selection.collapsed && delta<0) next.selection.min else old.selection.min
                    val e=if(old.selection.collapsed && delta<0) s-delta else old.selection.max;draft(next.text,s,e)
                }},Modifier.width(300.dp).heightIn(min=40.dp,max=120.dp).focusRequester(requester).padding(8.dp)
                .onPreviewKeyEvent {if(it.key==Key.Escape && it.type==KeyEventType.KeyUp) {cancel();true} else false}
                .semantics {contentDescription="Source Text"},
                textStyle=MaterialTheme.typography.bodyMedium.copy(color=TextPrimary),cursorBrush=SolidColor(TextPrimary))
        }
    }
}

/** Labels for the large-font labelled fallback; full names remain in accessibility and long-press labels. */
internal fun shortLabel(c: TextControl)=when(c) {
    TextControl.BOLD->"Bold";TextControl.ITALIC->"Italic";TextControl.UNDERLINE->"Under";TextControl.STRIKE->"Strike"
    TextControl.SUPER->"Super";TextControl.SUB->"Sub";TextControl.ALL_CAPS->"Caps";TextControl.LOWERCASE->"Lower";TextControl.SMALL_CAPS->"Small"
    TextControl.LIGATURES->"Liga";TextControl.ALIGN_LEFT->"Left";TextControl.ALIGN_CENTRE->"Centre";TextControl.ALIGN_RIGHT->"Right"
    TextControl.JUSTIFY->"Justify";TextControl.PAINT->"Colour";TextControl.STROKE_WIDTH->"Stroke";TextControl.ORDER->"Fill over";TextControl.TRACKING->"Track"
    TextControl.LEADING->"Lead";TextControl.H_SCALE->"H scale";TextControl.V_SCALE->"V scale";TextControl.SCALE_LINK->"Link";TextControl.BASELINE->"Base"
    TextControl.KERNING->"Kern";TextControl.INDENT_LEFT->"L ind";TextControl.INDENT_RIGHT->"R ind";TextControl.INDENT_FIRST->"1st";TextControl.BEFORE->"Before"
    TextControl.AFTER->"After";TextControl.BOX_MODE->"Box";TextControl.BOX->"Width";TextControl.DIRECTION->"Dir";else->c.label
}
private fun alignmentLabel(a: ParagraphAlignment)=when(a) {
    ParagraphAlignment.LEFT->"Align left";ParagraphAlignment.CENTRE->"Align centre";ParagraphAlignment.RIGHT->"Align right"
    ParagraphAlignment.JUSTIFY_LAST_LEFT->"Justify, last line left";ParagraphAlignment.JUSTIFY_LAST_CENTRE->"Justify, last line centre"
    ParagraphAlignment.JUSTIFY_LAST_RIGHT->"Justify, last line right";ParagraphAlignment.JUSTIFY_ALL->"Justify all lines"
}
