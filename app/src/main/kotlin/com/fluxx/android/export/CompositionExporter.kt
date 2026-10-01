package com.fluxx.android.export

import android.content.Context
import android.media.*
import com.fluxx.android.engine.RenderBridge
import com.fluxx.android.media.*
import com.fluxx.android.model.*
import com.fluxx.android.render.*
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import com.fluxx.android.Layer
import android.net.Uri

/** Independent offscreen Vulkan session. RAM and codec counts are independent of project layer count. */
@androidx.media3.common.util.UnstableApi
class CompositionExporter(private val context: Context) {
    suspend fun export(project: ProjectDocument,output: File,progress: (Int?) -> Unit): Unit = withContext(Dispatchers.Default) {
        val job=currentCoroutineContext()
        val cancelled={ !job.isActive }
        val repository=MediaRepository(context)
        CompositionSupport.check(context,repository,project)
        // Retain the proven zero-readback fast path for an unchanged, full-length single video.
        // This is an optimization choice, never a project or export layer-count restriction.
        val comp=project.composition
        val single=comp.layers.singleOrNull()
        if(single!=null && single.type==LayerType.VIDEO && single.visible && !single.muted && single.audioGain==1f &&
            !single.animTransform.isAnyAnimated() &&
            single.anchorX == .5f && single.anchorY == .5f &&
            single.timing.startUs==0L && single.timing.sourceInUs==0L && single.timing.durationUs==single.asset?.durationUs &&
            comp.resolvedDurationUs==single.timing.durationUs && comp.width==CompositionDefaults.WIDTH && comp.height==CompositionDefaults.HEIGHT &&
            (single.referenceWidth==0 || (single.referenceWidth==comp.width && single.referenceHeight==comp.height))) {
            val t=single.transform
            VideoExporter(context).export(Layer(single.id,t.positionX,t.positionY,t.scaleX,t.scaleY,t.rotationDegrees,t.opacity,
                Uri.parse(requireNotNull(single.asset?.uri))),output,progress)
            return@withContext
        }
        val audio=File.createTempFile("export_audio_",".pcm",context.cacheDir)
        var video: File?=null
        try {
            val renderedVideo=File.createTempFile("export_video_",".mp4",context.cacheDir).also { video=it }
            AudioMixer.mix(context,project,audio,cancelled) { progress((it*10).toInt()) }
            encodeVideo(project,repository,renderedVideo,cancelled) { progress(10+(it*80).toInt()) }
            mux(renderedVideo,audio,output,project.composition.resolvedDurationUs,cancelled) { progress(90+(it*9).toInt()) }
        } finally { audio.delete();video?.delete() }
    }

    private fun encodeVideo(project: ProjectDocument,repository: MediaRepository,output: File,
        cancelled: () -> Boolean,progress:(Float)->Unit) {
        val comp=project.composition
        val duration=comp.resolvedDurationUs
        val format=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,comp.width,comp.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE,12_000_000)
            setFloat(MediaFormat.KEY_FRAME_RATE,comp.frameRate.numerator.toFloat()/comp.frameRate.denominator)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
            setInteger(MediaFormat.KEY_COLOR_STANDARD,MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE,MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER,MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        val codec=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var muxer: MediaMuxer?=null
        var started=false
        var completed=false
        try {
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start()
            val mux=MediaMuxer(output.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);muxer=mux
            var track=-1
            val info=MediaCodec.BufferInfo()
            var outputEnded=false
            var frame=0L
            var inputEnded=false
            var lastOutput=android.os.SystemClock.elapsedRealtime()
            CompositionRenderer(context,repository).use { renderer ->
                while(!outputEnded) {
                    checkCancel(cancelled)
                    if(!inputEnded) {
                        val input=codec.dequeueInputBuffer(2000)
                        if(input>=0) {
                            val time=comp.frameRate.frameTimeUs(frame)
                            if(time>=duration) {
                                codec.queueInputBuffer(input,0,0,duration,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnded=true
                            } else {
                                renderer.render(project,time,cancelled)
                                val image=requireNotNull(codec.getInputImage(input)) { "Encoder does not expose flexible YUV input" }
                                val planes=image.planes
                                check(planes[0].pixelStride==1 && RenderBridge.copyYuv(renderer.session,
                                    planes[0].buffer.slice(),planes[1].buffer.slice(),planes[2].buffer.slice(),
                                    planes[0].rowStride,planes[1].rowStride,planes[2].rowStride,planes[1].pixelStride,planes[2].pixelStride)) { "Could not transfer rendered frame to encoder" }
                                codec.queueInputBuffer(input,0,comp.width*comp.height*3/2,time,0)
                                frame++;progress(time.toFloat()/duration)
                                lastOutput=android.os.SystemClock.elapsedRealtime()
                            }
                        }
                    }
                    var index=codec.dequeueOutputBuffer(info,2000)
                    while(index>=0) {
                        lastOutput=android.os.SystemClock.elapsedRealtime()
                        if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) {
                            check(started)
                            val buffer=requireNotNull(codec.getOutputBuffer(index))
                            buffer.position(info.offset);buffer.limit(info.offset+info.size)
                            mux.writeSampleData(track,buffer,info)
                        }
                        outputEnded=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index,false)
                        index=codec.dequeueOutputBuffer(info,0)
                    }
                    if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track=mux.addTrack(codec.outputFormat);mux.start();started=true }
                    check(android.os.SystemClock.elapsedRealtime()-lastOutput<15000) { "Video encoder stalled" }
                }
            }
            check(frame>0 && started) { "No video frames were encoded" }
            // Explicit track end avoids extending a non-grid-aligned source by a rounded frame.
            info.set(0,0,duration,MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            mux.writeSampleData(track,ByteBuffer.allocateDirect(1),info)
            completed=true
        } finally {
            try {codec.stop()} catch(_:Exception){};codec.release()
            finishMuxer(muxer,started,completed)
        }
    }

    private fun mux(video: File,audio: File,output: File,durationUs: Long,cancelled: () -> Boolean,progress:(Float)->Unit) {
        val extractor=MediaExtractor()
        val codec=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer?=null
        var started=false
        var completed=false
        try {
            extractor.setDataSource(video.absolutePath)
            val videoTrack=(0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true }
            extractor.selectTrack(videoTrack)
            val mux=MediaMuxer(output.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);muxer=mux
            val vTrack=mux.addTrack(extractor.getTrackFormat(videoTrack))
            var aTrack=-1
            codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,AudioMixer.RATE,2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE,192000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,16384)
            },null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start()
            val info=MediaCodec.BufferInfo()
            val videoInfo=MediaCodec.BufferInfo()
            var videoBuffer=ByteBuffer.allocateDirect(1024*1024)
            fun copyVideoThrough(time: Long) {
                while(extractor.sampleTime>=0 && extractor.sampleTime<=time) {
                    checkCancel(cancelled)
                    val size=extractor.sampleSize.toInt()
                    if(size>videoBuffer.capacity()) videoBuffer=ByteBuffer.allocateDirect(size)
                    videoBuffer.clear()
                    val count=extractor.readSampleData(videoBuffer,0)
                    if(count<0) break
                    videoInfo.set(0,count,extractor.sampleTime,if(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    mux.writeSampleData(vTrack,videoBuffer,videoInfo);extractor.advance()
                }
            }
            audio.inputStream().buffered().use { pcm ->
                val bytes=ByteArray(16384)
                var readBytes=0L
                var inputEnded=false
                var ended=false
                var lastOutput=android.os.SystemClock.elapsedRealtime()
                while(!ended) {
                    checkCancel(cancelled)
                    if(!inputEnded) {
                        val index=codec.dequeueInputBuffer(2000)
                        if(index>=0) {
                            val buffer=requireNotNull(codec.getInputBuffer(index));buffer.clear()
                            val count=pcm.read(bytes,0,minOf(bytes.size,buffer.remaining())/4*4)
                            val pts=readBytes/4*1_000_000/AudioMixer.RATE
                            if(count<0) {codec.queueInputBuffer(index,0,0,pts,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnded=true}
                            else {buffer.put(bytes,0,count);codec.queueInputBuffer(index,0,count,pts,0);readBytes+=count}
                            progress(readBytes.toFloat()/audio.length().coerceAtLeast(1))
                        }
                    }
                    val index=codec.dequeueOutputBuffer(info,2000)
                    if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {aTrack=mux.addTrack(codec.outputFormat);mux.start();started=true}
                    else if(index>=0) {
                        lastOutput=android.os.SystemClock.elapsedRealtime()
                        if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0 && info.presentationTimeUs<durationUs) {
                            check(started);copyVideoThrough(info.presentationTimeUs)
                            val buffer=requireNotNull(codec.getOutputBuffer(index));buffer.position(info.offset);buffer.limit(info.offset+info.size)
                            mux.writeSampleData(aTrack,buffer,info)
                        }
                        ended=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0;codec.releaseOutputBuffer(index,false)
                    }
                    check(android.os.SystemClock.elapsedRealtime()-lastOutput<15000) { "Audio encoder stalled" }
                }
                copyVideoThrough(Long.MAX_VALUE)
                val end=MediaCodec.BufferInfo().apply { set(0,0,durationUs,MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
                val empty=ByteBuffer.allocateDirect(1)
                mux.writeSampleData(vTrack,empty,end)
                mux.writeSampleData(aTrack,empty,end)
            }
            completed=true
        } finally {
            try {codec.stop()} catch(_:Exception){};codec.release();extractor.release()
            finishMuxer(muxer,started,completed)
        }
    }
    private fun finishMuxer(muxer: MediaMuxer?,started: Boolean,completed: Boolean) {
        try {
            if(started) try { muxer?.stop() } catch(e: Exception) { if(completed) throw e }
        } finally { muxer?.release() }
    }
    private fun checkCancel(cancelled: () -> Boolean) { if(cancelled()) throw CancellationException("Export cancelled") }
}
