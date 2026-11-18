package com.fluxx.android.fonts

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking

/**
 * TEXT FIX 9c tier wire format, byte-compatible with core-engine prepared_text_packet.cpp (big-endian u32 fields,
 * u32-length-prefixed UTF-8 strings). Requests come from the renderer; replies come from the isolated worker. Kotlin
 * only decodes request keys (to pick the font files) and encodes failure-only replies (worker death or timeout).
 */
internal object TierWire {
    data class Key(val font: String, val glyph: Int, val ppem: Int)
    const val REQUEST_MAGIC = 0x46545251
    const val REPLY_MAGIC = 0x46545447
    const val MAX_REQUESTS = 256
    const val MAX_REPLY_BYTES = 8 * 1024 * 1024
    val TIERS = setOf(96, 192, 384)

    fun decodeRequests(bytes: ByteArray): List<Key> {
        require(bytes.size in 12..1024 * 1024) { "Invalid tier request size" }
        val input = ByteBuffer.wrap(bytes)
        require(input.int == REQUEST_MAGIC && input.int == 1) { "Unsupported tier request packet" }
        val count = input.int; require(count in 0..MAX_REQUESTS) { "Too many tier requests" }
        return List(count) { key(input) }.also { require(!input.hasRemaining()) { "Trailing tier request data" } }
    }
    fun encodeRequests(keys: List<Key>): ByteArray = packet { out ->
        require(keys.size <= MAX_REQUESTS); out.writeInt(REQUEST_MAGIC); out.writeInt(1); keys(out, keys)
    }
    /** A reply with no glyphs: every key failed, so the renderer keeps those glyphs on the lower tier. */
    fun failure(keys: List<Key>): ByteArray = packet { out ->
        out.writeInt(REPLY_MAGIC); out.writeInt(1); out.writeInt(0); keys(out, keys); keys(out, emptyList())
    }
    data class ReplySummary(val glyphs: List<Key>, val failed: List<Key>, val deferred: List<Key>, val pixelBytes: Long)
    fun summarize(bytes: ByteArray): ReplySummary {
        require(bytes.size in 20..MAX_REPLY_BYTES) { "Invalid tier reply size" }
        val input = ByteBuffer.wrap(bytes)
        require(input.int == REPLY_MAGIC && input.int == 1) { "Unsupported tier reply" }
        val count = input.int; require(count in 0..MAX_REQUESTS)
        var pixels = 0L
        val glyphs = List(count) {
            val font = string(input); val glyph = input.int; val ppem = input.int; input.int // spread
            val w = input.int; val h = input.int; input.int; input.int
            val n = input.int; require(n.toLong() == w.toLong() * h && n <= input.remaining()) { "Invalid tier glyph pixels" }
            input.position(input.position() + n); pixels += n
            Key(font, glyph, ppem)
        }
        val failed = List(input.int.also { require(it in 0..MAX_REQUESTS) }) { key(input) }
        val deferred = List(input.int.also { require(it in 0..MAX_REQUESTS) }) { key(input) }
        require(!input.hasRemaining()) { "Trailing tier reply data" }
        return ReplySummary(glyphs, failed, deferred, pixels)
    }
    private fun key(input: ByteBuffer): Key {
        val key = Key(string(input), input.int, input.int)
        require(key.ppem in TIERS) { "Invalid tier request ppem" }
        return key
    }
    private fun string(input: ByteBuffer): String {
        val n = input.int; require(n in 1..1024 && n <= input.remaining())
        val bytes = ByteArray(n); input.get(bytes); return String(bytes, Charsets.UTF_8)
    }
    private fun keys(out: DataOutputStream, keys: List<Key>) {
        out.writeInt(keys.size)
        for (k in keys) { val b = k.font.toByteArray(Charsets.UTF_8); out.writeInt(b.size); out.write(b); out.writeInt(k.glyph); out.writeInt(k.ppem) }
    }
    private fun packet(write: (DataOutputStream) -> Unit) = ByteArrayOutputStream().also { DataOutputStream(it).use(write) }.toByteArray()
}

/**
 * Fetches imported-font atlas tiers from the isolated worker for one renderer. The render thread never waits on it:
 * previews submit and keep drawing the lower tier (the renderer's settle-and-redraw picks the glyphs up); export runs
 * the same fetch inline so every exported frame is deterministic. A worker crash, timeout or missing font file supplies
 * a failure reply: those glyphs stay on the lower tier and [lastError] records why; the app never crashes.
 */
internal class ImportedTierFetcher(
    private val fonts: (Set<String>) -> List<FontWorkerClient.FontInput>,
    private val rasterise: suspend (ByteArray, List<FontWorkerClient.FontInput>) -> ByteArray,
    private val supply: (ByteArray) -> Unit,
    private val executor: Executor,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    @Volatile var lastError: String? = null; private set
    @Volatile var inFlight = 0; private set

    /** Preview: returns at once; the reply is supplied from the executor. */
    fun submit(requests: ByteArray) {
        if (requests.isEmpty()) return
        synchronized(this) { inFlight++ }
        executor.execute { try { fetch(requests) } finally { synchronized(this) { inFlight-- } } }
    }
    /** Export: fetch and supply before the frame is prepared. */
    fun fetchNow(requests: ByteArray) { if (requests.isNotEmpty()) fetch(requests) }

    private fun fetch(requests: ByteArray) {
        val keys = try { TierWire.decodeRequests(requests) } catch (failure: Throwable) { log("Invalid tier request", failure); return }
        val reply = try {
            val inputs = fonts(keys.map { it.font }.toSet())
            val known = inputs.map { it.id }.toSet()
            val missing = keys.filter { it.font !in known }
            val wanted = keys.filter { it.font in known }
            if (missing.isNotEmpty()) supply(TierWire.failure(missing)) // Library changed: keep the lower tier.
            if (wanted.isEmpty()) return
            val bytes = runBlocking { rasterise(TierWire.encodeRequests(wanted), inputs) }
            TierWire.summarize(bytes) // Validate shape and caps before handing it to native code.
            bytes
        } catch (failure: Throwable) {
            lastError = FontImportErrors.message(failure)
            log("Imported-font tier glyphs unavailable; keeping the lower tier", failure)
            TierWire.failure(keys)
        }
        supply(reply)
    }
}
