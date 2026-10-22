package com.ideatic.sunwatt.core.format

import com.ideatic.sunwatt.core.data.madrid
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val ES = Locale.forLanguageTag("es-ES")
private val DE = Locale.forLanguageTag("de-DE")
private val EN_US = Locale.forLanguageTag("en-US")
private val EN_GB = Locale.forLanguageTag("en-GB")

class FormatTest {
    @Test
    fun formatAvoidsNegativeZero() {
        assertEquals("0,000", Format.eurPerKWh(-0.0000004, locale = ES))
        assertEquals("-0,003", Format.eurPerKWh(-0.003, locale = ES))
        assertEquals("0,147", Format.eurPerKWh(0.147, locale = ES))
        assertEquals("0,15", Format.eurPerKWh(0.147, decimals = 2, locale = ES))
        assertEquals("–", Format.eurPerKWh(null, locale = ES))
    }

    @Test
    fun formatLargeEnergyInMegawattHours() {
        val month = Format.energy(733.2, ES)
        val year = Format.energy(17_092.0, ES)
        assertEquals(EnergyText("733,2", "kWh"), month)
        assertEquals("kWh", Format.energy(9_999.0, ES).unit)
        assertEquals(EnergyText("17,1", "MWh"), year)
        assertEquals("–", Format.energy(null, ES).value)
        assertEquals("MWh", Format.energy(10_000.0, ES).unit)
        assertEquals("MWh", Format.energy(-10_000.0, ES).unit)
    }

    @Test
    fun powerUsesOneDecimalBelowTenAndNoneAbove() {
        assertEquals("4,8", Format.kW(4.84, ES))
        assertEquals("9,9", Format.kW(9.94, ES))
        assertEquals("15", Format.kW(14.6, ES))
        assertEquals("0,0", Format.kW(0.0, ES))
        assertEquals("–", Format.kW(null, ES))
        assertEquals("4.8", Format.kW(4.84, EN_US))
    }

    @Test
    fun energyHasOneDecimalUnderAThousand() {
        assertEquals("23,4", Format.kWh(23.4, ES))
        assertEquals("999,9", Format.kWh(999.94, ES))
        assertEquals("1234", Format.kWh(1234.4, ES)) // Spanish does not group four digits (ICU rule)
        assertEquals("1.234", Format.kWh(1234.4, DE))
        assertEquals("1,234", Format.kWh(1234.4, EN_US))
        assertEquals("12.346", Format.kWh(12_345.6, ES))
        assertEquals("–", Format.kWh(null, ES))
    }

    @Test
    fun roundingMatchesTheDecimalTextNotTheBinaryValue() {
        // ICU rounds half-even on the shortest decimal text: 2.675 gives 2.68 (Java on the raw double would give 2.67).
        assertEquals("2,68", Format.eurPerKWh(2.675, decimals = 2, locale = ES))
        assertEquals("0,12", Format.eurPerKWh(0.125, decimals = 2, locale = ES))
        assertEquals("0,14", Format.eurPerKWh(0.135, decimals = 2, locale = ES))
    }

    @Test
    fun timeUsesGivenTimeZone() {
        val date = madrid(2026, 10, 17, 14, 32)
        val lisbon = ZoneId.of("Europe/Lisbon")
        assertEquals("14:32", Format.time(date, locale = ES))
        assertEquals("13:32", Format.time(date, lisbon, ES))
        assertEquals("13 h", Format.hour(date, lisbon))
        assertEquals("14 h", Format.hour(date))
    }

    /** A 12-hour locale must say AM/PM: "02:32" alone reads as night (bug seen in the en-US store screenshots). */
    @Test
    fun timeShowsAmPmInTwelveHourLocales() {
        val date = madrid(2026, 10, 17, 14, 32)
        val morning = madrid(2026, 10, 17, 9, 5)
        assertEquals("2:32 PM", Format.time(date, locale = EN_US).replace(' ', ' '))
        assertEquals("09:05", Format.time(morning, locale = ES))
        assertEquals("14:32", Format.time(date, locale = EN_GB))
        assertTrue(Format.usesTwelveHourClock(EN_US))
        assertFalse(Format.usesTwelveHourClock(ES))
        assertFalse(Format.usesTwelveHourClock(DE))
    }
}
