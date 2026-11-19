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
    private val faces=TextFontRef.BUNDLED_IDS.toList()
    private val scalars=(32..126).toList()+(160..255).toList()
    private var session: FontSession?=null
    /** TEXT PERF 10: renderer frames pause the prewarm; it resumes this long after the last frame. */
    const val QUIET_MS=750L
    val activity=FrameActivityGate(QUIET_MS*1_000_000)
    /** Called once per rendered frame (render thread): one volatile store, no allocation. */
    fun frameRendered(nowNs: Long) = activity.frame(nowNs)
    private val loop=IdleWarmLoop(faces.size*scalars.size,activity) { index ->
        val fonts=requireNotNull(session)
        fonts.warmGlyph(faces[index/scalars.size],scalars[index%scalars.size])
    }
    @Synchronized fun foreground(context: Context) {
        val generation=epoch.incrementAndGet();job?.cancel()
        if(loop.finished) return
        val app=context.applicationContext
        job=scope.launch {
            delay(1500)
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            if(session==null) session=FontSession(app.assets)
            val began=System.nanoTime();val startCursor=loop.cursor;val startBytes=loop.bytes
            try {
                while(epoch.get()==generation) {
                    ensureActive()
                    when(loop.next(System.nanoTime())) {
                        IdleWarmLoop.Step.DONE -> break
                        IdleWarmLoop.Step.PAUSED -> delay(QUIET_MS/3) // Playback or scrubbing: stay off the CPU.
                        IdleWarmLoop.Step.WARMED -> yield()
                    }
                }
            } finally {TextEditTimings.record("idle_prewarm",System.nanoTime()-began,"glyphs=${loop.cursor-startCursor},requested_pixel_bytes=${loop.bytes-startBytes}")}
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
