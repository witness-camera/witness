import Foundation

/// Embeds a bit vector into a video by nudging each frame's overall
/// brightness by a sub-perceptual amount, in a pattern only the matching
/// decoder can read.
///
/// Why brightness over time rather than pixels in a region: a spatial
/// watermark has to survive the encoder that runs immediately after it, and
/// anything subtle enough to be invisible in the spatial domain is quantised
/// away by H.264 before the file is even written. A uniform shift in a
/// frame's mean luma survives, because encoders reproduce average brightness
/// faithfully — it is the thing they are least willing to throw away.
///
/// It also makes cropping irrelevant. The shift is applied to every pixel, so
/// any sub-rectangle of the frame carries the same shift as the whole. A
/// crop, a resize, a letterbox, or a re-frame for social video all preserve
/// the signal exactly, which no spatial watermark can claim.
///
/// Each bit is spread across *every* frame using its own pseudorandom chip
/// sequence, rather than given a few frames of its own. Dropping or trimming
/// frames then degrades every bit slightly instead of destroying a few
/// outright, and the per-frame amplitude stays far below perception because
/// no single frame carries a whole bit.
public struct TemporalWatermark {
    /// How far a frame's mean luma may be moved, in 8-bit levels. At 2.0 the
    /// shift is roughly one part in 128 — under the threshold at which a
    /// viewer perceives flicker, and above the noise floor H.264 leaves at
    /// the bit rates this app records.
    public let amplitude: Double
    public let bitCount: Int
    /// Public, deliberately. The chip pattern is not a secret — secrecy lives
    /// in the HMAC key, so that publishing this tool for audit costs nothing.
    public let chipSeed: UInt64

    public init(bitCount: Int = WatermarkPayload.totalBits,
                amplitude: Double = 2.0,
                chipSeed: UInt64 = 0x5749_544E_4553_5301) {
        self.bitCount = bitCount
        self.amplitude = amplitude
        self.chipSeed = chipSeed
        self.rows = Self.chipRows(count: bitCount)
    }

    // MARK: - Chips

    /// Length of one chip period. 128 frames is ~4.3s at 30fps, so a
    /// 15-second chunk carries three whole periods.
    public static let chipPeriod = 128

    /// Chip sequences, one row per bit, each ±1 over `chipPeriod` frames.
    ///
    /// Walsh–Hadamard rows rather than pseudorandom sequences. Random chips
    /// are only *approximately* orthogonal: correlating for one bit picks up
    /// cross-talk from all the others, which grows as √(bits × frames) while
    /// the signal grows as frames. At 80 bits in 450 frames that lands near a
    /// signal-to-noise ratio of 2.4 — enough to get most bits right and
    /// roughly one wrong, which for an all-or-nothing payload means it never
    /// reads. Hadamard rows are exactly orthogonal over a whole period, so
    /// cross-talk is zero by construction and the only noise left is the
    /// picture itself.
    ///
    /// The rows are chosen by *sequency* — how often each flips sign. Low
    /// sequency rows change slowly, which is precisely what the decoder's
    /// high-pass discards along with the content, so the slowest half of the
    /// matrix would be silently unreadable. Taking the fastest rows keeps
    /// every bit well clear of the filter.
    static func chipRows(count: Int) -> [[Double]] {
        precondition(count <= chipPeriod, "at most \(chipPeriod) bits per period")
        let order = chipPeriod
        // Sylvester construction: H[i][j] = (-1)^popcount(i & j).
        var rows: [(sequency: Int, row: [Double])] = (0..<order).map { i in
            let row = (0..<order).map { j -> Double in
                (i & j).nonzeroBitCount % 2 == 0 ? 1.0 : -1.0
            }
            let sequency = zip(row, row.dropFirst()).reduce(0) { $0 + ($1.0 != $1.1 ? 1 : 0) }
            return (sequency, row)
        }
        // Fastest first; ties broken by index so the ordering is stable and
        // the decoder reproduces it exactly.
        rows.sort { $0.sequency > $1.sequency }
        return rows.prefix(count).map(\.row)
    }

    /// Built once per instance. Both `frameOffsets` and `decode` walk every
    /// bit against every frame — tens of thousands of lookups — so this must
    /// be a stored property rather than rebuilt, or even cached behind a
    /// dictionary: the lookup itself was costing more than the arithmetic.
    let rows: [[Double]]

    /// The ±1 chip for one bit in one frame.
    func chip(bit: Int, frame: Int) -> Double {
        rows[bit][frame % Self.chipPeriod]
    }

    // MARK: - Encode

    /// The luma offset to add to every pixel of each frame.
    ///
    /// Normalised by √bitCount so the sum of many ±1 chips lands near
    /// `amplitude` rather than growing with the payload size — otherwise a
    /// longer payload would mean visible flicker.
    public func frameOffsets(bits: [Bool], frameCount: Int) -> [Double] {
        precondition(bits.count == bitCount, "payload is \(bits.count) bits, expected \(bitCount)")
        let scale = amplitude / Double(bitCount).squareRoot()
        let signs = bits.map { $0 ? 1.0 : -1.0 }
        return (0..<frameCount).map { frame in
            let phase = frame % Self.chipPeriod
            var sum = 0.0
            for bit in 0..<bitCount {
                sum += signs[bit] * rows[bit][phase]
            }
            return sum * scale
        }
    }

    // MARK: - Decode

    /// Recovers the payload from the per-frame mean luma of a video.
    ///
    /// Returns the bits alongside a confidence in [0, 1] — the normalised
    /// margin of the weakest bit. A recovered payload whose confidence is low
    /// is a guess, and the caller is expected to treat it as "not recovered"
    /// rather than report a match; that distinction is the difference between
    /// evidence and noise.
    public func decode(meanLuma: [Double]) -> (bits: [Bool], confidence: Double) {
        // Correlate over whole chip periods only. The rows are orthogonal
        // across a full period and not across part of one, so including a
        // partial tail would reintroduce precisely the cross-talk the
        // Hadamard construction exists to remove — a few frames of extra
        // signal bought at the cost of every bit's margin.
        let periods = meanLuma.count / Self.chipPeriod
        guard periods >= 1 else { return ([], 0) }
        let frameCount = periods * Self.chipPeriod

        // The picture's own brightness swamps the watermark by orders of
        // magnitude, but it moves slowly — a person walks, a cloud passes —
        // while the chips flip every frame or two. Subtracting a short moving
        // average removes the content and leaves the signal.
        let residual = Array(highPass(meanLuma).prefix(frameCount))

        var bits = [Bool]()
        var scores = [Double]()
        for bit in 0..<bitCount {
            var correlation = 0.0
            let row = rows[bit]
            for frame in 0..<frameCount {
                correlation += residual[frame] * row[frame % Self.chipPeriod]
            }
            bits.append(correlation > 0)
            scores.append(abs(correlation))
        }

        // Confidence is set by the *weakest* bit: one uncertain bit is enough
        // to make the whole payload wrong, so an average would flatter it.
        let energy = residual.reduce(0) { $0 + $1 * $1 }
        let reference = (energy * Double(frameCount)).squareRoot()
        let weakest = scores.min() ?? 0
        let confidence = reference > 0 ? min(1.0, weakest / reference * Double(bitCount).squareRoot()) : 0
        return (bits, confidence)
    }

    /// Removes the slow-moving content brightness, keeping the fast chips.
    func highPass(_ signal: [Double], window: Int = 9) -> [Double] {
        guard signal.count > window else { return signal }
        let half = window / 2
        return signal.indices.map { i in
            let lower = max(0, i - half)
            let upper = min(signal.count - 1, i + half)
            var sum = 0.0
            for j in lower...upper { sum += signal[j] }
            return signal[i] - sum / Double(upper - lower + 1)
        }
    }
}
