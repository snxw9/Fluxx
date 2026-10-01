package com.fluxx.android.render

import org.junit.Assert.*
import org.junit.Test

class TextProofPolicyTest {
    @Test fun medianRejectsSustainedGrowthButAllowsAnIsolatedSpike() {
        val stable = MutableList(30) { 20_000_000L }.apply { this[29] = 50_000_000L }
        assertTrue(TextProofPolicy.heapPass(stable))
        assertFalse(TextProofPolicy.heapPass(List(30) { 20_000_000L + it * 100_000L }))
        assertFalse(TextProofPolicy.heapPass(List(30) { 20_000_000L + it * 1_000L }))
    }
    @Test fun categoriesCannotCancelEachOtherOut() {
        assertFalse(TextProofPolicy.resourcePass(mapOf("images" to 1L, "buffers" to 2L),
            mapOf("images" to 2L, "buffers" to 1L)))
    }
    @Test(expected = IllegalArgumentException::class) fun incompleteRunCannotPass() {
        TextProofPolicy.heapPass(List(29) { 0L })
    }
}
