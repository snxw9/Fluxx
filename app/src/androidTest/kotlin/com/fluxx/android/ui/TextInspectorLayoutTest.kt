package com.fluxx.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.fluxx.android.model.*
import com.fluxx.android.ui.inspector.InspectorSheetBudget
import com.fluxx.android.ui.inspector.TextInspectorPanel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Source-only: real Compose bounds at 360dp inside the shared inspector frame, not a claim of device acceptance. */
class TextInspectorLayoutTest {
    @get:Rule val compose=createComposeRule()
    private val layer=CompositionLayer(1,LayerType.TEXT,text=TextProperties(source=AnimatableString(staticValue="Text")))

    @Composable private fun Frame(expanded: Boolean,scale: Float,selection: TextSelectionRange?=null,onSelection: (TextSelectionRange?)->Unit={}) {
        val height=InspectorSheetBudget.height(expanded,scale)
        // Same frame as ElementInspectorSheet: 12dp sides, 8dp top/bottom, 40/48dp targets by sheet height.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if(InspectorSheetBudget.compact(height)) 40.dp else 48.dp) {
            MaterialTheme {Box(Modifier.requiredSize(360.dp,height.dp).padding(horizontal=12.dp,vertical=8.dp)) {
                TextInspectorPanel(layer,0,FrameRate(),onAction={},onFocus={},onSourceBegin={},onSourceDraft={_,_,_->},onFinish={},onCancel={},onValueBegin={},onPayloadPreview={},
                    onCharacter={_,_,_->},onParagraph={_,_,_->},onBox={},compositionWidth=1080,selection=selection,onSelection=onSelection,error=null,onTools={},
                    onExplodePrepare={_,_->},onExplodeCommit={_,_->})
            }}
        }
    }

    @Test fun characterTabFitsBothSharedHeightsWithoutScrolling() {
        val expanded=mutableStateOf(true)
        var pixelsPerDp=1f
        compose.setContent {pixelsPerDp=LocalDensity.current.density;CompositionLocalProvider(LocalDensity provides Density(pixelsPerDp,1f)) {Frame(expanded.value,1f)}}
        for(e in listOf(true,false)) {
            compose.runOnIdle {expanded.value=e};compose.waitForIdle()
            val body=compose.onNodeWithTag("text-inspector-body").fetchSemanticsNode().boundsInRoot
            val header=compose.onNodeWithTag("text-inspector-header").fetchSemanticsNode().boundsInRoot
            val bar=compose.onNodeWithTag("text-bottom-bar").fetchSemanticsNode().boundsInRoot
            assertEquals(40f,header.height/pixelsPerDp,1f)
            assertTrue(header.bottom<=body.top+1f && bar.bottom<=body.bottom+1f)
            val pitch=if(e) 42f else 48f
            val rows=(0 until 5).map {i->compose.onNodeWithTag("text-row-$i").fetchSemanticsNode().boundsInRoot}
            rows.forEach {r->assertEquals(pitch,r.height/pixelsPerDp,1f);assertTrue(r.top>=body.top-1f && r.bottom<=bar.top+1f)}
            assertTrue(compose.onAllNodesWithTag("inspector-rail").fetchSemanticsNodes().isNotEmpty())
            // No Done/✓ and no − / + anywhere on the page.
            for(gone in listOf("Done","−","+","Layer tools")) assertTrue(gone,compose.onAllNodesWithText(gone).fetchSemanticsNodes().isEmpty())
        }
    }
    @Test fun largeFontScalesUseTheLabelledGridAndPageWholeRows() {
        val scale=mutableFloatStateOf(1.3f)
        var pixelsPerDp=1f
        compose.setContent {pixelsPerDp=LocalDensity.current.density;CompositionLocalProvider(LocalDensity provides Density(pixelsPerDp,scale.floatValue)) {Frame(false,scale.floatValue)}}
        for(s in listOf(1.3f,2f)) {
            compose.runOnIdle {scale.floatValue=s};compose.waitForIdle()
            val body=compose.onNodeWithTag("text-inspector-body").fetchSemanticsNode().boundsInRoot
            val rows=(0 until 10).mapNotNull {i->compose.onAllNodesWithTag("text-row-$i").fetchSemanticsNodes().firstOrNull()?.boundsInRoot}
            assertTrue(rows.isNotEmpty());rows.forEach {r->assertTrue("row inside body at $s",r.top>=body.top-1f && r.bottom<=body.bottom+1f)}
        }
    }
    @Test fun scopeChipOnlyWithSelection() {
        val selection=mutableStateOf<TextSelectionRange?>(null)
        compose.setContent {Frame(false,1f,selection.value) {selection.value=it}}
        assertTrue(compose.onAllNodesWithTag("text-scope-chip").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Whole layer",substring=true).fetchSemanticsNodes().isEmpty())
        compose.runOnIdle {selection.value=TextSelectionRange(0,2)}
        compose.onNodeWithTag("text-scope-chip").assertExists().performClick()
        compose.runOnIdle {assertNull(selection.value)}
    }
}
