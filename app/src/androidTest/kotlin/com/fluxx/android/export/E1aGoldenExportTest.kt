package com.fluxx.android.export

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.BuildConfig
import com.fluxx.android.model.*
import com.fluxx.android.render.AudioMixer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** User-run E1a harness. Uses the real exporter; no product UI or text subsystem. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class E1aGoldenExportTest {
    @Test fun exportFixedMixedProject() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val label = requireNotNull(InstrumentationRegistry.getArguments().getString("e1aLabel")) {
            "Set instrumentation argument e1aLabel to r26 or r28; see TEXT_LAYER.md"
        }
        require(label == "r26" || label == "r28")
        val expectedNdk = if (label == "r26") "26.1.10909125" else "28.2.13676358"
        assertEquals("Wrong toolchain for comparison label", expectedNdk, BuildConfig.NATIVE_NDK_VERSION)
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "e1a/$label")
        check(!directory.exists()) { "Archive/remove the previous $label results before rerunning" }
        check(directory.mkdirs())
        val assets = instrumentation.context.assets
        val source = File(directory, "source.mp4")
        assets.open("e1a/source.mp4").use { input -> source.outputStream().use { output -> input.copyTo(output) } }
        val manifest = assets.open("e1a/source.json").bufferedReader().use { JSONObject(it.readText()) }
        assertEquals(manifest.getString("sha256"), digest(source))
        File(directory, "source.json").writeText(manifest.toString(2))

        val image = File(directory, "checker.png")
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) for (x in 0 until width) {
                setPixel(x, y, if ((x / 8 + y / 8) % 2 == 0) 0xfffafafa.toInt() else 0xff222222.toInt())
            }
            image.outputStream().use { check(compress(Bitmap.CompressFormat.PNG, 100, it)) }
            recycle()
        }
        val project = fixture(source, image)
        // Hash the actual model with stable asset URIs; r26/r28 output directories differ.
        val canonicalProject = project.copy(composition = project.composition.copy(layers = FrozenList.of(
            project.composition.layers.map { layer ->
                layer.copy(asset = layer.asset?.let { it.copy(uri = "fixture://${it.id}") })
            }
        )))
        File(directory, "project.fluxx").writeBytes(ProjectCodec.encode(project))
        val pcm = File(directory, "mix.s16le")
        AudioMixer.mix(context, project, pcm, { false })
        assertEquals(288000L * 2 * 2, pcm.length()) // Six seconds, stereo, signed 16-bit, 48 kHz.
        val output = File(directory, "export.mp4")
        CompositionExporter(context).export(project, output) {}
        assertTrue(output.length() > 0)
        File(directory, "run.json").writeText(JSONObject().apply {
            put("schema", 1)
            put("fixture", "e1a-mixed-v1")
            put("project_sha256", MessageDigest.getInstance("SHA-256").digest(ProjectCodec.encode(canonicalProject))
                .joinToString("") { "%02x".format(it) })
            put("ndk", BuildConfig.NATIVE_NDK_VERSION)
            put("device_fingerprint", Build.FINGERPRINT)
            put("device_model", Build.MODEL)
            put("page_size", Os.sysconf(OsConstants._SC_PAGESIZE))
            put("source_sha256", digest(source))
            put("image_sha256", digest(image))
            put("export_sha256", digest(output))
            put("mix_sha256", digest(pcm))
            put("mix_sample_frames", pcm.length() / 4)
            put("environment", InstrumentationRegistry.getArguments().getString("e1aEnvironment") ?: "unspecified")
        }.toString(2))
    }

    private fun fixture(video: File, image: File): ProjectDocument {
        val duration = 6_000_000L
        val timing = ClipTiming(durationUs = duration)
        val videoAsset = AssetReference("e1a-video", Uri.fromFile(video).toString(), MediaAccess.AVAILABLE, duration)
        val imageAsset = AssetReference("e1a-image", Uri.fromFile(image).toString(), MediaAccess.AVAILABLE)
        val layers = listOf(
            CompositionLayer(1, LayerType.SOLID, 0, timing, solidColorArgb = 0xff102040.toInt()),
            CompositionLayer(2, LayerType.VIDEO, 1, timing, asset = videoAsset,
                transform = Transform(scaleX = .8f, scaleY = .8f), audioGain = .7f),
            CompositionLayer(3, LayerType.IMAGE, 2, timing, asset = imageAsset,
                transform = Transform(positionX = -.55f, positionY = -.35f, scaleX = .22f, scaleY = .22f),
                animTransform = AnimatableTransform(rotation = AnimatableProperty1D(true, keyframes = FrozenList.of(listOf(
                    Keyframe1D(0, -30f, EasingPreset.LINEAR), Keyframe1D(duration, 90f, EasingPreset.LINEAR)))))),
            CompositionLayer(4, LayerType.SOLID, 3, timing, solidColorArgb = 0xffee5030.toInt(),
                transform = Transform(positionX = .55f, positionY = -.3f, scaleX = .12f, scaleY = .2f),
                animTransform = AnimatableTransform(opacity = AnimatableProperty1D(true, keyframes = FrozenList.of(listOf(
                    Keyframe1D(0, .25f), Keyframe1D(3_000_000, .9f), Keyframe1D(duration, .25f)))))),
            CompositionLayer(5, LayerType.IMAGE, 4, timing, asset = imageAsset,
                transform = Transform(positionX = .5f, positionY = .4f, scaleX = .2f, scaleY = .2f),
                animTransform = AnimatableTransform(scale = AnimatableProperty2D(true, keyframes = FrozenList.of(listOf(
                    Keyframe2D(0, .1f, .1f), Keyframe2D(duration, .3f, .3f)))))),
            CompositionLayer(6, LayerType.VIDEO, 5, ClipTiming(startUs = 1_000_000, sourceInUs = 500_000, durationUs = 4_000_000),
                asset = videoAsset, audioGain = .3f,
                transform = Transform(scaleX = .2f, scaleY = .2f, opacity = .8f),
                animTransform = AnimatableTransform(position = AnimatableProperty2D(true, keyframes = FrozenList.of(listOf(
                    Keyframe2D(0, -.5f, .5f), Keyframe2D(4_000_000, .5f, -.5f))))))
        )
        return ProjectDocument(Composition(width = 640, height = 360, frameRate = FrameRate(30), layers = FrozenList.of(layers)))
    }

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
