import AppIntents
import SwiftUI

/// The instant-start intent. Bind it to the Action Button
/// (Settings → Action Button → Shortcut → Start Witness Recording),
/// trigger it from the lock screen widget, or invoke it by voice
/// ("Hey Siri, start recording with Witness"). It foregrounds the app
/// and drops straight into black-screen recording — zero taps.
struct StartRecordingIntent: AppIntent {
    static let title: LocalizedStringResource = "Start Witness Recording"
    static let description = IntentDescription(
        "Immediately starts a black-screen witness recording that uploads to your Google Drive as it records."
    )
    static let openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        AppState.shared.requestImmediateRecording()
        return .result()
    }
}

struct StopRecordingIntent: AppIntent {
    static let title: LocalizedStringResource = "Stop Witness Recording"
    static let description = IntentDescription("Stops the active witness recording.")
    static let openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        AppState.shared.capture.stopRecording()
        return .result()
    }
}

/// Registers the Siri phrases so voice invocation works with no setup.
struct WitnessShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: StartRecordingIntent(),
            phrases: [
                "Start recording with \(.applicationName)",
                "Start \(.applicationName)",
                "\(.applicationName) start recording",
            ],
            shortTitle: "Start Recording",
            systemImageName: "record.circle"
        )
        AppShortcut(
            intent: StopRecordingIntent(),
            phrases: [
                "Stop recording with \(.applicationName)",
                "\(.applicationName) stop recording",
            ],
            shortTitle: "Stop Recording",
            systemImageName: "stop.circle"
        )
    }
}
