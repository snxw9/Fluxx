package com.fluxx.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.fluxx.android.model.*
import com.fluxx.android.ui.inspector.TextInspectorPanel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Source-only: real Compose bounds, not a claim of device acceptance. */
class TextInspectorLayoutTest {
    @get:Rule val compose=createComposeRule()
    @Test fun floorWidthBothHeightsAndAccessibilityScalesKeepRailWithinBudget() {
        val scale=mutableFloatStateOf(1f);val height=mutableFloatStateOf(268f)
        var pixelsPerDp=1f
        compose.setContent {
            val density=LocalDensity.current
            pixelsPerDp=density.density
            CompositionLocalProvider(LocalDensity provides Density(density.density,scale.floatValue)) {
                MaterialTheme {Box(Modifier.requiredSize(360.dp,(height.floatValue*scale.floatValue).dp)) {
                    TextInspectorPanel(CompositionLayer(1,LayerType.TEXT,text=TextProperties(source=AnimatableString(staticValue="Text"))),0,FrameRate(),
                        onAction={},onFocus={},onSourceBegin={},onSourceDraft={_,_,_->},onFinish={},onCancel={},onValueBegin={},onPayloadPreview={},
                        onCharacter={_,_,_->},onParagraph={_,_,_->},onBox={},compositionWidth=1080,selection=null,onSelection={},error=null,onDismiss={},onTools={},
                        onExplodePrepare={_,_->},onExplodeCommit={_,_->})
                }}
            }
        }
        for(s in listOf(1f,1.3f,2f)) for(h in listOf(268f,328f)) {
            compose.runOnIdle {scale.floatValue=s;height.floatValue=h};compose.waitForIdle()
            val root=compose.onNodeWithTag("text-inspector").fetchSemanticsNode().boundsInRoot
            val body=compose.onNodeWithTag("text-inspector-body").fetchSemanticsNode().boundsInRoot
            val rail=compose.onNodeWithTag("text-inspector-rail").fetchSemanticsNode().boundsInRoot
            assertEquals(360f,root.width/pixelsPerDp,1f)
            assertEquals(48f,rail.height/pixelsPerDp,1f)
            assertTrue(body.bottom<=rail.top);assertTrue(rail.bottom<=root.bottom+1f)
            assertEquals(344f,rail.width/pixelsPerDp,1f)
        }
    }
}
