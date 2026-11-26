package com.fluxx.android.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import com.fluxx.android.render.TextMetricsRepository
import com.fluxx.android.ui.preview.PreviewLayerOverlay
import com.fluxx.android.ui.preview.ViewSettings
import com.fluxx.android.ui.theme.FluxxTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * TLP.2 on the A16 (fixture compositions, never the user's projects; Compose test input only): the ONE red selector
 * box (no layer handles while it shows), the key bar in the sheet header, drag snapping and the drag indicators.
 */
class TlpTwoUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun settle() { Thread.sleep(500); compose.waitForIdle() }
    private fun state(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    @Test fun oneRedBoxOnFocusNoHandlesAndBlurRestoresTheLayerBoxWithHandles() {
        // The real preview overlay with real text metrics: selector 2 covers the first half of "Selector box".
        val text = TextProperties(source = AnimatableString(staticValue = "Selector box"), animators = FrozenList.of(listOf(
            TextAnimator(1, selectors = FrozenList.of(listOf(TextRangeSelector(2, start = AnimatableProperty1D(staticValue = 0f),
                end = AnimatableProperty1D(staticValue = 50f)))),
                properties = FrozenList.of(listOf(TextPropertyOverride(AnimatorProperty.TRACKING, scalar = AnimatableProperty1D(staticValue = 50f))))))))
        val layer = CompositionLayer(7, LayerType.TEXT, timing = ClipTiming(durationUs = 4_000_000), text = text)
        val project = ProjectDocument(Composition(width = 1080, height = 1920, durationUs = 4_000_000, layers = FrozenList.of(listOf(layer))))
        val focus = mutableStateOf<AnimatorTrackAddress?>(null)
        compose.setContent { FluxxTheme { PreviewLayerOverlay(project, layer, 0, null, ViewSettings(), false, focus.value) } }
        settle()
        assertEquals("Layer box with handles", state("preview-layer-overlay"))
        // The box is the covered half: narrower than the layer box, in the same layout space.
        val metrics = runBlocking { TextMetricsRepository.get(context).measure(text.fontId, text.source.evaluateDocument(0), text.effectiveLigatures(0), text) }
        val box = metrics.selectorBox(text, 0, 1, 2)!!
        assertTrue(box.width > 0f && box.width < metrics.bounds(text, 0).width)
        // Focus a selector field: only the red box (no handles).
        focus.value = AnimatorTrackAddress(1, selectorId = 2, field = SelectorField.START)
        compose.waitUntil(5_000) { state("preview-layer-overlay") == "Selector box" }
        // Blur to an animator property field: the layer box and its handles return; so with no focus at all.
        focus.value = AnimatorTrackAddress(1, property = AnimatorProperty.TRACKING); settle()
        assertEquals("Layer box with handles", state("preview-layer-overlay"))
        focus.value = AnimatorTrackAddress(1, selectorId = 2); compose.waitUntil(5_000) { state("preview-layer-overlay") == "Selector box" }
        focus.value = null; settle()
        assertEquals("Layer box with handles", state("preview-layer-overlay"))
    }
}
