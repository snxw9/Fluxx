package com.fluxx.android.ui.common.colorpicker

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fluxx.android.model.ColorPalette
import com.fluxx.android.model.FluxxColor
import com.fluxx.android.model.PaletteRepository
import com.fluxx.android.ui.common.InlineValueField
import com.fluxx.android.ui.inspector.InspectorIconButton
import com.fluxx.android.ui.inspector.InspectorIcons
import com.fluxx.android.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** COMPACT: the popover picker (text Fill/Stroke, marker colour, swatch editor). FULL: the same design scaled up for pages. */
enum class ColourPickerVariant { COMPACT, FULL }

/** One colour the picker can edit (Fill / Stroke). The target toggle shows only when there are several. */
data class ColourPickerTarget(val icon: String, val label: String)

/** Pure colour-value arithmetic shared by both variants (hex, alpha %, R/G/B), all on packed ARGB. */
internal object ColourValues {
    /** RRGGBB keeps the current alpha; AARRGGBB replaces it; `#` optional; any case. Incomplete/invalid → null. */
    fun hex(text: String, current: Int): Int? = FluxxColor.parseHex(text.trim().removePrefix("#"), (current ushr 24) / 255f)?.toArgb()
    fun hexText(argb: Int) = FluxxColor.fromArgb(argb).toHexString(true)
    fun alphaPercent(argb: Int) = (argb ushr 24) * 100f / 255f
    fun alphaText(argb: Int) = alphaPercent(argb).roundToInt().toString()
    fun withAlpha(argb: Int, alpha: Int) = (argb and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
    fun withAlphaPercent(argb: Int, percent: Float) = withAlpha(argb, (percent.coerceIn(0f, 100f) / 100f * 255f).roundToInt())
    /** Alpha accepts 0..100 (an optional % sign); anything else is invalid, never clamped. */
    fun alpha(text: String, current: Int): Int? {
        val v = text.trim().removeSuffix("%").trim().replace(',', '.').toFloatOrNull() ?: return null
        if (!v.isFinite() || v < 0f || v > 100f) return null
        return withAlphaPercent(current, v)
    }
    /** Channel shift: 16 red, 8 green, 0 blue. Values 0..255. */
    fun channel(argb: Int, shift: Int) = argb ushr shift and 255
    fun channelText(argb: Int, shift: Int) = channel(argb, shift).toString()
    fun withChannel(argb: Int, shift: Int, value: Int) = (argb and (255 shl shift).inv()) or (value.coerceIn(0, 255) shl shift)
    fun channel(text: String, current: Int, shift: Int): Int? {
        val v = text.trim().toIntOrNull() ?: return null
        if (v !in 0..255) return null
        return withChannel(current, shift, v)
    }
    /** A swatch sets the colour and keeps the current alpha (a fully transparent colour becomes opaque). */
    fun swatch(current: Int, argb: Int) = withAlpha(argb, (current ushr 24).takeIf { it > 0 } ?: 255)
    fun hsv(hue: Float, saturation: Float, value: Float, current: Int) = FluxxColor.fromHsv(hue, saturation, value, (current ushr 24) / 255f).toArgb()
}

/** Default swatches when no palette is available, and the marker palette. */
internal val DEFAULT_SWATCHES = listOf(0xFFFFFFFF, 0xFFBDBDBD, 0xFF757575, 0xFF212121, 0xFF000000, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835,
    0xFF43A047, 0xFF1E88E5, 0xFF8E24AA, 0xFFD81B60).map { it.toInt() }
/** Marker flag colours (composition default red, layer default orange first; MARKERS.md). */
internal val MARKER_SWATCHES = listOf(0xFFF44336, 0xFFFF9800, 0xFFFFEB3B, 0xFF4CAF50, 0xFF00BCD4, 0xFF2196F3, 0xFF3F51B5,
    0xFF9C27B0, 0xFFE91E63, 0xFFFFFFFF, 0xFF9E9E9E, 0xFF212121).map { it.toInt() }

/** Recently applied colours (this session only; no persistence or schema change). Newest first, deduplicated. */
internal object ColourRecents {
    const val MAX = 12
    val colours: SnapshotStateList<Int> = mutableStateListOf()
    fun add(argb: Int) { colours.remove(argb); colours.add(0, argb); while (colours.size > MAX) colours.removeAt(colours.lastIndex) }
}

/**
 * Wraps the caller's begin/preview/finish/cancel: every interaction (SV/hue/alpha/RGB drag, a typed value session,
 * a swatch) is exactly one transaction, i.e. ONE undo entry. A second begin inside a transaction is ignored.
 */
internal class ColourTransaction(private val begin: () -> Unit, private val preview: (Int) -> Unit,
    private val finish: () -> Unit, private val cancel: () -> Unit, private val record: (Int) -> Unit = {}) {
    var active = false; private set
    private var last: Int? = null
    fun begin() { if (!active) { active = true; last = null; begin.invoke() } }
    fun preview(argb: Int) { if (active) { last = argb; preview.invoke(argb) } }
    fun finish() { if (active) { active = false; last?.let(record); finish.invoke() } }
    fun cancel() { if (active) { active = false; last = null; cancel.invoke() } }
    /** One-shot edit (swatch): begin, preview, finish. */
    fun set(argb: Int) { cancel(); begin(); preview(argb); finish() }
}

/** Palette swatches for the compact strip: the active palette (first 12), else [fallback]. */
@Composable internal fun paletteSwatches(palette: PaletteRepository?, fallback: List<Int>): List<Int> {
    val library = palette?.state?.collectAsState()?.value
    return (library?.activePalette ?: library?.palettes?.firstOrNull())?.swatches?.map { it.toArgb() }?.takeIf { it.isNotEmpty() }?.take(12) ?: fallback
}

/**
 * The shared colour picker. Both variants: rounded 10dp containers, the target toggle (only with several targets),
 * eyedropper / inherit / reset icons from the shared family, SV square + hue bar, alpha, the swatch/palette area below
 * the controls, live apply and ONE undo per interaction. Hex, alpha % and (FULL) R/G/B values are edited IN PLACE
 * ([InlineValueField]): no nested popup. Chrome is monochrome; only the colours are coloured.
 */
@Composable internal fun FluxxColourPicker(variant: ColourPickerVariant, argb: Int,
    onBegin: () -> Unit, onPreview: (Int) -> Unit, onFinish: () -> Unit, onCancel: () -> Unit,
    modifier: Modifier = Modifier, name: String = "colour",
    targets: List<ColourPickerTarget> = emptyList(), target: Int = 0, onTarget: (Int) -> Unit = {},
    swatches: List<Int> = DEFAULT_SWATCHES, palette: PaletteRepository? = null,
    onEyedropper: (() -> Unit)? = null, onInherit: (() -> Unit)? = null, onReset: (() -> Unit)? = null,
    showAlpha: Boolean = true, onSwatch: ((Int) -> Unit)? = null) {
    val b by rememberUpdatedState(onBegin); val p by rememberUpdatedState(onPreview)
    val f by rememberUpdatedState(onFinish); val c by rememberUpdatedState(onCancel)
    val tx = remember { ColourTransaction({ b() }, { p(it) }, { f() }, { c() }, ColourRecents::add) }
    // Hue/saturation survive greys and black while dragging (same rule as before; colour maths is FluxxColor's).
    val hsv = FluxxColor.fromArgb(argb).toHsv()
    var hue by remember(target) { mutableFloatStateOf(hsv.hue) }
    var saturation by remember(target) { mutableFloatStateOf(hsv.saturation) }
    var value by remember(target) { mutableFloatStateOf(hsv.value) }
    var dragging by remember { mutableStateOf(false) }
    var before by remember { mutableStateOf(Triple(hue, saturation, value)) }
    LaunchedEffect(argb, dragging) {
        if (!dragging) { val next = FluxxColor.fromArgb(argb).toHsv(); value = next.value
            if (next.value > .01f) { if (next.saturation > .01f && !(hue == 360f && next.hue == 0f)) hue = next.hue; saturation = next.saturation } }
    }
    val sv = SvControls(hue, saturation, value,
        begin = { before = Triple(hue, saturation, value); dragging = true; tx.begin() },
        commit = { dragging = false; tx.finish() },
        cancel = { hue = before.first; saturation = before.second; value = before.third; dragging = false; tx.cancel() },
        onSv = { s, v -> saturation = s; value = v; tx.preview(ColourValues.hsv(hue, s, v, argb)) },
        onHue = { h -> hue = h; tx.preview(ColourValues.hsv(h, saturation, value, argb)) })
    val pick: (Int) -> Unit = onSwatch ?: { tx.set(ColourValues.swatch(argb, it)) }
    val focus = LocalFocusManager.current
    val root = Modifier.pointerInput(Unit) { detectTapGestures { focus.clearFocus() } }
    @Composable fun Header(height: Dp) {
        Row(Modifier.fillMaxWidth().height(height).testTag("colour-picker-header"), verticalAlignment = Alignment.CenterVertically) {
            if (targets.size > 1) targets.forEachIndexed { i, t ->
                InspectorIconButton(InspectorIcons.icon(t.icon), "Edit ${t.label}", Modifier.testTag("colour-target-$i"), selected = i == target) { onTarget(i) }
            }
            Spacer(Modifier.weight(1f))
            if (onEyedropper != null) InspectorIconButton(InspectorIcons.icon("EYEDROPPER"), "Sample $name from the preview") { onEyedropper() }
            if (onInherit != null) InspectorIconButton(InspectorIcons.icon("INHERIT"), "Inherit $name from the layer") { onInherit() }
            if (onReset != null) InspectorIconButton(InspectorIcons.icon("RESET"), "Reset $name") { onReset() }
        }
    }
    @Composable fun HexField(modifier: Modifier, height: Dp) {
        InlineValueField(ColourValues.hexText(argb), argb, ColourValues::hex, "Hex $name", tx::begin, tx::preview, tx::finish, tx::cancel,
            modifier, hex = true, prefix = "#", height = height, testTag = "colour-hex")
    }
    @Composable fun AlphaField(modifier: Modifier, height: Dp) {
        InlineValueField(ColourValues.alphaText(argb), argb, ColourValues::alpha, "${name.replaceFirstChar { it.uppercase() }} alpha",
            tx::begin, tx::preview, tx::finish, tx::cancel, modifier, suffix = "%", height = height, testTag = "colour-alpha")
    }
    when (variant) {
        // ---- COMPACT: exactly the TEXT UI 6 text colour popover layout (288dp wide) -------------------------------
        ColourPickerVariant.COMPACT -> Column(modifier.width(288.dp).then(root).testTag("colour-picker-compact")) {
            Header(40.dp)
            Row(Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SaturationValueBox(sv.hue, sv.saturation, sv.value, sv.onSv,
                    Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, Border, RoundedCornerShape(8.dp)), sv.begin, sv.commit, sv.cancel)
                HueStrip(sv.hue, sv.onHue, Modifier.width(16.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp)), sv.begin, sv.commit, sv.cancel)
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                        ColorPreviewSwatch(FluxxColor.fromArgb(argb), Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)).border(1.dp, Border, RoundedCornerShape(5.dp)))
                        HexField(Modifier.weight(1f).padding(start = 6.dp), 32.dp)
                    }
                    if (showAlpha) Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                        Slider(ColourValues.alphaPercent(argb) / 100f, { v -> tx.begin(); tx.preview(ColourValues.withAlphaPercent(argb, v * 100f)) },
                            Modifier.weight(1f).semantics { contentDescription = "${name.replaceFirstChar { it.uppercase() }} alpha" },
                            onValueChangeFinished = { tx.finish() },
                            colors = SliderDefaults.colors(thumbColor = TextPrimary, activeTrackColor = TextPrimary, inactiveTrackColor = Border))
                        AlphaField(Modifier.width(52.dp), 32.dp)
                    }
                }
            }
            if (swatches.isNotEmpty()) SwatchStrip(swatches, argb, pick, Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 4.dp))
        }
        // ---- FULL: the same design language and order, scaled up, plus R/G/B and the palette area -----------------
        ColourPickerVariant.FULL -> BoxWithConstraints(modifier.then(root).testTag("colour-picker-full")) {
            val m = ColourPickerLayout.full(maxWidth.value, maxHeight.value.takeIf { it.isFinite() } ?: 260f)
            Column(Modifier.fillMaxSize()) {
                Header(m.header.dp)
                Row(Modifier.fillMaxWidth().height(m.controls.dp).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SaturationValueBox(sv.hue, sv.saturation, sv.value, sv.onSv,
                        Modifier.size(m.square.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Border, RoundedCornerShape(10.dp)), sv.begin, sv.commit, sv.cancel)
                    HueStrip(sv.hue, sv.onHue, Modifier.width(ColourPickerLayout.HUE_FULL.dp).height(m.square.dp).clip(RoundedCornerShape(10.dp)), sv.begin, sv.commit, sv.cancel)
                    Column(Modifier.weight(1f).height(m.square.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        val row = m.row.dp; val field = minOf(m.row - 4f, 32f).dp
                        Row(Modifier.fillMaxWidth().height(row), verticalAlignment = Alignment.CenterVertically) {
                            ColorPreviewSwatch(FluxxColor.fromArgb(argb), Modifier.size(minOf(m.row - 8f, 24f).dp).clip(RoundedCornerShape(6.dp)).border(1.dp, Border, RoundedCornerShape(6.dp)))
                            HexField(Modifier.weight(1f).padding(start = 6.dp), field)
                        }
                        if (showAlpha) ChannelRow("A", argb, { a -> ColourValues.withAlpha(argb, a) }, ColourValues.alphaPercent(argb) / 100f, 255, tx, row,
                            listOf(Color(ColourValues.withAlpha(argb, 0)), Color(ColourValues.withAlpha(argb, 255)))) { AlphaField(Modifier.width(56.dp), field) }
                        for ((label, shift) in listOf("R" to 16, "G" to 8, "B" to 0)) {
                            ChannelRow(label, argb, { v -> ColourValues.withChannel(argb, shift, v) }, ColourValues.channel(argb, shift) / 255f, 255, tx, row,
                                listOf(Color(ColourValues.withChannel(argb or (0xFF shl 24), shift, 0)), Color(ColourValues.withChannel(argb or (0xFF shl 24), shift, 255)))) {
                                InlineValueField(ColourValues.channelText(argb, shift), argb, { t, base -> ColourValues.channel(t, base, shift) }, "$label channel",
                                    tx::begin, tx::preview, tx::finish, tx::cancel, Modifier.width(56.dp), height = field, testTag = "colour-channel-$label")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(ColourPickerLayout.GAP.dp))
                PaletteArea(palette, swatches, argb, pick, m)
            }
        }
    }
}

private class SvControls(val hue: Float, val saturation: Float, val value: Float, val begin: () -> Unit, val commit: () -> Unit,
    val cancel: () -> Unit, val onSv: (Float, Float) -> Unit, val onHue: (Float) -> Unit)

/** FULL variant geometry (dp), computed from the available box: never scrolls, never overflows. */
internal data class FullPickerMetrics(val header: Float, val controls: Float, val square: Float, val row: Float, val palette: Float, val recents: Boolean)

internal object ColourPickerLayout {
    const val HEADER = 40f
    const val GAP = 6f
    const val HUE_FULL = 20f
    const val SQUARE_MAX = 200f
    const val RIGHT_MIN = 150f
    const val PALETTE_ROW = 40f
    const val ROW_MIN = 24f
    /** Five value rows on the right (hex, A, R, G, B). */
    const val ROWS = 5
    fun full(width: Float, height: Float): FullPickerMetrics {
        // Recents get their own row only when the controls keep at least 36dp value rows.
        val recents = height - HEADER - GAP - 2 * PALETTE_ROW >= ROWS * 36f
        val palette = if (recents) 2 * PALETTE_ROW else PALETTE_ROW
        val controls = (height - HEADER - GAP - palette).coerceAtLeast(ROWS * ROW_MIN)
        val square = minOf(controls, SQUARE_MAX, (width - 8f - HUE_FULL - 16f - RIGHT_MIN).coerceAtLeast(64f))
        return FullPickerMetrics(HEADER, controls, square, (square / ROWS).coerceAtLeast(ROW_MIN), palette, recents)
    }
}

/** A colour channel: label, gradient track (drag = one transaction), in-place value. */
@Composable private fun ChannelRow(label: String, argb: Int, set: (Int) -> Int, fraction: Float, max: Int, tx: ColourTransaction,
    height: Dp, gradient: List<Color>, field: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(14.dp), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Canvas(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 6.dp).testTag("colour-slider-$label")
            .semantics { contentDescription = "$label channel"; progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress { tx.begin(); tx.preview(set((it.coerceIn(0f, 1f) * max).roundToInt())); tx.finish(); true } }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(); down.consume(); tx.begin()
                    fun update(x: Float) { tx.preview(set(((x / size.width).coerceIn(0f, 1f) * max).roundToInt())) }
                    try {
                        update(down.position.x)
                        if (horizontalDrag(down.id) { it.consume(); update(it.position.x) }) tx.finish() else tx.cancel()
                    } catch (e: CancellationException) { tx.cancel(); throw e }
                }
            }) {
            val track = 6.dp.toPx(); val y = center.y
            clipRect(0f, y - track / 2, size.width, y + track / 2) { checkerboard() }
            drawRoundRect(Brush.horizontalGradient(gradient), Offset(0f, y - track / 2), Size(size.width, track), CornerRadius(track / 2))
            val x = fraction.coerceIn(0f, 1f) * size.width
            drawCircle(Color.Black, 7.dp.toPx(), Offset(x, y), style = Stroke(3.dp.toPx()))
            drawCircle(Color.White, 7.dp.toPx(), Offset(x, y), style = Stroke(1.5.dp.toPx()))
        }
        field()
    }
}

/** One scrolling row of 28dp rounded swatches (the compact strip; FULL uses it for the palette and recents rows). */
@Composable internal fun SwatchStrip(swatches: List<Int>, current: Int, onPick: (Int) -> Unit, modifier: Modifier, testTag: String = "colour-swatches") {
    Row(modifier.horizontalScroll(rememberScrollState()).testTag(testTag), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        val opaque = 0xFF000000.toInt()
        swatches.forEach { argb ->
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(Color(argb))
                .border(1.dp, if ((argb or opaque) == (current or opaque)) TextPrimary else Border, RoundedCornerShape(7.dp))
                .clickable(role = Role.Button) { onPick(argb) }
                .semantics { contentDescription = "Colour #${"%06X".format(argb and 0xFFFFFF)}" })
        }
    }
}

/**
 * FULL palette area, below the controls: palette browser (existing management: select, create, rename, delete, edit
 * swatches), the active palette's swatches, save-current-colour, and (when tall enough) this session's recent colours.
 */
@Composable private fun PaletteArea(palette: PaletteRepository?, fallback: List<Int>, argb: Int, pick: (Int) -> Unit, m: FullPickerMetrics) {
    val library = palette?.state?.collectAsState()?.value
    val active = library?.activePalette
    var browser by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Toast.makeText(context, e.message ?: "Could not save palettes", Toast.LENGTH_LONG).show() }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxWidth().height(m.palette.dp).testTag("colour-palette-area")) {
        Row(Modifier.fillMaxWidth().height(ColourPickerLayout.PALETTE_ROW.dp), verticalAlignment = Alignment.CenterVertically) {
            if (palette != null && library != null) Box {
                InspectorIconButton(InspectorIcons.icon("PALETTES"), "Open palette browser") { browser = true }
                PaletteBrowser(browser, { browser = false }, library.palettes, library.activePaletteId, busy,
                    onSelectPalette = { id -> perform { palette.setActivePalette(id); browser = false } },
                    onCreatePalette = { perform { palette.createPalette() } },
                    onDeletePalette = { id -> perform { palette.deletePalette(id) } },
                    onRenamePalette = { id, name -> scope.launch {
                        try { palette.renamePalette(id, name) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { Toast.makeText(context, e.message ?: "Could not rename palette", Toast.LENGTH_LONG).show() }
                    } },
                    onAddSwatch = { id, sw -> perform { palette.addSwatch(id, sw) } },
                    onRemoveSwatch = { id, index -> perform { palette.removeSwatch(id, index) } },
                    onReplaceSwatch = { id, index, sw -> perform { palette.replaceSwatch(id, index, sw) } })
            }
            val shown = active?.swatches?.map { it.toArgb() }?.takeIf { it.isNotEmpty() } ?: if (palette == null) fallback else emptyList()
            SwatchStrip(shown, argb, pick, Modifier.weight(1f).fillMaxHeight().padding(horizontal = 4.dp), "colour-palette-swatches")
            if (palette != null) {
                val full = active != null && active.swatches.size >= ColorPalette.MAX_SWATCHES
                InspectorIconButton(InspectorIcons.icon("SAVE_SWATCH"), if (full) "Palette full (${ColorPalette.MAX_SWATCHES}/${ColorPalette.MAX_SWATCHES})" else "Save colour to active palette",
                    enabled = active != null && !full && !busy) { active?.let { perform { palette.addSwatch(it.id, FluxxColor.fromArgb(argb)) } } }
            }
        }
        if (m.recents) Row(Modifier.fillMaxWidth().height(ColourPickerLayout.PALETTE_ROW.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Recent", Modifier.width(48.dp).padding(start = 8.dp), color = TextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            SwatchStrip(ColourRecents.colours.toList(), argb, pick, Modifier.weight(1f).fillMaxHeight().padding(horizontal = 4.dp), "colour-recents")
        }
    }
}
