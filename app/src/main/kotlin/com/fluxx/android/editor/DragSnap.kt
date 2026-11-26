package com.fluxx.android.editor

import com.fluxx.android.model.CompositionTimecode
import com.fluxx.android.model.FrameRate
import com.fluxx.android.model.ProjectDocument
import kotlin.math.abs

/**
 * TLP.2 part 4: what a dragged layer / key snaps to, in tie-break PRIORITY order (the enum order): playhead > marker >
 * clip edge > composition bound > neighbour key > ruler tick. [NEIGHBOUR] only exists for the single-key drag (its
 * existing targets: the other keys of the property and its own clip's start / end).
 */
enum class SnapKind { PLAYHEAD, MARKER, CLIP_EDGE, BOUND, NEIGHBOUR, TICK }

/** A snap target at composition time [timeUs]. */
data class SnapTarget(val timeUs: Long, val kind: SnapKind)

/** The snapped drag: the delta to apply to the WHOLE selection and the target it locked to (null: not snapped). */
data class SnapResult(val deltaUs: Long, val target: SnapTarget?)

/**
 * TLP.2 part 4 drag snapping (pure, JVM tested). Every candidate on the dragged side (a layer's start and end, every
 * selected layer's start and end, a key's time, every selected key's time; composition times at the drag's start) is
 * paired with every target; the pair with the smallest distance inside [zoneUs] wins, and the whole selection shifts by
 * `target - candidate`, so relative offsets are kept exactly. Ties go to the higher-priority [SnapKind]. Frame
 * quantisation and the >= 0 clamps are applied afterwards by the existing move builders, so a clamp always wins.
 */
object DragSnap {
    /** The snap zone: the existing key-drag zone (12dp in `TimelineClipRow`), now named and shared by every drag. */
    const val ZONE_DP = 12f

    fun snap(rawDeltaUs: Long, candidates: List<Long>, targets: List<SnapTarget>, zoneUs: Long): SnapResult {
        var best: SnapTarget? = null; var bestDelta = rawDeltaUs; var bestDistance = Long.MAX_VALUE
        for (c in candidates) {
            val moved = Math.addExact(c, rawDeltaUs)
            for (t in targets) {
                val d = abs(t.timeUs - moved)
                if (d > zoneUs) continue
                if (d < bestDistance || (d == bestDistance && best != null && t.kind.ordinal < best.kind.ordinal)) {
                    best = t; bestDistance = d; bestDelta = t.timeUs - c
                }
            }
        }
        return SnapResult(bestDelta, best)
    }

    /**
     * The shared targets of a drag in [project]: the playhead, composition markers, the markers of layers that are not
     * dragged ([dragged] ids; a dragged layer's own markers move with it), and the composition start (0) and end. For a
     * LAYER drag ([clipEdges]) also the start and end of every layer that is not dragged.
     */
    fun targets(project: ProjectDocument, playheadUs: Long, dragged: Set<Long>, clipEdges: Boolean): List<SnapTarget> = buildList {
        val comp = project.composition
        add(SnapTarget(playheadUs, SnapKind.PLAYHEAD))
        for (m in comp.markers) add(SnapTarget(m.timeUs, SnapKind.MARKER))
        for (l in comp.layers) if (l.id !in dragged) {
            for (m in l.markers) add(SnapTarget(Math.addExact(l.resolvedKeyframeAnchorUs, m.timeUs), SnapKind.MARKER))
            if (clipEdges) {
                add(SnapTarget(l.timing.startUs, SnapKind.CLIP_EDGE))
                l.timing.endUs?.let { add(SnapTarget(it, SnapKind.CLIP_EDGE)) }
            }
        }
        add(SnapTarget(0, SnapKind.BOUND))
        add(SnapTarget(comp.resolvedDurationUs, SnapKind.BOUND))
    }

    /**
     * The ruler's MAJOR and MID ticks (never minor) nearest to composition time [timeUs] at the current zoom [level]:
     * the two neighbouring majors and mids around it, as composition times (ticks are classified by the displayed
     * timecode frame, start frame + composition frame, exactly as the ruler draws them).
     */
    fun ticksNear(timeUs: Long, rate: FrameRate, level: RulerLevel, startFrame: Long): List<SnapTarget> {
        val p = level.period
        val tc = Math.addExact(startFrame, rate.nearestFrame(timeUs.coerceAtLeast(0)))
        val base = tc - Math.floorMod(tc, p)
        val frames = buildList {
            for (k in -1L..1L) {
                add(base + k * p)
                if (level.mids) add(base + k * p + level.midOffset)
            }
        }
        return frames.map { it - startFrame }.filter { it >= 0 }.distinct().map { SnapTarget(rate.frameTimeUs(it), SnapKind.TICK) }
    }

    /** [targets] plus the major / mid ticks near every moved candidate (candidates at the raw delta). */
    fun withTicks(targets: List<SnapTarget>, candidates: List<Long>, rawDeltaUs: Long, rate: FrameRate, level: RulerLevel?, startFrame: Long) =
        if (level == null) targets else targets + candidates.flatMap { ticksNear(Math.addExact(it, rawDeltaUs), rate, level, startFrame) }.distinct()
}

/** TLP.2 part 5: the drag readouts (pure, JVM tested), in the playhead readout's format (MM:SS:FF, floor to the shown frame). */
object DragReadout {
    /** LEFT: the dragged item's start (the earliest selected start / key) as the playhead readout shows it. */
    fun start(startUs: Long, startTimecodeUs: Long, rate: FrameRate): String = CompositionTimecode.formatPlayhead(startUs, startTimecodeUs, rate)

    /** RIGHT: the signed frame delta from where the drag began, `+00:00:06` / `-00:00:06`; zero is `+00:00:00`. */
    fun delta(fromUs: Long, toUs: Long, rate: FrameRate): String {
        val frames = rate.floorFrame(toUs.coerceAtLeast(0)) - rate.floorFrame(fromUs.coerceAtLeast(0))
        return (if (frames < 0) "-" else "+") + CompositionTimecode.formatFrame(abs(frames), rate)
    }
}

/**
 * TLP.2 parts 4 / 5: the live state of a timeline hold-drag (UI only, never in history), shared by the snap guide, the
 * ruler highlights and the readout overlay. Composition times: [startUs] is the dragged item's start now and [originUs]
 * where it began; [spanStartUs] / [spanEndUs] the ticks the ruler highlights; [guideUs] the snap target, null when free.
 */
data class TimelineDragInfo(val startUs: Long, val originUs: Long, val spanStartUs: Long, val spanEndUs: Long, val guideUs: Long?)
