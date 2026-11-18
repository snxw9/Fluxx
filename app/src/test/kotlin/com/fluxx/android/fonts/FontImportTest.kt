package com.fluxx.android.fonts

import com.fluxx.android.ui.inspector.FontFamilyItem
import com.fluxx.android.ui.inspector.FontStatusLine
import com.fluxx.android.ui.inspector.TextFontsModel
import com.fluxx.android.ui.inspector.TextInspectorLifecycle
import com.fluxx.android.ui.inspector.TextInspectorMode
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** TEXT FIX 9a: the import regression (picker result dropped on ON_STOP) and always-visible import outcomes. */
class FontImportTest {
    private val staticHash = "a".repeat(64); private val variableHash = "b".repeat(64); private val ttcHash = "c".repeat(64)
    private fun face(index: Int = 0, family: String = "Playfair Display", style: String = "Regular") =
        FontFaceMetadata(index, family, style, family.replace(" ", "") + "-" + style, 400, 5, 0f, emptyList(), emptyList())
    // Fixtures in the inspector's metadata shape: static, variable (wght axis + named instances), TTC (two faces).
    private val static = listOf(face())
    private val variable = listOf(face().copy(postScriptName = "PlayfairDisplay-Var",
        axes = listOf(FontAxisMetadata("wght", "Weight", 400f, 400f, 900f)),
        instances = listOf(FontNamedInstance(0, "Regular", listOf(400f)), FontNamedInstance(1, "Black", listOf(900f)))))
    private val ttc = listOf(face(0, "Noto Sans CJK", "Regular"), face(1, "Noto Sans Mono CJK", "Regular"))
    private fun src(path: String) = File("src/main/kotlin/com/fluxx/android/$path").readText()

    @Test fun thePickerNoLongerClosesTheFontsPage() {
        // Root cause: ON_STOP (the system picker stops the activity) reset the inspector to Browse, disposing the
        // Fonts page and with it the picker launcher, so the chosen file was dropped without any error.
        assertEquals(TextInspectorMode.Fonts, TextInspectorLifecycle.afterStop(TextInspectorMode.Fonts))
        assertEquals(TextInspectorMode.Browse, TextInspectorLifecycle.afterStop(TextInspectorMode.Source))
        assertEquals(TextInspectorMode.Browse, TextInspectorLifecycle.afterStop(TextInspectorMode.Browse))
        val panel = src("ui/inspector/TextInspectorPanel.kt")
        assertTrue(panel.contains("mode=TextInspectorLifecycle.afterStop(mode)"))
        assertFalse(panel.contains("axisSnapshot=null;manager.clearFocus();mode=TextInspectorMode.Browse\n    }"))
        // The page hands the URI to the repository; no composition scope owns the import.
        val page = src("ui/inspector/TextFontsPopup.kt")
        assertTrue(page.contains("repository.startImport(uri)"))
        assertFalse(page.contains("repository.import(uri)"))
    }
    @Test fun errorsAreShownFirstAndNeverHiddenByTheMissingFontLine() {
        val missing = "Requested Playfair Display Regular missing · Inter fallback · identity retained"
        val failed = FontImportStatus.Failed("Font files must be at most 16 MiB")
        // The old ordered `when` showed only the missing line here; now the error comes first and both show.
        val lines = TextFontsModel.statusLines(null, failed, missing)
        assertEquals(listOf(FontStatusLine.Kind.ERROR, FontStatusLine.Kind.MISSING), lines.map { it.kind })
        assertEquals("Font files must be at most 16 MiB", lines.first().text)
        val both = TextFontsModel.statusLines("Cannot create the private font library", failed, missing)
        assertEquals(listOf("Cannot create the private font library", failed.message, missing), both.map { it.text })
        assertTrue(both.filter { it.kind == FontStatusLine.Kind.ERROR }.all { it.dismissible })
        // Progress and confirmation.
        assertEquals("Importing font…", TextFontsModel.statusLines(null, FontImportStatus.Importing, null).single().text)
        assertEquals("Imported Playfair Display (1 face)", TextFontsModel.statusLines(null, FontImportStatus.Imported("Playfair Display", 1), null).single().text)
        assertEquals("Imported A, B (2 faces)", TextFontsModel.statusLines(null, FontImportStatus.Imported("A, B", 2), null).single().text)
        assertTrue(TextFontsModel.statusLines(null, FontImportStatus.Idle, null).isEmpty())
        assertEquals(4_000L, TextFontsModel.CONFIRMATION_MS)
    }
    @Test fun aWorkerThatNeverAnswersEndsAsAVisibleTimeout() = runBlocking {
        assertEquals(60_000L, FontImportErrors.WORKER_TIMEOUT_MS)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // withTimeout inside the importer (as FontWorkerClient.bounded) and the mapped exception both surface.
        val runner = FontImportRunner<Unit>(scope) { withTimeout(20) { awaitCancellation() } }
        runner.start(Unit)!!.join()
        assertEquals(FontImportStatus.Failed(FontImportErrors.TIMEOUT_MESSAGE), runner.status.value)
        assertTrue(FontImportErrors.TIMEOUT_MESSAGE.startsWith("Font worker did not respond"))
        val mapped = FontImportRunner<Unit>(scope) { throw FontWorkerTimeoutException(FontImportErrors.TIMEOUT_MESSAGE) }
        mapped.start(Unit)!!.join()
        assertEquals(FontImportStatus.Failed(FontImportErrors.TIMEOUT_MESSAGE), mapped.status.value)
        assertTrue(src("fonts/FontWorkerClient.kt").contains("withTimeout(FontImportErrors.WORKER_TIMEOUT_MS)"))
        scope.cancel()
    }
    @Test fun nonExceptionFailuresAreReported() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        for ((failure, expected) in listOf<Pair<Throwable, String>>(
            UnsatisfiedLinkError("libfluxxengine.so not found") to "Font engine unavailable (libfluxxengine.so not found); the font was not imported",
            OutOfMemoryError() to "Not enough memory to import this font",
            IllegalStateException() to "Font import failed (IllegalStateException)",
            IllegalArgumentException("The font file is empty") to "The font file is empty")) {
            val runner = FontImportRunner<Unit>(scope) { throw failure }
            runner.start(Unit)!!.join()
            assertEquals(FontImportStatus.Failed(expected), runner.status.value)
        }
        scope.cancel()
    }
    @Test fun theImportSurvivesTheCompositionLeaving() = runBlocking {
        val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = CompletableDeferred<Unit>()
        val runner = FontImportRunner<String>(repositoryScope) { gate.await(); FontLibraryEntry(variableHash, 1000, variable) }
        // The picker callback runs while the page is composed; then the page (and its scope) goes away mid-import.
        val composition = CoroutineScope(Job())
        composition.launch { runner.start("content://font") }.join()
        assertEquals(FontImportStatus.Importing, runner.status.value)
        assertNull("a second request while importing is ignored", runner.start("content://other"))
        composition.cancel()
        gate.complete(Unit)
        withTimeout(5_000) { while (runner.status.value == FontImportStatus.Importing) delay(5) }
        assertEquals(FontImportStatus.Imported("Playfair Display", 1), runner.status.value)
        runner.dismiss(); assertEquals(FontImportStatus.Idle, runner.status.value)
        repositoryScope.cancel()
    }
    @Test fun deleteThenReimportOfTheSameFileShowsTheFamilyAgain() {
        var entries = emptyList<FontLibraryEntry>()
        entries = FontLibraryIndex.add(entries, staticHash, 1000, static).second
        assertTrue(TextFontsModel.families(entries, "").any { it is FontFamilyItem.Imported && it.name == "Playfair Display" })
        entries = FontLibraryIndex.remove(entries, staticHash)
        assertTrue(TextFontsModel.families(entries, "playfair").isEmpty())
        // Same bytes, same sha256: the family comes back and search finds it.
        val (entry, next) = FontLibraryIndex.add(entries, staticHash, 1000, static)
        assertEquals(staticHash, entry.sha256)
        assertEquals(listOf("Playfair Display"), TextFontsModel.families(next, "playfair").map { it.name })
    }
    @Test fun staticVariableAndCollectionFixturesImportIntoTheList() {
        var entries = emptyList<FontLibraryEntry>()
        entries = FontLibraryIndex.add(entries, staticHash, 1000, static).second
        val (v, withVariable) = FontLibraryIndex.add(entries, variableHash, 2000, variable); entries = withVariable
        assertEquals(listOf("wght"), v.faces.single().axes.map { it.tag }); assertEquals(2, v.faces.single().instances.size)
        val (t, all) = FontLibraryIndex.add(entries, ttcHash, 3000, ttc.take(1)); entries = all
        assertEquals("Noto Sans CJK", FontLibraryIndex.families(t))
        // Importing another face of the same collection extends the entry instead of duplicating it.
        val (extended, merged) = FontLibraryIndex.add(entries, ttcHash, 3000, ttc.drop(1))
        assertEquals(listOf(0, 1), extended.faces.map { it.index }); assertEquals(3, merged.size)
        assertEquals("Noto Sans CJK, Noto Sans Mono CJK", FontLibraryIndex.families(extended))
        // Static and variable Playfair share one family row (two files); the TTC gives two family rows.
        val imported = TextFontsModel.families(merged, "").filterIsInstance<FontFamilyItem.Imported>()
        assertEquals(listOf("Noto Sans CJK", "Noto Sans Mono CJK", "Playfair Display"), imported.map { it.name })
        assertEquals(2, imported.last().faces.size)
    }
}
