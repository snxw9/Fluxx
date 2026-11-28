package com.fluxx.android.render

/**
 * MP2.1 B1: the warm planner (pure, JVM tested). While the preview PLAYS it names the decoders to open and position BEFORE
 * their layer needs them, so a cut or a re-entry takes a decoder that already holds its first frame.
 *
 * Why: at a cut the next clip's decoder used to be opened (or seeked) only when that clip became active, and open + seek
 * to the in-point's sync frame + decode up to the target took hundreds of ms (MP1: 24 / 29 non-contiguous cuts hitched).
 * A culled layer coming back after the 3 s delayed release needed the same, and the culling's 0.5 s look-ahead is
 * shorter than that.
 *
 * Rules:
 * - Only while playing (export never plans: [enabled]). Paused or scrubbing, nothing is due and every warm decoder is
 *   unwanted (the caller releases it within 3 s, or at once when the budget needs the room).
 * - Horizon per decoder class (codec + size): max(0.5 s, 2 x its measured open + seek + first frame), at most 2.5 s;
 *   [DEFAULT_HORIZON_US] (1.5 s) until a decoder of that class has been measured.
 * - CUT: a clip that starts within the horizon (and is visible at its start), unless it CONTINUES a decoder that is open
 *   now: a clip of the same source, active now, will stand at most [CONTIGUOUS_US] before the new in-point when the new
 *   clip starts (it decodes on, no seek), or within [DecoderKeys.SHARE_US] of it (the decoder is shared).
 *   Target: the clip's in-point source time; deadline: its start.
 * - RE-ENTRY: a culled layer that the culling test says becomes visible at r (the caller reads the renderer's own
 *   visibility test ahead; this planner evaluates nothing). The renderer starts decoding it (undrawn) when it is within
 *   [Culling.WARM_LOOKAHEAD_US] of r, so that is the deadline: d = r - 0.5 s; target: the layer's source time at d. An open
 *   decoder of the same source that the current frame does not use is reused (re-positioned, no second open), and every
 *   source due back within the horizon is kept from the 3 s hidden release ([Plan.keepUris]).
 * - Budget: a warm-up must fit [DecoderKeys.decoderBytes] against the budget AND the decoder cap, counting the decoders
 *   open now (those of the current frame included) and those already warming. Otherwise it is BLOCKED (reported, never
 *   forced); no open decoder is evicted for it.
 * - Order: soonest deadline first (then layer id); the caller opens at most one at a time.
 *
 * Source time: the model maps composition time to source time as sourceIn + (t - start) (no speed, stretch or remap:
 * [com.fluxx.android.model.FramePlan.forEachActive]); [Clip.sourceAt] is that mapping, nothing new.
 */
object UpcomingUse {
    const val MIN_HORIZON_US = 500_000L
    const val MAX_HORIZON_US = 2_500_000L
    const val DEFAULT_HORIZON_US = 1_500_000L
    /** A clip whose in-point lies at most this far after where the open decoder of its source will stand needs no warm-up. */
    const val CONTIGUOUS_US = 100_000L

    enum class Kind { CUT, REENTRY }
    enum class Blocked { BUDGET, CAP }

    /** One visible video clip. [decoderClass]: codec and size (the horizon's measurement class); [bytes]: its decoder's memory. */
    data class Clip(val layerId: Long, val uri: String, val startUs: Long, val endUs: Long, val sourceInUs: Long,
                    val decoderClass: String, val bytes: Long) {
        fun activeAt(t: Long) = t >= startUs && t < endUs
        fun sourceAt(t: Long) = sourceInUs + (t - startUs)
    }
    /** An open decoder of the renderer's pool. [forFrame]: the current frame uses it. */
    data class Open(val key: String, val uri: String, val bytes: Long, val forFrame: Boolean)
    /** A warm-up already queued, running or ready, with the memory its decoder holds (a re-used one has left the pool). */
    data class Warming(val layerId: Long, val bytes: Long)
    data class Input(
        val nowUs: Long,
        val playing: Boolean,
        val clips: List<Clip>,
        /** Culled (not decoded) layers active now -> the composition time they become visible (only those found within reach). */
        val returnsAtUs: Map<Long, Long> = emptyMap(),
        /** Clips not visible at their own start (culled there): no cut warm-up. */
        val hiddenAtStart: Set<Long> = emptySet(),
        val open: List<Open> = emptyList(),
        val warming: List<Warming> = emptyList(),
        val budgetBytes: Long,
        val cap: Int,
        /** Measured open + seek + first frame (us) of a decoder class; null before the first measurement. */
        val costUs: (String) -> Long? = { null },
    )
    data class Warm(val layerId: Long, val uri: String, val sourceUs: Long, val deadlineUs: Long, val kind: Kind, val bytes: Long,
                    /** A pooled decoder of the same source to re-position instead of opening one (re-entry only). */
                    val reuseKey: String? = null)
    data class Plan(
        /** Warm-ups to start, soonest deadline first (already warming ones are left out). */
        val due: List<Warm>,
        val blocked: List<Pair<Warm, Blocked>>,
        /** Layers whose warm-up is wanted now (due, blocked or already warming): any other warm decoder is unwanted. */
        val wanted: Set<Long>,
        /** Sources due back within the horizon: their pooled decoders must not get the 3 s hidden release. */
        val keepUris: Set<String>,
    ) { companion object { val NONE = Plan(emptyList(), emptyList(), emptySet(), emptySet()) } }

    /** Whether the planner runs at all: preview playback only (export keeps its own decoder rotation and never warms). */
    fun enabled(export: Boolean, playing: Boolean): Boolean = !export && playing

    fun horizonUs(costUs: Long?): Long = if (costUs == null) DEFAULT_HORIZON_US else (2 * costUs).coerceIn(MIN_HORIZON_US, MAX_HORIZON_US)

    /** Whether [next] continues a decoder open now (a clip of the same source active at [nowUs]) without a seek. */
    fun continues(next: Clip, clips: List<Clip>, nowUs: Long): Boolean = clips.any { a ->
        a.layerId != next.layerId && a.uri == next.uri && a.activeAt(nowUs) && next.startUs >= a.startUs && run {
            val at = a.sourceAt(next.startUs)
            val ahead = next.sourceInUs - at
            ahead in 0..CONTIGUOUS_US || kotlin.math.abs(ahead) <= DecoderKeys.SHARE_US
        }
    }

    fun plan(input: Input): Plan {
        if (!input.playing) return Plan.NONE
        val now = input.nowUs
        val wanted = ArrayList<Warm>()
        val keep = HashSet<String>()
        val claimed = HashSet<String>()
        for (c in input.clips) {
            val horizon = horizonUs(input.costUs(c.decoderClass))
            if (c.startUs > now && c.startUs - now <= horizon) {
                if (c.layerId in input.hiddenAtStart || continues(c, input.clips, now)) continue
                wanted += Warm(c.layerId, c.uri, c.sourceInUs, c.startUs, Kind.CUT, c.bytes)
            } else if (c.activeAt(now)) {
                val back = input.returnsAtUs[c.layerId] ?: continue
                if (back <= now) continue
                val deadline = maxOf(back - Culling.WARM_LOOKAHEAD_US, now)
                if (deadline >= c.endUs || deadline - now > horizon) continue
                keep += c.uri
                val reuse = input.open.firstOrNull { it.uri == c.uri && !it.forFrame && it.key !in claimed }?.key
                if (reuse != null) claimed += reuse
                wanted += Warm(c.layerId, c.uri, c.sourceAt(deadline), deadline, Kind.REENTRY, if (reuse != null) 0 else c.bytes, reuse)
            }
        }
        wanted.sortWith(compareBy<Warm>({ it.deadlineUs }, { it.layerId }))
        val warmingIds = input.warming.mapTo(HashSet()) { it.layerId }
        var bytes = input.open.sumOf { it.bytes } + input.warming.sumOf { it.bytes }
        var count = input.open.size + input.warming.size
        val due = ArrayList<Warm>(); val blocked = ArrayList<Pair<Warm, Blocked>>()
        for (w in wanted) {
            if (w.layerId in warmingIds) continue
            val slot = if (w.reuseKey != null) 0 else 1
            when {
                count + slot > input.cap -> blocked += w to Blocked.CAP
                bytes + w.bytes > input.budgetBytes -> blocked += w to Blocked.BUDGET
                else -> { due += w; bytes += w.bytes; count += slot }
            }
        }
        return Plan(due, blocked, wanted.mapTo(HashSet()) { it.layerId }, keep)
    }
}
