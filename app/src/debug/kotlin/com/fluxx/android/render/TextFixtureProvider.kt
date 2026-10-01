package com.fluxx.android.render

/** Explicit harness selection only. Normal debug editor sessions inject nothing. */
object TextFixtureProvider {
    @Volatile var fixtures: List<TextFixture> = emptyList()
    fun entries(): List<TextFixture> = fixtures
}
