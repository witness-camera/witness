package com.witnessrecord.app.service

import android.app.Activity
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Decides when to ask for a Play Store rating.
 *
 * The important rule here is a negative one: **never while recording.** A
 * store dialog appearing over a blacked-out screen during an encounter would
 * be far worse than never being rated at all, and the whole point of the
 * recording mode is that the phone reads as idle.
 *
 * So completion only ever *earns* a prompt. Presenting it is left to the Home
 * screen, which by construction is not on screen while recording - the
 * blackout screen is. That separation is deliberate: nothing on the capture
 * or upload path can put UI in front of someone mid-encounter.
 *
 * The policy below is plain preferences and has no Google dependency; only
 * [present] touches Play, and it fails silently where there is no Play Store
 * to review in - which is the correct behaviour on a de-Googled build rather
 * than something to apologise for.
 */
object ReviewPrompt {

    private const val PRESERVED_SESSIONS_KEY = "review.preservedSessions"
    private const val LAST_PROMPTED_AT_KEY = "review.lastPromptedAt"
    private const val EARNED_KEY = "review.earned"

    /**
     * Sessions that must finish - recorded *and* confirmed by the storage
     * backend - before asking. Two rather than one, so the ask follows
     * evidence the app actually does what it claims, not a first run.
     */
    private const val SESSIONS_BEFORE_ASKING = 2

    /**
     * 365 / 3, matching the iOS build's floor. Play's own in-app review quota
     * is undocumented and enforced silently, so a generous floor spends
     * requests that might otherwise be discarded without telling us.
     */
    private const val MINIMUM_DAYS_BETWEEN_PROMPTS = 122L
    private const val DAY_MILLIS = 86_400_000L

    /**
     * A finished recording is fully accounted for remotely: every chunk
     * confirmed, and the session's final manifest confirmed on top of them.
     *
     * Records the fact and nothing else - this never presents UI, and is safe
     * to call from the upload work scope.
     */
    fun sessionFullyPreserved() {
        val count = Prefs.shared.getInt(PRESERVED_SESSIONS_KEY, 0) + 1
        Prefs.shared.edit().putInt(PRESERVED_SESSIONS_KEY, count).apply()
        if (count >= SESSIONS_BEFORE_ASKING) Prefs.setBool(EARNED_KEY, true)
    }

    /**
     * Whether the Home screen should ask now.
     *
     * `isRecording` is passed in rather than read here so this stays free of
     * capture dependencies, and so the caller cannot forget to consider it.
     */
    fun shouldPresent(isRecording: Boolean): Boolean {
        if (isRecording) return false
        if (!Prefs.getBool(EARNED_KEY, false)) return false

        val last = Prefs.shared.getLong(LAST_PROMPTED_AT_KEY, 0L)
        if (last > 0 && System.currentTimeMillis() - last < MINIMUM_DAYS_BETWEEN_PROMPTS * DAY_MILLIS) {
            return false
        }
        return true
    }

    /**
     * Marks that a request was made. Play decides whether a dialog actually
     * appears, so this records the attempt, never an appearance - which is
     * also why the earned flag is cleared either way.
     */
    fun didRequest() {
        Prefs.shared.edit().putLong(LAST_PROMPTED_AT_KEY, System.currentTimeMillis()).apply()
        Prefs.setBool(EARNED_KEY, false)
    }

    /**
     * Asks Play to show its review dialog. Best-effort by design: the request
     * fails on a device with no Play Store, and Play may decline to show
     * anything even where there is one. Neither is worth surfacing - there is
     * nothing the user could do about it and nothing they asked for.
     */
    fun present(activity: Activity) {
        runCatching {
            val manager = ReviewManagerFactory.create(activity)
            manager.requestReviewFlow().addOnCompleteListener { request ->
                if (!request.isSuccessful) {
                    EventLogger.log("review.request_unavailable")
                    return@addOnCompleteListener
                }
                runCatching { manager.launchReviewFlow(activity, request.result) }
            }
        }.onFailure { EventLogger.log("review.unavailable", detail = it.message) }
    }
}
