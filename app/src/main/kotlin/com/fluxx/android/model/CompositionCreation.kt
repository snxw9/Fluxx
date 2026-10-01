package com.fluxx.android.model

import java.math.BigDecimal

data class CompositionCreation(
    val name: String, val width: Int, val height: Int,
    val durationUs: Long?, val fps: Int, val backgroundArgb: Int
) {
    fun validate() {
        require(name.isNotBlank() && name.trim() !in listOf(".", "..") &&
            name.none { it == '/' || it == '\\' || it.isISOControl() }) { "Enter a valid project name" }
        require(width > 0 && height > 0) { "Width and height must be positive integers" }
        require(width % 2 == 0 && height % 2 == 0) { "Width and height must be even numbers" }
        require(width.toLong() * height <= 1920L * 1080) { "Export supports up to 1080p pixel area" }
        require(durationUs == null || durationUs > 0) { "Duration must be greater than zero" }
        require(fps in 1..120) { "Frame rate must be between 1 and 120 fps" }
    }
}

data class CompositionDraft(
    val name: String = "Project 1", val width: String = "1920", val height: String = "1080",
    val duration: String = "", val fps: Int = 30, val backgroundArgb: Int = 0xff000000.toInt(),
    val aspect: String = "16:9", val resolution: Int = 1080, val pencil: Boolean = false,
    val tab: Int = 0, val customPreset: String? = null
) {
    fun request(): CompositionCreation {
        val w = width.toIntOrNull() ?: error("Width must be a positive integer")
        val h = height.toIntOrNull() ?: error("Height must be a positive integer")
        val time = if (duration.isBlank()) null else try { BigDecimal(duration).multiply(BigDecimal(1_000_000)).longValueExact() }
            catch (_: Exception) { error("Enter a duration in seconds with at most six decimal places") }
        return CompositionCreation(name.trim(), w, h, time, fps, backgroundArgb).also { it.validate() }
    }

    fun withAspect(label: String = aspect, pixels: Int = resolution): CompositionDraft {
        val parts = label.split(':').map { it.toInt() }
        val ratio = parts[0].toDouble() / parts[1]
        // Resolution denotes the short edge. Built-in dimensions are rounded to even values here;
        // user-entered dimensions are never rounded during validation.
        fun even(value: Double) = (kotlin.math.round(value / 2).toInt() * 2).coerceAtLeast(2)
        val w = if (ratio >= 1) even(pixels * ratio) else pixels
        val h = if (ratio >= 1) pixels else even(pixels / ratio)
        return copy(width = w.toString(), height = h.toString(), aspect = label,
            resolution = pixels, customPreset = null)
    }
}
