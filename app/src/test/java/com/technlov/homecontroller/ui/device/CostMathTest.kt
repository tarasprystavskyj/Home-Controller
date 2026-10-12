package com.technlov.homecontroller.ui.device

import com.technlov.homecontroller.R
import org.junit.Assert.assertEquals
import org.junit.Test

class CostMathTest {
    @Test
    fun settleFollowsUpHoldsJitterAndCorrectsOvercount() {
        assertEquals(1.20, settle(1.00, 1.20), 1e-12)   // runs on
        assertEquals(1.00, settle(1.00, 0.997), 1e-12)  // a third of a kopeck back: hold, no jitter
        assertEquals(0.60, settle(0.69, 0.60), 1e-12)   // 9 kopecks over-counted: corrected
    }

    @Test
    fun zonesChangeOnTheHour() {
        assertEquals(R.string.zone_night, Tariffs.zoneAt(2, 6).nameRes)
        assertEquals(R.string.zone_day, Tariffs.zoneAt(2, 7).nameRes)
        assertEquals(R.string.zone_night, Tariffs.zoneAt(2, 23).nameRes)
        assertEquals(R.string.zone_half, Tariffs.zoneAt(3, 7).nameRes)
        assertEquals(R.string.zone_peak, Tariffs.zoneAt(3, 8).nameRes)
        assertEquals(R.string.zone_half, Tariffs.zoneAt(3, 11).nameRes)
        assertEquals(R.string.zone_peak, Tariffs.zoneAt(3, 21).nameRes)
        assertEquals(R.string.zone_half, Tariffs.zoneAt(3, 22).nameRes)
        assertEquals(R.string.zone_all, Tariffs.zoneAt(1, 3).nameRes)
    }

    @Test
    fun aDayAtOneKilowattCostsWhatTheWebDashboardSaid() {
        // 1 kW for 24 h at 4.32 UAH: 103.68 single-rate, 86.40 two-zone, 93.744 three-zone.
        for ((zones, expected) in listOf(1 to 103.68, 2 to 86.40, 3 to 93.744)) {
            val cost = (0 until 24).sumOf { h -> 1.0 * 4.32 * Tariffs.zoneAt(zones, h).coef }
            assertEquals(expected, cost, 1e-9)
        }
    }
}
