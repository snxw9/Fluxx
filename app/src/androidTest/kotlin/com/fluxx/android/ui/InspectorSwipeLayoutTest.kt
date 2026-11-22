package com.fluxx.android.ui

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.fluxx.android.model.*
import com.fluxx.android.ui.inspector.ElementInspectorSheet
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * TEXT F3.3 §6: swipe down from the top of the inspector sheet closes it on every page and sub-page, including Edit
 * Text (Character, Paragraph, Animate), which had no swipe before. A swipe that starts in the body never closes it, and
 * the swipe does not trigger the header's tabs. Renders the real ElementInspectorSheet at the 328dp standard height.
 * (In the app a dismissed sheet leaves the screen; here each check starts from a fresh sheet.)
 */
class InspectorSwipeLayoutTest {
    @get:Rule val compose = createComposeRule()
    private var dismissed = 0
    private val generation = mutableIntStateOf(0)
    private val type = mutableStateOf(LayerType.TEXT)

    private fun start(layerType: LayerType) {
        type.value = layerType
        compose.setContent {
            val t = type.value
            // The video layer is a URI-less reference: nothing reads media.
            val layer = CompositionLayer(1, t, timing = ClipTiming(0, 0, 4_000_000),
                asset = if (t == LayerType.VIDEO) AssetReference("swipe-test", null, durationUs = 4_000_000) else null,
                text = if (t == LayerType.TEXT) TextProperties(source = AnimatableString(staticValue = "Swipe")) else TextProperties())
            MaterialTheme {
                key(generation.intValue) {
                    ElementInspectorSheet(layer, 1080, 1920, compositionDurationUs = 4_000_000, onRename = {}, onDuplicate = {}, onDelete = {},
                        onDismiss = { dismissed++ }, onTransformBegin = {}, onTransformPreview = {}, onTransformCommit = {}, onTransformCancel = {},
                        onTransformEdit = {}, onTimingChange = {}, onAudioChange = { _, _ -> }, onColorChange = {},
                        modifier = Modifier.requiredSize(360.dp, 328.dp).testTag("inspector-sheet"))
                }
            }
        }
        compose.waitForIdle()
    }
    private fun fresh() { compose.runOnIdle { generation.intValue++ }; compose.waitForIdle() }
    private fun click(tag: String) { compose.onNodeWithTag(tag).performClick(); compose.waitForIdle() }
    private fun tile(label: String) { compose.onNodeWithContentDescription(label).performClick(); compose.waitForIdle() }

    /** Swipe 140dp down from [startDp] below the sheet's top edge, at [xFraction] of its width. */
    private fun swipe(startDp: Float, xFraction: Float = .5f) {
        compose.onNodeWithTag("inspector-sheet").performTouchInput {
            val x = width * xFraction
            swipe(Offset(x, startDp * density), Offset(x, (startDp + 140f) * density), 300)
        }
        compose.waitForIdle()
    }
    /** On a fresh sheet opened with [open]: a body swipe does nothing, a top swipe closes, the page is unchanged. */
    private fun check(page: String, open: () -> Unit, selectedTag: String?, bodyStartDp: Float = 160f) {
        for (x in listOf(.15f, .5f, .85f)) {
            fresh(); open()
            val before = dismissed
            swipe(bodyStartDp, x)
            assertEquals("$page: a swipe from the body does not close", before, dismissed)
            swipe(20f, x)
            assertEquals("$page: swipe down from the top closes (x=$x)", before + 1, dismissed)
            selectedTag?.let { compose.onNodeWithTag(it).assertIsSelected() } // the swipe over a tab did not switch page
        }
    }

    @Test fun everyTextLayerPageClosesOnSwipeDownFromTheTop() {
        start(LayerType.TEXT)
        check("landing", {}, null)
        check("Transform", { tile("Transform") }, "inspector-tab-TRANSFORM")
        check("Timing", { tile("Timing") }, "inspector-tab-TIMING")
        check("Blending & Opacity", { tile("Blending & Opacity") }, "inspector-tab-BLENDING_OPACITY")
        // Edit Text: its own header (Character / Paragraph / Animate) — the page that had no swipe before F3.3.
        for (tab in listOf("CHARACTER", "PARAGRAPH", "ANIMATE"))
            check("Edit Text $tab", { tile("Edit Text"); click("text-tab-$tab") }, "text-tab-$tab")
    }

    @Test fun solidAndVideoPagesCloseOnSwipeDownFromTheTop() {
        start(LayerType.SOLID)
        check("Edit Solid", { tile("Edit Solid") }, "inspector-tab-CONTEXTUAL_EDIT", bodyStartDp = 120f)
        compose.runOnIdle { type.value = LayerType.VIDEO }
        check("Audio", { tile("Audio controls") }, "inspector-tab-AUDIO")
    }
}
