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
            fun bounds(tag: String)=compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            val body=bounds("text-inspector-body");val header=bounds("text-inspector-header");val bar=bounds("text-bottom-bar")
            assertEquals(40f,header.height/pixelsPerDp,1f)
            // 268 → body 212, 328 → body 272 (frame 16 + header 40).
            assertEquals(if(e) 212f else 272f,body.height/pixelsPerDp,1f)
            assertTrue(header.bottom<=body.top+1f && bar.bottom<=body.bottom+1f)
            val tile=if(e) 36f else 44f
            for(block in listOf("text-left-block","text-right-block")) {
                val rows=(0 until 3).map {i->bounds("$block-row-$i")}
                rows.forEach {r->assertTrue("$block row is a full touch target",r.height/pixelsPerDp>=40f-1f);assertTrue(r.top>=body.top-1f && r.bottom<=bar.top+1f)}
                assertEquals(3*tile+2*4f+8f,(rows.last().bottom-rows.first().top)/pixelsPerDp,1.5f)
            }
            assertTrue(compose.onAllNodesWithTag("text-explode-pill").fetchSemanticsNodes().isNotEmpty())
            assertTrue(compose.onAllNodesWithTag("text-order-switch").fetchSemanticsNodes().isNotEmpty())
            assertTrue(compose.onAllNodesWithTag("inspector-rail").fetchSemanticsNodes().isNotEmpty())
            // No Done/✓, no − / +, no header ⋮ text menu anywhere on the page.
            for(gone in listOf("Done","−","+","Layer tools")) assertTrue(gone,compose.onAllNodesWithText(gone).fetchSemanticsNodes().isEmpty())
            assertTrue(compose.onAllNodesWithTag("text-menu").fetchSemanticsNodes().isEmpty())
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
