package com.witnessrecord.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CrewClockTest {
    @Test fun aSymmetricExchangeGivesTheTrueOffset() {
        // The phone is 2.0 s slow. Each way takes 0.05 s; the server holds the request 0.01 s.
        val t0 = 1000.0
        val t1 = t0 + 2.0 + 0.05
        val t2 = t1 + 0.01
        val t3 = t0 + 0.05 + 0.01 + 0.05
        val r = CrewClock.reading(t0, t1, t2, t3)
        assertEquals(2.0, r.offsetSeconds, 1e-9)
        assertEquals(0.05, r.uncertaintySeconds, 1e-9)
    }

    @Test fun aFastPhoneGetsANegativeOffset() {
        val r = CrewClock.reading(1000.0, 999.545, 999.555, 1000.1)
        assertEquals(-0.5, r.offsetSeconds, 1e-9)
    }

    @Test fun uncertaintyIsNeverNegative() {
        assertEquals(0.0, CrewClock.reading(10.0, 10.0, 10.5, 10.4).uncertaintySeconds, 0.0)
    }

    private fun packet(stratum: Int, leap: Int, t1: Double, t2: Double): ByteArray {
        val p = ByteArray(48)
        p[0] = ((leap shl 6) or (3 shl 3) or 4).toByte()
        p[1] = stratum.toByte()
        fun put(at: Int, unix: Double) {
            val ntp = unix + 2_208_988_800.0
            val seconds = ntp.toLong()
            val fraction = ((ntp - seconds) * 4_294_967_296.0).toLong()
            for (i in 0 until 4) p[at + i] = (seconds shr (8 * (3 - i))).toByte()
            for (i in 0 until 4) p[at + 4 + i] = (fraction shr (8 * (3 - i))).toByte()
        }
        put(32, t1)
        put(40, t2)
        return p
    }

    @Test fun readsTheServersTimestampsFromAPacket() {
        val r = CrewClock.parse(packet(2, 0, 1_700_000_002.05, 1_700_000_002.06), 1_700_000_000.0, 1_700_000_000.11)
        assertNotNull(r)
        assertEquals(2.0, r!!.offsetSeconds, 1e-3)
    }

    @Test fun refusesAServerThatIsNotAGoodSource() {
        assertNull("kiss-of-death", CrewClock.parse(packet(0, 0, 1_700_000_002.0, 1_700_000_002.0), 1.7e9, 1.7e9 + 0.1))
        assertNull("unsynchronised", CrewClock.parse(packet(2, 3, 1_700_000_002.0, 1_700_000_002.0), 1.7e9, 1.7e9 + 0.1))
        assertNull("short", CrewClock.parse(ByteArray(20), 1.7e9, 1.7e9 + 0.1))
    }
}
