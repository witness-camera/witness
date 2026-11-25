package com.witnessrecord.app.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Resolves a Drive folder ID once per key, even when many chunk uploads race
 * to use it. Without this, concurrent uploads from the same recording session
 * each create their own folder, scattering one session across several.
 *
 * Results are persisted (backend depends on the instance), so a folder is
 * reused across relaunches - and can be [evict]ed if Drive reports it no
 * longer exists (e.g. the user deleted it), so the next call re-resolves a
 * fresh one instead of retrying the same dead ID forever.
 */
class DriveFolderCache private constructor(private val persistence: Persistence) {

    sealed interface Persistence {
        data class Preferences(val storeKey: String) : Persistence
        data class Secure(val key: SecureStore.Key) : Persistence
    }

    private val mutex = Mutex()

    /** In-progress resolutions, so simultaneous callers await one shared result. */
    private val inFlight = mutableMapOf<String, CompletableDeferred<String>>()

    /**
     * Returns the cached folder ID for [key], otherwise runs [create] exactly
     * once and caches its result.
     */
    suspend fun folderID(key: String, create: suspend () -> String): String {
        readCached(key)?.let { return it }

        val (deferred, isOwner) = mutex.withLock {
            // Re-check under the lock: another caller may have finished
            // between the fast-path read above and acquiring the lock.
            readCached(key)?.let { return it }
            val existing = inFlight[key]
            if (existing != null) {
                existing to false
            } else {
                val fresh = CompletableDeferred<String>()
                inFlight[key] = fresh
                fresh to true
            }
        }

        if (!isOwner) return deferred.await()

        return try {
            val id = create()
            writeCached(key, id)
            deferred.complete(id)
            id
        } catch (error: Throwable) {
            // Wake every waiter with the same failure rather than leaving them
            // parked forever on a resolution that will never arrive.
            deferred.completeExceptionally(error)
            throw error
        } finally {
            mutex.withLock { inFlight.remove(key) }
        }
    }

    /**
     * Drops a cached entry, e.g. after Drive reports the folder no longer
     * exists, so the next [folderID] call resolves a fresh one.
     */
    fun evict(key: String) {
        when (persistence) {
            is Persistence.Preferences -> {
                val map = Prefs.getStringMap(persistence.storeKey).toMutableMap()
                map.remove(key)
                Prefs.setStringMap(persistence.storeKey, map)
            }
            is Persistence.Secure -> SecureStore.delete(persistence.key)
        }
    }

    private fun readCached(key: String): String? = when (persistence) {
        is Persistence.Preferences -> Prefs.getStringMap(persistence.storeKey)[key]
        is Persistence.Secure -> SecureStore.string(persistence.key)
    }

    private fun writeCached(key: String, id: String) {
        when (persistence) {
            is Persistence.Preferences -> {
                val map = Prefs.getStringMap(persistence.storeKey).toMutableMap()
                map[key] = id
                Prefs.setStringMap(persistence.storeKey, map)
            }
            is Persistence.Secure -> SecureStore.set(id, persistence.key)
        }
    }

    companion object {
        /**
         * Session subfolders: many keys (one per session), fine to lose on
         * reinstall since a session belongs to one install's lifetime anyway.
         */
        val shared = DriveFolderCache(Persistence.Preferences("sessionFolderIDs"))

        /**
         * The single top-level "Witness" folder: Keystore-backed so it
         * survives a reinstall without spawning a second root folder.
         */
        val sharedRoot = DriveFolderCache(Persistence.Secure(SecureStore.Key.DRIVE_FOLDER_ID))
    }
}
