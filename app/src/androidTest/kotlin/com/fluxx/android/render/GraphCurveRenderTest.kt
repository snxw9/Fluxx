package com.fluxx.android.render

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.engine.TextDebugBridge
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/**
 * GRAPH G1 on device: a solid layer whose Position segment uses a Custom (overshooting) or Bounce curve renders, frame
 * by frame, exactly like a static layer placed at the evaluator's value for that frame (production offscreen renderer,
 * pre-encode pixels), and differs from the Linear curve mid-segment. Writes no files.
 */
@RunWith(AndroidJUnit4::class)
class GraphCurveRenderTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val comp = Composition(width = 320, height = 240, durationUs = 2_000_000)

    private fun animated(curve: EasingCurve) = CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(0, 0, 2_000_000),
        solidColorArgb = 0xffff0000.toInt(), transform = Transform(scaleX = .15f, scaleY = .15f),
        animTransform = AnimatableTransform(scale = AnimatableProperty2D(staticX = .15f, staticY = .15f),
            position = AnimatableProperty2D(true, keyframes = FrozenList.of(listOf(Keyframe2D(0, -.6f, -.3f).withCurve(curve), Keyframe2D(2_000_000, .6f, .3f))))))
    private fun project(layer: CompositionLayer) = ProjectDocument(comp.copy(layers = FrozenList.of(listOf(layer))))

    @Test fun customAndBounceFramesMatchTheEvaluator() {
        CompositionRenderer(context, MediaRepository(context)).use { a ->
            CompositionRenderer(context, MediaRepository(context)).use { b ->
                val x = ByteBuffer.allocateDirect(320 * 240 * 4); val y = ByteBuffer.allocateDirect(320 * 240 * 4)
                fun pixels(r: CompositionRenderer, p: ProjectDocument, t: Long, out: ByteBuffer) {
                    r.render(p, t); out.clear(); assertTrue(TextDebugBridge.pixels(r.session, out)); out.rewind()
                }
                for (curve in listOf(EasingCurve.bezier(.34f, 1.56f, .64f, 1f), EasingCurve.bounce(3f, .5f))) {
                    val layer = animated(curve)
                    var differsFromLinear = false
                    for (frame in 0..60 step 3) {
                        val t = comp.frameRate.frameTimeUs(frame.toLong())
                        val evaluated = layer.evaluatedTransform(t)
                        val static = layer.copy(transform = evaluated, animTransform = AnimatableTransform(
                            position = AnimatableProperty2D(staticX = evaluated.positionX, staticY = evaluated.positionY),
                            scale = AnimatableProperty2D(staticX = .15f, staticY = .15f)))
                        pixels(a, project(layer), t, x); pixels(b, project(static), t, y)
                        assertEquals("${curve.type} frame $frame", x, y)
                        if (frame in 9..51) {
                            pixels(b, project(animated(EasingCurve.LINEAR)), t, y)
                            if (x != y) differsFromLinear = true
                        }
                    }
                    assertTrue("${curve.type} moves differently from Linear", differsFromLinear)
                }
            }
        }
    }
}
