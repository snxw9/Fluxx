package com.fluxx.android.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fluxx.android.ui.inspector.AnchoredPopover
import com.fluxx.android.ui.inspector.InspectorIcons
import com.fluxx.android.ui.inspector.InspectorValueField
import com.fluxx.android.ui.inspector.PopoverDismissal
import com.fluxx.android.ui.inspector.PopoverToggle
import com.fluxx.android.ui.theme.TextDisabled
import com.fluxx.android.ui.theme.TextPrimary

/** TLP T3: the selection bars' height (the editor top bar's 48dp). The bottom bar has the same height and width. */
internal val SelectionBarHeight = 48.dp

/** The target size of a [BarIcon]: 48dp in the selection bars; the header height when the key bar sits in the sheet header. */
internal val LocalBarIconSize = compositionLocalOf { 48.dp }

/** TLP.3 A1: bar popovers open DOWNWARD from a bar at the top of the sheet (the key header bar), upward elsewhere. */
internal val LocalBarPopoverBelow = compositionLocalOf { false }

/**
 * TLP.2 part 3: the key bar's icons (Hold, Linear, Easy Ease, More, Stagger, Sequence) while KEY selection is active, else
 * null. Provided by the editor around the inspector sheet; the sheet's header row (section pages, Graph, Edit Text) shows
 * them INSTEAD of its own contents, in a row of exactly the header's height, so nothing below the header moves.
 */
internal val LocalKeyHeaderBar = compositionLocalOf<(@Composable RowScope.() -> Unit)?> { null }

/** The key bar in the sheet header: accent-filled like the selection bars, exactly [height] tall (the header's height). */
@Composable internal fun KeyHeaderBar(height: androidx.compose.ui.unit.Dp, content: @Composable RowScope.() -> Unit) {
    CompositionLocalProvider(LocalBarIconSize provides height, LocalBarPopoverBelow provides true) {
        Row(Modifier.fillMaxWidth().height(height).testTag("key-header-bar").clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly, content = content)
    }
}

/**
 * TLP T3 / T5 top bar, shown instead of the editor top bar in selection mode: accent-filled ([MaterialTheme] `primary`,
 * content in `onPrimary`). Back exits selection mode (it never leaves the project), the title says what is selected,
 * [actions] are the bar's icons.
 */
@Composable internal fun SelectionTopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, testTag: String = "selection-top-bar",
    actions: @Composable RowScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth().background(scheme.primary).statusBarsPadding().height(SelectionBarHeight).testTag(testTag)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BarIcon("BACK", "Exit selection", onClick = onBack, testTag = "$testTag-back")
            Text(title, Modifier.weight(1f).padding(horizontal = 4.dp).testTag("$testTag-title"), color = scheme.onPrimary,
                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions()
        }
    }
}

/**
 * TLP T3 / T5 bottom bar: an overlay ABOVE the timeline (it never resizes it; the timeline gets the same bottom scroll
 * padding), the top bar's height and width, accent-filled.
 */
@Composable internal fun SelectionBottomBar(modifier: Modifier = Modifier, testTag: String = "selection-bottom-bar", content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().height(SelectionBarHeight).testTag(testTag).background(MaterialTheme.colorScheme.primary)
        .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly, content = content)
}

/** One icon of a selection bar, in `onPrimary` (disabled: `onPrimary` at 38%); a 48dp target, or the header height in the sheet header. */
@Composable internal fun BarIcon(icon: String, label: String, enabled: Boolean = true, selected: Boolean = false, testTag: String? = null,
    onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onPrimary
    Box(Modifier.size(LocalBarIconSize.current).clip(RoundedCornerShape(10.dp))
        .background(if (selected) ink.copy(alpha = .14f) else androidx.compose.ui.graphics.Color.Transparent)
        .combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label; if (selected) stateDescription = "On" }
        .then(if (testTag != null) Modifier.testTag(testTag) else Modifier), contentAlignment = Alignment.Center) {
        Icon(InspectorIcons.icon(icon), null, Modifier.size(InspectorIcons.SIZE), tint = if (enabled) ink else ink.copy(alpha = .38f))
    }
}

/** A bar icon that toggles a non-modal popover anchored to it (the inspector's popover rules). */
@Composable internal fun BarPopoverIcon(icon: String, label: String, key: String, testTag: String, enabled: Boolean = true,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        BarIcon(icon, label, enabled, selected = open, testTag = testTag) {
            val next = PopoverToggle.next(if (open) key else null, key) != null
            if (next) { PopoverDismissal.request(); open = true } else open = false
        }
        AnchoredPopover(open, { open = false }, "$testTag-popover", modal = false, preferBelow = LocalBarPopoverBelow.current) { content { open = false } }
    }
}

/** A text row inside a bar popover (disabled rows are placeholders). */
@Composable internal fun PopoverRow(text: String, enabled: Boolean = true, testTag: String? = null, onClick: () -> Unit = {}) {
    Box(Modifier.widthIn(min = 168.dp).height(44.dp).clip(RoundedCornerShape(8.dp))
        .combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .then(if (testTag != null) Modifier.testTag(testTag) else Modifier).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
        Text(text, color = if (enabled) TextPrimary else TextDisabled, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * TLP T4 / T5 Stagger / Sequence popover body: a typed whole number (no steppers, commit on IME Done or focus loss) and a
 * Reverse toggle. [onApply] receives the typed value and the toggle.
 */
@Composable internal fun FramesPopoverContent(label: String, unit: String, initial: Int, reverse: Boolean, onReverse: () -> Unit,
    onApply: (Int) -> Unit, close: () -> Unit, testTag: String, allowNegative: Boolean = false, showReverse: Boolean = true, minimum: Int = 0) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
        InspectorValueField(initial.toString(), label, unit, onCommit = { text ->
            val n = text.trim().toIntOrNull()
            if (n != null && (allowNegative || n >= minimum)) { onApply(n); close() }
        }, onCancel = close, modifier = Modifier.testTag("$testTag-field"),
            keyboardType = if (allowNegative) androidx.compose.ui.text.input.KeyboardType.DecimalSigned else androidx.compose.ui.text.input.KeyboardType.Number,
            autoFocus = false)
        if (showReverse) Spacer(Modifier.width(4.dp))
        if (showReverse) com.fluxx.android.ui.inspector.InspectorIconButton(InspectorIcons.icon("EASE_INVERT"), if (reverse) "Reverse order: on" else "Reverse order: off",
            Modifier.testTag("$testTag-reverse").semantics { stateDescription = if (reverse) "On" else "Off" }, selected = reverse) { onReverse() }
    }
}
