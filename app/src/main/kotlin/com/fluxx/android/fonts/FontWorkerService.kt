package com.fluxx.android.fonts

import android.app.Service
import android.content.Intent
import android.os.*
import com.fluxx.android.engine.IsolatedFontParser
import java.io.ByteArrayOutputStream

/** No private-storage access or network permissions; receives only scoped file descriptors. */
class FontWorkerService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var endpoint: Messenger
    override fun onCreate() {
        super.onCreate()
        thread=HandlerThread("isolated-font-preparation").also { it.start() }
        endpoint=Messenger(object: Handler(thread.looper) {
            override fun handleMessage(message: Message) {
                if(message.what==CRASH_PROOF && com.fluxx.android.BuildConfig.DEBUG) {
                    android.os.Process.killProcess(android.os.Process.myPid());return
                }
                if(message.what !in listOf(INSPECT,PREPARE) || message.replyTo==null) return
                val reply=Message.obtain(null,message.what)
                @Suppress("DEPRECATION")
                val input=message.data.getParcelable<ParcelFileDescriptor>(INPUT)
                @Suppress("DEPRECATION")
                val output=message.data.getParcelable<ParcelFileDescriptor>(OUTPUT)
                @Suppress("DEPRECATION")
                val rows=message.data.getParcelableArrayList<Bundle>(FONTS).orEmpty()
                val main=Handler(Looper.getMainLooper())
                val watchdog=Runnable { android.os.Process.killProcess(android.os.Process.myPid()) }
                main.postDelayed(watchdog,60_000)
                try {
                    requireNotNull(input);requireNotNull(output)
                    val bytes=ParcelFileDescriptor.AutoCloseInputStream(input).use { stream ->
                        val buffer=ByteArray(8192);val collected=ByteArrayOutputStream()
                        while(true) {
                            val count=stream.read(buffer);if(count<0) break
                            require(count<=MAX_FONT_BYTES-collected.size()) { "Font files must be at most 16 MiB" }
                            collected.write(buffer,0,count)
                        }
                        val data=collected.toByteArray()
                        require(data.isNotEmpty() && data.size<=MAX_FONT_BYTES) { "Font files must be nonempty and at most 16 MiB" };data
                    }
                    val result=if(message.what==INSPECT) {
                        IsolatedFontParser.inspect(bytes).toByteArray(Charsets.UTF_8).also { require(it.size<=MAX_METADATA_BYTES) }
                    } else {
                        require(rows.size<=64) { "Prepared text supports at most 64 imported font identities" }
                        var total=0
                        val fonts=rows.map { row ->
                            @Suppress("DEPRECATION")
                            val file=requireNotNull(row.getParcelable<ParcelFileDescriptor>(INPUT))
                            ParcelFileDescriptor.AutoCloseInputStream(file).use { stream ->
                                val collected=ByteArrayOutputStream();val buffer=ByteArray(8192)
                                while(true) {
                                    val count=stream.read(buffer);if(count<0) break
                                    require(count<=MAX_FONT_BYTES-collected.size() && count<=32*1024*1024-total) { "Imported font preparation exceeds 32 MiB; reduce distinct font faces" }
                                    collected.write(buffer,0,count);total+=count
                                }
                                collected.toByteArray()
                            }
                        }.toTypedArray()
                        IsolatedFontParser.prepare(assets,rows.map { requireNotNull(it.getString("id")) }.toTypedArray(),fonts,
                            rows.map { it.getInt("face") }.toIntArray(),rows.map { it.getInt("instance",-1) }.toIntArray(),
                            rows.map { it.getString("tags").orEmpty() }.toTypedArray(),rows.map { it.getFloatArray("values") ?: FloatArray(0) }.toTypedArray(),
                            bytes,message.data.getBoolean("ligatures"),message.data.getBoolean("nominal"))
                    }
                    ParcelFileDescriptor.AutoCloseOutputStream(output).use { it.write(result) }
                    reply.data=Bundle().apply { putBoolean(OK,true) }
                } catch(e: Exception) {
                    runCatching { input?.close() };runCatching { output?.close() }
                    reply.data=Bundle().apply { putString(ERROR,e.message?.take(1024) ?: "Font validation failed") }
                } finally {
                    main.removeCallbacks(watchdog)
                    runCatching { input?.close() };runCatching { output?.close() }
                    for(row in rows) {
                        @Suppress("DEPRECATION")
                        val file=row.getParcelable<ParcelFileDescriptor>(INPUT)
                        runCatching { file?.close() }
                    }
                }
                runCatching { message.replyTo.send(reply) }
            }
        })
    }
    override fun onBind(intent: Intent)=endpoint.binder
    override fun onDestroy() { thread.quitSafely();super.onDestroy() }
    companion object {
        const val INSPECT=1
        const val PREPARE=2;const val FONTS="fonts"
        internal const val CRASH_PROOF=99
        const val INPUT="input";const val OUTPUT="output";const val OK="ok";const val ERROR="error"
        const val MAX_FONT_BYTES=16*1024*1024;const val MAX_METADATA_BYTES=4*1024*1024
    }
}
