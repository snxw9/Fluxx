package com.fluxx.android.fonts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.engine.RenderBridge
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import com.fluxx.android.render.CompositionRenderer
import com.fluxx.android.render.SdfAtlasTiers
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TEXT FIX 9c on device: imported faces rasterise atlas tiers in the isolated worker, a worker crash degrades to the
 * lower tier with an error (never an app crash), and export uses the sharp tier deterministically. Uses the committed
 * OFL fixture (SourceSerif4Variable-Roman.otf); a fixture imported here is deleted again, the user's fonts are untouched.
 */
@RunWith(AndroidJUnit4::class)
class ImportedTierAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun <T> withVariableFont(run: (FontLibraryEntry, FontWorkerClient.FontInput) -> T): T {
        val file = File.createTempFile("font-tier-proof-", ".otf", context.cacheDir)
        val library = FontLibraryRepository.getInstance(context)
        var added = false; var hash: String? = null
        try {
            instrumentation.context.assets.open("SourceSerif4Variable-Roman.otf").use { input -> file.outputStream().use { input.copyTo(it) } }
            val entry = runBlocking(Dispatchers.IO) {
                library.load()
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
                library.state.value.find { it.sha256 == digest } ?: library.import(android.net.Uri.fromFile(file)).also { added = true }
            }
            hash = entry.sha256
            val reference = entry.faces.single().reference(entry.sha256, requested = mapOf("wght" to 700f, "opsz" to 20f))
            return run(entry, requireNotNull(library.resolve(reference)))
        } finally {
            if (added) hash?.let { key -> runBlocking(Dispatchers.IO) { library.delete(key) } }
            file.delete()
        }
    }
    private fun keys(input: FontWorkerClient.FontInput, glyphs: List<Int>) =
        glyphs.flatMap { g -> listOf(96, 192, 384).map { TierWire.Key(input.id, g, it) } }

    @Test fun isolatedWorkerRasterisesImportedTiersWithinCaps() { withVariableFont { _, input ->
        // Glyph IDs 1..12 cover the fixture's first glyphs; the worker fails unknown IDs individually.
        val requested = keys(input, (1..12).toList())
        val start = System.nanoTime()
        val reply = runBlocking { FontWorkerClient(context).tiers(TierWire.encodeRequests(requested), listOf(input)) }
        val ms = (System.nanoTime() - start) / 1e6
        val summary = TierWire.summarize(reply)
        assertTrue(reply.size <= TierWire.MAX_REPLY_BYTES)
        assertEquals(requested.toSet(), (summary.glyphs + summary.failed + summary.deferred).toSet())
        assertTrue("most glyphs rasterised", summary.glyphs.size >= requested.size / 2)
        Log.i("FluxxTierProof", "imported tiers: ${summary.glyphs.size} glyphs, ${summary.failed.size} failed, ${summary.deferred.size} deferred, " +
            "${reply.size / 1024} KiB, ${"%.1f".format(ms)} ms total, ${"%.1f".format(ms / requested.size)} ms/glyph (incl. IPC)")
    } }
    @Test fun workerCrashDegradesToTheLowerTierWithAnError() { withVariableFont { _, input ->
        val requested = keys(input, listOf(3, 4))
        val supplied = mutableListOf<ByteArray>()
        val client = FontWorkerClient(context)
        val crashing = ImportedTierFetcher({ listOf(input) }, { _, _ -> client.crashForTest(input.file); error("unreachable") },
            { supplied += it }, Executor { it.run() })
        crashing.fetchNow(TierWire.encodeRequests(requested)) // Must not throw.
        assertEquals("every key keeps the lower tier", requested, TierWire.summarize(supplied.single()).failed)
        assertTrue(crashing.lastError.orEmpty().contains("isolated font parser"))
        // The worker restarts: the next request succeeds.
        val reply = runBlocking { client.tiers(TierWire.encodeRequests(requested), listOf(input)) }
        assertEquals(requested.size, TierWire.summarize(reply).glyphs.size)
        Log.i("FluxxTierProof", "crash tolerance: failure reply after worker death, recovered (${crashing.lastError})")
    } }
    @Test fun exportUsesTheSharpImportedTierDeterministically() { withVariableFont { entry, _ ->
        val font = entry.faces.single().reference(entry.sha256, requested = mapOf("wght" to 700f, "opsz" to 20f))
        fun project(size: Float): ProjectDocument {
            val document = TextSourceDocument("Hamburg @&").format(0, 0) { it.copy(font = font) }
            val text = TextProperties(source = AnimatableString(staticValue = "Hamburg @&", document = document), size = AnimatableProperty1D(staticValue = size))
            return ProjectDocument(Composition(width = 1080, height = 1920, durationUs = 1_000_000,
                layers = FrozenList.of(listOf(CompositionLayer(1, LayerType.TEXT, timing = ClipTiming(durationUs = 1_000_000), text = text)))))
        }
        CompositionRenderer(context, MediaRepository(context)).use { export ->
            export.render(project(24f), 0)
            val small = JSONObject(RenderBridge.textStats(export.session))
            assertEquals("small text needs no imported tier", 0, small.getInt("tierExternalRequested"))
            val start = System.nanoTime()
            export.render(project(600f), 0)
            val ms = (System.nanoTime() - start) / 1e6
            val large = JSONObject(RenderBridge.textStats(export.session))
            assertTrue("imported tier glyphs supplied inline", large.getInt("tierExternalSupplied") > 0)
            assertEquals(0, large.getInt("tierExternalFailed"))
            assertEquals("export never leaves tier glyphs pending", 0, large.getInt("tierPending"))
            Log.i("FluxxTierProof", "export 600 px imported: ${large.getInt("tierExternalSupplied")} tier glyphs supplied, first frame ${"%.0f".format(ms)} ms, " +
                "tierBytes=${large.getLong("tierBytes")} (cap ${SdfAtlasTiers.cpuBytes(false)})")
        }
    } }
}
