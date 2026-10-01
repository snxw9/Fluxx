package com.fluxx.android.ui.common

import android.text.format.DateUtils
import com.fluxx.android.model.FrameRate

object TimeFormat {
    /** Platform-localized relative dates, with an absolute date after seven days. */
    fun lastEdited(timeMillis: Long, nowMillis: Long): String =
        DateUtils.getRelativeTimeSpanString(timeMillis, nowMillis, DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH).toString()

    /**
     * Formats microseconds into SMPTE style or Timecode: MM:SS:FF
     */
    fun formatTimecode(timeUs: Long, frameRate: FrameRate): String =
        com.fluxx.android.model.CompositionTimecode.format(timeUs, frameRate)

    /**
     * Compact timestamp like "01:23.4"
     */
    fun formatDurationCompact(durationUs: Long): String {
        val safeUs = durationUs.coerceAtLeast(0)
        val totalSeconds = safeUs / 1_000_000.0
        val minutes = (totalSeconds / 60).toInt()
        val seconds = totalSeconds % 60
        return "%02d:%04.1f".format(minutes, seconds)
    }
}
