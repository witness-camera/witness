import SwiftUI

struct RootView: View {
    @EnvironmentObject private var appState: AppState
    @EnvironmentObject private var capture: CaptureViewModel
    @AppStorage("onboardingComplete") private var onboardingComplete = false
    @AppStorage("practiceComplete") private var practiceComplete = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            if capture.isRecording {
                BlackoutRecordingView()
            } else if !onboardingComplete && !appState.autoStartRequested {
                OnboardingView()
            } else if !practiceComplete && !appState.autoStartRequested {
                // One-time hands-on practice of the triple-tap-then-hold stop
                // gesture, right after onboarding — an instant-start path
                // (Action Button/Siri/widget) skips straight past it, same as
                // it already skips onboarding, since the user is mid-action.
                PracticeRecordingView()
            } else {
                HomeView()
            }
        }
        .onChange(of: appState.autoStartRequested) { _, requested in
            guard requested else { return }
            appState.autoStartRequested = false
            if !capture.isRecording {
                Task { await capture.startRecording() }
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                AppSettings.refreshCloudAccountConnected()
                CrewCloud.shared.syncNow()
            }
        }
        .onAppear {
            AppSettings.refreshCloudAccountConnected()
            // Instant-start paths (Action Button, Siri, widget) may set the
            // flag before this view exists.
            if appState.autoStartRequested {
                appState.autoStartRequested = false
                Task { await capture.startRecording() }
            }
        }
    }
}
