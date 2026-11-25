package com.witnessrecord.app.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Serializes an epoch-millisecond timestamp as an ISO-8601 UTC string.
 *
 * The manifest is a chain-of-custody document a contact or an attorney may
 * have to read years later, so every timestamp in it is written in a form
 * that is unambiguous without the app - not as a raw number. The iOS build
 * writes the same shape (`.iso8601`), so a manifest from either platform
 * parses identically.
 */
object Iso8601Serializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Iso8601Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeString(
            DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(value).truncateToSeconds())
        )
    }

    override fun deserialize(decoder: Decoder): Long =
        Instant.from(DateTimeFormatter.ISO_INSTANT.parse(decoder.decodeString())).toEpochMilli()

    /**
     * Apple's `.iso8601` strategy emits whole seconds. Matching that keeps a
     * manifest byte-comparable across the two platforms rather than differing
     * only in fractional digits.
     */
    private fun Instant.truncateToSeconds(): Instant = Instant.ofEpochSecond(epochSecond)
}
