package com.fluxx.android.render

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.fluxx.android.media.*
import com.fluxx.android.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Surface-owned controller. Commands conflate; stale decode work never presents a frame. */
data class PreviewState(val positionUs: Long = 0, val playing: Boolean = false, val message: String? = null,
    val resolution: PreviewResolution = PreviewResolution.FULL)

class PreviewController(context: Context, surface: Surface, width: Int, height: Int,
    private val onPosition: (Long) -> Unit = {}, private val onStatus: (String?) -> Unit = {}) {
    private val mutableState=MutableStateFlow(PreviewState())
    val state=mutableState.asStateFlow()
    private val app=context.applicationContext
    private val dispatcher=Executors.newSingleThreadExecutor { Thread(it,"FluxxPreview") }.asCoroutineDispatcher()
    private val scope=CoroutineScope(SupervisorJob()+dispatcher)
    private val main=Handler(Looper.getMainLooper())
    private val changes=Channel<Unit>(Channel.CONFLATED)
    private val generation=AtomicLong()
    private val requestGeneration=AtomicLong()
    @Volatile private var project=ProjectDocument()
    @Volatile private var requestedUs=0L
    @Volatile private var playing=false
    @Volatile private var resolution=PreviewResolution.FULL
    private data class SurfaceSize(val width: Int, val height: Int)
    @Volatile private var viewSize=SurfaceSize(width,height)
    @Volatile private var closed=false
    /** AUD2 B1: (read ns, heard us, duration us) of the running playback's last clock read; null when not playing. */
    @Volatile private var clockAnchor: LongArray?=null
    /** AUD2 B2: seeks are streaming in (scrubbing), and when the last one arrived. */
    @Volatile private var scrubbing=false
    @Volatile private var lastSeekNs=Long.MIN_VALUE / 2
    // Accessed only on dispatcher, including sampling and destruction.
    private var samplingRenderer: CompositionRenderer? = null
    // AUD2 B3: volatile so a gain-only edit on the main thread reaches the running mix (applyGains is thread-safe).
    @Volatile private var playback: StreamingPlayback? = null
    private var sampledEpoch = -1L
    private val worker=scope.launch {
        val repository=MediaRepository(app)
        var renderer: CompositionRenderer?=null
        var waitingSince=0L
        var preparingShown=false
        var validated:ProjectDocument?=null
        var lastDrawTime=-1L
        var lastGeneration=-1L
        var lastRequestGeneration=-1L
        var renderedSize=SurfaceSize(width,height)
        try {
            renderer=CompositionRenderer(app,repository,surface,width,height)
            samplingRenderer=renderer
            var approximateShown=false
            while(isActive && !closed) {
                // AUD2 B2: after an approximate scrub frame, the exact frame follows once no request came for SETTLE_MS.
                var settle=false
                if(!playing) {
                    if(approximateShown) settle=withTimeoutOrNull(ScrubPolicy.SETTLE_MS) { changes.receive() } == null
                    else changes.receive()
                } else changes.tryReceive()
                val epoch=generation.get()
                val snapshot=project
                val quality=resolution
                val cancel={ closed || generation.get()!=epoch }
                try {
                    if(epoch!=lastGeneration) {
                        // Viewport-only changes redraw without seeking or restarting audio.
                        val requestEpoch=requestGeneration.get()
                        if(requestEpoch!=lastRequestGeneration) {
                            playback?.close();playback=null
                            lastRequestGeneration=requestEpoch
                        }
                        lastDrawTime=-1
                        // A callback can publish another size while native resize is running.
                        // Record only the immutable pair actually passed to the renderer.
                        val targetSize=viewSize
                        if(renderedSize!=targetSize) {
                            renderer.resize(targetSize.width,targetSize.height)
                            renderedSize=targetSize
                        }
                        lastGeneration=epoch
                    }
                    if(snapshot.composition.layers.isEmpty()) {
                        renderer.renderPreview(snapshot,0,quality,cancel)
                        playing=false
                        position(epoch,requestedUs);status(epoch,null)
                        continue
                    }
                    if(validated!==snapshot) { CompositionSupport.check(app,repository,snapshot);validated=snapshot }
                    // AUD1 B1: streaming playback straight from the per-source PCM cache: no whole-file mix before Play. Sound
                    // starts once ~0.5 s is decoded; "Preparing audio" shows only when that takes longer than 0.3 s.
                    if(playing && playback==null) { playback=StreamingPlayback(app,snapshot,requestedUs); waitingSince=System.nanoTime(); preparingShown=false
                        // A gain-only edit that landed while this playback was being built.
                        project.let { latest -> if(latest!==snapshot) playback?.applyGains(latest) } }
                    playback?.let { pb -> if(playing) {
                        val waited=(System.nanoTime()-waitingSince)/1_000_000
                        if(!pb.ready && StreamingWindow.showStatus(waited,false) && !preparingShown) { status(epoch,"Preparing audio"); preparingShown=true }
                        // The moment audio is ready the indicator goes (not only when the next frame moves).
                        if(pb.ready && preparingShown) { status(epoch,null); preparingShown=false }
                    } }
                    playback?.error?.let { throw IllegalStateException("Audio preview failed",it) }
                    val readNs=System.nanoTime()
                    val time=if(playing) requireNotNull(playback).positionUs else requestedUs
                    val duration = snapshot.composition.resolvedDurationUs
                    // AUD2 B1: publish the heard clock for the timeline (only once the track really runs).
                    clockAnchor=if(playing && playback?.started==true && time<duration) longArrayOf(readNs,time,duration) else null
                    if(time>=duration && playing) {
                        clockAnchor=null
                        requestedUs=duration;playing=false;MediaWork.playback(false);playback?.close();playback=null
                        renderer.renderPreview(snapshot,duration,quality,cancel)
                        position(epoch,duration);status(epoch,null)
                        continue
                    }
                    val rate=snapshot.composition.frameRate
                    var frame=rate.nearestFrame(time.coerceIn(0,duration))
                    if(time<duration && rate.frameTimeUs(frame)>=duration && frame>0) frame--
                    val frameTime=if(time>=duration) duration else rate.frameTimeUs(frame)
                    if(settle) lastDrawTime=-1
                    if(lastDrawTime!=frameTime) {
                        renderer.approximateFrames=!playing && !settle && scrubbing
                        renderer.renderPreview(snapshot,frameTime,quality,cancel)
                        approximateShown=!playing && renderer.lastFrameApproximate
                        if(!cancel()) {
                            lastDrawTime=frameTime
                            position(epoch,if (playing) frameTime else requestedUs);status(epoch,null)
                        }
                    }
                    if(playing) delay(4)
                } catch(e: java.util.concurrent.CancellationException) {
                    // Render work is synchronous and cancellation-aware. A failed request is discarded.
                    if(closed) break
                } catch(e:Exception) {
                    clockAnchor=null
                    playing=false;MediaWork.playback(false);playback?.close();playback=null
                    status(epoch,e.message ?: "Preview failed")
                }
            }
        } catch(e:Exception) { if(!closed) status(generation.get(),e.message ?: "Preview unavailable") }
        finally {
            samplingRenderer=null
            MediaWork.playback(false)
            playback?.close();renderer?.close()
        }
    }
    /**
     * AUD2 B1: the composition time HEARD at [nanoTime] (System.nanoTime), extrapolated from the worker's last clock read;
     * null unless playback is running. Read by the timeline so it follows the sound, not the start of the shown frame.
     */
    fun playbackPositionAt(nanoTime: Long): Long? {
        val a = clockAnchor ?: return null
        return (a[1] + (nanoTime - a[0]) / 1000).coerceIn(a[1], a[2])
    }
    /**
     * AUD2 B3: a gain-only edit (a gain slider step, or a typed gain). The running mix takes it live (no new mix, no
     * feeding-thread restart, no re-gate, no seek; the picture is unchanged so nothing re-renders). Returns false when
     * [next] changes more than gains (then use [update]).
     */
    fun previewGains(next: ProjectDocument): Boolean {
        if(closed || !AudioGains.onlyGainsChanged(project,next)) return false
        project=next
        playback?.applyGains(next)
        return true
    }
    fun update(project: ProjectDocument,timeUs: Long,play: Boolean=false) {
        if(!play) clockAnchor=null
        // AUD2 B2: seeks arriving back to back (a finger scrubbing) allow the decoders' bounded scrub decode.
        val now=System.nanoTime(); val target=timeUs.coerceIn(0L, project.composition.resolvedDurationUs)
        if(play) scrubbing=false
        else if(target!=requestedUs) { scrubbing=ScrubPolicy.scrubbing(now-lastSeekNs); lastSeekNs=now }
        this.project=project;requestedUs=timeUs.coerceIn(0L, project.composition.resolvedDurationUs);playing=play && project.composition.resolvedDurationUs > 0
        // MP1 B5: background media work (PCM filling, peaks, thumbnails) pauses while playing or scrubbing.
        MediaWork.playback(playing); if(!play && scrubbing) MediaWork.scrub()
        mutableState.update { it.copy(playing=playing) }
        requestGeneration.incrementAndGet()
        generation.incrementAndGet();changes.trySend(Unit)
    }
    fun setPreviewResolution(value: PreviewResolution) {
        if (closed || resolution == value) return
        pause()
        resolution = value
        mutableState.update { it.copy(resolution = value, playing = false) }
        generation.incrementAndGet()
        changes.trySend(Unit)
    }
    fun seek(timeUs: Long) = update(project,timeUs,false)
    fun play() = update(project, requestedUs.takeIf { it < (project.composition.resolvedDurationUs) } ?: 0, true)
    fun pause() = update(project,requestedUs,false)
    /** Normalized workspace coordinates; native maps through its recorded blit rectangle. */
    suspend fun sampleColorAt(normalizedX: Float, normalizedY: Float): Int? = withContext(dispatcher) {
        if(closed || playing) return@withContext null
        // A pause command can precede the render loop's next iteration. Stop AudioTrack
        // here too, on its owning dispatcher, before doing any sampling work.
        playback?.close(); playback=null
        val renderer=samplingRenderer ?: return@withContext null
        val epoch=generation.get()
        val snapshot=project
        val size=viewSize
        if(sampledEpoch!=epoch) {
            renderer.resize(size.width,size.height)
            val rate=snapshot.composition.frameRate
            val time=rate.frameTimeUs(rate.nearestFrame(requestedUs))
            renderer.renderPreview(snapshot,time,resolution) { closed || playing || generation.get()!=epoch }
            sampledEpoch=epoch
        }
        if(closed || playing || generation.get()!=epoch) return@withContext null
        val packed=com.fluxx.android.engine.RenderBridge.readPreviewPixel(renderer.session,normalizedX,normalizedY)
        if(packed<0) null else packed.toInt()
    }
    fun resize(width:Int,height:Int) {
        if(width<=0 || height<=0) return
        val size=SurfaceSize(width,height)
        if(size==viewSize) return
        viewSize=size;generation.incrementAndGet();changes.trySend(Unit)
    }
    suspend fun close() {
        closed=true;generation.incrementAndGet();changes.close();worker.cancelAndJoin();scope.cancel();dispatcher.close()
    }
    private fun position(epoch:Long,time:Long) { main.post { if(!closed && epoch==generation.get()) {
        requestedUs=time;mutableState.update { it.copy(positionUs=time,playing=playing) };onPosition(time)
    } } }
    private fun status(epoch:Long,message:String?) { main.post {if(!closed && epoch==generation.get()) {
        mutableState.update { it.copy(message=message,playing=playing) };onStatus(message)
    }} }
}
