import SwiftUI
import StoreKit

/// Witness Pro paywall, styled like the onboarding and support pages.
///
/// Carries everything Guideline 3.1.2 requires on the page itself: the
/// subscription's name, length and price, the auto-renew disclosure, links to
/// the Terms of Use and Privacy Policy, and a restore path.
struct PaywallView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var pro = ProEntitlement.shared

    private let termsURL = URL(string: "https://www.apple.com/legal/internet-services/itunes/dev/stdeula/")!
    // witnessrecord.app 301s here, so this keeps working for any build that
    // shipped before the witnesscamera.com migration; new builds should point
    // straight at the current domain.
    private let privacyURL = URL(string: "https://witnesscamera.com/privacy")!

    var body: some View {
        ZStack {
            PaywallBackground()
                .ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Spacer(minLength: 40)

                    Image(systemName: "camera.aperture")
                        .font(.system(size: 30, weight: .semibold))
                        .foregroundStyle(.red)
                        .padding(.bottom, 14)

                    Text("Witness Pro")
                        .font(.system(size: 34, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.bottom, 9)

                    Text("Broadcast-grade capture, for documentary and investigative work.")
                        .font(.system(size: 21))
                        .foregroundStyle(.white.opacity(0.85))
                        .fixedSize(horizontal: false, vertical: true)

                    VStack(alignment: .leading, spacing: 18) {
                        factRow("4k.tv", "Record in 4K.")
                        factRow("speedometer", "24, 25 and 60 fps.")
                        factRow("swatchpalette", "Apple Log for full grading latitude.")
                        factRow("hand.raised.fill", "Stabilization from off to extreme, for walking or running.")
                        factRow("location.fill", "On-screen timestamp and location without the Witness tag.")
                        factRow("pip.fill", "Both cameras combined into one video.")
                        factRow("waveform", "A shorter highlight cut with the dead air trimmed out.")
                        factRow("camera.aperture", "Both rear lenses at once: 1× and 0.5×.")
                        factRow("eye.slash", "Faces and licence plates blurred, ready to publish.")
                        factRow("rectangle", "Landscape video with the phone held upright.")
                        factRow("flag.fill", "Mark key moments with the volume button.")
                    }
                    .padding(.top, 26)

                    Text("Everything in the free app stays free. Pro only adds capture quality.")
                        .font(.system(size: 14))
                        .foregroundStyle(.white.opacity(0.5))
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 22)

                    Spacer(minLength: 32)

                    purchaseSection

                    legalFooter
                        .padding(.top, 18)
                        .padding(.bottom, 24)
                }
                .padding(.horizontal, 28)
            }
        }
        .preferredColorScheme(.dark)
        .overlay(alignment: .topTrailing) {
            Button { dismiss() } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(.white.opacity(0.75))
                    .frame(width: 34, height: 34)
                    .background(.white.opacity(0.1), in: Circle())
            }
            .padding(.trailing, 20)
            .padding(.top, 6)
        }
        .onChange(of: pro.isPro) { _, isPro in
            // Nothing left to sell once the purchase lands.
            if isPro { dismiss() }
        }
    }

    @ViewBuilder
    private var purchaseSection: some View {
        if pro.storeUnavailable {
            Text("The App Store isn't reachable right now. Check your connection and try again.")
                .font(.system(size: 15))
                .foregroundStyle(.orange)
                .fixedSize(horizontal: false, vertical: true)
        } else if pro.monthly != nil || pro.lifetime != nil {
            // The subscription stays visually primary — it's the lower
            // commitment and the expected default — with the one-time
            // purchase offered underneath rather than as an equal choice,
            // so someone just wanting to try Pro isn't asked to decide
            // between two prices before they've used it once.
            if let monthly = pro.monthly {
                purchaseButton(monthly, label: "\(monthly.displayPrice) / month", prominent: true)
            }
            if pro.monthly != nil, pro.lifetime != nil {
                Text("or")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(.white.opacity(0.4))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 4)
            }
            if let lifetime = pro.lifetime {
                purchaseButton(
                    lifetime, label: "\(lifetime.displayPrice) once — yours forever",
                    prominent: pro.monthly == nil)
            }

            Button {
                Task { await pro.restore() }
            } label: {
                Text("Restore purchases")
                    .font(.system(size: 15))
                    .foregroundStyle(.white.opacity(0.6))
                    .frame(maxWidth: .infinity)
                    .padding(.top, 14)
            }
            .disabled(pro.isWorking)
        } else {
            ProgressView()
                .tint(.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 16)
        }
    }

    /// One purchase option. `prominent` is filled-white (the expected
    /// choice); the other is outlined, so two buttons never compete equally
    /// for attention.
    private func purchaseButton(_ product: Product, label: String, prominent: Bool) -> some View {
        Button {
            Task { await pro.purchase(product) }
        } label: {
            Group {
                if pro.isWorking {
                    ProgressView().tint(prominent ? .black : .white)
                } else {
                    // Price comes from StoreKit, never hard-coded, so it
                    // stays correct in every storefront and currency.
                    Text(label)
                        .font(.system(size: 17, weight: .semibold))
                }
            }
            .foregroundStyle(prominent ? .black : .white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .background {
                if prominent {
                    Capsule().fill(.white)
                } else {
                    Capsule().strokeBorder(.white.opacity(0.5), lineWidth: 1.5)
                }
            }
        }
        .disabled(pro.isWorking)
    }

    private var legalFooter: some View {
        VStack(alignment: .leading, spacing: 10) {
            // Each disclosure only appears while its product does — stated
            // unconditionally, "auto-renews" would be a false claim for
            // someone who bought only the one-time option, and Guideline
            // 3.1.2 requires the terms of what's actually being sold.
            if pro.monthly != nil {
                Text("Witness Pro Monthly auto-renews until cancelled. Cancel any time in your App Store account settings.")
                    .font(.system(size: 12))
                    .foregroundStyle(.white.opacity(0.45))
                    .fixedSize(horizontal: false, vertical: true)
            }
            if pro.lifetime != nil {
                Text("Witness Pro Lifetime is a one-time purchase. It doesn't renew and there's nothing to cancel.")
                    .font(.system(size: 12))
                    .foregroundStyle(.white.opacity(0.45))
                    .fixedSize(horizontal: false, vertical: true)
            }

            HStack(spacing: 16) {
                Link("Terms of Use", destination: termsURL)
                Link("Privacy Policy", destination: privacyURL)
            }
            .font(.system(size: 12, weight: .medium))
            .foregroundStyle(.white.opacity(0.6))
        }
    }

    private func factRow(_ icon: String, _ text: LocalizedStringKey) -> some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 19, weight: .semibold))
                .foregroundStyle(.red)
                .frame(width: 26)
            Text(text)
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(.white)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// Matches the ambient treatment used by onboarding and support. The glow is
/// an overlay on a flexible color, never a sibling: its fixed frame is wider
/// than the screen and as a sibling it would size the stack past the display.
private struct PaywallBackground: View {
    var body: some View {
        Color.black
            .overlay {
                RadialGradient(
                    colors: [.red.opacity(0.36), .red.opacity(0.05), .clear],
                    center: .center, startRadius: 10, endRadius: 380
                )
                .frame(width: 520, height: 520)
                .blur(radius: 60)
                .offset(CGSize(width: -60, height: -150))
            }
            .overlay {
                LinearGradient(
                    colors: [.white.opacity(0.05), .clear, .white.opacity(0.03)],
                    startPoint: .topLeading, endPoint: .bottomTrailing
                )
            }
            .clipped()
    }
}
