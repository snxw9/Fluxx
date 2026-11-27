package com.fluxx.android.ui

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry

/**
 * MP1.1 D: the device state a benchmark class ran under, recorded at the start of every instrumented media class and
 * printed in its output (status key STATE): Battery Saver (`settings get global low_power`), plugged state, level,
 * temperature, screen and keyguard. Read-only. A class that starts with Battery Saver on or on battery is marked
 * "NOT COMPARABLE" so its numbers are not used for before / after.
 */
object TestDeviceState {
    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
    }

    data class State(val lowPower: String, val plugged: String, val level: String, val temperatureC: Double, val screen: String, val locked: Boolean) {
        val comparable get() = lowPower.trim() == "0" && plugged != "battery"
        override fun toString() = "low_power=${lowPower.trim()} plugged=$plugged level=$level percent temperature=" + "%.1f".format(temperatureC) + "C screen=$screen keyguard=${if (locked) "locked" else "unlocked"}${if (comparable) "" else " NOT COMPARABLE"}"
    }

    fun read(): State {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val battery = shell("dumpsys battery")
        fun field(name: String) = Regex("""(?m)^\s+$name: (.+)$""").find(battery)?.groupValues?.get(1)?.trim().orEmpty()
        val plugged = when {
            field("AC powered") == "true" -> "AC"
            field("USB powered") == "true" -> "USB"
            field("Wireless powered") == "true" -> "wireless"
            else -> "battery"
        }
        val wake = Regex("""mWakefulness=(\w+)""").find(shell("dumpsys power"))?.groupValues?.get(1) ?: "?"
        val locked = context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        return State(shell("settings get global low_power"), plugged, field("level"), (field("temperature").toIntOrNull() ?: 0) / 10.0, wake, locked)
    }

    /** Records the state in the class output (and logcat). Returns it for the caller's own checks. */
    fun record(className: String): State {
        val s = read()
        val line = "$className start: $s"
        android.util.Log.i("STATE", line)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("STATE", line) })
        return s
    }
}
