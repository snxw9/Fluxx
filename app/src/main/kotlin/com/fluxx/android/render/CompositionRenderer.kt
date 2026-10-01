package com.fluxx.android.render

import android.content.Context
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.net.Uri
import android.view.Surface
import com.fluxx.android.engine.*
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import java.io.Closeable
import java.util.LinkedHashMap
import java.util.concurrent.CancellationException

/** A bounded working set, not a layer limit. Extra layers are decoded and composited in batches. */
class CompositionRenderer(private val context: Context, private val repository: MediaRepository,
    surface: Surface? = null, width: Int = 1, height: Int = 1) : Closeable {
    private val interactive = surface != null
    val session = RenderBridge.create(surface,width,height,context.assets).also { check(it != 0L) { "Vulkan rendering is unavailable" } }
    private val lowRam = context.getSystemService(android.app.ActivityManager::class.java).isLowRamDevice
    private val decoderSlots = if (lowRam) 2 else 4
    private val frameBudget = if (lowRam) 48L*1024*1024 else 96L*1024*1024
    private val imageBudget = if (lowRam) 16L*1024*1024 else 32L*1024*1024
    private data class DecoderEntry(val decoder: VideoDecoder, val info: MediaRepository.VideoInfo) {
        val bytes get() = info.width.toLong()*info.height*8 // retained images plus codec surfaces, conservative estimate
    }
    private data class Raster(val buffer: HardwareBuffer, val width: Int, val height: Int) {
        val bytes get() = width.toLong()*height*4
    }
    private val decoders = LinkedHashMap<String,DecoderEntry>(4,0.75f,true)
    private val images = LinkedHashMap<String,Raster>(8,0.75f,true)
    private var prepared: ProjectDocument? = null
    private var plan: FramePlan? = null
    private var staged = 0
    private val matrix = FloatArray(16)
    private val evaluatedTransform = FloatArray(6)
    private data class FixtureLayout(val source: String, val font: String, val handle: Long)
    private val fixtureLayouts = mutableMapOf<Long, FixtureLayout>()
    private var previewQuality: PreviewResolution? = null
    private var previewCompWidth = 0
    private var previewCompHeight = 0
    private var previewTargetWidth = 0
    private var previewTargetHeight = 0
    init { KeyframeEvaluator.easeProgress(0f, EasingPreset.EASY_EASE) } // Initialize LUT outside render().

    private fun flush() {
        if (staged == 0) return
        check(RenderBridge.flush(session)) { "GPU compositing failed" }
        staged = 0
    }
    private fun evictDecoder() {
        flush() // A codec image cannot be released while a pending batch can still sample it.
        val iterator = decoders.entries.iterator()
        if (iterator.hasNext()) { iterator.next().value.decoder.close(); iterator.remove() }
    }
    private fun decoder(uri: String): VideoDecoder {
        decoders[uri]?.let { return it.decoder }
        val info = repository.video(uri)
        while (decoders.isNotEmpty() && (decoders.size >= decoderSlots ||
            decoders.values.sumOf { it.bytes } + info.width.toLong()*info.height*8 > frameBudget ||
            decoders.values.count { it.info.mime == info.mime } >= info.maxInstances)) evictDecoder()
        var opened: VideoDecoder? = null
        try { opened = VideoDecoder(context,Uri.parse(uri)) }
        catch (e: Exception) {
            // Advertised instance counts are advisory. Retry with the smallest working set.
            if (decoders.isEmpty()) throw e
            while (decoders.isNotEmpty()) evictDecoder()
            opened = VideoDecoder(context,Uri.parse(uri))
        }
        return requireNotNull(opened).also { decoders[uri] = DecoderEntry(it,info) }
    }
    private fun raster(layer: CompositionLayer): Raster {
        val key = if(layer.type == LayerType.SOLID) "solid:${layer.solidColorArgb}" else requireNotNull(layer.asset?.uri)
        images[key]?.let { return it }
        val bitmap = if(layer.type == LayerType.SOLID) Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888).apply { eraseColor(layer.solidColorArgb) }
            else repository.image(key)
        val result = try { Raster(requireNotNull(FluxxEngine.bitmapBuffer(bitmap)) { "Could not upload image" },bitmap.width,bitmap.height) }
            finally { bitmap.recycle() }
        while (images.isNotEmpty() && images.values.sumOf { it.bytes } + result.bytes > imageBudget) {
            flush()
            val iterator=images.entries.iterator(); iterator.next().value.buffer.close(); iterator.remove()
        }
        images[key]=result
        return result
    }

    fun render(project: ProjectDocument, timeUs: Long, cancelled: () -> Boolean = { false }) =
        renderTarget(project, timeUs, project.composition.width, project.composition.height, cancelled)

    fun renderPreview(project: ProjectDocument, timeUs: Long, resolution: PreviewResolution,
        cancelled: () -> Boolean = { false }) {
        check(interactive) { "Preview quality is only available on a surface-backed renderer" }
        val comp = project.composition
        if (resolution != previewQuality || comp.width != previewCompWidth || comp.height != previewCompHeight) {
            val (width, height) = resolution.calculateDimensions(comp.width, comp.height)
            previewTargetWidth = width; previewTargetHeight = height
            previewCompWidth = comp.width; previewCompHeight = comp.height; previewQuality = resolution
        }
        renderTarget(project, timeUs, previewTargetWidth, previewTargetHeight, cancelled)
    }

    private fun renderTarget(project: ProjectDocument, timeUs: Long, targetWidth: Int, targetHeight: Int,
        cancelled: () -> Boolean) {
        if (prepared !== project) {
            val previousComp=prepared?.composition
            val nextComp=project.composition
            if (previousComp?.width != nextComp.width || previousComp?.height != nextComp.height) {
                android.util.Log.i("Fluxx", "CompositionRenderer canvas ${nextComp.width}x${nextComp.height}; projecting preserved layer geometry")
            }
            prepared=project
            plan=FramePlan(project)
        }
        val comp=project.composition
        check(RenderBridge.begin(session,targetWidth,targetHeight,comp.width,comp.height)) { "Could not start composition frame" }
        staged=0
        val fixtures = if (com.fluxx.android.BuildConfig.DEBUG) TextFixtureProvider.entries() else emptyList()
        val fixtureIds = fixtures.mapTo(mutableSetOf()) { it.id }
        fixtureLayouts.keys.filter { it !in fixtureIds }.forEach { id ->
            RenderBridge.releaseText(session, requireNotNull(fixtureLayouts.remove(id)).handle)
        }
        fixtures.forEach { entry ->
            val existing = fixtureLayouts[entry.id]
            if (existing?.source != entry.source || existing.font != entry.font) {
                existing?.let { RenderBridge.releaseText(session, it.handle) }
                fixtureLayouts[entry.id] = FixtureLayout(entry.source, entry.font,
                    RenderBridge.upsertText(session, entry.font, entry.source))
            }
        }
        if (fixtures.isNotEmpty()) check(RenderBridge.prepareText(session,
            fixtures.map { requireNotNull(fixtureLayouts[it.id]).handle }.toLongArray())) { "Text preparation failed" }
        var rasterIndex = 0
        fun drawFixtures(index: Int) {
            fixtures.filter { it.beforeRaster == index }.forEach { entry ->
                if (staged == 4) flush()
                // Baseline-relative pixel mesh, with a centred composition-space origin.
                java.util.Arrays.fill(matrix, 0f)
                val radians = Math.toRadians(entry.rotation.toDouble())
                val c = kotlin.math.cos(radians).toFloat(); val s = kotlin.math.sin(radians).toFloat()
                val sx = entry.scale * if (entry.flip) -1f else 1f
                matrix[0] = 2f * c * sx / comp.width; matrix[1] = 2f * s * sx / comp.height
                matrix[4] = -2f * s * entry.scale / comp.width; matrix[5] = 2f * c * entry.scale / comp.height
                matrix[10] = 1f; matrix[15] = 1f
                matrix[12] = entry.x * 2f / comp.width; matrix[13] = entry.y * 2f / comp.height
                check(RenderBridge.textLayer(session, requireNotNull(fixtureLayouts[entry.id]).handle,
                    matrix, entry.size, 0, entry.colour, entry.opacity)) { "Text draw submission failed" }
                staged++
            }
        }
        requireNotNull(plan).forEachActive(timeUs) { layer,localTimeUs,sourceTimeUs,_ ->
            if (cancelled()) throw CancellationException("Superseded frame")
            val isAnim = layer.animTransform.isAnyAnimated()
            val posX: Float
            val posY: Float
            val scaleX: Float
            val scaleY: Float
            val rot: Float
            val opacity: Float
            if (isAnim) {
                layer.evaluatedTransformInto(timeUs, evaluatedTransform)
                posX = evaluatedTransform[0]
                posY = evaluatedTransform[1]
                scaleX = evaluatedTransform[2]
                scaleY = evaluatedTransform[3]
                rot = evaluatedTransform[4]
                opacity = evaluatedTransform[5]
            } else {
                val t = layer.transform
                posX = t.positionX
                posY = t.positionY
                scaleX = t.scaleX
                scaleY = t.scaleY
                rot = t.rotationDegrees
                opacity = t.opacity
            }
            if(opacity == 0f) return@forEachActive
            drawFixtures(rasterIndex++)
            if(staged == 4) flush()
            val referenceWidth=layer.referenceWidth.takeIf { it > 0 } ?: comp.width
            val referenceHeight=layer.referenceHeight.takeIf { it > 0 } ?: comp.height
            if(layer.type == LayerType.VIDEO) {
                val uri=requireNotNull(layer.asset?.uri)
                val decoder=decoder(uri)
                // A source reused by several clips may need a seek; finish earlier uses before advancing it.
                flush()
                val image=decoder.frameAt(sourceTimeUs,cancelled)
                val crop=image.cropRect
                LayerGeometry.matrix(matrix,posX,posY,scaleX,scaleY,rot,comp.width,comp.height,crop.width(),crop.height(),decoder.rotation,decoder.pixelAspect,
                    referenceWidth=referenceWidth,referenceHeight=referenceHeight,anchorX=layer.anchorX,anchorY=layer.anchorY)
                image.hardwareBuffer?.use { buffer ->
                    check(RenderBridge.layer(session,layer.id,buffer,matrix,opacity)) { "Could not import video frame" }
                } ?: error("Decoder returned an inaccessible image")
            } else {
                val raster=raster(layer)
                LayerGeometry.matrix(matrix,posX,posY,scaleX,scaleY,rot,comp.width,comp.height,
                    if(layer.type == LayerType.SOLID) referenceWidth else raster.width,
                    if(layer.type == LayerType.SOLID) referenceHeight else raster.height,
                    referenceWidth=referenceWidth,referenceHeight=referenceHeight,anchorX=layer.anchorX,anchorY=layer.anchorY)
                check(RenderBridge.layer(session,layer.id,raster.buffer,matrix,opacity)) { "Could not composite image" }
            }
            staged++
        }
        drawFixtures(rasterIndex)
        if(cancelled()) throw CancellationException("Superseded frame")
        check(RenderBridge.finish(session)) { "Could not finish composition frame" }
        staged=0
    }
    fun resize(width: Int,height: Int) = RenderBridge.resize(session,width,height)
    override fun close() {
        // Native teardown waits for GPU completion before producers return their Images.
        RenderBridge.destroy(session)
        decoders.values.forEach { it.decoder.close() }; decoders.clear()
        images.values.forEach { it.buffer.close() }; images.clear()
    }
}
