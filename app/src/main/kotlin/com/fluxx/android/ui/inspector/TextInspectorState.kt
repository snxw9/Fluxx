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
    CASE("Case"),ALL_CAPS("All caps"),LOWERCASE("Lowercase"),SMALL_CAPS("Small caps"),SUPER("Superscript"),SUB("Subscript"),LIGATURES("Optional ligatures"),ALIGNMENT("Alignment"),
    ALIGN_LEFT("Align left"),ALIGN_CENTRE("Align centre"),ALIGN_RIGHT("Align right"),JUSTIFY("Justify choices"),
    INDENT_LEFT("Left indent","px"),INDENT_RIGHT("Right indent","px"),INDENT_FIRST("First-line indent","px"),
    BEFORE("Space before paragraph","px"),AFTER("Space after paragraph","px"),BOX_MODE("Point or box text"),BOX("Box width","px"),DIRECTION("Paragraph direction"),
    PAINT("Fill and stroke colour"),BASE_FONT("Layer base font"),BASE_ALIGNMENT("Layer base alignment")
}

/** One grid cell; [control] null is an empty slot that keeps columns aligned. */
internal data class TextGridCell(val control: TextControl?,val weight: Float=1f)

/**
 * Data-only tab faces (icon-first, After Effects Character/Paragraph style). Character, top to bottom:
 * row 1 paint, row 2 spacing/scale, a 3×3 style grid (plus two extra tiles beside it), and the bottom bar.
 */
internal object TextInspectorLayout {
    val characterRow1=listOf(TextControl.PAINT,TextControl.STROKE_WIDTH,TextControl.ORDER)
    val characterRow2=listOf(TextControl.TRACKING,TextControl.LEADING,TextControl.H_SCALE,TextControl.SCALE_LINK,TextControl.V_SCALE,TextControl.BASELINE)
    val styleGrid=listOf(listOf(TextControl.BOLD,TextControl.ITALIC,TextControl.UNDERLINE),
        listOf(TextControl.STRIKE,TextControl.SUPER,TextControl.SUB),listOf(TextControl.ALL_CAPS,TextControl.LOWERCASE,TextControl.LIGATURES))
    /** Existing capabilities with no slot in the 3×3 grid; they sit beside it, behind a hairline. */
    val styleExtras=listOf(TextControl.SMALL_CAPS,TextControl.KERNING)
    val bottomBar=listOf(TextControl.FONT,TextControl.SIZE,TextControl.SOURCE)
    val paragraphRows=listOf(listOf(TextControl.ALIGN_LEFT,TextControl.ALIGN_CENTRE,TextControl.ALIGN_RIGHT,TextControl.JUSTIFY),
        listOf(TextControl.INDENT_LEFT,TextControl.INDENT_RIGHT,TextControl.INDENT_FIRST,TextControl.BEFORE,TextControl.AFTER),
        listOf(TextControl.BOX_MODE,TextControl.BOX,TextControl.DIRECTION))
    /** Standard-scale tile rows above the bottom bar. */
    fun faceRows(tab: TextTab): List<List<TextControl>> = when(tab) {
        TextTab.CHARACTER->listOf(characterRow1,characterRow2)+styleGrid.mapIndexed {i,r->if(i==0) r+styleExtras else r}
        TextTab.PARAGRAPH->paragraphRows
        TextTab.ANIMATE->emptyList()
    }
    fun hasBottomBar(tab: TextTab)=tab!=TextTab.ANIMATE
    /** Every control reachable from the tab face, bottom bar included. */
    fun inventory(tab: TextTab): List<TextControl> = faceRows(tab).flatten()+if(hasBottomBar(tab)) bottomBar else emptyList()
    fun columns(fontScale: Float)=if(TextInspectorBudget.large(fontScale)) 3 else 6
    /** Large font scales: the labelled 3-column fallback reflows the same face controls in the same order. */
    fun labelledRows(tab: TextTab): List<List<TextGridCell>> = flow(faceRows(tab).flatten().map {TextGridCell(it)},3)
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

/** One Back transition, innermost first: popover, then sub-page (Fonts/Colour) to its tab, then the landing grid. */
internal enum class TextBackStep { CLOSE_EXPLODE, CLOSE_CONFIRM, PARENT_CHOICE, CLOSE_POPOVER, FINISH_SOURCE, CLOSE_PAGE, RETURN_TO_GRID }
internal object TextInspectorBack {
    fun step(mode: TextInspectorMode,popover: TextControl?,explode: Boolean,confirming: Boolean): TextBackStep=when {
        explode->TextBackStep.CLOSE_EXPLODE
        confirming->TextBackStep.CLOSE_CONFIRM
        popover==TextControl.BASE_ALIGNMENT->TextBackStep.PARENT_CHOICE
        popover!=null->TextBackStep.CLOSE_POPOVER
        mode==TextInspectorMode.Source->TextBackStep.FINISH_SOURCE
        mode==TextInspectorMode.Fonts || mode is TextInspectorMode.Colour->TextBackStep.CLOSE_PAGE
        // Nothing transient: return straight to the layer inspector grid, clearing any selection.
        else->TextBackStep.RETURN_TO_GRID
    }
}

/** The single header ⋮ menu: exactly these four items. */
internal enum class TextMenuItem { EXPLODE, RESET_STYLE, MANUAL_KERNING, RELATIVE_SIZE }
internal object TextHeaderMenu {
    val items=TextMenuItem.entries.toList()
    fun label(item: TextMenuItem,tab: TextTab,scopeLabel: String)=when(item) {
        TextMenuItem.EXPLODE->"Explode Text"
        TextMenuItem.RESET_STYLE->if(tab==TextTab.ANIMATE) "Reset text style (no Animate formatting)"
            else "Reset ${tab.name.lowercase().replaceFirstChar {it.uppercase()}} style · $scopeLabel"
        TextMenuItem.MANUAL_KERNING->"Manual kerning"
        TextMenuItem.RELATIVE_SIZE->"Relative size"
    }
    fun enabled(item: TextMenuItem,tab: TextTab)=item!=TextMenuItem.RESET_STYLE || tab!=TextTab.ANIMATE
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
    /** Global colour picker page (from the colour popover's Custom…). Edits apply live; leaving commits once. */
    data class Colour(val control: TextControl,val layer: CompositionLayer,val time: Long,val scope: TextEditScope): TextInspectorMode
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
        TextControl.SIZE->if(scope.selected) style.sizeFactor*100 else layer.text.size.evaluate(local)
        TextControl.RELATIVE_SIZE->style.sizeFactor*100
        TextControl.TRACKING->if(scope.selected) style.trackingDelta else layer.text.tracking.evaluate(local)
        TextControl.BASELINE->if(scope.selected) style.baselineDelta else layer.text.baselineShift.evaluate(local)
        TextControl.STROKE_WIDTH->if(scope.selected) style.strokeDelta else layer.text.strokeWidth.evaluate(local)
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
        is TextInspectorMode.Colour,TextInspectorMode.Source->listOf("draft",retain(mode))
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
 * Shared inspector sheet heights (every page, Edit Text included). Edit Text's Character tab needs 6 tile rows
 * (row 1, row 2, 3 style rows, bottom bar): frame 16 + header 40 + 6 × 42 = 308dp with 40dp tiles in compact
 * mode, and 16 + 40 + 6 × 48 = 344dp with 48dp targets otherwise. The old 268/328 budgets cannot hold six
 * rows (268 − 56 = 212 < 252; 328 − 56 = 272 < 288), so both shared heights rise by the minimum: 308 / 344.
 */
internal object InspectorSheetBudget {
    const val EXPANDED=308f
    const val STANDARD=344f
    /** Below this sheet height the inspector uses 40dp touch targets (LocalMinimumInteractiveComponentSize). */
    const val COMPACT_BELOW=320f
    const val FRAME_V=16f
    const val FRAME_H=24f
    fun height(expandedPreview: Boolean,fontScale: Float)=(if(expandedPreview) EXPANDED else STANDARD)*fontScale.coerceAtLeast(1f)
    fun compact(sheetHeight: Float)=sheetHeight<COMPACT_BELOW
}

/** Edit Text page budget inside the shared frame. The page never scrolls; large fonts page whole rows. */
internal object TextInspectorBudget {
    const val HEADER=40f
    const val TILE=40f
    const val RAIL=40f
    const val RAIL_GAP=4f
    const val MIN_TARGET=40f
    const val STANDARD_TARGET=48f
    fun large(fontScale: Float)=fontScale>1.2f
    /** Row pitch: a 40dp tile plus its gap; the whole pitch is the tap target (40dp compact, 48dp otherwise). */
    fun pitch(compact: Boolean)=if(compact) 42f else STANDARD_TARGET
    fun characterRows()=TextInspectorLayout.faceRows(TextTab.CHARACTER).size+1
    /** Height the Character tab needs at standard font scale. */
    fun pageHeight(compact: Boolean)=InspectorSheetBudget.FRAME_V+HEADER+characterRows()*pitch(compact)
    fun bodyHeight(sheetHeight: Float,fontScale: Float)=(sheetHeight-InspectorSheetBudget.FRAME_V-HEADER*fontScale.coerceAtLeast(1f)).coerceAtLeast(0f)
    fun bodyRows(sheetHeight: Float,fontScale: Float,compact: Boolean)=kotlin.math.floor(bodyHeight(sheetHeight,fontScale)/pitch(compact)+1e-3f).toInt()
    fun contentWidth(windowWidth: Float)=windowWidth-InspectorSheetBudget.FRAME_H-RAIL-RAIL_GAP
}
