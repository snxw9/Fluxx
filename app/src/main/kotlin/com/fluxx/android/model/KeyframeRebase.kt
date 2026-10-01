package com.fluxx.android.model

/** Retain every key and its outgoing easing, including keys before a split segment's origin. */
fun AnimatableProperty1D.rebaseKeyframes(deltaUs: Long): AnimatableProperty1D =
    if (deltaUs == 0L || keyframes.isEmpty()) this else copy(keyframes = FrozenList.of(
        keyframes.map { it.copy(timeUs = Math.subtractExact(it.timeUs, deltaUs)) }))

fun AnimatableProperty2D.rebaseKeyframes(deltaUs: Long): AnimatableProperty2D =
    if (deltaUs == 0L || keyframes.isEmpty()) this else copy(keyframes = FrozenList.of(
        keyframes.map { it.copy(timeUs = Math.subtractExact(it.timeUs, deltaUs)) }))

fun AnimatableTransform.rebaseKeyframes(deltaUs: Long): AnimatableTransform = copy(
    position = position.rebaseKeyframes(deltaUs), scale = scale.rebaseKeyframes(deltaUs),
    rotation = rotation.rebaseKeyframes(deltaUs), opacity = opacity.rebaseKeyframes(deltaUs)
)
