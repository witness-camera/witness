package com.witnessrecord.app.model

import android.content.Context
import com.witnessrecord.app.service.Prefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.encodeToString
import java.util.UUID

/**
 * Someone who is automatically granted access to a recording's Drive folder
 * as soon as that recording starts uploading, so the footage reaches a second
 * person even if the phone is taken.
 */
@Serializable
data class EmergencyContact(
    val id: String,
    val name: String,
    /**
     * Email address the session folder is shared with. Contacts without one
     * are stored but never shared via Drive.
     */
    val email: String? = null,
    /**
     * Phone number for the location-ping text. Optional and independent of
     * [email] - a contact can have either, both, or (until they add one)
     * neither, and the ping goes out over whichever they have on file.
     */
    val phone: String? = null,
) {
    val hasValidEmail: Boolean
        get() {
            val address = email ?: return false
            return address.contains("@") && !address.startsWith("@") && !address.endsWith("@")
        }

    /**
     * Whether the storage provider should be told to share the session folder
     * with this contact - the same email-validity check as any other use,
     * named for the one place that reads it.
     */
    val canReceiveFootage: Boolean get() = hasValidEmail

    val canReceiveSMS: Boolean get() = !phone.isNullOrBlank()

    companion object {
        private const val STORAGE_KEY = "emergencyContacts"
        private val json = Json { ignoreUnknownKeys = true }

        fun newContact(name: String, email: String?, phone: String? = null) =
            EmergencyContact(UUID.randomUUID().toString(), name, email, phone)

        /**
         * Lenient per-contact decoding: one malformed entry shouldn't erase
         * every other saved contact.
         */
        fun load(context: Context): List<EmergencyContact> {
            val raw = Prefs.of(context).getString(STORAGE_KEY, null) ?: return emptyList()
            val elements: JsonArray = runCatching { json.parseToJsonElement(raw).jsonArray }
                .getOrNull() ?: return emptyList()
            return elements.mapNotNull {
                runCatching { json.decodeFromJsonElement(serializer(), it) }.getOrNull()
            }
        }

        fun save(context: Context, contacts: List<EmergencyContact>) {
            Prefs.of(context).edit()
                .putString(STORAGE_KEY, json.encodeToString(contacts))
                .apply()
        }
    }
}
