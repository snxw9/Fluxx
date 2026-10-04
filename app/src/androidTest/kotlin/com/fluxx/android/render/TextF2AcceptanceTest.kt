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

/** Acceptance source only: production renderer, fixed declared limits, no reduced workload. */
@RunWith(AndroidJUnit4::class)
class TextF2AcceptanceTest {
    @Test fun preparedTransportPreservesRichMetrics() {
        val worker=Executors.newSingleThreadExecutor()
        try { worker.submit {
            val text=fixture(false,true).composition.layers.single().text
            val input=StyledTextPreparation.encode(text.source.evaluateDocument(0),text.fontId)
            FontSession(context.assets).use { fonts ->
                for(ligatures in listOf(true,false)) {
                    val expected=fonts.styledMetrics(text.fontId,input,ligatures)
                    val packet=fonts.preparedPacket(text.fontId,input,ligatures)
                    assertArrayEquals(expected,PreparedTextData.metrics(packet),0.0)
                    val invalid=packet.copyOf();invalid[7]=2
                    assertThrows(IllegalStateException::class.java) { PreparedTextData.metrics(invalid) }
                }
            }
        }.get(1,TimeUnit.MINUTES) } finally { worker.shutdownNow() }
    }
    @Volatile private var allocationCanary: Any? = null
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun scalar(v: Float)=AnimatableProperty1D(staticValue=v)
    private fun track(a: Float,b: Float)=AnimatableProperty1D(true,a,FrozenList.of(listOf(
        Keyframe1D(0,a,EasingPreset.LINEAR),Keyframe1D(10_000_000,b,EasingPreset.EASY_EASE))))
    private fun directory(name: String): File {
        val label=requireNotNull(InstrumentationRegistry.getArguments().getString("textRunLabel"))
        require(label.matches(Regex("[A-Za-z0-9_-]+")))
        return File(context.getExternalFilesDir(null),"text/$label/$name").also {
            check(!it.exists()) { "Archive the earlier reports and use a new textRunLabel" };check(it.mkdirs())
        }
    }
    private fun fixture(maximum: Boolean,rich: Boolean=false): ProjectDocument {
        val count=if(maximum) 16 else 8;val selectors=if(maximum) 8 else 4
        val source=if(maximum) "A".repeat(4096) else "AV office gy 0123456789 \n".repeat(30)
        val animators=List(count) { a -> TextAnimator(a+1L,anchorGrouping=scalar((a%4).toFloat()),
            selectors=FrozenList.of(List(selectors) { s -> TextRangeSelector(100L+a*8+s,
                start=track(0f,10f),end=track(100f,90f),offset=track(-5f,5f),
                basedOn=scalar((s%4).toFloat()),shape=scalar((s%6).toFloat()),mode=scalar((s%6).toFloat()),
                easeLow=track(-30f,30f),easeHigh=track(30f,-30f),randomizeOrder=scalar(1f),seed=0x12345678L+a*8+s) }),
            properties=FrozenList.of(AnimatorProperty.entries.map { p ->
                when(p.kind) {
                    2 -> TextPropertyOverride(p,vector=if(p==AnimatorProperty.SCALE) AnimatableProperty2D(staticX=101f,staticY=99f)
                        else AnimatableProperty2D(staticX=1f,staticY=-1f))
                    3 -> TextPropertyOverride(p,colour=AnimatableColour(staticValue=if(p==AnimatorProperty.FILL) 0x80ff8800.toInt() else 0x800080ff.toInt()))
                    else -> TextPropertyOverride(p,scalar=when(p) {
                        AnimatorProperty.OPACITY -> track(100f,98f)
                        AnimatorProperty.LINE_ANCHOR -> track(0f,100f)
                        AnimatorProperty.SKEW_AXIS -> track(0f,90f)
                        AnimatorProperty.TRACKING -> track(-5f,5f)
                        AnimatorProperty.STROKE_WIDTH -> track(0f,.1f)
                        AnimatorProperty.BLUR -> track(0f,.1f)
                        else -> track(-1f,1f)
                    })
                }
            })) }
        var document=TextSourceDocument(source)
        if(rich) {
            val boundaries=document.boundaries
            for(part in 0 until 12) {
                val start=boundaries[part*boundaries.lastIndex/12];val end=boundaries[(part+1)*boundaries.lastIndex/12]
                document=document.format(start,end) { it.copy(font=TextFontRef(listOf("fluxx.sans","fluxx.serif","fluxx.mono")[part%3],TextFaceStyle.entries[part%4]),
                    sizeFactor=1f+part*.02f,horizontalScale=90f+part,verticalScale=110f-part,
                    fauxBold=part%2==0,fauxItalic=part%3==0,caps=TextCaps.entries[part%3],script=TextScriptPosition.entries[part%3],
                    trackingDelta=part.toFloat(),baselineDelta=(part%3).toFloat(),strokeDelta=.1f,
                    fill=if(part%2==0) 0x80ff4400.toInt() else null,stroke=if(part%2!=0) 0x800088ff.toInt() else null,
                    kerning=if(part%2==0) TextKerning.METRICS else TextKerning.OFF) }
            }
        }
        val text=TextProperties(fontId="fluxx.serif",source=AnimatableString(staticValue=source,document=document.takeIf { rich }),size=track(8f,72f),
            tracking=track(-100f,100f),strokeWidth=track(20f,0f),strokeOverFill=track(0f,1f),
            ligatures=track(1f,0f),animators=FrozenList.of(animators))
        return ProjectDocument(Composition(width=1080,height=1920,durationUs=12_000_000,
            layers=FrozenList.of(listOf(CompositionLayer(1,LayerType.TEXT,timing=ClipTiming(durationUs=12_000_000),text=text)))))
    }
    @Test fun representativeLimitsColdAndThermal()=performance(false)
    @Test fun maximumLimitsColdAndThermal()=performance(true)
    @Test fun richRepresentativeColdAndThermal()=performance(false,true)
    @Test fun richMaximumColdAndThermal()=performance(true,true)

    @Suppress("DEPRECATION") private fun performance(maximum: Boolean,rich: Boolean=false) {
        val directory=directory((if(rich) "t1-rich-" else "f2-")+(if(maximum) "maximum" else "representative"))
        val host=instrumentation.startActivitySync(Intent(context,TextProofActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TextProofActivity
        val worker=Executors.newSingleThreadExecutor()
        try {
            val surface=host.awaitSurface()
            worker.submit {
                CompositionRenderer(context,MediaRepository(context),surface,1080,1920).use { renderer ->
                    val project=fixture(maximum,rich)
                    repeat(120) { renderer.renderPreview(project,it*16_667L,PreviewResolution.FULL) }
                    val handle=renderer.preparedTextHandle(1)
                    val before=LongArray(6);val after=LongArray(6);val evaluation=LongArray(3)
                    TextDebugBridge.frameCounters(renderer.session,handle,before)
                    if(maximum) assertEquals(4096L,before[4]) else assertTrue(before[4]>=500)
                    assertEquals(1L,TextDebugBridge.allocationCanary())
                    Debug.startAllocCounting()
                    try {
                        val initial=Debug.getThreadAllocCount();allocationCanary=arrayOfNulls<Any>(1024)
                        assertTrue("ART allocation counter must detect the canary",initial>=0 && Debug.getThreadAllocCount()>initial)
                        // Write BOTH reports before asserting: a cold failure must not discard thermal evidence.
                        val failures=ArrayList<String>()
                        for(pass in 0..1) {
                            if(pass==1) {
                                val until=SystemClock.elapsedRealtime()+180_000;var frame=0L
                                while(SystemClock.elapsedRealtime()<until) renderer.renderPreview(project,(frame++%600)*16_667L,PreviewResolution.FULL)
                            }
                            repeat(120) { renderer.renderPreview(project,it*16_667L,PreviewResolution.FULL) }
                            val frameNs=LongArray(600);val meshNs=LongArray(600);val selectorNs=LongArray(600);val geometryNs=LongArray(600)
                            renderer.textEvaluationCounters(1,evaluation);val coverageBefore=evaluation[2]
                            TextDebugBridge.frameCounters(renderer.session,handle,before)
                            val nativeBefore=TextDebugBridge.allocationCount();val kotlinBefore=Debug.getThreadAllocCount()
                            var frame=0;var missed=0
                            while(frame<600) {
                                val began=System.nanoTime()
                                renderer.renderPreview(project,frame*16_667L,PreviewResolution.FULL)
                                frameNs[frame]=System.nanoTime()-began
                                if(frameNs[frame]>16_666_667) missed++
                                TextDebugBridge.frameCounters(renderer.session,handle,after);meshNs[frame]=after[0]
                                renderer.textEvaluationCounters(1,evaluation);selectorNs[frame]=evaluation[0];geometryNs[frame]=evaluation[1]
                                frame++
                            }
                            val kotlinDelta=Debug.getThreadAllocCount()-kotlinBefore;val nativeDelta=TextDebugBridge.allocationCount()-nativeBefore
                            val report=JSONObject().put("pass",pass).put("glyphs",before[4]).put("animators",if(maximum) 16 else 8)
                                .put("selectorsPerAnimator",if(maximum) 8 else 4).put("frames",600).put("warmFrames",120)
                                .put("thermalLoadSeconds",if(pass==1) 180 else 0).put("nativeNewDelta",nativeDelta).put("workerThreadAllocDelta",kotlinDelta)
                                .put("frameNs",percentiles(frameNs)).put("meshBuildNs",percentiles(meshNs))
                                .put("selectorNs",percentiles(selectorNs)).put("propertyGeometryNs",percentiles(geometryNs))
                                .put("framesAbove60fpsBudget",missed).put("holds60fps",frameNs[593]<=16_666_667)
                                .put("coverageBuildsDelta",evaluation[2]-coverageBefore)
                                .put("rasterizationsDelta",after[1]-before[1]).put("atlasUploadsDelta",after[2]-before[2])
                                .put("shapesDelta",after[3]-before[3]).put("atlasGenerationDelta",after[5]-before[5])
                            val stats=JSONObject(RenderBridge.textStats(renderer.session))
                            report.put("validationEnabled",stats.getBoolean("validationEnabled")).put("validationErrors",stats.getLong("validationErrors"))
                            File(directory,"pass-$pass.json").writeText(report.toString(2))
                            if(kotlinDelta!=0 || nativeDelta!=0L) failures.add("pass $pass allocation: Kotlin=$kotlinDelta native=$nativeDelta")
                            for(i in intArrayOf(1,2,3,5)) if(before[i]!=after[i]) failures.add("pass $pass prepared counter $i changed")
                            if(frameNs[593]>16_666_667) failures.add("pass $pass p99 exceeds 60fps; report limits before lowering them")
                            if(!stats.getBoolean("validationEnabled") || stats.getLong("validationErrors")!=0L) failures.add("pass $pass Vulkan validation failure")
                        }
                        assertTrue(failures.joinToString("; "),failures.isEmpty())
                    } finally { Debug.stopAllocCounting() }
                }
            }.get(15,TimeUnit.MINUTES)
        } finally { worker.shutdownNow();instrumentation.runOnMainSync { host.finish() } }
    }
    private fun percentiles(values: LongArray): JSONObject {
        values.sort();return JSONObject().put("p50",values[299]).put("p95",values[569]).put("p99",values[593]).put("max",values[599])
    }
    @Test fun randomAndReversePreEncodePixelsMatch()=parity(false)
    @Test fun richRandomAndReversePreEncodePixelsMatch()=parity(true)
    private fun parity(rich: Boolean) {
        val directory=directory(if(rich) "t1-rich-parity" else "f2-parity")
        val host=instrumentation.startActivitySync(Intent(context,TextProofActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TextProofActivity
        try {
            CompositionRenderer(context,MediaRepository(context),host.awaitSurface(),1080,1920).use { preview ->
                CompositionRenderer(context,MediaRepository(context)).use { export ->
                    CompositionRenderer(context,MediaRepository(context)).use { thumbnail ->
                        val base=fixture(false,rich);val layer=base.composition.layers[0]
                        val source=layer.text.source.copy(isAnimated=true,keyframes=FrozenList.of(listOf(
                            StringKeyframe(0,layer.text.source.staticValue,layer.text.source.document),
                            StringKeyframe(7_000_000,"office A\u0301 B\n\nAV",if(rich) TextSourceDocument("office A\u0301 B\n\nAV").format(0,0) { it.copy(font=TextFontRef("fluxx.serif",TextFaceStyle.BOLD_ITALIC),fauxItalic=true,baselineDelta=5f) } else null))))
                        val project=base.copy(composition=base.composition.copy(layers=FrozenList.of(listOf(layer.copy(text=layer.text.copy(source=source))))))
                        val renderers=arrayOf(preview,export,thumbnail);val buffers=Array(3) { ByteBuffer.allocateDirect(1080*1920*4) }
                        val times=longArrayOf(0,10_000_000,5_000_000,0,7_500_000,5_000_000,9_000_000,2_000_000)
                        for(time in times) {
                            preview.renderPreview(project,time,PreviewResolution.FULL);export.render(project,time);thumbnail.render(project,time)
                            for(i in 0..2) { buffers[i].clear();assertTrue(TextDebugBridge.pixels(renderers[i].session,buffers[i]));buffers[i].rewind() }
                            assertEquals("Export pre-encode mismatch at $time",buffers[0],buffers[1])
                            assertEquals("Thumbnail pre-encode mismatch at $time",buffers[0],buffers[2])
                        }
                        File(directory,"result.json").writeText(JSONObject().put("matched",true).put("timesUs",org.json.JSONArray(times.toList())).toString(2))
                    }
                }
            }
        } finally { instrumentation.runOnMainSync { host.finish() } }
    }
    /** Inspect the emitted PNGs; numerical parity alone cannot establish visual semantics. */
    @Test fun exportVisualPropertyFixtures() {
        val directory=directory("f2-visual")
        val base=fixture(false);val layer=base.composition.layers[0]
        val normal=layer.text.copy(source=AnimatableString(staticValue="office AV gy A\u0301 B\n\nC"),
            size=scalar(72f),tracking=scalar(0f),ligatures=scalar(1f),strokeWidth=scalar(0f),animators=FrozenList.empty())
        val cases=ArrayList<Pair<String,TextProperties>>()
        fun animate(name: String,group: Int=0,vararg overrides: TextPropertyOverride) {
            cases.add(name to normal.copy(animators=FrozenList.of(listOf(TextAnimator(1,anchorGrouping=scalar(group.toFloat()),
                selectors=FrozenList.of(listOf(TextRangeSelector(2))),properties=FrozenList.of(overrides.toList()))))))
        }
        animate("anchor-only",0,TextPropertyOverride(AnimatorProperty.ANCHOR,vector=AnimatableProperty2D(true,0f,0f,
            FrozenList.of(listOf(Keyframe2D(0,0f,0f,EasingPreset.LINEAR),Keyframe2D(10_000_000,30f,20f,EasingPreset.LINEAR))))))
        for(group in 0..3) animate("pivot-$group",group,TextPropertyOverride(AnimatorProperty.ROTATION,scalar=track(0f,30f)))
        for(anchor in listOf(0f,50f,100f)) animate("line-anchor-${anchor.toInt()}",0,
            TextPropertyOverride(AnimatorProperty.TRACKING,scalar=track(0f,200f)),TextPropertyOverride(AnimatorProperty.LINE_ANCHOR,scalar=scalar(anchor)))
        animate("skew-clamp",0,TextPropertyOverride(AnimatorProperty.SKEW,scalar=track(-90f,90f)),TextPropertyOverride(AnimatorProperty.SKEW_AXIS,scalar=track(0f,90f)))
        animate("blur-budget",0,TextPropertyOverride(AnimatorProperty.BLUR,scalar=track(0f,20f)),TextPropertyOverride(AnimatorProperty.STROKE_WIDTH,scalar=track(0f,20f)))
        cases.add("tracking-zero-pop" to normal.copy(tracking=track(-100f,100f)))
        cases.add("ligatures-off" to normal.copy(ligatures=scalar(0f)))
        for(shape in SelectorShape.entries) {
            val selector=TextRangeSelector(2,shape=scalar(shape.ordinal.toFloat()),offset=track(-10f,10f),randomizeOrder=scalar(1f),seed=0x12345678)
            val properties=FrozenList.of(listOf(
                TextPropertyOverride(AnimatorProperty.POSITION,vector=AnimatableProperty2D(staticX=0f,staticY=-30f)),
                TextPropertyOverride(AnimatorProperty.FILL,colour=AnimatableColour(staticValue=0x80ff0000.toInt())),
                TextPropertyOverride(AnimatorProperty.STROKE_COLOUR,colour=AnimatableColour(staticValue=0x800000ff.toInt())),
                TextPropertyOverride(AnimatorProperty.STROKE_WIDTH,scalar=scalar(10f))))
            val animator=TextAnimator(1,selectors=FrozenList.of(listOf(selector)),properties=properties)
            cases.add("shape-${shape.name.lowercase()}" to normal.copy(animators=FrozenList.of(listOf(animator))))
        }
        for(size in listOf(8f,72f,500f)) for(order in 0..1) cases.add("stroke-${size.toInt()}-order-$order" to normal.copy(
            size=scalar(size),tracking=scalar(-200f),strokeWidth=scalar(100f),strokeOverFill=scalar(order.toFloat()),
            fill=AnimatableColour(staticValue=0x80ff0000.toInt()),strokeColour=AnimatableColour(staticValue=0x800000ff.toInt())))
        CompositionRenderer(context,MediaRepository(context)).use { renderer ->
            val pixels=ByteBuffer.allocateDirect(1080*1920*4)
            val bitmap=android.graphics.Bitmap.createBitmap(1080,1920,android.graphics.Bitmap.Config.ARGB_8888)
            try {
                for((name,text) in cases) {
                    val project=base.copy(composition=base.composition.copy(layers=FrozenList.of(listOf(layer.copy(text=text)))))
                    for(time in longArrayOf(0,5_000_000,10_000_000)) {
                        renderer.render(project,time);pixels.clear();assertTrue(TextDebugBridge.pixels(renderer.session,pixels));pixels.rewind()
                        bitmap.copyPixelsFromBuffer(pixels)
                        File(directory,"$name-$time.png").outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
                    }
                }
            } finally { bitmap.recycle() }
        }
        File(directory,"README.txt").writeText("Inspect centred round strokes, per-glyph overlap, anchor-only motion, post-spacing grouping, 0/50/100 line anchors, bounded blur/skew and intentional tracking-zero ligature pops. All PNGs use the production renderer; no UI controls are added.")
    }
}
