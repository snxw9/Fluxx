package com.fluxx.android.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Android's real AtomicFile and org.json; no alternate JSON or file implementation in tests. */
@RunWith(AndroidJUnit4::class)
class PaletteRepositoryTest {
    private fun temporaryFile(): File = File.createTempFile("palette_test_",".json",
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir).also { check(it.delete()) }
    private fun clean(file: File) { file.delete(); File(file.path+".bak").delete(); File(file.path+".new").delete() }

    @Test fun codecPreservesAlphaEmptyPalettesAndRepairsUnknownActiveId() {
        val library=PaletteLibrary(listOf(ColorPalette("a",null,emptyList()),
            ColorPalette("b","Colours",listOf(FluxxColor.fromArgb(0x00336699),FluxxColor.fromArgb(0x80336699.toInt())))),"b")
        assertEquals(library,PaletteCodec.decode(PaletteCodec.encode(library)))
        val invalidActive=library.copy(activePaletteId="missing")
        assertEquals("a",PaletteCodec.decode(PaletteCodec.encode(invalidActive)).activePaletteId)
    }

    @Test fun concurrentAddsDoNotLoseSwatchesAndCapNeverOverwrites() = runBlocking {
        val file=temporaryFile()
        try {
            val repo=PaletteRepository.forFile(file)
            repo.load()
            assertEquals(17,repo.state.value.activePalette!!.swatches.size)
            val palette=repo.createPalette("Work")
            coroutineScope {
                (0 until 24).map { i -> async { repo.addSwatch(palette.id,FluxxColor.fromArgb(0xFF000000.toInt() or i)) } }.awaitAll()
            }
            val saved=repo.state.value
            assertEquals(24,saved.activePalette!!.swatches.size)
            assertEquals((0 until 24).toSet(),saved.activePalette!!.swatches.map { it.toArgb() and 255 }.toSet())
            repo.addSwatch(palette.id,FluxxColor(1f,1f,1f))
            assertEquals(saved,repo.state.value)
            repo.load() // Idempotent load cannot replace newer in-memory state.
            assertEquals(saved,repo.state.value)
            val reopened=PaletteRepository.forFile(file)
            reopened.load()
            assertEquals(saved,reopened.state.value)
        } finally { clean(file) }
    }

    @Test fun renameReplaceRemoveAndDeleteSurviveReopen() = runBlocking {
        val file=temporaryFile()
        try {
            val repo=PaletteRepository.forFile(file)
            repo.load()
            val initial=repo.state.value
            val palette=repo.createPalette("  Custom  ")
            repo.renamePalette(palette.id,"   ")
            repo.addSwatch(palette.id,FluxxColor(1f,0f,0f))
            val alphaColor=FluxxColor.fromArgb(0x40223344)
            repo.replaceSwatch(palette.id,0,alphaColor)
            val reopened=PaletteRepository.forFile(file)
            reopened.load()
            assertEquals("Untitled",reopened.state.value.activePalette!!.displayName)
            assertEquals(alphaColor,reopened.state.value.activePalette!!.swatches.single())
            repo.removeSwatch(palette.id,0)
            assertTrue(repo.state.value.activePalette!!.swatches.isEmpty())
            repo.deletePalette(palette.id)
            assertEquals(initial,repo.state.value)
            repo.deletePalette(initial.activePaletteId!!)
            assertEquals(initial,repo.state.value)
        } finally { clean(file) }
    }

    @Test fun corruptFileIsNotSilentlyOverwritten() = runBlocking {
        val file=temporaryFile()
        try {
            file.writeText("{broken")
            val repo=PaletteRepository.forFile(file)
            val result=runCatching { repo.load() }
            assertTrue(result.isFailure)
            assertEquals("{broken",file.readText())
            assertTrue(repo.state.value.palettes.isEmpty())
        } finally { clean(file) }
    }

    @Test fun acceptedSaveOutlivesCallerCancellation() = runBlocking {
        val file=temporaryFile()
        try {
            val repo=PaletteRepository.forFile(file)
            val palette=repo.createPalette()
            val caller=launch(start=CoroutineStart.UNDISPATCHED) { repo.addSwatch(palette.id,FluxxColor(1f,0f,0f)) }
            caller.cancelAndJoin()
            withTimeout(5_000) { repo.state.first { it.activePalette?.swatches?.size==1 } }
            val reopened=PaletteRepository.forFile(file)
            reopened.load()
            assertEquals(1,reopened.state.value.activePalette!!.swatches.size)
        } finally { clean(file) }
    }
}
