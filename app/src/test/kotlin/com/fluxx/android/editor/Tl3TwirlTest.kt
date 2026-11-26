package com.fluxx.android.editor

import com.fluxx.android.model.*
import org.junit.Assert.*
import org.junit.Test

/** TL3 B1: twirl-down contents, expansion rules and the offset table. */
class Tl3TwirlTest {
    private val rate = FrameRate(30, 1)
    private fun f(n: Long) = rate.frameTimeUs(n)
    private fun keyed1(vararg frames: Long) = AnimatableProperty1D(true, keyframes = FrozenList.of(frames.map { Keyframe1D(f(it), it.toFloat()) }))

    private val solid = CompositionLayer(1, LayerType.SOLID, timing = ClipTiming(0, 0, f(90)), animTransform = AnimatableTransform(
        position = AnimatableProperty2D(true, keyframes = FrozenList.of(listOf(Keyframe2D(f(0), 0f, 0f), Keyframe2D(f(30), 1f, 1f)))),
        opacity = keyed1(10, 20)))

    @Test fun rowsAreOnlyTheKeyedPropertiesInInspectorOrder() {
        val rows = TwirlRows.rows(solid, hasAudio = false)
        assertEquals(listOf("Position", "Opacity"), rows.map { (it as TwirlRow.Property).name })
        assertEquals(listOf(TwirlValueKind.VECTOR, TwirlValueKind.SCALAR), rows.map { (it as TwirlRow.Property).kind })
        // No keys at all: one dim "No animated properties" row; with audio, the audio row instead (first).
        val still = CompositionLayer(2, LayerType.SOLID, timing = ClipTiming(0, 0, f(90)))
        assertEquals(listOf(TwirlRow.Empty(2)), TwirlRows.rows(still, false))
        assertEquals(listOf(TwirlRow.Audio(2)), TwirlRows.rows(still, true))
        assertEquals(TwirlRow.Audio(1), TwirlRows.rows(solid, true).first())
        // The last key of a property deleted: its row disappears.
        val noOpacity = solid.copy(animTransform = solid.animTransform.copy(opacity = AnimatableProperty1D(staticValue = 1f)))
        assertEquals(listOf("Position"), TwirlRows.rows(noOpacity, false).map { (it as TwirlRow.Property).name })
    }

    @Test fun textLayersListTextPropertiesColoursAndAnimatorFieldsWithKeys() {
        val fill = AnimatableColour(true, keyframes = FrozenList.of(listOf(ColourKeyframe(f(0), -1), ColourKeyframe(f(30), 0xff000000.toInt()))))
        val animator = TextAnimator(5, name = "Fade", selectors = FrozenList.of(listOf(TextRangeSelector(9, start = keyed1(0, 15)))),
            properties = FrozenList.of(listOf(TextPropertyOverride(AnimatorProperty.OPACITY, scalar = keyed1(0, 30)),
                TextPropertyOverride(AnimatorProperty.SCALE, vector = AnimatableProperty2D(staticX = 1f, staticY = 1f)))))
        val text = CompositionLayer(3, LayerType.TEXT, timing = ClipTiming(0, 0, f(90)), text = TextProperties(fill = fill,
            tracking = keyed1(0, 10), animators = FrozenList.of(listOf(animator))))
        val rows = TwirlRows.rows(text, false).map { it as TwirlRow.Property }
        assertEquals(listOf("Fill colour", "Tracking", "Fade opacity", "Fade start"), rows.map { it.name })
        assertEquals(TwirlValueKind.COLOUR, rows[0].kind)
        assertEquals(GraphTarget(animator = AnimatorTrackAddress(5, property = AnimatorProperty.OPACITY)), rows[2].target)
        assertEquals(GraphTarget(animator = AnimatorTrackAddress(5, selectorId = 9, field = SelectorField.START)), rows[3].target)
        // Unique row keys per layer.
        assertEquals(rows.size, rows.map { it.key }.distinct().size)
    }

    @Test fun theArrowTogglesOneLayerOrEverySelectedLayer() {
        assertEquals(setOf(1L), TwirlExpansion.arrow(emptySet(), 1, null))
        assertEquals(emptySet<Long>(), TwirlExpansion.arrow(setOf(1L), 1, null))
        val sel = LayerSelection(listOf(1L, 2L, 3L))
        // The new state is the inverse of the TAPPED layer's state, for every selected layer.
        assertEquals(setOf(1L, 2L, 3L), TwirlExpansion.arrow(setOf(2L), 1, sel))
        assertEquals(setOf(4L), TwirlExpansion.arrow(setOf(1L, 2L, 4L), 1, sel))
        // Pruned when layers are removed.
        val project = ProjectDocument(Composition(layers = FrozenList.of(listOf(solid))))
        assertEquals(setOf(1L), TwirlExpansion.prune(setOf(1L, 9L), project))
    }

    @Test fun itemsInsertRowsUnderTheirLayerAndTheOffsetTableFindsRows() {
        val other = CompositionLayer(2, LayerType.SOLID, timing = ClipTiming(0, 0, f(90)))
        val items = TwirlRows.items(listOf(solid, other), setOf(1L)) { it.id == 2L }
        assertEquals(listOf("L1", "R", "R", "L2"), items.map { if (it is TwirlItem.Layer) "L${it.layer.id}" else "R" })
        val withAudio = TwirlRows.items(listOf(solid, other), setOf(1L, 2L)) { it.id == 2L }
        assertEquals(TwirlRow.Audio(2), (withAudio.last() as TwirlItem.Row).row)
        val offsets = TwirlRows.offsets(withAudio)
        assertEquals(floatArrayOf(0f, 28f, 56f, 84f, 112f, 144f).toList(), offsets.toList()) // TL3.1 B6: 32dp audio row
        assertEquals(0, TwirlRows.indexAt(offsets, 0f)); assertEquals(1, TwirlRows.indexAt(offsets, 28f))
        assertEquals(4, TwirlRows.indexAt(offsets, 130f)); assertEquals(4, TwirlRows.indexAt(offsets, 999f))
    }
}
