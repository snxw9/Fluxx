package com.fluxx.android.ui.timeline

import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test

/** Timeline layer-bar keyframe markers: every keyed property contributes (text included), dedupe and quiet styling. */
class LayerKeyMarkersTest {
    private val textProperties=listOf(AnimPropertyType.FONT_SIZE,AnimPropertyType.FILL_COLOUR,AnimPropertyType.SOURCE_TEXT,
        AnimPropertyType.TRACKING,AnimPropertyType.LEADING,AnimPropertyType.BASELINE_SHIFT,AnimPropertyType.STROKE_COLOUR,
        AnimPropertyType.STROKE_WIDTH,AnimPropertyType.STROKE_OVER_FILL,AnimPropertyType.LIGATURES)
    private fun textLayer()=CompositionLayer(1,LayerType.TEXT,text=TextProperties(source=AnimatableString(staticValue="Text")))
    private fun keyed(layer: CompositionLayer,property: AnimPropertyType,at: Long): CompositionLayer {
        val editor=EditorViewModel()
        editor.dispatch(EditorAction.Load(ProjectDocument(Composition(layers=FrozenList.of(listOf(layer))))))
        editor.dispatch(EditorAction.ToggleAnimated(layer.id,property,true,at))
        return editor.state.project.composition.layers.single()
    }

    @Test fun everyAnimatableTextPropertyContributesToTheLayerMarkers() {
        // The TEXT UI 6 bar only read position/scale/rotation/opacity + source/size/fill: tracking, leading, baseline,
        // stroke colour/width, paint order and ligature keys never showed on the layer. Each must now contribute.
        assertEquals(AnimPropertyType.entries.drop(4).toSet(),textProperties.toSet())
        for(property in textProperties) {
            val layer=keyed(textLayer(),property,250_000)
            val keys=layer.propertyKeyTimes(property)
            assertTrue("$property is keyed",keys.isNotEmpty())
            assertTrue("$property key reaches the layer aggregation",LayerKeyMarkers.localTimes(layer).containsAll(keys))
            val offset=layer.resolvedKeyframeAnchorUs-layer.timing.startUs
            assertTrue("$property key is drawn on the bar",LayerKeyMarkers.clipTimes(layer,offset).containsAll(keys.map {it+offset}))
        }
    }
    @Test fun styleRunAndParagraphKeysArriveThroughSourceText() {
        val base=textLayer()
        val doc=base.text.source.sourceDocument.format(0,2) {it.copy(fauxBold=true)}
        val styled=keyed(base.copy(text=base.text.copy(source=AnimatableString(staticValue=doc.text,document=doc))),AnimPropertyType.SOURCE_TEXT,0)
        assertTrue(styled.text.source.isAnimated)
        assertEquals(listOf(-styled.resolvedKeyframeAnchorUs),LayerKeyMarkers.localTimes(styled))
    }
    @Test fun transformLayersShowAllFourPropertiesAndNothingElse() {
        var layer=CompositionLayer(2,LayerType.SOLID)
        layer=keyed(layer,AnimPropertyType.POSITION,0)
        layer=keyed(layer,AnimPropertyType.OPACITY,500_000)
        val times=LayerKeyMarkers.localTimes(layer)
        assertTrue(times.containsAll(listOf(0L-layer.resolvedKeyframeAnchorUs,500_000L-layer.resolvedKeyframeAnchorUs)))
    }
    @Test fun overlappingKeysCollapseAndFocusedWins() {
        val markers=LayerKeyMarkers.markers(listOf(0L,100L,100L,300L),setOf(100L))
        assertEquals(listOf(0L,100L,300L),markers.map {it.clipTimeUs})
        assertEquals(listOf(false,true,false),markers.map {it.focused})
        // A focused key the aggregation has not seen yet (e.g. mid-drag) is still drawn, at full ink.
        assertTrue(LayerKeyMarkers.markers(listOf(0L),setOf(50L)).single {it.clipTimeUs==50L}.focused)
    }
    @Test fun selectedPropertyIsFullInkAndOthersAreQuiet() {
        val focused=LayerKeyMarkers.Marker(0,true);val quiet=LayerKeyMarkers.Marker(0,false)
        assertEquals(1f,focused.alpha,0f);assertEquals(LayerKeyMarkers.FOCUSED_DP,focused.sizeDp,0f)
        // "Not super obvious but noticeable": 40–50% alpha and slightly smaller.
        assertTrue(quiet.alpha in .4f..0.5f);assertTrue(quiet.sizeDp<focused.sizeDp && quiet.sizeDp>=6f)
        val layer=keyed(keyed(CompositionLayer(3,LayerType.SOLID),AnimPropertyType.POSITION,0),AnimPropertyType.SCALE,400_000)
        val offset=layer.resolvedKeyframeAnchorUs-layer.timing.startUs
        val drawn=LayerKeyMarkers.markers(LayerKeyMarkers.clipTimes(layer,offset),LayerKeyMarkers.focusedClipTimes(layer,AnimPropertyType.SCALE,offset))
        assertEquals(1,drawn.count {it.focused});assertEquals(2,drawn.size)
        assertTrue(LayerKeyMarkers.focusedClipTimes(layer,null,offset).isEmpty())
    }
}
