package com.fluxx.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.fluxx.android.model.*
import com.fluxx.android.ui.inspector.TextFeedback
import com.fluxx.android.ui.inspector.TextInspectorBudget
import com.fluxx.android.ui.inspector.TextInspectorPanel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Source-only: real Compose bounds at 360dp, not a claim of device acceptance. */
class TextInspectorLayoutTest {
    @get:Rule val compose=createComposeRule()
    @Test fun fixedHeightFitsEveryRowAtStandardAndLargeFontScalesWithoutScrolling() {
        val scale=mutableFloatStateOf(1f)
        var pixelsPerDp=1f
        compose.setContent {
            val density=LocalDensity.current
            pixelsPerDp=density.density
            CompositionLocalProvider(LocalDensity provides Density(density.density,scale.floatValue)) {
                MaterialTheme {Box(Modifier.requiredSize(360.dp,TextInspectorBudget.sheetHeight(scale.floatValue).dp)) {
                    TextInspectorPanel(CompositionLayer(1,LayerType.TEXT,text=TextProperties(source=AnimatableString(staticValue="Text"))),0,FrameRate(),
                        onAction={},onFocus={},onSourceBegin={},onSourceDraft={_,_,_->},onFinish={},onCancel={},onValueBegin={},onPayloadPreview={},
                        onCharacter={_,_,_->},onParagraph={_,_,_->},onBox={},compositionWidth=1080,selection=null,onSelection={},error=null,onDismiss={},onTools={},
                        onExplodePrepare={_,_->},onExplodeCommit={_,_->})
                }}
            }
        }
        for(s in listOf(1f,1.3f,2f)) {
            compose.runOnIdle {scale.floatValue=s};compose.waitForIdle()
            val root=compose.onNodeWithTag("text-inspector").fetchSemanticsNode().boundsInRoot
            val body=compose.onNodeWithTag("text-inspector-body").fetchSemanticsNode().boundsInRoot
            val feedback=compose.onNodeWithTag("text-inspector-feedback").fetchSemanticsNode().boundsInRoot
            val rail=compose.onNodeWithTag("text-inspector-rail").fetchSemanticsNode().boundsInRoot
            assertEquals(360f,root.width/pixelsPerDp,1f)
            assertEquals(48f,rail.height/pixelsPerDp,1f);assertEquals(344f,rail.width/pixelsPerDp,1f)
            assertTrue(body.bottom<=feedback.top+1f && feedback.bottom<=rail.top+1f && rail.bottom<=root.bottom+1f)
            val rows=(0 until 6).mapNotNull {i->compose.onAllNodesWithTag("text-row-$i").fetchSemanticsNodes().firstOrNull()?.boundsInRoot}
            assertEquals(if(s>1.2f) 6 else 4,rows.size)
            rows.forEach {r->assertEquals(48f,r.height/pixelsPerDp,1f);assertTrue("row inside body at $s",r.top>=body.top-1f && r.bottom<=body.bottom+1f)}
            assertTrue(compose.onAllNodesWithTag("text-inspector-tabs").fetchSemanticsNodes().isNotEmpty())
        }
    }
    @Test fun worstStrokeClampLineFitsOneLineAtEachScale() {
        val widths=mutableMapOf<Float,Float>()
        var pixelsPerDp=1f
        compose.setContent {
            val base=LocalDensity.current;pixelsPerDp=base.density
            for(s in listOf(1f,1.3f,2f)) CompositionLocalProvider(LocalDensity provides Density(base.density,s)) {
                val measurer=rememberTextMeasurer()
                val text=requireNotNull(TextFeedback.strokeClamp(999.99f,"Apply · clamp 99.99–99.99 px",s))
                widths[s]=measurer.measure(text,MaterialTheme.typography.labelSmall,softWrap=false).size.width.toFloat()
            }
        }
        compose.waitForIdle()
        widths.forEach {(s,w)->assertTrue("scale $s width ${w/pixelsPerDp}dp",w/pixelsPerDp<=344f)}
        assertEquals(3,widths.size)
    }
}
