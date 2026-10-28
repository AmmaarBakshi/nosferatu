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

    @Test
    fun aStaleCopyWhileMovingIsRejectedButStaticHoldIsNot() {
        val g = GpsGate()
        val moving = GpsFix(0, start.lat, start.lon, 5.0, speedMps = 3.0)
        g.assess(moving)
        assertEquals(FixVerdict.Rejected(FixIssue.Stale), g.assess(moving.copy(timeMs = 1000)))
        // A chipset in static hold repeats the position with no speed: that is a still phone, not a stale fix.
        val g2 = GpsGate()
        val still = GpsFix(0, start.lat, start.lon, 5.0, speedMps = 0.0)
        g2.assess(still)
        assertIs<FixVerdict.Accepted>(g2.assess(still.copy(timeMs = 1000)))
    }

    @Test
    fun aConsistentRunOfTeleportsBecomesTheNewReference() {
        val g = GpsGate()
        // A wrong first fix 350 m away, then the runner's real fixes.
        g.assess(fix(0, Geo.destination(start, 45.0, 350.0)))
        val verdicts = (1..6).map { i -> g.assess(fix(i * 1000L, Geo.destination(start, 90.0, 3.0 * i))) }
        assertEquals(List(4) { FixVerdict.Rejected(FixIssue.ImpossibleSpeed) }, verdicts.take(4))
        assertIs<FixVerdict.Reanchored>(verdicts[4])
        assertIs<FixVerdict.Accepted>(verdicts[5])
    }

    @Test
    fun aShortJumpDoesNotReanchor() {
        val g = GpsGate()
        g.assess(fix(0))
        // Three fixes 300 m off, then back: the jump is rejected and the truth is accepted again.
        val jump = (1..3).map { i -> g.assess(fix(i * 1000L, Geo.destination(start, 0.0, 300.0 + i))) }
        assertEquals(List(3) { FixVerdict.Rejected(FixIssue.ImpossibleSpeed) }, jump)
        assertIs<FixVerdict.Accepted>(g.assess(fix(4000, Geo.destination(start, 90.0, 4.0))))
    }
}
