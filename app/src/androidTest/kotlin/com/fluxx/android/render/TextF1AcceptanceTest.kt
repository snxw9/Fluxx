package com.fluxx.android.render

import android.content.Intent
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.engine.*
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** User-run only. No alternate mesh/evaluation implementation; exercises the production render worker path. */
@RunWith(AndroidJUnit4::class)
class TextF1AcceptanceTest {
    @Volatile private var retainedAllocationCanary: Any? = null
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun directory(name: String): File {
        val label=requireNotNull(InstrumentationRegistry.getArguments().getString("textRunLabel")) { "Set a unique textRunLabel" }
        require(label.matches(Regex("[A-Za-z0-9_-]+")))
        return File(context.getExternalFilesDir(null),"text/$label/$name").also {
            check(!it.exists()) { "Archive previous proof results" };check(it.mkdirs())
        }
    }
    private fun track(a: Float,b: Float,endUs: Long = 10_000_000)=AnimatableProperty1D(true,a,FrozenList.of(listOf(
        Keyframe1D(0,a,EasingPreset.LINEAR),Keyframe1D(endUs,b,EasingPreset.LINEAR))))
    private fun fixture()=ProjectDocument(Composition(width=1080,height=1920,durationUs=12_000_000,
        layers=FrozenList.of(listOf(CompositionLayer(1,LayerType.TEXT,timing=ClipTiming(durationUs=12_000_000),
            text=TextProperties(fontId="fluxx.serif",source=AnimatableString(staticValue="AV office gy 0123456789 \n".repeat(30)),
                size=track(8f,72f),tracking=track(-100f,300f),leading=track(-10f,30f),baselineShift=track(-20f,40f),
                strokeWidth=track(20f,5f),strokeOverFill=track(0f,1f,5_000_000),
                fill=AnimatableColour(true,0x80ff0000.toInt(),FrozenList.of(listOf(ColourKeyframe(0,0x80ff0000.toInt()),ColourKeyframe(10_000_000,0x8000ff00.toInt())))),
                strokeColour=AnimatableColour(true,0x800000ff.toInt(),FrozenList.of(listOf(ColourKeyframe(0,0x800000ff.toInt()),ColourKeyframe(10_000_000,0x80ffff00.toInt()))))))))))

    @Suppress("DEPRECATION")
    @Test fun fiveHundredGlyphsAllocateNothingColdAndAfterThreeMinutesLoad() {
        val directory=directory("f1-performance")
        val host=instrumentation.startActivitySync(Intent(context,TextProofActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TextProofActivity
        val surface=host.awaitSurface()
        val worker=Executors.newSingleThreadExecutor()
        try {
            worker.submit {
                CompositionRenderer(context,MediaRepository(context),surface,1080,1920).use { renderer ->
                    val project=fixture()
                    repeat(120) { renderer.renderPreview(project,it*16_667L,PreviewResolution.FULL) }
                    val handle=renderer.preparedTextHandle(1)
                    val before=LongArray(6);val after=LongArray(6)
                    TextDebugBridge.frameCounters(renderer.session,handle,before)
                    assertTrue("Fixture must shape 500+ glyphs",before[4]>=500)
                    assertEquals("Native new counter must detect an intentional allocation",1L,TextDebugBridge.allocationCanary())
                    Debug.startAllocCounting()
                    try {
                        // Probe ART support before trusting a zero result from a deprecated platform counter.
                        val initial=Debug.getThreadAllocCount()
                        val canary=arrayOfNulls<Any>(1024)
                        canary[0]=project
                        retainedAllocationCanary=canary
                        val detected=Debug.getThreadAllocCount()
                        assertTrue("ART thread allocation counter unavailable/unreliable on this device",
                            initial in 0..<detected
                        )
                        for (pass in 0..1) {
                            if(pass==1) {
                                val until=SystemClock.elapsedRealtime()+180_000
                                var i=0L
                                while(SystemClock.elapsedRealtime()<until) {
                                    renderer.renderPreview(project,(i++%600)*16_667L,PreviewResolution.FULL)
                                }
                            }
                            val frameNs=LongArray(600);val meshNs=LongArray(600)
                            TextDebugBridge.frameCounters(renderer.session,handle,before)
                            val nativeBefore=TextDebugBridge.allocationCount()
                            val kotlinBefore=Debug.getThreadAllocCount()
                            var frame=0
                            while(frame<600) {
                                val started=System.nanoTime()
                                renderer.renderPreview(project,frame*16_667L,PreviewResolution.FULL)
                                frameNs[frame]=System.nanoTime()-started
                                TextDebugBridge.frameCounters(renderer.session,handle,after)
                                meshNs[frame]=after[0];frame++
                            }
                            val kotlinDelta=Debug.getThreadAllocCount()-kotlinBefore
                            val nativeDelta=TextDebugBridge.allocationCount()-nativeBefore
                            val report=JSONObject().put("pass",pass).put("glyphs",before[4]).put("frames",600)
                                .put("nativeNewDelta",nativeDelta).put("workerThreadAllocDelta",kotlinDelta)
                                .put("frameNs",percentiles(frameNs)).put("meshBuildNs",percentiles(meshNs))
                                .put("rasterizationsDelta",after[1]-before[1]).put("atlasUploadsDelta",after[2]-before[2])
                                .put("shapesDelta",after[3]-before[3]).put("atlasGenerationDelta",after[5]-before[5])
                            File(directory,"pass-$pass.json").writeText(report.toString(2))
                            assertEquals("Kotlin worker must allocate zero objects in measured window",0,kotlinDelta)
                            assertEquals("Native engine must call new zero times in measured window",0L,nativeDelta)
                            for(index in intArrayOf(1,2,3,5)) assertEquals("Prepared text counter $index changed",before[index],after[index])
                            val stats=JSONObject(RenderBridge.textStats(renderer.session))
                            assertTrue(stats.getBoolean("validationEnabled"));assertEquals(0L,stats.getLong("validationErrors"))
                        }
                    } finally { Debug.stopAllocCounting() }
                }
            }.get(10,TimeUnit.MINUTES)
        } finally { worker.shutdownNow();instrumentation.runOnMainSync { host.finish() } }
    }
    private fun percentiles(values: LongArray): JSONObject {
        values.sort()
        return JSONObject().put("p50",values[299]).put("p95",values[569]).put("p99",values[593]).put("max",values[599])
    }

    @Test fun equalResolutionPreviewExportAndThumbnailPixelsMatch() {
        val directory=directory("f1-parity")
        val host=instrumentation.startActivitySync(Intent(context,TextProofActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TextProofActivity
        val surface=host.awaitSurface()
        try {
            CompositionRenderer(context,MediaRepository(context),surface,1080,1920).use { preview ->
                CompositionRenderer(context,MediaRepository(context)).use { export ->
                    CompositionRenderer(context,MediaRepository(context)).use { thumbnail ->
                        val project=fixture();val buffers=Array(3) { ByteBuffer.allocateDirect(1080*1920*4) }
                        for(time in longArrayOf(0,5_000_000,10_000_000)) {
                            preview.renderPreview(project,time,PreviewResolution.FULL)
                            export.render(project,time);thumbnail.render(project,time)
                            for((i,renderer) in listOf(preview,export,thumbnail).withIndex()) {
                                buffers[i].clear();assertTrue(TextDebugBridge.pixels(renderer.session,buffers[i]));buffers[i].rewind()
                            }
                            assertEquals("Export pre-encode pixels differ at $time",buffers[0],buffers[1])
                            assertEquals("Thumbnail renderer pixels differ at $time",buffers[0],buffers[2])
                        }
                        File(directory,"result.json").writeText("{\"matched\":true,\"timesUs\":[0,5000000,10000000]}")
                    }
                }
            }
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }
}
