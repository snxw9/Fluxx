package com.fluxx.android.ui

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.fluxx.android.editor.*
import com.fluxx.android.model.*
import com.fluxx.android.ui.inspector.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * GRAPH G2.1 §9 on the A16: magnetic detents on the Transform controls (Rotation dial 5°, Opacity slider 25%, Scale
 * ruler 25%). Each preview is recorded: every previewed value is either ON a detent or outside every detent's zone
 * (locks inside, follows the finger outside, never skips), and typed values stay exact. Real EditorViewModel.
 */
class TransformDetentsTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var vm: EditorViewModel
    private val previews = mutableListOf<Transform>()
    private var previous: CurveLibraryStore? = null
    @Before fun memory() { previous = CurveLibraryStore.replaceForTest(CurveLibraryStore.inMemory()) }
    @After fun restore() { CurveLibraryStore.replaceForTest(previous) }

    private fun start() {
        vm = EditorViewModel()
        vm.dispatch(EditorAction.Load(ProjectDocument(com.fluxx.android.model.Composition(durationUs = 4_000_000, layers = FrozenList.of(listOf(
            CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(0, 0, 4_000_000))))))))
        vm.dispatch(EditorAction.Select(1))
        compose.setContent {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 48.dp) {
                MaterialTheme {
                    ElementInspectorSheet(vm.displayedState.selectedLayer!!, 1080, 1920, compositionDurationUs = 4_000_000, playheadUs = vm.displayedState.playheadUs,
                        onAction = vm::dispatch, onRename = {}, onDuplicate = {}, onDelete = {}, onDismiss = {},
                        onTransformBegin = vm::beginGesture, onTransformPreview = { previews += it; vm.editTransform(1, it, preview = true) },
                        onTransformCommit = { vm.commitGesture() }, onTransformCancel = { vm.cancelGesture() },
                        onTransformEdit = { vm.editTransform(1, it) }, onTimingChange = {}, onAudioChange = { _, _ -> },
                        onColorChange = {}, onGesturePreview = { vm.previewGesture(it) },
                        modifier = Modifier.requiredSize(360.dp, 328.dp).testTag("inspector-sheet"))
                }
            }
        }
        compose.waitForIdle()
    }
    private fun t() = vm.state.project.composition.layers[0].transform
    private fun click(tag: String) { compose.onNodeWithTag(tag, useUnmergedTree = true).performClick(); compose.waitForIdle() }
    /** Every value is on a detent (multiple of [step]) or at least [zone] (minus a hair) away from the nearest one. */
    private fun magnetic(values: List<Float>, step: Float, zone: (Float) -> Float, what: String) {
        var locked = 0; var free = 0
        for (v in values) {
            val d = Math.round(v / step) * step
            if (abs(v - d) < 1e-4f) locked++ else { assertTrue("$what $v is inside the zone of $d", abs(v - d) > zone(d) * .95f); free++ }
        }
        android.util.Log.d("GraphG21", "$what: ${values.size} previews, $locked on a detent, $free following the finger")
        assertTrue("$what locked somewhere", locked > 0); assertTrue("$what followed the finger somewhere", free > 0)
    }

    @Test fun rotationDialLocksEvery5DegreesAndTypedStaysExact() {
        start()
        compose.onNodeWithContentDescription("Transform").performClick(); compose.waitForIdle()
        click("transform-mode-ROTATION")
        val density = compose.density.density
        val r = (72f - 12f) * density
        previews.clear()
        compose.onNodeWithTag("rotation-dial").performTouchInput {
            fun p(deg: Float) = Offset(width / 2f + r * cos(Math.toRadians(deg.toDouble())).toFloat(), height / 2f + r * sin(Math.toRadians(deg.toDouble())).toFloat())
            down(p(0f)); var a = 0f
            while (a < 22f) { a += .25f; moveTo(p(a)) }
            up()
        }
        compose.waitForIdle()
        val values = previews.map { it.rotationDegrees }
        // The zone: 35% of the 5° spacing (the arc spacing is 5.2dp at this radius, finer than the 8dp finger zone).
        magnetic(values, 5f, { 1.75f }, "rotation")
        assertTrue(values.any { it == 5f } && values.any { it == 10f } && values.any { it == 15f })
        // Never skips: the free values include ones between the zones (e.g. 1.75..3.25 and 6.75..8.25).
        assertTrue(values.any { it in 1.8f..3.2f }); assertTrue(values.any { it in 6.8f..8.2f })
        assertEquals(values.last(), t().rotationDegrees, 1e-4f)
        // Typed: exact.
        compose.onNodeWithContentDescription("Rotation degrees", substring = true).performClick(); compose.waitForIdle()
        compose.onNodeWithContentDescription("Rotation degrees, exact value in °").performTextReplacement("7.3")
        compose.onNodeWithContentDescription("Rotation degrees, exact value in °").performImeAction(); compose.waitForIdle()
        assertEquals(7.3f, t().rotationDegrees, 0f)
    }

    @Test fun opacitySliderLocksEvery25PercentAndTypedStaysExact() {
        start()
        compose.onAllNodesWithContentDescription("Opacity", substring = true).onFirst().performClick(); compose.waitForIdle()
        val slider = compose.onNodeWithTag("section-slider-Opacity")
        val widthPx = slider.fetchSemanticsNode().size.width.toFloat()
        val zone = TransformDetents.zonePx(TransformDetents.ZONE_DP * compose.density.density, .25f * widthPx) / widthPx
        android.util.Log.d("GraphG21", "opacity slider ${widthPx / compose.density.density}dp: 25% detents ${(.25f * widthPx / compose.density.density)}dp apart, zone ${zone * 100}%")
        previews.clear()
        slider.performTouchInput { down(Offset(width * .30f, height / 2f)); var f = .30f; while (f < .80f) { f += .004f; moveTo(Offset(width * f, height / 2f)) }; up() }
        compose.waitForIdle()
        val values = previews.map { it.opacity }
        magnetic(values, .25f, { zone }, "opacity")
        assertTrue(values.any { it == .5f } && values.any { it == .75f })
        compose.onNodeWithContentDescription("Opacity percentage", substring = true).performClick(); compose.waitForIdle()
        compose.onNodeWithContentDescription("exact value in %", substring = true).performTextReplacement("52.3")
        compose.onNodeWithContentDescription("exact value in %", substring = true).performImeAction(); compose.waitForIdle()
        assertEquals(.523f, t().opacity, 1e-6f)
    }

    @Test fun scaleRulerLocksEvery25PercentAndTypedStaysExact() {
        start()
        compose.onNodeWithContentDescription("Transform").performClick(); compose.waitForIdle()
        click("transform-mode-SCALE")
        val density = compose.density.density
        previews.clear()
        // Slow drag (no velocity gain): dragging left raises the value (the tape follows the finger).
        compose.onNodeWithTag("scale-ruler-strip", useUnmergedTree = true).performTouchInput {
            down(Offset(width * .8f, height / 2f)); var x = width * .8f
            repeat(240) { x -= .5f * density; moveTo(Offset(x, height / 2f)); advanceEventTime(40) }
            up()
        }
        compose.waitForIdle()
        val values = previews.map { it.scaleX }
        val zone = { d: Float -> // doublings → value distance at the detent
            val z = TransformDetents.zonePx(TransformDetents.ZONE_DP * density, TransformDetents.scaleSpacingDoublings(d) * ScaleRulerMath.DP_PER_DOUBLING * density) /
                (ScaleRulerMath.DP_PER_DOUBLING * density)
            d * (Math.pow(2.0, z.toDouble()).toFloat() - 1f) }
        magnetic(values, .25f, zone, "scale")
        assertTrue(values.any { it == 1.25f })
        // Typed (the readout): exact.
        compose.onNodeWithContentDescription("Tap to type, double-tap", substring = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); compose.waitForIdle()
        compose.onNodeWithContentDescription("exact value in %", substring = true).performTextReplacement("123.4")
        compose.onNodeWithContentDescription("exact value in %", substring = true).performImeAction(); compose.waitForIdle()
        assertEquals(1.234f, t().scaleX, 1e-6f)
    }
}
