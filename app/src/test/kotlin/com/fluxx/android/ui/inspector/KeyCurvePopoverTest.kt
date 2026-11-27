package com.fluxx.android.ui.inspector

import com.fluxx.android.editor.EaseChoice
import com.fluxx.android.model.EasingLimits
import com.fluxx.android.model.EasingCurve
import org.junit.Assert.*
import org.junit.Test

/** TLP.3 A1: the key bar's curve popover. */
class KeyCurvePopoverTest {
    @Test fun typesComeFirstWithTheTypeStripsDefaultParameters() {
        assertEquals(listOf(EaseChoice.EASE_IN, EaseChoice.EASE_OUT, EaseChoice.BOUNCE, EaseChoice.ELASTIC), KeyCurvePopover.TYPES)
        // The Graph type strip applies a type to a curve of another type with these defaults.
        assertEquals(EaseChoice.EASE_IN_CURVE, EaseChoice.curve(EaseChoice.EASE_IN, EasingCurve.EASY_EASE))
        assertEquals(EaseChoice.EASE_OUT_CURVE, EaseChoice.curve(EaseChoice.EASE_OUT, EasingCurve.EASY_EASE))
        assertEquals(EasingCurve.bounce(EasingLimits.BOUNCE_DEFAULT_COUNT, EasingLimits.BOUNCE_DEFAULT_RESTITUTION),
            EaseChoice.curve(EaseChoice.BOUNCE, EasingCurve.EASY_EASE))
        assertEquals(EasingCurve.elastic(EasingLimits.ELASTIC_DEFAULT_OSCILLATIONS, EasingLimits.ELASTIC_DEFAULT_DAMPING),
            EaseChoice.curve(EaseChoice.ELASTIC, EasingCurve.EASY_EASE))
    }

    @Test fun aHeaderAnchorOpensDownwardAndABottomAnchorUpward() {
        val w = 1080; val h = 2400; val m = 24
        // The key header bar near the middle of the window (the sheet's top): room above AND below.
        val header = PopoverPlacer.place(800, 1300, 900, 1400, 500, 600, w, h, h, m, 4, preferBelow = true)
        assertFalse(header.above); assertEquals(1404, header.y)
        // The same anchor without the preference (the bottom overlay's rule) opens upward.
        assertTrue(PopoverPlacer.place(800, 1300, 900, 1400, 500, 600, w, h, h, m, 4).above)
        // Preferred below but no room below: it still opens above.
        assertTrue(PopoverPlacer.place(800, 2200, 900, 2300, 500, 600, w, h, h, m, 4, preferBelow = true).above)
    }
}
