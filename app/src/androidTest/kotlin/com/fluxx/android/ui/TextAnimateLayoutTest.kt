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
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import com.fluxx.android.ui.inspector.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * TEXT F3.1, source-only: real Compose bounds of the Animate list at 360dp inside the shared inspector frame (268 and
 * 328, font scale 1 and 1.3) with an animator expanded (Property groups, a selector expanded, Grouping row). The body
 * scrolls; every tile scrolled into view sits inside the body at full touch size. Edits go through the real reducer.
 * The controls preference uses an in-memory store (nothing is written to the app's files). Not a device acceptance claim.
 */
class TextAnimateLayoutTest {
    @get:Rule val compose=createComposeRule()
    private val start=EditorState(ProjectDocument(com.fluxx.android.model.Composition(durationUs=4_000_000,layers=FrozenList.of(listOf(
        CompositionLayer(1,LayerType.TEXT,timing=ClipTiming(durationUs=4_000_000),text=TextProperties(source=AnimatableString(staticValue="Animate me"))))))),1)
    private val memory=HashMap<String,String>()
    private val preference=AnimateControlsPreference(object: PreferenceStore {
        override fun get(key: String)=memory[key]; override fun put(key: String,value: String) { memory[key]=value } })
    private var previous: AnimateControlsPreference?=null
    @Before fun usePreferenceInMemory() { previous=AnimateControlsPreference.replaceForTest(preference) }
    @After fun restorePreference() { AnimateControlsPreference.replaceForTest(previous) }

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
    private fun click(tag: String) { compose.onNodeWithTag(tag).performClick();compose.waitForIdle() }
    private fun reveal(tag: String) { compose.onNodeWithTag("animate-list").performScrollToNode(hasTestTag(tag));compose.waitForIdle() }

    /** Scroll [tags] into view one by one: each is inside the body (no clipping) and a full touch target. */
    private fun checkReachable(tags: List<String>,body: Rect,ppd: Float,minTarget: Float) {
        for(tag in tags) {
            reveal(tag)
            val r=bounds(tag)
            assertTrue("$tag inside the body ($r vs $body)",r.top>=body.top-1f && r.bottom<=body.bottom+1f && r.left>=body.left-1f && r.right<=body.right+1f)
            assertTrue("$tag is a full touch target (${r.width/ppd} x ${r.height/ppd})",r.height/ppd>=minTarget-1f && r.width/ppd>=minTarget-1f)
        }
    }
    private fun text(state: MutableState<EditorState>)=state.value.selectedLayer!!.text

    private fun run(expanded: Boolean,scale: Float) {
        val state=mutableStateOf(start)
        var ppd=1f
        compose.setContent {ppd=LocalDensity.current.density;CompositionLocalProvider(LocalDensity provides Density(ppd,scale)) {Frame(state,expanded,scale)}}
        click("text-tab-ANIMATE")
        val body=bounds("text-inspector-body")
        if(scale==1f) assertEquals(if(expanded) 212f else 272f,body.height/ppd,1f)
        val target=if(InspectorSheetBudget.compact(InspectorSheetBudget.height(expanded,scale))) 40f else 48f
        val rail=bounds("inspector-rail")
        // Empty state: one centred add control over the persistent glyph.
        compose.onNodeWithTag("animate-glyph").assertExists()
        val add=bounds("animate-add-animator")
        assertEquals("add control is centred",(body.left+body.right)/2,(add.left+add.right)/2,4f*ppd)
        click("animate-add-animator")
        assertEquals(1,text(state).animators.size)
        compose.onNodeWithTag("animate-glyph").assertExists()
        // Property groups: Position and Opacity (Transform), Fill (Colour).
        for(p in listOf(AnimatorProperty.POSITION,AnimatorProperty.OPACITY,AnimatorProperty.FILL)) {
            reveal("animate-add-property");click("animate-add-property");click("animate-add-${p.name}")
        }
        assertEquals(3,text(state).animators[0].properties.size)
        checkReachable(listOf("animate-animator-0","animate-animator-0-grip","animate-animator-0-duplicate","animate-animator-0-eye","animate-add-property",
            "animate-group-Transform","animate-property-POSITION","animate-property-OPACITY","animate-group-Colour","animate-property-FILL"),body,ppd,target)
        // Range selector (expanded on add): primary tiles, then the Advanced group.
        val range=text(state).animators[0].selectors[0].id
        checkReachable(listOf("animate-selector-row","animate-add-selector","animate-selector-0","animate-field-START","animate-field-END","animate-field-OFFSET",
            "animate-field-SHAPE"),body,ppd,target)
        assertTrue(compose.onAllNodesWithTag("animate-field-AMOUNT").fetchSemanticsNodes().isEmpty())
        reveal("animate-advanced-$range");click("animate-advanced-$range")
        checkReachable(listOf("animate-field-UNITS","animate-field-BASED_ON","animate-field-MODE","animate-field-AMOUNT","animate-field-SMOOTHNESS",
            "animate-field-EASE_HIGH","animate-field-EASE_LOW","animate-field-RANDOMIZE_ORDER","animate-field-SEED"),body,ppd,target)
        for(hidden in listOf("animate-field-MIN_AMOUNT","animate-field-MAX_AMOUNT","animate-field-ENABLED"))
            assertTrue(hidden,compose.onAllNodesWithTag(hidden).fetchSemanticsNodes().isEmpty())
        // Wiggly: collapse Range, add Wiggly (expanded on add), primary and Advanced tiles reachable.
        reveal("animate-selector-0-toggle");click("animate-selector-0-toggle")
        reveal("animate-add-selector");click("animate-add-selector");click("animate-add-WIGGLY")
        assertEquals(2,text(state).animators[0].selectors.size)
        val wiggly=text(state).animators[0].selectors[1].id
        checkReachable(listOf("animate-selector-1","animate-field-MAX_AMOUNT","animate-field-MIN_AMOUNT","animate-field-WIGGLES_PER_SECOND","animate-field-CORRELATION"),body,ppd,target)
        reveal("animate-advanced-$wiggly");click("animate-advanced-$wiggly")
        checkReachable(listOf("animate-field-BASED_ON","animate-field-MODE","animate-field-TEMPORAL_PHASE","animate-field-SPATIAL_PHASE","animate-field-LOCK_DIMENSIONS",
            "animate-field-SEED"),body,ppd,target)
        assertTrue(compose.onAllNodesWithTag("animate-field-AMOUNT").fetchSemanticsNodes().isEmpty())
        // Grouping row at the end of the animator.
        checkReachable(listOf("animate-grouping-row","animate-grouping"),body,ppd,target)
        // The body scrolled (the add control is off screen) and the rail did not move.
        assertTrue(compose.onAllNodesWithTag("animate-add-animator").fetchSemanticsNodes().isEmpty() ||
            bounds("animate-add-animator").bottom<=body.top+1f)
        assertEquals(rail,bounds("inspector-rail"))
        compose.onNodeWithTag("animate-list").performScrollToIndex(0);compose.waitForIdle()
        // No Done / ✓, no − / + text buttons anywhere on the page.
        for(gone in listOf("Done","−","+")) assertTrue(gone,compose.onAllNodesWithText(gone).fetchSemanticsNodes().isEmpty())
    }

    @Test fun animatePageFitsAt268Scale1()=run(true,1f)
    @Test fun animatePageFitsAt328Scale1()=run(false,1f)
    @Test fun animatePageFitsAt268Scale13()=run(true,1.3f)
    @Test fun animatePageFitsAt328Scale13()=run(false,1.3f)

    @Test fun barIconsDuplicateAndEyeAndBothTransformControlStyles() {
        val state=mutableStateOf(start)
        compose.setContent {Frame(state,true,1f)}
        click("text-tab-ANIMATE");click("animate-add-animator")
        click("animate-add-property");click("animate-add-POSITION")
        // Default: sliders in an anchored popover.
        assertEquals(AnimateControls.SLIDERS,preference.value.value)
        reveal("animate-property-POSITION");click("animate-property-POSITION")
        compose.onNodeWithTag("animate-popover-animate-property-POSITION").assertExists()
        compose.onAllNodesWithTag("animate-position-pad").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        // Pad, dial, rulers: the same tile opens the Transform tab's pad in place of the list; back returns.
        preference.set(AnimateControls.PAD);compose.waitForIdle()
        compose.onNodeWithTag("animate-list").performScrollToIndex(0);compose.waitForIdle()
        reveal("animate-property-POSITION");click("animate-property-POSITION")
        val body=bounds("text-inspector-body");val pad=bounds("animate-position-pad")
        assertTrue("pad inside the body",pad.top>=body.top-1f && pad.bottom<=body.bottom+1f)
        click("animate-subpage-back")
        compose.onNodeWithTag("animate-list").assertExists()
        assertEquals("PAD",memory[AnimateControlsPreference.KEY])
        // Duplicate on the bar: one tap, a second animator with the same property.
        compose.onNodeWithTag("animate-list").performScrollToIndex(0);compose.waitForIdle()
        click("animate-animator-0-duplicate")
        assertEquals(2,text(state).animators.size)
        assertEquals(text(state).animators[0].properties,text(state).animators[1].properties)
        // Eye: animator off keeps its properties and selectors.
        val before=text(state).animators[0]
        click("animate-animator-0-eye")
        val after=text(state).animators[0]
        assertEquals(0f,after.enabled.staticValue,0f);assertEquals(before.properties,after.properties);assertEquals(before.selectors,after.selectors)
    }

    @Test fun characterOffsetAddsWithoutCrashing() {
        val state=mutableStateOf(start)
        compose.setContent {Frame(state,true,1f)}
        click("text-tab-ANIMATE");click("animate-add-animator")
        click("animate-add-property");click("animate-add-group-Advanced");click("animate-add-CHARACTER_OFFSET")
        click("animate-add-property");click("animate-add-group-Advanced");click("animate-add-CHARACTER_VALUE")
        val props=text(state).animators[0].properties.map { it.property }
        assertTrue(AnimatorProperty.CHARACTER_OFFSET in props && AnimatorProperty.CHARACTER_VALUE in props)
        reveal("animate-property-CHARACTER_OFFSET")
        compose.onNodeWithTag("animate-property-CHARACTER_OFFSET").assertExists()
    }
}
