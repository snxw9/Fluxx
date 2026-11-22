package com.fluxx.android.render

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.editor.*
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * TEXT F3.1 crash regression on device: each of the 25 animator properties is added through the real reducer and the
 * layer is prepared and rendered by the production preview (surface) and export (offscreen) renderers. A Character
 * animation over the codepoint cap keeps the preview drawing (without it, error published); export fails explicitly.
 * Writes no files.
 */
@RunWith(AndroidJUnit4::class)
class AnimatorPropertyRenderTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private var serial=500L
    private val ids=TextAnimatorEdits.Ids { serial++ }

    private fun project(p: AnimatorProperty?,characterValue: Float?=null): ProjectDocument {
        val layer=CompositionLayer(1,LayerType.TEXT,timing=ClipTiming(durationUs=4_000_000),
            text=TextProperties(source=AnimatableString(staticValue="Animate me")))
        var state=EditorState(ProjectDocument(Composition(width=1080,height=1920,durationUs=4_000_000,layers=FrozenList.of(listOf(layer)))),1)
        fun text()=state.project.composition.layers[0].text
        state=EditorReducer.reduce(state,TextAnimatorEdits.action(1,listOf(TextAnimatorEdits.addAnimator(text(),ids))))
        val id=text().animators[0].id
        if(p!=null) state=EditorReducer.reduce(state,TextAnimatorEdits.action(1,listOfNotNull(TextAnimatorEdits.addProperty(text(),id,p))))
        if(p!=null && characterValue!=null)
            state=EditorReducer.reduce(state,TextAnimatorEdits.action(1,listOf(TextAnimatorEdits.set(text(),TextAnimatorEdits.property(id,p),0,characterValue))))
        return state.project
    }

    @Test fun everyPropertyPreparesAndRendersInPreviewAndExport() {
        val host=instrumentation.startActivitySync(Intent(context,TextProofActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TextProofActivity
        val surface=host.awaitSurface()
        val worker=Executors.newSingleThreadExecutor()
        try {
            worker.submit {
                CompositionRenderer(context,MediaRepository(context),surface,1080,1920).use { preview ->
                    CompositionRenderer(context,MediaRepository(context)).use { export ->
                        for(p in AnimatorProperty.entries) {
                            val project=project(p)
                            for(time in longArrayOf(0,1_000_000,3_999_999)) {
                                preview.renderPreview(project,time,PreviewResolution.FULL)
                                export.render(project,time)
                            }
                            assertNull(p.name,TextLayerErrors.characters.value[1L])
                        }
                    }
                }
            }.get(5,TimeUnit.MINUTES)
        } finally { worker.shutdownNow();instrumentation.runOnMainSync { host.finish() } }
    }

    @Test fun characterAnimationOverTheCapDegradesInPreviewAndFailsExportExplicitly() {
        val project=project(AnimatorProperty.CHARACTER_OFFSET,5_000f)
        assertNotNull(TextCharacterPreparation.problem(project.composition.layers[0].text))
        val host=instrumentation.startActivitySync(Intent(context,TextProofActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TextProofActivity
        val surface=host.awaitSurface()
        val worker=Executors.newSingleThreadExecutor()
        try {
            worker.submit {
                CompositionRenderer(context,MediaRepository(context),surface,1080,1920).use { preview ->
                    preview.renderPreview(project,0,PreviewResolution.FULL)
                    assertTrue(TextLayerErrors.characters.value[1L]!!.contains("4096"))
                    // A supported document clears the error on the next preparation.
                    preview.renderPreview(project(AnimatorProperty.CHARACTER_OFFSET,2f),0,PreviewResolution.FULL)
                    assertNull(TextLayerErrors.characters.value[1L])
                }
                CompositionRenderer(context,MediaRepository(context)).use { export ->
                    assertThrows(IllegalArgumentException::class.java) { export.render(project,0) }
                }
            }.get(2,TimeUnit.MINUTES)
        } finally { worker.shutdownNow();instrumentation.runOnMainSync { host.finish() } }
    }
}
