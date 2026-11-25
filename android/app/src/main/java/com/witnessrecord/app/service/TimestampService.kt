package com.witnessrecord.app.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URL
import java.security.SecureRandom

/**
 * Anchors a hash to an independent RFC 3161 Time-Stamp Authority.
 *
 * Why this exists: every chunk's SHA-256 already lives in the session
 * manifest, but the manifest sits in the same folder as the footage. Anyone
 * who controls that account could alter both, so on its own the manifest is a
 * self-attested log, not a chain of custody. A TSA counter-signs the
 * manifest's digest with its own key and its own clock, producing proof that
 * this exact manifest - and therefore every hash inside it - existed,
 * unmodified, before a time nobody involved can move.
 *
 * Only a 32-byte digest is ever transmitted. The authority never receives the
 * manifest, the footage, or anything about the recording, and cannot
 * reconstruct any of them from the hash.
 *
 * The reply is stored verbatim as a `.tsr`, the standard interchange format,
 * so anyone can check it with ordinary tools and no cooperation from us:
 *
 *     openssl ts -verify -data <manifest>.json -in <timestamp>.tsr -CAfile <tsa-ca>.pem
 */
object TimestampService {

    /**
     * FreeTSA, a long-standing public authority requiring no account. The
     * stored reply is self-contained, so a recording's proof stays verifiable
     * even if this particular service later disappears.
     */
    val DEFAULT_AUTHORITY: URL = URL("https://freetsa.org/tsr")

    class TimestampException(message: String) : IOException(message)

    /**
     * Requests a timestamp token over [digest] (a 32-byte SHA-256), returning
     * the authority's reply as stored on disk.
     */
    suspend fun token(digest: ByteArray, authority: URL = DEFAULT_AUTHORITY): ByteArray {
        require(digest.size == 32) { "Expected a 32-byte SHA-256 digest" }

        val body = buildRequest(digest)
        val request = Request.Builder()
            .url(authority)
            .header("Content-Type", "application/timestamp-query")
            .post(body.toRequestBody("application/timestamp-query".toMediaType()))
            .build()

        return withContext(Dispatchers.IO) {
            HttpClients.shared.newCall(request).execute().use { response ->
                val responseBody = response.body?.bytes()
                if (!response.isSuccessful || responseBody == null) {
                    throw TimestampException("Timestamp authority returned ${response.code}")
                }
                // PKIStatus 0 = granted, 1 = granted with mods. Anything else
                // means no usable token came back, and storing the reply
                // would imply a proof that isn't there.
                val status = pkiStatus(responseBody)
                if (status != 0 && status != 1) {
                    throw TimestampException("Timestamp request was rejected")
                }
                responseBody
            }
        }
    }

    // MARK: - DER

    /**
     * Builds a DER-encoded RFC 3161 `TimeStampReq`. Byte-for-byte equivalent
     * to `openssl ts -query -sha256 -cert` for the same digest and nonce.
     *
     *     TimeStampReq ::= SEQUENCE {
     *         version        INTEGER { v1(1) },
     *         messageImprint MessageImprint,   -- SHA-256 AlgorithmIdentifier + digest
     *         nonce          INTEGER OPTIONAL, -- ties this reply to this request
     *         certReq        BOOLEAN           -- ask for the signing cert back
     *     }
     */
    fun buildRequest(digest: ByteArray): ByteArray {
        val sha256Oid = byteArrayOf(
            0x06, 0x09, 0x60.toByte(), 0x86.toByte(), 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01
        )
        val derNull = byteArrayOf(0x05, 0x00)

        val algorithm = der(0x30, sha256Oid + derNull)
        val messageImprint = der(0x30, algorithm + der(0x04, digest))
        val version = byteArrayOf(0x02, 0x01, 0x01)
        // Echoed back by the authority, so a substituted or replayed reply is
        // detectable rather than silently accepted.
        val nonce = der(0x02, positiveInteger(randomBytes(8)))
        val certReq = byteArrayOf(0x01, 0x01, 0xff.toByte())

        return der(0x30, version + messageImprint + nonce + certReq)
    }

    private fun der(tag: Int, body: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        out.add(tag.toByte())
        val length = body.size
        if (length < 0x80) {
            out.add(length.toByte())
        } else {
            var value = length
            val bytes = ArrayList<Byte>()
            while (value > 0) {
                bytes.add(0, (value and 0xff).toByte())
                value = value shr 8
            }
            out.add((0x80 or bytes.size).toByte())
            out.addAll(bytes)
        }
        out.addAll(body.toList())
        return out.toByteArray()
    }

    /**
     * DER INTEGERs are signed, so a leading byte with its high bit set would
     * read as negative - prefix a zero to keep the value positive.
     */
    private fun positiveInteger(bytes: ByteArray): ByteArray {
        val first = bytes.firstOrNull() ?: return bytes
        return if (first.toInt() and 0x80 != 0) byteArrayOf(0x00) + bytes else bytes
    }

    private fun randomBytes(count: Int): ByteArray {
        val bytes = ByteArray(count)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    /**
     * Reads `status` out of `TimeStampResp ::= SEQUENCE { PKIStatusInfo, ... }`
     * where `PKIStatusInfo ::= SEQUENCE { status INTEGER, ... }` - i.e. the
     * first INTEGER two levels in. Deliberately a minimal walk rather than a
     * general ASN.1 parser: the reply is stored verbatim regardless, and this
     * only decides whether it is worth storing at all.
     */
    fun pkiStatus(response: ByteArray): Int? {
        var index = 0
        fun readHeader(): Pair<Int, Int>? {
            if (index >= response.size) return null
            val tag = response[index].toInt() and 0xff
            index += 1
            if (index >= response.size) return null
            val first = response[index].toInt() and 0xff
            index += 1
            if (first and 0x80 == 0) return tag to first
            val byteCount = first and 0x7f
            if (byteCount <= 0 || byteCount > 4) return null
            var length = 0
            repeat(byteCount) {
                if (index >= response.size) return null
                length = (length shl 8) or (response[index].toInt() and 0xff)
                index += 1
            }
            return tag to length
        }

        val outer = readHeader() ?: return null
        if (outer.first != 0x30) return null
        val statusInfo = readHeader() ?: return null
        if (statusInfo.first != 0x30) return null
        val status = readHeader() ?: return null
        if (status.first != 0x02 || status.second <= 0) return null
        var value = 0
        repeat(status.second) {
            if (index >= response.size) return null
            value = (value shl 8) or (response[index].toInt() and 0xff)
            index += 1
        }
        return value
    }
}
