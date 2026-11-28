package com.fluxx.android.render

import com.fluxx.android.model.*
import com.fluxx.android.render.UpcomingUse.Blocked
import com.fluxx.android.render.UpcomingUse.Clip
import com.fluxx.android.render.UpcomingUse.Input
import com.fluxx.android.render.UpcomingUse.Kind
import com.fluxx.android.render.UpcomingUse.Open
import com.fluxx.android.render.UpcomingUse.Warming
import org.junit.Assert.*
import org.junit.Test

/** MP2.1 B1: the warm planner's rules (pure). */
class UpcomingUseTest {
    private val mb = 1024L * 1024
    private val budget = 750 * mb
    private val dec = 120 * mb
    private fun clip(id: Long, uri: String, start: Long, dur: Long, inUs: Long = 0, cls: String = "avc 1920x1080") =
        Clip(id, uri, start, start + dur, inUs, cls, dec)
    private fun input(now: Long, clips: List<Clip>, playing: Boolean = true, open: List<Open> = emptyList(), warming: List<Warming> = emptyList(),
                      returns: Map<Long, Long> = emptyMap(), hidden: Set<Long> = emptySet(), cap: Int = 4, budgetBytes: Long = budget,
                      cost: (String) -> Long? = { null }) =
        Input(now, playing, clips, returns, hidden, open, warming, budgetBytes, cap, cost)

    @Test fun horizonIsTwiceTheMeasuredCostBetweenHalfASecondAndTwoAndAHalf() {
        assertEquals(1_500_000L, UpcomingUse.horizonUs(null))
        assertEquals(500_000L, UpcomingUse.horizonUs(100_000))   // 0.2 s -> the 0.5 s floor
        assertEquals(1_200_000L, UpcomingUse.horizonUs(600_000))
        assertEquals(2_500_000L, UpcomingUse.horizonUs(2_000_000)) // 4 s -> the 2.5 s cap
        // The measured cost of the clip's own class sets its horizon: VP9 measured slow is warmed earlier than AVC measured fast.
        val clips = listOf(clip(1, "a", 0, 10_000_000), clip(2, "b", 11_800_000, 2_000_000, cls = "avc"), clip(3, "c", 11_800_000, 2_000_000, cls = "vp9"))
        val p = UpcomingUse.plan(input(10_000_000, clips, cost = { if (it == "vp9") 1_000_000 else 200_000 }))
        assertEquals(listOf(3L), p.due.map { it.layerId })   // 1.8 s ahead: inside VP9's 2 s horizon, outside AVC's 0.5 s
    }

    @Test fun aContiguousContinuationOfTheOpenDecoderNeedsNone() {
        // MP1 layout (i): 2 s clips of one file, each in-point where the previous one ends.
        val seq = (0 until 30).map { k -> clip(k + 1L, "t", k * 2_000_000L, 2_000_000, inUs = k * 2_000_000L) }
        for (now in listOf(0L, 1_000_000L, 1_900_000L, 33_000_000L)) assertTrue(UpcomingUse.plan(input(now, seq)).due.isEmpty())
        // A little gap ahead (decodes forward) or a shared time still needs none; a jump does.
        val small = listOf(clip(1, "t", 0, 2_000_000), clip(2, "t", 2_000_000, 2_000_000, inUs = 2_080_000))
        assertTrue(UpcomingUse.plan(input(1_000_000, small)).due.isEmpty())
        val jump = listOf(clip(1, "t", 0, 2_000_000), clip(2, "t", 2_000_000, 2_000_000, inUs = 26_000_000))
        assertEquals(listOf(Kind.CUT), UpcomingUse.plan(input(1_000_000, jump)).due.map { it.kind })
    }

    @Test fun aNonContiguousCutWarmsTheNextClipAtItsInPoint() {
        // MP1 layout (ii): in-points (k * 13 mod 29) * 2 s; a trimmed clip seeks to its own in-point.
        val seq = (0 until 30).map { k -> clip(k + 1L, "t", k * 2_000_000L, 2_000_000, inUs = ((k * 13) % 29) * 2_000_000L) }
        val p = UpcomingUse.plan(input(1_000_000, seq))
        assertEquals(1, p.due.size)
        val w = p.due.single()
        assertEquals(2L, w.layerId); assertEquals(26_000_000L, w.sourceUs); assertEquals(2_000_000L, w.deadlineUs); assertEquals(dec, w.bytes)
        assertEquals(setOf(2L), p.wanted)
    }

    @Test fun theSourceTimeIsTheFramePlansOwnMapping() {
        // The model has no speed or stretch: the planner's mapping equals what FramePlan hands the renderer.
        val layer = CompositionLayer(7, LayerType.VIDEO, timing = ClipTiming(3_000_000, 41_500_000, 5_000_000), asset = AssetReference("a", "u", MediaAccess.AVAILABLE, 99_000_000))
        val c = clip(7, "u", 3_000_000, 5_000_000, inUs = 41_500_000)
        val project = ProjectDocument(Composition(durationUs = 10_000_000, layers = FrozenList.of(listOf(layer))))
        for (t in listOf(3_000_000L, 4_200_000L, 7_966_666L)) {
            var source = -1L
            FramePlan(project).forEachActive(t) { _, _, s, _ -> source = s }
            assertEquals(source, c.sourceAt(t))
        }
    }

    @Test fun aClipStartingExactlyAtTheHorizonIsDueOneMicrosecondLaterIsNot() {
        val now = 4_000_000L
        val at = listOf(clip(1, "a", 0, 10_000_000), clip(2, "b", now + UpcomingUse.DEFAULT_HORIZON_US, 2_000_000))
        assertEquals(now + UpcomingUse.DEFAULT_HORIZON_US, UpcomingUse.plan(input(now, at)).due.single().deadlineUs)
        val past = listOf(clip(1, "a", 0, 10_000_000), clip(2, "b", now + UpcomingUse.DEFAULT_HORIZON_US + 1, 2_000_000))
        assertTrue(UpcomingUse.plan(input(now, past)).due.isEmpty())
        // A clip that has already started is the current frame's business, not a warm-up.
        assertTrue(UpcomingUse.plan(input(now, listOf(clip(2, "b", now, 2_000_000)))).due.isEmpty())
    }

    @Test fun theBudgetRefusesAWarmUpThatDoesNotFit() {
        val clips = listOf(clip(1, "a", 0, 2_000_000), clip(2, "b", 2_000_000, 2_000_000))
        val open = listOf(Open("a", "a", 700 * mb, forFrame = true))
        val p = UpcomingUse.plan(input(1_000_000, clips, open = open))
        assertTrue(p.due.isEmpty())
        assertEquals(listOf(2L to Blocked.BUDGET), p.blocked.map { it.first.layerId to it.second })
        assertEquals(setOf(2L), p.wanted) // still wanted: nothing that is warming for it is released
        // Warming decoders count too.
        val p2 = UpcomingUse.plan(input(1_000_000, clips + clip(3, "c", 2_500_000, 1_000_000), open = listOf(Open("a", "a", 550 * mb, true))))
        assertEquals(listOf(2L), p2.due.map { it.layerId })
        assertEquals(listOf(3L to Blocked.BUDGET), p2.blocked.map { it.first.layerId to it.second })
    }

    @Test fun theCapRefusesAWarmUpCountingTheCurrentFramesDecodersAndThoseWarming() {
        val clips = listOf(clip(1, "a", 0, 4_000_000), clip(2, "b", 0, 4_000_000), clip(3, "c", 0, 4_000_000), clip(4, "d", 3_000_000, 2_000_000))
        val open = listOf(Open("a", "a", dec, true), Open("b", "b", dec, true), Open("c", "c", dec, true))
        assertEquals(listOf(4L), UpcomingUse.plan(input(2_000_000, clips, open = open)).due.map { it.layerId })
        val p = UpcomingUse.plan(input(2_000_000, clips + clip(5, "e", 3_100_000, 1_000_000), open = open))
        assertEquals(listOf(4L), p.due.map { it.layerId })
        assertEquals(listOf(5L to Blocked.CAP), p.blocked.map { it.first.layerId to it.second })
        // Low-RAM cap 2: one frame decoder plus one warming leaves no room.
        val low = UpcomingUse.plan(input(2_000_000, listOf(clip(1, "a", 0, 4_000_000), clip(5, "e", 3_100_000, 1_000_000)), open = listOf(Open("a", "a", dec, true)),
            warming = listOf(Warming(9, dec)), cap = 2))
        assertEquals(Blocked.CAP, low.blocked.single().second)
    }

    @Test fun pausedOrScrubbingNothingIsDueAndNothingIsWanted() {
        val clips = listOf(clip(1, "a", 0, 2_000_000), clip(2, "b", 2_000_000, 2_000_000))
        val p = UpcomingUse.plan(input(1_000_000, clips, playing = false, warming = listOf(Warming(2, dec))))
        assertTrue(p.due.isEmpty() && p.wanted.isEmpty() && p.keepUris.isEmpty())
        assertFalse(UpcomingUse.enabled(export = false, playing = false))
    }

    @Test fun exportNeverPlans() {
        assertFalse(UpcomingUse.enabled(export = true, playing = true))
        assertFalse(UpcomingUse.enabled(export = true, playing = false))
        assertTrue(UpcomingUse.enabled(export = false, playing = true))
    }

    @Test fun severalDueSoonestDeadlineFirstAndAWarmingOneIsNotStartedTwice() {
        val clips = listOf(clip(1, "a", 0, 3_000_000), clip(2, "b", 2_400_000, 1_000_000), clip(3, "c", 2_100_000, 1_000_000), clip(4, "d", 2_100_000, 1_000_000))
        val p = UpcomingUse.plan(input(1_000_000, clips))
        assertEquals(listOf(3L, 4L, 2L), p.due.map { it.layerId })
        val q = UpcomingUse.plan(input(1_000_000, clips, warming = listOf(Warming(3, dec))))
        assertEquals(listOf(4L, 2L), q.due.map { it.layerId })
        assertTrue(3L in q.wanted)
    }

    @Test fun aClipHiddenAtItsStartIsNotWarmedForTheCut() {
        val clips = listOf(clip(1, "a", 0, 2_000_000), clip(2, "b", 2_000_000, 2_000_000))
        assertTrue(UpcomingUse.plan(input(1_000_000, clips, hidden = setOf(2L))).due.isEmpty())
    }

    @Test fun reEntryIsWarmedHalfASecondBeforeTheLayerReturnsAndItsDecoderIsKept() {
        // A layer of source "m" culled now (t = 10 s) that the culling test says is visible again at 11.6 s.
        val clips = listOf(clip(1, "s", 0, 30_000_000), clip(2, "m", 0, 30_000_000, inUs = 5_000_000))
        val idle = Open("m", "m", dec, forFrame = false)
        val p = UpcomingUse.plan(input(10_000_000, clips, open = listOf(Open("s", "s", dec, true), idle), returns = mapOf(2L to 11_600_000L)))
        val w = p.due.single()
        assertEquals(Kind.REENTRY, w.kind)
        assertEquals(11_600_000L - Culling.WARM_LOOKAHEAD_US, w.deadlineUs)
        assertEquals(5_000_000L + w.deadlineUs, w.sourceUs)
        assertEquals("m", w.reuseKey); assertEquals(0L, w.bytes) // re-positions the pooled decoder: no second open, no new memory
        assertEquals(setOf("m"), p.keepUris)
        // Returning beyond the horizon: nothing yet, and the decoder is not kept (the 3 s hidden release applies).
        val far = UpcomingUse.plan(input(10_000_000, clips, open = listOf(idle), returns = mapOf(2L to 14_000_000L)))
        assertTrue(far.due.isEmpty() && far.keepUris.isEmpty())
        // Released already: a new decoder is opened (counts against the budget).
        val cold = UpcomingUse.plan(input(10_000_000, clips, returns = mapOf(2L to 11_600_000L)))
        assertEquals(null, cold.due.single().reuseKey); assertEquals(dec, cold.due.single().bytes)
        // A decoder the current frame uses is never taken for a re-entry.
        val busy = UpcomingUse.plan(input(10_000_000, clips, open = listOf(Open("m", "m", dec, forFrame = true)), returns = mapOf(2L to 11_600_000L)))
        assertEquals(null, busy.due.single().reuseKey)
    }

    @Test fun aReturnAfterTheClipEndsIsNotWarmed() {
        val clips = listOf(clip(2, "m", 0, 11_000_000))
        assertTrue(UpcomingUse.plan(input(10_000_000, clips, returns = mapOf(2L to 11_600_000L))).due.isEmpty())
    }
}
