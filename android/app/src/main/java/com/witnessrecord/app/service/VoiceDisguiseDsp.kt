package com.witnessrecord.app.service

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sinh

/**
 * The signal processing behind the voice-disguised copy: pitch down by about
 * four and a half semitones with the duration unchanged, then a reshaping of
 * the tone, so speech stays intelligible but the speaker is hard to
 * recognise. The Android counterpart of what the iOS build gets from Apple's
 * time-pitch and EQ audio units, written out because Android has no
 * equivalent offline.
 *
 * Pure arithmetic on sample arrays with no Android types in it, so it can be
 * tested against known signals on a development machine.
 *
 * The pitch shift alone can be partly reversed by shifting it back, so the
 * tone is reshaped as well - thin the chest resonance, lift the upper mids.
 * Even so, this deters ordinary listeners rather than defeating specialist
 * analysis, and the setting says exactly that.
 */
object VoiceDisguiseDsp {

    /** Semitones; low enough to change who a voice sounds like, not so low that words blur. */
    const val PITCH_SEMITONES = -4.5

    /** What the pitch shift multiplies frequencies by. */
    val pitchRatio: Double = 2.0.pow(PITCH_SEMITONES / 12.0)

    /**
     * The whole chain over one block of mono samples. [state] carries the tone
     * filters' memory from one block to the next, so a long recording
     * processed block by block has no seam in its tone.
     */
    class Chain(private val sampleRate: Int) {
        private val tone = Tone(sampleRate)

        fun process(block: FloatArray): FloatArray {
            val shifted = pitchShift(block, sampleRate, pitchRatio)
            tone.process(shifted)
            return shifted
        }
    }

    /**
     * The tonal reshaping on its own: thin the chest resonance (high-pass at
     * 160 Hz) and lift the upper mids (+5 dB around 1.9 kHz). Stateful, so a
     * long recording fed through it block by block has no seam in its tone.
     */
    class Tone(sampleRate: Int) {
        private val highPass = Biquad.highPass(sampleRate.toDouble(), 160.0, 0.7071)
        private val presence = Biquad.peaking(sampleRate.toDouble(), 1900.0, bandwidthOctaves = 1.2, gainDb = 5.0)

        /** Shapes [samples] in place. */
        fun process(samples: FloatArray) {
            for (i in samples.indices) samples[i] = presence.step(highPass.step(samples[i]))
        }
    }

    /**
     * Shifts [input]'s pitch by [ratio] (below 1 lowers it) keeping its
     * length: the signal is first time-compressed by [ratio] with pitch
     * preserved (WSOLA), then played back slower by the same ratio, which
     * lowers the pitch and stretches it back to the original length.
     */
    fun pitchShift(input: FloatArray, sampleRate: Int, ratio: Double): FloatArray {
        if (input.size < 4096) return input.copyOf()
        val compressed = compress(input, sampleRate, ratio)
        return resample(compressed, ratio, input.size)
    }

    /**
     * WSOLA time compression: overlapping windowed frames are taken from the
     * input a little further apart than they are laid down in the output
     * (shortening it), each nudged within a small search to the position that
     * continues the previous frame most smoothly, so the waveform stays
     * coherent and the pitch does not move.
     */
    private fun compress(x: FloatArray, sampleRate: Int, ratio: Double): FloatArray {
        val frame = (round(sampleRate * 0.032).toInt() / 2) * 2     // ~32 ms
        val synthesisHop = frame / 2
        val analysisHop = synthesisHop / ratio
        val tolerance = frame / 4
        val window = FloatArray(frame) { (0.5 - 0.5 * cos(2.0 * PI * it / frame)).toFloat() }

        val frames = ((x.size - frame - tolerance) / analysisHop).toInt().coerceAtLeast(1)
        val out = FloatArray(frames * synthesisHop + frame)
        val weight = FloatArray(out.size)

        // Decimated copy for the coarse correlation search: a quarter of the
        // samples is plenty to find the best alignment, and the refinement
        // below recovers the exact sample.
        val decimation = 4
        val coarse = FloatArray(x.size / decimation) { i ->
            var sum = 0f
            for (k in 0 until decimation) sum += x[i * decimation + k]
            sum / decimation
        }

        var chosen = 0
        for (k in 0 until frames) {
            val nominal = (k * analysisHop).toInt()
            var start = nominal
            if (k > 0) {
                val target = chosen + synthesisHop     // where the last frame would naturally go on
                start = bestOffset(x, coarse, decimation, target, nominal, tolerance, frame)
            }
            start = start.coerceIn(0, x.size - frame)
            val outPos = k * synthesisHop
            for (i in 0 until frame) {
                out[outPos + i] += x[start + i] * window[i]
                weight[outPos + i] += window[i]
            }
            chosen = start
        }
        for (i in out.indices) if (weight[i] > 1e-3f) out[i] /= weight[i]
        return out
    }

    /**
     * The start near [nominal] (within [tolerance]) whose following [frame]
     * samples best match the samples at [target] - the natural continuation of
     * the previous frame - by normalized cross-correlation: coarse on the
     * decimated signal, then refined at full rate.
     */
    private fun bestOffset(
        x: FloatArray, coarse: FloatArray, decimation: Int,
        target: Int, nominal: Int, tolerance: Int, frame: Int,
    ): Int {
        val lo = max(0, nominal - tolerance)
        val hi = min(x.size - frame, nominal + tolerance)
        if (hi <= lo || target + frame > x.size) return nominal.coerceIn(0, max(0, x.size - frame))

        val coarseLength = frame / decimation
        val coarseTarget = target / decimation
        var best = lo
        var bestScore = -Double.MAX_VALUE
        var candidate = lo
        while (candidate <= hi) {
            val c = candidate / decimation
            val score = correlation(coarse, c, coarse, coarseTarget, coarseLength)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
            candidate += decimation
        }
        // Refine around the coarse winner at full rate.
        var refined = best
        var refinedScore = -Double.MAX_VALUE
        for (c in max(lo, best - decimation)..min(hi, best + decimation)) {
            val score = correlation(x, c, x, target, frame / 2)
            if (score > refinedScore) {
                refinedScore = score
                refined = c
            }
        }
        return refined
    }

    /** Normalized cross-correlation of two equal-length stretches. */
    private fun correlation(a: FloatArray, aStart: Int, b: FloatArray, bStart: Int, length: Int): Double {
        if (aStart < 0 || bStart < 0 || aStart + length > a.size || bStart + length > b.size) return -Double.MAX_VALUE
        var dot = 0.0
        var energyA = 0.0
        var energyB = 0.0
        for (i in 0 until length) {
            val va = a[aStart + i].toDouble()
            val vb = b[bStart + i].toDouble()
            dot += va * vb
            energyA += va * va
            energyB += vb * vb
        }
        val norm = kotlin.math.sqrt(energyA * energyB)
        return if (norm < 1e-9) 0.0 else dot / norm
    }

    /** Reads [input] at [step] samples per output sample (linear interpolation), for [length] outputs. */
    private fun resample(input: FloatArray, step: Double, length: Int): FloatArray {
        val out = FloatArray(length)
        for (i in 0 until length) {
            val position = i * step
            val index = position.toInt()
            if (index + 1 >= input.size) break
            val fraction = (position - index).toFloat()
            out[i] = input[index] * (1f - fraction) + input[index + 1] * fraction
        }
        return out
    }

    /** One second-order filter section (RBJ cookbook), direct form I, with its own memory. */
    class Biquad private constructor(
        private val b0: Double, private val b1: Double, private val b2: Double,
        private val a1: Double, private val a2: Double,
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun step(sample: Float): Float {
            val x0 = sample.toDouble()
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x0
            y2 = y1; y1 = y0
            return y0.toFloat()
        }

        companion object {
            fun highPass(sampleRate: Double, frequency: Double, q: Double): Biquad {
                val w0 = 2.0 * PI * frequency / sampleRate
                val alpha = sin(w0) / (2.0 * q)
                val cosW = cos(w0)
                val a0 = 1.0 + alpha
                return Biquad(
                    (1.0 + cosW) / 2.0 / a0, -(1.0 + cosW) / a0, (1.0 + cosW) / 2.0 / a0,
                    -2.0 * cosW / a0, (1.0 - alpha) / a0,
                )
            }

            fun peaking(sampleRate: Double, frequency: Double, bandwidthOctaves: Double, gainDb: Double): Biquad {
                val a = 10.0.pow(gainDb / 40.0)
                val w0 = 2.0 * PI * frequency / sampleRate
                val alpha = sin(w0) * sinh(ln(2.0) / 2.0 * bandwidthOctaves * w0 / sin(w0))
                val cosW = cos(w0)
                val a0 = 1.0 + alpha / a
                return Biquad(
                    (1.0 + alpha * a) / a0, -2.0 * cosW / a0, (1.0 - alpha * a) / a0,
                    -2.0 * cosW / a0, (1.0 - alpha / a) / a0,
                )
            }
        }
    }

    /** Peak absolute value, for tests and sanity checks. */
    fun peak(samples: FloatArray): Float = samples.maxOfOrNull { abs(it) } ?: 0f
}
