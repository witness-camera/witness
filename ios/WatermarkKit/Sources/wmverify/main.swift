import Foundation
import CryptoKit
import WatermarkKit

/// Offline verification tool.
///
/// Published in full, deliberately. Anyone can read exactly what is embedded
/// in Witness footage and how it is checked — which is the only honest way to
/// ship a watermark into an app used by people documenting police. Secrecy
/// lives in the key, never in the method, so publishing this costs nothing
/// and withholding it would buy nothing.
///
/// What this tool can do: given a video and a *candidate* Apple Account
/// identifier, say whether that account produced the video.
///
/// What it cannot do, by construction: name anyone. The payload is a one-way
/// function, so there is no path from a video to an identity. An investigator
/// must already have a suspect, and lawful access to that account, before
/// this tool has anything to say.

struct Arguments {
    var videoPath: String
    var candidateID: String?
    var keyHex: String?
    var minimumConfidence: Double = 0.05
}

func usage() -> Never {
    FileHandle.standardError.write(Data("""
    wmverify — check whether a Witness recording came from a given Apple Account.

    USAGE
      wmverify <video> --account <appTransactionID> --key <hex>
      wmverify <video>                        # report only whether a watermark is present

    OPTIONS
      --account <id>   Candidate appTransactionID, obtained from the account
                       holder's device or from Apple under legal process.
      --key <hex>      Verification key, 64 hex characters.
      --min <value>    Confidence floor below which the result is reported as
                       "not recovered" rather than as a mismatch. Default 0.05.

    NOTES
      A "no match" on a low-confidence read means the watermark could not be
      read, not that the account is excluded. Heavy re-encoding, colour
      grading, frame-rate conversion and clips under ~5 seconds can all
      destroy the payload. Absence of a watermark is never evidence of
      anything.

      This tool answers one question: does this candidate match? It cannot
      produce an account identifier from a video.

    """.utf8))
    exit(2)
}

func parse() -> Arguments {
    var rest = Array(CommandLine.arguments.dropFirst())
    guard let path = rest.first, !path.hasPrefix("--") else { usage() }
    rest.removeFirst()
    var arguments = Arguments(videoPath: path)
    while let flag = rest.first {
        rest.removeFirst()
        guard let value = rest.first else { usage() }
        rest.removeFirst()
        switch flag {
        case "--account": arguments.candidateID = value
        case "--key": arguments.keyHex = value
        case "--min": arguments.minimumConfidence = Double(value) ?? 0.05
        default: usage()
        }
    }
    return arguments
}

func key(fromHex hex: String) -> SymmetricKey? {
    let cleaned = hex.trimmingCharacters(in: .whitespacesAndNewlines)
    guard cleaned.count == 64 else { return nil }
    var bytes = [UInt8]()
    var index = cleaned.startIndex
    while index < cleaned.endIndex {
        let next = cleaned.index(index, offsetBy: 2)
        guard let byte = UInt8(cleaned[index..<next], radix: 16) else { return nil }
        bytes.append(byte)
        index = next
    }
    return SymmetricKey(data: Data(bytes))
}

let arguments = parse()
let url = URL(fileURLWithPath: arguments.videoPath)
guard FileManager.default.fileExists(atPath: url.path) else {
    FileHandle.standardError.write(Data("No such file: \(url.path)\n".utf8))
    exit(2)
}

let watermark = TemporalWatermark()

do {
    let means = try await VideoLuma.meanLumaPerFrame(of: url)
    guard means.count >= TemporalWatermark.chipPeriod else {
        throw WatermarkError.tooShort(frames: means.count, needed: TemporalWatermark.chipPeriod)
    }
    let (bits, confidence) = watermark.decode(meanLuma: means)

    print("file:       \(url.lastPathComponent)")
    print("frames:     \(means.count)")
    print("confidence: \(String(format: "%.3f", confidence))")

    guard confidence >= arguments.minimumConfidence else {
        // Reported as its own outcome rather than as a mismatch: a payload
        // read out of noise would otherwise clear an account that did in
        // fact record the video.
        print("result:     NOT RECOVERED — no readable watermark in this file")
        print("            This does not exclude any account.")
        exit(3)
    }
    guard let payload = WatermarkPayload(bits: bits) else {
        print("result:     NOT RECOVERED — payload malformed")
        exit(3)
    }
    print("watermark:  present")

    guard let candidate = arguments.candidateID, let keyHex = arguments.keyHex else {
        print("result:     PRESENT, NOT CHECKED — pass --account and --key to verify a candidate")
        exit(0)
    }
    guard let verificationKey = key(fromHex: keyHex) else {
        FileHandle.standardError.write(Data("Key must be 64 hex characters.\n".utf8))
        exit(2)
    }

    if payload.matches(candidateID: candidate, key: verificationKey) {
        print("result:     MATCH — this recording was produced by the account you supplied")
        exit(0)
    } else {
        print("result:     NO MATCH — a watermark is present but was not produced by that account")
        exit(1)
    }
} catch {
    FileHandle.standardError.write(Data("\(error.localizedDescription)\n".utf8))
    exit(2)
}
