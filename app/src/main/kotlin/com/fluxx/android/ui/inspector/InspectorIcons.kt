package com.fluxx.android.ui.inspector

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.fluxx.android.model.ParagraphAlignment

/**
 * Builds icon path data on the shared 24-unit grid. Boxes are rounded rectangles, polygons have rounded
 * vertices, arrowheads are open chevrons (polylines). [mirror] flips x so OUT/Redo variants stay exact mirrors.
 */
internal class IconPath(private val mirror: Boolean = false) {
    private val out = StringBuilder()
    private fun n(v: Float): String {
        val r = Math.round(v * 100f) / 100f
        return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
    }
    private fun x(v: Float) = if (mirror) 24f - v else v
    private fun pt(px: Float, py: Float) = "${n(x(px))},${n(py)}"
    private fun sweep(s: Int) = if (mirror) 1 - s else s
    /** Open polyline through x,y pairs (round caps and joins come from the shared stroke). */
    fun line(vararg p: Float) = apply {
        require(p.size >= 4 && p.size % 2 == 0)
        out.append("M").append(pt(p[0], p[1]))
        for (i in 2 until p.size step 2) out.append(" L").append(pt(p[i], p[i + 1]))
        out.append(' ')
    }
    /** Rounded rectangle; corner radius is at least 2 units. */
    fun rect(left: Float, top: Float, w: Float, h: Float, radius: Float = 2.5f) = apply {
        val r = radius.coerceAtLeast(2f).coerceAtMost(minOf(w, h) / 2f)
        val l = if (mirror) 24f - left - w else left
        val ox = l; val right = l + w; val bottom = top + h
        fun p(a: Float, b: Float) = "${n(a)},${n(b)}"
        out.append("M").append(p(ox + r, top)).append(" L").append(p(right - r, top))
            .append(" A${n(r)},${n(r)} 0 0 1 ").append(p(right, top + r)).append(" L").append(p(right, bottom - r))
            .append(" A${n(r)},${n(r)} 0 0 1 ").append(p(right - r, bottom)).append(" L").append(p(ox + r, bottom))
            .append(" A${n(r)},${n(r)} 0 0 1 ").append(p(ox, bottom - r)).append(" L").append(p(ox, top + r))
            .append(" A${n(r)},${n(r)} 0 0 1 ").append(p(ox + r, top)).append(" Z ")
    }
    fun circle(cx: Float, cy: Float, r: Float) = apply {
        out.append("M").append(pt(cx - r, cy)).append(" A${n(r)},${n(r)} 0 1 1 ").append(pt(cx + r, cy))
            .append(" A${n(r)},${n(r)} 0 1 1 ").append(pt(cx - r, cy)).append(" Z ")
    }
    /** Elliptical arc from (x0,y0) to (x1,y1). */
    fun arc(x0: Float, y0: Float, rx: Float, ry: Float, large: Int, sweepFlag: Int, x1: Float, y1: Float) = apply {
        out.append("M").append(pt(x0, y0)).append(" A${n(rx)},${n(ry)} 0 $large ${sweep(sweepFlag)} ").append(pt(x1, y1)).append(' ')
    }
    fun cubic(x0: Float, y0: Float, c1x: Float, c1y: Float, c2x: Float, c2y: Float, x1: Float, y1: Float) = apply {
        out.append("M").append(pt(x0, y0)).append(" C").append(pt(c1x, c1y)).append(' ').append(pt(c2x, c2y)).append(' ').append(pt(x1, y1)).append(' ')
    }
    /** Closed polygon with every vertex rounded by [radius] (diamonds, triangles, hexagons). */
    fun poly(radius: Float, vararg p: Float) = apply {
        require(p.size >= 6 && p.size % 2 == 0)
        val count = p.size / 2
        fun vx(i: Int) = p[((i % count + count) % count) * 2]
        fun vy(i: Int) = p[((i % count + count) % count) * 2 + 1]
        fun toward(i: Int, j: Int): Pair<Float, Float> {
            val dx = vx(j) - vx(i); val dy = vy(j) - vy(i)
            val len = kotlin.math.sqrt(dx * dx + dy * dy)
            val t = if (len == 0f) 0f else (radius / len).coerceAtMost(.5f)
            return (vx(i) + dx * t) to (vy(i) + dy * t)
        }
        for (i in 0 until count) {
            val (ax, ay) = toward(i, i - 1); val (bx, by) = toward(i, i + 1)
            out.append(if (i == 0) "M" else " L").append(pt(ax, ay)).append(" Q").append(pt(vx(i), vy(i))).append(' ').append(pt(bx, by))
        }
        val (sx, sy) = toward(0, -1)
        out.append(" L").append(pt(sx, sy)).append(" Z ")
    }
    fun build() = out.toString().trim()
}

/**
 * The one inspector icon family. Every icon is a path on a 24×24 grid drawn with ONE stroke weight
 * ([STROKE] units, scaled with the icon size), round caps and round joins. Containers are rounded, arrowheads
 * are open chevrons, letters are simple geometric forms, and only state glyphs ([FILLED]) are filled.
 * Vectors are built once and cached. Tint always comes from the caller (monochrome theme tokens).
 */
internal object InspectorIcons {
    const val GRID = 24f
    const val STROKE = 1.5f
    val CAP = StrokeCap.Round
    val JOIN = StrokeJoin.Round
    /** Drawn size of every icon inside its 40/48dp tile; [INLINE] is the size beside text in chips and pills. */
    val SIZE = 22.dp
    val INLINE = 18.dp

    /** Deliberate aliases: one concept shown in two places. */
    val ALIASES = listOf(setOf("FILL", "PAINT"), setOf("CANCEL", "CLOSE"), setOf("DIRECTION", "LTR"), setOf("MISSING", "WARNING"),
        setOf("LINK", "SCALE_LINK"), setOf("DELETE", "DELETE_LAYER"))
    /** State glyphs: the only filled icons (active keyframe / hold, fill swatch). */
    val FILLED = setOf("FILL", "PAINT", "KEY_ON", "HOLD_ON")

    private fun p(mirror: Boolean = false) = IconPath(mirror)
    private fun aLetter(b: IconPath, l: Float, r: Float, top: Float, bottom: Float) = b.line(l, bottom, (l + r) / 2f, top, r, bottom)
        .line(l + (r - l) * .2f, top + (bottom - top) * .68f, r - (r - l) * .2f, top + (bottom - top) * .68f)
    private fun clipMove(m: Boolean) = p(m).rect(15f, 7f, 6f, 10f, 2f).line(3f, 12f, 12f, 12f).line(9.5f, 9.5f, 12f, 12f, 9.5f, 14.5f).build()
    private fun clipExtend(m: Boolean) = p(m).rect(12f, 7f, 9f, 10f, 2f).line(12f, 4f, 12f, 20f).line(3f, 12f, 9.5f, 12f).line(5.5f, 9.5f, 3f, 12f, 5.5f, 14.5f).build()
    private fun clipTrim(m: Boolean) = p(m).rect(4f, 7f, 17f, 10f, 2f).line(9f, 3f, 9f, 21f).line(5.5f, 10f, 7f, 11.5f).line(5.5f, 13.5f, 7f, 15f).build()
    private fun undo(m: Boolean) = p(m).line(9f, 5f, 5f, 9f, 9f, 13f).line(5f, 9f, 14f, 9f).arc(14f, 9f, 5f, 5f, 0, 1, 14f, 19f).line(14f, 19f, 10f, 19f).build()
    private fun boundary(m: Boolean) = p(m).line(6f, 5f, 6f, 19f).line(17f, 6f, 10.5f, 12f, 17f, 18f).build()
    private fun indent(m: Boolean) = p(m).line(4f, 4f, 4f, 20f).line(11f, 6f, 20f, 6f).line(11f, 12f, 20f, 12f).line(11f, 18f, 20f, 18f).line(6.5f, 9.5f, 9f, 12f, 6.5f, 14.5f).build()
    private fun direction(m: Boolean) = p(m).line(10f, 4f, 10f, 13f).line(14f, 4f, 14f, 13f).line(16f, 4f, 9f, 4f).arc(9f, 4f, 3f, 3f, 0, 0, 9f, 10f)
        .line(9f, 10f, 10f, 10f).line(4f, 18f, 20f, 18f).line(17.5f, 15.5f, 20f, 18f, 17.5f, 20.5f).build()
    private fun keyDiamond(b: IconPath) = b.poly(2f, 12f, 3f, 21f, 12f, 12f, 21f, 3f, 12f)

    /** Path data for every named icon. Unknown names fail loudly in tests (no silent default glyph). */
    val PATHS: Map<String, String> = buildMap {
        // ---- Navigation / chrome ------------------------------------------------------------------------
        put("BACK", p().line(15f, 5f, 8f, 12f, 15f, 19f).build())
        put("NEXT", p().line(9f, 5f, 16f, 12f, 9f, 19f).build())
        put("CLOSE", p().line(6f, 6f, 18f, 18f).line(18f, 6f, 6f, 18f).build()); put("CANCEL", getValue("CLOSE"))
        put("OVERFLOW", p().circle(12f, 5.5f, 1f).circle(12f, 12f, 1f).circle(12f, 18.5f, 1f).build())
        put("RESET", p().arc(5.5f, 12f, 6.5f, 6.5f, 1, 0, 8f, 6.9f).line(8.7f, 3.5f, 8f, 6.9f, 11.5f, 7f).build())
        put("INHERIT", p().line(6f, 4f, 6f, 11f).arc(6f, 11f, 4f, 4f, 0, 0, 10f, 15f).line(10f, 15f, 19f, 15f).line(15.5f, 11.5f, 19f, 15f, 15.5f, 18.5f).build())
        put("SEARCH", p().circle(10.5f, 10.5f, 6f).line(15f, 15f, 20f, 20f).build())
        put("IMPORT", p().line(12f, 3f, 12f, 14f).line(8f, 10f, 12f, 14f, 16f, 10f).line(4f, 15f, 4f, 18f).arc(4f, 18f, 2f, 2f, 0, 0, 6f, 20f)
            .line(6f, 20f, 18f, 20f).arc(18f, 20f, 2f, 2f, 0, 0, 20f, 18f).line(20f, 18f, 20f, 15f).build())
        put("DELETE", p().line(4f, 7f, 20f, 7f).line(9.5f, 7f, 9.5f, 5f).arc(9.5f, 5f, 1f, 1f, 0, 1, 10.5f, 4f).line(10.5f, 4f, 13.5f, 4f)
            .arc(13.5f, 4f, 1f, 1f, 0, 1, 14.5f, 5f).line(14.5f, 5f, 14.5f, 7f).line(6.5f, 7f, 7.3f, 18.2f).arc(7.3f, 18.2f, 2f, 2f, 0, 0, 9.3f, 20f)
            .line(9.3f, 20f, 14.7f, 20f).arc(14.7f, 20f, 2f, 2f, 0, 0, 16.7f, 18.2f).line(16.7f, 18.2f, 17.5f, 7f).line(10.5f, 11f, 10.5f, 16f).line(13.5f, 11f, 13.5f, 16f).build())
        put("DELETE_LAYER", getValue("DELETE"))
        put("DUPLICATE", p().rect(4f, 8f, 12f, 12f).line(8f, 8f, 8f, 6.5f).arc(8f, 6.5f, 2.5f, 2.5f, 0, 1, 10.5f, 4f).line(10.5f, 4f, 17.5f, 4f)
            .arc(17.5f, 4f, 2.5f, 2.5f, 0, 1, 20f, 6.5f).line(20f, 6.5f, 20f, 13.5f).arc(20f, 13.5f, 2.5f, 2.5f, 0, 1, 17.5f, 16f).line(17.5f, 16f, 16f, 16f)
            .line(7.5f, 14f, 12.5f, 14f).line(10f, 11.5f, 10f, 16.5f).build())
        put("AUDIO", p().poly(1.5f, 4f, 9.5f, 8f, 9.5f, 13f, 5f, 13f, 19f, 8f, 14.5f, 4f, 14.5f).arc(16.5f, 9f, 4.5f, 4.5f, 0, 1, 16.5f, 15f)
            .arc(19f, 6.5f, 8f, 8f, 0, 1, 19f, 17.5f).build())
        put("WARNING", p().poly(2.2f, 12f, 3.5f, 21f, 19.5f, 3f, 19.5f).line(12f, 9.5f, 12f, 13.5f).circle(12f, 16.5f, .6f).build()); put("MISSING", getValue("WARNING"))
        // ---- Colour picker family (palette browser, save current colour to the active palette) ----------------
        put("PALETTES", p().rect(4f, 4f, 7f, 7f, 2f).rect(13f, 4f, 7f, 7f, 2f).rect(4f, 13f, 7f, 7f, 2f).rect(13f, 13f, 7f, 7f, 2f).build())
        put("SAVE_SWATCH", p().rect(3f, 9f, 12f, 12f, 3f).line(18.5f, 3f, 18.5f, 11f).line(16f, 8.5f, 18.5f, 11f, 21f, 8.5f).build())
        put("EYEDROPPER", p().circle(17f, 7f, 2.6f).line(12.5f, 7.5f, 16.5f, 11.5f).line(14.5f, 9.5f, 6.5f, 17.5f, 5f, 19f).build())
        // ---- Rail ---------------------------------------------------------------------------------------
        put("KEY", keyDiamond(p()).build())
        put("KEY_ANIM", keyDiamond(p()).poly(1f, 12f, 9f, 15f, 12f, 12f, 15f, 9f, 12f).build())
        put("KEY_ON", getValue("KEY"))
        put("HOLD_ANIM", p().rect(5f, 5f, 14f, 14f, 3f).poly(1f, 12f, 9f, 15f, 12f, 12f, 15f, 9f, 12f).build())
        put("HOLD_ON", p().rect(5f, 5f, 14f, 14f, 3f).build())
        put("GRAPH", p().line(4f, 4f, 4f, 20f, 20f, 20f).cubic(6.5f, 17f, 11.5f, 17f, 11.5f, 7f, 19f, 7f).build())
        put("CUBE", p().poly(2f, 12f, 3f, 20f, 7.5f, 20f, 16.5f, 12f, 21f, 4f, 16.5f, 4f, 7.5f).line(4.6f, 7.9f, 12f, 12f, 19.4f, 7.9f).line(12f, 12f, 12f, 20.3f).build())
        // ---- Landing grid categories --------------------------------------------------------------------
        put("TRANSFORM", p().line(3f, 12f, 21f, 12f).line(12f, 3f, 12f, 21f).line(6f, 9f, 3f, 12f, 6f, 15f).line(18f, 9f, 21f, 12f, 18f, 15f)
            .line(9f, 6f, 12f, 3f, 15f, 6f).line(9f, 18f, 12f, 21f, 15f, 18f).build())
        put("TIMING", p().circle(12f, 12f, 8.5f).line(12f, 7f, 12f, 12f, 15.5f, 14f).build())
        put("BLENDING_OPACITY", p().circle(9.5f, 12f, 6f).circle(14.5f, 12f, 6f).build())
        put("EDIT_TEXT", p().rect(3f, 4f, 18f, 16f, 3f).line(8f, 9f, 16f, 9f).line(12f, 9f, 12f, 16f).build())
        put("EDIT_SOLID", p().circle(12f, 12f, 8.5f).circle(9f, 9f, 1f).circle(14.5f, 8.5f, 1f).circle(8f, 13.5f, 1f).build())
        put("EDIT_FOOTAGE", p().rect(3f, 5f, 18f, 14f, 3f).line(7f, 5f, 7f, 19f).line(17f, 5f, 17f, 19f).build())
        put("EFFECTS", p().line(12f, 3f, 12f, 8f).line(12f, 16f, 12f, 21f).line(3f, 12f, 8f, 12f).line(16f, 12f, 21f, 12f)
            .line(6f, 6f, 8.5f, 8.5f).line(15.5f, 15.5f, 18f, 18f).line(6f, 18f, 8.5f, 15.5f).line(15.5f, 8.5f, 18f, 6f).build())
        put("LAYER_STYLES", p().poly(1.5f, 12f, 4f, 20f, 8f, 12f, 12f, 4f, 8f).line(4f, 12f, 12f, 16f, 20f, 12f).line(4f, 16f, 12f, 20f, 20f, 16f).build())
        put("TRACK_MATTES", p().rect(3f, 3f, 12f, 12f, 3f).circle(15f, 15f, 6f).build())
        put("MOTION_BLUR", p().line(3f, 8f, 9f, 8f).line(3f, 12f, 7f, 12f).line(3f, 16f, 9f, 16f).circle(15.5f, 12f, 5f).build())
        // ---- Transform modes ----------------------------------------------------------------------------
        put("POSITION", p().line(12f, 3f, 12f, 8.5f).line(12f, 15.5f, 12f, 21f).line(3f, 12f, 8.5f, 12f).line(15.5f, 12f, 21f, 12f)
            .line(9.5f, 5.5f, 12f, 3f, 14.5f, 5.5f).line(9.5f, 18.5f, 12f, 21f, 14.5f, 18.5f).line(5.5f, 9.5f, 3f, 12f, 5.5f, 14.5f)
            .line(18.5f, 9.5f, 21f, 12f, 18.5f, 14.5f).circle(12f, 12f, 1.6f).build())
        put("ROTATION", p().arc(19f, 12f, 7f, 7f, 1, 1, 16.95f, 7.05f).line(16.6f, 3.6f, 16.95f, 7.05f, 13.5f, 6.8f).build())
        put("SCALE", p().line(5f, 19f, 19f, 5f).line(12.5f, 5f, 19f, 5f, 19f, 11.5f).line(5f, 12.5f, 5f, 19f, 11.5f, 19f).build())
        put("ANCHOR", p().circle(12f, 12f, 6f).circle(12f, 12f, 1.2f).line(12f, 3f, 12f, 6f).line(12f, 18f, 12f, 21f).line(3f, 12f, 6f, 12f).line(18f, 12f, 21f, 12f).build())
        put("LINK", p().rect(2.5f, 9f, 9.5f, 6f, 3f).rect(12f, 9f, 9.5f, 6f, 3f).line(8.5f, 12f, 15.5f, 12f).build()); put("SCALE_LINK", getValue("LINK"))
        put("LINK_OFF", p().rect(2.5f, 9f, 8f, 6f, 3f).rect(13.5f, 9f, 8f, 6f, 3f).line(12f, 5.5f, 12f, 7.5f).line(12f, 16.5f, 12f, 18.5f).build())
        // ---- Clip quick actions (OUT variants are exact mirrors) ---------------------------------------
        put("MOVE_IN", clipMove(false)); put("MOVE_OUT", clipMove(true))
        put("EXTEND_IN", clipExtend(false)); put("EXTEND_OUT", clipExtend(true))
        put("TRIM_IN", clipTrim(false)); put("TRIM_OUT", clipTrim(true))
        put("SPLIT", p().circle(6f, 7f, 2.5f).circle(6f, 17f, 2.5f).line(8f, 9f, 19f, 19f).line(8f, 15f, 19f, 5f).build())
        // ---- Editor action bar --------------------------------------------------------------------------
        put("UNDO", undo(false)); put("REDO", undo(true))
        put("PREV_KEY", boundary(false)); put("NEXT_KEY", boundary(true))
        put("PLAY", p().poly(2f, 7f, 4.5f, 19.5f, 12f, 7f, 19.5f).build())
        put("PAUSE", p().rect(6.5f, 5f, 4f, 14f, 2f).rect(13.5f, 5f, 4f, 14f, 2f).build())
        put("LAYER", p().rect(4f, 8f, 12f, 12f).line(8f, 8f, 8f, 6.5f).arc(8f, 6.5f, 2.5f, 2.5f, 0, 1, 10.5f, 4f).line(10.5f, 4f, 17.5f, 4f)
            .arc(17.5f, 4f, 2.5f, 2.5f, 0, 1, 20f, 6.5f).line(20f, 6.5f, 20f, 13.5f).arc(20f, 13.5f, 2.5f, 2.5f, 0, 1, 17.5f, 16f).line(17.5f, 16f, 16f, 16f).build())
        put("PASTE", p().rect(5f, 5f, 14f, 16f, 2.5f).rect(9f, 3f, 6f, 4f, 2f).line(9.5f, 14f, 14.5f, 14f).line(12f, 11.5f, 12f, 16.5f).build())
        put("SELECT_ALL", p().rect(4f, 4f, 16f, 16f, 3f).line(8f, 12f, 11f, 15f, 16f, 9f).build())
        // ---- Edit Text: Character ----------------------------------------------------------------------
        put("FONT", aLetter(p(), 3f, 13f, 6f, 19f).circle(17.5f, 15.5f, 3.5f).line(21f, 12f, 21f, 19f).build())
        put("AXIS", p().line(4f, 7f, 7f, 7f).line(11f, 7f, 20f, 7f).circle(9f, 7f, 2f).line(4f, 17f, 13f, 17f).line(17f, 17f, 20f, 17f).circle(15f, 17f, 2f).build())
        put("SIZE", p().line(3f, 10f, 10f, 10f).line(6.5f, 10f, 6.5f, 19f).line(11f, 5f, 21f, 5f).line(16f, 5f, 16f, 19f).build())
        put("FILL", p().rect(5f, 5f, 14f, 14f, 3.5f).build()); put("PAINT", getValue("FILL"))
        put("STROKE", p().rect(4f, 4f, 16f, 16f, 4f).rect(8f, 8f, 8f, 8f, 2f).build())
        put("STROKE_WIDTH", p().rect(3.5f, 5.5f, 11f, 13f, 3f).line(19f, 6f, 19f, 18f).line(17.5f, 6f, 20.5f, 6f).line(17.5f, 18f, 20.5f, 18f).build())
        put("TRACKING", aLetter(p(), 3f, 9f, 5f, 14f).line(15f, 5f, 18f, 14f, 21f, 5f).line(4f, 19f, 20f, 19f)
            .line(6.5f, 16.5f, 4f, 19f, 6.5f, 21.5f).line(17.5f, 16.5f, 20f, 19f, 17.5f, 21.5f).build())
        put("KERNING", aLetter(p(), 4f, 11f, 5f, 14f).line(10f, 5f, 13.5f, 14f, 17f, 5f).line(3f, 19f, 9f, 19f).line(7f, 17f, 9f, 19f, 7f, 21f)
            .line(21f, 19f, 15f, 19f).line(17f, 17f, 15f, 19f, 17f, 21f).build())
        put("LEADING", p().line(9f, 6f, 20f, 6f).line(9f, 12f, 20f, 12f).line(9f, 18f, 20f, 18f).line(4.5f, 5f, 4.5f, 19f)
            .line(2.5f, 7f, 4.5f, 5f, 6.5f, 7f).line(2.5f, 17f, 4.5f, 19f, 6.5f, 17f).build())
        put("BASELINE", aLetter(p(), 5f, 13f, 4f, 14f).line(3f, 19f, 21f, 19f).line(18f, 15.5f, 18f, 6f).line(16f, 8f, 18f, 6f, 20f, 8f).build())
        put("H_SCALE", p().line(7f, 4f, 17f, 4f).line(12f, 4f, 12f, 14f).line(4f, 19f, 20f, 19f).line(6.5f, 16.5f, 4f, 19f, 6.5f, 21.5f)
            .line(17.5f, 16.5f, 20f, 19f, 17.5f, 21.5f).build())
        put("V_SCALE", p().line(4f, 6f, 12f, 6f).line(8f, 6f, 8f, 19f).line(18f, 4f, 18f, 20f).line(15.5f, 6.5f, 18f, 4f, 20.5f, 6.5f)
            .line(15.5f, 17.5f, 18f, 20f, 20.5f, 17.5f).build())
        put("BOLD", p().line(7f, 5f, 7f, 19f).line(7f, 5f, 12.5f, 5f).arc(12.5f, 5f, 3.5f, 3.5f, 0, 1, 12.5f, 12f).line(7f, 12f, 13.5f, 12f)
            .arc(13.5f, 12f, 3.5f, 3.5f, 0, 1, 13.5f, 19f).line(13.5f, 19f, 7f, 19f).build())
        put("ITALIC", p().line(10f, 5f, 18f, 5f).line(6f, 19f, 14f, 19f).line(14f, 5f, 10f, 19f).build())
        put("UNDERLINE", p().line(7f, 4f, 7f, 11f).arc(7f, 11f, 5f, 5f, 0, 0, 17f, 11f).line(17f, 11f, 17f, 4f).line(5f, 20f, 19f, 20f).build())
        put("STRIKE", p().cubic(16.5f, 6.5f, 15.5f, 4.6f, 13.8f, 4f, 12f, 4f).cubic(12f, 4f, 9.5f, 4f, 7.5f, 5.5f, 7.5f, 7.6f)
            .cubic(7.5f, 7.6f, 7.5f, 9.8f, 9.6f, 10.7f, 11.5f, 11.2f).line(4f, 12.5f, 20f, 12.5f)
            .cubic(14f, 14f, 15.8f, 14.6f, 16.8f, 15.6f, 16.8f, 16.8f).cubic(16.8f, 16.8f, 16.8f, 18.8f, 14.6f, 20f, 12f, 20f)
            .cubic(12f, 20f, 9.7f, 20f, 8f, 19f, 7.2f, 17.4f).build())
        put("SUPER", p().line(4f, 10f, 11f, 19f).line(11f, 10f, 4f, 19f).arc(14.5f, 6f, 2.25f, 2.25f, 1, 1, 18.3f, 7.6f).line(18.3f, 7.6f, 14.5f, 11f, 19f, 11f).build())
        put("SUB", p().line(4f, 5f, 11f, 14f).line(11f, 5f, 4f, 14f).arc(14.5f, 14f, 2.25f, 2.25f, 1, 1, 18.3f, 15.6f).line(18.3f, 15.6f, 14.5f, 19f, 19f, 19f).build())
        put("ALL_CAPS", aLetter(aLetter(p(), 2.5f, 10.5f, 6f, 18f), 13.5f, 21.5f, 6f, 18f).build())
        put("LOWERCASE", p().circle(7f, 14.5f, 3.5f).line(10.5f, 11f, 10.5f, 18f).circle(16.5f, 14.5f, 3.5f).line(20f, 11f, 20f, 18f).build())
        put("LIGATURES", p().line(6f, 20f, 6f, 8.5f).arc(6f, 8.5f, 3.5f, 3.5f, 0, 1, 12.5f, 6.8f).line(3.5f, 11f, 10f, 11f).line(16f, 11f, 16f, 20f).circle(16f, 6.5f, .6f).build())
        put("ORDER", p().rect(4f, 4f, 11f, 11f).line(9f, 15f, 9f, 17.5f).arc(9f, 17.5f, 2.5f, 2.5f, 0, 0, 11.5f, 20f).line(11.5f, 20f, 17.5f, 20f)
            .arc(17.5f, 20f, 2.5f, 2.5f, 0, 0, 20f, 17.5f).line(20f, 17.5f, 20f, 11.5f).arc(20f, 11.5f, 2.5f, 2.5f, 0, 0, 17.5f, 9f).line(17.5f, 9f, 15f, 9f).build())
        put("EXPLODE", p().rect(3f, 3f, 7f, 7f, 2f).rect(14f, 5f, 7f, 7f, 2f).rect(7f, 14f, 7f, 7f, 2f).build())
        put("SOURCE", p().line(4f, 5f, 14f, 5f).line(9f, 5f, 9f, 19f).line(18f, 5f, 18f, 19f).line(16.5f, 4.5f, 19.5f, 4.5f).line(16.5f, 19.5f, 19.5f, 19.5f).build())
        // ---- Edit Text: Paragraph ----------------------------------------------------------------------
        put("INDENT_LEFT", indent(false)); put("INDENT_RIGHT", indent(true))
        put("INDENT_FIRST", p().line(4f, 4f, 4f, 20f).line(12f, 6f, 20f, 6f).line(7f, 12f, 20f, 12f).line(7f, 18f, 20f, 18f).line(6.5f, 4f, 8.5f, 6f, 6.5f, 8f).build())
        put("BEFORE", p().line(4f, 13f, 20f, 13f).line(4f, 18f, 16f, 18f).line(12f, 3f, 12f, 9f).line(10f, 5f, 12f, 3f, 14f, 5f).line(10f, 7f, 12f, 9f, 14f, 7f).build())
        put("AFTER", p().line(4f, 6f, 20f, 6f).line(4f, 11f, 16f, 11f).line(12f, 15f, 12f, 21f).line(10f, 17f, 12f, 15f, 14f, 17f).line(10f, 19f, 12f, 21f, 14f, 19f).build())
        put("POINT", p().line(5f, 6f, 15f, 6f).line(10f, 6f, 10f, 18f).circle(18.5f, 17.5f, 1.5f).build())
        put("AREA", p().rect(3f, 4f, 18f, 16f, 3f).line(7f, 9f, 17f, 9f).line(7f, 12.5f, 17f, 12.5f).line(7f, 16f, 13f, 16f).build())
        put("BOX", p().rect(3f, 5f, 18f, 14f, 3f).line(7f, 12f, 17f, 12f).line(9f, 10f, 7f, 12f, 9f, 14f).line(15f, 10f, 17f, 12f, 15f, 14f).build())
        put("LTR", direction(false)); put("DIRECTION", getValue("LTR")); put("RTL", direction(true))
    }

    /** Every name shown anywhere in the inspector, the editor action bar or the quick action bar. */
    val VISIBLE: List<String> get() = PATHS.keys.toList()

    fun path(name: String): String = PATHS[name] ?: error("No inspector icon named $name")
    /** Identity of a drawn glyph: path plus fill, so a filled and hollow diamond are different glyphs. */
    fun glyph(name: String) = path(name) + if (name in FILLED) "#filled" else ""

    private val cache = mutableMapOf<String, ImageVector>()
    fun icon(name: String): ImageVector = synchronized(cache) { cache.getOrPut(name) { build(name, path(name), name in FILLED) } }
    private fun build(name: String, data: String, filled: Boolean): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, GRID, GRID).addPath(PathParser().parsePathString(data).toNodes(),
            fill = if (filled) SolidColor(Color.Black) else null, stroke = SolidColor(Color.Black),
            strokeLineWidth = STROKE, strokeLineCap = CAP, strokeLineJoin = JOIN).build()

    /** Edit Text control → icon name (one place, so no control can silently fall back to a default glyph). */
    fun controlName(c: TextControl): String = when (c) {
        TextControl.ALIGN_LEFT, TextControl.ALIGN_CENTRE, TextControl.ALIGN_RIGHT, TextControl.JUSTIFY -> "ALIGN:${c.name}"
        TextControl.BOX_MODE -> "AREA"; TextControl.EXPLODE -> "EXPLODE"; TextControl.DIRECTION -> "LTR"
        else -> c.name
    }
    fun control(c: TextControl): ImageVector = when (c) {
        TextControl.ALIGN_LEFT -> alignment(ParagraphAlignment.LEFT); TextControl.ALIGN_CENTRE -> alignment(ParagraphAlignment.CENTRE)
        TextControl.ALIGN_RIGHT -> alignment(ParagraphAlignment.RIGHT); TextControl.JUSTIFY -> alignment(ParagraphAlignment.JUSTIFY_LAST_LEFT)
        else -> icon(controlName(c))
    }
    fun alignment(value: ParagraphAlignment): ImageVector =
        synchronized(cache) { cache.getOrPut("align.$value") { build("align.$value", alignmentPath(value), false) } }
    fun alignmentPath(value: ParagraphAlignment): String {
        val b = p()
        val justify = value.ordinal >= 3
        if (justify) b.line(4f, 5f, 20f, 5f).line(4f, 9.5f, 20f, 9.5f).line(4f, 14f, 20f, 14f)
        else when (value) {
            ParagraphAlignment.RIGHT -> b.line(4f, 5f, 20f, 5f).line(9f, 9.5f, 20f, 9.5f).line(6f, 14f, 20f, 14f)
            ParagraphAlignment.CENTRE -> b.line(4f, 5f, 20f, 5f).line(7f, 9.5f, 17f, 9.5f).line(5.5f, 14f, 18.5f, 14f)
            else -> b.line(4f, 5f, 20f, 5f).line(4f, 9.5f, 15f, 9.5f).line(4f, 14f, 18f, 14f)
        }
        when (value) {
            ParagraphAlignment.RIGHT, ParagraphAlignment.JUSTIFY_LAST_RIGHT -> b.line(10f, 18.5f, 20f, 18.5f)
            ParagraphAlignment.CENTRE, ParagraphAlignment.JUSTIFY_LAST_CENTRE -> b.line(7f, 18.5f, 17f, 18.5f)
            ParagraphAlignment.JUSTIFY_ALL -> b.line(4f, 18.5f, 20f, 18.5f)
            else -> b.line(4f, 18.5f, 14f, 18.5f)
        }
        return b.build()
    }
}
