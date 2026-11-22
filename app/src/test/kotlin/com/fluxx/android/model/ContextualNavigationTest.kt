package com.fluxx.android.model

import com.fluxx.android.editor.AnimPropertyType
import com.fluxx.android.editor.ContextualNavigation
import com.fluxx.android.editor.*
import org.junit.Assert.*
import org.junit.Test

/**
 * TEXT F3.1 global Prev/Next rule: focused keys (only when keyed) → markers inside the layer span → layer in/out →
 * markers outside the span → timeline bounds; nothing selected: composition markers → timeline bounds.
 *
 * Fixture: the selected layer spans 2 s – 8 s (anchor 2 s). Position keys 3 s and 6 s, rotation keys 4 s and 5 s.
 * Layer markers at 2.5 s (inside), 0.5 s and 9 s (outside, kept after a trim); composition markers 4.5 s (inside) and
 * 9.5 s (outside). A second layer spans 4 s – 5 s; the composition is 10 s.
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

    private fun project(selected: CompositionLayer = layer(), markers: List<Marker> = listOf(Marker(10, 9 * S / 2), Marker(11, 19 * S / 2))) =
        ProjectDocument(Composition(durationUs = 10 * S, markers = FrozenList.of(markers),
            layers = FrozenList.of(listOf(selected, CompositionLayer(2, LayerType.SOLID, timing = ClipTiming(4 * S, 0, 1 * S))))))

    private fun next(p: ProjectDocument, l: CompositionLayer?, t: Long, property: AnimPropertyType? = null, open: Boolean = true,
        track: AnimatorTrackAddress? = null) = ContextualNavigation.findNext(p, l, open, property, t, null, track)
    private fun prev(p: ProjectDocument, l: CompositionLayer?, t: Long, property: AnimPropertyType? = null, open: Boolean = true,
        track: AnimatorTrackAddress? = null) = ContextualNavigation.findPrevious(p, l, open, property, t, null, track)

    @Test fun exactlyAtInAndOut() {
        val l = layer(); val p = project(l)
        // At in (2 s): next is the first inside marker; previous has nothing inside or at in/out, so the outside marker.
        assertEquals(5 * S / 2, next(p, l, 2 * S))
        assertEquals(S / 2, prev(p, l, 2 * S))
        // At out (8 s): previous is the last inside marker; next skips the inside tiers to the outside markers.
        assertEquals(9 * S / 2, prev(p, l, 8 * S))
        assertEquals(9 * S, next(p, l, 8 * S))
        assertEquals(19 * S / 2, next(p, l, 9 * S))
        assertEquals(10 * S, next(p, l, 19 * S / 2))
    }

    @Test fun markersInsideTheSpanComeBeforeInOutAndOutsideMarkers() {
        val l = layer(); val p = project(l)
        assertEquals(9 * S / 2, next(p, l, 3 * S))           // composition marker inside the span
        assertEquals(8 * S, next(p, l, 5 * S))               // inside markers exhausted → out point
        assertEquals(2 * S, prev(p, l, 5 * S / 2))           // inside exhausted backwards → in point
        assertEquals(S / 2, prev(p, l, 2 * S))               // in/out exhausted → layer marker kept before the trim
        assertEquals(0L, prev(p, l, S / 2))                  // outside exhausted → timeline start
    }

    @Test fun animatedFocusWinsAndFallsThroughWhenExhausted() {
        val l = layer(); val p = project(l)
        assertEquals(6 * S, next(p, l, 4 * S, AnimPropertyType.POSITION))       // the key beats the closer 4.5 s marker
        assertEquals(3 * S, prev(p, l, 4 * S, AnimPropertyType.POSITION))
        assertEquals(5 * S, next(p, l, 4 * S, AnimPropertyType.ROTATION))
        // Past the last key: the next tier (inside markers, then in/out) — markers are no longer blocked by a focus.
        assertEquals(8 * S, next(p, l, 13 * S / 2, AnimPropertyType.POSITION))
        assertEquals(6 * S, prev(p, l, 13 * S / 2, AnimPropertyType.POSITION))
        assertEquals(5 * S / 2, prev(p, l, 3 * S, AnimPropertyType.POSITION))
    }

    @Test fun staticFocusedPropertyIsTreatedAsUnfocused() {
        val l = layer(); val p = project(l)
        assertEquals(9 * S / 2, next(p, l, 3 * S, AnimPropertyType.OPACITY))
        val static = l.copy(animTransform = l.animTransform.copy(position = l.animTransform.position.copy(isAnimated = false)))
        assertEquals(next(project(static), static, 3 * S), next(project(static), static, 3 * S, AnimPropertyType.POSITION))
    }

    @Test fun sameRuleWithTheInspectorClosed() {
        val l = layer(); val p = project(l)
        // Closed: no focus, the other tiers are identical.
        assertEquals(9 * S / 2, next(p, l, 4 * S, AnimPropertyType.POSITION, open = false))
        for (t in listOf(0L, 2 * S, 3 * S, 8 * S, 9 * S)) {
            assertEquals(next(p, l, t), next(p, l, t, open = false)); assertEquals(prev(p, l, t), prev(p, l, t, open = false))
        }
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
        assertEquals(3 * S, next(p, text, 6 * S / 5, track = start))           // static field → unfocused → out point
        assertEquals(3 * S, next(p, text, 6 * S / 5, track = offset, open = false))
        assertEquals(3 * S, next(p, text, 6 * S / 5, track = TextAnimatorEdits.selectorField(1, 99, SelectorField.OFFSET)))
    }

    @Test fun nothingSelectedUsesCompositionMarkersThenBounds() {
        val p = project()
        assertEquals(9 * S / 2, next(p, null, 3 * S, open = false))
        assertEquals(4 * S, prev(p, null, 9 * S / 2, open = false))             // no earlier marker → layer 2 start
        assertEquals(10 * S, next(p, null, 19 * S / 2, open = false))
        assertEquals(19 * S / 2, prev(p, null, 12 * S, open = false))      // markers first, even past the end
        assertEquals(12 * S, next(p, null, 12 * S, open = false))               // Next stays put beyond the last bound
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
    }

    @Test fun overflowAndNegativeTimesCannotProduceInvalidSeeks() {
        val source = layer().copy(keyframeAnchorUs = Long.MAX_VALUE, markers = FrozenList.empty(),
            animTransform = AnimatableTransform(rotation = AnimatableProperty1D(isAnimated = true,
                keyframes = FrozenList.of(listOf(Keyframe1D(1, 0f))))))
        assertTrue(ContextualNavigation.keyframeTimes(source, AnimPropertyType.ROTATION).isEmpty())
        assertEquals(Long.MAX_VALUE, next(project(source), source, Long.MAX_VALUE, AnimPropertyType.ROTATION))
        val negative = source.copy(keyframeAnchorUs = -2)
        assertTrue(ContextualNavigation.keyframeTimes(negative, AnimPropertyType.ROTATION).isEmpty())
        // Layer markers before time zero are ignored.
        val early = layer().copy(markers = FrozenList.of(listOf(Marker(1, -5 * S))))
        assertEquals(0L, prev(project(early, emptyList()), early, 2 * S))
    }
}
