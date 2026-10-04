package com.fluxx.android.render

import android.content.Context
import android.os.Looper
import com.fluxx.android.engine.FontSession
import com.fluxx.android.model.TextLayoutMetrics
import com.fluxx.android.model.TextMetricsProvider
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class NativeTextMetricsProvider(context: Context) : TextMetricsProvider, AutoCloseable {
    private val owner = Thread.currentThread()
    private val session = run {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Native text metrics require a worker" }
        FontSession(context.applicationContext.assets)
    }
    override fun measure(fontId: String, source: String): TextLayoutMetrics {
        return measure(fontId,source,true)
    }
    fun measure(fontId: String,source: String,ligatures: Boolean): TextLayoutMetrics {
        check(Thread.currentThread() === owner && Looper.myLooper() != Looper.getMainLooper())
        return TextLayoutMetrics.fromNative(session.metrics(fontId, source,ligatures), source.isEmpty())
    }
    override fun close() { check(Thread.currentThread() === owner); session.close() }
}

/** Process-owned CPU service. Session creation, shaping and mutex waits all occur on this worker. */
class TextMetricsRepository private constructor(context: Context) {
    private val app = context.applicationContext
    private val dispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "Fluxx-text-metrics") }.asCoroutineDispatcher()
    private val cache = object : LinkedHashMap<Triple<String, String,Boolean>, TextLayoutMetrics>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<String, String,Boolean>, TextLayoutMetrics>?) = size > 64
    }
    private var provider: NativeTextMetricsProvider? = null
    suspend fun measure(font: String, source: String,ligatures: Boolean=true): TextLayoutMetrics = withContext(dispatcher) {
        check(Looper.myLooper() != Looper.getMainLooper())
        // Prepare the partner layout too: a tracking zero crossing can reuse geometry immediately.
        val partner=Triple(font,source,!ligatures)
        if(!cache.containsKey(partner)) {
            val native=provider ?: NativeTextMetricsProvider(app).also { provider=it }
            cache[partner]=native.measure(font,source,!ligatures)
        }
        cache.getOrPut(Triple(font,source,ligatures)) {
            val native = provider ?: NativeTextMetricsProvider(app).also { provider = it }
            native.measure(font, source,ligatures)
        }
    }
    companion object {
        @Volatile private var instance: TextMetricsRepository? = null
        fun get(context: Context): TextMetricsRepository = instance ?: synchronized(this) {
            instance ?: TextMetricsRepository(context).also { instance = it }
        }
    }
}
