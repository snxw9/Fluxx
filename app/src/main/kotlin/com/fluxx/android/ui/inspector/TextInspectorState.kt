package com.fluxx.android.ui.inspector

import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import kotlin.math.abs

internal enum class TextTab { CHARACTER, PARAGRAPH, ANIMATE }
internal enum class TextControl(val label: String,val unit: String="") {
    SOURCE("Source Text"),FONT("Font family and style"),SIZE("Font size","px"),RELATIVE_SIZE("Relative run size","%"),
    FILL("Fill colour"),STROKE("Stroke colour"),STROKE_WIDTH("Stroke width","px"),ORDER("Paint order"),
    TRACKING("Tracking","1/1000 em"),LEADING("Additional leading","px"),BASELINE("Baseline shift","px"),
    H_SCALE("Horizontal glyph scale","%"),V_SCALE("Vertical glyph scale","%"),KERNING("Kerning mode"),
    MANUAL_KERNING("Manual spacing after selected characters","1/1000 em"),BOLD("Faux bold"),ITALIC("Faux italic"),
    CASE("Case"),SUPER("Superscript"),SUB("Subscript"),LIGATURES("Optional ligatures"),ALIGNMENT("Alignment"),
    INDENT_LEFT("Left indent","px"),INDENT_RIGHT("Right indent","px"),INDENT_FIRST("First-line indent","px"),
    BEFORE("Space before paragraph","px"),AFTER("Space after paragraph","px"),BOX("Box width","px"),DIRECTION("Paragraph direction"),
    FORMAT("Character formatting"),PAINT("Fill and stroke"),INDENTS("Paragraph indents"),SPACING("Paragraph spacing")
}
internal data class TextEditScope(val range: TextSelectionRange?) {
    val selected get()=range!=null && !range.collapsed
    fun owner(control: TextControl): AnimPropertyType? {
        if(selected) return null
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
internal data class TextInspectorFocus(val tab: TextTab=TextTab.CHARACTER,val control: TextControl=TextControl.SIZE)
internal sealed interface TextInspectorMode {
    data object Browse: TextInspectorMode
    data object Source: TextInspectorMode
    data object Fonts: TextInspectorMode
    data class Popover(val group: TextControl): TextInspectorMode
    data class Numeric(val draft: TextNumericDraft): TextInspectorMode
    data class Colour(val control: TextControl): TextInspectorMode
}

/** Captures signed origin, scope and snapped playhead once; later preview never retargets a key. */
internal class TextNumericDraft(val layer: CompositionLayer,time: Long,frameRate: FrameRate,
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
    fun delta(control: TextControl,dx: Float,velocity: Float,fine: Boolean=false): Float {
        val wide=control in setOf(TextControl.SIZE,TextControl.BOX,TextControl.INDENT_LEFT,TextControl.INDENT_RIGHT,
            TextControl.INDENT_FIRST,TextControl.BEFORE,TextControl.AFTER,TextControl.LEADING,TextControl.BASELINE)
        val factor=if(wide) 1f+(MAX_ACCELERATION-1f)*((abs(velocity)-VELOCITY_START)/(VELOCITY_FULL-VELOCITY_START)).coerceIn(0f,1f) else 1f
        return dx/(if(control==TextControl.TRACKING || control==TextControl.MANUAL_KERNING) TRACKING_PIXELS_PER_UNIT else PIXELS_PER_UNIT)*factor*(if(fine) FINE else 1f)
    }
}

internal object TextInspectorBudget {
    // Text mode removes all old parent chrome; main inspector never scrolls.
    const val PARENT_CHROME=0f
    fun bodyHeight(total: Float,fontScale: Float): Float=(total-PARENT_CHROME-48f-32f*fontScale-40f-48f-4f).coerceAtLeast(0f)
    fun columns(width: Float,fontScale: Float)=if(fontScale>1.2f) 3 else if(width>=600f) 6 else 4
    fun needsOverlay(total: Float,fontScale: Float)=bodyHeight(total,fontScale)<96f
}
