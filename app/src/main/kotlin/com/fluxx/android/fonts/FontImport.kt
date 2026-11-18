package com.fluxx.android.fonts

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the Fonts page shows about the last import; owned by the repository so it outlives the page. */
sealed interface FontImportStatus {
    data object Idle : FontImportStatus
    data object Importing : FontImportStatus
    data class Imported(val family: String, val faces: Int) : FontImportStatus
    data class Failed(val message: String) : FontImportStatus
}

/** The isolated worker did not answer within [FontImportErrors.WORKER_TIMEOUT_MS]. */
class FontWorkerTimeoutException(message: String) : IOException(message)

internal object FontImportErrors {
    /** Per worker call (inspect / prepare). After it the call is cancelled and its binding released. */
    const val WORKER_TIMEOUT_MS = 60_000L
    val TIMEOUT_MESSAGE = "Font worker did not respond within ${WORKER_TIMEOUT_MS / 1000} s; the font was not imported"

    /** Every failure class (including Errors such as UnsatisfiedLinkError) becomes a visible one-line message. */
    fun message(failure: Throwable): String = when (failure) {
        is FontWorkerTimeoutException, is TimeoutCancellationException -> TIMEOUT_MESSAGE
        is UnsatisfiedLinkError -> "Font engine unavailable (${failure.message ?: "native library"}); the font was not imported"
        is OutOfMemoryError -> "Not enough memory to import this font"
        else -> failure.message?.takeIf { it.isNotBlank() }?.take(300) ?: "Font import failed (${failure.javaClass.simpleName})"
    }
}

/** Pure index arithmetic shared by import and delete (JVM tested). */
internal object FontLibraryIndex {
    /** Adds (or extends) the entry for [hash]; a re-import after delete produces the family again. */
    fun add(entries: List<FontLibraryEntry>, hash: String, bytes: Long, selected: List<FontFaceMetadata>): Pair<FontLibraryEntry, List<FontLibraryEntry>> {
        val existing = entries.find { it.sha256 == hash }
        val entry = FontLibraryEntry(hash, bytes, (existing?.faces.orEmpty() + selected).distinctBy { it.index }.sortedBy { it.index })
        return entry to (entries.filterNot { it.sha256 == hash } + entry)
    }
    fun remove(entries: List<FontLibraryEntry>, hash: String) = entries.filterNot { it.sha256 == hash }
    fun families(entry: FontLibraryEntry) = entry.faces.map { it.family }.distinct().joinToString(", ")
}

/**
 * Runs imports in a scope that outlives the Fonts page (the repository's own scope), so leaving the page, the picker
 * returning after activity recreation or a recomposition can never cancel an import mid-flight or lose its result.
 * The outcome is published through [status]; a stuck worker ends as [FontImportStatus.Failed] after the worker timeout.
 */
internal class FontImportRunner<T>(private val scope: CoroutineScope, private val importer: suspend (T) -> FontLibraryEntry) {
    private val mutable = MutableStateFlow<FontImportStatus>(FontImportStatus.Idle)
    val status: StateFlow<FontImportStatus> = mutable.asStateFlow()
    private var job: Job? = null

    /** Starts one import; a second request while one is running is ignored (the import button is disabled). */
    @Synchronized fun start(input: T): Job? {
        if (job?.isActive == true) return null
        mutable.value = FontImportStatus.Importing
        return scope.launch {
            mutable.value = try {
                val entry = importer(input)
                FontImportStatus.Imported(FontLibraryIndex.families(entry), entry.faces.size)
            } catch (failure: Throwable) {
                if (failure is CancellationException && failure !is TimeoutCancellationException && !isActive) {
                    mutable.value = FontImportStatus.Failed("Font import was cancelled"); throw failure
                }
                FontImportStatus.Failed(FontImportErrors.message(failure))
            }
        }.also { job = it }
    }
    /** Clears a shown result (tap on the line, the confirmation timing out, or the next action). */
    fun dismiss() { if (mutable.value !is FontImportStatus.Importing) mutable.value = FontImportStatus.Idle }
}
