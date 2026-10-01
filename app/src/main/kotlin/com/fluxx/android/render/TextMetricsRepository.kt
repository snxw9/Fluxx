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
        check(Thread.currentThread() === owner && Looper.myLooper() != Looper.getMainLooper())
        return TextLayoutMetrics.fromNative(session.metrics(fontId, source), source.isEmpty())
    }
    override fun close() { check(Thread.currentThread() === owner); session.close() }
}

/** Process-owned CPU service. Session creation, shaping and mutex waits all occur on this worker. */
class TextMetricsRepository private constructor(context: Context) {
    private val app = context.applicationContext
    private val dispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "Fluxx-text-metrics") }.asCoroutineDispatcher()
    private val cache = object : LinkedHashMap<Pair<String, String>, TextLayoutMetrics>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, TextLayoutMetrics>?) = size > 64
    }
    private var provider: NativeTextMetricsProvider? = null
    suspend fun measure(font: String, source: String): TextLayoutMetrics = withContext(dispatcher) {
        check(Looper.myLooper() != Looper.getMainLooper())
        cache.getOrPut(font to source) {
            val native = provider ?: NativeTextMetricsProvider(app).also { provider = it }
            native.measure(font, source)
        }
    }
    companion object {
        @Volatile private var instance: TextMetricsRepository? = null
        fun get(context: Context): TextMetricsRepository = instance ?: synchronized(this) {
            instance ?: TextMetricsRepository(context).also { instance = it }
        }
    }
}
