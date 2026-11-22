package com.fluxx.android.model

import com.fluxx.android.editor.AnimPropertyType
import com.fluxx.android.editor.ContextualNavigation
import com.fluxx.android.editor.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Global Prev/Next rule, TEXT F3.2 tier 1: the focused field's keys while it is keyed, otherwise ALL keys of the
 * selected layer → markers inside the layer span → layer in/out → markers outside the span → timeline bounds;
 * nothing selected: composition markers → timeline bounds.
 *
 * Fixture: the selected layer spans 2 s – 8 s (anchor 2 s). Position keys 3 s and 6 s, rotation keys 4 s and 5 s
 * (layer keys 3, 4, 5, 6 s). Layer markers at 2.5 s (inside), 0.5 s and 9 s (outside, kept after a trim); composition
 * markers 4.5 s (inside) and 9.5 s (outside). A second layer spans 4 s – 5 s; the composition is 10 s.
 */
class ContextualNavigationTest {
    private val S = 1_000_000L
    private fun layer() = CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(2 * S, 0, 6 * S),
        markers = FrozenList.of(listOf(Marker(2, -3 * S / 2), Marker(1, S / 2), Marker(3, 7 * S))),
        animTransform = AnimatableTransform(
            position = AnimatableProperty2D(isAnimated = true, keyframes = FrozenList.of(listOf(
                Keyframe2D(1 * S, 0f, 0f), Keyframe2D(4 * S, 1f, 1f)))),
            rotation = AnimatableProperty1D(isAnimated = true, keyframes = FrozenList.of(listOf(
                Keyframe1D(2 * S, 30f), Keyframe1D(3 * S, 90f))))))
    /** The same layer without keys: tier 1 is empty, so tiers 2–5 decide. */
    private fun bare() = layer().copy(animTransform = AnimatableTransform())

    private fun project(selected: CompositionLayer = layer(), markers: List<Marker> = listOf(Marker(10, 9 * S / 2), Marker(11, 19 * S / 2))) =
        ProjectDocument(Composition(durationUs = 10 * S, markers = FrozenList.of(markers),
            layers = FrozenList.of(listOf(selected, CompositionLayer(2, LayerType.SOLID, timing = ClipTiming(4 * S, 0, 1 * S))))))

    private fun next(p: ProjectDocument, l: CompositionLayer?, t: Long, property: AnimPropertyType? = null, open: Boolean = true,
        track: AnimatorTrackAddress? = null) = ContextualNavigation.findNext(p, l, open, property, t, null, track)
    private fun prev(p: ProjectDocument, l: CompositionLayer?, t: Long, property: AnimPropertyType? = null, open: Boolean = true,
        track: AnimatorTrackAddress? = null) = ContextualNavigation.findPrevious(p, l, open, property, t, null, track)
    private fun walk(p: ProjectDocument, l: CompositionLayer?, from: Long, forward: Boolean, steps: Int, property: AnimPropertyType? = null,
        open: Boolean = true, track: AnimatorTrackAddress? = null): List<Long> {
        var t = from
        return List(steps) { t = if (forward) next(p, l, t, property, open, track) else prev(p, l, t, property, open, track); t }
    }

    @Test fun unfocusedLayerHopsBetweenAllItsKeysFirst() {
        val l = layer(); val p = project(l)
        // Next from the in point: every layer key (both properties), then the remaining tiers.
        assertEquals(listOf(3 * S, 4 * S, 5 * S, 6 * S, 8 * S, 9 * S, 19 * S / 2, 10 * S), walk(p, l, 2 * S, true, 8))
        // Previous from the out point: keys backwards, then in point, then outside markers and the start.
        assertEquals(listOf(6 * S, 5 * S, 4 * S, 3 * S, 5 * S / 2, 2 * S, S / 2, 0L), walk(p, l, 8 * S, false, 8))
    }

    @Test fun exactlyAtInAndOutWithoutKeys() {
        val l = bare(); val p = project(l)
        assertEquals(5 * S / 2, next(p, l, 2 * S))
        assertEquals(S / 2, prev(p, l, 2 * S))
        assertEquals(9 * S / 2, prev(p, l, 8 * S))
        assertEquals(9 * S, next(p, l, 8 * S))
        assertEquals(19 * S / 2, next(p, l, 9 * S))
        assertEquals(10 * S, next(p, l, 19 * S / 2))
    }

    @Test fun tiersFallThroughInOrder() {
        val l = bare(); val p = project(l)
        assertEquals(9 * S / 2, next(p, l, 3 * S))           // composition marker inside the span
        assertEquals(8 * S, next(p, l, 5 * S))               // inside markers exhausted → out point
        assertEquals(2 * S, prev(p, l, 5 * S / 2))           // inside exhausted backwards → in point
        assertEquals(S / 2, prev(p, l, 2 * S))               // in/out exhausted → layer marker kept before the trim
        assertEquals(0L, prev(p, l, S / 2))                  // outside exhausted → timeline start
        // With keys: an exhausted key tier falls through to the same tiers.
        val keyed = layer(); val k = project(keyed)
        assertEquals(8 * S, next(k, keyed, 6 * S))           // past the last key → no inside marker later → out point
        assertEquals(5 * S / 2, prev(k, keyed, 3 * S))       // before the first key → inside marker
    }

    @Test fun keyedFocusOnlyUsesItsOwnKeys() {
        val l = layer(); val p = project(l)
        assertEquals(6 * S, next(p, l, 4 * S, AnimPropertyType.POSITION))       // skips the rotation key at 5 s
        assertEquals(3 * S, prev(p, l, 4 * S, AnimPropertyType.POSITION))
        assertEquals(5 * S, next(p, l, 4 * S, AnimPropertyType.ROTATION))
        // Past the focused property's last key: the next tier (not the other property's keys).
        assertEquals(8 * S, next(p, l, 13 * S / 2, AnimPropertyType.POSITION))
        assertEquals(6 * S, prev(p, l, 13 * S / 2, AnimPropertyType.POSITION))
        assertEquals(5 * S / 2, prev(p, l, 3 * S, AnimPropertyType.POSITION))
        assertEquals(8 * S, next(p, l, 5 * S, AnimPropertyType.ROTATION))        // not position's 6 s key
    }

    @Test fun focusedWithoutKeysFallsToLayerWideKeys() {
        val l = layer(); val p = project(l)
        assertEquals(4 * S, next(p, l, 3 * S, AnimPropertyType.OPACITY))          // static opacity → every layer key
        assertEquals(listOf(3 * S, 4 * S, 5 * S, 6 * S), walk(p, l, 2 * S, true, 4, AnimPropertyType.OPACITY))
        val static = l.copy(animTransform = l.animTransform.copy(position = l.animTransform.position.copy(isAnimated = false)))
        assertEquals(next(project(static), static, 3 * S), next(project(static), static, 3 * S, AnimPropertyType.POSITION))
        assertEquals(4 * S, next(project(static), static, 3 * S, AnimPropertyType.POSITION)) // the rotation keys remain
    }

    @Test fun sameRuleWithTheInspectorClosed() {
        val l = layer(); val p = project(l)
        // Closed: no focus, so the layer-wide keys.
        assertEquals(5 * S, next(p, l, 4 * S, AnimPropertyType.POSITION, open = false))
        for (t in listOf(0L, 2 * S, 3 * S, 9 * S / 2, 8 * S, 9 * S)) {
            assertEquals(next(p, l, t), next(p, l, t, open = false)); assertEquals(prev(p, l, t), prev(p, l, t, open = false))
        }
    }

    /** A text layer with keys on many kinds of fields, each at its own time (local = absolute − 1 s anchor). */
    private fun textLayer(): CompositionLayer {
        fun keyed(vararg t: Long) = AnimatableProperty1D(true, 0f, FrozenList.of(t.map { Keyframe1D(it, 0f) }))
        val a = TextAnimator(1,
            enabled = AnimatableProperty1D(true, 1f, FrozenList.of(listOf(Keyframe1D(7 * S / 10, 1f, EasingPreset.LINEAR)))), // 1.7 s
            selectors = FrozenList.of(listOf(TextRangeSelector(11, offset = keyed(S / 2),                                     // 1.5 s
                seedKeyframes = FrozenList.of(listOf(TextSeedKeyframe(3 * S / 10, 0), TextSeedKeyframe(9 * S / 10, 25)))))), // 1.3 s, 1.9 s
            properties = FrozenList.of(listOf(TextPropertyOverride(AnimatorProperty.OPACITY, scalar = keyed(S / 5))))) // 1.2 s
        return CompositionLayer(1, LayerType.TEXT, timing = ClipTiming(S, 0, 2 * S), text = TextProperties(
            size = AnimatableProperty1D(true, 72f, FrozenList.of(listOf(Keyframe1D(S / 10, 72f)))),                       // 1.1 s
            animators = FrozenList.of(listOf(a))),
            animTransform = AnimatableTransform(rotation = AnimatableProperty1D(isAnimated = true,
                keyframes = FrozenList.of(listOf(Keyframe1D(4 * S / 10, 0f))))))                                           // 1.4 s
    }

    @Test fun layerWideKeysCoverTransformTextAnimatorSelectorSeedAndEnabled() {
        val text = textLayer()
        val p = ProjectDocument(Composition(durationUs = 10 * S, layers = FrozenList.of(listOf(text))))
        val keys = listOf(11, 12, 13, 14, 15, 17, 19).map { it * S / 10 }
        assertEquals(keys, ContextualNavigation.layerKeys(text).sorted())
        // Nothing focused: every key in order, then the out point, then the end.
        assertEquals(keys + listOf(3 * S, 10 * S), walk(p, text, S, true, keys.size + 2, open = false))
        assertEquals(keys.reversed() + listOf(S, 0L), walk(p, text, 2 * S, false, keys.size + 2))
        // A focused, keyed animator field still uses only its own keys.
        val seed = TextAnimatorEdits.seed(1, 11)
        assertEquals(listOf(13 * S / 10, 19 * S / 10), walk(p, text, S, true, 2, track = seed))
        // A focused static field (Start) falls back to the layer-wide keys.
        val start = TextAnimatorEdits.selectorField(1, 11, SelectorField.START)
        assertEquals(11 * S / 10, next(p, text, S, track = start))
    }

    @Test fun animatorFieldWithAndWithoutKeys() {
        val keyed = AnimatableProperty1D(true, 0f, FrozenList.of(listOf(Keyframe1D(0, 0f), Keyframe1D(S / 2, 100f))))
        val a = TextAnimator(1, selectors = FrozenList.of(listOf(TextRangeSelector(11, offset = keyed))))
        val text = CompositionLayer(1, LayerType.TEXT, timing = ClipTiming(S, 0, 2 * S), text = TextProperties(animators = FrozenList.of(listOf(a))))
        val p = ProjectDocument(Composition(durationUs = 10 * S, layers = FrozenList.of(listOf(text))))
        val offset = TextAnimatorEdits.selectorField(1, 11, SelectorField.OFFSET)
        val start = TextAnimatorEdits.selectorField(1, 11, SelectorField.START)
        assertEquals(3 * S / 2, next(p, text, 6 * S / 5, track = offset))
        assertEquals(S, prev(p, text, 6 * S / 5, track = offset))
        assertEquals(3 * S / 2, next(p, text, 6 * S / 5, track = start))       // static field → layer keys
        assertEquals(3 * S / 2, next(p, text, 6 * S / 5, track = offset, open = false))
        assertEquals(3 * S / 2, next(p, text, 6 * S / 5, track = TextAnimatorEdits.selectorField(1, 99, SelectorField.OFFSET)))
        assertEquals(3 * S, next(p, text, 3 * S / 2, track = start))            // keys exhausted → out point
    }

    @Test fun nothingSelectedUsesCompositionMarkersThenBounds() {
        val p = project()
        assertEquals(9 * S / 2, next(p, null, 3 * S, open = false))
        assertEquals(4 * S, prev(p, null, 9 * S / 2, open = false))             // no earlier marker → layer 2 start
        assertEquals(10 * S, next(p, null, 19 * S / 2, open = false))
        assertEquals(19 * S / 2, prev(p, null, 12 * S, open = false))      // markers first, even past the end
        assertEquals(12 * S, next(p, null, 12 * S, open = false))               // Next stays put beyond the last bound
        // Layer keys never apply without a selection.
        assertEquals(9 * S / 2, next(p, null, 3 * S, AnimPropertyType.POSITION))
    }

    @Test fun emptyProjects() {
        assertEquals(0L, ContextualNavigation.findPrevious(ProjectDocument(), null, false, null, 0))
        assertEquals(0L, ContextualNavigation.findNext(ProjectDocument(), null, false, null, 0))
        val bare = CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(0, 0, 2 * S))
        val p = ProjectDocument(Composition(durationUs = 2 * S, layers = FrozenList.of(listOf(bare))))
        assertEquals(2 * S, next(p, bare, 0)); assertEquals(0L, prev(p, bare, S))
    }

    @Test fun signedKeysUseTemporalAnchorRatherThanTrimmedInPoint() {
        val split = layer().copy(timing = ClipTiming(7 * S, 0, 1 * S), keyframeAnchorUs = 6 * S, markers = FrozenList.empty(),
            animTransform = AnimatableTransform(rotation = AnimatableProperty1D(isAnimated = true,
                keyframes = FrozenList.of(listOf(Keyframe1D(-2 * S, 0f), Keyframe1D(1 * S, 90f))))))
        assertEquals(4 * S, prev(project(split, emptyList()), split, 7 * S, AnimPropertyType.ROTATION))
        assertEquals(7 * S, next(project(split, emptyList()), split, 4 * S, AnimPropertyType.ROTATION))
        assertEquals(4 * S, prev(project(split, emptyList()), split, 7 * S))      // unfocused: the same keys
    }

    @Test fun overflowAndNegativeTimesCannotProduceInvalidSeeks() {
        val source = layer().copy(keyframeAnchorUs = Long.MAX_VALUE, markers = FrozenList.empty(),
            animTransform = AnimatableTransform(rotation = AnimatableProperty1D(isAnimated = true,
                keyframes = FrozenList.of(listOf(Keyframe1D(1, 0f))))))
        assertTrue(ContextualNavigation.keyframeTimes(source, AnimPropertyType.ROTATION).isEmpty())
        assertTrue(ContextualNavigation.layerKeys(source).isEmpty())
        assertEquals(Long.MAX_VALUE, next(project(source), source, Long.MAX_VALUE, AnimPropertyType.ROTATION))
        val negative = source.copy(keyframeAnchorUs = -2)
        assertTrue(ContextualNavigation.keyframeTimes(negative, AnimPropertyType.ROTATION).isEmpty())
        assertTrue(ContextualNavigation.layerKeys(negative).isEmpty())
        // Layer markers before time zero are ignored.
        val early = bare().copy(markers = FrozenList.of(listOf(Marker(1, -5 * S))))
        assertEquals(0L, prev(project(early, emptyList()), early, 2 * S))
    }
}
