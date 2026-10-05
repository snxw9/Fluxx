package com.fluxx.android.fonts

import android.content.Context
import android.os.Looper
import com.fluxx.android.model.*
import kotlinx.coroutines.runBlocking

/** All IPC, file validation and native font work finish before playback. */
object ImportedTextPreparation {
    data class Result(val packet: ByteArray,val missing: Set<TextFontRef>)
    fun needed(document: TextSourceDocument)=document.text.isNotEmpty() && document.runs.any { it.style.font?.sha256!=null }
    fun prepare(context: Context,font: String,document: TextSourceDocument,ligatures: Boolean,nominal: Boolean=false): Result {
        check(Looper.myLooper()!=Looper.getMainLooper()) { "Imported font preparation requires a worker" }
        return runBlocking {
            val library=FontLibraryRepository.getInstance(context);library.load()
            val refs=document.runs.mapNotNull { it.style.font }.filter { it.sha256!=null }.distinct()
            val resolved=refs.associateWith { library.resolve(it) }
            val missing=resolved.filterValues { it==null }.keys
            val wire=StyledTextPreparation.encode(document,font) { ref -> resolved[ref]?.id ?: "fluxx.sans" }
            val inputs=resolved.values.filterNotNull().distinctBy { it.id }
            require(inputs.size<=64) { "Prepared text supports at most 64 imported font identities" }
            Result(FontWorkerClient(context).prepare(wire,inputs,ligatures,nominal),missing)
        }
    }
}
