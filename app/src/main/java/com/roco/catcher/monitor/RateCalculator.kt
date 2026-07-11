package com.roco.catcher.monitor

import com.roco.catcher.model.CaughtPetEvent
import com.roco.catcher.model.RatePoint

object RateCalculator {
    private const val ONE_MINUTE_MILLIS = 60_000L

    fun averageRate(caughtCount: Int, effectiveRunMillis: Long): Double {
        if (caughtCount <= 0) return 0.0
        val minutes = (effectiveRunMillis / 60_000.0).coerceAtLeast(1.0 / 60.0)
        return caughtCount / minutes
    }

    fun currentRate(events: List<CaughtPetEvent>, nowMillis: Long): Double {
        if (events.isEmpty()) return 0.0
        val start = (nowMillis - ONE_MINUTE_MILLIS).coerceAtLeast(0L)
        return events.count { it.caughtAtMillis in start..nowMillis }.toDouble()
    }

    fun history(events: List<CaughtPetEvent>): List<RatePoint> {
        if (events.isEmpty()) return emptyList()

        val grouped = events.groupBy { it.caughtAtMillis / ONE_MINUTE_MILLIS }
        return grouped.keys.sorted().map { bucket ->
            val count = grouped[bucket].orEmpty().size
            RatePoint(
                bucketIndex = bucket,
                displayTimeMillis = bucket * ONE_MINUTE_MILLIS,
                count = count,
                ratePerMinute = count.toDouble(),
            )
        }
    }
}

