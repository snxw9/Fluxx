package com.fluxx.android.render

import android.app.Activity
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Debug-only visible proof host. Native work remains on the instrumentation owner thread. */
class TextProofActivity : Activity() {
    /** TEXT PERF 11: lifecycle hooks so a harness can wire a preview exactly as MainActivity does (main thread). */
    interface Lifecycle {
        fun paused() {}
        fun resumed() {}
        fun surfaceCreated(surface: Surface, width: Int, height: Int) {}
        fun surfaceChanged(width: Int, height: Int) {}
        fun surfaceDestroyed() {}
    }
    @Volatile var lifecycle: Lifecycle? = null
    private val surfaces = LinkedBlockingQueue<Surface>()
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); installSurface() }
    override fun onResume() { super.onResume(); lifecycle?.resumed() }
    override fun onPause() { lifecycle?.paused(); super.onPause() }
    fun installSurface() {
        surfaces.clear()
        setContentView(SurfaceView(this).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    lifecycle?.surfaceCreated(holder.surface, if (width > 0) width else 1, if (height > 0) height else 1)
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    if (holder.surface.isValid) surfaces.offer(holder.surface)
                    if (width > 0 && height > 0) lifecycle?.surfaceChanged(width, height)
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) { lifecycle?.surfaceDestroyed() }
            })
        })
    }
    fun awaitSurface(): Surface = requireNotNull(surfaces.poll(15, TimeUnit.SECONDS)) { "Proof surface timed out" }
}
