package com.fluxx.android.fonts

import com.fluxx.android.render.SdfAtlasTiers
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

/** TEXT PERF 11: imported tier glyphs are reused from the process cache; a hit is byte-identical to the worker's record. */
class TierGlyphCacheTest {
    private val font = "user." + "c".repeat(64)
    private val other = "user." + "d".repeat(64)
    private fun input(id: String) = FontWorkerClient.FontInput(id, File("x"), com.fluxx.android.model.TextFontRef())
    private val direct = Executor { it.run() }
    private fun reply(glyphs: List<TierWire.Key>, failed: List<TierWire.Key> = emptyList()) =
        ByteArrayOutputStream().also { DataOutputStream(it).use { out ->
            fun str(s: String) { val b = s.toByteArray(); out.writeInt(b.size); out.write(b) }
            fun list(l: List<TierWire.Key>) { out.writeInt(l.size); for (k in l) { str(k.font); out.writeInt(k.glyph); out.writeInt(k.ppem) } }
            out.writeInt(TierWire.REPLY_MAGIC); out.writeInt(1); out.writeInt(glyphs.size)
            for (g in glyphs) { val side = 2 + g.glyph % 3; str(g.font); out.writeInt(g.glyph); out.writeInt(g.ppem); out.writeInt(SdfAtlasTiers.spread(g.ppem))
                out.writeInt(side); out.writeInt(side); out.writeInt(-1); out.writeInt(4); out.writeInt(side * side); out.write(ByteArray(side * side) { (g.glyph + it).toByte() }) }
            list(failed); list(emptyList())
        } }.toByteArray()
    private fun records(bytes: ByteArray) = TierWire.glyphRecords(bytes).associate { it.first to it.second.toList() }

    @Test fun recordsRoundTripExactly() {
        val keys = listOf(TierWire.Key(font, 1, 96), TierWire.Key(font, 2, 192), TierWire.Key(other, 1, 384))
        val original = reply(keys)
        val parsed = TierWire.glyphRecords(original)
        assertEquals(keys, parsed.map { it.first })
        assertArrayEquals("a reply rebuilt from records equals the worker's", original, TierWire.reply(parsed.map { it.second }))
    }
    @Test fun aSecondRendererGetsTheSameGlyphsWithoutTheWorker() {
        val cache = TierGlyphCache()
        val keys = listOf(TierWire.Key(font, 5, 96), TierWire.Key(font, 6, 384))
        val calls = mutableListOf<List<TierWire.Key>>()
        val worker: suspend (ByteArray, List<FontWorkerClient.FontInput>) -> ByteArray =
            { req, _ -> TierWire.decodeRequests(req).also { calls += it }.let { reply(it) } }
        val first = mutableListOf<ByteArray>()
        ImportedTierFetcher({ ids -> ids.map(::input) }, worker, { first += it }, direct, cache = cache).fetchNow(TierWire.encodeRequests(keys))
        val second = mutableListOf<ByteArray>()
        ImportedTierFetcher({ ids -> ids.map(::input) }, worker, { second += it }, direct, cache = cache).fetchNow(TierWire.encodeRequests(keys))
        assertEquals("one worker call in total", listOf(keys), calls)
        assertEquals(records(first.single()), records(second.single()))
    }
    @Test fun partialHitsAskOnlyForMissesAndFailuresAreNotCached() {
        val cache = TierGlyphCache()
        val hit = TierWire.Key(font, 7, 192); val miss = TierWire.Key(font, 8, 192); val bad = TierWire.Key(font, 9, 384)
        cache.store(TierWire.glyphRecords(reply(listOf(hit))))
        val calls = mutableListOf<List<TierWire.Key>>(); val supplied = mutableListOf<ByteArray>()
        ImportedTierFetcher({ ids -> ids.map(::input) }, { req, _ -> TierWire.decodeRequests(req).also { calls += it }.let { reply(listOf(miss), failed = listOf(bad)) } },
            { supplied += it }, direct, cache = cache).fetchNow(TierWire.encodeRequests(listOf(hit, miss, bad)))
        assertEquals(listOf(listOf(miss, bad)), calls)
        assertEquals(listOf(hit), TierWire.summarize(supplied[0]).glyphs)
        assertEquals(listOf(miss), TierWire.summarize(supplied[1]).glyphs); assertEquals(listOf(bad), TierWire.summarize(supplied[1]).failed)
        assertEquals("the failed key is asked for again next time", listOf(miss), cache.take(listOf(miss, bad)).map { it.first })
    }
    @Test fun capEvictsLeastRecentlyUsed() {
        val keys = List(4) { TierWire.Key(font, 100 + it * 3, 96) } // Equal 4x4 bitmaps.
        val size = TierWire.glyphRecords(reply(keys.take(1))).single().second.size.toLong()
        val cache = TierGlyphCache(capBytes = size * 3)
        cache.store(TierWire.glyphRecords(reply(keys.take(3))))
        cache.take(listOf(keys[0])) // Touch: keys[1] is now least recently used.
        cache.store(TierWire.glyphRecords(reply(listOf(keys[3]))))
        assertEquals(3, cache.size())
        assertEquals(listOf(keys[0], keys[2], keys[3]), cache.take(keys).map { it.first })
    }
}
