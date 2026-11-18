package com.fluxx.android.ui.inspector

import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import kotlin.math.abs
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.text.input.TextFieldValue

internal enum class TextTab { CHARACTER, PARAGRAPH, ANIMATE }
internal enum class TextControl(val label: String,val unit: String="") {
    SOURCE("Source Text"),FONT("Font family and style"),SIZE("Font size","px"),RELATIVE_SIZE("Relative run size","%"),
    FILL("Fill colour"),STROKE("Stroke colour"),STROKE_WIDTH("Stroke width","px"),ORDER("Paint order"),
    TRACKING("Tracking","1/1000 em"),LEADING("Additional leading","px"),BASELINE("Baseline shift","px"),
    H_SCALE("Horizontal glyph scale","%"),V_SCALE("Vertical glyph scale","%"),KERNING("Kerning mode"),
    MANUAL_KERNING("Manual spacing after selected characters","1/1000 em"),BOLD("Faux bold"),ITALIC("Faux italic"),
    CASE("Case"),SUPER("Superscript"),SUB("Subscript"),LIGATURES("Optional ligatures"),ALIGNMENT("Alignment"),
    ALIGN_LEFT("Align left"),ALIGN_CENTRE("Align centre"),ALIGN_RIGHT("Align right"),JUSTIFY("Justify choices"),
    INDENT_LEFT("Left indent","px"),INDENT_RIGHT("Right indent","px"),INDENT_FIRST("First-line indent","px"),
    BEFORE("Space before paragraph","px"),AFTER("Space after paragraph","px"),BOX("Box width","px"),DIRECTION("Paragraph direction"),
    PAINT("Fill and stroke"),BASE_FONT("Layer base font"),BASE_ALIGNMENT("Layer base alignment")
}

/** One grid cell; [control] null is an empty slot that keeps columns aligned. */
internal data class TextGridCell(val control: TextControl?,val weight: Float=1f)

/** Data-only tab faces. Every common control sits directly on its tab; no aggregate popups. */
internal object TextInspectorLayout {
    private fun cells(vararg c: TextControl?)=c.map {TextGridCell(it)}
    // Font chip takes 1.8 of 4 columns so each Fill/Stroke swatch keeps a 48dp-wide target (51.6dp at 344dp).
    val characterRows: List<List<TextGridCell>> = listOf(
        listOf(TextGridCell(TextControl.FONT,1.8f),TextGridCell(TextControl.SIZE),TextGridCell(TextControl.PAINT,1.2f)),
        cells(TextControl.TRACKING,TextControl.LEADING,TextControl.BASELINE,TextControl.STROKE_WIDTH),
        cells(TextControl.H_SCALE,TextControl.V_SCALE,TextControl.KERNING,TextControl.ORDER),
        listOf(TextControl.BOLD,TextControl.ITALIC,TextControl.CASE,TextControl.SUPER,TextControl.SUB,TextControl.LIGATURES).map {TextGridCell(it,4f/6f)})
    val paragraphRows: List<List<TextGridCell>> = listOf(
        cells(TextControl.ALIGN_LEFT,TextControl.ALIGN_CENTRE,TextControl.ALIGN_RIGHT,TextControl.JUSTIFY),
        cells(TextControl.INDENT_LEFT,TextControl.INDENT_RIGHT,TextControl.INDENT_FIRST,TextControl.DIRECTION),
        cells(TextControl.BEFORE,TextControl.AFTER,TextControl.BOX,null))
    fun columns(fontScale: Float)=if(TextInspectorBudget.large(fontScale)) 3 else 4
    /** 4-column faces at standard scale; the labelled 3-column fallback reflows the same cells in the same order. */
    fun rows(tab: TextTab,fontScale: Float): List<List<TextGridCell>> {
        val base=when(tab) {TextTab.CHARACTER->characterRows;TextTab.PARAGRAPH->paragraphRows;TextTab.ANIMATE->return emptyList()}
        if(!TextInspectorBudget.large(fontScale)) return base
        return flow(base.flatten().mapNotNull {c->c.control?.let {TextGridCell(it,if(it==TextControl.FONT) 2f else 1f)}},3)
    }
    fun flow(cells: List<TextGridCell>,columns: Int): List<List<TextGridCell>> {
        val rows=mutableListOf<MutableList<TextGridCell>>();var used=0f
        for(cell in cells) {
            if(rows.isEmpty() || used+cell.weight>columns+1e-3f) {rows+=mutableListOf<TextGridCell>();used=0f}
            rows.last()+=cell;used+=cell.weight
        }
        rows.lastOrNull()?.let {last->val free=columns-last.sumOf {it.weight.toDouble()}.toFloat();if(free>1e-3f) last+=TextGridCell(null,free)}
        return rows
    }
    /** Short-window fallback: whole rows per page, switched from the tab row. The sheet itself never scrolls. */
    fun pages(rows: List<List<TextGridCell>>,capacity: Int): List<List<List<TextGridCell>>> =
        if(rows.isEmpty()) listOf(emptyList()) else rows.chunked(capacity.coerceAtLeast(1))
    /** Overlay rows sit beside their anchor row, never on it, when the page has room. */
    fun overlayRow(anchor: Int?,height: Int,rowCount: Int): Int {
        if(anchor==null || anchor<0 || anchor>=rowCount) return 0
        return when {
            anchor-height>=0 -> anchor-height
            anchor+1+height<=rowCount -> anchor+1
            else -> (rowCount-height).coerceAtLeast(0)
        }
    }
    fun rowOf(rows: List<List<TextGridCell>>,control: TextControl)=rows.indexOfFirst {r->r.any {it.control==control}}.takeIf {it>=0}
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
            TextControl.SIZE->AnimPropertyType.FONT_SIZE;TextControl.FILL->AnimPropertyType.FILL_COLOUR
            TextControl.STROKE->AnimPropertyType.STROKE_COLOUR;TextControl.STROKE_WIDTH->AnimPropertyType.STROKE_WIDTH
            TextControl.TRACKING->AnimPropertyType.TRACKING;TextControl.LEADING->AnimPropertyType.LEADING
            TextControl.BASELINE->AnimPropertyType.BASELINE_SHIFT;TextControl.ORDER->AnimPropertyType.STROKE_OVER_FILL
            TextControl.LIGATURES->AnimPropertyType.LIGATURES
            else->AnimPropertyType.SOURCE_TEXT
        }
    }
    fun available(control: TextControl)=!selected || control !in setOf(TextControl.LEADING,TextControl.LIGATURES,TextControl.ORDER,TextControl.BOX)
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
/** One Back transition, innermost first. System Back and the editor's top-left arrow share it. */
internal enum class TextBackStep { CLOSE_EXPLODE, CANCEL_DRAFT, CLOSE_CONFIRM, PARENT_CHOICE, CLOSE_CHOICE, CLOSE_FONTS, RETURN_TO_GRID }
internal object TextInspectorBack {
    fun step(mode: TextInspectorMode,explode: Boolean,confirming: Boolean): TextBackStep=when {
        explode->TextBackStep.CLOSE_EXPLODE
        mode is TextInspectorMode.Numeric || mode is TextInspectorMode.Colour || mode==TextInspectorMode.Source->TextBackStep.CANCEL_DRAFT
        confirming->TextBackStep.CLOSE_CONFIRM
        mode is TextInspectorMode.Popover && mode.group==TextControl.BASE_ALIGNMENT->TextBackStep.PARENT_CHOICE
        mode is TextInspectorMode.Popover->TextBackStep.CLOSE_CHOICE
        mode==TextInspectorMode.Fonts->TextBackStep.CLOSE_FONTS
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

/** Rail diamond: key/animation state replaces the old status line. Hold owners use square glyphs. */
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

internal data class TextInspectorFocus(val tab: TextTab=TextTab.CHARACTER,val control: TextControl=TextControl.SIZE)
internal sealed interface TextInspectorMode {
    data object Browse: TextInspectorMode
    data object Source: TextInspectorMode
    data object Fonts: TextInspectorMode
    data class Popover(val group: TextControl): TextInspectorMode
    data class Numeric(val draft: TextNumericDraft): TextInspectorMode
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
    fun scrubValue(value: Float): Float=when {
        control==TextControl.SIZE && !scope.selected->value.coerceIn(TextLimits.MIN_SIZE,TextLimits.MAX_SIZE)
        control==TextControl.RELATIVE_SIZE || control==TextControl.SIZE->value.coerceIn(.01f,10000f)
        control==TextControl.H_SCALE || control==TextControl.V_SCALE->value.coerceIn(0f,10000f)
        control==TextControl.MANUAL_KERNING->value.coerceIn(-10000f,10000f)
        control==TextControl.BOX || control==TextControl.STROKE_WIDTH && !scope.selected->value.coerceAtLeast(0f)
        else->value
    }.let {if(it.isFinite()) it else initial}
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

/** UI-only tunables: velocity acceleration is bounded and does not alter renderer evaluation. */
internal object TextScrubTuning {
    const val PIXELS_PER_UNIT=4f
    const val TRACKING_PIXELS_PER_UNIT=2f
    const val VELOCITY_START=160f
    const val VELOCITY_FULL=1400f
    const val MAX_ACCELERATION=64f
    const val FINE=.1f
    const val HAPTIC_INTERVAL_MS=80L
    const val DETENT_PX=10f
    const val DETENT_TRACKING=100f
    const val AXIS_PERCENT=100f
    const val EXACT_STEP=1f
    fun delta(control: TextControl,dx: Float,velocity: Float,fine: Boolean=false): Float {
        val wide=control in setOf(TextControl.SIZE,TextControl.BOX,TextControl.INDENT_LEFT,TextControl.INDENT_RIGHT,
            TextControl.INDENT_FIRST,TextControl.BEFORE,TextControl.AFTER,TextControl.LEADING,TextControl.BASELINE,TextControl.TRACKING,TextControl.MANUAL_KERNING)
        val factor=if(wide) 1f+(MAX_ACCELERATION-1f)*((abs(velocity)-VELOCITY_START)/(VELOCITY_FULL-VELOCITY_START)).coerceIn(0f,1f) else 1f
        return dx/(if(control==TextControl.TRACKING || control==TextControl.MANUAL_KERNING) TRACKING_PIXELS_PER_UNIT else PIXELS_PER_UNIT)*factor*(if(fine) FINE else 1f)
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
        is TextInspectorMode.Numeric,is TextInspectorMode.Colour,TextInspectorMode.Source->listOf("draft",retain(mode))
        is TextInspectorMode.Popover->listOf("popover",mode.group.name)
        TextInspectorMode.Fonts->listOf("fonts");else->listOf("browse")
    }
    fun restore(values: List<Any>): TextInspectorMode = when(values[0]) {
        "draft"->snapshot?.takeIf {it.first==values[1]}?.second ?: TextInspectorMode.Browse
        "fonts"->TextInspectorMode.Fonts
        "popover"->TextInspectorMode.Popover(TextControl.valueOf(values[1] as String))
        else->TextInspectorMode.Browse
    }
    val saver=Saver<TextInspectorMode,List<Any>>(save={save(it)},restore={runCatching {restore(it)}.getOrDefault(TextInspectorMode.Browse)})
    // Bundle carries tokens only: a maximum-size Source Text must not exceed Binder's state budget.
    val sourceSaver=Saver<TextFieldValue,Long>(save={val id=++serial;sourceField=id to it;id},restore={id->sourceField?.takeIf {it.first==id}?.second})
}

/**
 * One fixed Edit Text height, sized for the 4-row Character face. Text mode removes all parent chrome
 * (no header, padding or drag handle), so the sheet height is the whole surface. The sheet never scrolls.
 */
internal object TextInspectorBudget {
    const val PARENT_CHROME=0f
    const val SOURCE=48f
    const val TABS=40f
    const val ROW=48f
    const val FEEDBACK=16f
    const val RAIL=48f
    const val SIDE=8f
    const val MIN_TARGET=48f
    const val SCRUB_ZONE=36f
    fun large(fontScale: Float)=fontScale>1.2f
    fun feedbackHeight(fontScale: Float)=FEEDBACK*fontScale.coerceAtLeast(1f)
    private fun chrome(fontScale: Float)=PARENT_CHROME+SOURCE+TABS+feedbackHeight(fontScale)+RAIL
    fun sheetHeight(fontScale: Float)=chrome(fontScale)+ROW*TextInspectorLayout.rows(TextTab.CHARACTER,fontScale).size
    fun bodyHeight(total: Float,fontScale: Float)=(total-chrome(fontScale)).coerceAtLeast(0f)
    fun bodyRows(total: Float,fontScale: Float)=kotlin.math.floor(bodyHeight(total,fontScale)/ROW+1e-3f).toInt()
    fun cellWidth(width: Float,weight: Float,columns: Int)=(width-2*SIDE)*weight/columns
}
