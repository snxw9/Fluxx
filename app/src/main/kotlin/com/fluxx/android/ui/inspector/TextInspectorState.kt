package com.fluxx.android.ui.inspector

import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.text.input.TextFieldValue

internal enum class TextTab { CHARACTER, PARAGRAPH, ANIMATE }
internal enum class TextControl(val label: String,val unit: String="") {
    SOURCE("Source Text"),FONT("Font family and style"),SIZE("Font size","px"),RELATIVE_SIZE("Relative run size","%"),
    FILL("Fill colour"),STROKE("Stroke colour"),STROKE_WIDTH("Stroke width","px"),ORDER("Fill over stroke"),
    TRACKING("Tracking","1/1000 em"),LEADING("Additional leading","px"),BASELINE("Baseline shift","px"),
    H_SCALE("Horizontal glyph scale","%"),V_SCALE("Vertical glyph scale","%"),SCALE_LINK("Link horizontal and vertical glyph scale"),KERNING("Kerning mode"),
    MANUAL_KERNING("Manual spacing after selected characters","1/1000 em"),BOLD("Faux bold"),ITALIC("Faux italic"),
    UNDERLINE("Underline"),STRIKE("Strikethrough"),
    CASE("Case"),ALL_CAPS("All caps"),LOWERCASE("Lowercase"),SUPER("Superscript"),SUB("Subscript"),LIGATURES("Optional ligatures"),ALIGNMENT("Alignment"),
    ALIGN_LEFT("Align left"),ALIGN_CENTRE("Align centre"),ALIGN_RIGHT("Align right"),JUSTIFY("Justify choices"),
    INDENT_LEFT("Left indent","px"),INDENT_RIGHT("Right indent","px"),INDENT_FIRST("First-line indent","px"),
    BEFORE("Space before paragraph","px"),AFTER("Space after paragraph","px"),BOX_MODE("Point or box text"),BOX("Box width","px"),DIRECTION("Paragraph direction"),
    PAINT("Fill and stroke colour"),EXPLODE("Explode Text"),BASE_FONT("Layer base font"),BASE_ALIGNMENT("Layer base alignment")
}

/** One grid cell; [control] null is an empty slot that keeps columns aligned. */
internal data class TextGridCell(val control: TextControl?,val weight: Float=1f)

/** One tab face: a top row, two 3-column blocks (left, right) and, below them, the shared bottom bar. */
internal data class TextFace(val top: List<TextControl>,val left: List<List<TextControl>>,val right: List<List<TextControl>>) {
    val rows get()=maxOf(left.size,right.size)
    val controls get()=top+left.flatten()+right.flatten()
}

/**
 * Data-only tab faces (icon-first). Character, top to bottom: the top row (Explode Text pill left; Fill/Stroke
 * tile with the Fill-over-stroke switch under it, right), two 3×3 blocks (spacing/scale left, style right) and
 * the bottom bar (font chip, size, inline text). Paragraph uses the same skeleton with alignment on top.
 */
internal object TextInspectorLayout {
    const val COLUMNS=3
    val characterTop=listOf(TextControl.EXPLODE,TextControl.PAINT,TextControl.ORDER)
    val characterLeft=listOf(listOf(TextControl.STROKE_WIDTH,TextControl.TRACKING,TextControl.LEADING),
        listOf(TextControl.H_SCALE,TextControl.SCALE_LINK,TextControl.V_SCALE),listOf(TextControl.BASELINE,TextControl.KERNING))
    val characterRight=listOf(listOf(TextControl.BOLD,TextControl.ITALIC,TextControl.UNDERLINE),
        listOf(TextControl.STRIKE,TextControl.SUPER,TextControl.SUB),listOf(TextControl.ALL_CAPS,TextControl.LOWERCASE,TextControl.LIGATURES))
    val paragraphTop=listOf(TextControl.ALIGN_LEFT,TextControl.ALIGN_CENTRE,TextControl.ALIGN_RIGHT,TextControl.JUSTIFY)
    val paragraphLeft=listOf(listOf(TextControl.INDENT_LEFT,TextControl.INDENT_RIGHT,TextControl.INDENT_FIRST),listOf(TextControl.BEFORE,TextControl.AFTER))
    val paragraphRight=listOf(listOf(TextControl.BOX_MODE,TextControl.BOX,TextControl.DIRECTION))
    val bottomBar=listOf(TextControl.FONT,TextControl.SIZE,TextControl.SOURCE)
    fun face(tab: TextTab): TextFace? = when(tab) {
        TextTab.CHARACTER->TextFace(characterTop,characterLeft,characterRight)
        TextTab.PARAGRAPH->TextFace(paragraphTop,paragraphLeft,paragraphRight)
        TextTab.ANIMATE->null
    }
    /** Top row then block rows (left block row i followed by right block row i): the visual reading order. */
    fun faceRows(tab: TextTab): List<List<TextControl>> {
        val f=face(tab) ?: return emptyList()
        return listOf(f.top)+(0 until f.rows).map {i->f.left.getOrElse(i) {emptyList()}+f.right.getOrElse(i) {emptyList()}}
    }
    fun hasBottomBar(tab: TextTab)=tab!=TextTab.ANIMATE
    /** Every control reachable from the tab face, bottom bar included. */
    fun inventory(tab: TextTab): List<TextControl> = (face(tab)?.controls ?: emptyList())+if(hasBottomBar(tab)) bottomBar else emptyList()
    fun columns(fontScale: Float)=if(TextInspectorBudget.large(fontScale)) 3 else 6
    /** A short last row (2 of 3) is centred: offset in columns from the block's left edge. */
    fun rowOffset(rowSize: Int)=(COLUMNS-rowSize).coerceAtLeast(0)/2f
    /** Large font scales: the labelled 3-column fallback reflows the same face controls in the same order. */
    fun labelledRows(tab: TextTab): List<List<TextGridCell>> = flow((face(tab)?.controls ?: emptyList()).map {TextGridCell(it)},3)
    fun flow(cells: List<TextGridCell>,columns: Int): List<List<TextGridCell>> {
        val rows=mutableListOf<MutableList<TextGridCell>>();var used=0f
        for(cell in cells) {
            if(rows.isEmpty() || used+cell.weight>columns+1e-3f) {rows+=mutableListOf<TextGridCell>();used=0f}
            rows.last()+=cell;used+=cell.weight
        }
        rows.lastOrNull()?.let {last->val free=columns-last.sumOf {it.weight.toDouble()}.toFloat();if(free>1e-3f) last+=TextGridCell(null,free)}
        return rows
    }
    /** Short-window fallback: whole rows per page, switched from the header. The sheet itself never scrolls. */
    fun <T> pages(rows: List<T>,capacity: Int): List<List<T>> =
        if(rows.isEmpty()) listOf(emptyList()) else rows.chunked(capacity.coerceAtLeast(1))
}

/** Stroke clamp feedback, shown only while clamped. Width-scaled forms keep one line at each font scale. */
internal object TextFeedback {
    private fun number(v: Float)=if(v==v.toLong().toFloat()) v.toLong().toString() else "%.2f".format(java.util.Locale.ROOT,v).trimEnd('0').trimEnd('.')
    private fun effective(clampLabel: String?)=clampLabel?.removePrefix("Apply · clamp ")?.removeSuffix(" px")?.let {range->
        val parts=range.split('–');if(parts.size==2 && parts[0]==parts[1]) parts[0] else range}
    /** Accessibility/announcement text: always complete. */
    fun strokeClampFull(requested: Float,clampLabel: String?)=effective(clampLabel)?.let {"Requested ${number(requested)} px | Effective $it px | SDF spread / size limit"}
    /** Visible one-line text. Proven width envelope: requested ≤ 999.99, effective range ≤ 99.99–99.99. */
    fun strokeClamp(requested: Float,clampLabel: String?,fontScale: Float=1f): String? {
        val e=effective(clampLabel) ?: return null
        return when {
            fontScale<=1.2f -> "Requested ${number(requested)} px | Effective $e px | SDF limit"
            fontScale<=1.4f -> "Req ${number(requested)} | Eff $e px | SDF limit"
            else -> "Req ${number(requested)} | Eff $e"
        }
    }
    fun line(error: String?,message: String?,numeric: String?,stroke: String?)=error ?: message ?: numeric ?: stroke
}
internal data class TextEditScope(val range: TextSelectionRange?) {
    val selected get()=range!=null && !range.collapsed
    fun owner(control: TextControl): AnimPropertyType? {
        if(selected || control==TextControl.BASE_FONT || control==TextControl.BASE_ALIGNMENT) return null
        return when(control) {
            TextControl.SIZE->AnimPropertyType.FONT_SIZE;TextControl.FILL,TextControl.PAINT->AnimPropertyType.FILL_COLOUR
            TextControl.STROKE->AnimPropertyType.STROKE_COLOUR;TextControl.STROKE_WIDTH->AnimPropertyType.STROKE_WIDTH
            TextControl.TRACKING->AnimPropertyType.TRACKING;TextControl.LEADING->AnimPropertyType.LEADING
            TextControl.BASELINE->AnimPropertyType.BASELINE_SHIFT;TextControl.ORDER->AnimPropertyType.STROKE_OVER_FILL
            TextControl.LIGATURES->AnimPropertyType.LIGATURES
            else->AnimPropertyType.SOURCE_TEXT
        }
    }
    fun available(control: TextControl)=!selected || control !in setOf(TextControl.LEADING,TextControl.LIGATURES,TextControl.ORDER,TextControl.BOX,TextControl.BOX_MODE)
    fun title(control: TextControl)=when {
        selected && control==TextControl.SIZE->"Relative run size"
        selected && control in setOf(TextControl.TRACKING,TextControl.BASELINE,TextControl.STROKE_WIDTH)->"${control.label} delta"
        selected && control in setOf(TextControl.FILL,TextControl.STROKE)->"${control.label} override"
        else->control.label
    }
    fun unit(control: TextControl)=if(selected && control==TextControl.SIZE) "%" else control.unit
    fun count(document: TextSourceDocument): Int {
        val r=range ?: return 0
        return document.boundaries.count {it>=r.start && it<r.end}
    }
}

/**
 * The property that owns keyframe navigation: the open popover's property if one is open, otherwise the last
 * touched control. The action bar's Prev/Next keyframe buttons follow this owner.
 */
internal object TextFocus {
    /** The colour popover edits Fill or Stroke; its target is recorded as the last touched control. */
    fun control(lastTouched: TextControl,popover: TextControl?): TextControl = when(popover) {
        null,TextControl.PAINT->lastTouched
        else->popover
    }
    fun owner(scope: TextEditScope,tab: TextTab,lastTouched: TextControl,popover: TextControl?): AnimPropertyType? =
        if(tab==TextTab.ANIMATE) null else scope.owner(control(lastTouched,popover))
}

/** One Back transition, innermost first: popover, then the Fonts sub-page to its tab, then the landing grid. */
internal enum class TextBackStep { CLOSE_EXPLODE, CLOSE_CONFIRM, PARENT_CHOICE, CLOSE_POPOVER, FINISH_SOURCE, CLOSE_PAGE, RETURN_TO_GRID }
internal object TextInspectorBack {
    fun step(mode: TextInspectorMode,popover: TextControl?,explode: Boolean,confirming: Boolean): TextBackStep=when {
        explode->TextBackStep.CLOSE_EXPLODE
        confirming->TextBackStep.CLOSE_CONFIRM
        popover==TextControl.BASE_ALIGNMENT->TextBackStep.PARENT_CHOICE
        popover!=null->TextBackStep.CLOSE_POPOVER
        mode==TextInspectorMode.Source->TextBackStep.FINISH_SOURCE
        mode==TextInspectorMode.Fonts->TextBackStep.CLOSE_PAGE
        // Nothing transient: return straight to the layer inspector grid, clearing any selection.
        else->TextBackStep.RETURN_TO_GRID
    }
}

/**
 * The rail's property-options ⋮ is the single overflow menu of the Edit Text page (the header has none).
 * Order: Reset {property} value, Enable/Freeze, Reset animation…, Hold/easing, Reset {tab} style · scope,
 * Manual spacing. Relative size is not listed: with a selection the Size tile already edits relative run size.
 */
internal enum class TextRailItem { RESET_VALUE, ANIMATION, RESET_ANIMATION, EASING, RESET_STYLE, MANUAL_SPACING }
internal object TextRailMenu {
    fun items(owner: AnimPropertyType?,animated: Boolean,onKey: Boolean,tab: TextTab): List<TextRailItem> = buildList {
        add(TextRailItem.RESET_VALUE)
        if(owner!=null) {
            add(TextRailItem.ANIMATION)
            if(animated) add(TextRailItem.RESET_ANIMATION)
            if(owner in TextKeyState.HOLD_OWNERS || (animated && onKey)) add(TextRailItem.EASING)
        }
        if(tab!=TextTab.ANIMATE) add(TextRailItem.RESET_STYLE)
        if(tab==TextTab.CHARACTER) add(TextRailItem.MANUAL_SPACING)
    }
    fun label(item: TextRailItem,tab: TextTab,scopeLabel: String,property: String,animated: Boolean)=when(item) {
        TextRailItem.RESET_VALUE->"Reset $property value"
        TextRailItem.ANIMATION->if(animated) "Freeze at playhead" else "Enable animation"
        TextRailItem.RESET_ANIMATION->"Reset animation…"
        TextRailItem.EASING->"Hold / easing"
        TextRailItem.RESET_STYLE->"Reset ${tab.name.lowercase().replaceFirstChar {it.uppercase()}} style · $scopeLabel"
        TextRailItem.MANUAL_SPACING->"Manual spacing…"
    }
}

/** Rail diamond: key/animation state. Hold owners use square glyphs. */
internal object TextKeyState {
    val HOLD_OWNERS=setOf(AnimPropertyType.SOURCE_TEXT,AnimPropertyType.STROKE_OVER_FILL,AnimPropertyType.LIGATURES)
    fun icon(owner: AnimPropertyType?,animated: Boolean,onKey: Boolean): String {
        val hold=owner in HOLD_OWNERS
        return when {!animated->"KEY";onKey->if(hold) "HOLD_ON" else "KEY_ON";else->if(hold) "HOLD_ANIM" else "KEY_ANIM"}
    }
    fun description(owner: AnimPropertyType?,animated: Boolean,onKey: Boolean,timecode: String)=when {
        owner==null->"No keyframes for this control"
        !animated->"$owner: animation off"
        onKey->"$owner: on key at $timecode"+if(owner in HOLD_OWNERS) ", Hold" else ""
        else->"$owner: animated, between keys"+if(owner in HOLD_OWNERS) ", Hold" else ""
    }
}

/** Page shown inside the inspector frame. Popovers are separate (one at a time) and always close first. */
internal sealed interface TextInspectorMode {
    data object Browse: TextInspectorMode
    data object Source: TextInspectorMode
    data object Fonts: TextInspectorMode
}

/** Which page survives the activity stopping (the system font picker stops it): only Fonts; drafts always cancel. */
internal object TextInspectorLifecycle {
    fun afterStop(mode: TextInspectorMode): TextInspectorMode=if(mode==TextInspectorMode.Fonts) mode else TextInspectorMode.Browse
}

/** Receives one colour interaction: begin once, live previews, then finish (one undo entry) or cancel. */
internal interface TextDraftSink { fun begin(); fun preview(payload: TextProperties); fun finish(); fun cancel() }

/**
 * Colour popover state. Every interaction (an SV/hue/alpha drag, a swatch, a typed hex or alpha, an eyedropper
 * sample) is one transaction: begin → live previews on the captured layer/time/scope → finish. No Done.
 */
internal class TextColourSession(private val sink: TextDraftSink) {
    var target=TextControl.FILL; private set
    private var captured: Triple<CompositionLayer,Long,TextEditScope>?=null
    val active get()=captured!=null
    fun target(c: TextControl) {target=if(c==TextControl.STROKE) TextControl.STROKE else TextControl.FILL}
    fun begin(layer: CompositionLayer,time: Long,scope: TextEditScope) {if(captured==null) {captured=Triple(layer,time,scope);sink.begin()}}
    fun preview(argb: Int) {captured?.let {(l,t,s)->sink.preview(textColourPayload(l,t,s,target,argb))}}
    fun finish() {if(captured!=null) {captured=null;sink.finish()}}
    fun cancel() {if(captured!=null) {captured=null;sink.cancel()}}
    /** A one-shot edit: exactly one begin/preview/finish. */
    fun set(layer: CompositionLayer,time: Long,scope: TextEditScope,argb: Int) {cancel();begin(layer,time,scope);preview(argb);finish()}
}

/** Pure colour arithmetic for the popover: typed hex/alpha, swatches keep the current alpha. */
internal object TextColourEdit {
    fun alphaPercent(argb: Int)=(argb ushr 24)*100f/255f
    fun withAlphaPercent(argb: Int,percent: Float)=withAlpha(argb,kotlin.math.round(percent.coerceIn(0f,100f)/100f*255f).toInt())
    /** RRGGBB keeps the current alpha; AARRGGBB replaces it. Invalid text returns null (nothing applied). */
    fun hex(text: String, current: Int): Int? = FluxxColor.parseHexEntry(text, current)
    fun hexText(argb: Int)=FluxxColor.fromArgb(argb).toHexString(true)
    /** A swatch sets the colour and keeps the current alpha (a fully transparent colour becomes opaque). */
    fun swatch(current: Int,argb: Int)=withAlpha(argb,(current ushr 24).takeIf {it>0} ?: 255)
    fun hsv(hue: Float,saturation: Float,value: Float,current: Int)=FluxxColor.fromHsv(hue,saturation,value,(current ushr 24)/255f).toArgb()
}

/** Captures signed origin, scope and snapped playhead once; later preview never retargets a key. */
internal class TextNumericDraft(val layer: CompositionLayer,time: Long,val frameRate: FrameRate,
    val scope: TextEditScope,val control: TextControl) {
    val time=frameRate.snap(time)
    val local=Math.subtractExact(this.time,layer.resolvedKeyframeAnchorUs)
    val document=layer.text.source.evaluateDocument(local)
    val start=scope.range?.start ?: 0;val end=scope.range?.end ?: 0
    private val style=document.runAt(start)?.style ?: TextRunStyle()
    private val paragraph=document.paragraphs.getOrNull(document.text.take(start).count {it=='\n'}) ?: TextParagraphStyle()
    val limit: PropertyLimit? get()=PropertyLimits.forControl(control,scope.selected)
    val initial: Float=when(control) {
        TextControl.SIZE->if(scope.selected) style.sizeFactor*100 else layer.text.sizeAt(local)
        TextControl.RELATIVE_SIZE->style.sizeFactor*100
        TextControl.TRACKING->if(scope.selected) style.trackingDelta else layer.text.tracking.evaluate(local)
        TextControl.BASELINE->if(scope.selected) style.baselineDelta else layer.text.baselineShift.evaluate(local)
        TextControl.STROKE_WIDTH->if(scope.selected) style.strokeDelta else layer.text.strokeWidthAt(local)
        TextControl.LEADING->layer.text.leading.evaluate(local)
        TextControl.H_SCALE->style.horizontalScale;TextControl.V_SCALE->style.verticalScale
        TextControl.MANUAL_KERNING->style.manualKerning.firstOrNull {it.character>=start}?.amount ?: 0f
        TextControl.INDENT_LEFT->paragraph.indentLeft;TextControl.INDENT_RIGHT->paragraph.indentRight
        TextControl.INDENT_FIRST->paragraph.indentFirst;TextControl.BEFORE->paragraph.spaceBefore
        TextControl.AFTER->paragraph.spaceAfter;TextControl.BOX->document.boxWidth
        else->error("Not numeric: $control")
    }
    /** Default the reset icon returns to (inheritance: run values reset to neutral, layer values to the model default). */
    val default: Float get()=when(control) {
        TextControl.SIZE->if(scope.selected) 100f else TextProperties().size.staticValue
        TextControl.RELATIVE_SIZE,TextControl.H_SCALE,TextControl.V_SCALE->100f
        else->0f
    }
    val mixed: Boolean get() {
        fun runValue(s: TextRunStyle): Any=when(control) {
            TextControl.SIZE,TextControl.RELATIVE_SIZE->s.sizeFactor;TextControl.TRACKING->s.trackingDelta
            TextControl.BASELINE->s.baselineDelta;TextControl.STROKE_WIDTH->s.strokeDelta
            TextControl.H_SCALE->s.horizontalScale;TextControl.V_SCALE->s.verticalScale
            TextControl.MANUAL_KERNING->s.manualKerning.map {it.amount}.distinct();else->0f
        }
        if(control in setOf(TextControl.INDENT_LEFT,TextControl.INDENT_RIGHT,TextControl.INDENT_FIRST,TextControl.BEFORE,TextControl.AFTER)) {
            val first=if(scope.selected) document.text.take(start).count {it=='\n'} else 0
            val last=if(scope.selected) document.text.take((end-1).coerceAtLeast(start)).count {it=='\n'} else document.paragraphs.lastIndex
            return document.paragraphs.filterIndexed {i,_->i in first..last}.map {p->when(control) {
                TextControl.INDENT_LEFT->p.indentLeft;TextControl.INDENT_RIGHT->p.indentRight;TextControl.INDENT_FIRST->p.indentFirst;TextControl.BEFORE->p.spaceBefore;else->p.spaceAfter
            }}.distinct().size>1
        }
        val runOnly=control in setOf(TextControl.RELATIVE_SIZE,TextControl.H_SCALE,TextControl.V_SCALE,TextControl.MANUAL_KERNING)
        return (scope.selected || runOnly) && document.runs.filter { !scope.selected || it.start<end && it.end>start }.map {runValue(it.style)}.distinct().size>1
    }
    /** Hard-cap clamp applied to every committed or programmatic value (shared [PropertyLimits]). */
    fun scrubValue(value: Float): Float=if(value.isNaN()) initial else (limit?.hard(value) ?: value).let {if(it.isFinite()) it else initial}
    /** Glyph scale with the link on: the other axis follows proportionally (uniform scaling). */
    fun payload(value: Float,linked: Boolean): TextProperties {
        if(!linked || control !in setOf(TextControl.H_SCALE,TextControl.V_SCALE)) return payload(value)
        require(value.isFinite()) {"Enter a finite number"}
        val h=style.horizontalScale;val v=style.verticalScale
        val other=PropertyLimits.GLYPH_SCALE.hard(if(control==TextControl.H_SCALE) (if(h==0f) value else v*value/h) else (if(v==0f) value else h*value/v))
        return TextEdits.document(layer,time,document.format(start,end) {s->
            if(control==TextControl.H_SCALE) s.copy(horizontalScale=value,verticalScale=other) else s.copy(verticalScale=value,horizontalScale=other)})
    }
    fun payload(value: Float): TextProperties {
        require(scope.available(control)) {"Clear selection to edit ${control.label}"}
        require(value.isFinite()) {"Enter a finite number"}
        if(!scope.selected && control in setOf(TextControl.SIZE,TextControl.TRACKING,TextControl.BASELINE,TextControl.LEADING,TextControl.STROKE_WIDTH)) {
            if(control==TextControl.SIZE) return TextEdits.size(layer,time,value)
            return TextEdits.scalar(layer,time,requireNotNull(scope.owner(control)),value)
        }
        val updated=when(control) {
            TextControl.BOX->document.copy(boxWidth=value)
            TextControl.INDENT_LEFT,TextControl.INDENT_RIGHT,TextControl.INDENT_FIRST,TextControl.BEFORE,TextControl.AFTER->document.formatParagraphs(start,end) {p->when(control) {
                TextControl.INDENT_LEFT->p.copy(indentLeft=value);TextControl.INDENT_RIGHT->p.copy(indentRight=value)
                TextControl.INDENT_FIRST->p.copy(indentFirst=value);TextControl.BEFORE->p.copy(spaceBefore=value);else->p.copy(spaceAfter=value)
            }}
            else->document.format(start,end) {s->when(control) {
                TextControl.SIZE,TextControl.RELATIVE_SIZE->s.copy(sizeFactor=value/100)
                TextControl.TRACKING->s.copy(trackingDelta=value);TextControl.BASELINE->s.copy(baselineDelta=value)
                TextControl.STROKE_WIDTH->s.copy(strokeDelta=value);TextControl.H_SCALE->s.copy(horizontalScale=value)
                TextControl.V_SCALE->s.copy(verticalScale=value)
                TextControl.MANUAL_KERNING->{
                    val a=if(scope.selected) start else 0;val b=if(scope.selected) end else document.text.length
                    val entries=s.manualKerning.associateBy {it.character}.toMutableMap()
                    document.boundaries.filter {it in a until b && document.text[it]!='\n'}.forEach {entries[it]=TextManualKerning(it,value)}
                    s.copy(manualKerning=FrozenList.of(entries.values.sortedBy {it.character}))
                }
                else->s
            }}
        }
        return TextEdits.document(layer,time,updated)
    }
}

/** Saved editor state only: never added to document schema or undo. */
internal object TextInspectorModeCodec {
    private var serial=0L
    private var snapshot: Pair<Long,TextInspectorMode>?=null
    private var sourceField: Pair<Long,TextFieldValue>?=null
    @Synchronized private fun retain(mode: TextInspectorMode): Long {
        snapshot?.let {if(it.second===mode) return it.first}
        val id=++serial;snapshot=id to mode;return id
    }
    fun save(mode: TextInspectorMode): List<Any> = when(mode) {
        TextInspectorMode.Source->listOf("draft",retain(mode))
        TextInspectorMode.Fonts->listOf("fonts");else->listOf("browse")
    }
    fun restore(values: List<Any>): TextInspectorMode = when(values[0]) {
        "draft"->snapshot?.takeIf {it.first==values[1]}?.second ?: TextInspectorMode.Browse
        "fonts"->TextInspectorMode.Fonts
        else->TextInspectorMode.Browse
    }
    val saver=Saver<TextInspectorMode,List<Any>>(save={save(it)},restore={runCatching {restore(it)}.getOrDefault(TextInspectorMode.Browse)})
    // Bundle carries tokens only: a maximum-size Source Text must not exceed Binder's state budget.
    val sourceSaver=Saver<TextFieldValue,Long>(save={val id=++serial;sourceField=id to it;id},restore={id->sourceField?.takeIf {it.first==id}?.second})
}

/**
 * Shared inspector sheet heights for EVERY page (Edit Text included): the original 268dp (expanded preview) and
 * 328dp (standard), scaled by font scale exactly as before TEXT UI 5. Edit Text fits by using smaller tiles.
 */
internal object InspectorSheetBudget {
    const val EXPANDED=268f
    const val STANDARD=328f
    /** Below this sheet height the inspector uses 40dp touch targets (LocalMinimumInteractiveComponentSize). */
    const val COMPACT_BELOW=320f
    const val FRAME_V=16f
    const val FRAME_H=24f
    fun height(expandedPreview: Boolean,fontScale: Float)=(if(expandedPreview) EXPANDED else STANDARD)*fontScale.coerceAtLeast(1f)
    fun compact(sheetHeight: Float)=sheetHeight<COMPACT_BELOW
}

/**
 * Exact Edit Text face geometry (dp) for one sheet height and window width. Vertically:
 * body = top + groupGap + middle (3 tiles + 2 gaps) + groupGap + bottom (1 tile). Every touch target is a full cell
 * pitch (tile + gap, the group gap split between neighbouring bands); drawn tiles are inset by half a gap.
 * Horizontally the content is 6 equal cells (two 3-column blocks) plus one [TextInspectorBudget.BLOCK_SPACER].
 */
internal data class TextFaceMetrics(val compact: Boolean,val body: Float,val tile: Float,val top: Float,val middle: Float,val bottom: Float,
    val cell: Float,val paintTile: Float,val switchHeight: Float,val switchGap: Float,val switchBeside: Boolean) {
    val gap get()=TextInspectorBudget.GRID_GAP
    val groupGap get()=TextInspectorBudget.GROUP_GAP
    /** Band heights as laid out (each group gap is split between the bands on either side of its hairline). */
    val topBand get()=top+groupGap/2
    val middleBand get()=middle+groupGap
    val bottomBand get()=bottom+groupGap/2
    /** Middle block row heights: the outer rows take half a group gap each, so all three are touch-sized. */
    val middleRows get()=listOf(tile+gap/2+groupGap/2,tile+gap,tile+gap/2+groupGap/2)
    val pitchV get()=tile+gap
    val pitchH get()=cell
    val visualTileWidth get()=cell-gap
    val blockWidth get()=3*cell-gap
    val fits get()=top>=TextInspectorBudget.PAINT_MIN && minOf(pitchV,pitchH,bottomBand,middleRows.min())>=TextInspectorBudget.MIN_TARGET && topBand+middleBand+bottomBand<=body+1e-3f
}

/** Edit Text page budget inside the shared frame. The page never scrolls; large fonts page whole rows. */
internal object TextInspectorBudget {
    const val HEADER=40f
    const val RAIL=40f
    const val RAIL_GAP=4f
    const val MIN_TARGET=40f
    const val TILE_COMPACT=36f
    const val TILE_STANDARD=44f
    const val GRID_GAP=4f
    const val GROUP_GAP=8f
    /** Spacer between the two blocks' cells; the visual gap between their tiles is spacer + one grid gap = 8dp. */
    const val BLOCK_SPACER=4f
    const val PAINT_MIN=28f
    const val SWITCH_COMPACT=16f
    const val SWITCH_STANDARD=24f
    const val PILL=28f
    /** Labelled large-font fallback: one row = a 44dp tile plus its gap. */
    const val LABELLED_PITCH=48f
    fun large(fontScale: Float)=fontScale>1.2f
    fun header(fontScale: Float)=HEADER*fontScale.coerceAtLeast(1f)
    fun bodyHeight(sheetHeight: Float,fontScale: Float)=(sheetHeight-InspectorSheetBudget.FRAME_V-header(fontScale)).coerceAtLeast(0f)
    fun contentWidth(windowWidth: Float)=windowWidth-InspectorSheetBudget.FRAME_H-RAIL-RAIL_GAP
    fun metrics(sheetHeight: Float,contentWidth: Float,fontScale: Float=1f): TextFaceMetrics =
        forBody(bodyHeight(sheetHeight,fontScale),contentWidth,InspectorSheetBudget.compact(sheetHeight))
    /** Same arithmetic from the measured body (what the page actually receives). */
    fun forBody(body: Float,contentWidth: Float,compact: Boolean): TextFaceMetrics {
        val tile=if(compact) TILE_COMPACT else TILE_STANDARD
        val middle=3*tile+2*GRID_GAP
        val top=body-middle-tile-2*GROUP_GAP
        val switch=if(compact) SWITCH_COMPACT else SWITCH_STANDARD
        val switchGap=if(top>=tile+GRID_GAP+switch) GRID_GAP else 0f
        val stacked=top>=PAINT_MIN+switch+switchGap
        val paint=if(stacked) minOf(tile,top-switch-switchGap) else minOf(tile,top)
        return TextFaceMetrics(compact,body,tile,top,middle,tile,(contentWidth-BLOCK_SPACER)/6f,paint,switch,if(stacked) switchGap else 0f,!stacked)
    }
    /** Labelled fallback: whole rows that fit above the bottom bar. */
    fun labelledCapacity(sheetHeight: Float,fontScale: Float)=
        kotlin.math.floor((bodyHeight(sheetHeight,fontScale)-LABELLED_PITCH)/LABELLED_PITCH+1e-3f).toInt().coerceAtLeast(1)
}

/**
 * Inspector chrome. The landing grid keeps its drag handle and layer-name row (name, duplicate, delete) above the
 * quick actions. Every section page (Transform, Timing, Blending & Opacity, Edit Solid, Audio; Edit Text draws its
 * own) has ONE header row, exactly like Edit Text: ← | page tabs. Content height is arithmetic, not a guess.
 */
internal object InspectorChromeBudget {
    /** Landing grid only. */
    const val HANDLE=20f
    const val CONTENT_TOP=4f
    const val LABEL_LINE=16f
    /** Landing grid only: layer name, duplicate, delete. */
    fun nameRow(compact: Boolean)=if(compact) 40f else 48f
    /** The single section-page header row (same as Edit Text's: 40dp, grows with font scale). */
    fun header(fontScale: Float)=TextInspectorBudget.header(fontScale)
    fun contentHeight(sheetHeight: Float,fontScale: Float)=
        (sheetHeight-InspectorSheetBudget.FRAME_V-header(fontScale)-CONTENT_TOP).coerceAtLeast(0f)
    /** TEXT UI 6 section chrome (handle + name row + tab row), kept only to state how much height TEXT UI 7 freed. */
    fun legacyTabRow(compact: Boolean,fontScale: Float)=(if(compact) 6f+6f else 12f+10f)+2f+LABEL_LINE*fontScale.coerceAtLeast(1f)
    fun legacyContentHeight(sheetHeight: Float,fontScale: Float): Float {
        val compact=InspectorSheetBudget.compact(sheetHeight)
        return sheetHeight-InspectorSheetBudget.FRAME_V-HANDLE-nameRow(compact)-legacyTabRow(compact,fontScale)-CONTENT_TOP
    }
}

/**
 * Section-page control sizes (TEXT UI 8). Every size is a fixed constant that does NOT depend on the sheet height
 * (268 / 328, preview expanded or not): the leftover content height becomes equal margins above and below the
 * control group, which is centred in the content area ([centre]). Only rows that contain text grow, and only with
 * font scale ([text]: +[TEXT_GROWTH]dp per +1.0 of scale). Pure arithmetic, JVM tested at 268/328 x 1/1.3/2.
 */
internal object SectionLayout {
    const val MIN_TARGET=40f
    const val GAP=8f
    /** Text-bearing rows grow by this much per +1.0 of font scale (one text line at 2x). */
    const val TEXT_GROWTH=20f
    // ---- Transform ------------------------------------------------------------------------------------------
    /** Mode column (Position / Rotation / Scale): 40dp tiles, top-aligned. */
    const val MODE_TILE=40f
    const val MODE_COLUMN=MODE_TILE
    const val MODE_GAP=4f
    const val VALUE_BOX=40f
    const val PAD=128f
    const val DIAL=144f
    const val READOUT=28f
    const val STRIP=48f
    const val RULER_GAP=4f
    // ---- Opacity / Blending, Timing, Audio --------------------------------------------------------------------
    const val BLEND_ROW=40f
    const val LABEL_LINE=20f
    const val TOUCH_ROW=48f
    const val TRACK=6f
    const val THUMB=20f
    const val VALUE_W=72f
    /** Slider rows stack without an extra gap: the label line already separates them. */
    const val SLIDER_GAP=0f

    fun text(base: Float,fontScale: Float)=base+TEXT_GROWTH*(fontScale.coerceAtLeast(1f)-1f)
    fun valueBox(fontScale: Float)=text(VALUE_BOX,fontScale)

    data class Ruler(val readout: Float,val strip: Float,val gap: Float) {
        val height get()=readout+gap+strip
    }
    /** 28 readout + 4 + 48 strip = 80 at font scale 1, whatever the sheet height. */
    fun ruler(fontScale: Float)=Ruler(text(READOUT,fontScale),STRIP,RULER_GAP)
    /** Linked: one ruler; unlinked: Width and Height with a [RULER_GAP] between them (2 x 80 + 4 = 164). */
    fun scaleGroup(linked: Boolean,fontScale: Float)=ruler(fontScale).let {if(linked) it.height else 2*it.height+RULER_GAP}
    /** X / Y value boxes, a gap, then the 128dp pad. */
    fun positionGroup(fontScale: Float)=valueBox(fontScale)+GAP+PAD
    /** The dial carries its value box inside. */
    fun dialGroup()=DIAL

    /** Slider rows: label line above a touch row with a 6dp track, 20dp thumb and a ~72 x 40 value box. */
    data class SliderRow(val label: Float,val track: Float,val thumb: Float,val touch: Float,val valueWidth: Float,val valueHeight: Float) {
        val height get()=label+touch
    }
    fun sliderRow(fontScale: Float): SliderRow {
        val value=valueBox(fontScale)
        return SliderRow(text(LABEL_LINE,fontScale),TRACK,THUMB,maxOf(TOUCH_ROW,value),VALUE_W,value)
    }
    /** [rows] slider rows under [fixedRows] plain 40dp text rows (the blend-mode row). */
    fun sliderGroup(rows: Int,fixedRows: Int,fontScale: Float)=
        fixedRows*(text(BLEND_ROW,fontScale)+GAP)+rows*sliderRow(fontScale).height+(rows-1).coerceAtLeast(0)*SLIDER_GAP

    /** Where a group sits: equal top/bottom margins when it fits, otherwise top-aligned (and scrollable, never clipped). */
    data class Placement(val top: Float,val bottom: Float,val fits: Boolean)
    fun centre(content: Float,group: Float): Placement =
        if(group<=content) ((content-group)/2f).let {Placement(it,content-group-it,true)} else Placement(0f,0f,false)
}

/** Rail stack: 40dp tiles with a 6dp gap, top-aligned; disabled placeholders shrink first when space is short. */
internal object RailBudget {
    const val TILE=40f
    const val GAP=6f
    const val DISABLED_MIN=24f
    fun full(items: Int)=items*TILE+(items-1).coerceAtLeast(0)*GAP
    /** Height of each disabled (non-interactive) item so the whole stack fits [available]. */
    fun disabledHeight(available: Float,interactive: Int,disabled: Int): Float {
        if(disabled==0 || available>=full(interactive+disabled)) return TILE
        val free=available-interactive*TILE-(interactive+disabled-1)*GAP
        return (free/disabled).coerceIn(DISABLED_MIN,TILE)
    }
}
