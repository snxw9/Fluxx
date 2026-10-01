package com.fluxx.android.render

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import com.fluxx.android.ProjectManager
import com.fluxx.android.ProjectMetadata
import com.fluxx.android.engine.RenderBridge
import com.fluxx.android.media.CompositionSupport
import com.fluxx.android.media.MediaRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** First composition frame, using the preview/export compositor, never a source-asset substitute. */
class ProjectThumbnails(context: Context, private val projects: ProjectManager) {
    private val app = context.applicationContext
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    suspend fun firstFrame(metadata: ProjectMetadata): Bitmap? = withContext(Dispatchers.Default) {
        val key = "${metadata.file.absolutePath}:${metadata.lastModified}:${metadata.sizeBytes}"
        cache.get(key)?.let { return@withContext it }
        renderLock.withLock {
            cache.get(key)?.let { return@withLock it }
            if (metadata.layerCount == 0) return@withLock null
            try {
                val project = projects.loadProject(metadata.file).getOrThrow()
                val repository = MediaRepository(app)
                CompositionSupport.check(app, repository, project)
                val comp = project.composition
                // copyYuv uses 2x2 chroma blocks; the existing support check rejects odd dimensions.
                val pixels = Math.multiplyExact(comp.width, comp.height)
                val y = ByteBuffer.allocateDirect(pixels)
                val u = ByteBuffer.allocateDirect(pixels / 4)
                val v = ByteBuffer.allocateDirect(pixels / 4)
                val job = currentCoroutineContext()
                CompositionRenderer(app, repository).use { renderer ->
                    renderer.render(project, 0L) { !job.isActive }
                    job.ensureActive()
                    check(RenderBridge.copyYuv(renderer.session, y, u, v,
                        comp.width, comp.width / 2, comp.width / 2, 1, 1))
                }
                job.ensureActive()
                val scale = minOf(1f, 480f / maxOf(comp.width, comp.height))
                val width = (comp.width * scale).roundToInt().coerceAtLeast(1)
                val height = (comp.height * scale).roundToInt().coerceAtLeast(1)
                val colors = IntArray(width * height)
                for (row in 0 until height) {
                    job.ensureActive()
                    val sourceY = (row.toLong() * comp.height / height).toInt()
                    for (col in 0 until width) {
                        val sourceX = (col.toLong() * comp.width / width).toInt()
                        val chroma = (sourceY / 2) * (comp.width / 2) + sourceX / 2
                        val luma = ((y.get(sourceY * comp.width + sourceX).toInt() and 255) - 16) * 1.164384f
                        val cb = (u.get(chroma).toInt() and 255) - 128
                        val cr = (v.get(chroma).toInt() and 255) - 128
                        // Inverse of the renderer's limited-range BT.709 export readback.
                        val red = (luma + 1.792741f * cr).roundToInt().coerceIn(0, 255)
                        val green = (luma - 0.213249f * cb - 0.532909f * cr).roundToInt().coerceIn(0, 255)
                        val blue = (luma + 2.112402f * cb).roundToInt().coerceIn(0, 255)
                        colors[row * width + col] = (255 shl 24) or (red shl 16) or (green shl 8) or blue
                    }
                }
                Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888).also { cache.put(key, it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("Fluxx", "Project thumbnail unavailable", error)
                null
            }
        }
    }

    companion object {
        // At most one thumbnail session/decoder pool across activity recreation and list cells.
        private val renderLock = Mutex()
    }
}
