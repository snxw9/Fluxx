package com.fluxx.android.render

import com.fluxx.android.engine.FrameCache
import org.junit.Assert.*
import org.junit.Test

/** MP2.3 B2: the frame cache rule decode-behind relies on (how many backward steps the cache answers). */
class DecodeBehindTest {
    @Test fun runBeforeCountsTheConsecutiveCachedFramesBehindATime() {
        val c = FrameCache<String>(5) {}
        for (k in 0..4) c.add(k * 33_333L, "f$k")
        assertEquals(5, c.runBefore(4 * 33_333L))
        assertEquals(3, c.runBefore(2 * 33_333L + 10)) // floor 2, consecutive with 3 after it
        c.breakRun(); c.add(10 * 33_333L, "s")         // a seek: the new frame is not consecutive with the old run
        assertEquals(0, c.runBefore(10 * 33_333L + 5)) // the floor is not proven (nothing after it, not ended)
        assertEquals(1, c.runBefore(10 * 33_333L))     // exactly on it
    }
}
