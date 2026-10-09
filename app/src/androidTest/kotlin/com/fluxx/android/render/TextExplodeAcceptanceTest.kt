package com.fluxx.android.render

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.editor.*
import com.fluxx.android.engine.TextDebugBridge
import com.fluxx.android.media.MediaRepository
import com.fluxx.android.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/** Source-only fixtures. Pixel equality is intentionally strict and remains device-unverified. */
@RunWith(AndroidJUnit4::class)
class TextExplodeAcceptanceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun joiningPropertyKeepsArabicWordsButNotHebrewIndivisible() {
        val text="سلام שָׁלוֹם Latin"
        val words=TextJoiningPreparation.words(TextSourceDocument(text))
        assertEquals(listOf(TextSelectionRange(0,4)),words)
    }
    private fun source(value: String,width: Float=0f): CompositionLayer {
        val doc=TextSourceDocument(value,boxWidth=width)
        return CompositionLayer(1,LayerType.TEXT,timing=ClipTiming(durationUs=2_000_000),
            text=TextProperties(source=AnimatableString(staticValue=value,document=doc),size=AnimatableProperty1D(staticValue=32f),fontId="fluxx.serif"),
            animTransform=AnimatableTransform(rotation=AnimatableProperty1D(true,keyframes=FrozenList.of(listOf(
                Keyframe1D(0,-20f),Keyframe1D(2_000_000,35f)))),scale=AnimatableProperty2D(true,staticX=1f,staticY=1f,
                keyframes=FrozenList.of(listOf(Keyframe2D(0,.8f,1.2f),Keyframe2D(2_000_000,1.2f,.8f))))))
    }
    private suspend fun checkPixels(layer: CompositionLayer,split: TextSplit) {
        val comp=Composition(width=320,height=240,durationUs=2_000_000,layers=FrozenList.of(listOf(layer)))
        val metrics=TextMetricsRepository.get(context)
        val plan=TextExplode.prepare(layer,comp,500_000,2,TextExplodeOptions(split=split)) {metrics.geometry(it,500_000)}
        val exploded=EditorReducer.reduce(EditorState(ProjectDocument(comp)),EditorAction.ExplodeText(plan)).project
        val reference=if(split==TextSplit.CHARACTERS) layer.copy(text=layer.text.copy(ligatures=AnimatableProperty1D(staticValue=0f))) else layer
        val original=ProjectDocument(comp.copy(layers=FrozenList.of(listOf(reference))))
        CompositionRenderer(context,MediaRepository(context)).use {before->
            CompositionRenderer(context,MediaRepository(context)).use {after->
                val a=ByteBuffer.allocateDirect(320*240*4);val b=ByteBuffer.allocateDirect(320*240*4)
                for(time in longArrayOf(0,500_000,1_500_000)) {
                    before.render(original,time);after.render(exploded,time)
                    a.clear();b.clear();assertTrue(TextDebugBridge.pixels(before.session,a));assertTrue(TextDebugBridge.pixels(after.session,b))
                    a.rewind();b.rewind();assertEquals("$split pre-encode pixels at $time",a,b)
                }
            }
        }
        assertEquals(exploded,ProjectCodec.decode(ProjectCodec.encode(exploded)))
    }
    @Test fun nonLigatingTextAndAnimatedGroupPivotsArePixelEqual()=runBlocking(Dispatchers.Default) {
        for(split in TextSplit.entries) checkPixels(source("AB CD"),split)
    }
    @Test fun charactersCompareAgainstWholeLayerWithLigaturesOff()=runBlocking(Dispatchers.Default) {
        checkPixels(source("office a\u0301"),TextSplit.CHARACTERS)
    }
    @Test fun wrappedAndJustifiedLinesKeepTheirBoxAndExpansion()=runBlocking(Dispatchers.Default) {
        val layer=source("AB CD EF GH",120f)
        val doc=layer.text.source.sourceDocument.formatParagraphs(0,0) {it.copy(alignment=ParagraphAlignment.JUSTIFY_LAST_RIGHT)}
        val styled=layer.copy(text=layer.text.copy(source=AnimatableString(staticValue=doc.text,document=doc)))
        for(split in listOf(TextSplit.LINES,TextSplit.PARAGRAPHS)) checkPixels(styled,split)
    }
}
