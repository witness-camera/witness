import SwiftUI
import AVKit

/// Active-recording screen. By default it's solid black with brightness forced
/// to minimum — to an observer the phone looks off. The user can toggle a live
/// viewfinder (back camera full-screen, front camera as a corner PiP) to see
/// what's being filmed.
///
/// Controls are hidden by default and revealed by a triple-tap, so nothing is
/// pressed by accident. Stopping requires a deliberate press-and-hold.
struct BlackoutRecordingView: View {
    @EnvironmentObject private var capture: CaptureViewModel
    @ObservedObject private var ping = EmergencyPingCoordinator.shared
    @ObservedObject private var pro = ProEntitlement.shared
    @State private var controlsVisible = false
    @State private var hideTask: Task<Void, Never>?

    var body: some View {
        ZStack {
            if capture.viewfinderVisible {
                CameraPreviewView(
                    backLayer: capture.backPreviewLayer,
                    frontLayer: capture.frontPreviewLayer,
                    hardwareLayer: capture.hardwarePreviewLayer,
                    showFront: capture.dualActive,
                    swapped: capture.mainViewSwapped)
                    .ignoresSafeArea()
            } else {
                Color.black.ignoresSafeArea()
            }

            // Volume buttons mark the moment (Pro) — works with the screen
            // black and the phone in a pocket, no looking needed.
            if pro.isPro {
                VolumeMarkerInteraction { capture.addMarker() }
                    .frame(width: 1, height: 1)
                    .allowsHitTesting(false)
            }

            if capture.viewfinderVisible, let mark = capture.lastMarkAt,
               let start = capture.recordingStartedAt {
                MarkToast(mark: mark, start: start)
            }

            if controlsVisible || capture.viewfinderVisible {
                controls
            }
        }
        .contentShape(Rectangle())
        .onTapGesture(count: 3) { revealControls() }
        .onTapGesture { if capture.viewfinderVisible { revealControls() } }
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .sheet(item: Binding(
            get: { ping.activeStep },
            set: { if $0 == nil { ping.activeStep = nil } }
        )) { step in
            switch step {
            case .sms(let recipients, let body):
                MessageComposeView(recipients: recipients, body: body) { _ in
                    ping.smsStepFinished()
                }
            case .mail(let recipients, let subject, let body):
                MailComposeView(recipients: recipients, subject: subject, body: body) { _ in
                    ping.mailStepFinished()
                }
            }
        }
        .alert("Location access is off",
               isPresented: Binding(
                get: { ping.locationDeniedPromptNeeded },
                set: { ping.locationDeniedPromptNeeded = $0 })
        ) {
            Button("Open Settings") {
                ping.cancelWaiting()
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
            Button("Send Without Location") { ping.sendWithoutLocation() }
            Button("Cancel", role: .cancel) { ping.cancelWaiting() }
        } message: {
            Text("Turn on location for Witness so your contact can see where you are, or send the message without it.")
        }
        .alert("No emergency contact on file",
               isPresented: Binding(
                get: { ping.noEligibleContacts },
                set: { ping.noEligibleContacts = $0 })
        ) {
            Button("OK") {}
        } message: {
            Text("Add one with a phone number or email in Settings so this button has someone to reach.")
        }
    }

    private var controls: some View {
        VStack {
            HStack {
                if capture.viewfinderVisible {
                    HStack(spacing: 6) {
                        Label("REC", systemImage: "circle.fill")
                            .font(.caption.bold())
                            .foregroundStyle(.red)
                        // SwiftUI's own timer style: counts up on its own,
                        // no polling Timer needed, same plain "0:14" look as
                        // the system Camera app's own recording clock.
                        if let startedAt = capture.recordingStartedAt {
                            Text(startedAt, style: .timer)
                                .font(.caption.bold().monospacedDigit())
                                .foregroundStyle(.white)
                        }
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(.black.opacity(0.4), in: Capsule())
                }
                Spacer()
                if pro.isPro {
                    Button {
                        capture.addMarker()
                        revealControls()
                    } label: {
                        Image(systemName: "flag.fill")
                            .font(.title3)
                            .foregroundStyle(.white.opacity(0.85))
                            .padding(12)
                            .background(.black.opacity(0.4), in: Circle())
                    }
                    .accessibilityLabel("Mark this moment")
                }
                Button {
                    ping.start()
                    revealControls()
                } label: {
                    Image(systemName: "location.fill")
                        .font(.title3)
                        .foregroundStyle(.white.opacity(0.85))
                        .padding(12)
                        .background(.black.opacity(0.4), in: Circle())
                }
                .accessibilityLabel("Alert your emergency contact with your location")
                Button {
                    capture.toggleViewfinder()
                    revealControls()
                } label: {
                    Image(systemName: capture.viewfinderVisible ? "eye.slash.fill" : "eye.fill")
                        .font(.title3)
                        .foregroundStyle(.white.opacity(0.85))
                        .padding(12)
                        .background(.black.opacity(0.4), in: Circle())
                }
            }
            .padding(.horizontal)
            .padding(.top, 8)

            Spacer()

            VStack(spacing: 16) {
                if capture.viewfinderVisible && capture.lensControlAvailable {
                    Button {
                        capture.toggleLens()
                        revealControls()
                    } label: {
                        Text(capture.lensMultiplier <= 0.5 ? "0.5×" : "1×")
                            .font(.subheadline.bold().monospacedDigit())
                            .foregroundStyle(.white)
                            .frame(minWidth: 44)
                            .padding(.vertical, 8)
                            .background(.black.opacity(0.45), in: Capsule())
                            .overlay(Capsule().stroke(.white.opacity(0.5), lineWidth: 1))
                    }
                    .accessibilityLabel("Rear lens, currently \(capture.lensMultiplier <= 0.5 ? "0.5" : "1") times. Tap to switch.")
                }

                // Meaningless outside dual capture — with one camera
                // there is no second view to swap with.
                if capture.viewfinderVisible && capture.dualActive {
                    Button {
                        capture.toggleMainViewSwap()
                        revealControls()
                    } label: {
                        Label("Swap main view", systemImage: "arrow.triangle.2.circlepath.camera.fill")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(.black.opacity(0.45), in: Capsule())
                            .overlay(Capsule().stroke(.white.opacity(0.5), lineWidth: 1))
                    }
                    .accessibilityLabel(capture.mainViewSwapped
                        ? "Showing front camera as main view. Tap to switch back to rear."
                        : "Showing rear camera as main view. Tap to switch to front.")
                }

                Group {
                    // Two literal Text calls, not one ternary-selected string —
                    // a ternary between string literals passed to Text
                    // resolves to the non-localizing String initializer, not
                    // LocalizedStringKey, so each branch must be its own call.
                    if capture.viewfinderVisible {
                        Text("Recording, press and hold to stop")
                    } else {
                        Text("Recording, hold to stop")
                    }
                }
                .font(.footnote)
                .foregroundStyle(.white.opacity(capture.viewfinderVisible ? 0.8 : 0.6))

                Image(systemName: "stop.circle")
                    .font(.system(size: 56))
                    // Red whether or not the viewfinder is on: these controls only
                    // exist for a few seconds after a triple-tap, and a faint
                    // grey icon on black was hard to find in exactly that window.
                    .foregroundStyle(.red.opacity(0.9))
                    .onLongPressGesture(minimumDuration: 1.0) {
                        capture.stopRecording()
                    }

                if let warning = capture.lowStorageWarning {
                    Text(warning)
                        .font(.caption2)
                        .foregroundStyle(.orange.opacity(0.7))
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                }
            }
            .padding(.bottom, 40)
        }
        .transition(.opacity)
    }

    private func revealControls() {
        withAnimation { controlsVisible = true }
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(for: .seconds(6))
            if !Task.isCancelled {
                withAnimation { controlsVisible = false }
            }
        }
    }
}

/// "Marked 0:42", shown for two seconds after each mark while the
/// viewfinder is on. Redrawn per mark by keying on its time.
private struct MarkToast: View {
    let mark: Date
    let start: Date
    @State private var visible = true

    var body: some View {
        let offset = max(0, Int(mark.timeIntervalSince(start)))
        VStack {
            Spacer()
            if visible {
                Label {
                    Text("Marked \(offset / 60):\(String(format: "%02d", offset % 60))")
                } icon: {
                    Image(systemName: "flag.fill")
                }
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 14).padding(.vertical, 8)
                .background(.black.opacity(0.55), in: Capsule())
                .transition(.opacity)
            }
            Spacer().frame(height: 300)
        }
        .allowsHitTesting(false)
        .id(mark)
        .task {
            try? await Task.sleep(for: .seconds(2))
            withAnimation { visible = false }
        }
    }
}

/// Hosts an `AVCaptureEventInteraction`, which turns hardware volume-button
/// presses into capture events while the camera is running, instead of
/// changing the volume. iOS 17.2 and later; does nothing before that.
private struct VolumeMarkerInteraction: UIViewRepresentable {
    let onPress: () -> Void

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        if #available(iOS 17.2, *) {
            let interaction = AVCaptureEventInteraction { event in
                if event.phase == .began { context.coordinator.onPress() }
            }
            view.addInteraction(interaction)
        }
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.onPress = onPress
    }

    func makeCoordinator() -> Coordinator { Coordinator(onPress: onPress) }

    final class Coordinator {
        var onPress: () -> Void
        init(onPress: @escaping () -> Void) { self.onPress = onPress }
    }
}
