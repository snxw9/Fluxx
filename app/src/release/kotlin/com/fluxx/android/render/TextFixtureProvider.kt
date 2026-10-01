package com.fluxx.android.render

/** Release no-op; no fixture registry ships in production. */
object TextFixtureProvider {
    fun entries(): List<TextFixture> = emptyList()
}
