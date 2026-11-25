package com.fluxx.android.ui.editor

import android.content.res.Configuration
import androidx.compose.material3.Icon
import com.fluxx.android.ui.inspector.InspectorIcons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import com.fluxx.android.render.PreviewResolution
import com.fluxx.android.ui.preview.ViewSettings
import com.fluxx.android.model.CompositionLayer
import com.fluxx.android.model.LayerType
import com.fluxx.android.ui.theme.*

/**
 * Transport bar directly below preview per Phase 3 spec:
 * - 7 evenly spaced icon controls:
 *   1. Undo (enabled = canUndo)
 *   2. Redo (enabled = canRedo)
 *   3. Previous boundary
 *   4. Play / Pause (centered prominently)
 *   5. Next boundary
 *   6. Layer menu — selected-layer transforms/info plus clipboard actions
 *   7. View / Preview menu (resolution, overlays and zoom)
 * - Outline style at rest
 * - 20dp visible / 48dp wide, 40dp tall control
 * - White when active, #7A7A7A when inactive/disabled
 * - No gap / card wrapper
 */
internal val EditorActionBarHeight = 40.dp

@Composable
fun EditorActionBar(
    isPlaying: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    hasTimelineContent: Boolean = true,
    hasClipboard: Boolean = false,
    selectedLayer: CompositionLayer? = null,
    textCanFit: Boolean = false,
    onFlipHorizontal: () -> Unit = {},
    onFlipVertical: () -> Unit = {},
    onFitToWidth: () -> Unit = {},
    onFitToHeight: () -> Unit = {},
    onStretchToArea: () -> Unit = {},
    onMediaInfo: () -> Unit = {},

    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onPrevBoundary: () -> Unit,
    onPlayPauseToggle: () -> Unit,
    onNextBoundary: () -> Unit,
    onPasteLayer: () -> Unit = {},
    /** TLP T4: the 6th icon is context-aware (selection mode, multi-layer copy / duplicate; T5 keys via [keyMenu]). */
    selectionMode: Boolean = false,
    onSelectAll: () -> Unit = {},
    onInvertSelection: () -> Unit = {},
    onDuplicateSelection: (Int) -> Unit = {},
    onCopySelection: () -> Unit = {},
    keyMenu: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
    viewSettings: ViewSettings = ViewSettings(),
    onViewSettingsChange: (ViewSettings) -> Unit = {},
    previewResolution: PreviewResolution = PreviewResolution.FULL,
    onPreviewResolutionChange: (PreviewResolution) -> Unit = {},
    previewFitScale: Float = 1f,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Background,
        modifier = modifier
            .fillMaxWidth()
            .height(EditorActionBarHeight)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Undo
            ToolbarButton(
                enabled = canUndo,
                label = "Undo",
                onClick = onUndo
            ) { color ->
                UndoVectorIcon(color = color)
            }

            // 2. Redo
            ToolbarButton(
                enabled = canRedo,
                label = "Redo",
                onClick = onRedo
            ) { color ->
                RedoVectorIcon(color = color)
            }

            // 3. Jump to Previous boundary
            ToolbarButton(
                enabled = true,
                onClick = onPrevBoundary,
                label = "Previous contextual boundary"
            ) { color ->
                PrevBoundaryVectorIcon(color = color)
            }

            // 4. Play / Pause (centered)
            ToolbarButton(
                enabled = hasTimelineContent,
                onClick = onPlayPauseToggle,
                label = if (isPlaying) "Pause" else "Play"
            ) { color ->
                PlayPauseVectorIcon(isPlaying = isPlaying, color = color)
            }

            // 5. Jump to Next boundary
            ToolbarButton(
                enabled = true,
                onClick = onNextBoundary,
                label = "Next contextual boundary"
            ) { color ->
                NextBoundaryVectorIcon(color = color)
            }

            // 6. Reuse the layer menu for contextual and clipboard actions.
            LayerMenuButton(
                hasClipboard = hasClipboard,
                selectedLayer = selectedLayer,
                onFlipHorizontal = onFlipHorizontal, onFlipVertical = onFlipVertical,
                onFitToWidth = onFitToWidth, onFitToHeight = onFitToHeight,
                onStretchToArea = onStretchToArea, onMediaInfo = onMediaInfo,
                textCanFit = textCanFit,
                onPasteLayer = onPasteLayer,
                selectionMode = selectionMode, onSelectAll = onSelectAll, onInvertSelection = onInvertSelection,
                onDuplicateSelection = onDuplicateSelection, onCopySelection = onCopySelection, keyMenu = keyMenu
            )

            // 7. View / Preview. Resolution stays inside this menu to retain seven targets.
            ViewMenuButton(viewSettings, onViewSettingsChange, previewResolution, onPreviewResolutionChange, previewFitScale)
        }
    }
}

/**
 * 6th toolbar icon: the contextual Layer menu (TLP T4: context-aware).
 * - No selection: Select all, Paste.
 * - One layer selected (outside selection mode): its transforms / media info and deferred placeholders, then Paste, Select all.
 * - Layers selected (selection mode): Duplicate, Duplicate ×N (typed count), Copy, Paste, Select all, Invert selection.
 * - Keyframes selected (T5): [keyMenu] replaces the layer items.
 */
@Composable
private fun LayerMenuButton(
    hasClipboard: Boolean,
    selectedLayer: CompositionLayer? = null,
    onFlipHorizontal: () -> Unit = {},
    onFlipVertical: () -> Unit = {},
    onFitToWidth: () -> Unit = {},
    onFitToHeight: () -> Unit = {},
    onStretchToArea: () -> Unit = {},
    onMediaInfo: () -> Unit = {},
    onPasteLayer: () -> Unit,
    textCanFit: Boolean = false,
    selectionMode: Boolean = false,
    onSelectAll: () -> Unit = {},
    onInvertSelection: () -> Unit = {},
    onDuplicateSelection: (Int) -> Unit = {},
    onCopySelection: () -> Unit = {},
    keyMenu: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var duplicateCount by remember { mutableStateOf(false) }

    Box(Modifier.semantics { contentDescription = "Layer actions" }) {
        ToolbarButton(
            enabled = true,
            onClick = { duplicateCount = false; menuExpanded = true }
        ) { color ->
            LayerActionVectorIcon(color = color)
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false; duplicateCount = false }
        ) {
            val close = { menuExpanded = false; duplicateCount = false }
            if (keyMenu != null) { keyMenu(close); return@DropdownMenu }
            if (selectionMode) {
                DropdownMenuItem(text = { Text("Duplicate") }, onClick = { close(); onDuplicateSelection(1) },
                    modifier = Modifier.testTag("layer-menu-duplicate"))
                if (!duplicateCount) DropdownMenuItem(text = { Text("Duplicate ×N") }, onClick = { duplicateCount = true },
                    modifier = Modifier.testTag("layer-menu-duplicate-n"))
                else Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Copies", color = TextPrimary, modifier = Modifier.padding(end = 8.dp))
                    com.fluxx.android.ui.inspector.InspectorValueField("2", "Number of duplicates", "", onCommit = { text ->
                        text.trim().toIntOrNull()?.takeIf { it in 1..99 }?.let { n -> close(); onDuplicateSelection(n) }
                    }, onCancel = { duplicateCount = false }, modifier = Modifier.testTag("layer-menu-duplicate-count"),
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                }
                DropdownMenuItem(text = { Text("Copy") }, onClick = { close(); onCopySelection() }, modifier = Modifier.testTag("layer-menu-copy"))
            } else if (selectedLayer != null) {
                DropdownMenuItem(text = { Text("Flip Horizontal") }, onClick = { close(); onFlipHorizontal() })
                DropdownMenuItem(text = { Text("Flip Vertical") }, onClick = { close(); onFlipVertical() })
                if (selectedLayer.type == LayerType.VIDEO || selectedLayer.type == LayerType.IMAGE || selectedLayer.type == LayerType.TEXT) {
                    val fitEnabled = selectedLayer.type != LayerType.TEXT || textCanFit
                    DropdownMenuItem(text = { Text("Fit to Composition Width") }, enabled = fitEnabled, onClick = { close(); onFitToWidth() })
                    DropdownMenuItem(text = { Text("Fit to Composition Height") }, enabled = fitEnabled, onClick = { close(); onFitToHeight() })
                    DropdownMenuItem(text = { Text("Stretch to Composition Area") }, enabled = fitEnabled, onClick = { close(); onStretchToArea() })
                    if (selectedLayer.type != LayerType.TEXT) DropdownMenuItem(text = { Text("Media Info") }, onClick = { close(); onMediaInfo() })
                }
                DropdownMenuItem(text = { Text("Auto Orient") }, enabled = false, onClick = {})
                DropdownMenuItem(text = { Text("Convert to Outline / Autotrace") }, enabled = false, onClick = {})
                HorizontalDivider()
            }
            DropdownMenuItem(
                text = { Text("Paste layer", color = if (hasClipboard) TextPrimary else TextDisabled) },
                onClick = { close(); onPasteLayer() },
                enabled = hasClipboard,
                leadingIcon = { PasteVectorIcon(color = if (hasClipboard) TextPrimary else TextDisabled) },
                modifier = Modifier.testTag("layer-menu-paste")
            )
            DropdownMenuItem(
                text = { Text("Select all layers", color = TextPrimary) },
                onClick = { close(); onSelectAll() },
                leadingIcon = { SelectAllVectorIcon(color = TextPrimary) },
                modifier = Modifier.testTag("layer-menu-select-all")
            )
            if (selectionMode) DropdownMenuItem(text = { Text("Invert selection") }, onClick = { close(); onInvertSelection() },
                modifier = Modifier.testTag("layer-menu-invert"))
        }
    }
}

@Preview(showBackground = true)
@Preview(name = "Dark Mode", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun EditorActionBarPreview() {
    FluxxTheme {
        EditorActionBar(
            isPlaying = false,
            canUndo = true,
            canRedo = false,
            hasClipboard = true,
            onUndo = {},
            onRedo = {},
            onPrevBoundary = {},
            onPlayPauseToggle = {},
            onNextBoundary = {},
            onPasteLayer = {},
        )
    }
}

@Composable
private fun ToolbarButton(
    enabled: Boolean,
    label: String? = null,
    onClick: () -> Unit,
    content: @Composable (Color) -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp, EditorActionBarHeight)
            .clickable(enabled = enabled) { onClick() }
            .semantics { if (label != null) contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        val color = if (enabled) TextPrimary else TextSecondary // TextSecondary is #7A7A7A
        content(color)
    }
}

// Action bar glyphs come from the shared inspector icon family (one stroke, round caps/joins, open chevrons).
@Composable
private fun BarIcon(name: String, color: Color, modifier: Modifier) =
    Icon(InspectorIcons.icon(name), null, modifier.size(InspectorIcons.SIZE), tint = color)
@Composable private fun UndoVectorIcon(color: Color, modifier: Modifier = Modifier) = BarIcon("UNDO", color, modifier)
@Composable private fun RedoVectorIcon(color: Color, modifier: Modifier = Modifier) = BarIcon("REDO", color, modifier)
@Composable private fun PrevBoundaryVectorIcon(color: Color, modifier: Modifier = Modifier) = BarIcon("PREV_KEY", color, modifier)
@Composable private fun NextBoundaryVectorIcon(color: Color, modifier: Modifier = Modifier) = BarIcon("NEXT_KEY", color, modifier)
@Composable private fun PlayPauseVectorIcon(isPlaying: Boolean, color: Color, modifier: Modifier = Modifier) = BarIcon(if (isPlaying) "PAUSE" else "PLAY", color, modifier)
@Composable private fun LayerActionVectorIcon(color: Color, modifier: Modifier = Modifier) = BarIcon("LAYER", color, modifier)
@Composable private fun PasteVectorIcon(color: Color, modifier: Modifier = Modifier) = BarIcon("PASTE", color, modifier)
@Composable private fun SelectAllVectorIcon(color: Color, modifier: Modifier = Modifier) = BarIcon("SELECT_ALL", color, modifier)
