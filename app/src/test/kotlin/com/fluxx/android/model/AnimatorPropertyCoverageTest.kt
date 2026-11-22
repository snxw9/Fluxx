package com.fluxx.android.model

import com.fluxx.android.editor.*
import org.junit.Assert.*
import org.junit.Test

/**
 * TEXT F3.1 crash regression. A16 crash buffer (11-22 11:14 / 11:16, three occurrences):
 * `IllegalArgumentException: Character animation needs worker preparation` from PreparedTextAnimation.<init>
 * ← TextLayoutMetrics.prepareAnimation ← animation ← animatedBounds ← bounds ← EditorScreen (textCanFit), on the main
 * thread. The UI-side metrics are one edit behind the document (no character catalogue yet) when Character offset /
 * value is added. Every one of the 25 properties must add through the real reducer and prepare + evaluate.
 */
class AnimatorPropertyCoverageTest {
    private var serial = 100L
    private val ids = TextAnimatorEdits.Ids { serial++ }
    private val rate = FrameRate(30, 1)
    private val document = TextSourceDocument("AB")

    /** Two clusters, one line, 1000-unit metrics (the native ABI shape the JVM tests use). */
    private fun metrics(count: Int): DoubleArray {
        val out = ArrayList<Double>()
        out.addAll(listOf(1000.0, 800.0, -200.0, 0.0, 1.0, count.toDouble(), count * 600.0))
        for (i in 0 until count) out.addAll(listOf(0.0, i * 600.0, 0.0, 0.0, 700.0, 500.0, -700.0))
        out.add(count.toDouble()); for (i in 0 until count) out.add(i.toDouble())
        out.addAll(listOf(271828.0, count.toDouble(), count.toDouble(), 1.0))
        for (i in 0 until count) out.addAll(listOf(i.toDouble(), i + 1.0, 0.0, 0.0, i * 600.0, 600.0))
        for (i in 0 until count) out.addAll(listOf(0.0, 0.0, i.toDouble(), 0.0))
        for (i in 0 until count) out.add(i.toDouble())
        return out.toDoubleArray()
    }
    private fun state(): EditorState {
        val layer = CompositionLayer(1, LayerType.TEXT, timing = ClipTiming(durationUs = 4_000_000),
            text = TextProperties(source = AnimatableString(staticValue = "AB")))
        val s = EditorState(ProjectDocument(Composition(durationUs = 4_000_000, frameRate = rate, layers = FrozenList.of(listOf(layer)))), 1)
        return EditorReducer.reduce(s, TextAnimatorEdits.action(1, listOf(TextAnimatorEdits.addAnimator(layer.text, ids))))
    }
    private fun EditorState.text() = project.composition.layers.first().text
    private fun add(p: AnimatorProperty): TextProperties {
        val s = state(); val id = s.text().animators.single().id
        val next = EditorReducer.reduce(s, TextAnimatorEdits.action(1, listOfNotNull(TextAnimatorEdits.addProperty(s.text(), id, p))))
        return next.text().also { assertTrue(p.name, it.animators.single().properties.any { o -> o.property == p }) }
    }

    @Test fun everyPropertyAddsAndMeasuresOnTheUiPathWithoutACatalogue() {
        assertEquals(25, AnimatorProperty.entries.size)
        // The exact crash path: metrics without a worker catalogue, bounds/geometry on the UI thread.
        val metrics = TextLayoutMetrics.fromNative(metrics(2), false, document)
        for (p in AnimatorProperty.entries) {
            val text = add(p)
            for (t in listOf(0L, 500_000L, 3_999_999L)) {
                assertFalse(p.name, metrics.bounds(text, t).let { it.width.isNaN() || it.height.isNaN() })
                assertNotNull(p.name, metrics.geometry(text, t))
            }
        }
    }

    @Test fun everyPropertyPreparesAndEvaluatesWithTheWorkerCatalogue() {
        for (p in AnimatorProperty.entries) {
            val text = add(p)
            val catalogue = if (TextCharacterPreparation.needed(text)) {
                val cps = TextCharacterPreparation.codepoints(text, arrayOf(document))
                TextCharacterCatalogue(arrayOf(TextFontRef(text.fontId)), cps, arrayOf(TextNominalMetrics(cps, metrics(cps.size))))
            } else null
            val prepared = PreparedTextAnimation(text, metrics(2), SelectorEaseTable.shared, document, characterCatalogue = catalogue)
            for (t in listOf(0L, 1_000_000L)) prepared.evaluate(t)
            assertEquals(p.name, p == AnimatorProperty.CHARACTER_OFFSET || p == AnimatorProperty.CHARACTER_VALUE, prepared.characters != null)
            assertNull(p.name, TextCharacterPreparation.problem(text))
        }
    }

    @Test fun aStaleCatalogueThatMissesAReachedCodepointFallsBackInsteadOfThrowing() {
        // Catalogue prepared for Character offset 1, document now asks for offset 50: the UI path must not throw.
        val small = add(AnimatorProperty.CHARACTER_OFFSET)
        val offset = TextAnimatorEdits.property(small.animators.single().id, AnimatorProperty.CHARACTER_OFFSET)
        val one = TextAnimatorActions.payload(CompositionLayer(1, LayerType.TEXT, text = small), rate, TextAnimatorEdits.set(small, offset, 0, 1f))
        val cps = TextCharacterPreparation.codepoints(one, arrayOf(document))
        val catalogue = TextCharacterCatalogue(arrayOf(TextFontRef(one.fontId)), cps, arrayOf(TextNominalMetrics(cps, metrics(cps.size))))
        val metrics = TextLayoutMetrics.fromNative(metrics(2), false, document).withCharacterCatalogue(catalogue)
        val far = TextAnimatorActions.payload(CompositionLayer(1, LayerType.TEXT, text = one), rate, TextAnimatorEdits.set(one, offset, 0, 50f))
        assertNotNull(metrics.geometry(far, 0)); assertFalse(metrics.bounds(far, 0).width.isNaN())
    }

    @Test fun preCheckRefusesValuesBeyondTheCodepointAndCatalogueCaps() {
        val text = add(AnimatorProperty.CHARACTER_OFFSET)
        val address = TextAnimatorEdits.property(text.animators.single().id, AnimatorProperty.CHARACTER_OFFSET)
        fun at(v: Float) = TextAnimatorActions.payload(CompositionLayer(1, LayerType.TEXT, text = text), rate, TextAnimatorEdits.set(text, address, 0, v))
        assertNull(TextCharacterPreparation.problem(at(100f)))
        // "AB" ± 5,000 reaches more than 4,096 codepoints.
        assertTrue(TextCharacterPreparation.problem(at(5_000f))!!.contains("4096"))
        // Character value is absolute: a single wide key range is checked the same way.
        val value = add(AnimatorProperty.CHARACTER_VALUE)
        val v = TextAnimatorEdits.property(value.animators.single().id, AnimatorProperty.CHARACTER_VALUE)
        assertNull(TextCharacterPreparation.problem(value))
        val keyed = TextAnimatorActions.payload(CompositionLayer(1, LayerType.TEXT, timing = ClipTiming(durationUs = 4_000_000), text = value), rate,
            AnimatorEdit.Key(v, AnimatorKeyOperation.ENABLE, 0))
        val wide = TextAnimatorActions.payload(CompositionLayer(1, LayerType.TEXT, timing = ClipTiming(durationUs = 4_000_000), text = keyed), rate,
            AnimatorEdit.Key(v, AnimatorKeyOperation.SET, 1_000_000, value = 9_000f))
        assertNotNull(TextCharacterPreparation.problem(wide))
        // Fonts × codepoints over 16,384 while codepoints stay under 4,096: eight run fonts × ~2,100 reachable codepoints (A–H ± 2,100).
        val runs = TextSourceDocument("ABCDEFGH").let { d -> (0 until 8).fold(d) { doc, i -> doc.format(i, i + 1) { it.copy(font = TextFontRef("font.$i")) } } }
        val fonts = at(2_100f).copy(source = AnimatableString(staticValue = runs.text, document = runs))
        assertTrue(TextCharacterPreparation.problem(fonts)!!.contains("16384"))
        assertNull(TextCharacterPreparation.problem(fonts.copy(source = AnimatableString(staticValue = "ABCDEFGH"))))
        // What the layer renders instead: the same animators without Character overrides.
        assertFalse(TextCharacterPreparation.needed(TextCharacterPreparation.withoutCharacters(wide)))
        assertEquals(wide.animators.size, TextCharacterPreparation.withoutCharacters(wide).animators.size)
    }
}
