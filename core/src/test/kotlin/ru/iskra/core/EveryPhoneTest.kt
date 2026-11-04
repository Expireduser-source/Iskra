package ru.iskra.core

import ru.iskra.core.device.Vendor
import ru.iskra.core.device.VendorGuide
import ru.iskra.core.power.DeviceClass
import ru.iskra.core.power.DeviceClassifier
import ru.iskra.core.power.EconomyMode
import ru.iskra.core.power.EconomyPolicy
import ru.iskra.core.power.MotionVote
import ru.iskra.core.power.PowerState
import ru.iskra.core.power.RefreshPolicy
import ru.iskra.core.skills.DayWeather
import ru.iskra.core.skills.Forecast
import ru.iskra.core.skills.MomentLines
import ru.iskra.core.skills.Moments
import ru.iskra.core.skills.PhraseBank
import ru.iskra.core.skills.Place
import ru.iskra.core.skills.ProactiveFrequency
import ru.iskra.core.skills.Script
import ru.iskra.core.skills.Scripts
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 0.9: phones of every maker, economy, small moments. */
class EveryPhoneTest {

    @Test fun vendorDetectionCoversMajorMakers() {
        val cases = mapOf(
            ("Xiaomi" to "Redmi") to Vendor.XIAOMI, ("Xiaomi" to "POCO") to Vendor.XIAOMI,
            ("samsung" to "samsung") to Vendor.SAMSUNG, ("HUAWEI" to "HUAWEI") to Vendor.HUAWEI,
            ("HONOR" to "HONOR") to Vendor.HONOR, ("OPPO" to "OPPO") to Vendor.OPPO,
            ("realme" to "realme") to Vendor.OPPO, ("OnePlus" to "OnePlus") to Vendor.ONEPLUS,
            ("vivo" to "iQOO") to Vendor.VIVO, ("TECNO MOBILE LIMITED" to "TECNO") to Vendor.TRANSSION,
            ("INFINIX MOBILITY LIMITED" to "Infinix") to Vendor.TRANSSION, ("asus" to "asus") to Vendor.ASUS,
            ("Meizu" to "meizu") to Vendor.MEIZU, ("Google" to "google") to Vendor.NEAR_STOCK,
            ("motorola" to "motorola") to Vendor.NEAR_STOCK,
        )
        cases.forEach { (mb, v) -> assertEquals(v, VendorGuide.detect(mb.first, mb.second), "$mb") }
    }

    @Test fun everyVendorHasStepsAndButtonsHaveScreens() {
        for (v in Vendor.entries) {
            val steps = VendorGuide.steps(v)
            assertTrue(steps.isNotEmpty(), "$v has no steps")
            for (s in steps) {
                assertTrue(s.text.isNotBlank())
                if (s.button != null) assertTrue(s.screens.isNotEmpty(), "$v: button «${s.button}» opens nothing")
                s.screens.forEach { assertTrue((it.pkg != null && it.cls != null) || it.action != null, "$v: empty screen ref") }
            }
        }
    }

    @Test fun deviceClassFromRamAndCores() {
        assertEquals(DeviceClass.LOW, DeviceClassifier.classify(2_800, 8, lowRam = false))
        assertEquals(DeviceClass.LOW, DeviceClassifier.classify(8_000, 8, lowRam = true))
        assertEquals(DeviceClass.LOW, DeviceClassifier.classify(6_000, 4, lowRam = false))
        assertEquals(DeviceClass.MID, DeviceClassifier.classify(3_700, 8, lowRam = false))
        assertEquals(DeviceClass.HIGH, DeviceClassifier.classify(7_600, 8, lowRam = false))
        assertEquals(DeviceClass.MID, DeviceClassifier.classify(0, 0, lowRam = false)) // unknown stays neutral
    }

    @Test fun autoIsBalancedButSavesWhenThePhoneAsks() {
        val normal = EconomyPolicy.resolve(EconomyMode.AUTO, PowerState(80, false, false), DeviceClass.MID)
        assertEquals(EconomyMode.BALANCED, normal.mode)
        assertEquals(RefreshPolicy.HIGH_WHILE_MOVING, normal.refresh) // never 120 Hz while she stands still

        val saver = EconomyPolicy.resolve(EconomyMode.AUTO, PowerState(80, false, powerSave = true), DeviceClass.HIGH)
        assertEquals(EconomyMode.BATTERY, saver.mode)
        assertEquals(RefreshPolicy.SYSTEM, saver.refresh)
        assertTrue(saver.forceEconomicalEar)
        assertFalse(saver.proactiveVoice)

        assertEquals(EconomyMode.BATTERY, EconomyPolicy.resolve(EconomyMode.AUTO, PowerState(15, false, false), DeviceClass.MID).mode)
        // Charging at 15 % is not a reason to save.
        assertEquals(EconomyMode.BALANCED, EconomyPolicy.resolve(EconomyMode.AUTO, PowerState(15, true, false), DeviceClass.MID).mode)
        assertEquals(EconomyMode.BATTERY, EconomyPolicy.resolve(EconomyMode.AUTO, PowerState(), DeviceClass.LOW).mode)
    }

    @Test fun explicitChoiceWins() {
        val max = EconomyPolicy.resolve(EconomyMode.MAX, PowerState(5, false, true), DeviceClass.LOW)
        assertEquals(EconomyMode.MAX, max.mode)
        assertEquals(RefreshPolicy.ALWAYS_HIGH, max.refresh)
        val bat = EconomyPolicy.resolve(EconomyMode.BATTERY, PowerState(100, true, false), DeviceClass.HIGH)
        assertEquals(30, bat.movingFps); assertEquals(20, bat.idleFps); assertEquals(0, bat.maxLiveliness)
        assertTrue(bat.describe().contains("Батарея"))
    }

    @Test fun legacyFrameRateSettingMigrates() {
        assertEquals(EconomyMode.AUTO, EconomyPolicy.fromLegacyFps("AUTO"))
        assertEquals(EconomyMode.AUTO, EconomyPolicy.fromLegacyFps(null))
        assertEquals(EconomyMode.BALANCED, EconomyPolicy.fromLegacyFps("HZ60"))
        assertEquals(EconomyMode.BATTERY, EconomyPolicy.fromLegacyFps("ECO"))
    }

    @Test fun motionVoteSwitchesOnAtOnceAndOffAfterAPause() {
        val v = MotionVote(holdMs = 1500)
        assertFalse(v.update(false, 0))
        assertTrue(v.update(true, 100)); assertTrue(v.moving)
        assertFalse(v.update(false, 200))
        assertFalse(v.update(true, 900))       // a short pause between steps keeps the vote
        assertFalse(v.update(false, 1000))
        assertFalse(v.update(false, 2400))
        assertTrue(v.update(false, 2600)); assertFalse(v.moving)
    }

    @Test fun chargingReactionRespectsLimits() {
        val h = 3_600_000L
        assertTrue(Moments.charging(10 * h, 0, ProactiveFrequency.RARE, quiet = false, screenOn = true, busy = false))
        assertFalse(Moments.charging(10 * h, 9 * h, ProactiveFrequency.RARE, false, true, false)) // too soon
        assertFalse(Moments.charging(10 * h, 0, ProactiveFrequency.OFF, false, true, false))
        assertFalse(Moments.charging(10 * h, 0, ProactiveFrequency.OFTEN, quiet = true, screenOn = true, busy = false))
        assertFalse(Moments.charging(10 * h, 0, ProactiveFrequency.OFTEN, false, screenOn = false, busy = false))
        assertFalse(Moments.charging(10 * h, 0, ProactiveFrequency.OFTEN, false, true, busy = true))
        MomentLines.CHARGING.forEach { assertTrue(it.length <= 90, it) }
        assertTrue(MomentLines.charging(PhraseBank(Random(1))) in MomentLines.CHARGING)
    }

    @Test fun morningBriefSaysOnlyWhatIsKnown() {
        assertEquals("Доброе утро!", Moments.morning("Доброе утро!", null, 0))
        assertEquals("Доброе утро! За окном +3°, пасмурно. На сегодня два напоминания — я прослежу.",
            Moments.morning("Доброе утро!", "+3°, пасмурно", 2))
        assertEquals("Утро! На сегодня одно напоминание — я прослежу.", Moments.morning("Утро!", " ", 1))
        val f = Forecast(Place("Москва", 0.0, 0.0), tempNow = -2.4, codeNow = 71, windNow = null, days = listOf(DayWeather(-5.0, 0.0, 3, null)))
        assertEquals("−2°, небольшой снег", Moments.weatherNow(f))
        assertEquals(null, Moments.weatherNow(f.copy(tempNow = null)))
    }

    @Test fun russianPlural() {
        assertEquals("одно напоминание", Moments.count(1, "напоминание", "напоминания", "напоминаний"))
        assertEquals("четыре напоминания", Moments.count(4, "напоминание", "напоминания", "напоминаний"))
        assertEquals("5 напоминаний", Moments.count(5, "напоминание", "напоминания", "напоминаний"))
        assertEquals("11 напоминаний", Moments.count(11, "напоминание", "напоминания", "напоминаний"))
        assertEquals("21 напоминание", Moments.count(21, "напоминание", "напоминания", "напоминаний"))
        assertEquals("две заметки", Moments.count(2, "заметка", "заметки", "заметок"))
    }

    @Test fun scriptDecidesTheSystemVoiceLanguage() {
        assertEquals(Script.CYRILLIC, Scripts.dominant("Ставлю таймер на 5 минут."))
        assertEquals(Script.CYRILLIC, Scripts.dominant("OK, ставлю будильник на 7:00"))
        assertEquals(Script.LATIN, Scripts.dominant("Sure! I set a timer for five minutes."))
        assertEquals(Script.OTHER, Scripts.dominant("12:30 — 5 %"))
    }
}
