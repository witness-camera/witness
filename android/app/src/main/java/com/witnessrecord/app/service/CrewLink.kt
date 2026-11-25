package com.witnessrecord.app.service

import com.witnessrecord.app.model.Crew
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * A crew packed into a link, for a QR code shown in person or an invite sent
 * by message. Byte-for-byte the iOS build's `CrewLink`, so each platform opens
 * the other's codes.
 *
 * The crew travels in the URL's fragment, after the `#`: browsers never send a
 * fragment to the server, so opening the web link reveals nothing about the
 * crew to witnesscamera.com, which only hands it on to the app.
 *
 * The token is the crew's JSON, deflated *raw* (no zlib header - that is what
 * Apple's `.zlib` compression produces) and written as unpadded base64url.
 */
object CrewLink {
    const val WEB_BASE = "https://witnesscamera.com/crew"

    /** The link to share: the web form, because a `witnessrecord://` link isn't
     *  tappable in most messaging apps; the page passes it to the app. */
    fun url(crew: Crew): String = "$WEB_BASE#${token(crew)}"

    /** Accepts the web link, the app's own scheme (`witnessrecord://crew#...`)
     *  or a bare token, as a scanner or a paste might hand over. */
    fun parse(fromText: String): Crew? {
        val text = fromText.trim()
        if (text.isEmpty()) return null
        val hash = text.indexOf('#')
        if (hash < 0) {
            // No link around it: only a token-shaped string is worth trying.
            return if (text.none { it.isWhitespace() || it == '/' || it == ':' }) parseToken(text) else null
        }
        val fragment = text.substring(hash + 1)
        val head = text.substring(0, hash)
        val uri = runCatching { URI(head) }.getOrNull() ?: return null
        val isWeb = uri.host == "witnesscamera.com" && (uri.path ?: "").startsWith("/crew")
        val isApp = uri.scheme == "witnessrecord" && uri.host == "crew"
        if (!isWeb && !isApp) return null
        return parseToken(fragment)
    }

    fun token(crew: Crew): String {
        val json = Crew.json.encodeToString(Crew.serializer(), crew).toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out, Deflater(Deflater.BEST_COMPRESSION, true)).use { it.write(json) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    fun parseToken(fromToken: String): Crew? {
        val packed = runCatching { Base64.getUrlDecoder().decode(fromToken.trim()) }.getOrNull() ?: return null
        // Raw deflate is what both platforms write; a zlib-wrapped one is
        // accepted too, in case some other tool produced the token.
        val json = inflate(packed, nowrap = true) ?: inflate(packed, nowrap = false) ?: return null
        return runCatching {
            Crew.json.decodeFromString(Crew.serializer(), json.toString(Charsets.UTF_8)).normalized()
        }.getOrNull()
    }

    private fun inflate(data: ByteArray, nowrap: Boolean): ByteArray? = runCatching {
        InflaterInputStream(data.inputStream(), Inflater(nowrap)).use { it.readBytes() }
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}
