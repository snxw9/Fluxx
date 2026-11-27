package com.fluxx.android.editor

import com.fluxx.android.model.CompositionTimecode
import com.fluxx.android.model.FrameRate
import com.fluxx.android.model.ProjectDocument
import kotlin.math.abs

/**
 * TLP.2 part 4: what a dragged layer / key snaps to, in tie-break PRIORITY order (the enum order): playhead > marker >
 * clip edge > composition bound > neighbour key > ruler major tick > ruler mid tick. [NEIGHBOUR] only exists for the single-key drag (its
 * existing targets: the other keys of the property and its own clip's start / end).
 */
enum class SnapKind { PLAYHEAD, MARKER, CLIP_EDGE, BOUND, NEIGHBOUR, TICK, MID_TICK }

/**
 * TLP.3 A2: the per-class snap zones, in FRAMES of the current zoom clamped in dp:
 * `zoneDp = clamp(frames x framePitchDp, FLOOR_DP, capDp)`. Positions are whole frames, so a zone under one frame changes
 * nothing; the old flat 12dp (about +/-3 frames at the default zoom) swallowed the frames around every target.
 */
object SnapZones {
    /** Playhead, markers, clip edges, composition bounds and the single-key extra targets (neighbour keys, own clip edges). */
    const val STRONG_FRAMES = 2f
    const val STRONG_CAP_DP = 8f
    /** Ruler MAJOR ticks. */
    const val MAJOR_FRAMES = 1f
    const val MAJOR_CAP_DP = 6f
    /** Ruler MID ticks. */
    const val MID_FRAMES = 1f
    const val MID_CAP_DP = 5f
    /** Every class keeps at least this zone, so zoomed far out snapping never disappears. */
    const val FLOOR_DP = 3f

    fun framesOf(kind: SnapKind) = when (kind) { SnapKind.TICK -> MAJOR_FRAMES; SnapKind.MID_TICK -> MID_FRAMES; else -> STRONG_FRAMES }
    fun capOf(kind: SnapKind) = when (kind) { SnapKind.TICK -> MAJOR_CAP_DP; SnapKind.MID_TICK -> MID_CAP_DP; else -> STRONG_CAP_DP }

    /** The zone of [kind] in dp at [dpPerUs] (the timeline's zoom in dp per microsecond) and [rate]. */
    fun dp(kind: SnapKind, dpPerUs: Double, rate: FrameRate): Float {
        val pitchDp = dpPerUs * 1_000_000.0 * rate.denominator / rate.numerator
        return (framesOf(kind) * pitchDp).toFloat().coerceIn(FLOOR_DP, capOf(kind))
    }

    /** Per-kind zones in microseconds, for [DragSnap.snap]. */
    fun of(dpPerUs: Double, rate: FrameRate): (SnapKind) -> Long {
        // Rounded up plus 1us: frame times are whole microseconds (33333 / 33334 at 30 fps), so N frames always fit.
        val us = SnapKind.entries.map { kotlin.math.ceil(dp(it, dpPerUs, rate) / dpPerUs).toLong() + 1 }
        return { us[it.ordinal] }
    }
}

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
    /** One zone for every class (tests); the timeline uses the per-class [SnapZones]. */
    fun snap(rawDeltaUs: Long, candidates: List<Long>, targets: List<SnapTarget>, zoneUs: Long): SnapResult =
        snap(rawDeltaUs, candidates, targets) { zoneUs }

    /** TLP.3 A2: each target captures within its own class's zone ([SnapZones.of]); the closest captured pair wins. */
    fun snap(rawDeltaUs: Long, candidates: List<Long>, targets: List<SnapTarget>, zoneOf: (SnapKind) -> Long): SnapResult {
        var best: SnapTarget? = null; var bestDelta = rawDeltaUs; var bestDistance = Long.MAX_VALUE
        for (c in candidates) {
            val moved = Math.addExact(c, rawDeltaUs)
            for (t in targets) {
                val d = abs(t.timeUs - moved)
                if (d > zoneOf(t.kind)) continue
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
                add((base + k * p) to SnapKind.TICK)
                if (level.mids) add((base + k * p + level.midOffset) to SnapKind.MID_TICK)
            }
        }
        return frames.map { (it.first - startFrame) to it.second }.filter { it.first >= 0 }.distinct()
            .map { SnapTarget(rate.frameTimeUs(it.first), it.second) }
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
 * TLP.2 parts 4 / 5: the live state of a timeline hold-drag (UI only, never in history), shared by the
 * ruler highlights and the readout overlay. Composition times: [startUs] is the dragged item's start now and [originUs]
 * where it began; [spanStartUs] / [spanEndUs] the ticks the ruler highlights; (TLP.3 A3: no snap guide line; the haptic and these highlights remain).
 */
data class TimelineDragInfo(val startUs: Long, val originUs: Long, val spanStartUs: Long, val spanEndUs: Long)
