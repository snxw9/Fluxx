package com.fluxx.android.render

import android.content.Context
import android.media.*
import android.net.Uri
import com.fluxx.android.model.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.roundToInt

/** One audio decoder at a time; disk-backed accumulation keeps RAM independent of layer count. */
object AudioMixer {
    const val RATE = 48000
    const val CHANNELS = 2
    fun signature(project: ProjectDocument): List<Any?> = listOf(project.composition.resolvedDurationUs,
        project.composition.layers.filter { it.visible && it.type == LayerType.VIDEO && !it.muted && it.audioGain > 0 }
            .sortedBy { it.id }
            .map { listOf(it.asset?.uri,it.timing,it.audioGain) })

    fun mix(context: Context, project: ProjectDocument, target: File, cancelled: () -> Boolean,
        progress: (Float) -> Unit = {}) {
        val duration=project.composition.resolvedDurationUs
        // Include the last partial sample so AudioTrack's clock can reach the composition end.
        val frames=Math.addExact(Math.multiplyExact(duration / 1_000_000,RATE.toLong()),
            (duration % 1_000_000 * RATE + 999_999) / 1_000_000)
        val scratch=File.createTempFile("mix_", ".float",context.cacheDir)
        try {
            RandomAccessFile(scratch,"rw").use { output ->
                output.setLength(Math.multiplyExact(frames,8L))
                val layers=project.composition.layers.filter { it.visible && it.type == LayerType.VIDEO && !it.muted && it.audioGain > 0 && it.timing.isActive(it.timing.startUs) && it.timing.startUs < duration }
                val headroom=AudioMixPolicy.headroom(project)
                layers.forEachIndexed { index,layer ->
                    checkCancelled(cancelled)
                    mixLayer(context,layer,duration,output,headroom,cancelled)
                    progress((index+1f)/maxOf(1,layers.size))
                }
                output.seek(0)
                target.outputStream().buffered(64*1024).use { pcm ->
                    val bytes=ByteArray(32768)
                    val floats=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    val encoded=ByteBuffer.allocate(16384).order(ByteOrder.LITTLE_ENDIAN)
                    var remaining=frames*8
                    while(remaining>0) {
                        checkCancelled(cancelled)
                        val size=minOf(bytes.size.toLong(),remaining).toInt()
                        output.readFully(bytes,0,size); floats.position(0); encoded.clear()
                        repeat(size/4) { encoded.putShort((floats.float.coerceIn(-1f,1f)*32767f).roundToInt().toShort()) }
                        pcm.write(encoded.array(),0,size/2); remaining-=size
                    }
                }
            }
        } finally { scratch.delete() }
    }

    private fun mixLayer(context: Context,layer: CompositionLayer,duration: Long,output: RandomAccessFile,
        headroom: Float,cancelled: () -> Boolean) {
        val extractor=MediaExtractor()
        var decoder: MediaCodec?=null
        try {
            extractor.setDataSource(context,Uri.parse(requireNotNull(layer.asset?.uri)),null)
            val track=(0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return
            extractor.selectTrack(track)
            val format=extractor.getTrackFormat(track)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING,android.media.AudioFormat.ENCODING_PCM_16BIT)
            var rate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var channelSamples=FloatArray(channels)
            val codec=MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            decoder=codec; codec.configure(format,null,null,0); codec.start()
            extractor.seekTo(layer.timing.sourceInUs,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val startFrame=layer.timing.startUs*RATE/1_000_000
            val endFrame=minOf(requireNotNull(layer.timing.endUs),duration)*RATE/1_000_000
            val total=(endFrame-startFrame).coerceAtLeast(0)
            var emitted=0L
            var inputEnded=false
            var ended=false
            val info=MediaCodec.BufferInfo()
            val samples=FloatArray(8192)
            val bytes=ByteArray(samples.size*4)
            val mixBuffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var used=0
            var blockStart=0L
            var previousTime=Double.NaN
            var previousL=0f; var previousR=0f
            fun flush() {
                if(used==0) return
                output.seek((startFrame+blockStart)*8)
                output.readFully(bytes,0,used*4); mixBuffer.position(0)
                for(i in 0 until used) {
                    val old=mixBuffer.getFloat(i*4)
                    mixBuffer.putFloat(i*4,old+samples[i]*layer.audioGain*headroom)
                }
                output.seek((startFrame+blockStart)*8);output.write(bytes,0,used*4);used=0
            }
            var lastOutput=android.os.SystemClock.elapsedRealtime()
            while(!ended && emitted<total) {
                checkCancelled(cancelled)
                check(android.os.SystemClock.elapsedRealtime()-lastOutput<10000) { "Audio decoding stalled" }
                if(!inputEnded) {
                    val index=codec.dequeueInputBuffer(0)
                    if(index>=0) {
                        val input=requireNotNull(codec.getInputBuffer(index))
                        val size=extractor.readSampleData(input,0)
                        if(size<0) {codec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnded=true}
                        else {codec.queueInputBuffer(index,0,size,extractor.sampleTime,0);extractor.advance()}
                    }
                }
                val index=codec.dequeueOutputBuffer(info,2000)
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate=codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels=codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    channelSamples=FloatArray(channels)
                    val encoding=if(codec.outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) codec.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else android.media.AudioFormat.ENCODING_PCM_16BIT
                    check(encoding==android.media.AudioFormat.ENCODING_PCM_16BIT) { "Unsupported decoded audio format" }
                } else if(index>=0) {
                    lastOutput=android.os.SystemClock.elapsedRealtime()
                    val buffer=requireNotNull(codec.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                    buffer.position(info.offset);buffer.limit(info.offset+info.size)
                    val count=info.size/(channels*2)
                    for(i in 0 until count) {
                        for(channel in 0 until channels) channelSamples[channel]=buffer.short/32768f
                        var l=channelSamples[0]
                        var r=if(channels>1) channelSamples[1] else l
                        // Standard Android PCM channel order: FL, FR, FC, LFE, back/side pairs.
                        // Keep downmix headroom; LFE is omitted from the stereo fold-down.
                        if(channels>2) {
                            var weight=1f
                            if(channels!=4) {l+=channelSamples[2]*0.7071f;r+=channelSamples[2]*0.7071f;weight+=0.7071f}
                            val surroundStart=when(channels) {4->2;5->3;else->4}
                            var channel=surroundStart
                            while(channel<channels) {
                                l+=channelSamples[channel]*0.7071f
                                if(channel+1<channels) r+=channelSamples[channel+1]*0.7071f
                                weight+=0.7071f;channel+=2
                            }
                            l/=weight;r/=weight
                        }
                        val time=info.presentationTimeUs+i*1_000_000.0/rate
                        if(previousTime.isNaN()) {
                            emitted=maxOf(emitted,ceil((time-layer.timing.sourceInUs)*RATE/1_000_000.0).toLong().coerceAtLeast(0))
                            previousTime=time;previousL=l;previousR=r
                        }
                        while(emitted<total) {
                            val targetTime=layer.timing.sourceInUs+emitted*1_000_000.0/RATE
                            if(targetTime>time) break
                            if(used==0) blockStart=emitted
                            val fraction=if(time>previousTime) ((targetTime-previousTime)/(time-previousTime)).toFloat().coerceIn(0f,1f) else 1f
                            samples[used++]=previousL+(l-previousL)*fraction
                            samples[used++]=previousR+(r-previousR)*fraction
                            emitted++
                            if(used==samples.size) flush()
                        }
                        previousTime=time;previousL=l;previousR=r
                    }
                    ended=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index,false)
                }
            }
            flush()
        } finally {
            try { decoder?.stop() } catch(_:Exception){}
            decoder?.release();extractor.release()
        }
    }
    private fun checkCancelled(cancelled: () -> Boolean) { if(cancelled()) throw java.util.concurrent.CancellationException("Audio mix cancelled") }
}
