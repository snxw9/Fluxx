package com.fluxx.android.ui.inspector

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import com.fluxx.android.editor.ClipQuickAction
import com.fluxx.android.model.LayerType
import com.fluxx.android.model.ParagraphAlignment
import com.fluxx.android.model.SelectorMode
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** One icon family for the whole inspector: one stroke, round caps/joins, rounded boxes, no Canvas-drawn icons. */
class InspectorIconFamilyTest {
    private fun paths(node: VectorNode): List<VectorPath> = when(node) {
        is VectorPath->listOf(node)
        is VectorGroup->node.flatMap {paths(it)}
        else->emptyList()
    }

    @Test fun everyIconUsesTheSharedStrokeCapAndJoin() {
        val names=InspectorIcons.VISIBLE
        assertTrue(names.size>=80)
        val vectors=names.map {InspectorIcons.icon(it)}+ParagraphAlignment.entries.map {InspectorIcons.alignment(it)}
        for(v in vectors) {
            assertEquals(24f,v.viewportWidth,0f);assertEquals(24f,v.viewportHeight,0f)
            val drawn=paths(v.root)
            assertTrue(v.name,drawn.isNotEmpty())
            assertEquals(v.name,if(v.name in InspectorIcons.REGIONS) 2 else 1,drawn.size)
            drawn.forEachIndexed {i,p->
                assertEquals(v.name,InspectorIcons.STROKE,p.strokeLineWidth,0f)
                assertEquals(v.name,StrokeCap.Round,p.strokeLineCap)
                assertEquals(v.name,StrokeJoin.Round,p.strokeLineJoin)
                // Hollow by default; only state glyphs are filled, and region glyphs fill only their second (region) path.
                assertEquals("${v.name} path $i",v.name in InspectorIcons.FILLED || i==1,p.fill!=null)
            }
        }
        assertEquals(setOf("FILL","PAINT","KEY_ON","HOLD_ON"),InspectorIcons.FILLED)
    }
    @Test fun noCanvasDrawnIconRemainsInTheInspectorOrActionBar() {
        val sources=File("src/main/kotlin/com/fluxx/android/ui/inspector").listFiles {f->f.extension=="kt"}!!.toList()+
            File("src/main/kotlin/com/fluxx/android/ui/editor/EditorActionBar.kt")
        assertTrue(sources.size>5)
        val function=Regex("""fun\s+(\w+)\s*\(""")
        for(file in sources) {
            // Split into top-level declarations; any function whose name says Icon must not draw with Canvas.
            for(chunk in file.readText().split(Regex("""\n(?=@Composable|private fun|internal fun|fun )"""))) {
                val name=function.find(chunk)?.groupValues?.get(1) ?: continue
                if(name.contains("Icon")) assertFalse("${file.name}: $name draws with Canvas",chunk.contains("Canvas("))
            }
            assertFalse("${file.name} still references the old icon object",file.readText().contains("TextInspectorIcons"))
        }
        assertFalse(File("src/main/kotlin/com/fluxx/android/ui/inspector/TextInspectorIcons.kt").exists())
    }
    @Test fun boxesAreRoundedAndThereAreNoSharpClosedPolygons() {
        for(name in InspectorIcons.VISIBLE) {
            val sub=InspectorIcons.path(name).split('M').filter {it.isNotBlank()}
            for(s in sub) if(s.trim().endsWith("Z")) assertTrue("$name has a sharp closed shape: M$s",s.contains('A') || s.contains('Q') || s.contains('C'))
        }
        for((name,region) in InspectorIcons.REGIONS) for(sub in region.split('M').filter {it.isNotBlank()})
            assertTrue("$name region is a closed rounded shape: M$sub",sub.trim().endsWith("Z") && (sub.contains('A') || sub.contains('Q')))
        // Rounded rectangles keep a corner radius of at least 2 units.
        assertTrue(IconPath().rect(4f,4f,16f,16f,.5f).build().contains("A2,2"))
    }
    @Test fun noTwoIconsShareAGlyphUnlessDeliberateAliases() {
        val names=InspectorIcons.VISIBLE
        for(i in names.indices) for(j in i+1 until names.size) {
            val a=names[i];val b=names[j]
            if(InspectorIcons.ALIASES.any {a in it && b in it}) continue
            assertNotEquals("$a looks identical to $b",InspectorIcons.glyph(a),InspectorIcons.glyph(b))
        }
        val scales=listOf("SIZE","V_SCALE","H_SCALE").map {InspectorIcons.path(it)}
        assertEquals(3,scales.toSet().size)
        val alignments=ParagraphAlignment.entries.map {InspectorIcons.alignmentPath(it)}
        assertEquals(alignments.size,alignments.toSet().size)
        for(gone in listOf("DONE","MINUS","PLUS","SMALL_CAPS")) assertFalse(gone,gone in names)
        // Mirrored pairs really are mirrors (same structure, different glyph).
        for((a,b) in listOf("UNDO" to "REDO","MOVE_IN" to "MOVE_OUT","INDENT_LEFT" to "INDENT_RIGHT","LTR" to "RTL"))
            assertEquals(InspectorIcons.path(a).count {it=='M'},InspectorIcons.path(b).count {it=='M'})
    }
    @Test fun selectorModeIconsAreDistinctFilledRegionGlyphs() {
        val modes=AnimateFields.MODES.map {it.icon}
        assertEquals(SelectorMode.entries.size,modes.size)
        assertEquals(setOf("MODE_ADD","MODE_SUBTRACT","MODE_INTERSECT","MODE_MIN","MODE_MAX","MODE_DIFFERENCE"),modes.toSet())
        for(name in modes) {
            assertTrue(name,name in InspectorIcons.REGIONS && name !in InspectorIcons.FILLED)
            // No text glyphs: the outline is two circles or two rounded bars, every closed shape rounded.
            assertTrue(name,InspectorIcons.path(name).split('M').filter {it.isNotBlank()}.all {it.trim().endsWith("Z") && it.contains('A')})
        }
        assertEquals(modes.size,modes.map {InspectorIcons.glyph(it)}.toSet().size)
        // Set modes share one outline and differ only in the filled region; min/max share the bars.
        assertEquals(1,listOf("MODE_ADD","MODE_SUBTRACT","MODE_INTERSECT","MODE_DIFFERENCE").map {InspectorIcons.path(it)}.toSet().size)
        assertEquals(InspectorIcons.path("MODE_MIN"),InspectorIcons.path("MODE_MAX"))
        // The difference glyph is two crescents (two closed sub-paths).
        assertEquals(2,InspectorIcons.REGIONS.getValue("MODE_DIFFERENCE").count {it=='Z'})
    }
    @Test fun everySurfaceResolvesToAFamilyIcon() {
        for(tab in TextTab.entries) for(c in TextInspectorLayout.inventory(tab)) {
            val name=InspectorIcons.controlName(c)
            if(!name.startsWith("ALIGN:")) assertTrue("$c → $name",name in InspectorIcons.PATHS)
            assertNotNull(InspectorIcons.control(c))
        }
        for(section in InspectorSection.entries) for(type in LayerType.entries) assertTrue(categoryIconName(section,type) in InspectorIcons.PATHS)
        for(kind in ClipQuickAction.entries) assertTrue(kind.name in InspectorIcons.PATHS)
        for(name in listOf("KEY","KEY_ANIM","KEY_ON","GRAPH","CUBE","OVERFLOW","BACK","DUPLICATE","DELETE_LAYER","AUDIO","POSITION","ROTATION","SCALE",
            "ANCHOR","LINK","LINK_OFF","RESET","EYEDROPPER","UNDO","REDO","PREV_KEY","NEXT_KEY","PLAY","PAUSE","LAYER","PASTE","SELECT_ALL"))
            assertTrue(name,name in InspectorIcons.PATHS)
        assertThrows(IllegalStateException::class.java) {InspectorIcons.path("NOPE")}
    }
}
