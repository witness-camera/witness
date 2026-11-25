import Foundation

/// Decides when to ask for an App Store rating.
///
/// The important rule here is a negative one: **never while recording.** A
/// StoreKit dialog appearing over a blacked-out screen during an encounter
/// would be far worse than never being rated at all, and the whole point of
/// the recording mode is that the phone reads as idle.
///
/// So completion only ever *earns* a prompt. Presenting it is left to the Home
/// screen, which by construction is not on screen while recording — the
/// blackout view is. That separation is deliberate: nothing on the capture or
/// upload path can put UI in front of someone mid-encounter.
@MainActor
final class ReviewPrompt {
    static let shared = ReviewPrompt()
    private init() {}

    private enum Key {
        static let preservedSessions = "review.preservedSessions"
        static let lastPromptedAt = "review.lastPromptedAt"
        static let earned = "review.earned"
    }

    /// Sessions that must finish — recorded *and* confirmed by the storage
    /// backend — before asking. Two rather than one, so the ask follows
    /// evidence the app actually does what it claims, not a first run.
    private static let sessionsBeforeAsking = 2

    /// 365 ÷ 3, so we can never exceed Apple's cap of three prompts per user
    /// per year. Apple silently discards anything over its own limit — no
    /// error, no callback — so a floor any shorter would spend requests we
    /// would never learn had been thrown away. 90 days was four a year.
    private static let minimumDaysBetweenPrompts = 122.0

    private let defaults = UserDefaults.standard

    /// A finished recording is fully accounted for remotely: every chunk
    /// confirmed, and the session's final manifest confirmed on top of them.
    ///
    /// Records the fact and nothing else — this never presents UI, and is safe
    /// to call from the upload work queue.
    func sessionFullyPreserved() {
        let count = defaults.integer(forKey: Key.preservedSessions) + 1
        defaults.set(count, forKey: Key.preservedSessions)
        if count >= Self.sessionsBeforeAsking {
            defaults.set(true, forKey: Key.earned)
        }
    }

    /// Whether the Home screen should ask now.
    ///
    /// `isRecording` is passed in rather than read here so this stays free of
    /// capture dependencies, and so the caller cannot forget to consider it.
    func shouldPresent(isRecording: Bool) -> Bool {
        guard !isRecording,
              defaults.bool(forKey: Key.earned) else { return false }

        if let last = defaults.object(forKey: Key.lastPromptedAt) as? Date,
           Date().timeIntervalSince(last) < Self.minimumDaysBetweenPrompts * 86_400 {
            return false
        }
        return true
    }

    /// Marks that a request was made. Apple decides whether a dialog actually
    /// appears, so this records the attempt, never an appearance — which is
    /// also why the earned flag is cleared either way.
    func didRequest() {
        defaults.set(Date(), forKey: Key.lastPromptedAt)
        defaults.set(false, forKey: Key.earned)
    }
}
