import XCTest
import CryptoKit
@testable import WatermarkKit

final class PayloadTests: XCTestCase {
    let key = SymmetricKey(data: Data(repeating: 0xAB, count: 32))

    func testMatchesOnlyTheIssuingAccount() {
        let payload = WatermarkPayload.make(appTransactionID: "70001234567", key: key)
        XCTAssertTrue(payload.matches(candidateID: "70001234567", key: key))
        XCTAssertFalse(payload.matches(candidateID: "70001234568", key: key))
    }

    func testWrongKeyDoesNotMatch() {
        let payload = WatermarkPayload.make(appTransactionID: "70001234567", key: key)
        let other = SymmetricKey(data: Data(repeating: 0x11, count: 32))
        XCTAssertFalse(payload.matches(candidateID: "70001234567", key: other))
    }

    /// The property the whole design rests on: two recordings from the same
    /// account must share nothing an observer could correlate.
    func testRecordingsFromOneAccountAreUnlinkable() {
        let id = "70001234567"
        let first = WatermarkPayload.make(appTransactionID: id, key: key)
        let second = WatermarkPayload.make(appTransactionID: id, key: key)
        XCTAssertNotEqual(first.nonce, second.nonce)
        XCTAssertNotEqual(first.tag, second.tag)
        // Both still verify against the account that made them.
        XCTAssertTrue(first.matches(candidateID: id, key: key))
        XCTAssertTrue(second.matches(candidateID: id, key: key))
    }

    /// A tag lifted from one recording must not verify when pasted into
    /// another, or an adversary could transplant attribution between videos.
    func testTagIsBoundToItsNonce() {
        let id = "70001234567"
        let first = WatermarkPayload.make(appTransactionID: id, key: key)
        let second = WatermarkPayload.make(appTransactionID: id, key: key)
        let transplanted = WatermarkPayload(nonce: second.nonce, tag: first.tag)
        XCTAssertFalse(transplanted.matches(candidateID: id, key: key))
    }

    func testBitRoundTrip() {
        let payload = WatermarkPayload.make(appTransactionID: "70001234567", key: key)
        let bits = payload.bits
        XCTAssertEqual(bits.count, WatermarkPayload.totalBits)
        let restored = WatermarkPayload(bits: bits)
        XCTAssertEqual(restored?.nonce, payload.nonce)
        XCTAssertEqual(restored?.tag, payload.tag)
    }
}

final class TemporalWatermarkTests: XCTestCase {
    let key = SymmetricKey(data: Data(repeating: 0xAB, count: 32))
    /// 15 seconds at 30fps — one chunk, the unit this app actually records.
    let frameCount = 450

    /// Stands in for a real scene: slow brightness drift plus per-frame
    /// noise, both far larger than the watermark, so the decoder has to
    /// actually reject content rather than getting a clean signal handed to it.
    func syntheticScene(frameCount: Int, seed: UInt64 = 99) -> [Double] {
        var value = seed
        func next() -> Double {
            value = (value &* 6364136223846793005) &+ 1442695040888963407
            return Double((value >> 33) % 10000) / 10000.0
        }
        return (0..<frameCount).map { frame in
            let drift = 110 + 25 * sin(Double(frame) / 60.0)
            let flicker = (next() - 0.5) * 3.0
            return drift + flicker
        }
    }

    func testRecoversPayloadFromCleanSignal() {
        let watermark = TemporalWatermark()
        let payload = WatermarkPayload.make(appTransactionID: "70001234567", key: key)
        let offsets = watermark.frameOffsets(bits: payload.bits, frameCount: frameCount)
        let scene = syntheticScene(frameCount: frameCount)
        let watermarked = zip(scene, offsets).map(+)

        let (bits, confidence) = watermark.decode(meanLuma: watermarked)
        XCTAssertEqual(bits, payload.bits)
        XCTAssertGreaterThan(confidence, 0)
        XCTAssertTrue(WatermarkPayload(bits: bits)!.matches(candidateID: "70001234567", key: key))
    }

    /// Unwatermarked footage must not produce a payload that verifies against
    /// anything — a false positive here is an innocent person attributed.
    func testCleanFootageDoesNotFalselyMatch() {
        let watermark = TemporalWatermark()
        let scene = syntheticScene(frameCount: frameCount, seed: 7)
        let (bits, _) = watermark.decode(meanLuma: scene)
        let payload = WatermarkPayload(bits: bits)!
        XCTAssertFalse(payload.matches(candidateID: "70001234567", key: key))
    }

    /// Quantisation to whole luma levels is the least an encoder will do.
    func testSurvivesQuantisation() {
        let watermark = TemporalWatermark()
        let payload = WatermarkPayload.make(appTransactionID: "70001234567", key: key)
        let offsets = watermark.frameOffsets(bits: payload.bits, frameCount: frameCount)
        let scene = syntheticScene(frameCount: frameCount)
        let watermarked = zip(scene, offsets).map { ($0 + $1).rounded() }

        let (bits, _) = watermark.decode(meanLuma: watermarked)
        XCTAssertEqual(bits, payload.bits)
    }

    /// A crop changes which pixels are averaged, so the mean shifts and the
    /// noise differs — but the watermark offset is identical in every pixel,
    /// so it must come through untouched.
    func testSurvivesCropAndExposureShift() {
        let watermark = TemporalWatermark()
        let payload = WatermarkPayload.make(appTransactionID: "70001234567", key: key)
        let offsets = watermark.frameOffsets(bits: payload.bits, frameCount: frameCount)
        let scene = syntheticScene(frameCount: frameCount)
        // Different region: different content mean, different noise, same offset.
        let cropped = syntheticScene(frameCount: frameCount, seed: 4242)
        let watermarked = zip(cropped, offsets).map { ($0 * 0.9 + 12) + $1 }
        _ = scene

        let (bits, _) = watermark.decode(meanLuma: watermarked)
        XCTAssertEqual(bits, payload.bits)
    }

    /// Trimming the tail is ordinary editing; the payload should still read.
    func testSurvivesTrimming() {
        let watermark = TemporalWatermark()
        let payload = WatermarkPayload.make(appTransactionID: "70001234567", key: key)
        let offsets = watermark.frameOffsets(bits: payload.bits, frameCount: frameCount)
        let scene = syntheticScene(frameCount: frameCount)
        let watermarked = Array(zip(scene, offsets).map(+).prefix(320))

        let (bits, _) = watermark.decode(meanLuma: watermarked)
        XCTAssertEqual(bits, payload.bits)
    }

    /// How much additive noise the payload tolerates before it stops reading.
    ///
    /// Deliberately measured over many fixed nonces rather than one random
    /// payload: an earlier version of this test used the default random nonce
    /// and reported anywhere from ±1.5 to ±4.0 between runs, which is not a
    /// robustness figure, it is a coin flip. The reported number is the
    /// highest level at which *every* trial still reads.
    func testNoiseTolerance() {
        let watermark = TemporalWatermark()
        let trials = 24

        var strongest = 0.0
        for noise in stride(from: 0.0, through: 8.0, by: 0.5) {
            var allSurvived = true
            for trial in 0..<trials {
                let payload = WatermarkPayload.make(
                    appTransactionID: "70001234567", key: key,
                    nonce: UInt64(trial &* 2_654_435_761) & ((1 << 40) - 1))
                let offsets = watermark.frameOffsets(bits: payload.bits, frameCount: frameCount)
                var value = UInt64(trial &+ 1) &* 0x9E37_79B9
                func next() -> Double {
                    value = (value &* 6364136223846793005) &+ 1442695040888963407
                    return Double((value >> 33) % 10000) / 10000.0
                }
                let scene = syntheticScene(frameCount: frameCount, seed: UInt64(trial &+ 3))
                let watermarked = zip(scene, offsets).map { $0 + $1 + (next() - 0.5) * noise }
                if watermark.decode(meanLuma: watermarked).bits != payload.bits {
                    allSurvived = false
                    break
                }
            }
            if allSurvived { strongest = noise }
        }
        print("[watermark] all \(trials) payloads survived additive noise up to ±\(strongest) luma levels "
              + "(amplitude \(watermark.amplitude))")
        XCTAssertGreaterThanOrEqual(strongest, 1.0)
    }
}
