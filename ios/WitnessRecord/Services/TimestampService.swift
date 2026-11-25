import Foundation

/// Anchors a hash to an independent RFC 3161 Time-Stamp Authority.
///
/// Why this exists: every chunk's SHA-256 already lives in the session
/// manifest, but the manifest sits in the same Drive folder as the footage.
/// Anyone who controls that account could alter both, so on its own the
/// manifest is a self-attested log, not a chain of custody. A TSA counter-signs
/// the manifest's digest with its own key and its own clock, producing proof
/// that this exact manifest — and therefore every hash inside it — existed,
/// unmodified, before a time nobody involved can move.
///
/// Only a 32-byte digest is ever transmitted. The authority never receives the
/// manifest, the footage, or anything about the recording, and cannot
/// reconstruct any of them from the hash.
///
/// The reply is stored verbatim as a `.tsr`, the standard interchange format,
/// so anyone can check it with ordinary tools and no cooperation from us:
///
///     openssl ts -verify -data <manifest>.json -in <timestamp>.tsr -CAfile <tsa-ca>.pem
enum TimestampService {

    /// FreeTSA, a long-standing public authority requiring no account. The
    /// stored reply is self-contained, so a recording's proof stays verifiable
    /// even if this particular service later disappears.
    static let defaultAuthority = URL(string: "https://freetsa.org/tsr")!

    struct TimestampError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    /// Requests a timestamp token over `digest` (a 32-byte SHA-256), returning
    /// the authority's reply as stored on disk.
    static func token(forSHA256 digest: Data,
                      authority: URL = defaultAuthority) async throws -> Data {
        guard digest.count == 32 else {
            throw TimestampError(message: "Expected a 32-byte SHA-256 digest")
        }

        var request = URLRequest(url: authority)
        request.httpMethod = "POST"
        request.setValue("application/timestamp-query", forHTTPHeaderField: "Content-Type")
        request.httpBody = buildRequest(digest: digest)
        request.timeoutInterval = 20

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse,
              (200...299).contains(http.statusCode) else {
            throw TimestampError(
                message: "Timestamp authority returned \((response as? HTTPURLResponse)?.statusCode ?? -1)")
        }
        // PKIStatus 0 = granted, 1 = granted with mods. Anything else means no
        // usable token came back, and storing the reply would imply a proof
        // that isn't there.
        guard let status = pkiStatus(of: data), status == 0 || status == 1 else {
            throw TimestampError(message: "Timestamp request was rejected")
        }
        return data
    }

    // MARK: - DER

    /// Builds a DER-encoded RFC 3161 `TimeStampReq`. Byte-for-byte equivalent
    /// to `openssl ts -query -sha256 -cert` for the same digest and nonce.
    ///
    ///     TimeStampReq ::= SEQUENCE {
    ///         version        INTEGER { v1(1) },
    ///         messageImprint MessageImprint,   -- SHA-256 AlgorithmIdentifier + digest
    ///         nonce          INTEGER OPTIONAL, -- ties this reply to this request
    ///         certReq        BOOLEAN           -- ask for the signing cert back
    ///     }
    static func buildRequest(digest: Data) -> Data {
        let sha256OID = Data([0x06, 0x09, 0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01])
        let derNull = Data([0x05, 0x00])

        let algorithm = der(tag: 0x30, sha256OID + derNull)
        let messageImprint = der(tag: 0x30, algorithm + der(tag: 0x04, digest))
        let version = Data([0x02, 0x01, 0x01])
        // Echoed back by the authority, so a substituted or replayed reply is
        // detectable rather than silently accepted.
        let nonce = der(tag: 0x02, positiveInteger(randomBytes(8)))
        let certReq = Data([0x01, 0x01, 0xff])

        return der(tag: 0x30, version + messageImprint + nonce + certReq)
    }

    private static func der(tag: UInt8, _ body: Data) -> Data {
        var out = Data([tag])
        let length = body.count
        if length < 0x80 {
            out.append(UInt8(length))
        } else {
            var bytes: [UInt8] = []
            var value = length
            while value > 0 {
                bytes.insert(UInt8(value & 0xff), at: 0)
                value >>= 8
            }
            out.append(UInt8(0x80 | bytes.count))
            out.append(contentsOf: bytes)
        }
        out.append(body)
        return out
    }

    /// DER INTEGERs are signed, so a leading byte with its high bit set would
    /// read as negative — prefix a zero to keep the value positive.
    private static func positiveInteger(_ bytes: Data) -> Data {
        guard let first = bytes.first, first & 0x80 != 0 else { return bytes }
        return Data([0x00]) + bytes
    }

    private static func randomBytes(_ count: Int) -> Data {
        Data((0..<count).map { _ in UInt8.random(in: UInt8.min...UInt8.max) })
    }

    /// Reads `status` out of `TimeStampResp ::= SEQUENCE { PKIStatusInfo, ... }`
    /// where `PKIStatusInfo ::= SEQUENCE { status INTEGER, ... }` — i.e. the
    /// first INTEGER two levels in. Deliberately a minimal walk rather than a
    /// general ASN.1 parser: the reply is stored verbatim regardless, and this
    /// only decides whether it is worth storing at all.
    static func pkiStatus(of response: Data) -> Int? {
        var index = response.startIndex
        func readHeader() -> (tag: UInt8, length: Int)? {
            guard index < response.endIndex else { return nil }
            let tag = response[index]
            index = response.index(after: index)
            guard index < response.endIndex else { return nil }
            let first = response[index]
            index = response.index(after: index)
            if first & 0x80 == 0 { return (tag, Int(first)) }
            let byteCount = Int(first & 0x7f)
            guard byteCount > 0, byteCount <= 4 else { return nil }
            var length = 0
            for _ in 0..<byteCount {
                guard index < response.endIndex else { return nil }
                length = (length << 8) | Int(response[index])
                index = response.index(after: index)
            }
            return (tag, length)
        }

        guard let outer = readHeader(), outer.tag == 0x30 else { return nil }
        guard let statusInfo = readHeader(), statusInfo.tag == 0x30 else { return nil }
        guard let status = readHeader(), status.tag == 0x02, status.length > 0 else { return nil }
        var value = 0
        for _ in 0..<status.length {
            guard index < response.endIndex else { return nil }
            value = (value << 8) | Int(response[index])
            index = response.index(after: index)
        }
        return value
    }
}
