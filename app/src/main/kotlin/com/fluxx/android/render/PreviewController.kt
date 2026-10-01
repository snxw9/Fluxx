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
    // Accessed only on dispatcher, including sampling and destruction.
    private var samplingRenderer: CompositionRenderer? = null
    private var playback: PcmPlayback? = null
    private var sampledEpoch = -1L
    private val worker=scope.launch {
        val repository=MediaRepository(app)
        var renderer: CompositionRenderer?=null
        var pcm:File?=null
        var audioSignature:List<Any?>?=null
        var validated:ProjectDocument?=null
        var lastDrawTime=-1L
        var lastGeneration=-1L
        var lastRequestGeneration=-1L
        var renderedSize=SurfaceSize(width,height)
        try {
            renderer=CompositionRenderer(app,repository,surface,width,height)
            samplingRenderer=renderer
            while(isActive && !closed) {
                if(!playing) changes.receive()
                else changes.tryReceive()
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
                    if(playing && playback==null) {
                        val signature=AudioMixer.signature(snapshot)
                        if(audioSignature!=signature) {
                            status(epoch,"Preparing audio")
                            audioSignature=null
                            pcm?.delete();pcm=File.createTempFile("preview_audio_",".pcm",app.cacheDir)
                            AudioMixer.mix(app,snapshot,pcm,cancel)
                            audioSignature=signature
                        }
                        if(cancel()) continue
                        playback=PcmPlayback(requireNotNull(pcm),requestedUs)
                    }
                    playback?.error?.let { throw IllegalStateException("Audio preview failed",it) }
                    val time=if(playing) requireNotNull(playback).positionUs else requestedUs
                    val duration = snapshot.composition.resolvedDurationUs
                    if(time>=duration && playing) {
                        requestedUs=duration;playing=false;playback?.close();playback=null
                        renderer.renderPreview(snapshot,duration,quality,cancel)
                        position(epoch,duration);status(epoch,null)
                        continue
                    }
                    val rate=snapshot.composition.frameRate
                    var frame=rate.nearestFrame(time.coerceIn(0,duration))
                    if(time<duration && rate.frameTimeUs(frame)>=duration && frame>0) frame--
                    val frameTime=if(time>=duration) duration else rate.frameTimeUs(frame)
                    if(lastDrawTime!=frameTime) {
                        renderer.renderPreview(snapshot,frameTime,quality,cancel)
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
                    playing=false;playback?.close();playback=null
                    status(epoch,e.message ?: "Preview failed")
                }
            }
        } catch(e:Exception) { if(!closed) status(generation.get(),e.message ?: "Preview unavailable") }
        finally {
            samplingRenderer=null
            playback?.close();renderer?.close();pcm?.delete()
        }
    }
    fun update(project: ProjectDocument,timeUs: Long,play: Boolean=false) {
        this.project=project;requestedUs=timeUs.coerceIn(0L, project.composition.resolvedDurationUs);playing=play && project.composition.resolvedDurationUs > 0
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
