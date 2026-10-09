package com.fluxx.android.fonts

import android.content.*
import android.os.*
import java.io.File
import java.io.IOException
import com.fluxx.android.model.TextFontRef
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Preparation only. Never called by a prepared frame evaluator or renderer draw. */
class FontWorkerClient(context: Context) {
    private val context=context.applicationContext
    suspend fun inspect(input: File): String = withContext(Dispatchers.IO) {
        require(input.length() in 1..FontWorkerService.MAX_FONT_BYTES.toLong()) { "Font files must be nonempty and at most 16 MiB" }
        val output=File.createTempFile("font-metadata-",".json",context.cacheDir)
        try {
            withTimeout(60_000) { request(input,output) }
            require(output.length() in 1..FontWorkerService.MAX_METADATA_BYTES.toLong()) { "Invalid font metadata size" }
            output.readText(Charsets.UTF_8)
        } finally { output.delete() }
    }
    data class FontInput(val id: String,val file: File,val reference: TextFontRef)
    suspend fun prepare(input: ByteArray,fonts: List<FontInput>,ligatures: Boolean,nominal: Boolean=false,seeds: ByteArray=ByteArray(0)): ByteArray=withContext(Dispatchers.IO) {
        require(input.size<=1024*1024 && fonts.size<=64)
        require(fonts.sumOf { it.file.length() }<=32L*1024*1024) { "Imported font preparation exceeds 32 MiB; reduce distinct font faces" }
        val source=File.createTempFile("font-layout-input-",".bin",context.cacheDir)
        val output=File.createTempFile("font-layout-output-",".bin",context.cacheDir)
        val cached=File.createTempFile("font-sdf-seeds-",".bin",context.cacheDir)
        try {
            val transferStart=System.nanoTime()
            source.writeBytes(input)
            require(seeds.size<=8*1024*1024);cached.writeBytes(seeds)
            val preparationFileNs=System.nanoTime()-transferStart
            val ipcStart=System.nanoTime()
            withTimeout(60_000) { request(source,output,FontWorkerService.PREPARE,fonts,ligatures,nominal,cached) }
            com.fluxx.android.render.TextEditTimings.record("ipc_round_trip",System.nanoTime()-ipcStart)
            require(output.length() in 1..32L*1024*1024) { "Prepared text packet exceeds 32 MiB" }
            val readStart=System.nanoTime()
            output.readBytes().also {com.fluxx.android.render.TextEditTimings.record("packet_file_transfer",preparationFileNs+System.nanoTime()-readStart,"bytes=${it.size}")}
        } finally { source.delete();output.delete();cached.delete() }
    }
    internal suspend fun crashForTest(input: File) {
        check(com.fluxx.android.BuildConfig.DEBUG)
        val output=File.createTempFile("font-crash-proof-",".tmp",context.cacheDir)
        try { withTimeout(15_000) { request(input,output,FontWorkerService.CRASH_PROOF) } }
        finally { output.delete() }
    }
    private suspend fun request(input: File,output: File,operation: Int=FontWorkerService.INSPECT,
        fonts: List<FontInput> = emptyList(),ligatures: Boolean=true,nominal: Boolean=false,cached: File?=null)=withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine<Unit> { pending ->
            keepWarm(context)
            requests.incrementAndGet()
            var bound=false
            lateinit var connection: ServiceConnection
            fun release() { if(bound) { bound=false;runCatching { context.unbindService(connection) };keepWarm(context) } }
            fun failed(reason: String) { release();if(pending.isActive) pending.resumeWithException(IOException(reason)) }
            val response=Messenger(object: Handler(Looper.getMainLooper()) {
                override fun handleMessage(message: Message) {
                    if(message.what!=operation || !pending.isActive) return
                    if(message.data.getBoolean(FontWorkerService.OK)) {
                        message.data.getLongArray("timings")?.let {times -> if(times.size==3) {
                            com.fluxx.android.render.TextEditTimings.record("shaping",times[0],"isolated")
                            com.fluxx.android.render.TextEditTimings.record("rasterization",times[1],"isolated cache misses")
                            com.fluxx.android.render.TextEditTimings.record("packet_encode",times[2],"isolated excluding raster")
                        } }
                        release();pending.resume(Unit)
                    }
                    else failed(message.data.getString(FontWorkerService.ERROR) ?: "Font validation failed")
                }
            })
            connection=object: ServiceConnection {
                override fun onServiceConnected(name: ComponentName,binder: IBinder) {
                    if(!pending.isActive) { release();return }
                    launch(Dispatchers.IO) {
                    val descriptors=mutableListOf<ParcelFileDescriptor>()
                    try {
                        ParcelFileDescriptor.open(input,ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
                            ParcelFileDescriptor.open(output,ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE).use { destination ->
                                val message=Message.obtain(null,operation).apply {
                                    replyTo=response
                                    data=Bundle().apply {
                                        putParcelable(FontWorkerService.INPUT,source);putParcelable(FontWorkerService.OUTPUT,destination)
                                        putBoolean("ligatures",ligatures)
                                        putBoolean("nominal",nominal)
                                        if(cached!=null) putParcelable(FontWorkerService.CACHE,ParcelFileDescriptor.open(cached,ParcelFileDescriptor.MODE_READ_ONLY).also {descriptors.add(it)})
                                        val rows=ArrayList<Bundle>()
                                        for(font in fonts) {
                                            val descriptor=ParcelFileDescriptor.open(font.file,ParcelFileDescriptor.MODE_READ_ONLY).also { descriptors.add(it) }
                                            rows.add(Bundle().apply {
                                                putParcelable(FontWorkerService.INPUT,descriptor);putString("id",font.id)
                                                putInt("face",font.reference.faceIndex);putInt("instance",font.reference.namedInstance)
                                                putString("tags",font.reference.axes.joinToString("") { it.tag })
                                                putFloatArray("values",font.reference.axes.map { it.value.staticValue }.toFloatArray())
                                            })
                                        }
                                        putParcelableArrayList(FontWorkerService.FONTS,rows)
                                    }
                                }
                                Messenger(binder).send(message)
                            }
                        }
                    } catch(e: Exception) { Handler(Looper.getMainLooper()).post { failed(e.message ?: "Cannot reach the isolated font worker") } }
                    finally { descriptors.forEach { runCatching { it.close() } } }
                    }
                }
                override fun onServiceDisconnected(name: ComponentName)=failed("The isolated font parser stopped. The font was not imported; try another font file.")
                override fun onBindingDied(name: ComponentName)=failed("The isolated font parser died; the font was not imported.")
                override fun onNullBinding(name: ComponentName)=failed("The isolated font worker is unavailable")
            }
            pending.invokeOnCancellation { Handler(Looper.getMainLooper()).post { release() } }
            try {
                bound=context.bindService(Intent(context,FontWorkerService::class.java),connection,Context.BIND_AUTO_CREATE)
                if(!bound) failed("Cannot bind the isolated font worker")
            } catch(e: Exception) { failed(e.message ?: "Cannot bind the isolated font worker") }
        }
    }
    companion object {
        // One process-retaining binding; normal per-request bindings still report parser death
        // to their own continuation. Idle expiry releases native caches with the process.
        private val main=Handler(Looper.getMainLooper())
        private var warmContext: Context?=null
        private var warmConnection: ServiceConnection?=null
        private val expire=Runnable {
            warmConnection?.let {connection -> runCatching {warmContext?.unbindService(connection)}}
            warmConnection=null;warmContext=null
        }
        private fun keepWarm(context: Context) {
            check(Looper.myLooper()==Looper.getMainLooper())
            main.removeCallbacks(expire)
            if(warmConnection==null) {
                val connection=object: ServiceConnection {
                    override fun onServiceConnected(name: ComponentName,binder: IBinder) {}
                    override fun onServiceDisconnected(name: ComponentName) {main.removeCallbacks(expire);expire.run()}
                    override fun onBindingDied(name: ComponentName) {main.removeCallbacks(expire);expire.run()}
                }
                if(runCatching {context.bindService(Intent(context,FontWorkerService::class.java),connection,Context.BIND_AUTO_CREATE)}.getOrDefault(false)) {
                    warmContext=context;warmConnection=connection
                }
            }
            main.postDelayed(expire,30_000)
        }
        private val requests=java.util.concurrent.atomic.AtomicLong()
        val requestCount get()=requests.get()
    }
}
