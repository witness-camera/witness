package com.witnessrecord.app.service

import java.io.File
import java.security.MessageDigest

object Hashing {
    /**
     * Streaming SHA-256 of a file on disk. Reads in 1 MB slices so hashing a
     * video chunk - or a whole merged session video - never loads the file
     * into memory.
     */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
