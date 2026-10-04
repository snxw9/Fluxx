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
    private data class ProductLayout(val handle: Long, val metrics: TextLayoutMetrics)
    private val textLayouts = LinkedHashMap<Triple<String, String, Boolean>, ProductLayout>(64, .75f, true)
    private class PreparedText(val layer: CompositionLayer, val sources: Array<String>, val layouts: Array<ProductLayout>,
        val animations: Array<PreparedTextAnimation>) {
        var lastIndex=0
        fun index(source: String,ligatures: Boolean): Int { var i=0;while(sources[i]!=source) i++;return i*2+(if(ligatures) 0 else 1) }
    }
    private val selectorEase by lazy { SelectorEaseTable() }
    private var preparedText = emptyArray<PreparedText>()
    private var preparedHandles = LongArray(0)
    private var nativePreparedTextProject: ProjectDocument? = null
    private var onlyText = false
    private val logicalBounds = FloatArray(4)
    private fun prepareTextProject(project: ProjectDocument) {
        nativePreparedTextProject = null
        val layers = project.composition.layers
        onlyText = layers.isNotEmpty() && layers.all { it.type == LayerType.TEXT } && TextFixtureProvider.entries().isEmpty()
        preparedText = layers.filter { it.type==LayerType.TEXT }.sortedWith(compareBy<CompositionLayer> { it.zOrder }.thenBy { it.id }).map { layer ->
            layer.text.source.sourceDocument.requireLegacyLayout()
            for(key in layer.text.source.keyframes) key.sourceDocument.requireLegacyLayout()
            val sources = (listOf(layer.text.source.staticValue) + layer.text.source.keyframes.map { it.value }).distinct().toTypedArray()
            require(sources.size<=com.fluxx.android.model.TextSourceDocument.MAX_PREPARED_LAYOUTS/2) {
                "Text layer exceeds the 64 prepared-layout limit; reduce distinct Source Text alternatives to at most 32"
            }
            val layouts=sources.flatMap { source -> listOf(textLayout(layer.text.fontId,source,true),textLayout(layer.text.fontId,source,false)) }.toTypedArray()
            PreparedText(layer,sources,layouts,Array(layouts.size) { requireNotNull(layouts[it].metrics.prepareAnimation(layer.text,selectorEase)) })
        }.toTypedArray()
        preparedHandles = preparedText.flatMap { it.layouts.map { layout -> layout.handle } }.toLongArray()
        // Release obsolete layouts; active source-key layouts remain pinned until the next project preparation.
        val iterator = textLayouts.entries.iterator()
        while (iterator.hasNext()) { val entry = iterator.next()
            if (entry.value.handle !in preparedHandles) { RenderBridge.releaseText(session,entry.value.handle); iterator.remove() }
        }
    }
    private fun renderPreparedText(project: ProjectDocument, time: Long, cancelled: () -> Boolean) {
        require(time >= 0)
        val comp = project.composition
        try {
            if (nativePreparedTextProject === project) check(RenderBridge.reusePreparedText(session))
            else { check(RenderBridge.prepareText(session,preparedHandles)); nativePreparedTextProject = project }
        }
        catch (error: TextCapacityException) {
            val ids = preparedText.map { it.layer.id }.toSet()
            if (interactive) TextLayerErrors.capacity(project,ids,error.message.orEmpty())
            throw TextFrameCapacityException(ids,error.message.orEmpty(),error)
        }
        if (interactive) TextLayerErrors.clear()
        for (entry in preparedText) {
            if (cancelled()) throw CancellationException("Superseded frame")
            val layer = entry.layer
            if (!layer.visible || !layer.timing.isActive(time) || (comp.durationUs != null && time >= comp.durationUs)) continue
            layer.evaluatedTransformInto(time,evaluatedTransform)
            val opacity = evaluatedTransform[5]; if (opacity == 0f) continue
            val local = time-layer.resolvedKeyframeAnchorUs
            val source = layer.text.source.evaluate(local)
            val index = entry.index(source,layer.text.effectiveLigatures(local))
            entry.lastIndex=index
            val layout = entry.layouts[index]; val text = layer.text
            val size = text.size.evaluate(local); val tracking = text.tracking.evaluate(local)
            val leading = text.leading.evaluate(local); val baseline = text.baselineShift.evaluate(local)
            val animation=entry.animations[index];animation.evaluate(local)
            layout.metrics.logicalInto(size,tracking,leading,baseline,logicalBounds)
            LayerGeometry.textMatrixInto(matrix,evaluatedTransform,logicalBounds,comp.width,comp.height,
                layer.referenceWidth.takeIf { it > 0 } ?: comp.width,layer.referenceHeight.takeIf { it > 0 } ?: comp.height,layer.anchorX,layer.anchorY)
            if (staged == 4) flush()
            check(RenderBridge.textLayer(session,layout.handle,matrix,size,text.alignment.ordinal,text.fill.evaluate(local),opacity,
                tracking,leading,baseline,text.strokeColour.evaluate(local),text.strokeWidth.evaluate(local),text.paintOrder(local),modifiers=animation.modifiers))
            staged++
        }
        if (cancelled()) throw CancellationException("Superseded frame")
        check(RenderBridge.finish(session)); staged = 0
    }
    private var previewQuality: PreviewResolution? = null
    private var previewCompWidth = 0
    private var previewCompHeight = 0
    private var previewTargetWidth = 0
    private var previewTargetHeight = 0
    private fun textLayout(font: String, source: String,ligatures: Boolean=true): ProductLayout = textLayouts.getOrPut(Triple(font,source,ligatures)) {
        val handle = RenderBridge.upsertText(session, font, source,ligatures)
        try { ProductLayout(handle, TextLayoutMetrics.fromNative(RenderBridge.textMetrics(session, handle), source.isEmpty())) }
        catch (error: Exception) { RenderBridge.releaseText(session, handle); throw error }
    }
    init { KeyframeEvaluator.easeProgress(0f, EasingPreset.EASY_EASE); RenderBridge.configureText(session, if(lowRam) 2 else 4) }

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
            prepareTextProject(project)
        }
        val comp=project.composition
        check(RenderBridge.begin(session,targetWidth,targetHeight,comp.width,comp.height)) { "Could not start composition frame" }
        staged=0
        if (onlyText && TextFixtureProvider.entries().isEmpty()) { renderPreparedText(project,timeUs,cancelled); return }
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
        val textForFrame = mutableMapOf<Long, ProductLayout>()
        val textAnimationsForFrame = mutableMapOf<Long,PreparedTextAnimation>()
        requireNotNull(plan).forEachActive(timeUs) { layer, _, _, _ ->
            if (layer.type == LayerType.TEXT && layer.evaluatedTransform(timeUs).opacity != 0f) {
                val local = timeUs - layer.resolvedKeyframeAnchorUs
                val source = layer.text.source.evaluate(local)
                val entry=preparedText.first { it.layer.id==layer.id }
                val index=entry.index(source,layer.text.effectiveLigatures(local))
                entry.lastIndex=index
                textForFrame[layer.id]=entry.layouts[index]
                textAnimationsForFrame[layer.id]=entry.animations[index]
            }
        }
        val handles = fixtures.map { requireNotNull(fixtureLayouts[it.id]).handle } + preparedHandles.toList()
        if (handles.isNotEmpty()) try {
            if(fixtures.isEmpty() && nativePreparedTextProject===project) check(RenderBridge.reusePreparedText(session))
            else { check(RenderBridge.prepareText(session, handles.toLongArray(), fixtures.isNotEmpty() && preparedHandles.isEmpty())) { "Text preparation failed" };if(fixtures.isEmpty()) nativePreparedTextProject=project }
        } catch (error: TextCapacityException) {
            if (textForFrame.isEmpty()) throw error
            if (interactive) TextLayerErrors.capacity(project, textForFrame.keys.toSet(), error.message.orEmpty())
            throw TextFrameCapacityException(textForFrame.keys.toSet(), error.message.orEmpty(), error)
        }
        if (interactive) TextLayerErrors.clear()
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
            if(layer.type == LayerType.TEXT) {
                val local = timeUs - layer.resolvedKeyframeAnchorUs
                val layout = requireNotNull(textForFrame[layer.id])
                val size = layer.text.size.evaluate(local)
                val animation=requireNotNull(textAnimationsForFrame[layer.id]);animation.evaluate(local)
                LayerGeometry.textMatrix(matrix, layer.evaluatedTransform(timeUs), layout.metrics.logical(layer.text, local),
                    comp.width, comp.height, referenceWidth, referenceHeight, layer.anchorX, layer.anchorY)
                check(RenderBridge.textLayer(session, layout.handle, matrix, size, layer.text.alignment.ordinal,
                    layer.text.fill.evaluate(local), opacity, layer.text.tracking.evaluate(local), layer.text.leading.evaluate(local),
                    layer.text.baselineShift.evaluate(local), layer.text.strokeColour.evaluate(local), layer.text.strokeWidth.evaluate(local), layer.text.paintOrder(local),modifiers=animation.modifiers)) { "Could not composite text" }
            } else if(layer.type == LayerType.VIDEO) {
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
        val iterator = textLayouts.entries.iterator()
        var idle = textLayouts.values.count { it.handle !in preparedHandles }
        while (iterator.hasNext() && idle > 64) {
            val entry = iterator.next()
            if (entry.value.handle !in preparedHandles) { RenderBridge.releaseText(session, entry.value.handle); iterator.remove(); idle-- }
        }
    }
    /** Harness reads the production layout handle after preparation; no alternate renderer. */
    internal fun preparedTextHandle(layerId: Long): Long {
        for (entry in preparedText) if (entry.layer.id == layerId) return entry.layouts[0].handle
        error("Text layer has not been prepared")
    }
    internal fun textEvaluationCounters(layerId: Long,out: LongArray) {
        require(out.size>=3)
        for(entry in preparedText) if(entry.layer.id==layerId) {
            val current=entry.animations[entry.lastIndex]
            var builds=0L;for(animation in entry.animations) builds+=animation.coverageBuilds
            out[0]=current.selectorNs;out[1]=current.geometryNs;out[2]=builds;return
        }
        error("Text layer has not been prepared")
    }
    fun resize(width: Int,height: Int) = RenderBridge.resize(session,width,height)
    override fun close() {
        // Native teardown waits for GPU completion before producers return their Images.
        RenderBridge.destroy(session)
        if (interactive) TextLayerErrors.clear()
        decoders.values.forEach { it.decoder.close() }; decoders.clear()
        images.values.forEach { it.buffer.close() }; images.clear()
    }
}

class TextFrameCapacityException(val layerIds: Set<Long>, message: String, cause: Throwable) : IllegalStateException(message, cause)
