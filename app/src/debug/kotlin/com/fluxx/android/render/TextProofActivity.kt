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
    private val surfaces = LinkedBlockingQueue<Surface>()
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); installSurface() }
    fun installSurface() {
        surfaces.clear()
        setContentView(SurfaceView(this).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = Unit
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    if (holder.surface.isValid) surfaces.offer(holder.surface)
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            })
        })
    }
    fun awaitSurface(): Surface = requireNotNull(surfaces.poll(15, TimeUnit.SECONDS)) { "Proof surface timed out" }
}
