package com.fluxx.android.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fluxx.android.editor.DragReadout
import com.fluxx.android.editor.TimelineDragInfo
import com.fluxx.android.model.FrameRate
import com.fluxx.android.ui.inspector.InspectorIcons
import com.fluxx.android.ui.theme.TextPrimary

/**
 * TLP.2 part 5 (Alight Motion style): while a timeline hold-drag runs, a transient readout drawn OVER the action bar's
 * strip (the host gives it the bar's bounds; no layout change). LEFT: |<- the dragged item's start (playhead readout
 * format); RIGHT: the shift arrows and the signed delta since the drag began. No pointer input, so it eats no gestures;
 * it disappears the moment the drag ends or is cancelled.
 */
@Composable internal fun DragReadoutOverlay(info: TimelineDragInfo, startTimecodeUs: Long, rate: FrameRate, modifier: Modifier = Modifier) {
    val start = DragReadout.start(info.startUs, startTimecodeUs, rate)
    val delta = DragReadout.delta(info.originUs, info.startUs, rate)
    Row(modifier.background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp).testTag("drag-readout"),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(InspectorIcons.icon("TO_START"), null, Modifier.size(InspectorIcons.SIZE), tint = TextPrimary)
        Spacer(Modifier.width(8.dp))
        Text(start, Modifier.testTag("drag-readout-start").semantics { contentDescription = "Start $start" }, color = TextPrimary,
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace))
        Spacer(Modifier.weight(1f))
        Icon(InspectorIcons.icon("SHIFT"), null, Modifier.size(InspectorIcons.SIZE), tint = TextPrimary)
        Spacer(Modifier.width(8.dp))
        Text(delta, Modifier.testTag("drag-readout-delta").semantics { contentDescription = "Moved $delta" }, color = TextPrimary,
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace))
    }
}
