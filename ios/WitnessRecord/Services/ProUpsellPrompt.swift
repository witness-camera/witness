import Foundation

/// Decides when to mention Witness Pro to someone who hasn't bought it.
///
/// Deliberately an in-app prompt, never a push notification, even though
/// the app already has notification permission (for `BackgroundSaveNotifier`).
/// Onboarding's own caption for that permission says notifications are
/// "only ever used to tell you a recording still needs the app reopened to
/// finish saving" — a promise, not just a description. Routing a sales pitch
/// through that channel would make it false, and would mean a "get Pro"
/// notification could light up someone's lock screen at an arbitrary later
/// moment, which is exactly the wrong thing for a safety app to risk. An
/// in-app prompt can only ever appear while Home itself is on screen, which
/// — by the same construction that protects the review prompt — is never
/// while actually recording.
///
/// Tasteful over persistent: this shows on exactly two launches, ever — not
/// a recurring cooldown like the review prompt. Once early (launch 2, since
/// someone's very first open is still learning what the app does) and once
/// again later (launch 20, for someone who's kept using the free app long
/// enough that the pitch is worth repeating once), then never again. A
/// prompt that keeps coming back every time you open the app reads as a
/// nag regardless of how it's worded.
@MainActor
final class ProUpsellPrompt {
    static let shared = ProUpsellPrompt()
    private init() {}

    private enum Key {
        static let launchCount = "proUpsell.launchCount"
        /// Which launch count the prompt last actually appeared at — not a
        /// plain "has it ever shown" flag, since this shows twice. Guards
        /// against showing twice for the *same* launch if Home's check
        /// re-runs while the app stays foregrounded (backgrounding and
        /// returning without a fresh process launch leaves the count
        /// unchanged), without needing a separate flag per milestone.
        static let lastPresentedAtLaunch = "proUpsell.lastPresentedAtLaunch"
    }

    /// The two launches this shows on. A `Set`, not a range or a modulus,
    /// because these are two deliberately chosen moments, not a recurring
    /// interval — nothing shows at launch 40 just because 40 is a multiple
    /// of 20.
    private static let launchesToPresentOn: Set<Int> = [2, 20]

    private let defaults = UserDefaults.standard

    /// Called once per process launch, from
    /// `application(_:didFinishLaunchingWithOptions:)` — never presents UI,
    /// just counts.
    func recordAppOpened() {
        defaults.set(defaults.integer(forKey: Key.launchCount) + 1, forKey: Key.launchCount)
    }

    /// Whether the Home screen should show the prompt now.
    ///
    /// `isRecording` and `isPro` are passed in rather than read here, so this
    /// stays free of capture/StoreKit dependencies and the caller can't
    /// forget either check.
    func shouldPresent(isRecording: Bool, isPro: Bool) -> Bool {
        guard !isRecording, !isPro else { return false }
        let launch = defaults.integer(forKey: Key.launchCount)
        guard Self.launchesToPresentOn.contains(launch) else { return false }
        return defaults.integer(forKey: Key.lastPresentedAtLaunch) != launch
    }

    /// Marks that the prompt was shown at this launch — whatever happens
    /// next, "not now" or through to the paywall, so it doesn't ask again
    /// until (at most) the next milestone launch.
    func didPresent() {
        defaults.set(defaults.integer(forKey: Key.launchCount), forKey: Key.lastPresentedAtLaunch)
    }
}
