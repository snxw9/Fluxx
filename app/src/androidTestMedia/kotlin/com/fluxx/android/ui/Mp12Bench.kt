package com.fluxx.android.ui

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.engine.VideoDecoder
import com.fluxx.android.model.LayerType
import com.fluxx.android.model.ProjectDocument
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * MP1.2: the memory trace of a long mixed-source playback, shared by [Mp12Project1Test] and [Mp12MixedSequenceTest].
 * Everything is read-only and in-process: Debug.MemoryInfo (PSS total / graphics / native / java), the dmabuf this process
 * holds (its own /proc/self/fd + fdinfo: codec output buffers and HardwareBuffers live there, NOT in PSS or RSS), decoders
 * open (VideoDecoder test events), the renderer's live Vulkan images / memories (debug GPU ledger), the PCM cache files'
 * allocated bytes, the system's MemAvailable and the onTrimMemory callbacks received. Rows go out as instrumentation status
 * lines (key MP12CSV) the moment they are taken, so a trace survives the process being killed.
 */
object Mp12Bench {
    fun say(key: String, line: String) {
        android.util.Log.i(key, line)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString(key, line) })
    }

    // ---- dmabuf held by this process ---------------------------------------------------------------------------------
    data class Dmabuf(val count: Int, val bytes: Long)
    /** Distinct dmabufs (by inode) this process has an fd to, and their total size. */
    fun dmabuf(): Dmabuf {
        val seen = HashSet<String>(); var bytes = 0L
        val fds = File("/proc/self/fd").listFiles() ?: return Dmabuf(-1, -1)
        for (fd in fds) {
            val target = runCatching { android.system.Os.readlink(fd.path) }.getOrNull() ?: continue
            if (!target.contains("dmabuf")) continue
            val info = runCatching { File("/proc/self/fdinfo/${fd.name}").readText() }.getOrNull() ?: continue
            val ino = Regex("""(?m)^ino:\s*(\d+)""").find(info)?.groupValues?.get(1) ?: target
            if (!seen.add(ino)) continue
            Regex("""(?m)^size:\s*(\d+)""").find(info)?.groupValues?.get(1)?.toLongOrNull()?.let { bytes += it }
        }
        return Dmabuf(seen.size, bytes)
    }

    fun memAvailableKb(): Long = runCatching {
        File("/proc/meminfo").readLines().first { it.startsWith("MemAvailable:") }.filter { it.isDigit() }.toLong()
    }.getOrDefault(-1)

    /** Debug ledger of the renderer's Vulkan objects (debug builds); (images, memories, bytes) or nulls. */
    fun ledger(): Triple<Long, Long, Long>? = runCatching {
        val bridge = Class.forName("com.fluxx.android.engine.TextDebugBridge")
        val json = org.json.JSONObject(bridge.getMethod("gpuLedger").invoke(bridge.getField("INSTANCE").get(null)) as String)
        Triple(json.optLong("images"), json.optLong("memories"), json.optLong("bytes"))
    }.getOrNull()

    fun pcmAllocated(context: android.content.Context): Long = File(context.cacheDir, "aud1-pcm").listFiles().orEmpty()
        .filter { it.name.endsWith(".pcm") }.sumOf { runCatching { android.system.Os.stat(it.path).st_blocks * 512L }.getOrDefault(0L) }

    // ---- decoders ----------------------------------------------------------------------------------------------------
    /** Open decoders by probe key, with their size and cache frames ("open <key> WxH cache=N codec=..."). */
    class DecoderTrack {
        data class Open(val key: String, val w: Int, val h: Int, val cache: Int, val codec: String)
        val open = java.util.concurrent.ConcurrentHashMap<String, MutableList<Open>>()
        @Volatile var opened = 0; @Volatile var closed = 0; @Volatile var failed = 0; @Volatile var holds = 0
        val events = CopyOnWriteArrayList<Pair<Long, String>>()
        fun start() {
            VideoDecoder.testEvents = { e ->
                when {
                    e.startsWith("open ") -> {
                        val p = e.split(' ')
                        val (w, h) = p[2].split('x').map { it.toInt() }
                        val o = Open(p[1], w, h, p[3].substringAfter('=').toInt(), p.getOrNull(4)?.substringAfter('=') ?: "?")
                        open.getOrPut(o.key) { java.util.Collections.synchronizedList(ArrayList()) }.add(o); opened++
                        events += System.nanoTime() to e
                    }
                    e.startsWith("close ") -> { open[e.substringAfter(' ')]?.let { l -> if (l.isNotEmpty()) l.removeAt(0); if (l.isEmpty()) open.remove(e.substringAfter(' ')) }; closed++; events += System.nanoTime() to e }
                    e.startsWith("open-failed") -> { failed++; events += System.nanoTime() to e }
                    e.startsWith("hold ") -> { if (holds++ == 0) events += System.nanoTime() to e }
                }
            }
        }
        fun stop() { VideoDecoder.testEvents = null }
        val count get() = open.values.sumOf { it.size }
        /** Cached decoded frames, as the decoders size them (w x h x 1.5 per frame). */
        val frameCacheBytes get() = open.values.flatten().sumOf { it.w.toLong() * it.h * 3 / 2 * it.cache }
        fun sizes() = open.values.flatten().joinToString("+") { "${it.w}x${it.h}" }
    }

    // ---- the trace ---------------------------------------------------------------------------------------------------
    data class Row(val label: String, val ms: Long, val why: String, val clip: Int, val playUs: Long, val pssMb: Double, val graphicsMb: Double,
                   val nativeMb: Double, val javaMb: Double, val dmabufMb: Double, val dmabufCount: Int, val decoders: Int, val decoderSizes: String,
                   val frameCacheMb: Double, val vkImages: Long, val vkMemories: Long, val pcmMb: Double, val memAvailMb: Double, val trims: Int) {
        fun csv() = listOf(label, ms, why, clip, playUs, "%.1f".format(pssMb), "%.1f".format(graphicsMb), "%.1f".format(nativeMb), "%.1f".format(javaMb),
            "%.1f".format(dmabufMb), dmabufCount, decoders, decoderSizes, "%.1f".format(frameCacheMb), vkImages, vkMemories, "%.1f".format(pcmMb),
            "%.0f".format(memAvailMb), trims).joinToString(",")
        companion object { const val HEADER = "label,ms,why,clip,play_us,pss_mb,graphics_mb,native_mb,java_mb,dmabuf_mb,dmabuf_count,decoders,decoder_sizes,frame_cache_mb,vk_images,vk_memories,pcm_alloc_mb,mem_available_mb,trim_callbacks" }
    }

    class Trace(private val context: android.content.Context, private val label: String, private val decoders: DecoderTrack) {
        val rows = CopyOnWriteArrayList<Row>()
        val trims = CopyOnWriteArrayList<Pair<Long, Int>>()
        private val t0 = System.nanoTime()
        private val callbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) { trims += System.nanoTime() to level; say("MP12", "$label: onTrimMemory($level) at ${(System.nanoTime() - t0) / 1_000_000} ms") }
            override fun onConfigurationChanged(newConfig: Configuration) {}
            @Deprecated("") override fun onLowMemory() { trims += System.nanoTime() to -1; say("MP12", "$label: onLowMemory at ${(System.nanoTime() - t0) / 1_000_000} ms") }
        }
        init { context.applicationContext.registerComponentCallbacks(callbacks) }
        fun close() { context.applicationContext.unregisterComponentCallbacks(callbacks) }

        fun sample(why: String, clip: Int, playUs: Long): Row {
            val mi = Debug.MemoryInfo(); Debug.getMemoryInfo(mi)
            fun stat(k: String) = (mi.getMemoryStat(k)?.toLongOrNull() ?: 0L) / 1024.0
            val d = dmabuf(); val l = ledger()
            val r = Row(label, (System.nanoTime() - t0) / 1_000_000, why, clip, playUs, mi.totalPss / 1024.0, stat("summary.graphics"),
                stat("summary.native-heap"), stat("summary.java-heap"), d.bytes / 1048576.0, d.count, decoders.count, decoders.sizes().ifEmpty { "-" },
                decoders.frameCacheBytes / 1048576.0, l?.first ?: -1, l?.second ?: -1, pcmAllocated(context) / 1048576.0, memAvailableKb() / 1024.0, trims.size)
            rows += r; say("MP12CSV", r.csv())
            return r
        }
    }

    /** One clip of a sequence (composition start, end, the source's size / codec / rate). */
    data class Clip(val index: Int, val startUs: Long, val endUs: Long, val uri: String, val name: String, val info: String)
    fun clips(project: ProjectDocument, info: (String) -> String): List<Clip> = project.composition.layers
        .filter { it.type == LayerType.VIDEO && it.visible && it.asset?.uri != null }
        .sortedWith(compareBy({ it.timing.startUs }, { -it.zOrder }))
        .mapIndexed { k, l -> Clip(k + 1, l.timing.startUs, l.timing.endUs ?: Long.MAX_VALUE, l.asset!!.uri!!, l.name, info(l.asset!!.uri!!)) }
    /** The clip on screen at [t] (the latest-starting active one), 0 before the first. */
    fun clipAt(clips: List<Clip>, t: Long): Int = clips.lastOrNull { t >= it.startUs && t < it.endUs }?.index ?: clips.lastOrNull { t >= it.startUs }?.index ?: 0

    // ---- sources -----------------------------------------------------------------------------------------------------
    data class Source(val uri: Uri, val name: String, val bytes: Long, val container: String, val codec: String, val width: Int, val height: Int,
                      val fps: Double, val durationUs: Long, val rotation: Int, val audio: String) {
        val ratio get() = if (rotation % 180 == 0) width.toDouble() / height else height.toDouble() / width
        fun row() = "%s | %s | %s | %dx%d | %.2f | %.1f s | %.1f MB | %d | %s".format(name.replace('|', '/'), container, codec, width, height, fps, durationUs / 1e6, bytes / 1048576.0, rotation, audio)
    }

    /** Every MediaStore video under Download/Seal/ (any volume), as content URIs, with the track metadata (read in place). */
    fun sealVideos(context: android.content.Context): List<Source> {
        val out = ArrayList<Source>()
        val cr = context.contentResolver
        val seen = HashSet<Uri>()
        for (volume in MediaStore.getExternalVolumeNames(context)) {
            val collection = MediaStore.Video.Media.getContentUri(volume)
            cr.query(collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.RELATIVE_PATH),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf("Download/Seal/"), "${MediaStore.MediaColumns.DISPLAY_NAME} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val uri = android.content.ContentUris.withAppendedId(collection, c.getLong(0))
                    if (!seen.add(uri)) continue
                    probe(context, uri, c.getString(1), c.getLong(2))?.let { out += it }
                }
            }
        }
        return out
    }

    fun probe(context: android.content.Context, uri: Uri, name: String, bytes: Long): Source? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(context, uri, null)
            var v: MediaFormat? = null; var vt = -1; var audio = "-"
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i); val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && v == null) { v = f; vt = i } else if (mime.startsWith("audio/") && audio == "-") audio = mime.removePrefix("audio/")
            }
            val f = v ?: return null
            val dur = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else -1L
            var fps = if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrElse { f.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() } else 0.0
            if (fps <= 0) {
                // From the first 2 s of sample times.
                ex.selectTrack(vt); val ts = ArrayList<Long>()
                while (ex.sampleTime >= 0 && ts.size < 121) { ts += ex.sampleTime; ex.advance() }
                ts.sort(); if (ts.size > 2) fps = (ts.size - 1) * 1e6 / (ts.last() - ts.first())
            }
            val rot = if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
            Source(uri, name, bytes, name.substringAfterLast('.', "?").lowercase(), f.getString(MediaFormat.KEY_MIME)!!.removePrefix("video/"),
                f.getInteger(MediaFormat.KEY_WIDTH), f.getInteger(MediaFormat.KEY_HEIGHT), fps, dur, rot, audio)
        } catch (_: Exception) { null } finally { ex.release() }
    }
}
