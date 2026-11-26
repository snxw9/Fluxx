package com.fluxx.android.ui.inspector

import androidx.compose.runtime.compositionLocalOf
import com.fluxx.android.editor.GraphTarget

/**
 * TLP.1 part 4: Copy keys / Paste keys live in the property options ⋮ of an animated property's page (the shared
 * [InspectorUtilityRail]), for the rail's own property. The editor provides the clipboard; the rail only asks whether
 * each item is enabled and forwards the tap. Not used by the Graph page (it keeps Copy / Paste graph, the curve only).
 */
internal class KeyClipboardHost(
    val canCopy: (GraphTarget) -> Boolean,
    val canPaste: (GraphTarget) -> Boolean,
    val copy: (GraphTarget) -> Unit,
    val paste: (GraphTarget) -> Unit,
)
internal val LocalKeyClipboardHost = compositionLocalOf<KeyClipboardHost?> { null }

/** The rail menu items for [target] (empty without a host or a target). */
internal fun keyClipboardItems(host: KeyClipboardHost?, target: GraphTarget?): List<RailMenuItem> {
    if (host == null || target == null) return emptyList()
    return listOf(
        RailMenuItem("Copy keys", enabled = host.canCopy(target)) { host.copy(target) },
        RailMenuItem("Paste keys", enabled = host.canPaste(target)) { host.paste(target) },
    )
}
