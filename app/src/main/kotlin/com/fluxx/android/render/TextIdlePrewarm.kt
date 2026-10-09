package com.fluxx.android.render

import android.content.Context
import com.fluxx.android.engine.FontSession
import com.fluxx.android.model.*
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Best-effort idle work only. Foreground work cancels between glyphs, never on a renderer
 * frame. The shared native SDF cache keeps its existing 8 MiB unpinned budget. */
object TextIdlePrewarm {
    private val dispatcher=Executors.newSingleThreadExecutor {r -> Thread(r,"Fluxx-text-idle").apply {priority=Thread.MIN_PRIORITY}}.asCoroutineDispatcher()
    private val scope=CoroutineScope(SupervisorJob()+dispatcher)
    private var job: Job?=null
    private val epoch=AtomicLong()
    @Volatile private var cursor=0
    private val faces=TextFontRef.BUNDLED_IDS.toList()
    private val scalars=(32..126).toList()+(160..255).toList()
    private var session: FontSession?=null
    @Synchronized fun foreground(context: Context) {
        val generation=epoch.incrementAndGet();job?.cancel()
        if(cursor>=faces.size*scalars.size) return
        val app=context.applicationContext
        job=scope.launch {
            delay(1500)
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val fonts=session ?: FontSession(app.assets).also {session=it}
            val began=System.nanoTime();var bytes=0L;var glyphs=0
            try {
                while(cursor<faces.size*scalars.size && epoch.get()==generation) {
                    ensureActive()
                    bytes+=fonts.warmGlyph(faces[cursor/scalars.size],scalars[cursor%scalars.size]);cursor++;glyphs++
                    yield()
                }
            } finally {TextEditTimings.record("idle_prewarm",System.nanoTime()-began,"glyphs=$glyphs,requested_pixel_bytes=$bytes")}
        }
    }
    fun imported(context: Context,reference: TextFontRef) {
        scope.launch {
            delay(1500)
            var observed=epoch.get()
            // Small batches allow cancellation/foreground checks before another IPC request.
            for(batch in (32..126).toList().chunked(8)) {
                while(epoch.get()!=observed) {observed=epoch.get();delay(1500);ensureActive()}
                val text=buildString {for(cp in batch) appendCodePoint(cp)}
                val document=TextSourceDocument(text).format(0,0) {it.copy(font=reference)}
                runCatching {com.fluxx.android.fonts.ImportedTextPreparation.prepare(context,"fluxx.sans",document,false)}
                yield()
            }
        }
    }
    fun partner(context: Context,font: String,document: TextSourceDocument,ligatures: Boolean) {
        val observed=epoch.get();val app=context.applicationContext
        scope.launch {
            delay(100)
            if(epoch.get()!=observed) return@launch
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val started=System.nanoTime()
            runCatching {
                if(com.fluxx.android.fonts.ImportedTextPreparation.needed(document))
                    com.fluxx.android.fonts.ImportedTextPreparation.prepare(app,font,document,ligatures)
                else {
                    val fonts=session ?: FontSession(app.assets).also {session=it}
                    fonts.preparedPacket(font,StyledTextPreparation.encode(document,font),ligatures)
                }
            }
            TextEditTimings.record("background_ligature_partner",System.nanoTime()-started)
        }
    }
}
