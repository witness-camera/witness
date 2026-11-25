package com.witnessrecord.app.service

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.witnessrecord.app.WitnessApplication
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-backed storage for the values that identify where footage goes:
 * the signed-in account and the Drive destination folder ID. The Android
 * counterpart of the iOS build's Keychain wrapper.
 *
 * Unlike the iOS build, no OAuth refresh token is kept here - Play Services
 * owns the Drive credential and never hands it over, so the app has nothing
 * to store. What is left is not a credential but is still personal, so it is
 * encrypted with an AES-256-GCM key generated inside the hardware-backed
 * Android Keystore. The key material never enters the app's process, so a
 * filesystem dump of the data directory yields ciphertext only.
 *
 * Deliberately *not* `setUnlockedDeviceRequired`: uploads have to keep
 * draining while the phone is locked mid-encounter, which is exactly the
 * property `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` gives the iOS
 * build.
 *
 * (This replaces Jetpack's EncryptedSharedPreferences, which is deprecated as
 * of security-crypto 1.1.0.)
 */
object SecureStore {

    enum class Key(val storageKey: String) {
        GOOGLE_ACCOUNT("com.witnessrecord.google.account"),
        DRIVE_FOLDER_ID("com.witnessrecord.drive.folderID"),
        /**
         * WebDAV has no token exchange, so the password itself is the
         * long-lived credential and belongs here rather than alongside the
         * server address in preferences.
         */
        WEBDAV_PASSWORD("com.witnessrecord.webdav.password"),
        DROPBOX_REFRESH_TOKEN("com.witnessrecord.dropbox.refreshToken"),
    }

    private const val KEY_ALIAS = "witness_secure_v1"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    private val prefs: SharedPreferences by lazy {
        WitnessApplication.appContext.getSharedPreferences("witness_secure", Context.MODE_PRIVATE)
    }

    /**
     * The Keystore key, created on first use. Null if the device's Keystore is
     * unavailable - in which case values are stored as-is rather than lost,
     * because failing to remember the Drive folder mid-encounter is worse than
     * storing a folder ID in the clear.
     */
    private val secretKey: SecretKey? by lazy {
        runCatching {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                ?: generateKey()
        }.getOrElse {
            EventLogger.log("keystore.unavailable", detail = it.message)
            null
        }
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun set(value: String, key: Key) {
        prefs.edit().putString(key.storageKey, encrypt(value)).apply()
    }

    fun string(key: Key): String? =
        prefs.getString(key.storageKey, null)?.let { decrypt(it) }

    fun delete(key: Key) {
        prefs.edit().remove(key.storageKey).apply()
    }

    /** Stored as base64 of `iv || ciphertext`; a fresh IV per write. */
    private fun encrypt(value: String): String {
        val key = secretKey ?: return value
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val ciphertext = cipher.doFinal(value.toByteArray())
            Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
        }.getOrElse {
            EventLogger.log("keystore.encrypt_failed", detail = it.message)
            value
        }
    }

    private fun decrypt(stored: String): String? {
        val key = secretKey ?: return stored
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            // Anything shorter than an IV plus a GCM tag was never ciphertext -
            // most likely a value written while the Keystore was unavailable.
            if (bytes.size <= IV_BYTES) return stored
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, bytes, 0, IV_BYTES),
            )
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
        }.getOrElse {
            // A key rotated out from under us (app data restored onto another
            // device, Keystore reset) makes old values undecryptable. Dropping
            // one only costs a fresh Drive-folder lookup.
            EventLogger.log("keystore.decrypt_failed", detail = it.message)
            null
        }
    }
}
