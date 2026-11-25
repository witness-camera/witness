package com.witnessrecord.app.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Measures how far this phone's clock is from true time, so a crew's
 * recordings can be lined up to within a fraction of a second without the
 * phones ever having to meet.
 *
 * iOS matches clocks phone to phone over Bluetooth/Wi-Fi. Android's crew has
 * no phone-to-phone link, so it compares each phone with a public network time
 * server instead (plain SNTP, RFC 4330). Each phone's offset to true time is
 * stamped into its recording, and every angle lands on the same timeline.
 *
 * The one thing leaving the phone is an ordinary 48-byte time request to a
 * public time server, made when the crew screen is open or a crew recording
 * starts. It carries nothing about the crew.
 */
object CrewClock {
    /** A measurement: add [offsetSeconds] to the phone's clock to get true time,
     *  give or take [uncertaintySeconds]. */
    data class Reading(val offsetSeconds: Double, val uncertaintySeconds: Double)

    private val hosts = listOf("time.android.com", "time.cloudflare.com", "time.google.com")
    private const val SAMPLES = 4
    private const val TIMEOUT_MS = 1_500
    private const val NTP_UNIX_OFFSET_SECONDS = 2_208_988_800L

    /**
     * The tightest of a few quick exchanges with the first server that
     * answers: the one with the shortest round trip is the least
     * contaminated by network delay. Null when no server could be reached.
     */
    suspend fun measure(): Reading? = withContext(Dispatchers.IO) {
        for (host in hosts) {
            val reading = runCatching { measure(host) }.getOrNull()
            if (reading != null) return@withContext reading
        }
        null
    }

    private fun measure(host: String): Reading? {
        val address = InetAddress.getByName(host)
        var best: Reading? = null
        DatagramSocket().use { socket ->
            socket.soTimeout = TIMEOUT_MS
            repeat(SAMPLES) {
                val sample = runCatching { exchange(socket, address) }.getOrNull() ?: return@repeat
                if (best == null || sample.uncertaintySeconds < best!!.uncertaintySeconds) best = sample
            }
        }
        // A server that answers with a round trip over a second isn't giving a
        // measurement worth stamping into evidence.
        return best?.takeIf { it.uncertaintySeconds < 1.0 }
    }

    private fun exchange(socket: DatagramSocket, address: InetAddress): Reading? {
        val request = ByteArray(48)
        request[0] = 0x1B // leap indicator 0, version 3, mode 3 (client)
        val sentWall = System.currentTimeMillis()
        val sentNano = System.nanoTime()
        socket.send(DatagramPacket(request, request.size, address, 123))
        val response = ByteArray(48)
        socket.receive(DatagramPacket(response, response.size))
        val elapsedSeconds = (System.nanoTime() - sentNano) / 1e9
        val t0 = sentWall / 1000.0
        // The reply's arrival on the wall clock, derived from the monotonic
        // timer so a clock step in between can't corrupt the delay.
        val t3 = t0 + elapsedSeconds
        return parse(response, t0, t3)
    }

    /**
     * The offset and its uncertainty from one reply. [t0] is when the request
     * left and [t3] when the reply arrived, both on the phone's clock, in
     * Unix seconds; the server's receive (t1) and transmit (t2) times are in
     * the packet. Pure arithmetic, kept apart from the socket so it can be tested.
     */
    fun parse(response: ByteArray, t0: Double, t3: Double): Reading? {
        if (response.size < 48) return null
        val stratum = response[1].toInt() and 0xFF
        val leap = (response[0].toInt() shr 6) and 0x3
        // Stratum 0 is a "kiss of death"; leap 3 means the server's clock is unsynchronised.
        if (stratum == 0 || leap == 3) return null
        val t1 = timestamp(response, 32)
        val t2 = timestamp(response, 40)
        if (t1 <= 0.0 || t2 <= 0.0) return null
        return reading(t0, t1, t2, t3)
    }

    fun reading(t0: Double, t1: Double, t2: Double, t3: Double): Reading {
        val offset = ((t1 - t0) + (t2 - t3)) / 2
        val delay = (t3 - t0) - (t2 - t1)
        // Half the round trip is the most the one-way delays could disagree by.
        return Reading(offset, maxOf(0.0, delay) / 2)
    }

    private fun timestamp(packet: ByteArray, at: Int): Double {
        var seconds = 0L
        var fraction = 0L
        for (i in 0 until 4) seconds = (seconds shl 8) or (packet[at + i].toLong() and 0xFF)
        for (i in 4 until 8) fraction = (fraction shl 8) or (packet[at + i].toLong() and 0xFF)
        if (seconds == 0L && fraction == 0L) return 0.0
        return (seconds - NTP_UNIX_OFFSET_SECONDS) + fraction / 4_294_967_296.0
    }
}
