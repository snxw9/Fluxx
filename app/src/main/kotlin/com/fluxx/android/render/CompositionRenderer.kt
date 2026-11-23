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
    private data class ProductLayout(val handle: Long, val metrics: TextLayoutMetrics,val packetBytes: Int=0)
    private val fontLibrary=com.fluxx.android.fonts.FontLibraryRepository.getInstance(context)
    // TEXT FIX 9c: imported-font atlas tiers come from the isolated worker. Supplies may arrive on the fetch thread after
    // the frame that asked; [tierLock] + [closed] keep them away from a destroyed session.
    private val tierLock=Any()
    private var closed=false
    private val tierFetcher=com.fluxx.android.fonts.ImportedTierFetcher(
        fonts={ ids -> fontLibrary.inputs(ids) },
        rasterise={ requests,inputs -> com.fluxx.android.fonts.FontWorkerClient(context).tiers(requests,inputs) },
        supply={ reply -> synchronized(tierLock) { if(!closed) RenderBridge.supplyTextTierGlyphs(session,reply) } },
        executor=TIER_EXECUTOR,
        log={ message,failure -> android.util.Log.w("FluxxText",message,failure) },
        cache=com.fluxx.android.fonts.TierGlyphCache.shared) // TEXT PERF 11: survives renderer rebuilds (Home, export).
    private var preparedFontRevision= -1L
    private val textLayouts = LinkedHashMap<Triple<String, TextSourceDocument, Boolean>, ProductLayout>(64, .75f, true)
    private class PreparedText(val layer: CompositionLayer, val sources: Array<TextSourceDocument>, val layouts: Array<ProductLayout>,
        val catalogueHandles: LongArray,
        val animations: Array<PreparedTextAnimation>) {
        private val sourceIndices=java.util.IdentityHashMap<TextSourceDocument,Int>().also { map ->
            for(document in listOf(layer.text.source.sourceDocument)+layer.text.source.keyframes.map { it.sourceDocument })
                map[document]=sources.indexOf(document)
        }
        var lastIndex=0
        fun index(source: TextSourceDocument,ligatures: Boolean): Int = requireNotNull(sourceIndices[source])*2+(if(ligatures) 0 else 1)
        // SDF tier state, prepared once so the per-frame tier policy allocates nothing (TEXT FIX 9d).
        /** Distinct non-whitespace characters per source alternative: the atlas estimate for this layer. */
        val glyphCounts=IntArray(sources.size) { s -> sources[s].text.filterNot { it.isWhitespace() }.toSet().size.coerceAtLeast(1) }
        /** Every native handle this layer draws with (layouts and per-character catalogues). */
        val tierHandles=(layouts.map { it.handle }+catalogueHandles.toList()).distinct().toLongArray()
        var tier=SdfAtlasTiers.NO_TIER
        /** TEXT PERF 11: largest keyframed font size; the idle tier warm-up reaches the tiers this layer will need. */
        val sizeMax=layer.text.size.let { size -> maxOf(kotlin.math.abs(size.staticValue),
            size.keyframes.maxOfOrNull { kotlin.math.abs(it.value) } ?: 0f) }
    }
    private val selectorEase by lazy { SelectorEaseTable() }
    private var preparedText = emptyArray<PreparedText>()
    private var preparedHandles = LongArray(0)
    private val characterHandles=mutableListOf<Long>()
    private var nativePreparedTextProject: ProjectDocument? = null
    private var onlyText = false
    private var evaluationsWarmed = false
    private val logicalBounds = FloatArray(4)
    private fun prepareTextProject(project: ProjectDocument) {
        nativePreparedTextProject = null
        RenderBridge.clearTextCharacterBindings(session)
        for(handle in characterHandles) RenderBridge.releaseText(session,handle)
        characterHandles.clear()
        var characterBytes=0L
        val characterErrors=HashMap<Long,String>()
        // TEXT PERF 11: one track memo per prepared project, used only on this renderer's thread. Equal tracks of
        // different layers (duplicated layers, shared animators) are evaluated once per frame time.
        val memo=TrackMemo()
        val layers = project.composition.layers
        val wanted=layers.filter { it.type==LayerType.TEXT }.flatMap { layer ->
            StyledTextPreparation.alternatives(layer.text.source).flatMap { source ->
                val shaping=StyledTextPreparation.shapingDocument(source)
                listOf(Triple(layer.text.fontId,shaping,true),Triple(layer.text.fontId,shaping,false)) }
        }.toHashSet()
        val obsolete=textLayouts.entries.iterator()
        while(obsolete.hasNext()) { val entry=obsolete.next();if(entry.key !in wanted) {
            RenderBridge.releaseText(session,entry.value.handle);obsolete.remove()
        } }
        onlyText = layers.isNotEmpty() && layers.all { it.type == LayerType.TEXT } && TextFixtureProvider.entries().isEmpty()
        preparedText = layers.filter { it.type==LayerType.TEXT }.sortedWith(compareBy<CompositionLayer> { it.zOrder }.thenBy { it.id }).map { layer ->
            val sources = StyledTextPreparation.alternatives(layer.text.source)
            val linePool=LinePermutationPool()
            val effective=layer.text.effectiveLigatures(0)
            val layouts=sources.flatMap { source ->
                val first=textLayout(layer.text.fontId,source,effective)
                val other=if(StyledTextPreparation.needsBothModes(layer.text)) textLayout(layer.text.fontId,source,!effective) else first
                if(effective) listOf(first,other) else listOf(other,first)
            }.toTypedArray()
            // TEXT F3.1: a Character offset / value animation that cannot be prepared (codepoint or catalogue caps) never
            // fails the preview: the layer is prepared without those overrides and the error is published for the UI.
            // Export keeps the explicit failure (no silent degradation in a rendered file).
            var animatedText=layer.text
            val catalogue=try { TextCharacterCataloguePreparation.prepare(context,layer.text.fontId,layer.text,sources) }
            catch(error: IllegalArgumentException) {
                if(!interactive || !TextCharacterPreparation.needed(layer.text)) throw error
                characterErrors[layer.id]=error.message ?: "Character animation cannot be prepared"
                animatedText=TextCharacterPreparation.withoutCharacters(layer.text);null
            }
            val handles=catalogue?.packets?.map { packet->
                characterBytes+=packet.size
                require(characterBytes+textLayouts.values.sumOf { it.packetBytes.toLong() }<=64L*1024*1024) {
                    "Prepared text/character packets exceed 64 MiB; reduce fonts or source/character alternatives"
                }
                RenderBridge.upsertPreparedText(session,packet).also { characterHandles.add(it) }
            }?.toLongArray()
            val animations=Array(layouts.size) { requireNotNull(layouts[it].metrics.prepareAnimation(animatedText,selectorEase,sources[it/2],linePool,catalogue?.catalogue,memo)) }
            // TEXT F3.2: a seed animation over the per-selector cap renders clamped (nearest prepared seed); warn in the preview.
            if(interactive) animations.firstNotNullOfOrNull { it.seedWarning }?.let { characterErrors.putIfAbsent(layer.id,it) }
            if(handles!=null) for(i in animations.indices) animations[i].characters?.let { characters->
                RenderBridge.bindTextCharacters(session,layouts[i].handle,handles,characters.catalogue.codepoints,characters.fontIndices,
                    sources[i/2].text,animations[i].modifiers,characters.codes)
            }
            PreparedText(layer,sources,layouts,handles ?: LongArray(0),animations)
        }.toTypedArray()
        require(characterBytes+textLayouts.values.sumOf { it.packetBytes.toLong() }<=64L*1024*1024) { "Prepared text/character packets exceed 64 MiB; reduce fonts or alternatives" }
        if(interactive) TextLayerErrors.characters(characterErrors)
        preparedHandles = (preparedText.flatMap { it.layouts.map { layout -> layout.handle } }+characterHandles).distinct().toLongArray()
        // TEXT PERF 11: compile the animator evaluation in idle time before the first Play (once per preview renderer).
        if (interactive && !evaluationsWarmed) {
            val animated = preparedText.filter { it.layer.text.animators.isNotEmpty() }.distinctBy { it.layer.text }.take(4)
            if (animated.isNotEmpty()) {
                evaluationsWarmed = true
                TextIdlePrewarm.evaluations(animated.map { Triple(it.layouts[0].metrics, it.layer.text, it.sources[0]) })
            }
        }
        // Release obsolete layouts; active source-key layouts remain pinned until the next project preparation.
        val iterator = textLayouts.entries.iterator()
        while (iterator.hasNext()) { val entry = iterator.next()
            if (entry.value.handle !in preparedHandles) { RenderBridge.releaseText(session,entry.value.handle); iterator.remove() }
        }
    }
    private fun renderPreparedText(project: ProjectDocument, time: Long, cancelled: () -> Boolean) {
        require(time >= 0)
        val comp = project.composition
        val preparing = System.nanoTime()
        try {
            if (nativePreparedTextProject === project) check(RenderBridge.reusePreparedText(session))
            else { check(RenderBridge.prepareText(session,preparedHandles)); nativePreparedTextProject = project }
            stageNs[STAGE_PREPARE] += System.nanoTime() - preparing
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
            val source = layer.text.source.evaluateDocument(local)
            val index = entry.index(source,layer.text.effectiveLigatures(local))
            entry.lastIndex=index
            val layout = entry.layouts[index]; val text = layer.text
            val size = text.sizeAt(local); val tracking = text.tracking.evaluate(local)
            val leading = text.leading.evaluate(local); val baseline = text.baselineShift.evaluate(local)
            val animation=entry.animations[index]
            val evaluating = System.nanoTime(); animation.evaluate(local); stageNs[STAGE_EVALUATE] += System.nanoTime() - evaluating
            animation.logicalBounds.copyInto(logicalBounds)
            LayerGeometry.textMatrixInto(matrix,evaluatedTransform,logicalBounds,comp.width,comp.height,
                layer.referenceWidth.takeIf { it > 0 } ?: comp.width,layer.referenceHeight.takeIf { it > 0 } ?: comp.height,layer.anchorX,layer.anchorY)
            if (staged == 4) flush()
            val meshing = System.nanoTime()
            check(RenderBridge.textLayer(session,layout.handle,matrix,size,text.alignment.ordinal,text.fill.evaluate(local),opacity,
                tracking,leading,baseline,text.strokeColour.evaluate(local),text.strokeWidthAt(local),text.paintOrder(local),modifiers=animation.modifiers))
            stageNs[STAGE_MESH] += System.nanoTime() - meshing
            stageNs[STAGE_LAYERS]++
            staged++
        }
        if (cancelled()) throw CancellationException("Superseded frame")
        val finishing = System.nanoTime()
        check(RenderBridge.finish(session)); staged = 0
        stageNs[STAGE_FINISH] += System.nanoTime() - finishing
        if(interactive && TextEditTimings.waitingFor(project)) TextEditTimings.frameReady(project,RenderBridge.textStats(session))
    }
    // ---- SDF atlas tiers -------------------------------------------------------------------------------------
    private var lastPreviewStart = 0L
    private var lastPreviewEnd = 0L
    private var lastPreviewTime = -1L
    private var streamingRate: com.fluxx.android.model.FrameRate? = null
    private var streamingSpanUs = 0L
    // Per-frame tier state, rebuilt only when the prepared text changes (TEXT FIX 9d: the F1 zero-allocation window
    // covers this path; TEXT UI 6's HashMap/ArrayList/Request/boxing version allocated ~90 objects per frame).
    private var tierPlanFor: Array<PreparedText>? = null
    private var tierAllHandles = LongArray(0)
    private var tierSlots = emptyArray<IntArray>()
    private var tierPpems = IntArray(0)
    private var tierGlyphs = IntArray(0)
    private var tierHandleTiers = IntArray(0)
    private var tierSent = IntArray(0)
    private var tierWarm = IntArray(0)
    private var tierWarmHandles = IntArray(0)
    private var tierWarmSent = IntArray(0)
    private fun tierPlan() {
        if (tierPlanFor === preparedText) return
        val handles = preparedText.flatMap { it.tierHandles.toList() }.distinct().toLongArray()
        tierAllHandles = handles
        tierSlots = Array(preparedText.size) { e -> IntArray(preparedText[e].tierHandles.size) { handles.indexOf(preparedText[e].tierHandles[it]) } }
        tierPpems = IntArray(preparedText.size); tierGlyphs = IntArray(preparedText.size)
        tierHandleTiers = IntArray(handles.size); tierSent = IntArray(handles.size) { -1 }
        tierWarm = IntArray(preparedText.size); tierWarmHandles = IntArray(handles.size); tierWarmSent = IntArray(handles.size) { SdfAtlasTiers.BASE }
        tierPlanFor = preparedText
    }
    /**
     * Picks each text layer's SDF tier from its on-screen pixels per em (with hysteresis) and hands the result to the
     * native renderer before preparation. Inactive layers drop to the base tier. Export generates inline (synchronous);
     * preview generates off-thread and uses the previous tier until the sharper glyphs are ready. Steady state
     * allocates nothing: preallocated arrays, indexed loops, and the native call only when a handle's tier changes.
     */
    private fun applyTextTiers(project: ProjectDocument, timeUs: Long, outputScale: Float) {
        if (preparedText.isEmpty()) return
        tierPlan()
        val comp = project.composition
        for (e in preparedText.indices) {
            val entry = preparedText[e]
            val layer = entry.layer
            val active = layer.visible && layer.timing.isActive(timeUs) && (comp.durationUs == null || timeUs < comp.durationUs)
            val local = timeUs - layer.resolvedKeyframeAnchorUs
            val source = layer.text.source.evaluateDocument(local)
            val index = entry.index(source, layer.text.effectiveLigatures(local))
            val tier = if (!active) SdfAtlasTiers.BASE else {
                layer.evaluatedTransformInto(timeUs, evaluatedTransform)
                val size = layer.text.sizeAt(local)
                val runs = source.runs
                var runScale = 0f; var bold = false
                for (r in 0 until runs.size) { val style = runs[r].style
                    runScale = maxOf(runScale, style.sizeFactor * maxOf(style.horizontalScale, style.verticalScale) / 100f); bold = bold || style.fauxBold }
                if (runs.size == 0) runScale = 1f
                val animation = entry.animations[index]
                val evaluating = System.nanoTime(); animation.evaluate(local); stageNs[STAGE_EVALUATE] += System.nanoTime() - evaluating
                val referenceWidth = if (layer.referenceWidth > 0) layer.referenceWidth else comp.width
                val referenceHeight = if (layer.referenceHeight > 0) layer.referenceHeight else comp.height
                val projection = maxOf(comp.width.toFloat() / referenceWidth, comp.height.toFloat() / referenceHeight)
                val layerScale = maxOf(kotlin.math.abs(evaluatedTransform[2]), kotlin.math.abs(evaluatedTransform[3])) * projection
                val px = SdfAtlasTiers.pixelsPerEm(size, runScale, animation.maxGlyphScale(), layerScale, outputScale)
                val distance = SdfAtlasTiers.distanceEm(layer.text.strokeWidthAt(local), size, bold,
                    animation.maxModifierDistanceEm())
                // TEXT PERF 11: the tiers this layer reaches as its keyframed size grows (the current tier included).
                if (interactive) tierWarm[e] = minOf(SdfAtlasTiers.ideal(if (size > 0f && entry.sizeMax > size) px * (entry.sizeMax / size) else px),
                    SdfAtlasTiers.maxTierFor(distance))
                SdfAtlasTiers.selectTier(px, entry.tier, distance)
            }
            if (!active) tierWarm[e] = SdfAtlasTiers.BASE
            entry.tier = tier
            tierPpems[e] = tier; tierGlyphs[e] = entry.glyphCounts[index / 2]
        }
        // Stay inside the shared atlas cap: highest tiers step down first (native packing degrades further if needed).
        SdfAtlasTiers.degradeInPlace(tierPpems, tierGlyphs, preparedText.size, SdfAtlasTiers.capBytes(lowRam))
        java.util.Arrays.fill(tierHandleTiers, SdfAtlasTiers.BASE)
        var sharp = false
        for (e in preparedText.indices) for (slot in tierSlots[e]) {
            if (tierPpems[e] > tierHandleTiers[slot]) tierHandleTiers[slot] = tierPpems[e]
            if (tierPpems[e] > SdfAtlasTiers.BASE) sharp = true
        }
        val tierJni = System.nanoTime()
        if (!tierHandleTiers.contentEquals(tierSent)) {
            RenderBridge.textTiers(session, tierAllHandles, tierHandleTiers, !interactive)
            System.arraycopy(tierHandleTiers, 0, tierSent, 0, tierSent.size)
        }
        if (interactive) {
            // Idle-priority warm-up targets per handle; renderPreview sends them from settled frames only.
            java.util.Arrays.fill(tierWarmHandles, SdfAtlasTiers.BASE)
            for (e in preparedText.indices) for (slot in tierSlots[e]) if (tierWarm[e] > tierWarmHandles[slot]) tierWarmHandles[slot] = tierWarm[e]
        }
        stageNs[STAGE_TIER_JNI] += System.nanoTime() - tierJni
        if (!sharp) return // Base tier only: nothing for the isolated worker.
        val requestJni = System.nanoTime()
        // Imported-font glyphs the renderer cannot rasterise itself: preview fetches them in the background (the lower
        // tier stays until they arrive); export fetches them now, so every exported frame is deterministic.
        var tierRequests = synchronized(tierLock) { RenderBridge.textTierRequests(session) }
        stageNs[STAGE_TIER_JNI] += System.nanoTime() - requestJni
        if (interactive) { if (tierRequests != null) tierFetcher.submit(tierRequests) }
        else for (round in 0 until EXPORT_TIER_ROUNDS) {
            if (tierRequests == null) break
            tierFetcher.fetchNow(tierRequests)
            tierRequests = synchronized(tierLock) { RenderBridge.textTierRequests(session) } // Deferred keys come back here.
        }
    }

    /**
     * TEXT PERF 11: sends the idle tier warm-up targets, from settled frames only (open, pause, seek). During playback
     * and scrubbing the native queue would not run anyway, and a target on a tier boundary (size × reach ≈ a tier's
     * sharp limit) flipped between frames and re-sent, allocating inside F1's zero-allocation window. Targets only rise
     * within a prepared project, so boundary jitter never re-sends.
     */
    private fun requestTierWarmUp() {
        if (tierPlanFor !== preparedText || tierWarmSent.isEmpty()) return
        var raised = false
        for (i in tierWarmHandles.indices) if (tierWarmHandles[i] > tierWarmSent[i]) { tierWarmSent[i] = tierWarmHandles[i]; raised = true }
        if (raised) RenderBridge.warmTextTiers(session, tierAllHandles, tierWarmSent)
    }
    private var previewQuality: PreviewResolution? = null
    private var previewCompWidth = 0
    private var previewCompHeight = 0
    private var previewTargetWidth = 0
    private var previewTargetHeight = 0
    private fun textLayout(font: String, source: TextSourceDocument,ligatures: Boolean=true): ProductLayout = textLayouts.getOrPut(Triple(font,StyledTextPreparation.shapingDocument(source),ligatures)) {
        TextIdlePrewarm.foreground(context)
        val preparationStart=System.nanoTime()
        require(source.paragraphs.all { it.direction==ParagraphDirection.LTR }) { "RTL layout requires T5; the requested document is preserved" }
        var packetBytes=0
        val handle = if(com.fluxx.android.fonts.ImportedTextPreparation.needed(source)) {
            val result=com.fluxx.android.fonts.ImportedTextPreparation.prepare(context,font,source,ligatures)
            packetBytes=result.packet.size
            require(textLayouts.values.sumOf { it.packetBytes.toLong() }+packetBytes<=64L*1024*1024) {
                "Imported text preparation exceeds 64 MiB; reduce distinct source documents or font faces"
            }
            RenderBridge.upsertPreparedText(session,result.packet)
        } else RenderBridge.upsertStyledText(session, font, StyledTextPreparation.encode(source,font),ligatures)
        try { ProductLayout(handle, TextLayoutMetrics.fromNative(RenderBridge.textMetrics(session, handle), source.text.isEmpty(),source),packetBytes)
            .also {TextEditTimings.record("renderer_preparation",System.nanoTime()-preparationStart)
                TextIdlePrewarm.partner(context,font,StyledTextPreparation.shapingDocument(source),!ligatures)} }
        catch (error: Exception) { RenderBridge.releaseText(session, handle); throw error }
    }
    init { KeyframeEvaluator.easeProgress(0f, EasingPreset.EASY_EASE); RenderBridge.configureText(session, SdfAtlasTiers.pages(lowRam)) }

    private fun flush() {
        if (staged == 0) return
        check(RenderBridge.flush(session)) { "GPU compositing failed" }
        stageNs[STAGE_FLUSHES]++
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

    fun render(project: ProjectDocument, timeUs: Long, cancelled: () -> Boolean = NEVER_CANCELLED) =
        renderTarget(project, timeUs, project.composition.width, project.composition.height, cancelled)

    fun renderPreview(project: ProjectDocument, timeUs: Long, resolution: PreviewResolution,
        cancelled: () -> Boolean = NEVER_CANCELLED) {
        check(interactive) { "Preview quality is only available on a surface-backed renderer" }
        val comp = project.composition
        if (resolution != previewQuality || comp.width != previewCompWidth || comp.height != previewCompHeight) {
            val (width, height) = resolution.calculateDimensions(comp.width, comp.height)
            previewTargetWidth = width; previewTargetHeight = height
            previewCompWidth = comp.width; previewCompHeight = comp.height; previewQuality = resolution
        }
        val framing = System.nanoTime(); android.os.Trace.beginSection("fluxx.frame")
        try { renderTarget(project, timeUs, previewTargetWidth, previewTargetHeight, cancelled) } finally { android.os.Trace.endSection() }
        stageNs[STAGE_FRAME] += System.nanoTime() - framing
        PreviewFrames.presented()
        // Sharper SDF tiers are generated off-thread; this frame used the previous tier. Unless frames are already
        // streaming (playback), wait (cancellably) for the glyphs and redraw once so a settled frame becomes sharp.
        val now = System.nanoTime()
        // Cached per frame rate: FrameRate's exact arithmetic allocates (~19 objects), and this runs every preview frame.
        if (comp.frameRate != streamingRate) { streamingRate = comp.frameRate; streamingSpanUs = comp.frameRate.let { it.frameTimeUs(3) - it.frameTimeUs(0) } }
        // TEXT PERF 11: frames stream (playback, forward scrubbing) when calls follow each other back to back (the previous
        // call ended < 100 ms before this one began) and time moves forward by no more than the wall time between the two
        // calls plus three frames. TEXT FIX 9 measured end to end and allowed three frames of progress, so any frame over
        // ~96 ms, or playback below 20 fps (30 text layers with size animation, a renderer rebuilt after Home), looked like
        // a seek: each such frame then blocked up to 1.5 s for sharp tiers while the audio clock ran on, and playback
        // stuttered and skipped ahead.
        val elapsedUs = (framing - lastPreviewStart) / 1000
        val streaming = lastPreviewTime >= 0 && timeUs > lastPreviewTime && framing - lastPreviewEnd < 100_000_000L &&
            timeUs - lastPreviewTime <= elapsedUs + streamingSpanUs
        lastPreviewStart = framing; lastPreviewTime = timeUs
        if (!streaming) requestTierWarmUp()
        try {
            if (streaming || RenderBridge.awaitTextTiers(session, 0) == 0) return
            val deadline = now + 1_500_000_000L
            // TEXT PERF 11: imported-font tiers come from the isolated worker and took > 1.5 s for 15 glyphs at 384 ppem;
            // the frame then stayed blurry because nothing redraws a settled preview. While such a fetch is in flight the
            // wait may run to 6 s; any new request (seek, edit, play) still cancels it at once.
            val importedDeadline = now + 6_000_000_000L
            while (!cancelled()) {
                val limit = if (tierFetcher.inFlight > 0) importedDeadline else deadline
                if (System.nanoTime() >= limit) break
                if (RenderBridge.awaitTextTiers(session, 8) == 0) {
                    if (!cancelled()) { renderTarget(project, timeUs, previewTargetWidth, previewTargetHeight, cancelled); PreviewFrames.presented() }
                    return
                }
            }
        } finally { lastPreviewEnd = System.nanoTime() }
    }

    private fun renderTarget(project: ProjectDocument, timeUs: Long, targetWidth: Int, targetHeight: Int,
        cancelled: () -> Boolean) {
        TextIdlePrewarm.frameRendered(System.nanoTime()) // TEXT PERF 10: idle prewarm stays paused while frames render.
        // Export pins the prepared resource snapshot; interactive previews refresh library edits.
        if((interactive || prepared!==project) && preparedFontRevision!=fontLibrary.revision) {
            for(layout in textLayouts.values) RenderBridge.releaseText(session,layout.handle)
            textLayouts.clear();prepared=null;preparedFontRevision=fontLibrary.revision
        }
        if (prepared !== project) {
            val previousComp=prepared?.composition
            val nextComp=project.composition
            if (previousComp?.width != nextComp.width || previousComp?.height != nextComp.height) {
                android.util.Log.i("Fluxx", "CompositionRenderer canvas ${nextComp.width}x${nextComp.height}; projecting preserved layer geometry")
            }
            prepared=null // Preparation can release obsolete handles; a failure must never reuse the old frame plan.
            val nextPlan=FramePlan(project)
            prepareTextProject(project)
            plan=nextPlan
            prepared=project
        }
        val comp=project.composition
        check(RenderBridge.begin(session,targetWidth,targetHeight,comp.width,comp.height)) { "Could not start composition frame" }
        staged=0
        val outputScale=if(comp.width>0) targetWidth.toFloat()/comp.width else 1f
        val tiering = System.nanoTime(); android.os.Trace.beginSection("fluxx.tiers")
        try { applyTextTiers(project,timeUs,outputScale) } finally { android.os.Trace.endSection() }
        stageNs[STAGE_TIERS] += System.nanoTime() - tiering
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
                val source = layer.text.source.evaluateDocument(local)
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
                val size = layer.text.sizeAt(local)
                val animation=requireNotNull(textAnimationsForFrame[layer.id]);animation.evaluate(local)
                layer.evaluatedTransformInto(timeUs,evaluatedTransform)
                LayerGeometry.textMatrixInto(matrix, evaluatedTransform, animation.logicalBounds,
                    comp.width, comp.height, referenceWidth, referenceHeight, layer.anchorX, layer.anchorY)
                check(RenderBridge.textLayer(session, layout.handle, matrix, size, layer.text.alignment.ordinal,
                    layer.text.fill.evaluate(local), opacity, layer.text.tracking.evaluate(local), layer.text.leading.evaluate(local),
                    layer.text.baselineShift.evaluate(local), layer.text.strokeColour.evaluate(local), layer.text.strokeWidthAt(local), layer.text.paintOrder(local),modifiers=animation.modifiers)) { "Could not composite text" }
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
        if(interactive && TextEditTimings.waitingFor(project)) TextEditTimings.frameReady(project,RenderBridge.textStats(session))
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
    internal fun textLinePermutationCounters(layerId: Long,out: LongArray) {
        require(out.size>=2)
        for(entry in preparedText) if(entry.layer.id==layerId) {
            val animation=entry.animations[0]
            out[0]=animation.linePermutationBuilds;out[1]=animation.linePermutationBytes;return
        }
        error("Text layer has not been prepared")
    }
    /**
     * TEXT PERF 10 frame stage profile, in ns since the last [frameStages] call (then reset). Wall clock on the render
     * thread; [STAGE_EVALUATE] is inside [STAGE_TIERS] (and the prepared-text pass), the others are disjoint parts of
     * [STAGE_FRAME]. Diagnostic only: System.nanoTime per stage, no allocation, no effect on output.
     * TEXT PERF 11 adds counts, not times: [STAGE_LAYERS] text layers drawn and [STAGE_FLUSHES] mid-frame batch flushes;
     * [STAGE_TIER_JNI] is the tier/tier-request JNI time inside [STAGE_TIERS].
     */
    private val stageNs = LongArray(STAGES)
    internal fun frameStages(out: LongArray) {
        require(out.size >= STAGES)
        stageNs.copyInto(out); java.util.Arrays.fill(stageNs, 0L)
    }
    companion object {
        const val STAGE_FRAME = 0
        const val STAGE_TIERS = 1
        const val STAGE_EVALUATE = 2
        const val STAGE_PREPARE = 3
        const val STAGE_MESH = 4
        const val STAGE_FINISH = 5
        const val STAGE_LAYERS = 6
        const val STAGE_FLUSHES = 7
        const val STAGE_TIER_JNI = 8
        const val STAGES = 9
        /** One background thread for imported-font tier fetches across renderers (worker calls are serialised anyway). */
        private val TIER_EXECUTOR=java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable,"fluxx-imported-tiers").apply { isDaemon=true;priority=Thread.NORM_PRIORITY-1 } }
        /** Export rounds for deferred tier keys (each worker reply is <= 8 MiB / 20 s). */
        private const val EXPORT_TIER_ROUNDS=8
        /** Shared default: a `{ false }` default argument allocates a new lambda on every call (F1 zero-allocation window). */
        private val NEVER_CANCELLED: () -> Boolean = { false }
    }
    fun resize(width: Int,height: Int) = RenderBridge.resize(session,width,height)
    override fun close() {
        // Native teardown waits for GPU completion before producers return their Images.
        synchronized(tierLock) { closed=true;RenderBridge.destroy(session) }
        if (interactive) TextLayerErrors.clear()
        decoders.values.forEach { it.decoder.close() }; decoders.clear()
        images.values.forEach { it.buffer.close() }; images.clear()
    }
}

class TextFrameCapacityException(val layerIds: Set<Long>, message: String, cause: Throwable) : IllegalStateException(message, cause)
