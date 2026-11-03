package ru.iskra.core

import ru.iskra.core.diag.PowerSample
import ru.iskra.core.diag.PowerStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PowerStatsTest {
    private val min = 60_000L

    @Test
    fun drainPerHourAndCpuShareWithoutCharging() {
        val p = PowerStats()
        // 60 min, 80 % → 77 %, 36 s of CPU, screen off for the second half.
        for (i in 0..12) p.add(PowerSample(i * 5 * min, 80f - i * 0.25f, false, i < 6, "RIG", i * 3_000L))
        val s = assertNotNull(p.summary())
        assertEquals(60f, s.minutes, 0.01f)
        assertEquals(3f, s.drainPctPerHour!!, 0.01f)
        assertEquals(1f, s.cpuPct, 0.01f)
        assertEquals(0.5f, s.screenOffShare, 0.01f)
        assertEquals(setOf("RIG"), s.looks)
    }

    @Test
    fun chargingAndProcessRestartsEndTheStretch() {
        val p = PowerStats()
        p.add(PowerSample(0, 50f, true, true, "RIG", 0))
        p.add(PowerSample(5 * min, 60f, true, true, "RIG", 100))
        assertNull(p.summary(), "charging now")
        p.add(PowerSample(10 * min, 61f, false, true, "RIG", 200))
        p.add(PowerSample(15 * min, 61f, false, true, "RIG", 50)) // process restarted: CPU counter smaller
        p.add(PowerSample(45 * min, 60f, false, true, "RIG", 950))
        val s = assertNotNull(p.summary())
        assertEquals(30f, s.minutes, 0.01f)
        assertEquals(2f, s.drainPctPerHour!!, 0.01f)
    }

    @Test
    fun tooShortGivesNoDrainYet() {
        val p = PowerStats()
        p.add(PowerSample(0, 80f, false, true, "SPRITES", 0))
        p.add(PowerSample(10 * min, 80f, false, true, "SPRITES", 1000))
        val s = assertNotNull(p.summary())
        assertNull(s.drainPctPerHour)
        assert(s.describe().contains("мало данных"))
    }
}
