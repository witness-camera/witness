import SwiftUI

/// Illustrated support page, styled like the onboarding pages (full-bleed
/// ambient backdrop, large type, red glyphs) rather than a plain settings
/// list — it's the one place the project asks something of the user, so it
/// gets the same care as the pages that explain the app.
///
/// One email address covers feedback, support questions, and donations. No
/// in-app payment flow: donations happen externally via PayPal, entirely
/// outside the app.
struct SupportView: View {
    @Environment(\.dismiss) private var dismiss

    private let supportEmail = "athingby@gmail.com"
    private let donateURL = URL(string: "https://paypal.me/athingby")!

    /// PayPal brand blue (#0070BA).
    private static let payPalBlue = Color(red: 0, green: 112 / 255, blue: 186 / 255)

    var body: some View {
        ZStack {
            SupportBackground()
                .ignoresSafeArea()

            VStack(alignment: .leading, spacing: 0) {
                Spacer(minLength: 40)

                Image(systemName: "heart.fill")
                    .font(.system(size: 30, weight: .semibold))
                    .foregroundStyle(.red)
                    .padding(.bottom, 14)

                Text("Support Witness")
                    .font(.system(size: 34, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(.bottom, 9)

                Text("A civic project, free for everyone, and always will be.")
                    .font(.system(size: 21))
                    .foregroundStyle(.white.opacity(0.85))
                    .fixedSize(horizontal: false, vertical: true)

                VStack(alignment: .leading, spacing: 18) {
                    factRow("infinity", "Free forever, no ads.")
                    factRow("gift.fill", "Kept running by donations.")
                    factRow("bubble.left.fill", "Bugs and ideas welcome.")
                }
                .padding(.top, 26)

                Spacer()

                VStack(spacing: 12) {
                    // PayPal's own brand blue, so the donate action reads as
                    // what it is at a glance instead of as generic chrome.
                    Link(destination: donateURL) {
                        HStack(spacing: 10) {
                            Image(systemName: "heart.fill")
                                .font(.system(size: 16, weight: .semibold))
                            Text("Donate with PayPal")
                                .font(.system(size: 17, weight: .semibold))
                        }
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                        .background(Self.payPalBlue, in: Capsule())
                    }

                    Link(destination: URL(string: "mailto:\(supportEmail)")!) {
                        HStack(spacing: 10) {
                            Image(systemName: "envelope.fill")
                                .font(.system(size: 16, weight: .semibold))
                            Text(verbatim: supportEmail)
                                .font(.system(size: 17, weight: .semibold))
                        }
                        .foregroundStyle(.black)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                        .background(.white, in: Capsule())
                    }
                }

                Text("If you're able, contributions help keep Witness going and fund future apps for citizens.")
                    .font(.system(size: 14))
                    .foregroundStyle(.white.opacity(0.5))
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 16)
                    .padding(.bottom, 20)
            }
            .padding(.horizontal, 28)
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

/// Same ambient treatment as the onboarding backdrop, held static here since
/// this is a single page rather than a paged flow.
private struct SupportBackground: View {
    var body: some View {
        // The glow is layered as an overlay on a flexible Color rather than
        // as a ZStack sibling: its fixed 520pt frame is wider than the
        // screen, and as a sibling it would size the whole stack to 520pt,
        // pushing the page's content off both edges.
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
