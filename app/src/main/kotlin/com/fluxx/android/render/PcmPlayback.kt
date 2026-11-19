package com.fluxx.android.render

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import java.io.RandomAccessFile
import java.io.Closeable

/**
 * Streams the prepared stereo mix. The AudioTrack playback head, not UI timers, owns the clock.
 * TEXT PERF 11: the track is filled at once but plays only when [gate] opens (the first frame of this playback has
 * been presented), so the head never runs ahead of a slow first frame. Until then [positionUs] is the start position.
 */
internal class PcmPlayback(file: File, startUs: Long,
                           private val gate: PlaybackGate = PlaybackGate({ PreviewFrames.count }, System::nanoTime)) : Closeable {
    private val startFrame = startUs*AudioMixer.RATE/1_000_000
    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(AudioMixer.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
        .setBufferSizeInBytes(maxOf(16384,AudioTrack.getMinBufferSize(AudioMixer.RATE,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT)))
        .setTransferMode(AudioTrack.MODE_STREAM).build()
    @Volatile private var running=true
    @Volatile var error: Throwable?=null
        private set
    private var playing=false
    private val thread=Thread({
        try {
            RandomAccessFile(file,"r").use { input ->
                input.seek((startFrame*4).coerceAtMost(input.length()))
                val bytes=ByteArray(16384)
                while(running) {
                    val size=input.read(bytes)
                    if(size<0) break
                    var offset=0
                    while(running && offset<size) {
                        val written=track.write(bytes,offset,size-offset,AudioTrack.WRITE_NON_BLOCKING)
                        check(written>=0) { "Audio output failed" }
                        offset+=written
                        if(written==0) Thread.sleep(2)
                    }
                }
            }
        } catch(e:Exception) { if(running) error=e }
    },"FluxxAudio")
    init { check(track.state==AudioTrack.STATE_INITIALIZED);thread.start() }
    /** Read on the preview worker only (it also starts the track once the gate opens). */
    val positionUs: Long get() {
        if(!playing && gate.poll()) { track.play();playing=true }
        return position(startFrame,track.playbackHeadPosition.toLong() and 0xffffffffL)
    }
    override fun close() {
        running=false
        thread.join()
        track.pause();track.flush();track.release()
    }
    companion object {
        /** The head is 0 until the track plays, so the position before the gate opens is the start position. */
        fun position(startFrame: Long, headFrames: Long): Long = (startFrame+headFrames)*1_000_000/AudioMixer.RATE
    }
}
