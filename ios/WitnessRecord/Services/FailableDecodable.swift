import Foundation

/// Decodes an array element leniently: if one element's JSON doesn't match
/// the current type, `value` is `nil` instead of failing the whole array.
///
/// Used for on-disk queues that must survive a future schema change without
/// losing sibling records — e.g. one `ChunkRecord` with a since-removed field
/// should not erase every other pending chunk from the upload queue.
struct FailableDecodable<T: Decodable>: Decodable {
    let value: T?

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        value = try? container.decode(T.self)
    }
}
