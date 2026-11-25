package com.witnessrecord.app.service

import android.content.Context
import android.content.SharedPreferences
import com.witnessrecord.app.WitnessApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The app's single preference store - the direct analogue of the iOS build's
 * `UserDefaults.standard`. Every setting key lives in [AppSettings] so the
 * Compose UI and the capture/upload layers can never disagree about a name or
 * a default.
 */
object Prefs {
    private const val NAME = "witness_prefs"

    fun of(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    val shared: SharedPreferences get() = of(WitnessApplication.appContext)

    fun getBool(key: String, default: Boolean): Boolean = shared.getBoolean(key, default)
    fun setBool(key: String, value: Boolean) = shared.edit().putBoolean(key, value).apply()

    fun getString(key: String, default: String? = null): String? = shared.getString(key, default)
    fun setString(key: String, value: String?) = shared.edit().putString(key, value).apply()

    /**
     * String->String map persistence. SharedPreferences has no map type, so
     * these are stored as a JSON object under one key - used for the
     * per-session Drive folder and manifest file-ID caches.
     */
    fun getStringMap(key: String): Map<String, String> {
        val raw = shared.getString(key, null) ?: return emptyMap()
        return runCatching {
            Json.decodeFromString<Map<String, String>>(raw)
        }.getOrDefault(emptyMap())
    }

    fun setStringMap(key: String, value: Map<String, String>) {
        shared.edit().putString(key, Json.encodeToString(value)).apply()
    }

    fun getStringListMap(key: String): Map<String, List<String>> {
        val raw = shared.getString(key, null) ?: return emptyMap()
        return runCatching {
            Json.decodeFromString<Map<String, List<String>>>(raw)
        }.getOrDefault(emptyMap())
    }

    fun setStringListMap(key: String, value: Map<String, List<String>>) {
        shared.edit().putString(key, Json.encodeToString(value)).apply()
    }
}
