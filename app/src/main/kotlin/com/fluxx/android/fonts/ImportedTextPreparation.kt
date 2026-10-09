package com.fluxx.android.fonts

import android.content.Context
import android.os.Looper
import com.fluxx.android.model.*
import kotlinx.coroutines.runBlocking

/** All IPC, file validation and native font work finish before playback. */
object ImportedTextPreparation {
    data class Result(val packet: ByteArray,val missing: Set<TextFontRef>)
    private data class Key(val font: String,val document: TextSourceDocument,val ligatures: Boolean,val nominal: Boolean,val revision: Long)
    private val packets=LinkedHashMap<Key,Result>(16,.75f,true)
    private var packetBytes=0L
    fun needed(document: TextSourceDocument)=document.text.isNotEmpty() && document.runs.any { it.style.font?.sha256!=null }
    fun prepare(context: Context,font: String,document: TextSourceDocument,ligatures: Boolean,nominal: Boolean=false): Result {
        check(Looper.myLooper()!=Looper.getMainLooper()) { "Imported font preparation requires a worker" }
        return runBlocking {
            val library=FontLibraryRepository.getInstance(context);library.load()
            val key=Key(font,StyledTextPreparation.shapingDocument(document),ligatures,nominal,library.revision)
            synchronized(packets) {packets[key]}?.let {return@runBlocking it}
            val refs=document.runs.mapNotNull { it.style.font }.filter { it.sha256!=null }.distinct()
            val resolved=refs.associateWith { library.resolve(it) }
            val missing=resolved.filterValues { it==null }.keys
            val wire=StyledTextPreparation.encode(document,font) { ref -> resolved[ref]?.id ?: "fluxx.sans" }
            val inputs=resolved.values.filterNotNull().distinctBy { it.id }
            require(inputs.size<=64) { "Prepared text supports at most 64 imported font identities" }
            val cache=SdfDiskCache(java.io.File(context.cacheDir,"imported-sdf"))
            val seeds=cache.seeds(inputs.map {it.id}.toSet())
            val packet=try {FontWorkerClient(context).prepare(wire,inputs,ligatures,nominal,seeds)}
                catch(failure: Exception) {
                    if(seeds.isEmpty() || failure is kotlinx.coroutines.CancellationException) throw failure
                    // A disposable cache must never make an otherwise valid font unavailable.
                    FontWorkerClient(context).prepare(wire,inputs,ligatures,nominal)
                }
            com.fluxx.android.engine.PreparedTextData.metrics(packet) // Validate before cache extraction.
            runCatching { cache.storePacket(packet) } // Disposable cache failure never rejects a valid font.
            Result(packet,missing).also {result -> synchronized(packets) {
                packets.put(key,result)?.let {packetBytes-=it.packet.size};packetBytes+=packet.size
                val entries=packets.entries.iterator()
                while(entries.hasNext() && (packets.size>16 || packetBytes>32L*1024*1024)) {packetBytes-=entries.next().value.packet.size;entries.remove()}
            } }
        }
    }
}
