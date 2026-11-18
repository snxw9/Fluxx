package com.fluxx.android.ui.inspector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxx.android.ui.theme.*
import kotlinx.coroutines.CancellationException

/**
 * Section-page slider with a thick track and a large thumb sized by [SectionLayout.sliderRow]. The whole row is the
 * touch target; one drag = begin, live changes, finish (or cancel): one undo entry, exactly like the M3 slider path.
 */
@Composable internal fun SectionSlider(fraction: Float, row: SectionLayout.SliderRow, label: String,
    onBegin: () -> Unit, onChange: (Float) -> Unit, onFinish: () -> Unit, onCancel: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    val begin by rememberUpdatedState(onBegin); val change by rememberUpdatedState(onChange)
    val finish by rememberUpdatedState(onFinish); val cancel by rememberUpdatedState(onCancel)
    val focus = LocalFocusManager.current
    var active by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (active) cancel() } }
    Canvas(modifier.height(row.touch.dp).testTag("section-slider-$label")
        .semantics {
            contentDescription = "$label slider"
            progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
            if (!enabled) disabled()
            else setProgress { begin(); change(it.coerceIn(0f, 1f)); finish(); true }
        }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(); down.consume(); focus.clearFocus()
                active = true; begin()
                fun update(x: Float) = change((x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
                try {
                    update(down.position.x)
                    val completed = horizontalDrag(down.id) { it.consume(); update(it.position.x) }
                    active = false
                    if (completed) finish() else cancel()
                } catch (e: CancellationException) { if (active) { active = false; cancel() }; throw e }
            }
        }) {
        val track = row.track.dp.toPx(); val thumb = row.thumb.dp.toPx() / 2f
        val y = center.y; val start = thumb; val width = (size.width - 2 * thumb).coerceAtLeast(1f)
        val x = start + fraction.coerceIn(0f, 1f) * width
        val ink = if (enabled) TextPrimary else TextDisabled
        drawRoundRect(Border, Offset(start, y - track / 2), Size(width, track), CornerRadius(track / 2))
        drawRoundRect(ink, Offset(start, y - track / 2), Size(x - start, track), CornerRadius(track / 2))
        drawCircle(ink, thumb, Offset(x, y))
        drawCircle(Surface, thumb * .35f, Offset(x, y))
    }
}

/** Label line of a slider row: label, then the keyframe badge beside it (when the property is keyed). */
@Composable internal fun SliderRowLabel(label: String, row: SectionLayout.SliderRow, badge: KeyBadge = KeyBadge.NONE, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().height(row.label.dp).semantics(mergeDescendants = true) {
        KeyBadges.description(badge)?.let { stateDescription = it } }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = if (enabled) TextSecondary else TextDisabled, style = MaterialTheme.typography.labelMedium,
            fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        KeyBadgeGlyph(badge, if (enabled) TextSecondary else TextDisabled, Modifier.testTag("slider-badge-$label"))
    }
}

/**
 * A section page's control group (TEXT UI 8): [group] dp tall from [SectionLayout], centred vertically in the content
 * area (equal margins above and below, [SectionLayout.centre]) and horizontally in its column. A group taller than the
 * content (large font scales) falls back to top-aligned and scrollable: no overlap, never clipped. With [group] null
 * the group is measured (pages whose text rows have no fixed height) and centred the same way.
 */
@Composable internal fun CentredSectionGroup(group: Float?, testTag: String, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("$testTag-area")) {
        val place = group?.let { SectionLayout.centre(maxHeight.value, it) }
        if (place != null && place.fits) Column(Modifier.fillMaxWidth().padding(top = place.top.dp).height(group.dp).testTag(testTag),
            horizontalAlignment = Alignment.CenterHorizontally, content = content)
        else Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = if (group == null) maxHeight else 0.dp).testTag(testTag),
            verticalArrangement = if (group == null) Arrangement.Center else Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}
