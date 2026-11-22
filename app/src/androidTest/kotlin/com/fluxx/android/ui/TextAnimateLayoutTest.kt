package com.fluxx.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import com.fluxx.android.ui.inspector.InspectorSheetBudget
import com.fluxx.android.ui.inspector.TextInspectorPanel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * TEXT F3, source-only: real Compose bounds of the Animate page at 360dp inside the shared inspector frame (268 and 328,
 * font scale 1 and 1.3). Edits go through the real reducer so the add tiles really add. Not a claim of device acceptance.
 */
class TextAnimateLayoutTest {
    @get:Rule val compose=createComposeRule()
    private val start=EditorState(ProjectDocument(com.fluxx.android.model.Composition(durationUs=4_000_000,layers=FrozenList.of(listOf(
        CompositionLayer(1,LayerType.TEXT,timing=ClipTiming(durationUs=4_000_000),text=TextProperties(source=AnimatableString(staticValue="Animate me"))))))),1)

    @Composable private fun Frame(state: MutableState<EditorState>,expanded: Boolean,scale: Float) {
        val height=InspectorSheetBudget.height(expanded,scale)
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if(InspectorSheetBudget.compact(height)) 40.dp else 48.dp) {
            MaterialTheme {Box(Modifier.requiredSize(360.dp,height.dp).padding(horizontal=12.dp,vertical=8.dp)) {
                TextInspectorPanel(state.value.selectedLayer!!,0,FrameRate(),onAction={state.value=EditorReducer.reduce(state.value,it)},onFocus={},onSourceBegin={},
                    onSourceDraft={_,_,_->},onFinish={},onCancel={},onValueBegin={},onPayloadPreview={},onCharacter={_,_,_->},onParagraph={_,_,_->},onBox={},
                    compositionWidth=1080,selection=null,onSelection={},error=null,onTools={},onExplodePrepare={_,_->},onExplodeCommit={_,_->},compositionHeight=1920)
            }}
        }
    }
    private fun bounds(tag: String): Rect=compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun all(prefix: String)=compose.onAllNodesWithTag(prefix,useUnmergedTree=false).fetchSemanticsNodes()

    private fun checkInside(tags: List<String>,body: Rect,ppd: Float,minTarget: Float) {
        for(tag in tags) {
            val r=bounds(tag)
            assertTrue("$tag inside the body ($r vs $body)",r.top>=body.top-1f && r.bottom<=body.bottom+1f && r.left>=body.left-1f && r.right<=body.right+1f)
            assertTrue("$tag is a full touch target",r.height/ppd>=minTarget-1f && r.width/ppd>=minTarget-1f)
        }
    }

    private fun run(expanded: Boolean,scale: Float) {
        val state=mutableStateOf(start)
        var ppd=1f
        compose.setContent {ppd=LocalDensity.current.density;CompositionLocalProvider(LocalDensity provides Density(ppd,scale)) {Frame(state,expanded,scale)}}
        compose.onNodeWithTag("text-tab-ANIMATE").performClick();compose.waitForIdle()
        val body=bounds("text-inspector-body")
        if(scale==1f) assertEquals(if(expanded) 212f else 272f,body.height/ppd,1f)
        val target=if(InspectorSheetBudget.compact(InspectorSheetBudget.height(expanded,scale))) 40f else 48f
        // Empty state: one add tile, an icon hint, no paragraph text.
        compose.onNodeWithTag("animate-empty").assertExists()
        checkInside(listOf("animate-add-animator"),body,ppd,target)
        compose.onNodeWithTag("animate-add-animator").performClick();compose.waitForIdle()
        assertEquals(1,state.value.selectedLayer!!.text.animators.size)
        // Properties: add three properties through the popover, then every tile sits inside the body.
        for(p in listOf(AnimatorProperty.POSITION,AnimatorProperty.OPACITY,AnimatorProperty.FILL)) {
            compose.onNodeWithTag("animate-add-property").performClick();compose.waitForIdle()
            compose.onNodeWithTag("animate-add-${p.name}").performClick();compose.waitForIdle()
        }
        assertEquals(3,state.value.selectedLayer!!.text.animators[0].properties.size)
        checkInside(listOf("animate-animator-0","animate-add-animator","animate-tab-PROPERTIES","animate-tab-SELECTORS","animate-grouping","animate-enabled",
            "animate-property-POSITION","animate-property-OPACITY","animate-property-FILL","animate-add-property"),body,ppd,target)
        val strip=bounds("animate-animator-strip");val switch=bounds("animate-switch");val grid=bounds("animate-properties")
        assertTrue(strip.bottom<=switch.top+1f && switch.bottom<=grid.top+1f && grid.bottom<=body.bottom+1f)
        // Selectors: the range handle strip and the selector face fit; More holds the rest.
        compose.onNodeWithTag("animate-tab-SELECTORS").performClick();compose.waitForIdle()
        checkInside(listOf("animate-selector-0","animate-add-selector","animate-range-strip","animate-field-SHAPE","animate-field-BASED_ON","animate-field-MODE",
            "animate-field-AMOUNT"),body,ppd,target-8f)
        val controls=bounds("animate-selector-controls")
        assertTrue("selector controls inside the body",controls.bottom<=body.bottom+1f)
        // Wiggly: add one; its whole face (no strip) fits.
        compose.onNodeWithTag("animate-add-selector").performClick();compose.waitForIdle()
        compose.onNodeWithTag("animate-add-WIGGLY").performClick();compose.waitForIdle()
        assertEquals(2,state.value.selectedLayer!!.text.animators[0].selectors.size)
        compose.onNodeWithTag("animate-selector-1").performClick();compose.waitForIdle()
        checkInside(listOf("animate-field-MAX_AMOUNT","animate-field-WIGGLES_PER_SECOND","animate-field-SEED","animate-field-ENABLED"),body,ppd,target)
        // No Done / ✓, no − / + text buttons anywhere on the page.
        for(gone in listOf("Done","−","+")) assertTrue(gone,compose.onAllNodesWithText(gone).fetchSemanticsNodes().isEmpty())
        assertTrue(all("inspector-rail").isNotEmpty())
    }

    @Test fun animatePageFitsAt268Scale1()=run(true,1f)
    @Test fun animatePageFitsAt328Scale1()=run(false,1f)
    @Test fun animatePageFitsAt268Scale13()=run(true,1.3f)
    @Test fun animatePageFitsAt328Scale13()=run(false,1.3f)

    @Test fun positionOpensTheTransformControlsInPlaceAndBackReturns() {
        val state=mutableStateOf(start)
        compose.setContent {Frame(state,true,1f)}
        compose.onNodeWithTag("text-tab-ANIMATE").performClick()
        compose.onNodeWithTag("animate-add-animator").performClick()
        compose.onNodeWithTag("animate-add-property").performClick()
        compose.onNodeWithTag("animate-add-POSITION").performClick()
        compose.onNodeWithTag("animate-property-POSITION").performClick();compose.waitForIdle()
        val body=bounds("text-inspector-body");val pad=bounds("animate-position-pad")
        assertTrue("pad inside the body",pad.top>=body.top-1f && pad.bottom<=body.bottom+1f)
        compose.onNodeWithTag("animate-subpage-back").performClick();compose.waitForIdle()
        compose.onNodeWithTag("animate-properties").assertExists()
    }
}
