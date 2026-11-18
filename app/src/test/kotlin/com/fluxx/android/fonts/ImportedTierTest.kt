package com.fluxx.android.fonts

import com.fluxx.android.render.SdfAtlasTiers
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

/** TEXT FIX 9c: imported fonts and per-character (animated / Explode) glyphs use the same atlas tiers as bundled text. */
class ImportedTierTest {
    private val font = "user." + "a".repeat(64)
    private val keys = listOf(TierWire.Key(font, 70, 96), TierWire.Key(font, 71, 192), TierWire.Key(font, 72, 384))
    private fun src(path: String) = File(path).readText()
    private fun input(id: String) = FontWorkerClient.FontInput(id, File("x"), com.fluxx.android.model.TextFontRef())
    private val direct = Executor { it.run() }

    /** A worker reply in the native format: [glyphs] of w x h, then failed / deferred key lists. */
    private fun reply(glyphs: List<TierWire.Key>, failed: List<TierWire.Key> = emptyList(), deferred: List<TierWire.Key> = emptyList(), side: Int = 3) =
        ByteArrayOutputStream().also { DataOutputStream(it).use { out ->
            fun str(s: String) { val b = s.toByteArray(); out.writeInt(b.size); out.write(b) }
            fun list(l: List<TierWire.Key>) { out.writeInt(l.size); for (k in l) { str(k.font); out.writeInt(k.glyph); out.writeInt(k.ppem) } }
            out.writeInt(TierWire.REPLY_MAGIC); out.writeInt(1); out.writeInt(glyphs.size)
            for (g in glyphs) { str(g.font); out.writeInt(g.glyph); out.writeInt(g.ppem); out.writeInt(SdfAtlasTiers.spread(g.ppem))
                out.writeInt(side); out.writeInt(side); out.writeInt(-2); out.writeInt(5); out.writeInt(side * side); out.write(ByteArray(side * side) { 128.toByte() }) }
            list(failed); list(deferred)
        } }.toByteArray()

    @Test fun importedFacesAndCataloguesSelectTiersLikeBundledText() {
        // The renderer no longer pins layouts with prepared (external) bitmaps to 48 ppem.
        val renderer = src("../core-engine/src/main/cpp/text/text_renderer.cpp")
        assertTrue(renderer.contains("const unsigned wanted=tierFor(layout.get());"))
        assertFalse(renderer.contains("layout->externalSdfs.empty()?tierFor(layout.get()):tiers::BasePpem"))
        // Imported keys are never rasterised in the app process (the tier thread only opens bundled faces).
        assertTrue(renderer.contains("if(!external(std::get<0>(key)) && !retained_.count(key)"))
        // Same per-layer policy for every font: px/em with 15% hysteresis; catalogue (animated/Explode) handles follow the layer.
        assertEquals(SdfAtlasTiers.select(300f, null), SdfAtlasTiers.select(300f, null))
        assertEquals(192, SdfAtlasTiers.select(250f, null)); assertEquals(384, SdfAtlasTiers.select(1000f, null))
        assertTrue(src("src/main/kotlin/com/fluxx/android/render/CompositionRenderer.kt").contains("entry.layouts.map { it.handle } + entry.catalogueHandles.toList()"))
    }
    @Test fun requestWireRoundTripAndCaps() {
        val bytes = TierWire.encodeRequests(keys)
        assertEquals(keys, TierWire.decodeRequests(bytes))
        assertEquals(TierWire.REQUEST_MAGIC, java.nio.ByteBuffer.wrap(bytes).int)
        assertEquals(256, TierWire.MAX_REQUESTS); assertEquals(8 * 1024 * 1024, TierWire.MAX_REPLY_BYTES)
        // The base tier ships in the prepared packet and is never requested; tiers are exactly 96/192/384.
        assertThrows(IllegalArgumentException::class.java) { TierWire.decodeRequests(TierWire.encodeRequests(listOf(TierWire.Key(font, 1, 48)))) }
        assertThrows(IllegalArgumentException::class.java) { TierWire.encodeRequests(List(257) { keys[0] }) }
        assertThrows(IllegalArgumentException::class.java) { TierWire.decodeRequests(bytes + 0) }
        // Native constants agree (prepared_text_packet.h).
        val header = src("../core-engine/src/main/cpp/text/prepared_text_packet.h")
        assertTrue(header.contains("constexpr uint32_t MaxTierRequests=256;") && header.contains("constexpr size_t MaxTierReplyBytes=8*1024*1024;"))
        assertEquals(FontWorkerService.MAX_TIER_REPLY_BYTES, TierWire.MAX_REPLY_BYTES)
    }
    @Test fun aReplyIsSummarisedAndValidatedBeforeNativeCode() {
        val ok = reply(keys.take(2), failed = keys.drop(2))
        val summary = TierWire.summarize(ok)
        assertEquals(keys.take(2), summary.glyphs); assertEquals(keys.drop(2), summary.failed); assertEquals(18L, summary.pixelBytes)
        val truncated = ok.copyOf(ok.size - 1)
        assertThrows(Exception::class.java) { TierWire.summarize(truncated) }
        val failure = TierWire.summarize(TierWire.failure(keys))
        assertTrue(failure.glyphs.isEmpty()); assertEquals(keys, failure.failed); assertTrue(failure.deferred.isEmpty())
    }
    @Test fun previewSubmitsWithoutWaitingAndExportFetchesInline() {
        val supplied = mutableListOf<ByteArray>(); val queued = mutableListOf<Runnable>()
        val fetcher = ImportedTierFetcher({ ids -> ids.map(::input) }, { req, _ -> reply(TierWire.decodeRequests(req)) }, { supplied += it }, Executor { queued += it })
        fetcher.submit(TierWire.encodeRequests(keys))
        assertTrue("the render thread never waits", supplied.isEmpty()); assertEquals(1, fetcher.inFlight)
        queued.single().run()
        assertEquals(keys, TierWire.summarize(supplied.single()).glyphs); assertEquals(0, fetcher.inFlight)
        supplied.clear(); fetcher.fetchNow(TierWire.encodeRequests(keys))
        assertEquals("export supplies before preparing the frame", 1, supplied.size)
        fetcher.submit(ByteArray(0)); assertTrue(queued.size == 1)
    }
    @Test fun workerDeathFallsBackToTheLowerTierWithAnError() {
        val supplied = mutableListOf<ByteArray>(); val logged = mutableListOf<String>()
        for (death in listOf<Throwable>(java.io.IOException("The isolated font parser stopped."), FontWorkerTimeoutException(FontImportErrors.TIMEOUT_MESSAGE),
            UnsatisfiedLinkError("no fluxxengine"), IllegalStateException("Font glyph exceeds the 2048-pixel SDF dimension limit"))) {
            supplied.clear()
            val fetcher = ImportedTierFetcher({ ids -> ids.map(::input) }, { _, _ -> throw death }, { supplied += it }, direct, { m, _ -> logged += m })
            fetcher.fetchNow(TierWire.encodeRequests(keys))
            // Every key is reported failed: the renderer keeps those glyphs on the lower tier; no exception escapes.
            assertEquals(keys, TierWire.summarize(supplied.single()).failed)
            assertEquals(FontImportErrors.message(death), fetcher.lastError)
        }
        assertEquals(4, logged.size)
        // A malformed worker reply is never handed to native code.
        supplied.clear()
        val bad = ImportedTierFetcher({ ids -> ids.map(::input) }, { _, _ -> byteArrayOf(1, 2, 3) }, { supplied += it }, direct)
        bad.fetchNow(TierWire.encodeRequests(keys)); assertEquals(keys, TierWire.summarize(supplied.single()).failed)
    }
    @Test fun aDeletedFontFileKeepsTheLowerTier() {
        val supplied = mutableListOf<ByteArray>(); var asked: List<TierWire.Key>? = null
        val fetcher = ImportedTierFetcher({ emptyList() }, { req, _ -> asked = TierWire.decodeRequests(req); reply(emptyList()) }, { supplied += it }, direct)
        fetcher.fetchNow(TierWire.encodeRequests(keys))
        assertNull("no worker call without the font file", asked)
        assertEquals(keys, TierWire.summarize(supplied.single()).failed)
    }
    @Test fun importedTiersShareEvictionOrderCapAndHysteresis() {
        // One ready cache and one atlas: imported glyphs are evicted highest tier first, then least recently used.
        val entries = listOf(SdfAtlasTiers.Entry(96, 1), SdfAtlasTiers.Entry(384, 9), SdfAtlasTiers.Entry(384, 2), SdfAtlasTiers.Entry(192, 0))
        assertEquals(listOf(384 to 2L, 384 to 9L, 192 to 0L, 96 to 1L), SdfAtlasTiers.evictionOrder(entries).map { it.ppem to it.lastUse })
        assertEquals(24L * 1024 * 1024, SdfAtlasTiers.capBytes(false)); assertEquals(12L * 1024 * 1024, SdfAtlasTiers.capBytes(true))
        assertEquals(.15f, SdfAtlasTiers.HYSTERESIS, 0f)
        val renderer = src("../core-engine/src/main/cpp/text/text_renderer.cpp")
        assertTrue("supplied glyphs join the shared ready cache and its trim", renderer.contains("readyBytes_+=glyph->pixels.size();ready_[key]=glyph;readyUse_[key]=frame_;++accepted;"))
        assertTrue("only requested keys are accepted", renderer.contains("if(!externalInFlight_.erase(key)) continue;"))
    }
    @Test fun cacheKeysCarryPpemAndGeneratorVersion() {
        // Disk: base-tier imported glyphs, keyed by generator version, ppem and spread. Tier glyphs live in memory only.
        assertEquals("v2-48-8-$font-7.sdf", SdfDiskCache.key(font, 7))
        // Memory: the FontManager key separates tiers; the renderer key is (font, glyph, ppem).
        assertTrue(src("../core-engine/src/main/cpp/text/font_manager.cpp").contains("\"@\" + std::to_string(ppem)"))
        assertTrue(src("../core-engine/src/main/cpp/text/text_renderer.h").contains("using Key=std::tuple<std::string,uint32_t,unsigned>;"))
    }
}
