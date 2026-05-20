package com.tx5dr.bridge

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class LatencyWindow(private val capacity: Int = 1024) {
    private val values = DoubleArray(capacity)
    private var nextIndex = 0
    private var size = 0

    fun record(valueMs: Double) {
        if (!valueMs.isFinite() || valueMs < 0.0) return
        values[nextIndex] = valueMs
        nextIndex = (nextIndex + 1) % capacity
        if (size < capacity) size += 1
    }

    fun snapshot(reset: Boolean = true): LatencySummary {
        if (size == 0) return LatencySummary()
        val sorted = values.copyOf(size).also { it.sort() }
        val summary = LatencySummary(
            count = size.toLong(),
            maxMs = sorted.last(),
            p50Ms = percentile(sorted, 0.50),
            p95Ms = percentile(sorted, 0.95),
            p99Ms = percentile(sorted, 0.99),
        )
        if (reset) clear()
        return summary
    }

    fun clear() {
        nextIndex = 0
        size = 0
    }

    private fun percentile(sorted: DoubleArray, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val rank = ceil(p * sorted.size).toInt() - 1
        return sorted[min(max(rank, 0), sorted.lastIndex)]
    }
}

data class LatencySummary(
    val count: Long = 0,
    val maxMs: Double = 0.0,
    val p50Ms: Double = 0.0,
    val p95Ms: Double = 0.0,
    val p99Ms: Double = 0.0,
) {
    fun format(prefix: String): String =
        "$prefix.count=$count $prefix.max=${fmt(maxMs)}ms $prefix.p50=${fmt(p50Ms)}ms $prefix.p95=${fmt(p95Ms)}ms $prefix.p99=${fmt(p99Ms)}ms"

    private fun fmt(value: Double): String = String.format(Locale.US, "%.2f", value)
}
