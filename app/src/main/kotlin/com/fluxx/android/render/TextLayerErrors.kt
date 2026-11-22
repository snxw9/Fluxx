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

    /**
     * TEXT F3.1: layers whose Character offset / value animation could not be prepared, by layer id. The preview keeps
     * drawing them without that animation; the inspector shows the message. Replaced on every project preparation.
     */
    private val mutableCharacters = MutableStateFlow<Map<Long, String>>(emptyMap())
    val characters = mutableCharacters.asStateFlow()
    internal fun characters(errors: Map<Long, String>) { mutableCharacters.value = errors.toMap() }
}
