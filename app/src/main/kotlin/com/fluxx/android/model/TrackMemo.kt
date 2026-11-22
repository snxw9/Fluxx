package com.fluxx.android.model

/**
 * TEXT PERF 11: keyframe tracks evaluated once per frame time and shared by equal tracks.
 *
 * Every text layer evaluated each of its animator, selector and layer tracks itself. Thirty duplicated layers with the
 * F2 animator stack evaluated about 650 tracks each per frame: on the A16 that was 23% of the render thread (keyframe
 * search and easing) plus 8.5% colour interpolation, all repeating identical work. A track is a pure function of
 * (track, time), so equal tracks share one slot: the first layer evaluated at a time computes the value, the others
 * read it back. Equality is the data-class equality of the track (static value, keyframes with their times, values
 * and easing; Floats compare as `Float.equals`, so -0 and +0 stay apart), which is everything the value depends on.
 *
 * A memo and its slots are confined to one thread (the renderer's render thread, or a private memo per animation).
 * Slots are interned during preparation; per frame a slot costs one comparison of the last time.
 */
class TrackMemo {
    class Scalar internal constructor(private val track: AnimatableProperty1D) {
        private val animated = track.isAnimated && track.keyframes.isNotEmpty()
        private val static = track.staticValue
        private var valueAt = 0L; private var hasValue = false; private var value = 0f
        private var heldAt = 0L; private var hasHeld = false; private var held = 0f
        /** Exactly [AnimatableProperty1D.evaluate]. */
        fun evaluate(time: Long): Float {
            if (!animated) return static
            if (!hasValue || valueAt != time) { value = track.evaluate(time); valueAt = time; hasValue = true }
            return value
        }
        /** Exactly [hold]. */
        fun hold(time: Long): Float {
            if (!animated) return static
            if (!hasHeld || heldAt != time) { held = track.hold(time); heldAt = time; hasHeld = true }
            return held
        }
    }
    class Vector internal constructor(private val track: AnimatableProperty2D) {
        private val animated = track.isAnimated && track.keyframes.isNotEmpty()
        private val pair = FloatArray(2)
        private var at = 0L; private var has = false
        /** Exactly [AnimatableProperty2D.evaluate] into [out] at [offset]. */
        fun evaluate(time: Long, out: FloatArray, offset: Int) {
            if (!animated) { track.evaluate(time, out, offset); return }
            if (!has || at != time) { track.evaluate(time, pair, 0); at = time; has = true }
            out[offset] = pair[0]; out[offset + 1] = pair[1]
        }
    }
    class Colour internal constructor(private val track: AnimatableColour) {
        private val animated = track.isAnimated && track.keyframes.isNotEmpty()
        private var at = 0L; private var has = false; private var value = 0
        /** Exactly [AnimatableColour.evaluate]. */
        fun evaluate(time: Long): Int {
            if (!animated) return track.staticValue
            if (!has || at != time) { value = track.evaluate(time); at = time; has = true }
            return value
        }
    }
    private val scalars = HashMap<AnimatableProperty1D, Scalar>()
    private val vectors = HashMap<AnimatableProperty2D, Vector>()
    private val colours = HashMap<AnimatableColour, Colour>()
    fun scalar(track: AnimatableProperty1D): Scalar = scalars.getOrPut(track) { Scalar(track) }
    fun vector(track: AnimatableProperty2D): Vector = vectors.getOrPut(track) { Vector(track) }
    fun colour(track: AnimatableColour): Colour = colours.getOrPut(track) { Colour(track) }
    /** Distinct slots (diagnostics and tests). */
    val slots: Int get() = scalars.size + vectors.size + colours.size
}
