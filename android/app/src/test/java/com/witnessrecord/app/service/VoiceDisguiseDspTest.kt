package com.witnessrecord.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VoiceDisguiseDspTest {

    private val rate = 44_100

    private fun tone(frequency: Double, seconds: Double) =
        FloatArray((rate * seconds).toInt()) { (0.5 * sin(2.0 * PI * frequency * it / rate)).toFloat() }

    /** Frequency estimated from rising zero crossings across the middle of the signal. */
    private fun estimateFrequency(signal: FloatArray): Double {
        val from = signal.size / 4
        val to = signal.size * 3 / 4
        var crossings = 0
        for (i in from + 1 until to) if (signal[i - 1] < 0f && signal[i] >= 0f) crossings++
        return crossings / ((to - from).toDouble() / rate)
    }

    @Test
    fun pitchShiftKeepsLengthAndLowersFrequencyByTheRatio() {
        val input = tone(220.0, 3.0)
        val output = VoiceDisguiseDsp.pitchShift(input, rate, VoiceDisguiseDsp.pitchRatio)

        assertEquals("duration must not change", input.size, output.size)
        val expected = 220.0 * VoiceDisguiseDsp.pitchRatio
        val measured = estimateFrequency(output)
        assertEquals("shifted frequency", expected, measured, expected * 0.04)
    }

    @Test
    fun pitchShiftKeepsRoughlyTheSameLoudness() {
        val input = tone(180.0, 2.0)
        val output = VoiceDisguiseDsp.pitchShift(input, rate, VoiceDisguiseDsp.pitchRatio)
        assertTrue("peak should stay near the input's", VoiceDisguiseDsp.peak(output) in 0.35f..0.65f)
    }

    @Test
    fun highPassRemovesRumbleAndKeepsSpeechRange() {
        fun settledPeak(frequency: Double): Float {
            val filter = VoiceDisguiseDsp.Biquad.highPass(rate.toDouble(), 160.0, 0.7071)
            val out = FloatArray(rate) { filter.step((0.5 * sin(2.0 * PI * frequency * it / rate)).toFloat()) }
            return VoiceDisguiseDsp.peak(out.copyOfRange(rate / 2, rate))
        }
        assertTrue("40 Hz rumble should be strongly attenuated", settledPeak(40.0) < 0.05f)
        assertTrue("1 kHz speech range should pass", settledPeak(1000.0) > 0.45f)
    }

    @Test
    fun blocksOfAnyLengthComeBackTheSameLength() {
        for (seconds in listOf(0.05, 0.3, 1.7, 5.0)) {
            val input = tone(300.0, seconds)
            val output = VoiceDisguiseDsp.Chain(rate).process(input)
            assertEquals("length for ${seconds}s", input.size, output.size)
        }
    }
}
