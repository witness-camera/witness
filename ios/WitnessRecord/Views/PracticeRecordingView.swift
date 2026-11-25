import SwiftUI

/// One-time, hands-on walkthrough of the exact gesture used to stop a real
/// recording — triple-tap, then press-and-hold — shown once right after
/// onboarding, before it's ever needed for real. Mirrors
/// `BlackoutRecordingView`'s black screen and gesture recognizers exactly
/// (same tap count, same 1-second hold) so the muscle memory actually
/// transfers. Nothing here is a real recording: no camera, no capture, no
/// upload — just the gesture.
struct PracticeRecordingView: View {
    @AppStorage("practiceComplete") private var practiceComplete = false

    private enum Step {
        case intro, waitingForTripleTap, waitingForHold, success
    }

    @State private var step: Step = .intro
    @State private var pulse = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            switch step {
            case .intro: introCard
            case .waitingForTripleTap: tripleTapCoaching
            case .waitingForHold: holdCoaching
            case .success: successCard
            }
        }
        .statusBarHidden(step == .waitingForTripleTap || step == .waitingForHold)
        .persistentSystemOverlays(.hidden)
        .contentShape(Rectangle())
        .onTapGesture(count: 3) {
            guard step == .waitingForTripleTap else { return }
            tap(.medium)
            withAnimation(.spring(response: 0.4, dampingFraction: 0.75)) { step = .waitingForHold }
        }
        .onAppear { pulse = true }
    }

    // MARK: - Steps

    private var introCard: some View {
        VStack(spacing: 20) {
            Spacer()
            Image(systemName: "hand.tap.fill")
                .font(.system(size: 40, weight: .semibold))
                .foregroundStyle(.red)
            Text("One quick practice")
                .font(.system(size: 30, weight: .bold))
                .foregroundStyle(.white)
            Text("When you record for real, the screen goes black. Stopping takes a specific gesture, let's practice it now, so it's second nature later.")
                .font(.system(size: 17))
                .foregroundStyle(.white.opacity(0.75))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 36)
                .fixedSize(horizontal: false, vertical: true)
            Spacer()

            Button {
                tap()
                withAnimation { step = .waitingForTripleTap }
            } label: {
                Text("Start practice")
                    .font(.system(size: 18, weight: .semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
            }
            .buttonStyle(.practiceHeroPill)
            .padding(.horizontal, 28)

            Button {
                tap()
                practiceComplete = true
            } label: {
                Text("Skip")
                    .font(.system(size: 15))
                    .foregroundStyle(.white.opacity(0.4))
            }
            .padding(.bottom, 24)
        }
    }

    private var tripleTapCoaching: some View {
        VStack(spacing: 18) {
            Spacer()
            pulsingTarget(icon: "hand.tap.fill", ringSize: 120, iconSize: 34)
            Text("This is what recording looks like")
                .font(.system(size: 15))
                .foregroundStyle(.white.opacity(0.4))
            Text("Triple-tap anywhere to reveal the controls")
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
            Spacer()
            Spacer()
        }
    }

    private var holdCoaching: some View {
        VStack {
            HStack {
                Label("PRACTICE", systemImage: "circle.fill")
                    .font(.caption.bold())
                    .foregroundStyle(.orange)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(.black.opacity(0.4), in: Capsule())
                Spacer()
            }
            .padding(.horizontal)
            .padding(.top, 8)

            Spacer()

            VStack(spacing: 16) {
                Text("Now press and hold the stop icon for 1 second")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 30)

                ZStack {
                    Circle()
                        .stroke(.white.opacity(0.35), lineWidth: 2)
                        .frame(width: 90, height: 90)
                        .scaleEffect(pulse ? 1.25 : 1.0)
                        .opacity(pulse ? 0 : 0.9)
                        .animation(.easeOut(duration: 1.1).repeatForever(autoreverses: false), value: pulse)
                    Image(systemName: "stop.circle")
                        .font(.system(size: 56))
                        .foregroundStyle(.red.opacity(0.9))
                        .onLongPressGesture(minimumDuration: 1.0) {
                            tap(.heavy)
                            withAnimation(.spring(response: 0.4, dampingFraction: 0.75)) { step = .success }
                        }
                }
            }
            .padding(.bottom, 60)
        }
        .transition(.opacity)
    }

    private var successCard: some View {
        VStack(spacing: 20) {
            Spacer()
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 48, weight: .semibold))
                .foregroundStyle(.green)
            Text("That's it")
                .font(.system(size: 30, weight: .bold))
                .foregroundStyle(.white)
            Text("Triple-tap, then press and hold. Same gesture, every time you actually record.")
                .font(.system(size: 17))
                .foregroundStyle(.white.opacity(0.75))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 36)
                .fixedSize(horizontal: false, vertical: true)
            Spacer()

            Button {
                tap(.medium)
                practiceComplete = true
            } label: {
                Text("Continue")
                    .font(.system(size: 18, weight: .semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
            }
            .buttonStyle(.practiceHeroPill)
            .padding(.horizontal, 28)
            .padding(.bottom, 24)
        }
    }

    // MARK: - Building blocks

    private func pulsingTarget(icon: String, ringSize: CGFloat, iconSize: CGFloat) -> some View {
        ZStack {
            Circle()
                .stroke(.white.opacity(0.3), lineWidth: 2)
                .frame(width: ringSize, height: ringSize)
                .scaleEffect(pulse ? 1.15 : 0.9)
                .opacity(pulse ? 0 : 0.8)
                .animation(.easeOut(duration: 1.3).repeatForever(autoreverses: false), value: pulse)
            Image(systemName: icon)
                .font(.system(size: iconSize, weight: .semibold))
                .foregroundStyle(.white)
        }
    }

    private func tap(_ style: UIImpactFeedbackGenerator.FeedbackStyle = .light) {
        UIImpactFeedbackGenerator(style: style).impactOccurred()
    }
}

private struct PracticeHeroPillButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(.black)
            .background(.white, in: Capsule())
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .opacity(configuration.isPressed ? 0.88 : 1)
            .animation(.spring(response: 0.28, dampingFraction: 0.55), value: configuration.isPressed)
    }
}

private extension ButtonStyle where Self == PracticeHeroPillButtonStyle {
    static var practiceHeroPill: PracticeHeroPillButtonStyle { PracticeHeroPillButtonStyle() }
}
