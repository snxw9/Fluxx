package com.fluxx.android.editor

import android.util.Log
import com.fluxx.android.ProjectManager
import com.fluxx.android.model.ProjectDocument
import java.io.File
import kotlinx.coroutines.*

/** Orders immutable save requests across Activity recreation. No draft or second writer. */
internal object ProjectSaveQueue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tail: Deferred<Result<Unit>>? = null

    @Synchronized
    fun enqueue(manager: ProjectManager, file: File, snapshot: ProjectDocument): Deferred<Result<Unit>> {
        val previous = tail
        return scope.async {
            previous?.join()
            // The manager owns serialization, locking and atomic replacement.
            val result = try { manager.saveProject(file, snapshot) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { Result.failure(failure) }
            result.onFailure { Log.e("Fluxx", "Project save failed: ${file.name}", it) }
            result
        }.also { tail = it }
    }

    /** A newly opened project must not race an earlier background save. */
    suspend fun awaitPending() {
        val pending = synchronized(this) { tail }
        pending?.join()
    }
}
