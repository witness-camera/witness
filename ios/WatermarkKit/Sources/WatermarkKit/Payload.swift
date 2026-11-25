import Foundation
import CryptoKit

/// What gets embedded in a recording: a random nonce, and a keyed tag over
/// the account identifier and that nonce.
///
///     payload = nonce ‖ truncate(HMAC(key, appTransactionID ‖ nonce))
///
/// Two properties this shape buys, both deliberate.
///
/// It is **verify-only**. The tag is a one-way function, so no tool — not
/// even one holding the key — can turn a video into an account identifier.
/// It can only answer "does this candidate match?", which means an
/// investigator must already have a suspect and lawful access to their
/// account. There is no path from an anonymous video to an identity, which
/// is the difference between answering a warrant and building a dragnet.
///
/// It is **unlinkable**. The nonce is fresh per recording, so two videos from
/// the same Apple Account carry completely different payloads. Without it,
/// every video from an account would share one constant tag, and anyone able
/// to read watermarks could cluster a person's recordings across years —
/// then attribute all of them the moment one of those videos happened to
/// show their face. That attack needs no warrant and no cooperation from
/// Apple, so the nonce is not a refinement; it is the reason this design is
/// safe to ship at all.
public struct WatermarkPayload {
    /// Sized against what a temporal watermark can actually carry through
    /// H.264 in a 15-second chunk, not against cryptographic ideals. 40 bits
    /// of tag is a ~1-in-10¹² false match — far below the standard of doubt
    /// this evidence would ever be weighed against, and the identification
    /// never rests on the tag alone.
    public static let nonceBits = 40
    public static let tagBits = 40
    public static var totalBits: Int { nonceBits + tagBits }

    public let nonce: UInt64
    public let tag: UInt64

    public init(nonce: UInt64, tag: UInt64) {
        self.nonce = nonce
        self.tag = tag
    }

    /// Builds the payload for a recording. Call once per chunk: a fresh nonce
    /// per recording is what keeps recordings unlinkable.
    public static func make(appTransactionID: String,
                            key: SymmetricKey,
                            nonce: UInt64 = UInt64.random(in: 0..<(1 << UInt64(nonceBits)))) -> WatermarkPayload {
        WatermarkPayload(nonce: nonce,
                         tag: tag(appTransactionID: appTransactionID, key: key, nonce: nonce))
    }

    /// The keyed tag, truncated to `tagBits`.
    ///
    /// The nonce is bound into the MAC input rather than merely stored beside
    /// it, so a tag lifted from one video cannot be replayed into another.
    static func tag(appTransactionID: String, key: SymmetricKey, nonce: UInt64) -> UInt64 {
        var message = Data(appTransactionID.utf8)
        // Fixed-width big-endian, so the encoding is unambiguous across
        // platforms and no separator is needed between the two fields.
        withUnsafeBytes(of: nonce.bigEndian) { message.append(contentsOf: $0) }

        let mac = HMAC<SHA256>.authenticationCode(for: message, using: key)
        var value: UInt64 = 0
        // Big-endian over the first 8 bytes, then masked down: taking the
        // leading bits of a MAC is the standard truncation, and any subset of
        // a MAC's output is itself a valid (shorter) MAC.
        for byte in Array(mac).prefix(8) {
            value = (value << 8) | UInt64(byte)
        }
        return value & mask(bits: tagBits)
    }

    /// Whether this payload was produced by `candidateID` under `key`.
    ///
    /// Constant-time comparison: the tag is short enough that a timing oracle
    /// could otherwise let someone recover it a few bits at a time by feeding
    /// candidates to a verifier.
    public func matches(candidateID: String, key: SymmetricKey) -> Bool {
        let expected = Self.tag(appTransactionID: candidateID, key: key, nonce: nonce)
        var difference = expected ^ tag
        difference |= difference >> 32
        difference |= difference >> 16
        difference |= difference >> 8
        difference |= difference >> 4
        difference |= difference >> 2
        difference |= difference >> 1
        return (difference & 1) == 0
    }

    // MARK: - Bit packing

    /// Payload as a bit vector, nonce first, most-significant bit first.
    public var bits: [Bool] {
        Self.bits(of: nonce, count: Self.nonceBits) + Self.bits(of: tag, count: Self.tagBits)
    }

    public init?(bits: [Bool]) {
        guard bits.count == Self.totalBits else { return nil }
        self.nonce = Self.value(of: Array(bits.prefix(Self.nonceBits)))
        self.tag = Self.value(of: Array(bits.suffix(Self.tagBits)))
    }

    private static func bits(of value: UInt64, count: Int) -> [Bool] {
        (0..<count).map { (value >> UInt64(count - 1 - $0)) & 1 == 1 }
    }

    private static func value(of bits: [Bool]) -> UInt64 {
        bits.reduce(into: UInt64(0)) { $0 = ($0 << 1) | ($1 ? 1 : 0) }
    }

    private static func mask(bits: Int) -> UInt64 {
        bits >= 64 ? .max : (UInt64(1) << UInt64(bits)) - 1
    }
}
