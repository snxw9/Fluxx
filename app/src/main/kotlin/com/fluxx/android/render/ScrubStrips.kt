package com.fluxx.android.render

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * MP2.3 B1: the process-wide scrub-strip disk cache (cacheDir/[StripPolicy.DIR]), one [StripFile] per source, keyed as
 * the PCM cache: the URI's SHA-1, the file's size and last-modified time, and the format version (files of another
 * version are deleted). LRU over a budget of ALLOCATED bytes ([StripPolicy.CAP_BYTES]); files in use are never evicted.
 * Partial coverage is kept across restarts. A cache only: never in the document, never in undo, never read by export.
 */
class ScrubStrips private constructor(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.cacheDir, StripPolicy.DIR).apply {
        mkdirs()
        listFiles().orEmpty().filter { StripPolicy.stale(it.name) }.forEach { it.delete() }
    }
    private val open = HashMap<String, StripFile>()

    /** The strip of [uri] (opened and its index loaded on first use), or null when the source cannot be keyed. */
    @Synchronized fun get(uri: String, durationUs: Long): StripFile? {
        open[uri]?.let { if (!it.closed) return it }
        if (durationUs <= 0) return null
        val (size, modified) = meta(Uri.parse(uri)) ?: return null
        val base = StripPolicy.fileBase(uri, size, modified)
        evict(open.values.map { it.base }.toSet() + base)
        return runCatching { StripFile(dir, base, StripPolicy.slots(durationUs), ::allocated) }.getOrNull()?.also { open[uri] = it }
    }

    /** The strip of [uri] if it is already open (the render path never opens files). */
    @Synchronized fun opened(uri: String): StripFile? = open[uri]?.takeIf { !it.closed }

    /** After a run: evict the least recently used other strips so the cache fits; false when [file] alone is over the budget. */
    @Synchronized fun fits(file: StripFile): Boolean {
        evict(open.values.map { it.base }.toSet())
        return file.allocatedBytes() <= StripPolicy.CAP_BYTES
    }

    /** Allocated bytes of every strip file on disk. */
    fun allocatedBytes(): Long = dir.listFiles().orEmpty().sumOf { allocated(it) }

    private fun evict(keep: Set<String>) {
        val groups = dir.listFiles().orEmpty().filter { it.name.endsWith(".strip") || it.name.endsWith(".sidx") }.groupBy { it.name.substringBeforeLast('.') }
        val files = groups.map { (base, fs) -> Triple(base, fs.sumOf { allocated(it) }, fs.maxOf { it.lastModified() }) }
        for (base in StripPolicy.evict(files, keep)) groups[base]?.forEach { it.delete() }
    }

    private fun allocated(f: File): Long = runCatching { android.system.Os.stat(f.path).st_blocks * 512L }.getOrDefault(f.length())

    private fun meta(u: Uri): Pair<Long, Long>? {
        if (u.scheme == "file") return File(requireNotNull(u.path)).let { if (it.isFile) it.length() to it.lastModified() else null }
        var size = -1L; var modified = -1L
        runCatching { app.contentResolver.query(u, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(android.provider.OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
                c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 }?.let { modified = c.getLong(it) }
                if (modified < 0) c.getColumnIndex(android.provider.MediaStore.MediaColumns.DATE_MODIFIED).takeIf { it >= 0 }?.let { modified = c.getLong(it) * 1000 }
            } } }
        return if (size < 0) null else size to modified
    }

    /** The builder's [StripBuilder.Ops] over this cache and [StripDecoder]. */
    internal fun ops(): StripBuilder.Ops<StripDecoder> = object : StripBuilder.Ops<StripDecoder> {
        override fun strip(uri: String, durationUs: Long) = get(uri, durationUs)
        override fun open(uri: String) = StripDecoder(app, Uri.parse(uri))
        override fun seek(decoder: StripDecoder, us: Long) = decoder.seek(us)
        override fun next(decoder: StripDecoder, want: (Long) -> Boolean) = decoder.next(want)
        override fun close(decoder: StripDecoder) = decoder.close()
        override fun afterRun(file: StripFile) = fits(file)
    }

    companion object {
        @Volatile private var instance: ScrubStrips? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: ScrubStrips(context.applicationContext).also { instance = it } }

        /** TEST-ONLY: the covered share of source [fromUs, toUs] of [uri]'s open strip (0 when none is open). */
        @JvmStatic fun testCoverage(context: Context, uri: String, fromUs: Long, toUs: Long, durationUs: Long): Double {
            val f = get(context).opened(uri) ?: return 0.0
            val r = StripPolicy.slotOf(fromUs)..minOf(StripPolicy.slotOf(toUs), f.index.slots - 1)
            if (r.isEmpty()) return 0.0
            return f.withIndex { it.coveredIn(r) }.toDouble() / (r.last - r.first + 1)
        }
        /** TEST-ONLY: the cache's allocated bytes on disk. */
        @JvmStatic fun testAllocatedBytes(context: Context): Long = get(context).allocatedBytes()
    }
}
