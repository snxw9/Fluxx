package com.fluxx.android.model

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Application-owned palettes. All read/modify/write operations share one IO mutex. */
class PaletteRepository private constructor(private val file: File) {
    private val lock=Mutex()
    // An accepted save survives dismissal of the picker or Activity recreation.
    private val worker=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutableState=MutableStateFlow(PaletteLibrary())
    val state=mutableState.asStateFlow()
    private var loaded=false

    suspend fun load() = worker.async { lock.withLock { loadLocked() } }.await()
    private fun loadLocked() {
        if(loaded) return
        val library=if(!file.exists() && !File(file.path+".bak").exists()) {
            seed().also { writeLocked(it) }
        } else {
            // Never silently overwrite a corrupt library with defaults.
            PaletteCodec.decode(AtomicFile(file).readFully()).let { if(it.palettes.isEmpty()) seed().also(::writeLocked) else it }
        }
        mutableState.value=library
        loaded=true
    }
    private suspend fun edit(change: (PaletteLibrary)->PaletteLibrary) = worker.async {
        lock.withLock {
            loadLocked()
            val current=mutableState.value
            val next=change(current)
            if(next!=current) {
                writeLocked(next) // Publish only successfully persisted state.
                mutableState.value=next
            }
        }
    }.await()
    suspend fun setActivePalette(id: String) = edit { library ->
        require(library.palettes.any { it.id==id }) { "Palette no longer exists" }
        library.copy(activePaletteId=id)
    }
    suspend fun createPalette(name: String? = null): ColorPalette {
        val palette=ColorPalette(UUID.randomUUID().toString(),name?.trim()?.ifBlank { null },emptyList())
        edit { it.copy(palettes=it.palettes+palette,activePaletteId=palette.id) }
        return palette
    }
    suspend fun deletePalette(id: String) = edit { library ->
        if(library.palettes.size<=1) library else {
            val remaining=library.palettes.filterNot { it.id==id }
            library.copy(palettes=remaining,activePaletteId=library.activePaletteId.takeIf { key -> remaining.any { it.id==key } } ?: remaining.first().id)
        }
    }
    suspend fun renamePalette(id: String, name: String?) = update(id) { it.copy(name=name?.trim()?.ifBlank { null }) }
    suspend fun addSwatch(id: String, color: FluxxColor) = update(id) {
        if(it.swatches.size>=ColorPalette.MAX_SWATCHES) it else it.copy(swatches=it.swatches+color)
    }
    suspend fun removeSwatch(id: String, index: Int) = update(id) {
        if(index !in it.swatches.indices) it else it.copy(swatches=it.swatches.filterIndexed { i,_ -> i!=index })
    }
    suspend fun replaceSwatch(id: String, index: Int, color: FluxxColor) = update(id) {
        if(index !in it.swatches.indices) it else it.copy(swatches=it.swatches.mapIndexed { i,old -> if(i==index) color else old })
    }
    private suspend fun update(id: String, change: (ColorPalette)->ColorPalette) = edit { library ->
        require(library.palettes.any { it.id==id }) { "Palette no longer exists" }
        library.copy(palettes=library.palettes.map { if(it.id==id) change(it) else it })
    }
    private fun writeLocked(library: PaletteLibrary) {
        val bytes=PaletteCodec.encode(library)
        val atomic=AtomicFile(file)
        val stream=atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch(e:Exception) { atomic.failWrite(stream); throw e }
    }
    companion object {
        @Volatile private var instance: PaletteRepository? = null
        fun getInstance(context: Context): PaletteRepository = instance ?: synchronized(this) {
            instance ?: PaletteRepository(File(context.applicationContext.filesDir,"color_palettes.json")).also { instance=it }
        }
        internal fun forFile(file: File) = PaletteRepository(file)
        private fun seed(): PaletteLibrary {
            // Exact deduplicated union of both old palettes, including inspector's #222222.
            val colors=listOf(0xFFFFFFFF,0xFFB0B0B0,0xFF888888,0xFF686868,0xFF303030,0xFF222222,0xFF181818,0xFF000000,
                0xFFE53935,0xFFD81B60,0xFF8E24AA,0xFF3949AB,0xFF1E88E5,0xFF00ACC1,0xFF43A047,0xFFFDD835,0xFFFB8C00)
                .map { FluxxColor.fromArgb(it.toInt()) }
            val palette=ColorPalette(UUID.randomUUID().toString(),"Default",colors)
            return PaletteLibrary(listOf(palette),palette.id)
        }
    }
}
