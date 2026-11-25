import Foundation
import SwiftUI

/// Bridges the instant-start entry points (App Intent, Siri, lock screen
/// widget deep link) to the capture layer. The intent can fire before the
/// scene is active, so the request is latched and consumed by RootView.
@MainActor
final class AppState: ObservableObject {
    static let shared = AppState()

    let capture = CaptureViewModel()

    /// Set by any instant-start path; RootView consumes it and goes straight
    /// to black-screen recording, skipping all UI.
    @Published var autoStartRequested = false

    /// Set when a crew link is opened, so Home shows the crew it joined.
    @Published var showCrew = false

    private init() {}

    func requestImmediateRecording() {
        autoStartRequested = true
        if AppSettings.quickStartViewfinder, !capture.isRecording {
            capture.setViewfinderPreference(true)
        }
        // If the UI is already up, don't wait for the view to react.
        if !capture.isRecording {
            Task { await capture.startRecording() }
        }
    }

    func handleDeepLink(_ url: URL) {
        if url.scheme == "witnessrecord", url.host == "start" {
            requestImmediateRecording()
        } else if let crew = CrewLink.crew(from: url) {
            CrewStore.shared.receive(crew)
            showCrew = true
        }
    }
}
