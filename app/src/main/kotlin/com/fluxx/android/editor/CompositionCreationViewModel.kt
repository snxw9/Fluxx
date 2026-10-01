package com.fluxx.android.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxx.android.ProjectManager
import com.fluxx.android.ProjectMetadata
import com.fluxx.android.model.CompositionDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class CompositionCreationViewModel(private val saved: SavedStateHandle) : ViewModel() {
    var visible by mutableStateOf(saved.get<Boolean>("visible") ?: false)
        private set
    var draft by mutableStateOf(restore())
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var createdFile by mutableStateOf(saved.get<String>("createdFile"))
        private set
    var openAttempt by mutableStateOf(0)
        private set

    fun open(projects: List<ProjectMetadata>) {
        if (visible || busy) return
        if (createdFile != null) {
            visible = true; saved["visible"] = true
            return
        }
        val next = (projects.mapNotNull { Regex("Project (\\d+)").matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0) + 1
        update(CompositionDraft(name = "Project $next"))
        visible = true; saved["visible"] = true; error = null
    }
    fun update(value: CompositionDraft) {
        if (createdFile != null) { createdFile = null; saved["createdFile"] = null }
        draft = value; error = null
        saved["draft"] = arrayListOf(value.name, value.width, value.height, value.duration,
            value.fps.toString(), value.backgroundArgb.toString(), value.aspect, value.resolution.toString(),
            value.pencil.toString(), value.tab.toString(), value.customPreset.orEmpty())
    }
    fun dismiss() { if (!busy) { visible = false; saved["visible"] = false } }
    fun create(manager: ProjectManager) {
        if (busy) return
        if (createdFile != null) { openAttempt++; return }
        busy = true; error = null
        val snapshot = draft
        viewModelScope.launch {
            try {
                val file = manager.createProject(snapshot.copy(duration = "").request()).getOrThrow()
                createdFile = file.absolutePath; saved["createdFile"] = createdFile
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not create project" }
            finally { busy = false }
        }
    }
    fun opened() {
        createdFile = null; saved["createdFile"] = null
        visible = false; saved["visible"] = false
    }
    fun openFailed(message: String) { error = message }
    private fun restore(): CompositionDraft {
        val values = saved.get<ArrayList<String>>("draft") ?: return CompositionDraft()
        return runCatching { CompositionDraft(values[0], values[1], values[2], values[3], values[4].toInt(),
            values[5].toInt(), values[6], values[7].toInt(), values[8].toBoolean(), values[9].toInt(),
            values[10].ifEmpty { null }) }.getOrDefault(CompositionDraft())
    }
}
