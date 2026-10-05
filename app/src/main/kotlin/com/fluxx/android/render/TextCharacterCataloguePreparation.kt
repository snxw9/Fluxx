package com.fluxx.android.render

import android.content.Context
import android.os.Looper
import com.fluxx.android.engine.FontSession
import com.fluxx.android.engine.PreparedTextData
import com.fluxx.android.model.*
import com.fluxx.android.fonts.FontLibraryRepository
import com.fluxx.android.fonts.FontWorkerClient
import kotlinx.coroutines.runBlocking

/** CPU-only worker preparation. Packets can be imported into the renderer without parsing fonts. */
object TextCharacterCataloguePreparation {
    class Result(val catalogue: TextCharacterCatalogue,val packets: Array<ByteArray>)
    fun prepare(context: Context,font: String,text: TextProperties,documents: Array<TextSourceDocument>): Result? {
        check(Looper.myLooper()!=Looper.getMainLooper()) { "Character catalogue preparation requires a worker" }
        if(!TextCharacterPreparation.needed(text)) return null
        val cps=TextCharacterPreparation.codepoints(text,documents);if(cps.isEmpty()) return null
        val fonts=TextCharacterPreparation.fonts(font,documents)
        require(fonts.size*cps.size<=TextCharacterPreparation.MAX_CATALOGUE_GLYPHS) {
            "Character catalogue exceeds 16384 font/codepoint glyphs; reduce fonts or animated character ranges"
        }
        val library=FontLibraryRepository.getInstance(context)
        runBlocking { library.load() }
        val packets=FontSession(context.applicationContext.assets).use { session -> Array(fonts.size) { i->
            val ref=fonts[i]
            val resolved=if(ref.sha256!=null) library.resolve(ref) else null
            val id=if(ref.sha256==null) ref.bundledId else resolved?.id ?: "fluxx.sans"
            val wire=StyledTextPreparation.nominal(cps,id)
            if(resolved!=null) runBlocking { FontWorkerClient(context).prepare(wire,listOf(resolved),false,true) }
            else session.preparedPacket(font,wire,false,true)
        } }
        require(packets.sumOf { it.size.toLong() }<=32L*1024*1024) {
            "Character catalogue exceeds 32 MiB per layer; reduce fonts or animated character ranges"
        }
        val metrics=Array(packets.size) { TextNominalMetrics(cps,PreparedTextData.metrics(packets[it])) }
        return Result(TextCharacterCatalogue(fonts,cps,metrics),packets)
    }
}
