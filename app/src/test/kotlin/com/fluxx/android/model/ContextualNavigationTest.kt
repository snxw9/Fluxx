package com.fluxx.android.model

import com.fluxx.android.editor.AnimPropertyType
import com.fluxx.android.editor.ContextualNavigation
import org.junit.Assert.*
import org.junit.Test

class ContextualNavigationTest {
    private fun layer() = CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(2_000_000, 0, 6_000_000),
        animTransform = AnimatableTransform(
            position = AnimatableProperty2D(isAnimated = true, keyframes = FrozenList.of(listOf(
                Keyframe2D(1_000_000, 0f, 0f), Keyframe2D(4_000_000, 1f, 1f)))),
            rotation = AnimatableProperty1D(isAnimated = true, keyframes = FrozenList.of(listOf(
                Keyframe1D(2_000_000, 30f), Keyframe1D(3_000_000, 90f))))))

    private fun project(selected: CompositionLayer = layer()) = ProjectDocument(Composition(durationUs = 10_000_000,
        layers = FrozenList.of(listOf(selected, CompositionLayer(2, LayerType.SOLID, timing = ClipTiming(4_000_000, 0, 1_000_000))))))

    @Test fun focusedKeysWinOverCloserGlobalBoundariesAndOtherProperties() {
        val layer = layer(); val project = project(layer)
        assertEquals(3_000_000L, ContextualNavigation.findPrevious(project, layer, true, AnimPropertyType.POSITION, 5_500_000))
        assertEquals(6_000_000L, ContextualNavigation.findNext(project, layer, true, AnimPropertyType.POSITION, 3_500_000))
        assertEquals(4_000_000L, ContextualNavigation.findNext(project, layer, true, AnimPropertyType.ROTATION, 3_500_000))
    }

    @Test fun exhaustedDirectionFallsThroughUsingAFreshTier() {
        val layer = layer(); val project = project(layer)
        assertEquals(2_000_000L, ContextualNavigation.findPrevious(project, layer, true, AnimPropertyType.POSITION, 3_000_000))
        assertEquals(0L, ContextualNavigation.findPrevious(project, layer, true, AnimPropertyType.POSITION, 2_000_000))
        assertEquals(8_000_000L, ContextualNavigation.findNext(project, layer, true, AnimPropertyType.POSITION, 6_000_000))
        assertEquals(10_000_000L, ContextualNavigation.findNext(project, layer, true, AnimPropertyType.POSITION, 8_000_000))
    }

    @Test fun closedInspectorAndStaticPropertiesUseSelectedLayerBounds() {
        val layer = layer(); val project = project(layer)
        assertEquals(8_000_000L, ContextualNavigation.findNext(project, layer, false, AnimPropertyType.POSITION, 3_500_000))
        assertEquals(2_000_000L, ContextualNavigation.findPrevious(project, layer, true, AnimPropertyType.OPACITY, 5_500_000))
        val static = layer.copy(animTransform = layer.animTransform.copy(position = layer.animTransform.position.copy(isAnimated = false)))
        assertEquals(8_000_000L, ContextualNavigation.findNext(project(static), static, true, AnimPropertyType.POSITION, 3_500_000))
    }

    @Test fun noSelectionUsesAllBoundsIncludingCompositionEndInBothDirections() {
        val project = project()
        assertEquals(4_000_000L, ContextualNavigation.findNext(project, null, false, null, 3_500_000))
        assertEquals(5_000_000L, ContextualNavigation.findPrevious(project, null, false, null, 5_500_000))
        assertEquals(10_000_000L, ContextualNavigation.findPrevious(project, null, false, null, 12_000_000))
        assertEquals(12_000_000L, ContextualNavigation.findNext(project, null, false, null, 12_000_000))
    }

    @Test fun signedKeysUseTemporalAnchorRatherThanTrimmedInPoint() {
        val split = layer().copy(timing = ClipTiming(7_000_000, 0, 1_000_000), keyframeAnchorUs = 6_000_000,
            animTransform = AnimatableTransform(rotation = AnimatableProperty1D(isAnimated = true,
                keyframes = FrozenList.of(listOf(Keyframe1D(-2_000_000, 0f), Keyframe1D(1_000_000, 90f))))))
        assertEquals(4_000_000L, ContextualNavigation.findPrevious(project(split), split, true, AnimPropertyType.ROTATION, 7_000_000))
        assertEquals(7_000_000L, ContextualNavigation.findNext(project(split), split, true, AnimPropertyType.ROTATION, 4_000_000))
    }

    @Test fun overflowAndNegativeCompositionKeysCannotProduceInvalidSeeks() {
        val source = layer().copy(keyframeAnchorUs = Long.MAX_VALUE,
            animTransform = AnimatableTransform(rotation = AnimatableProperty1D(isAnimated = true,
                keyframes = FrozenList.of(listOf(Keyframe1D(1, 0f))))))
        assertTrue(ContextualNavigation.keyframeTimes(source, AnimPropertyType.ROTATION).isEmpty())
        assertEquals(Long.MAX_VALUE, ContextualNavigation.findNext(project(source), source, true, AnimPropertyType.ROTATION, Long.MAX_VALUE))
        val negative = source.copy(keyframeAnchorUs = -2)
        assertTrue(ContextualNavigation.keyframeTimes(negative, AnimPropertyType.ROTATION).isEmpty())
        assertEquals(0L, ContextualNavigation.findPrevious(ProjectDocument(), null, false, null, 0))
        assertEquals(0L, ContextualNavigation.findNext(ProjectDocument(), null, false, null, 0))
    }
}
