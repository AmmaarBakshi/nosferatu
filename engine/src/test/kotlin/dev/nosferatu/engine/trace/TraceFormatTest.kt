package dev.nosferatu.engine.trace

import dev.nosferatu.engine.activity.Activity
import dev.nosferatu.engine.geo.LatLon
import dev.nosferatu.engine.model.AccelSample
import dev.nosferatu.engine.model.GpsFix
import dev.nosferatu.engine.model.GyroSample
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TraceFormatTest {
    @Test
    fun roundTripsEveryRecordType() {
        val events = listOf(
            TraceEvent.Note(0, "scenario: test, with commas"),
            TraceEvent.Gps(GpsFix(1000, 19.0596123, 72.8295456, 4.5, 3.21, 0.4, 87.5, 12.0)),
            TraceEvent.Gps(GpsFix(2000, 19.0596, 72.8295, 8.0)),
            TraceEvent.Accel(AccelSample(1020, 0.123, -9.806, 1.5)),
            TraceEvent.Gyro(GyroSample(1020, 0.0123, -0.0045, 0.5)),
            TraceEvent.Truth(1000, LatLon(19.0596, 72.8295), Activity.Running),
        )
        val w = StringWriter()
        TraceFormat.writeAll(events, w)
        val back = TraceFormat.read(w.toString().reader().buffered())
        assertEquals(events, back)
    }

    @Test
    fun ignoresWhatItDoesNotKnow() {
        assertNull(TraceFormat.decode("# comment"))
        assertNull(TraceFormat.decode(""))
        assertNull(TraceFormat.decode("x,100,future record"))
        assertNull(TraceFormat.decode("g,notatime,1,2"))
    }
}
