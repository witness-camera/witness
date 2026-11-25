import Foundation
import UserNotifications

/// Warns the user, after Witness is backgrounded mid-recording, if finishing
/// that recording is taking long enough that reopening the app would help.
///
/// Nothing about the footage's safety depends on this ever firing: chunks
/// keep uploading through background URLSession tasks regardless, and a
/// short recording on ordinary settings finishes and saves — chunks closed,
/// session video written, Photos save attempted — well inside the OS's
/// background-execution window without any help. This exists for the other
/// case: a long recording, or Pro capture settings heavy enough that the
/// session video has to be merged from chunks after the fact (see
/// `SessionStitcher`), where that merge can outlast the roughly thirty
/// seconds `beginBackgroundTask` grants before iOS suspends the app —
/// and finishing it then genuinely does need Witness reopened.
///
/// The honesty of "genuinely" is the whole design: this is armed
/// optimistically on every backgrounding-while-recording, and disarmed the
/// moment the real completion signal arrives (`SessionStitcher.finishSession`'s
/// own completion, not the earlier "chunks are closed" signal). So it only
/// ever reaches the user when reopening would actually still do something —
/// never as a blanket "you must reopen or lose this," which stopped being
/// true when backgrounding was made to finish and save immediately.
@MainActor
final class BackgroundSaveNotifier {
    static let shared = BackgroundSaveNotifier()
    private init() {}

    private static let identifier = "witness.reopen-to-finish"

    /// Comfortably shorter than the ~30s background-execution budget
    /// `SessionStitcher`'s own assertion is racing against, so the warning
    /// can only ever arrive while reopening would still help — never after
    /// the window has already closed, which would just be alarming for
    /// nothing.
    private static let delay: TimeInterval = 20

    /// Ask once, up front — not in the middle of backgrounding a recording,
    /// which would be the first time this ever needed to fire and therefore
    /// too late to ask. Wired into onboarding's permissions page alongside
    /// camera, location, and Photos.
    func requestPermissionIfNeeded() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    /// Call the moment the app backgrounds while a recording is in progress.
    /// Safe to call even without notification permission — a denied request
    /// just means `add` silently delivers nothing, the same as the warning
    /// never having existed.
    func armIfNeeded() {
        let content = UNMutableNotificationContent()
        content.title = String(localized: "Still saving your recording")
        content.body = String(
            localized: "Reopen Witness to finish saving it to Photos and your cloud storage.")
        content.sound = .default
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: Self.delay, repeats: false)
        let request = UNNotificationRequest(
            identifier: Self.identifier, content: content, trigger: trigger)
        UNUserNotificationCenter.current().add(request)
    }

    /// Call once a recording has genuinely finished — cancels the warning if
    /// it hasn't fired yet. A no-op, not an error, if it already has.
    func disarm() {
        UNUserNotificationCenter.current()
            .removePendingNotificationRequests(withIdentifiers: [Self.identifier])
    }
}
