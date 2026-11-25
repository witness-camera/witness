package com.witnessrecord.app.service

import com.witnessrecord.app.R
import com.witnessrecord.app.WitnessApplication
import com.witnessrecord.app.model.Crew
import com.witnessrecord.app.model.CrewAngle
import com.witnessrecord.app.model.CrewMember
import com.witnessrecord.app.model.CrewStamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.util.UUID

/**
 * The crews this phone belongs to, and which one new recordings join.
 *
 * Persisted in [Prefs], which the capture and upload layers also read: a
 * recording has to be stamped the instant it starts, often from a tile or
 * widget cold start, without waiting on any screen.
 *
 * The Android crew is carried phone to phone - in a link or a QR code - with
 * no server and no live connection, so every phone holds its own copy and
 * [receive] folds in whatever another phone's copy knows. See [Crew.merged].
 */
object CrewStore {
    private object Key {
        const val CREWS = "crew.crews"
        const val ACTIVE = "crew.active"
        /** Per crew: this phone's member id. */
        const val ME = "crew.me"
        /** Per crew: "offset,uncertainty" against true time, in seconds. */
        const val CLOCK = "crew.clock"
        /** Per session: the encoded [CrewStamp] it was recorded under. */
        const val STAMPS = "crew.sessionStamps"
        const val MY_NAME = "crew.myName"
        const val MY_EMAIL = "crew.myEmail"
        /** Per session: [provider wire id, folder id] once its folder exists. */
        const val FOLDERS = "crew.sessionFolders"
    }

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _crews = MutableStateFlow<List<Crew>>(emptyList())
    val crews: StateFlow<List<Crew>> = _crews.asStateFlow()

    private val _activeCrewID = MutableStateFlow<String?>(null)
    val activeCrewID: StateFlow<String?> = _activeCrewID.asStateFlow()

    /** Bumped when the clock reading or the person's details change, so a screen showing them refreshes. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    @Volatile private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            _crews.value = decodeCrews(Prefs.getString(Key.CREWS))
            _activeCrewID.value = Prefs.getString(Key.ACTIVE)
            loaded = true
        }
    }

    val activeCrew: Crew? get() { ensureLoaded(); return _crews.value.firstOrNull { it.id == _activeCrewID.value } }

    fun crew(id: String): Crew? { ensureLoaded(); return _crews.value.firstOrNull { it.id == id } }

    fun allCrews(): List<Crew> { ensureLoaded(); return _crews.value }

    fun myMemberID(crewID: String): String? = Prefs.getStringMap(Key.ME)[crewID]

    var myName: String
        get() = Prefs.getString(Key.MY_NAME).orEmpty()
        set(value) { Prefs.setString(Key.MY_NAME, value); _revision.value++ }

    var myEmail: String
        get() = Prefs.getString(Key.MY_EMAIL).orEmpty()
        set(value) { Prefs.setString(Key.MY_EMAIL, value); _revision.value++ }

    // MARK: - Starting, joining, leaving

    /** Starts a crew with this phone as organizer. */
    fun start(title: String): Crew {
        ensureLoaded()
        val context = WitnessApplication.appContext
        val me = CrewMember(id = newID(), name = displayName(), email = emailOrNull(), updatedAt = nowSeconds())
        val evidenceCase = CaseLibrary.newCase(title, context.getString(R.string.crew_default_title))
        val crew = Crew(
            id = newID(), title = title.trim().ifEmpty { context.getString(R.string.crew_default_title) },
            createdAt = nowSeconds(), organizerID = me.id, members = listOf(me),
            caseID = evidenceCase.id.uppercase(), secret = newSecret(),
        )
        setMe(me.id, crew.id)
        synchronized(lock) {
            _crews.value = _crews.value + crew
            _activeCrewID.value = crew.id
        }
        save()
        scope.launch { measureClock(crew.id) }
        return crew
    }

    /**
     * Takes in a crew from a link or a scanned code: joins it if this phone
     * isn't in it yet, otherwise merges what the other side knows. Returns the
     * crew as this phone now holds it.
     */
    fun receive(incoming: Crew): Crew {
        ensureLoaded()
        val existing = crew(incoming.id)
        val result: Crew
        if (existing != null) {
            val before = existing.members.mapNotNull { it.email }.toSet()
            result = existing.merged(incoming)
            replace(result)
            if (!result.members.mapNotNull { it.email }.toSet().all { it in before }) {
                UploadManager.reshareCrewSessions(incoming.id)
            }
        } else {
            val me = CrewMember(id = newID(), name = displayName(), email = emailOrNull(), updatedAt = nowSeconds())
            val context = WitnessApplication.appContext
            val joined = incoming.copy(
                members = incoming.members + me,
                caseID = CaseLibrary.newCase(incoming.title, context.getString(R.string.crew_default_title)).id.uppercase(),
            )
            setMe(me.id, joined.id)
            synchronized(lock) {
                _crews.value = _crews.value + joined
                _activeCrewID.value = joined.id
            }
            result = joined
            scope.launch { measureClock(joined.id) }
        }
        save()
        return result
    }

    /** Stops new recordings joining the crew. The crew, its case and its
     *  shares stay, so the edit can still be exported. */
    fun leave() {
        ensureLoaded()
        _activeCrewID.value = null
        save()
    }

    fun rejoin(crewID: String) {
        if (crew(crewID) == null) return
        _activeCrewID.value = crewID
        save()
    }

    fun delete(crewID: String) {
        ensureLoaded()
        synchronized(lock) {
            _crews.value = _crews.value.filterNot { it.id == crewID }
            if (_activeCrewID.value == crewID) _activeCrewID.value = null
        }
        save()
    }

    /** Updates this phone's own name and email in every crew it belongs to, so
     *  the next link or code carries them. */
    fun updateMyDetails() {
        ensureLoaded()
        var changed = false
        val name = displayName()
        val email = emailOrNull()
        val updated = _crews.value.map { crew ->
            val me = myMemberID(crew.id) ?: return@map crew
            val i = crew.members.indexOfFirst { it.id == me }
            if (i < 0) return@map crew
            val member = crew.members[i]
            if (member.name == name && member.email == email) return@map crew
            changed = true
            crew.copy(members = crew.members.toMutableList().also {
                it[i] = member.copy(name = name, email = email, updatedAt = nowSeconds())
            })
        }
        if (changed) {
            _crews.value = updated
            save()
        }
    }

    /** Whether this phone's member still lacks the details the others need to
     *  see who joined and to share footage with them. */
    fun needsMyDetails(crewID: String): Boolean {
        val me = myMemberID(crewID) ?: return false
        val member = crew(crewID)?.members?.firstOrNull { it.id == me } ?: return false
        return myName.isBlank() || !member.canReceiveFootage
    }

    // MARK: - Clock

    data class ClockState(val offsetSeconds: Double, val uncertaintySeconds: Double?)

    fun clock(crewID: String): ClockState? {
        val raw = Prefs.getStringMap(Key.CLOCK)[crewID] ?: return null
        val parts = raw.split(',')
        val offset = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        return ClockState(offset, parts.getOrNull(1)?.toDoubleOrNull())
    }

    private fun setClock(crewID: String, offset: Double, uncertainty: Double?) {
        val map = Prefs.getStringMap(Key.CLOCK).toMutableMap()
        map[crewID] = if (uncertainty != null) "$offset,$uncertainty" else "$offset"
        Prefs.setStringMap(Key.CLOCK, map)
        _revision.value++
    }

    /**
     * Compares this phone's clock with network time and keeps the result for
     * the crew. Returns the reading, or null when no time server answered (no
     * connection), in which case the previous reading, if any, stands.
     */
    suspend fun measureClock(crewID: String): CrewClock.Reading? {
        val reading = CrewClock.measure() ?: return null
        setClock(crewID, reading.offsetSeconds, reading.uncertaintySeconds)
        return reading
    }

    // MARK: - Recordings

    /** The name this phone's recordings carry in the crew, and whether it is
     *  recording right now as far as the crew can tell. */
    fun setRecording(crewID: String, memberID: String, recording: Boolean) {
        val crew = crew(crewID) ?: return
        val i = crew.members.indexOfFirst { it.id == memberID }
        if (i < 0) return
        val member = crew.members[i]
        replace(crew.copy(members = crew.members.toMutableList().also {
            it[i] = member.copy(recordingSince = if (recording) nowSeconds() else null, updatedAt = nowSeconds())
        }))
        save()
    }

    /**
     * Stamps a recording that is starting, if a crew is active. Called the
     * moment the session id is minted. The clock reading held is used at once;
     * a fresh one is taken in the background and written over it, since the
     * offset applies to the whole recording.
     */
    fun stampSession(sessionID: String) {
        ensureLoaded()
        val activeID = _activeCrewID.value ?: return
        val crew = crew(activeID) ?: return
        val me = myMemberID(activeID) ?: return
        val member = crew.members.firstOrNull { it.id == me } ?: return
        val clock = clock(activeID)
        val stamp = CrewStamp(
            crewID = crew.id, crewTitle = crew.title, memberID = me, memberName = member.name,
            clockOffsetSeconds = clock?.offsetSeconds ?: 0.0,
            clockUncertaintySeconds = clock?.uncertaintySeconds,
        )
        putStamp(sessionID, stamp)
        setRecording(crew.id, me, true)
        scope.launch {
            val reading = measureClock(crew.id) ?: return@launch
            // Still this recording's stamp, and the same crew: write the
            // better reading into it.
            val current = stamp(sessionID) ?: return@launch
            if (current.crewID != crew.id) return@launch
            putStamp(sessionID, current.copy(
                clockOffsetSeconds = reading.offsetSeconds, clockUncertaintySeconds = reading.uncertaintySeconds,
            ))
        }
    }

    fun stamp(sessionID: String): CrewStamp? {
        val raw = Prefs.getStringMap(Key.STAMPS)[sessionID] ?: return null
        return runCatching { Crew.json.decodeFromString(CrewStamp.serializer(), raw) }.getOrNull()
    }

    private fun putStamp(sessionID: String, stamp: CrewStamp) {
        synchronized(lock) {
            val map = Prefs.getStringMap(Key.STAMPS).toMutableMap()
            map[sessionID] = Crew.json.encodeToString(CrewStamp.serializer(), stamp)
            Prefs.setStringMap(Key.STAMPS, map)
        }
    }

    /**
     * Called when a crew recording stops: adds its angles to the crew, which
     * travel to the other phones the next time a link or code is shared.
     * [startedAtMillis] is the phone's own clock; the stamp's offset puts it
     * on the crew's.
     */
    fun addRecording(
        sessionID: String, stamp: CrewStamp, cameras: List<String>,
        startedAtMillis: Long, endedAtMillis: Long, folderName: String?,
    ) {
        val crew = crew(stamp.crewID) ?: return
        val folder = folder(sessionID)
        val added = cameras.map { camera ->
            CrewAngle(
                sessionID = sessionID.uppercase(), memberID = stamp.memberID, camera = camera,
                startedAt = startedAtMillis / 1000.0 + stamp.clockOffsetSeconds,
                duration = (endedAtMillis - startedAtMillis) / 1000.0,
                folderName = folderName,
                fileName = UploadManager.mergedRemoteFileName(camera),
                providerID = folder?.first, folderID = folder?.second,
            )
        }.filter { angle -> crew.angles.none { it.id == angle.id } }
        replace(crew.copy(angles = crew.angles + added))
        setRecording(stamp.crewID, stamp.memberID, false)
        save()
    }

    /** Records where a crew recording's footage went, once its folder exists.
     *  Called from the upload queue; cheap to call for every chunk. */
    fun noteSessionFolder(sessionID: String, providerWire: String, folderID: String) {
        if (stamp(sessionID) == null) return
        synchronized(lock) {
            val map = Prefs.getStringListMap(Key.FOLDERS).toMutableMap()
            if (map[sessionID] == listOf(providerWire, folderID)) return
            map[sessionID] = listOf(providerWire, folderID)
            Prefs.setStringListMap(Key.FOLDERS, map)
        }
        attachFolder(sessionID, providerWire, folderID)
    }

    fun folder(sessionID: String): Pair<String, String>? {
        val values = Prefs.getStringListMap(Key.FOLDERS)[sessionID]
        return if (values != null && values.size == 2) values[0] to values[1] else null
    }

    /** This phone's recordings in a crew, for sharing their folders again with
     *  members whose email arrived late. */
    fun sessions(crewID: String): List<String> {
        return Prefs.getStringMap(Key.STAMPS).mapNotNull { (session, raw) ->
            val stamp = runCatching { Crew.json.decodeFromString(CrewStamp.serializer(), raw) }.getOrNull()
            if (stamp?.crewID == crewID) session else null
        }
    }

    /** The other members' emails for a crew recording's folder, alongside the
     *  emergency contacts it is already shared with. */
    fun shareEmails(sessionID: String): List<String> {
        val stamp = stamp(sessionID) ?: return emptyList()
        val crew = crew(stamp.crewID) ?: return emptyList()
        return crew.members
            .filter { it.id != stamp.memberID && it.canReceiveFootage }
            .mapNotNull { it.email?.trim() }
    }

    private fun attachFolder(sessionID: String, providerWire: String, folderID: String) {
        ensureLoaded()
        val key = sessionID.uppercase()
        var changed = false
        val updated = _crews.value.map { crew ->
            if (crew.angles.none { it.sessionID == key && it.folderID == null }) return@map crew
            changed = true
            crew.copy(angles = crew.angles.map {
                if (it.sessionID == key && it.folderID == null) it.copy(providerID = providerWire, folderID = folderID) else it
            })
        }
        if (changed) {
            _crews.value = updated
            save()
        }
    }

    // MARK: - Persistence

    private fun displayName(): String {
        val name = myName.trim()
        return name.ifEmpty { WitnessApplication.appContext.getString(R.string.crew_member_default) }
    }

    private fun emailOrNull(): String? = myEmail.trim().ifEmpty { null }

    private fun setMe(memberID: String, crewID: String) {
        val map = Prefs.getStringMap(Key.ME).toMutableMap()
        map[crewID] = memberID
        Prefs.setStringMap(Key.ME, map)
    }

    private fun replace(crew: Crew) {
        synchronized(lock) { _crews.value = _crews.value.map { if (it.id == crew.id) crew else it } }
    }

    private fun save() {
        val crews = _crews.value
        Prefs.setString(Key.CREWS, Crew.json.encodeToString(ListSerializer(Crew.serializer()), crews))
        Prefs.setString(Key.ACTIVE, _activeCrewID.value)
    }

    /** Each crew decoded on its own, so one written by a version this build
     *  can't read is dropped rather than taking every other crew with it. */
    internal fun decodeCrews(raw: String?): List<Crew> {
        if (raw.isNullOrEmpty()) return emptyList()
        val array = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching { Crew.json.decodeFromJsonElement(Crew.serializer(), element).normalized() }.getOrNull()
        }
    }

    private fun newID(): String = UUID.randomUUID().toString().uppercase()

    private fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0

    /** 256 random bits, base64: the format iOS uses for the key its iCloud sync is sealed with. */
    private fun newSecret(): String {
        val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }
}
