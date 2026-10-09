package com.fluxx.android.fonts

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.fluxx.android.model.TextFontRef
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** App-private, content-addressed fonts. Project documents retain references, never file bytes. */
class FontLibraryRepository private constructor(private val context: Context) {
    private val directory=File(context.filesDir,"fonts")
    private val index=File(directory,"index.json")
    private val lock=Mutex()
    private val worker=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutable=MutableStateFlow<List<FontLibraryEntry>>(emptyList())
    val state=mutable.asStateFlow()
    private var loaded=false
    @Volatile var revision=0L;private set
    suspend fun load()=worker.async { lock.withLock { loadLocked() } }.await()
    private fun loadLocked() {
        if(loaded) return
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create the private font library" }
        for(file in listOf(index,File(index.path+".bak"))) require(!file.exists() || file.length()<=FontMetadataCodec.MAX_INDEX_BYTES) { "Font library index exceeds 8 MiB" }
        mutable.value=if(index.exists() || File(index.path+".bak").exists()) FontMetadataCodec.decode(AtomicFile(index).readFully()) else emptyList()
        loaded=true
    }
    /** SAF grants are used only during this import; no persistent grant or online download. */
    suspend fun import(uri: Uri,faces: Set<Int>?=null): FontLibraryEntry=worker.async {
        val staging=File.createTempFile("font-import-",".bin",context.cacheDir)
        try {
            val digest=MessageDigest.getInstance("SHA-256")
            requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot read the selected font" }.use { input ->
                staging.outputStream().use { output ->
                    val buffer=ByteArray(8192);var total=0
                    while(true) {
                        val count=input.read(buffer);if(count<0) break
                        require(count<=FontWorkerService.MAX_FONT_BYTES-total) { "Font files must be at most 16 MiB" }
                        output.write(buffer,0,count);digest.update(buffer,0,count);total+=count
                    }
                    require(total>0) { "The font file is empty" }
                }
            }
            val hash=digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            val inspected=FontMetadataCodec.inspect(FontWorkerClient(context).inspect(staging))
            require(faces==null || faces.isNotEmpty() && faces.all { i -> inspected.any { it.index==i } }) { "Choose valid collection face indices" }
            val selected=inspected.filter { faces==null || it.index in faces }
            val imported=lock.withLock {
                loadLocked()
                val existing=mutable.value.find { it.sha256==hash }
                val entry=FontLibraryEntry(hash,staging.length(),(existing?.faces.orEmpty()+selected).distinctBy { it.index }.sortedBy { it.index })
                val next=mutable.value.filterNot { it.sha256==hash }+entry
                val encoded=FontMetadataCodec.encode(next) // Limits checked before any permanent write.
                val file=AtomicFile(File(directory,hash));val stream=file.startWrite()
                try { staging.inputStream().use { it.copyTo(stream) };file.finishWrite(stream) }
                catch(e: Exception) { file.failWrite(stream);throw e }
                writeIndex(encoded);revision=Math.addExact(revision,1);mutable.value=next;entry
            }
            for(face in imported.faces) com.fluxx.android.render.TextIdlePrewarm.imported(context,face.reference(imported.sha256))
            imported
        } finally { staging.delete() }
    }.await()
    /** Caller supplies the existing confirmation dialog; requested project refs are untouched. */
    suspend fun delete(hash: String)=worker.async { lock.withLock {
        require(hash.matches(Regex("[a-f0-9]{64}")));loadLocked()
        val next=mutable.value.filterNot { it.sha256==hash }
        if(next.size!=mutable.value.size) {
            writeIndex(FontMetadataCodec.encode(next));revision=Math.addExact(revision,1);mutable.value=next
            AtomicFile(File(directory,hash)).delete()
        }
    } }.await()
    /** Presence is metadata only; renderer preparation verifies the content hash again. */
    fun contains(reference: TextFontRef): Boolean=if(reference.sha256==null) reference.bundledId in TextFontRef.BUNDLED_IDS else mutable.value.any { entry ->
        entry.sha256==reference.sha256 && entry.faces.any { it.index==reference.faceIndex } && File(directory,entry.sha256).isFile
    }
    internal fun resolve(reference: TextFontRef): FontWorkerClient.FontInput? {
        val hash=reference.sha256 ?: return null
        val entry=mutable.value.find { it.sha256==hash } ?: return null
        val face=entry.faces.find { it.index==reference.faceIndex } ?: return null
        val file=File(directory,hash)
        if(!file.isFile) return null
        require(file.length()==entry.bytes) { "Imported font size changed; re-import the font" }
        val actual=MessageDigest.getInstance("SHA-256").let { digest ->
            file.inputStream().use { stream -> val buffer=ByteArray(8192)
                while(true) { val count=stream.read(buffer);if(count<0) break;digest.update(buffer,0,count) } }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        require(actual==hash) { "Imported font checksum changed; re-import the font" }
        val effective=face.reference(hash,reference.namedInstance,reference.axes.associate { it.tag to it.value.staticValue })
        val signature=buildString {
            append(hash);append(':');append(effective.faceIndex);append(':');append(effective.namedInstance)
            for(axis in effective.axes) { append(':');append(axis.tag);append(':');append(axis.value.staticValue.toRawBits()) }
        }
        val identity=MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return FontWorkerClient.FontInput("user.$identity",file,effective)
    }
    private fun writeIndex(bytes: ByteArray) {
        val atomic=AtomicFile(index);val stream=atomic.startWrite()
        try { stream.write(bytes);atomic.finishWrite(stream) }
        catch(e: Exception) { atomic.failWrite(stream);throw e }
    }
    companion object {
        @Volatile private var instance: FontLibraryRepository?=null
        fun getInstance(context: Context)=instance ?: synchronized(this) {
            instance ?: FontLibraryRepository(context.applicationContext).also { instance=it }
        }
    }
}
