package com.fluxx.android.render

import android.content.Context
import android.os.Looper
import com.fluxx.android.engine.FontSession
import com.fluxx.android.model.TextLayoutMetrics
import com.fluxx.android.model.TextMetricsProvider
import com.fluxx.android.model.TextSourceDocument
import com.fluxx.android.model.StyledTextPreparation
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class NativeTextMetricsProvider(private val context: Context) : TextMetricsProvider, AutoCloseable {
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
    fun measure(fontId: String,source: TextSourceDocument,ligatures: Boolean): TextLayoutMetrics {
        check(Thread.currentThread() === owner && Looper.myLooper() != Looper.getMainLooper())
        val metrics=if(com.fluxx.android.fonts.ImportedTextPreparation.needed(source)) {
            com.fluxx.android.engine.PreparedTextData.metrics(com.fluxx.android.fonts.ImportedTextPreparation.prepare(context,fontId,source,ligatures).packet)
        } else session.styledMetrics(fontId,StyledTextPreparation.encode(source,fontId),ligatures)
        return TextLayoutMetrics.fromNative(metrics,source.text.isEmpty(),source)
    }
    override fun close() { check(Thread.currentThread() === owner); session.close() }
}

/** Process-owned CPU service. Session creation, shaping and mutex waits all occur on this worker. */
class TextMetricsRepository private constructor(context: Context) {
    private val app = context.applicationContext
    init {TextEditTimings.initialize(app)}
    private val dispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "Fluxx-text-metrics") }.asCoroutineDispatcher()
    private val cache = object : LinkedHashMap<Triple<String, String,Boolean>, TextLayoutMetrics>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<String, String,Boolean>, TextLayoutMetrics>?) = size > 64
    }
    private var provider: NativeTextMetricsProvider? = null
    private val fontLibrary=com.fluxx.android.fonts.FontLibraryRepository.getInstance(app)
    private var fontRevision= -1L
    private val characterCache=object: LinkedHashMap<Pair<String,com.fluxx.android.model.TextProperties>,com.fluxx.android.model.TextCharacterCatalogue>(8,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String,com.fluxx.android.model.TextProperties>,com.fluxx.android.model.TextCharacterCatalogue>?)=size>8
    }
    private val richCache=object: LinkedHashMap<Triple<String,TextSourceDocument,Boolean>,TextLayoutMetrics>(64,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<String,TextSourceDocument,Boolean>,TextLayoutMetrics>?)=size>64
    }
    suspend fun measure(font: String,source: TextSourceDocument,ligatures: Boolean=true, text: com.fluxx.android.model.TextProperties?=null): TextLayoutMetrics = withContext(dispatcher) {
        fontLibrary.load()
        if(fontRevision!=fontLibrary.revision) { richCache.clear();characterCache.clear();fontRevision=fontLibrary.revision }
        require(source.paragraphs.all { it.direction==com.fluxx.android.model.ParagraphDirection.LTR }) { "RTL layout requires T5; requested direction is preserved" }
        val native=provider ?: NativeTextMetricsProvider(app).also { provider=it }
        TextIdlePrewarm.foreground(app)
        val keyStart=System.nanoTime()
        val shaping=StyledTextPreparation.shapingDocument(source)
        TextEditTimings.record("key_build",System.nanoTime()-keyStart)
        val layout=richCache.getOrPut(Triple(font,shaping,ligatures)) {
            val start=System.nanoTime();native.measure(font,shaping,ligatures).also {TextEditTimings.record("metrics_preparation",System.nanoTime()-start)}
        }.withDocument(source)
        if(text!=null && StyledTextPreparation.needsBothModes(text)) {
            val partner=Triple(font,shaping,!ligatures)
            if(!richCache.containsKey(partner)) richCache[partner]=native.measure(font,shaping,!ligatures)
        }
        else TextIdlePrewarm.partner(app,font,shaping,!ligatures)
        if(text!=null && com.fluxx.android.model.TextCharacterPreparation.needed(text)) {
            val documents=StyledTextPreparation.alternatives(text.source)
            val key=font to text
            val catalogue=characterCache[key] ?: TextCharacterCataloguePreparation.prepare(app,font,text,documents)?.catalogue?.also { characterCache[key]=it }
            if(catalogue!=null) layout.withCharacterCatalogue(catalogue) else layout
        } else layout
    }
    suspend fun measure(font: String, source: String,ligatures: Boolean=true): TextLayoutMetrics = withContext(dispatcher) {
        check(Looper.myLooper() != Looper.getMainLooper())
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
