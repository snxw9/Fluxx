package com.fluxx.android.ui

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.editor.EditorReducer
import com.fluxx.android.editor.EditorState
import com.fluxx.android.editor.TextAnimatorEdits
import com.fluxx.android.engine.RenderBridge
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import com.fluxx.android.render.CompositionRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * MP1 B3: the culling DIFFERENTIAL golden test. Every scene is rendered offscreen twice, with culling forced OFF and ON,
 * at the same times, and the composited output (the renderer's own readback, as export reads it) must be BYTE-IDENTICAL.
 * Each scene also reports how many layers the ON renderer culled and how many video decodes it skipped, so a pass is
 * never vacuous. Parenting does not exist in the model (no parent layers to test).
 */
class Mp1CullGoldenTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun report(line: String) {
        android.util.Log.i("MP1", line)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("MP1", line) })
    }
    private val w = 640; private val h = 360; private val dur = 2_000_000L
    private lateinit var f: File; private lateinit var m: Aud1Bench.Media

    private fun video(id: Long, x: Float, y: Float, s: Float, sy: Float = s, rot: Float = 0f, opacity: Float = 1f, z: Int = 10, inUs: Long = 10_000_000) =
        CompositionLayer(id, LayerType.VIDEO, name = "v$id", zOrder = z, timing = ClipTiming(0, inUs, dur),
            transform = Transform(x, y, s, sy, rot, opacity),
            asset = AssetReference("mp1-golden-$id", android.net.Uri.fromFile(f).toString(), access = MediaAccess.AVAILABLE, durationUs = m.durationUs))
    private fun solid(id: Long, argb: Int, x: Float, y: Float, s: Float, sy: Float = s, rot: Float = 0f, opacity: Float = 1f, z: Int = 5) =
        CompositionLayer(id, LayerType.SOLID, name = "s$id", zOrder = z, timing = ClipTiming(0, 0, dur), solidColorArgb = argb,
            transform = Transform(x, y, s, sy, rot, opacity))
    private fun text(id: Long, x: Float, y: Float, stroke: Float, z: Int = 20) =
        CompositionLayer(id, LayerType.TEXT, name = "t$id", zOrder = z, timing = ClipTiming(0, 0, dur), transform = Transform(x, y),
            text = TextProperties(source = AnimatableString(staticValue = "Cull me"), strokeWidth = AnimatableProperty1D(staticValue = stroke)))
    private fun moved(l: CompositionLayer, vararg keys: Pair<Long, Float>) = l.copy(animTransform = l.animTransform.copy(position =
        AnimatableProperty2D(true, keyframes = FrozenList.of(keys.map { (t, x) -> Keyframe2D(t, x, l.transform.positionY, EasingPreset.LINEAR) }))))
    private fun scene(vararg layers: CompositionLayer) =
        ProjectDocument(Composition(width = w, height = h, durationUs = dur, frameRate = FrameRate(30, 1),
            layers = FrozenList.of(listOf(solid(1, 0xff404040.toInt(), 0f, 0f, 1f, z = 0)) + layers)))

    /** A text layer whose animator offsets its characters (bounds cannot be proven: never culled on position). */
    private fun animatedText(id: Long, x: Float): ProjectDocument {
        var state = EditorState(scene(text(id, x, 0f, 0f)), id)
        fun t() = state.project.composition.layers.first { it.id == id }.text
        var serial = 900L; val ids = TextAnimatorEdits.Ids { serial++ }
        state = EditorReducer.reduce(state, TextAnimatorEdits.action(id, listOf(TextAnimatorEdits.addAnimator(t(), ids))))
        val a = t().animators[0].id
        state = EditorReducer.reduce(state, TextAnimatorEdits.action(id, listOfNotNull(TextAnimatorEdits.addProperty(t(), a, AnimatorProperty.POSITION))))
        state = EditorReducer.reduce(state, TextAnimatorEdits.action(id, listOf(TextAnimatorEdits.set(t(), TextAnimatorEdits.property(a, AnimatorProperty.POSITION), 0, 900f))))
        return state.project
    }

    private class Frame(val y: ByteBuffer, val u: ByteBuffer, val v: ByteBuffer)
    private fun read(r: CompositionRenderer): Frame {
        val fr = Frame(ByteBuffer.allocateDirect(w * h), ByteBuffer.allocateDirect(w * h / 4), ByteBuffer.allocateDirect(w * h / 4))
        check(RenderBridge.copyYuv(r.session, fr.y, fr.u, fr.v, w, w / 2, w / 2, 1, 1)) { "readback failed" }
        return fr
    }
    private fun same(a: Frame, b: Frame) = a.y == b.y && a.u == b.u && a.v == b.v

    @Test fun cullingIsPixelIdentical() {
        TestDeviceState.record(javaClass.simpleName)
        f = Aud2Bench.find(context, Aud2Bench.TYPICAL) ?: run { assumeTrue("MP1: typical clip not found (${Aud2Bench.lookupLog})", false); return }
        m = Aud1Bench.media(f)
        val times = longArrayOf(0, 500_000, 1_000_000, 1_966_666)
        val entry = (12..18).map { FrameRate(30, 1).frameTimeUs(it.toLong()) }.toLongArray()   // 0.4 .. 0.6 s
        val every = (0 until 60).map { FrameRate(30, 1).frameTimeUs(it.toLong()) }.toLongArray()
        val scenes = listOf(
            Triple("scale 0, both axes", scene(solid(2, 0xffff0000.toInt(), 0f, 0f, 0f), video(3, 0f, 0f, 0f), solid(4, 0xff00ff00.toInt(), .5f, .5f, .3f)), times),
            Triple("scale 0, one axis", scene(solid(2, 0xffff0000.toInt(), 0f, 0f, 0f, .5f), video(3, 0f, 0f, .5f, 0f)), times),
            Triple("fully outside (video, solid)", scene(video(2, 3f, 0f, .5f), solid(3, 0xffff0000.toInt(), 0f, -3f, .5f), solid(4, 0xff00ff00.toInt(), 0f, 0f, .4f)), times),
            Triple("opacity 0", scene(video(2, 0f, 0f, .5f, opacity = 0f), solid(3, 0xffff0000.toInt(), 0f, 0f, .5f, opacity = 0f)), times),
            Triple("animating in from outside (around the entry)", scene(moved(video(2, 3f, 0f, .5f), 0L to 3f, 1_000_000L to 0f),
                moved(solid(3, 0xffff00ff.toInt(), 3f, .5f, .3f), 0L to 3f, 1_000_000L to 0f)), entry),
            Triple("touching the edge, partly outside", scene(solid(2, 0xffff0000.toInt(), 2f, 0f, 1f), video(3, 1.5f, 0f, 1f), solid(4, 0xff0000ff.toInt(), 0f, -2f, .5f, 1f)), times),
            Triple("rotation 90 degrees", scene(video(2, 0f, 0f, .5f, rot = 90f), solid(3, 0xffff0000.toInt(), .8f, 0f, .5f, .2f, rot = 90f)), times),
            Triple("text with a stroke (outside / partly in)", scene(text(2, 5f, 0f, 6f), text(3, .9f, 0f, 6f), text(4, 0f, -2f, 0f)), times),
            Triple("text with an animator, layer outside", animatedText(2, -2.2f), times),
            Triple("stacked, with and without culled layers", scene(video(2, 0f, 0f, .6f, z = 10), solid(3, 0xffff0000.toInt(), 4f, 0f, 1f, z = 11),
                solid(4, 0x80ffffff.toInt(), .2f, .2f, .5f, z = 12, opacity = .5f), video(5, -3f, 0f, .5f, z = 13, inUs = 20_000_000)), times),
            Triple("video culled for 1 s, then re-entering (every frame)", scene(video(2, -.4f, 0f, .5f), moved(video(3, .4f, 0f, .5f, inUs = 30_000_000),
                0L to .4f, 400_000L to .4f, 500_000L to 4f, 1_400_000L to 4f, 1_500_000L to .4f)), every))
        report("B3 golden: parenting does not exist in the model: the hidden-parent scene is not applicable")
        var total = 0; var mismatches = 0
        CompositionRenderer(context, MediaRepository(context)).use { off ->
            CompositionRenderer(context, MediaRepository(context)).use { on ->
                off.cullingEnabled = false; on.cullingEnabled = true
                val stats = LongArray(3)
                for ((name, project, ts) in scenes) {
                    on.cullStats(stats); var bad = 0
                    for (t in ts) {
                        off.render(project, t); val a = read(off)
                        on.render(project, t); val b = read(on)
                        if (!same(a, b)) bad++
                    }
                    on.cullStats(stats)
                    total += ts.size; mismatches += bad
                    report("B3 golden '$name': ${ts.size} frames, ${if (bad == 0) "byte-identical" else "$bad DIFFERENT"}; ON culled ${stats[0]} layer-frames, skipped ${stats[1]} video decodes, ${stats[2]} warm decodes")
                }
            }
        }
        report("B3 golden result: $total frames compared, $mismatches different: ${if (mismatches == 0) "PASS" else "FAIL"}")
        assertEquals("frames that differ with culling on", 0, mismatches)
        assertTrue(total > 0)
    }
}
