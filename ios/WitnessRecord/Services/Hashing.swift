import Foundation
import CryptoKit

enum Hashing {
    /// Streaming SHA-256 of a file on disk. Reads in 1 MB slices so hashing a
    /// video chunk never loads the whole file into memory.
    static func sha256Hex(of fileURL: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: fileURL)
        defer { try? handle.close() }

        var hasher = SHA256()
        while autoreleasepool(invoking: {
            guard let data = try? handle.read(upToCount: 1_048_576), !data.isEmpty else { return false }
            hasher.update(data: data)
            return true
        }) {}

        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}
