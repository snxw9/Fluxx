package com.fluxx.android.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import com.fluxx.android.ui.inspector.LocalHostImeBottom
import com.fluxx.android.ui.theme.*

/** Receives one value-entry session: begin once (on the first valid value), live previews, then finish or cancel. */
internal interface ValueSink { fun begin(); fun preview(value: Int); fun finish(); fun cancel() }

/**
 * One in-place edit, focus to blur. A VALID complete entry applies live (begin on the first valid value, then one
 * preview per distinct value); leaving the field keeps it as ONE undo entry. Empty, invalid or incomplete text applies
 * nothing and reverts. While the field is empty the previous value stays visible, faded, behind the input.
 */
internal class InlineEntrySession(private val parse: (text: String, base: Int) -> Int?, private val sink: ValueSink,
    /** Whether a valid value applies LIVE while typing; others apply when the field is left (GRAPH G2.1: 3-digit hex). */
    private val live: (String) -> Boolean = { true }) {
    /** The value shown when the session started (faded placeholder while the field is empty). */
    var previous = ""; private set
    var text = ""; private set
    var active = false; private set
    private var base = 0
    private var applied: Int? = null
    val placeholderVisible get() = active && text.isEmpty()
    fun focus(display: String, base: Int) {
        if (active) return
        active = true; previous = display; text = display; this.base = base; applied = null
    }
    /** A keystroke. Applies only valid complete values; repeating the same value applies nothing new. */
    fun edit(next: String) {
        if (!active) return
        text = next
        if (!live(next)) return
        val value = parse(next, base) ?: return
        if (value == applied) return
        if (applied == null) sink.begin()
        applied = value; sink.preview(value)
    }
    /**
     * Focus lost, IME Done, tap outside or Back. A valid final value is kept (finish: one undo); empty or invalid text
     * reverts anything previewed (cancel) and changes nothing. Returns whether a value was kept.
     */
    fun end(): Boolean {
        if (!active) return false
        active = false
        val final = parse(text, base)
        // A valid final value that was not applied live (e.g. #fff) applies now, still one undo.
        if (final != null && final != applied) { if (applied == null) sink.begin(); applied = final; sink.preview(final) }
        val kept = applied != null && final != null
        if (applied != null) { if (kept) sink.finish() else sink.cancel() }
        applied = null; text = previous
        return kept
    }
    /** Hardware Escape: revert whatever was typed. */
    fun cancel() {
        if (!active) return
        active = false
        if (applied != null) sink.cancel()
        applied = null; text = previous
    }
}

/**
 * Lift a page (e.g. the inspector sheet) above the keyboard while one of its in-place fields is focused; nothing is
 * resized. Hosts that float above the keyboard by themselves (anchored popovers) provide null.
 */
internal class ImeLiftHost { var lift by mutableFloatStateOf(0f) }
internal val LocalImeLift = compositionLocalOf<ImeLiftHost?> { null }

/**
 * Reusable in-place value field (hex, alpha %, R/G/B): tap to edit with select-all, numeric or hex IME, NO popup or
 * dialog. Leaving it (focus loss, IME Done, tap outside, Back) ends the [InlineEntrySession]; Escape cancels.
 */
@Composable internal fun InlineValueField(display: String, base: Int, parse: (String, Int) -> Int?, label: String,
    onBegin: () -> Unit, onPreview: (Int) -> Unit, onFinish: () -> Unit, onCancel: () -> Unit,
    modifier: Modifier = Modifier, hex: Boolean = false, prefix: String = "", suffix: String = "", height: Dp = 32.dp,
    testTag: String = "inline-value-field", onFocus: (Boolean) -> Unit = {}, live: (String) -> Boolean = { true }) {
    val begin by rememberUpdatedState(onBegin); val preview by rememberUpdatedState(onPreview)
    val finish by rememberUpdatedState(onFinish); val cancel by rememberUpdatedState(onCancel)
    val currentParse by rememberUpdatedState(parse)
    val currentLive by rememberUpdatedState(live)
    val session = remember { InlineEntrySession({ t, b -> currentParse(t, b) }, object : ValueSink {
        override fun begin() = begin(); override fun preview(value: Int) = preview(value)
        override fun finish() = finish(); override fun cancel() = cancel()
    }) { currentLive(it) } }
    var field by remember { mutableStateOf(TextFieldValue(display)) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(display, focused) { if (!focused) field = TextFieldValue(display) }
    DisposableEffect(Unit) { onDispose { session.end() } }
    // Back first hides the keyboard: leaving the field then ends the session like a tap outside.
    val hostIme = LocalHostImeBottom.current
    val ime = if (hostIme >= 0) hostIme else WindowInsets.ime.getBottom(LocalDensity.current)
    var keyboardSeen by remember { mutableStateOf(false) }
    LaunchedEffect(ime, focused) {
        if (!focused) keyboardSeen = false
        else if (ime > 0) keyboardSeen = true
        else if (keyboardSeen) { keyboardSeen = false; focusManager.clearFocus() }
    }
    // Pages lift above the keyboard (only while this field is focused); popovers lift themselves.
    val lift = LocalImeLift.current
    val view = LocalView.current
    val margin = with(LocalDensity.current) { 8.dp.toPx() }
    var bottom by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(focused, ime, bottom) {
        if (lift == null) return@LaunchedEffect
        if (!focused || ime <= 0) { if (!focused) lift.lift = 0f; return@LaunchedEffect }
        val visible = view.rootView.height - ime
        lift.lift = (bottom + lift.lift - visible + margin).coerceAtLeast(0f)
    }
    val shape = RoundedCornerShape(8.dp)
    Row(modifier.height(height).clip(shape).background(Surface).border(1.dp, if (focused) TextPrimary else Border, shape)
        .padding(horizontal = 6.dp).onGloballyPositioned { bottom = it.boundsInWindow().bottom }, verticalAlignment = Alignment.CenterVertically) {
        if (prefix.isNotEmpty()) Text(prefix, color = TextSecondary, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, maxLines = 1)
        BasicTextField(field, { next ->
                field = next
                session.edit(next.text)
            }, singleLine = true,
            textStyle = MaterialTheme.typography.labelMedium.copy(color = TextPrimary, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Start),
            cursorBrush = SolidColor(TextPrimary),
            keyboardOptions = KeyboardOptions(keyboardType = if (hex) KeyboardType.Ascii else KeyboardType.Number,
                capitalization = if (hex) KeyboardCapitalization.Characters else KeyboardCapitalization.None, autoCorrectEnabled = false,
                imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    // Previous-value placeholder: faded behind the empty input; typing replaces it.
                    if (focused && field.text.isEmpty()) Text(session.previous, color = TextPrimary.copy(alpha = PLACEHOLDER_ALPHA),
                        style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, maxLines = 1)
                    inner()
                }
            },
            modifier = Modifier.weight(1f).testTag(testTag)
                .onFocusChanged {
                    if (it.isFocused && !focused) {
                        session.focus(display, base)
                        field = TextFieldValue(display, TextRange(0, display.length))
                    }
                    if (focused && !it.isFocused) { session.end(); field = TextFieldValue(display) }
                    if (focused != it.isFocused) onFocus(it.isFocused)
                    focused = it.isFocused
                }
                .onPreviewKeyEvent {
                    if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) { session.cancel(); focusManager.clearFocus(); true } else false
                }
                .semantics { contentDescription = "$label $display; tap to type" })
        if (suffix.isNotEmpty()) Text(suffix, color = TextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

internal const val PLACEHOLDER_ALPHA = .4f
