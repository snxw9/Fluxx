package com.fluxx.android.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.provider.MediaStore
import com.fluxx.android.model.*
import com.fluxx.android.render.MediaProbes
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList

/**
 * MP1 benchmark helpers. The benchmark files stay where they are (read in place, never copied, never written); documents
 * are in memory only. A missing file SKIPS its test with the lookup log.
 */
object Mp1Bench {
    /** The long source: `Project 3 [...].mp4`, 5.02 GB HEVC 1080p30 + AAC, 50:18. Found by this prefix only. */
    const val LONG_PREFIX = "Project 3"
    var lookupLog = ""

    /**
     * The long source: StorageManager volumes' Download/Seal first, then the rest of each volume's Download tree, then a
     * MediaStore query by DISPLAY_NAME prefix (READ_MEDIA_VIDEO is granted). Returns the file (read in place) or null.
     */
    fun findLong(context: Context): File? {
        val log = StringBuilder()
        val sm = context.getSystemService(android.os.storage.StorageManager::class.java)
        val volumes = (sm.storageVolumes.mapNotNull { it.directory } + File("/storage").listFiles().orEmpty()).distinct()
        fun readable(f: File) = runCatching { f.inputStream().use { it.read() } >= 0 }.getOrDefault(false)
        for (v in volumes) {
            val dir = File(v, "Download/Seal"); val names = dir.listFiles()
            log.append(dir.path).append(": ").append(names?.size?.toString() ?: "unlistable").append("; ")
            names?.firstOrNull { it.name.startsWith(LONG_PREFIX) }?.let { if (readable(it)) { log.append("found in Seal; "); lookupLog = log.toString(); return it } }
        }
        for (v in volumes) {
            val dl = File(v, "Download"); if (!dl.isDirectory) continue
            val hit = dl.walkTopDown().maxDepth(4).firstOrNull { it.isFile && it.name.startsWith(LONG_PREFIX) && it.name.endsWith(".mp4") }
            log.append(dl.path).append(" tree: ").append(if (hit != null) "found" else "none").append("; ")
            if (hit != null && readable(hit)) { lookupLog = log.toString(); return hit }
        }
        runCatching {
            context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("$LONG_PREFIX%"), null)?.use { c ->
                log.append("MediaStore rows ").append(c.count).append("; ")
                while (c.moveToNext()) { val path = c.getString(2) ?: continue; val f = File(path)
                    if (readable(f)) { log.append("found via MediaStore; "); lookupLog = log.toString(); return f } }
            }
        }.onFailure { log.append("MediaStore query failed ").append(it.javaClass.simpleName).append("; ") }
        lookupLog = log.toString()
        return null
    }

    /** Battery temperature in C (the sticky ACTION_BATTERY_CHANGED broadcast; read-only), and whether it is charging. */
    fun battery(context: Context): Pair<Double, Boolean> {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val t = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -10) ?: -10) / 10.0
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return t to plugged
    }

    /** Waits (up to [maxWaitMs]) while the battery is above [limitC]; false when it is still too hot (skip and report). */
    fun coolEnough(context: Context, limitC: Double = 42.0, maxWaitMs: Long = 180_000, log: (String) -> Unit): Boolean {
        val t0 = System.nanoTime()
        while (true) {
            val (t, plugged) = battery(context)
            if (t <= limitC) { log("battery %.1f C (%s)".format(t, if (plugged) "charging" else "not charging")); return true }
            if ((System.nanoTime() - t0) / 1_000_000 > maxWaitMs) { log("battery %.1f C above %.0f C after waiting %d s: SKIPPED".format(t, limitC, maxWaitMs / 1000)); return false }
            Thread.sleep(10_000)
        }
    }

    /** This process's CPU time (user + system) in ms, from /proc/self/stat (clock ticks at 100 Hz). */
    fun cpuMs(): Long = runCatching { val f = File("/proc/self/stat").readText().substringAfterLast(')').trim().split(' ')
        (f[11].toLong() + f[12].toLong()) * 10 }.getOrDefault(-1)
    /** CPU ms per thread name (summed over threads with the same name), from /proc/self/task. */
    fun threadCpuMs(): Map<String, Long> = File("/proc/self/task").listFiles().orEmpty().mapNotNull { t ->
        runCatching { val s = File(t, "stat").readText(); val name = s.substringAfter('(').substringBeforeLast(')')
            val f = s.substringAfterLast(')').trim().split(' '); name to (f[11].toLong() + f[12].toLong()) * 10 }.getOrNull()
    }.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
    /** Bytes this process read from storage (read_bytes) and through read calls (rchar), from /proc/self/io. */
    fun io(): Pair<Long, Long> = runCatching { val m = File("/proc/self/io").readLines().associate { it.substringBefore(':') to it.substringAfter(':').trim().toLong() }
        (m["read_bytes"] ?: -1) to (m["rchar"] ?: -1) }.getOrDefault(-1L to -1L)

    /** Top-level MP4 atoms (type, offset, size): where the moov (the seek index) sits relative to mdat. Header reads only. */
    fun atoms(f: File): List<Triple<String, Long, Long>> = RandomAccessFile(f, "r").use { r ->
        val out = ArrayList<Triple<String, Long, Long>>(); var at = 0L; val len = r.length()
        while (at + 8 <= len && out.size < 64) {
            r.seek(at); var size = r.readInt().toLong() and 0xffffffffL
            val type = ByteArray(4).also { r.readFully(it) }.toString(Charsets.US_ASCII)
            if (size == 1L) size = r.readLong() else if (size == 0L) size = len - at
            if (size < 8) break
            out += Triple(type, at, size); at += size
        }
        out
    }

    /** One video layer at a 3x2 / 2x2 tile, or anywhere: [px]/[py] NDC centre, [s] uniform scale. */
    fun placed(id: Long, f: File, m: Aud1Bench.Media, uri: String, z: Int, timing: ClipTiming, px: Float, py: Float, s: Float) =
        CompositionLayer(id, LayerType.VIDEO, name = "mp1-$id", zOrder = z, timing = timing,
            transform = Transform(positionX = px, positionY = py, scaleX = s, scaleY = s),
            asset = AssetReference("mp1-bench-$id", uri, access = MediaAccess.AVAILABLE, durationUs = m.durationUs))

    fun solid(id: Long, argb: Int, durationUs: Long, z: Int = 0) =
        CompositionLayer(id, LayerType.SOLID, name = "mp1-bg", zOrder = z, timing = ClipTiming(0, 0, durationUs), solidColorArgb = argb)

    /** Decoder "frame" event fields (t=, shown=, us=, how=, seek=). */
    fun field(e: String, k: String): String? = Regex("""\b$k=(\S+)""").find(e)?.groupValues?.get(1)
}

/** Collects [MediaProbes] events while a test runs. */
class Mp1Probe {
    val events = CopyOnWriteArrayList<Pair<Long, String>>()
    fun start() { MediaProbes.events = { e -> events += System.nanoTime() to e } }
    fun stop() { MediaProbes.events = null }
}
