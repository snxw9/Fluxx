package com.fluxx.android.render

import com.fluxx.android.model.ProjectDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TextLayerErrorState(val project: ProjectDocument, val layerIds: Set<Long>, val message: String)
/** Preview error publication uses its existing renderer worker; export failures propagate to the export job. */
object TextLayerErrors {
    private val mutableState = MutableStateFlow<TextLayerErrorState?>(null)
    val state = mutableState.asStateFlow()
    internal fun capacity(project: ProjectDocument, ids: Set<Long>, message: String) {
        mutableState.value = TextLayerErrorState(project, ids, message)
    }
    internal fun clear() { mutableState.value = null }
}
