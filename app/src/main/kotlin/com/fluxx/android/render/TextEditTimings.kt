package com.fluxx.android.render

import android.content.Context
import com.fluxx.android.BuildConfig
import com.fluxx.android.model.ProjectDocument
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Debug-only edit instrumentation. No formatting, file writes or task creation occurs on
 * a prepared frame unless an edit is awaiting its first completed frame. Durations are CPU
 * wall times, never inferred GPU execution times. */
object TextEditTimings {
    private val writer=Executors.newSingleThreadExecutor {r -> Thread(r,"Fluxx-text-timing-log")}
    private val serial=AtomicLong();private val pending=AtomicLong()
    private class Ticket(val id: Long,val began: Long) {@Volatile var project: ProjectDocument?=null}
    @Volatile private var ticket: Ticket?=null
    @Volatile private var file: File?=null
    fun initialize(context: Context) {if(BuildConfig.DEBUG) file=File(context.filesDir,"text-edit-timings.jsonl")}
    fun begin(): Long {
        if(!BuildConfig.DEBUG) return 0
        val id=serial.incrementAndGet();ticket=Ticket(id,System.nanoTime());pending.set(id);record("edit",0,"requested",id);return id
    }
    val waiting get()=BuildConfig.DEBUG && pending.get()!=0L
    fun expect(project: ProjectDocument) {if(BuildConfig.DEBUG) ticket?.project=project}
    fun waitingFor(project: ProjectDocument)=waiting && ticket?.project===project
    fun record(stage: String,nanos: Long,detail: String="",id: Long=serial.get()) {
        if(!BuildConfig.DEBUG || file==null) return
        val target=requireNotNull(file)
        writer.execute {
            runCatching {
                // Bounded, shareable log: retain the previous 4 MiB plus the current 4 MiB.
                if(target.length()>4*1024*1024) {val previous=File(target.path+".previous");previous.delete();target.renameTo(previous)}
                val row=org.json.JSONObject().put("edit",id).put("stage",stage).put("cpu_wall_ns",nanos).put("detail",detail)
                target.appendText(row.toString()+"\n")
            }
        }
    }
    fun frameReady(project: ProjectDocument,stats: String) {
        val current=ticket ?: return
        if(!BuildConfig.DEBUG || current.project!==project || !pending.compareAndSet(current.id,0)) return
        record("first_completed_frame",System.nanoTime()-current.began,stats,current.id)
    }
}
