package com.fluxx.android.ui.inspector

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import com.fluxx.android.ui.common.colorpicker.*
import com.fluxx.android.ui.theme.*
import kotlinx.coroutines.delay

/**
 * Edit Text as an ordinary inspector page at the shared 268/328dp height: one header row
 * (← | Character | Paragraph | Animate, plus ⚠ only on error), the shared compact left rail whose ⋮ is the page's
 * single overflow menu, and a fixed face: top row, two 3-column blocks, bottom bar (font, size, text). Values change
 * through small anchored popovers; colour is edited entirely inside its popover. The page never scrolls; the
 * keyboard overlays it and only the focused popover lifts above it.
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
    onSourceSelection: (String,TextSelectionRange)->Unit={_,r->onSelection(r)},
    compositionHeight: Int=0,onAnimatorFocus: (AnimatorTrackAddress?)->Unit={}) {
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
    var resetAnimation by remember {mutableStateOf(false)}
    var explode by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf<String?>(null)}
    var feedbackOpen by remember {mutableStateOf(false)}
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
    val currentLayer by rememberUpdatedState(layer)
    val currentScope by rememberUpdatedState(editScope)
    // The action bar's Prev/Next keyframe buttons follow this owner (last touched control or the open popover).
    LaunchedEffect(owner) {onFocus(owner)}
    // Animate tab (TEXT F3): the focused animator field owns the rail diamond, timeline focus and Prev/Next.
    val animateState=rememberAnimatePageState(layer.id)
    val animOwner=if(tab==TextTab.ANIMATE) AnimateSelection.focus(layer.text,animateState.focus) else null
    // TEXT F3.1: Position / Rotation / Scale control style, an app preference switched from the rail menu.
    val controlsPreference=AnimateControlsPreference.get(androidx.compose.ui.platform.LocalContext.current)
    val animateControls by controlsPreference.value.collectAsState()
    val animAnimated=animOwner?.let {TextAnimatorEdits.animated(layer.text,it)} ?: false
    val animOnKey=animOwner?.let {ownerLocal in TextAnimatorEdits.keyTimes(layer,it)} ?: false
    LaunchedEffect(animOwner) {onAnimatorFocus(animOwner)}
    DisposableEffect(layer.id) {onDispose {onAnimatorFocus(null)}}
    fun animatorAction(edit: AnimatorEdit) {onAction(TextAnimatorEdits.action(layer.id,listOf(edit)))}

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
    // Colour: every popover interaction is one transaction on the layer/time/scope captured when it starts.
    val sinkBegin by rememberUpdatedState(onValueBegin);val sinkPreview by rememberUpdatedState(onPayloadPreview)
    val sinkFinish by rememberUpdatedState(onFinish);val sinkCancel by rememberUpdatedState(onCancel)
    val colourSession=remember {TextColourSession(object: TextDraftSink {
        override fun begin()=sinkBegin()
        override fun preview(payload: TextProperties)=sinkPreview(payload)
        override fun finish()=sinkFinish()
        override fun cancel()=sinkCancel()
    })}
    fun colourTarget()=if(control==TextControl.STROKE) TextControl.STROKE else TextControl.FILL
    fun colourSet(t: TextControl,argb: Int) {focus(t);colourSession.target(t);colourSession.set(currentLayer,frameRate.snap(time),currentScope,argb)}
    fun closePopover() {numericCancel();colourSession.cancel();if(axisSnapshot!=null) {onCancel();axisSnapshot=null};popoverName=null}
    fun sourceDone() {manager.clearFocus();if(mode==TextInspectorMode.Source) {onFinish();mode=TextInspectorMode.Browse}}
    fun sourceCancel() {manager.clearFocus();if(mode==TextInspectorMode.Source) {onCancel();mode=TextInspectorMode.Browse}}
    fun closePage() {
        if(mode==TextInspectorMode.Fonts && axisSnapshot!=null) {onCancel();axisSnapshot=null}
        mode=TextInspectorMode.Browse
    }
    /**
     * Backgrounding cancels every live draft. It does NOT leave the Fonts page: the system font picker stops this
     * activity, and leaving the page then disposed the picker launcher, silently dropping the chosen file (TEXT FIX 9).
     */
    fun cancelAll() {
        numericCancel();colourSession.cancel();popoverName=null
        if(mode==TextInspectorMode.Source || axisSnapshot!=null) onCancel()
        axisSnapshot=null;manager.clearFocus();mode=TextInspectorLifecycle.afterStop(mode)
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
            TextControl.SOURCE->message="Source Text is preserved. Use Reset style for formatting."
            TextControl.SIZE,TextControl.TRACKING,TextControl.LEADING,TextControl.BASELINE,TextControl.STROKE_WIDTH,TextControl.H_SCALE,TextControl.V_SCALE,
            TextControl.INDENT_LEFT,TextControl.INDENT_RIGHT,TextControl.INDENT_FIRST,TextControl.BEFORE,TextControl.AFTER->
                numericSet(control,TextNumericDraft(layer,time,frameRate,editScope,control).default)
            TextControl.FILL,TextControl.STROKE,TextControl.PAINT->{val c=colourTarget()
                if(editScope.selected) format {if(c==TextControl.FILL) it.copy(fill=null) else it.copy(stroke=null)}
                else onAction(TextEdits.colour(layer,time,requireNotNull(editScope.owner(c)),if(c==TextControl.FILL) -1 else 0xff000000.toInt()))}
            TextControl.RELATIVE_SIZE->format {it.copy(sizeFactor=1f)}
            TextControl.FONT->format {it.copy(font=null)}
            TextControl.MANUAL_KERNING->format {it.copy(manualKerning=FrozenList.empty())}
            TextControl.BOLD->format {it.copy(fauxBold=false)};TextControl.ITALIC->format {it.copy(fauxItalic=false)}
            TextControl.CASE,TextControl.ALL_CAPS,TextControl.LOWERCASE->format {it.copy(caps=TextCaps.NONE)}
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

    val fontScale=density.fontScale
    val large=TextInspectorBudget.large(fontScale)
    val requestedStroke=layer.text.strokeWidth.evaluate(local)+if(editScope.selected) style.strokeDelta else 0f
    val strokeNote=TextFeedback.strokeClampFull(requestedStroke,strokeClamp)
    val feedback=error ?: message
    val fillArgb=style.fill?.takeIf {editScope.selected} ?: layer.text.fill.evaluate(local)
    val strokeArgb=style.stroke?.takeIf {editScope.selected} ?: layer.text.strokeColour.evaluate(local)
    val strokeOver=layer.text.paintOrder(local)!=0
    // Keyframe badges: derived from the same key model as the rail diamond, per property, at the snapped playhead.
    fun badge(c: TextControl)=KeyBadges.text(layer,c,ownerLocal)

    // ---- Tiles ------------------------------------------------------------------------------------------------
    @Composable fun NumericTile(c: TextControl,modifier: Modifier,inset: PaddingValues,text: String?=null) {
        val d=TextNumericDraft(layer,time,frameRate,editScope,c)
        val limit=requireNotNull(d.limit)
        val enabled=editScope.available(c) && !finishing
        val title=editScope.title(c)
        InspectorTile(InspectorIcons.control(c),"$title, ${editScope.unit(c)}",modifier,active=popover==c,enabled=enabled,
            caption=if(d.mixed) "—" else tileCaption(limit,d.initial),text=text,testTag="text-tile-${c.name}",fill=inset,badge=badge(c),popover={
                AnchoredPopover(popover==c,::closePopover,"text-popover-${c.name}",modal=false) {
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
    @Composable fun ToggleTile(c: TextControl,modifier: Modifier,inset: PaddingValues,on: Boolean,text: String?,enabled: Boolean=true,
        vector: androidx.compose.ui.graphics.vector.ImageVector=InspectorIcons.control(c),caption: String?=null,state: String?=null,click: ()->Unit) {
        InspectorTile(vector,c.label,modifier,active=on,enabled=enabled && !finishing,caption=caption,text=text,state=state,testTag="text-tile-${c.name}",fill=inset,badge=badge(c)) {click()}
    }
    @Composable fun PaintTile(modifier: Modifier,inset: PaddingValues,label: String?) {
        val description="Fill #${"%08X".format(fillArgb)}, stroke #${"%08X".format(strokeArgb)}, $scopeLabel"
        InspectorTile(null,"Fill and stroke colour",modifier,active=popover==TextControl.PAINT,enabled=!finishing,text=label ?: "Fill · Stroke",state=description,
            testTag="text-tile-PAINT",fill=inset,iconContent={DualColourSwatch(fillArgb,strokeArgb)},badge=badge(TextControl.PAINT),popover={
                AnchoredPopover(popover==TextControl.PAINT,::closePopover,"text-colour-popover") {
                    TextColourPopoverContent(target=colourTarget(),fill=fillArgb,stroke=strokeArgb,selected=editScope.selected,palette=palette,
                        onTarget={focus(it);colourSession.target(it)},
                        onBegin={t->focus(t);colourSession.target(t);numericCancel();colourSession.begin(currentLayer,frameRate.snap(time),currentScope)},
                        onPreview={argb->colourSession.preview(argb)},onFinish={colourSession.finish()},onCancel={colourSession.cancel()},
                        onSet={t,argb->colourSet(t,argb)},
                        onInherit={t->closePopover();format {if(t==TextControl.FILL) it.copy(fill=null) else it.copy(stroke=null)}},
                        onReset={t->colourSet(t,if(t==TextControl.FILL) -1 else 0xFF000000.toInt())},
                        onEyedropper=if(sampleColour!=null) ({t: TextControl->closePopover();sampleColour {argb->if(argb!=null) colourSet(t,argb)}})
                            else onEyedropper?.let {global->{_: TextControl->closePopover();global()}})
                }
            }) {focus(colourTarget());openPopover(TextControl.PAINT)}
    }
    @Composable fun OrderSwitch(modifier: Modifier,height: Dp,labelled: Boolean) {
        val enabled=editScope.available(TextControl.ORDER) && !finishing
        Row(modifier.toggleable(!strokeOver,enabled=enabled,role=Role.Switch) {focus(TextControl.ORDER);layerToggle(TextControl.ORDER,AnimPropertyType.STROKE_OVER_FILL,strokeOver)}
            .semantics(mergeDescendants=true) {contentDescription="Fill over stroke";stateDescription=if(!editScope.available(TextControl.ORDER)) "Clear selection to edit" else if(strokeOver) "Off: stroke over fill" else "On"}
            .testTag("text-order-switch"),verticalAlignment=Alignment.CenterVertically) {
            CompactSwitch(!strokeOver,enabled,height)
            if(labelled) Text("Fill over stroke",Modifier.padding(start=6.dp),color=if(enabled) TextSecondary else TextDisabled,
                fontSize=if(height<20.dp) 9.sp else 11.sp,lineHeight=if(height<20.dp) 10.sp else 13.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
    @Composable fun ExplodePill(modifier: Modifier) {
        Row(modifier.height(TextInspectorBudget.PILL.dp).clip(CircleShape).background(SurfaceHigh)
            .clickable(enabled=!finishing,role=Role.Button,onClickLabel="Explode Text") {onFinish();explode=true}
            .semantics(mergeDescendants=true) {contentDescription="Explode Text into one layer per character, word or line"}.testTag("text-explode-pill")
            .padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
            Icon(InspectorIcons.icon("EXPLODE"),null,Modifier.size(InspectorIcons.INLINE),tint=if(finishing) TextDisabled else TextPrimary)
            Text("Explode Text",Modifier.padding(start=6.dp),color=if(finishing) TextDisabled else TextPrimary,style=MaterialTheme.typography.labelMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
    @Composable fun Tile(c: TextControl,modifier: Modifier,inset: PaddingValues,labelled: Boolean) {
        val label=if(labelled) shortLabel(c) else null
        when(c) {
            TextControl.PAINT->PaintTile(modifier,inset,label)
            TextControl.EXPLODE->InspectorTile(InspectorIcons.icon("EXPLODE"),"Explode Text",modifier,enabled=!finishing,text=label,testTag="text-tile-EXPLODE",fill=inset) {onFinish();explode=true}
            TextControl.ORDER->ToggleTile(c,modifier,inset,!strokeOver,label,editScope.available(c),caption=if(strokeOver) "S" else "F",state=if(strokeOver) "Stroke over fill" else "Fill over stroke") {
                layerToggle(c,AnimPropertyType.STROKE_OVER_FILL,strokeOver)}
            TextControl.SCALE_LINK->ToggleTile(c,modifier,inset,linkScale,label,vector=InspectorIcons.icon(if(linkScale) "SCALE_LINK" else "LINK_OFF"),
                state=if(linkScale) "Linked: H and V scale together" else "Unlinked") {focus(c);linkScale=!linkScale}
            TextControl.BOLD->ToggleTile(c,modifier,inset,style.fauxBold,label) {focus(c);format {it.copy(fauxBold=!style.fauxBold)}}
            TextControl.ITALIC->ToggleTile(c,modifier,inset,style.fauxItalic,label) {focus(c);format {it.copy(fauxItalic=!style.fauxItalic)}}
            // No underline/strikethrough in the document model yet; shown disabled (schema change required).
            TextControl.UNDERLINE,TextControl.STRIKE->ToggleTile(c,modifier,inset,false,label,enabled=false,state="Unavailable: needs a document format change") {}
            TextControl.SUPER,TextControl.SUB->{val target=if(c==TextControl.SUPER) TextScriptPosition.SUPER else TextScriptPosition.SUB
                ToggleTile(c,modifier,inset,style.script==target,label) {focus(c);format {it.copy(script=if(style.script==target) TextScriptPosition.NORMAL else target)}}}
            TextControl.ALL_CAPS,TextControl.LOWERCASE->{val target=if(c==TextControl.ALL_CAPS) TextCaps.ALL else TextCaps.LOWER
                ToggleTile(c,modifier,inset,style.caps==target,label) {focus(c);format {it.copy(caps=if(style.caps==target) TextCaps.NONE else target)}}}
            TextControl.LIGATURES->{val on=layer.text.ligatures.evaluate(local)!=0f
                ToggleTile(c,modifier,inset,on,label,editScope.available(c),state=if(layer.text.effectiveLigatures(local)) "On" else if(on) "On, split while tracking is not zero" else "Off") {
                    layerToggle(c,AnimPropertyType.LIGATURES,on)}}
            TextControl.KERNING->ToggleTile(c,modifier,inset,style.kerning==TextKerning.METRICS,label,caption=if(style.kerning==TextKerning.METRICS) "Met" else "Off",
                state=if(style.kerning==TextKerning.METRICS) "Metrics" else "Off") {focus(c);format {it.copy(kerning=if(style.kerning==TextKerning.METRICS) TextKerning.OFF else TextKerning.METRICS)}}
            TextControl.ALIGN_LEFT,TextControl.ALIGN_CENTRE,TextControl.ALIGN_RIGHT->{
                val value=when(c) {TextControl.ALIGN_LEFT->ParagraphAlignment.LEFT;TextControl.ALIGN_CENTRE->ParagraphAlignment.CENTRE;else->ParagraphAlignment.RIGHT}
                ToggleTile(c,modifier,inset,effectiveAlignment==value,label,vector=InspectorIcons.alignment(value),state=if(paragraph.alignment==null && effectiveAlignment==value) "On, inherited" else null) {align(c,value)}}
            TextControl.JUSTIFY->InspectorTile(InspectorIcons.alignment(effectiveAlignment.takeIf {it.ordinal>=3} ?: ParagraphAlignment.JUSTIFY_LAST_LEFT),
                "Justify choices"+(effectiveAlignment.takeIf {it.ordinal>=3}?.let {", ${alignmentLabel(it)}"} ?: ""),modifier,
                active=popover==TextControl.JUSTIFY || popover==TextControl.BASE_ALIGNMENT || effectiveAlignment.ordinal>=3,enabled=!finishing,text=label,testTag="text-tile-JUSTIFY",fill=inset,popover={
                    AnchoredPopover(popover==TextControl.JUSTIFY || popover==TextControl.BASE_ALIGNMENT,::closePopover,"text-choice-popover") {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            fun choose(action: ()->Unit) {action();popoverName=null}
                            if(popover==TextControl.BASE_ALIGNMENT) {
                                InspectorIconButton(InspectorIcons.icon("BACK"),"Back to justify choices") {popoverName=TextControl.JUSTIFY.name}
                                TextAlignment.entries.forEach {value->val p=ParagraphAlignment.valueOf(value.name)
                                    InspectorIconButton(InspectorIcons.alignment(p),"Layer base: ${alignmentLabel(p)}",selected=layer.text.alignment==value) {
                                        choose {onBeginDraft(false) {fresh->onPayloadPreview(fresh.text.copy(alignment=value));onFinish()}}}}
                            } else {
                                ParagraphAlignment.entries.drop(3).forEach {value->InspectorIconButton(InspectorIcons.alignment(value),alignmentLabel(value),selected=paragraph.alignment==value) {choose {align(TextControl.JUSTIFY,value)}}}
                                InspectorIconButton(InspectorIcons.icon("INHERIT"),"Inherit layer alignment",selected=paragraph.alignment==null) {choose {align(TextControl.ALIGNMENT,null)}}
                                InspectorIconButton(InspectorIcons.icon("NEXT"),if(editScope.selected) "Layer base alignment; clear selection to edit" else "Layer base alignment choices",enabled=!editScope.selected) {
                                    popoverName=TextControl.BASE_ALIGNMENT.name}
                            }
                        }
                    }
                }) {openPopover(TextControl.JUSTIFY)}
            TextControl.BOX_MODE->{val point=document.boxWidth==0f
                ToggleTile(c,modifier,inset,!point,label,editScope.available(c),vector=InspectorIcons.icon(if(point) "POINT" else "AREA"),state=if(point) "Point text" else "Box text") {
                    focus(c);if(!editScope.available(c)) message="Box width is layer-only"
                    else if(point) runCatching {TextEdits.fillCompositionWidth(layer,time,compositionWidth)}.onSuccess(onBox).onFailure {message=it.message} else onBox(0f)}}
            TextControl.DIRECTION->ToggleTile(c,modifier,inset,paragraph.direction==ParagraphDirection.RTL,label,vector=InspectorIcons.icon(paragraph.direction.name),
                state=if(paragraph.direction==ParagraphDirection.RTL) "Right to left, stored only; RTL rendering unsupported" else "Left to right") {
                val next=if(paragraph.direction==ParagraphDirection.LTR) ParagraphDirection.RTL else ParagraphDirection.LTR
                direction(next);if(next==ParagraphDirection.RTL) message="RTL direction stored only; RTL rendering unsupported"}
            else->if(PropertyLimits.forControl(c,editScope.selected)!=null) NumericTile(c,modifier,inset,label) else Spacer(modifier)
        }
    }
    /** Bottom bar: font chip (2 cells), size (1 cell), inline text field (3 cells, the right block's edges). */
    @Composable fun BottomBar(cell: Dp,height: Dp,inset: PaddingValues) {
        Row(Modifier.fillMaxWidth().height(height).hairlineTop().testTag("text-bottom-bar")) {
            val fontName=(if(missingFonts) "Missing · " else "")+fontLabel(style.font)
            InspectorTile(InspectorIcons.icon(if(missingFonts) "MISSING" else "FONT"),"Font: $fontName; open Fonts",Modifier.width(cell*2).fillMaxHeight(),
                active=mode==TextInspectorMode.Fonts,enabled=!finishing,text=fontName,testTag="text-font-chip",fill=inset) {closePopover();focus(TextControl.FONT);mode=TextInspectorMode.Fonts}
            NumericTile(TextControl.SIZE,Modifier.width(cell).fillMaxHeight(),inset)
            Spacer(Modifier.width(TextInspectorBudget.BLOCK_SPACER.dp))
            SourceField(document,selection,editScope,mode==TextInspectorMode.Source,!finishing,Modifier.weight(1f).fillMaxHeight(),inset,badge(TextControl.SOURCE),
                {focus(TextControl.SOURCE);closePopover();onBeginDraft(true) {mode=TextInspectorMode.Source}},onSelection,onSourceSelection,onSourceDraft,onSourceBegin,::sourceDone,::sourceCancel)
        }
    }
    /** One 3-column block. Three rows take the face's touch-sized row heights; fewer rows are centred. */
    @Composable fun Block(rows: List<List<TextControl>>,m: TextFaceMetrics,testTag: String) {
        val cell=m.cell.dp;val half=(m.gap/2).dp;val group=(m.groupGap/2).dp
        Column(Modifier.width(cell*3).fillMaxHeight().testTag(testTag),verticalArrangement=if(rows.size==3) Arrangement.Top else Arrangement.Center) {
            rows.forEachIndexed {i,row->
                val full=rows.size==3
                val height=if(full) m.middleRows[i].dp else m.pitchV.dp
                val inset=if(!full) PaddingValues(half) else PaddingValues(start=half,end=half,top=if(i==0) group else half,bottom=if(i==2) group else half)
                Row(Modifier.fillMaxWidth().height(height).testTag("$testTag-row-$i")) {
                    // A short last row (2 of 3) is centred under the full rows.
                    Spacer(Modifier.width(cell*TextInspectorLayout.rowOffset(row.size)))
                    row.forEach {c->Tile(c,Modifier.width(cell).fillMaxHeight(),inset,false)}
                }
            }
        }
    }
    @Composable fun Face(face: TextFace,m: TextFaceMetrics) {
        val cell=m.cell.dp;val half=(m.gap/2).dp;val spacer=TextInspectorBudget.BLOCK_SPACER.dp
        Column(Modifier.fillMaxSize().testTag("text-face-${tab.name}")) {
            // ---- Top row ------------------------------------------------------------------------------------
            Box(Modifier.fillMaxWidth().height(m.topBand.dp).padding(bottom=(m.groupGap/2).dp).testTag("text-top-row")) {
                if(tab==TextTab.CHARACTER) Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(cell*3).fillMaxHeight().padding(horizontal=half),contentAlignment=Alignment.CenterStart) {ExplodePill(Modifier.fillMaxWidth())}
                    Spacer(Modifier.width(spacer))
                    Box(Modifier.width(cell*3).fillMaxHeight().padding(horizontal=half),contentAlignment=Alignment.Center) {
                        if(m.switchBeside) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            PaintTile(Modifier.weight(1f).height(m.paintTile.dp),PaddingValues(0.dp),null)
                            OrderSwitch(Modifier.padding(start=6.dp),m.switchHeight.dp,false)
                        } else Column(Modifier.fillMaxWidth()) {
                            PaintTile(Modifier.fillMaxWidth().height(m.paintTile.dp),PaddingValues(0.dp),null)
                            Spacer(Modifier.height(m.switchGap.dp))
                            OrderSwitch(Modifier.fillMaxWidth().height(m.switchHeight.dp),m.switchHeight.dp,true)
                        }
                    }
                } else Row(Modifier.fillMaxSize(),verticalAlignment=Alignment.CenterVertically) {
                    face.top.forEach {c->Tile(c,Modifier.weight(1f).height(m.tile.dp+m.gap.dp),PaddingValues(half),false)}
                }
            }
            // ---- Middle: two 3-column blocks ----------------------------------------------------------------
            Row(Modifier.fillMaxWidth().height(m.middleBand.dp).hairlineTop()) {
                Block(face.left,m,"text-left-block")
                Box(Modifier.width(spacer).fillMaxHeight().padding(vertical=(m.groupGap/2+m.gap).dp),contentAlignment=Alignment.Center) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Border))
                }
                Block(face.right,m,"text-right-block")
            }
            // ---- Bottom bar ---------------------------------------------------------------------------------
            BottomBar(cell,m.bottomBand.dp,PaddingValues(start=half,end=half,top=(m.groupGap/2).dp))
        }
    }

    Column(Modifier.fillMaxSize().testTag("text-inspector").onPreviewKeyEvent {
        if(it.key==Key.Escape && it.type==KeyEventType.KeyUp && (mode!=TextInspectorMode.Browse || popover!=null || resetAnimation)) {back();true} else false}) {
        // ---- Header: ← | Character | Paragraph | Animate | (⚠ only on error) ---------------------------------------
        val subPage=mode==TextInspectorMode.Fonts
        Row(Modifier.fillMaxWidth().height(TextInspectorBudget.header(fontScale).dp).testTag("text-inspector-header"),verticalAlignment=Alignment.CenterVertically) {
            InspectorIconButton(InspectorIcons.icon("BACK"),if(subPage) "Back to ${tab.name.lowercase()}" else "Back to layer tools") {back()}
            if(subPage) Text("Fonts · $scopeLabel",Modifier.weight(1f).padding(horizontal=8.dp).semantics {heading()},color=TextPrimary,
                style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
            else TextTab.entries.forEach {t->
                val selectedTab=t==tab
                Box(Modifier.weight(1f).fillMaxHeight().clickable(enabled=!finishing,role=Role.Tab) {
                    closePopover();tabIndex=t.ordinal;page=0
                }.semantics {selected=selectedTab}.testTag("text-tab-${t.name}"),contentAlignment=Alignment.Center) {
                    Text(t.name.lowercase().replaceFirstChar {it.uppercase()},color=if(selectedTab) TextPrimary else TextSecondary,
                        style=MaterialTheme.typography.labelMedium,maxLines=1)
                    if(selectedTab) Box(Modifier.align(Alignment.BottomCenter).padding(bottom=4.dp).size(24.dp,2.dp).background(TextPrimary,RoundedCornerShape(1.dp)))
                }
            }
            if(feedback!=null) Box {
                InspectorIconButton(InspectorIcons.icon("WARNING"),"Message: $feedback",Modifier.semantics {liveRegion=LiveRegionMode.Polite}) {feedbackOpen=true}
                AnchoredPopover(feedbackOpen,{feedbackOpen=false},"text-feedback") {
                    Text(feedback,Modifier.widthIn(max=280.dp).padding(8.dp),color=if(error!=null) MaterialTheme.colorScheme.error else TextPrimary,style=MaterialTheme.typography.labelMedium)
                }
            }
        }
        // ---- Body: shared rail + page --------------------------------------------------------------------------
        Row(Modifier.weight(1f).fillMaxWidth()) {
            val discrete=owner in TextKeyState.HOLD_OWNERS
            val propertyName=editScope.title(TextFocus.control(control,popover))
            val railItems=TextRailMenu.items(owner,animated,onKey,tab)
            if(tab==TextTab.ANIMATE) {
                // The focused animator field: same rail, same key model (AnimatorEdit.Key), Hold squares for discrete fields.
                val name=animOwner?.let {AnimateFields.spec(it).label}
                val hold=animOwner!=null && TextAnimatorEdits.isHold(animOwner)
                InspectorUtilityRail(resetLabel=if(name!=null) "Reset $name value" else "Reset value",
                    onReset={animOwner?.let {a->runCatching {TextAnimatorEdits.reset(layer.text,a,ownerLocal)}.onSuccess(::animatorAction).onFailure {message=it.message}}
                        ?: run {message="Choose an animator field to reset"}},
                    isAnimated=animAnimated,hasKeyframeAtPlayhead=animOnKey,
                    onToggleKeyframe=if(animOwner==null || finishing) null else ({animatorAction(TextAnimatorEdits.toggleKey(layer.text,animOwner,ownerLocal,animOnKey))}),
                    show3D=false,keyIcon=InspectorIcons.icon(AnimateRail.keyIcon(animOwner,animAnimated,animOnKey)),
                    keyLabel=AnimateRail.keyLabel(name,animAnimated,animOnKey),
                    keyState=AnimateRail.keyState(name,hold,animAnimated,animOnKey,CompositionTimecode.format(time,frameRate)),
                    extraItems=buildList {
                        if(animOwner?.property in AnimateControlsPreference.PROPERTIES) add(RailMenuItem(AnimateRail.controlsLabel(animateControls)) {
                            controlsPreference.set(if(animateControls==AnimateControls.SLIDERS) AnimateControls.PAD else AnimateControls.SLIDERS)})
                        if(animOwner!=null) {
                            add(RailMenuItem(if(animAnimated) "Freeze at playhead" else "Enable animation",enabled=!finishing) {
                                animatorAction(if(animAnimated) TextAnimatorEdits.freeze(animOwner,ownerLocal) else TextAnimatorEdits.enable(animOwner,ownerLocal))})
                            if(animAnimated) add(RailMenuItem("Reset animation…",enabled=!finishing) {resetAnimation=true})
                            if(hold) add(RailMenuItem("Hold (discrete)",enabled=false) {})
                            else if(animAnimated && animOnKey) EasingPreset.entries.forEach {easing->add(RailMenuItem(easing.name,enabled=!finishing) {
                                animatorAction(TextAnimatorEdits.easing(animOwner,ownerLocal,easing))})}
                        }
                    },
                    confirm=if(resetAnimation && animOwner!=null) "Remove all $name keys, keep the value at the playhead?" to {animatorAction(TextAnimatorEdits.freeze(animOwner,ownerLocal))} else null,
                    onConfirmDismiss={resetAnimation=false})
            } else InspectorUtilityRail(resetLabel=TextRailMenu.label(TextRailItem.RESET_VALUE,tab,scopeLabel,propertyName,animated),onReset=::resetValue,
                isAnimated=animated,hasKeyframeAtPlayhead=onKey,
                onToggleKeyframe=if(owner==null || finishing) null else ({
                    onAction(if(!animated) EditorAction.ToggleAnimated(layer.id,owner,true,time) else if(onKey) EditorAction.RemoveKeyframe(layer.id,owner,ownerLocal)
                        else if(owner==AnimPropertyType.SOURCE_TEXT) EditorAction.SetTextPayload(layer.id,TextEdits.document(layer,time,document))
                        else if(owner==AnimPropertyType.FILL_COLOUR || owner==AnimPropertyType.STROKE_COLOUR) TextEdits.colour(layer,time,owner,layer.text.colour(owner).evaluate(local))
                        else EditorAction.SetKeyframe(layer.id,owner,ownerLocal,layer.text.scalarValue(owner,local)))
                }),
                show3D=false,keyIcon=InspectorIcons.icon(TextKeyState.icon(owner,animated,onKey)),
                keyLabel=when {owner==null->"Keyframes unavailable for selection or Animate";!animated->"Enable $owner animation at playhead";onKey->"Remove $owner keyframe at playhead";else->"Add $owner keyframe at playhead"},
                keyState=TextKeyState.description(owner,animated,onKey,CompositionTimecode.format(time,frameRate)),
                extraItems=buildList {
                    if(owner!=null && TextRailItem.ANIMATION in railItems) add(RailMenuItem(TextRailMenu.label(TextRailItem.ANIMATION,tab,scopeLabel,propertyName,animated)) {
                        onAction(EditorAction.ToggleAnimated(layer.id,owner,!animated,time))})
                    if(TextRailItem.RESET_ANIMATION in railItems) add(RailMenuItem(TextRailMenu.label(TextRailItem.RESET_ANIMATION,tab,scopeLabel,propertyName,animated)) {resetAnimation=true})
                    if(owner!=null && TextRailItem.EASING in railItems) {
                        if(discrete) add(RailMenuItem("Hold (discrete)",enabled=false) {})
                        else EasingPreset.entries.forEach {easing->add(RailMenuItem(easing.name) {
                            onAction(if(owner==AnimPropertyType.FILL_COLOUR || owner==AnimPropertyType.STROKE_COLOUR) EditorAction.SetColourKeyframe(layer.id,ownerLocal,layer.text.colour(owner).evaluate(local),easing,owner)
                                else EditorAction.SetKeyframe(layer.id,owner,ownerLocal,layer.text.scalarValue(owner,local),easing))})}
                    }
                    // One undo entry: one format/paragraph transaction for the current scope.
                    if(TextRailItem.RESET_STYLE in railItems) add(RailMenuItem(TextRailMenu.label(TextRailItem.RESET_STYLE,tab,scopeLabel,propertyName,animated),enabled=!finishing) {
                        if(tab==TextTab.PARAGRAPH) onParagraph(a,b) {TextParagraphStyle()} else format {TextRunStyle()}})
                    if(TextRailItem.MANUAL_SPACING in railItems) add(RailMenuItem(TextRailMenu.label(TextRailItem.MANUAL_SPACING,tab,scopeLabel,propertyName,animated),enabled=!finishing) {
                        openPopover(TextControl.MANUAL_KERNING)})
                },
                confirm=if(resetAnimation) "Remove all ${owner?.name} keys, keep the value at the playhead?" to {if(owner!=null) onAction(EditorAction.ToggleAnimated(layer.id,owner,false,time))} else null,
                onConfirmDismiss={resetAnimation=false},
                menuAnchor={
                    // Manual spacing opens anchored on the ⋮ it was chosen from.
                    val d=TextNumericDraft(layer,time,frameRate,editScope,TextControl.MANUAL_KERNING)
                    AnchoredPopover(popover==TextControl.MANUAL_KERNING,::closePopover,"text-popover-MANUAL_KERNING",modal=false) {
                        SliderPopoverContent(requireNotNull(d.limit),d.initial,d.mixed,editScope.title(TextControl.MANUAL_KERNING),
                            onReset={closePopover();format {it.copy(manualKerning=FrozenList.empty())}},onBegin={numericBegin(TextControl.MANUAL_KERNING)},
                            onPreview=::numericPreview,onFinish=::numericFinish,onCancel=::numericCancel,onTyped={numericSet(TextControl.MANUAL_KERNING,it)},enabled=!finishing)
                    }
                })
            Spacer(Modifier.width(TextInspectorBudget.RAIL_GAP.dp))
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().testTag("text-inspector-body")) {
                val hitSize=LocalMinimumInteractiveComponentSize.current.let {if(it==Dp.Unspecified) 48.dp else it}
                val bodyWidth=maxWidth.value;val bodyHeight=maxHeight.value
                val metrics=TextInspectorBudget.forBody(bodyHeight,bodyWidth,hitSize<48.dp)
                val face=TextInspectorLayout.face(tab)
                when {
                    mode==TextInspectorMode.Fonts->TextFontsPage(style.font,layer.text.fontId,scopeLabel,
                        {ref->if(axisSnapshot!=null) {onCancel();axisSnapshot=null};focus(TextControl.FONT);format {it.copy(font=ref)}},
                        axisBegin={focus(TextControl.FONT);axisSnapshot=Triple(layer,frameRate.snap(time),editScope);onValueBegin()},
                        axisPreview={ref->axisSnapshot?.let {(base,capturedTime,capturedScope)->
                            val range=capturedScope.range;val doc=base.text.source.evaluateDocument(capturedTime-base.resolvedKeyframeAnchorUs)
                            onPayloadPreview(TextEdits.document(base,capturedTime,doc.format(range?.start ?: 0,range?.end ?: 0) {it.copy(font=ref)}))
                        }},axisFinish={if(axisSnapshot!=null) {onFinish();axisSnapshot=null}},axisCancel={if(axisSnapshot!=null) {onCancel();axisSnapshot=null}},busy=finishing,
                        baseFont=if(editScope.selected) null else {id->focus(TextControl.BASE_FONT);onBeginDraft(false) {fresh->onPayloadPreview(fresh.text.copy(fontId=id));onFinish()}},
                        hit=hitSize,modifier=Modifier.fillMaxSize())
                    face==null->TextAnimatePage(layer,time,frameRate,compositionWidth,compositionHeight,animateState,finishing,palette,animateControls,
                        onAction=onAction,onValueBegin=onValueBegin,onPayloadPreview=onPayloadPreview,onFinish=onFinish,onCancel=onCancel,
                        onMessage={message=it},metrics=AnimateMetrics(bodyHeight,bodyWidth,hitSize<48.dp),modifier=Modifier.fillMaxSize())
                    large->{
                        // Large font scales: labelled 3-column reflow of the same controls; whole rows page from a button.
                        val pitch=TextInspectorBudget.LABELLED_PITCH
                        val capacity=kotlin.math.floor((bodyHeight-pitch)/pitch+1e-3f).toInt().coerceAtLeast(1)
                        val rows=TextInspectorLayout.labelledRows(tab)
                        val pages=TextInspectorLayout.pages(rows,capacity)
                        val index=page.coerceIn(0,pages.lastIndex)
                        val half=(TextInspectorBudget.GRID_GAP/2).dp
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.fillMaxWidth().weight(1f)) {
                                Column(Modifier.weight(1f)) {
                                    pages[index].forEachIndexed {i,row->Row(Modifier.fillMaxWidth().height(pitch.dp).testTag("text-row-$i")) {
                                        row.forEach {cell->val cm=Modifier.weight(cell.weight).fillMaxHeight();cell.control?.let {Tile(it,cm,PaddingValues(half),true)} ?: Spacer(cm)}}}
                                }
                                if(pages.size>1) TextButton(onClick={page=(index+1)%pages.size},modifier=Modifier.width(48.dp).semantics {contentDescription="More controls, page ${index+1} of ${pages.size}"}) {
                                    Text("${index+1}/${pages.size}",color=TextPrimary,style=MaterialTheme.typography.labelMedium)}
                            }
                            BottomBar(((bodyWidth-TextInspectorBudget.BLOCK_SPACER)/6f).dp,pitch.dp,PaddingValues(half))
                        }
                    }
                    else->Face(face,metrics)
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

/** Compact monochrome switch (about 28×16dp in compact sheets); semantics live on the enclosing toggleable row. */
@Composable private fun CompactSwitch(on: Boolean,enabled: Boolean,height: Dp) {
    val width=height*1.75f
    val knob=height-4.dp
    Box(Modifier.size(width,height).clip(CircleShape).background(if(on && enabled) TextPrimary else SurfaceHigh)
        .border(1.dp,if(on && enabled) TextPrimary else Border,CircleShape),contentAlignment=if(on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.padding(horizontal=2.dp).size(knob).clip(CircleShape).background(if(!enabled) TextDisabled else if(on) Surface else TextSecondary))
    }
}

/** Fill / Stroke dual-colour swatch (front square = fill, back square = stroke). Real colours; no Canvas. */
@Composable private fun DualColourSwatch(fill: Int,stroke: Int) {
    Box(Modifier.size(InspectorIcons.INLINE)) {
        val square=11.dp;val shape=RoundedCornerShape(3.dp)
        Box(Modifier.align(Alignment.TopEnd).size(square).clip(shape).border(3.dp,Color(stroke),shape).border(1.dp,TextSecondary,shape))
        Box(Modifier.align(Alignment.BottomStart).size(square).clip(shape).background(Color(fill)).border(1.dp,TextPrimary,shape))
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

/**
 * The whole text colour picker inside the anchored popover (no second page): Fill/Stroke target, saturation/value
 * square with a hue bar (the global picker's components and colour model), alpha with a tappable value, a tappable
 * hex value, eyedropper, inherit (selection only), reset, and one scrollable swatch strip. Live; one undo per
 * interaction; no Done. Chrome is monochrome; only the colours themselves are coloured.
 */
@Composable private fun TextColourPopoverContent(target: TextControl,fill: Int,stroke: Int,selected: Boolean,palette: PaletteRepository?,
    onTarget: (TextControl)->Unit,onBegin: (TextControl)->Unit,onPreview: (Int)->Unit,onFinish: ()->Unit,onCancel: ()->Unit,
    onSet: (TextControl,Int)->Unit,onInherit: (TextControl)->Unit,onReset: (TextControl)->Unit,onEyedropper: ((TextControl)->Unit)?) {
    // The shared picker family's COMPACT variant: same 288dp layout, icons and swatch strip as TEXT UI 6; hex and
    // alpha are now edited in place (no nested popup), one undo per edit session.
    val current=if(target==TextControl.FILL) fill else stroke
    val name=if(target==TextControl.FILL) "fill" else "stroke"
    val targets=TEXT_COLOUR_TARGETS.map {ColourPickerTarget(if(it==TextControl.FILL) "FILL" else "STROKE","${it.label.lowercase()} colour")}
    FluxxColourPicker(ColourPickerVariant.COMPACT,current,onBegin={onBegin(target)},onPreview=onPreview,onFinish=onFinish,onCancel=onCancel,
        modifier=Modifier.testTag("text-colour-picker"),name="$name colour",targets=targets,target=TEXT_COLOUR_TARGETS.indexOf(target),
        onTarget={onTarget(TEXT_COLOUR_TARGETS[it])},swatches=paletteSwatches(palette,TEXT_DEFAULT_SWATCHES),
        onEyedropper=onEyedropper?.let {e->{e(target)}},onInherit=if(selected) ({onInherit(target)}) else null,onReset={onReset(target)},
        onSwatch={argb->onSet(target,TextColourEdit.swatch(current,argb))})
}
internal val TEXT_COLOUR_TARGETS=listOf(TextControl.FILL,TextControl.STROKE)
internal fun withAlpha(argb: Int,alpha: Int)=(argb and 0x00FFFFFF) or (alpha.coerceIn(0,255) shl 24)

/**
 * Bottom-bar text field. Tap to edit: the editor lifts above the keyboard in an anchored popover (the window is
 * never resized). Tap outside or Back (after the keyboard hides) commits; hardware Escape cancels.
 */
@Composable private fun SourceField(document: TextSourceDocument,selection: TextSelectionRange?,scope: TextEditScope,editing: Boolean,enabled: Boolean,modifier: Modifier,inset: PaddingValues,badge: KeyBadge,
    open: ()->Unit,onSelection: (TextSelectionRange?)->Unit,onSourceSelection: (String,TextSelectionRange)->Unit,draft: (String,Int,Int)->Unit,begin: ()->Unit,done: ()->Unit,cancel: ()->Unit) {
    Box(modifier.padding(inset),contentAlignment=Alignment.CenterStart) {
        Row(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(SurfaceHigh)
            .border(1.dp,if(editing) TextPrimary else Color.Transparent,RoundedCornerShape(10.dp))
            .clickable(enabled=enabled,role=Role.Button,onClickLabel="Edit text") {open()}
            .semantics(mergeDescendants=true) {contentDescription="Text: ${document.text.take(80)}";KeyBadges.description(badge)?.let {stateDescription=it}}
            .padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Icon(InspectorIcons.control(TextControl.SOURCE),null,Modifier.size(InspectorIcons.INLINE),tint=TextSecondary)
            Text(document.text.replace('\n',' ').ifEmpty {"Text"},Modifier.weight(1f).padding(start=6.dp),color=TextPrimary,
                style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
            if(scope.selected) {
                val count=scope.count(document)
                Row(Modifier.clip(RoundedCornerShape(8.dp)).background(Surface).clickable(role=Role.Button,onClickLabel="Clear selection") {onSelection(null)}
                    .semantics(mergeDescendants=true) {contentDescription="Selection: $count characters. Clear selection";liveRegion=LiveRegionMode.Polite}
                    .testTag("text-scope-chip").padding(horizontal=6.dp,vertical=3.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("$count",color=TextPrimary,style=MaterialTheme.typography.labelSmall)
                    Icon(InspectorIcons.icon("CANCEL"),null,Modifier.padding(start=2.dp).size(12.dp),tint=TextSecondary)
                }
            }
        }
        KeyBadgeGlyph(badge,TextSecondary,Modifier.align(Alignment.TopEnd))
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
    TextControl.SUPER->"Super";TextControl.SUB->"Sub";TextControl.ALL_CAPS->"Caps";TextControl.LOWERCASE->"Lower"
    TextControl.LIGATURES->"Liga";TextControl.ALIGN_LEFT->"Left";TextControl.ALIGN_CENTRE->"Centre";TextControl.ALIGN_RIGHT->"Right"
    TextControl.JUSTIFY->"Justify";TextControl.PAINT->"Colour";TextControl.STROKE_WIDTH->"Stroke";TextControl.ORDER->"Fill over";TextControl.TRACKING->"Track"
    TextControl.LEADING->"Lead";TextControl.H_SCALE->"H scale";TextControl.V_SCALE->"V scale";TextControl.SCALE_LINK->"Link";TextControl.BASELINE->"Base"
    TextControl.KERNING->"Kern";TextControl.INDENT_LEFT->"L ind";TextControl.INDENT_RIGHT->"R ind";TextControl.INDENT_FIRST->"1st";TextControl.BEFORE->"Before"
    TextControl.AFTER->"After";TextControl.BOX_MODE->"Box";TextControl.BOX->"Width";TextControl.DIRECTION->"Dir";TextControl.EXPLODE->"Explode";else->c.label
}
private fun alignmentLabel(a: ParagraphAlignment)=when(a) {
    ParagraphAlignment.LEFT->"Align left";ParagraphAlignment.CENTRE->"Align centre";ParagraphAlignment.RIGHT->"Align right"
    ParagraphAlignment.JUSTIFY_LAST_LEFT->"Justify, last line left";ParagraphAlignment.JUSTIFY_LAST_CENTRE->"Justify, last line centre"
    ParagraphAlignment.JUSTIFY_LAST_RIGHT->"Justify, last line right";ParagraphAlignment.JUSTIFY_ALL->"Justify all lines"
}
