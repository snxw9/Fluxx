package com.fluxx.android.fonts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxx.android.engine.IsolatedFontParser
import com.fluxx.android.engine.PreparedTextData
import com.fluxx.android.model.*
import android.net.Uri
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Source-only acceptance. Running this is reserved for the user's device checks. */
@RunWith(AndroidJUnit4::class)
class FontImportAcceptanceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun importedBytesAreRejectedInApplicationProcess() {
        try { IsolatedFontParser.inspect(byteArrayOf(0,1,0,0));fail("The app process must not parse imported fonts") }
        catch(_: IllegalStateException) {}
    }
    @Test fun isolatedInspectionAndCrashRecovery()=runBlocking {
        val font=File.createTempFile("font-worker-proof-",".ttf",context.cacheDir)
        try {
            context.assets.open("fonts/Inter-Regular.ttf").use { input -> font.outputStream().use { input.copyTo(it) } }
            val client=FontWorkerClient(context)
            assertTrue(FontMetadataCodec.inspect(client.inspect(font)).single().family.isNotEmpty())
            try { client.crashForTest(font);fail("The isolated crash must be reported") }
            catch(e: IOException) { assertTrue(e.message.orEmpty().contains("isolated font parser")) }
            assertTrue(FontMetadataCodec.inspect(client.inspect(font)).isNotEmpty())
            font.writeBytes(byteArrayOf(0,1,2,3))
            try { client.inspect(font);fail("Malformed font accepted") }
            catch(e: IOException) { assertTrue(e.message.orEmpty().isNotEmpty()) }
        } finally { font.delete() }
    }
    @Test fun libraryMetadataRoundTripsWithoutRewritingRequestedAxes() {
        val axis=FontAxisMetadata("wght","Weight",100f,400f,900f)
        val face=FontFaceMetadata(1,"Example","Regular","Example-Regular",400,5,0f,
            listOf(axis),listOf(FontNamedInstance(0,"Bold",listOf(700f))))
        val entry=FontLibraryEntry("ab".repeat(32),1234,listOf(face))
        assertEquals(listOf(entry),FontMetadataCodec.decode(FontMetadataCodec.encode(listOf(entry))))
        assertEquals(700f,face.reference(entry.sha256,0).axes.single().value.staticValue,0f)
    }
    @Test fun importedVariableCffPreparesBothModesAndSurvivesMissingFont()=runBlocking {
        withContext(Dispatchers.IO) {
            val font=File.createTempFile("font-variable-proof-",".otf",context.cacheDir)
            val library=FontLibraryRepository.getInstance(context)
            var hash: String?=null
            var added=false
            try {
                InstrumentationRegistry.getInstrumentation().context.assets.open("SourceSerif4Variable-Roman.otf").use { input -> font.outputStream().use { input.copyTo(it) } }
                library.load()
                val digest=java.security.MessageDigest.getInstance("SHA-256").digest(font.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
                val previous=library.state.value.find { it.sha256==digest }
                val entry=previous ?: library.import(Uri.fromFile(font)).also { added=true };hash=entry.sha256
                val face=entry.faces.single();assertTrue(face.axes.any { it.tag=="wght" })
                val low=face.reference(entry.sha256,requested=mapOf("wght" to 200f,"opsz" to 20f))
                val high=face.reference(entry.sha256,requested=mapOf("wght" to 900f,"opsz" to 20f))
                fun doc(ref: TextFontRef)=TextSourceDocument("mmm office",FrozenList.of(listOf(TextStyleRun(0,10,TextRunStyle(font=ref)))))
                val lowPacket=ImportedTextPreparation.prepare(context,"fluxx.sans",doc(low),true).packet
                val highDocument=doc(high)
                val highPacket=ImportedTextPreparation.prepare(context,"fluxx.sans",highDocument,true).packet
                assertFalse(PreparedTextData.metrics(lowPacket).contentEquals(PreparedTextData.metrics(highPacket)))
                assertTrue(PreparedTextData.preview(highPacket,240,48,0xff000000.toInt()).any { it ushr 24>0 })
                assertTrue(ImportedTextPreparation.prepare(context,"fluxx.sans",highDocument,false).missing.isEmpty())
                if(face.instances.isNotEmpty()) assertTrue(ImportedTextPreparation.prepare(context,"fluxx.sans",doc(face.reference(entry.sha256,face.instances.first().index)),true).missing.isEmpty())
                if(added) library.delete(entry.sha256)
                val missing=if(added) high else high.copy(sha256="00".repeat(32))
                val missingDocument=doc(missing)
                assertEquals(setOf(missing),ImportedTextPreparation.prepare(context,"fluxx.sans",missingDocument,true).missing)
                assertEquals(missing,missingDocument.runs.single().style.font)
            } finally { if(added) hash?.let { library.delete(it) };font.delete() }
        }
    }
}
