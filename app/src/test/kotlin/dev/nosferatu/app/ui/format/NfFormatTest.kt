package dev.nosferatu.app.ui.format

import kotlin.test.Test
import kotlin.test.assertEquals

class NfFormatTest {
    @Test
    fun distance() {
        assertEquals("0.00", NfFormat.km(0.0))
        assertEquals("5.04", NfFormat.km(5_041.0))
        assertEquals("42.20", NfFormat.km(42_195.0))
    }

    @Test
    fun duration() {
        assertEquals("00:00", NfFormat.duration(0))
        assertEquals("05:07", NfFormat.duration(307_000))
        assertEquals("1:02:03", NfFormat.duration(3_723_000))
    }

    @Test
    fun paceFromSpeed() {
        assertEquals("5:00", NfFormat.pace(1000.0 / 300))
        assertEquals("3:20", NfFormat.pace(5.0))
        assertEquals("--:--", NfFormat.pace(0.1))
        assertEquals("--:--", NfFormat.pace(null))
    }

    @Test
    fun smallValues() {
        assertEquals("4.2 m", NfFormat.metres(4.24))
        assertEquals("120 m", NfFormat.metres(120.4))
        assertEquals("1.50 km", NfFormat.metres(1500.0))
        assertEquals("--", NfFormat.cadence(null))
        assertEquals("172", NfFormat.cadence(171.6))
        assertEquals("12.6", NfFormat.kmh(3.5))
    }
}
