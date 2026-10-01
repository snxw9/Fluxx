package com.fluxx.android.render

/** Pure acceptance policy; device measurements are supplied by the instrumentation harness. */
object TextProofPolicy {
    const val WARMUP = 5
    const val CYCLES = 30
    const val MAX_HEAP_GROWTH = 1024L * 1024
    fun heapPass(samples: List<Long>): Boolean {
        require(samples.size == CYCLES)
        require(samples.all { it >= 0 })
        fun median(values: List<Long>): Long = values.sorted().let { it[4] + (it[5] - it[4]) / 2 }
        val initial = median(samples.take(10)); val final = median(samples.takeLast(10))
        // Three consecutive ten-cycle windows may not sustain measurable upward growth.
        val middle = median(samples.subList(10, 20))
        val sustained = middle > initial && final > middle
        return final - initial <= MAX_HEAP_GROWTH && !sustained
    }
    fun resourcePass(baseline: Map<String, Long>, actual: Map<String, Long>): Boolean = baseline == actual
}
