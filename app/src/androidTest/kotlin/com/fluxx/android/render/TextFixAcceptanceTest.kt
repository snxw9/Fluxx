package com.fluxx.android.render

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.engine.FontSession
import com.fluxx.android.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Source-only FIX fixtures. Compilation is not a device result. */
@RunWith(AndroidJUnit4::class)
class TextFixAcceptanceTest {
    @Test fun importedFacesUseSyntheticScriptForEveryCluster() = runBlocking(Dispatchers.IO) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val library=com.fluxx.android.fonts.FontLibraryRepository.getInstance(context);library.load()
        val entries=library.state.value
        require(entries.isNotEmpty()) {"Import a static and a variable face before running the imported-font FIX fixture"}
        for(entry in entries) for(face in entry.faces) for(script in listOf(TextScriptPosition.SUPER,TextScriptPosition.SUB)) {
            val reference=face.reference(entry.sha256)
            val document=TextSourceDocument("Text Hello 123!?").format(0,0) {it.copy(font=reference,script=script,fauxBold=true,fauxItalic=true)}
            val packet=com.fluxx.android.fonts.ImportedTextPreparation.prepare(context,"fluxx.sans",document,true).packet
            val values=com.fluxx.android.engine.PreparedTextData.metrics(packet)
            val prefix=6+values[4].toInt()*2+values[5].toInt()*8
            val clusters=values[prefix+1].toInt()
            for(c in 0 until clusters) assertEquals(.583,values[values.size-clusters+c],1e-9)
        }
    }
    @Test fun caseScriptAndWrappedVisualSelectionUseSharedPreparedMetrics() = runBlocking(Dispatchers.Default) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        FontSession(context.assets).use {fonts ->
            for(family in TextFontRef.BUNDLED_IDS) for(source in listOf("Hello World","Straße 123!?","Text","Hello","ffi a\u0301")) {
                for(caps in TextCaps.entries) for(script in TextScriptPosition.entries) {
                    val document=TextSourceDocument(source).format(0,0) {it.copy(caps=caps,script=script,fauxBold=true,fauxItalic=true)}.copy(boxWidth=120f)
                    val metrics=TextLayoutMetrics.fromNative(fonts.styledMetrics(family,StyledTextPreparation.encode(document,family),true),false,document)
                    val text=TextProperties(size=AnimatableProperty1D(staticValue=72f))
                    val geometry=requireNotNull(metrics.geometry(text,0));assertTrue(geometry.count>0)
                    for(c in geometry.starts.indices) {
                        assertTrue(document.boundaries.binarySearch(geometry.starts[c])>=0)
                        assertTrue(document.boundaries.binarySearch(geometry.ends[c])>=0)
                        assertTrue(geometry.boxes[c*4].isFinite())
                    }
                }
            }
        }
    }
}
