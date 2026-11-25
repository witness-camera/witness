package com.witnessrecord.app.service

/**
 * Decides when to mention Witness Pro to someone who hasn't bought it.
 *
 * Deliberately an in-app prompt, never a push notification. An in-app prompt
 * can only ever appear while Home itself is on screen, which - by the same
 * construction that protects [ReviewPrompt] - is never while actually
 * recording.
 *
 * Tasteful over persistent: this shows on exactly two launches, ever - not a
 * recurring cooldown like the review prompt. Once early (launch 2, since
 * someone's very first open is still learning what the app does) and once
 * again later (launch 20, for someone who's kept using the free app long
 * enough that the pitch is worth repeating once), then never again. A prompt
 * that keeps coming back every time you open the app reads as a nag
 * regardless of how it's worded.
 */
object ProUpsellPrompt {

    private const val LAUNCH_COUNT_KEY = "proUpsell.launchCount"

    /**
     * Which launch count the prompt last actually appeared at - not a plain
     * "has it ever shown" flag, since this shows twice. Guards against
     * showing twice for the *same* launch if Home's check re-runs while the
     * app stays foregrounded (backgrounding and returning without a fresh
     * process launch leaves the count unchanged), without needing a separate
     * flag per milestone.
     */
    private const val LAST_PRESENTED_AT_LAUNCH_KEY = "proUpsell.lastPresentedAtLaunch"

    /**
     * The two launches this shows on. A set, not a range or a modulus,
     * because these are two deliberately chosen moments, not a recurring
     * interval - nothing shows at launch 40 just because 40 is a multiple of
     * 20.
     */
    private val LAUNCHES_TO_PRESENT_ON = setOf(2, 20)

    /**
     * Called once per process launch, from [com.witnessrecord.app.WitnessApplication.onCreate] -
     * never presents UI, just counts.
     */
    fun recordAppOpened() {
        val count = Prefs.shared.getInt(LAUNCH_COUNT_KEY, 0) + 1
        Prefs.shared.edit().putInt(LAUNCH_COUNT_KEY, count).apply()
    }

    /**
     * Whether the Home screen should show the prompt now.
     *
     * `isRecording` and `isPro` are passed in rather than read here, so this
     * stays free of capture/billing dependencies and the caller can't forget
     * either check.
     */
    fun shouldPresent(isRecording: Boolean, isPro: Boolean): Boolean {
        if (isRecording || isPro) return false
        val launch = Prefs.shared.getInt(LAUNCH_COUNT_KEY, 0)
        if (launch !in LAUNCHES_TO_PRESENT_ON) return false
        return Prefs.shared.getInt(LAST_PRESENTED_AT_LAUNCH_KEY, 0) != launch
    }

    /**
     * Marks that the prompt was shown at this launch - whatever happens
     * next, "not now" or through to the paywall, so it doesn't ask again
     * until (at most) the next milestone launch.
     */
    fun didPresent() {
        val launch = Prefs.shared.getInt(LAUNCH_COUNT_KEY, 0)
        Prefs.shared.edit().putInt(LAST_PRESENTED_AT_LAUNCH_KEY, launch).apply()
    }
}
