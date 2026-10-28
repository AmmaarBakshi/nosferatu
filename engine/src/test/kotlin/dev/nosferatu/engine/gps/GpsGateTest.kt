package dev.nosferatu.engine.gps

import dev.nosferatu.engine.geo.Geo
import dev.nosferatu.engine.geo.LatLon
import dev.nosferatu.engine.model.GpsFix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GpsGateTest {
    private val start = LatLon(19.06, 72.83)
    private fun fix(t: Long, p: LatLon = start, acc: Double? = 5.0) = GpsFix(t, p.lat, p.lon, acc)

    @Test
    fun acceptsNormalFixes() {
        val g = GpsGate()
        assertIs<FixVerdict.Accepted>(g.assess(fix(0)))
        assertIs<FixVerdict.Accepted>(g.assess(fix(1000, Geo.destination(start, 90.0, 3.5))))
    }

    @Test
    fun rejectsBrokenFixes() {
        val g = GpsGate()
        g.assess(fix(5000))
        assertEquals(FixVerdict.Rejected(FixIssue.OutOfOrder), g.assess(fix(4000)))
        assertEquals(FixVerdict.Rejected(FixIssue.Duplicate), g.assess(fix(5000)))
        assertEquals(FixVerdict.Rejected(FixIssue.Invalid), g.assess(GpsFix(6000, 0.0, 0.0, 5.0)))
        assertEquals(FixVerdict.Rejected(FixIssue.NoAccuracy), g.assess(fix(7000, acc = null)))
        assertEquals(FixVerdict.Rejected(FixIssue.VeryPoorAccuracy), g.assess(fix(8000, acc = 120.0)))
    }

    @Test
    fun downWeightsPoorAccuracy() {
        val v = GpsGate().assess(fix(0, acc = 30.0))
        assertIs<FixVerdict.DownWeighted>(v)
        assertEquals(45.0, v.sigmaM, 1e-9)
    }

    @Test
    fun rejectsTeleportsButNotFastRunning() {
        val g = GpsGate()
        g.assess(fix(0))
        // 6 m/s is a fast run.
        assertIs<FixVerdict.Accepted>(g.assess(fix(1000, Geo.destination(start, 0.0, 6.0))))
        // 400 m in a second.
        assertEquals(FixVerdict.Rejected(FixIssue.ImpossibleSpeed), g.assess(fix(2000, Geo.destination(start, 0.0, 406.0))))
        // The rejected fix is not the new reference: the next normal fix is judged against the last good one.
        assertIs<FixVerdict.Accepted>(g.assess(fix(3000, Geo.destination(start, 0.0, 15.0))))
    }
}
