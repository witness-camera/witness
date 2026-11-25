import SwiftUI

/// Where footage gets uploaded: shown when connecting an account, in the same
/// shape as choosing a mail provider during iOS setup.
///
/// Presented before any sign-in, so the decision is made once and knowingly.
/// Switching later doesn't move footage already uploaded — each account keeps
/// whatever it received — and the page says so rather than leaving the user to
/// discover it.
struct StoragePickerView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var storage = StorageProviders.shared
    @State private var unsupportedExplanation: StorageProviderID?

    /// Called with the chosen provider once it is ready to sign in, so the
    /// caller can present the account flow from its own view controller.
    var onSelect: (StorageProviderID) -> Void

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(StorageProviderID.allCases) { provider in
                        row(for: provider)
                    }
                } header: {
                    Text("Choose where footage goes")
                } footer: {
                    Text("Recordings upload to an account you own. Witness has no server of its own and keeps no copy: the developer cannot see your footage on any of these services.")
                }
            }
            .navigationTitle("Storage")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("Cancel") { dismiss() } }
        }
        .alert(
            StorageProviders.unsupportedExplanationTitle,
            isPresented: Binding(
                get: { unsupportedExplanation != nil },
                set: { if !$0 { unsupportedExplanation = nil } })
        ) {
            Button("OK") { unsupportedExplanation = nil }
        } message: {
            Text(StorageProviders.unsupportedExplanationMessage)
        }
    }

    private func row(for provider: StorageProviderID) -> some View {
        let connectable = storage.provider(provider) != nil
        let unsupported = storage.isUnsupported(provider)
        // Unsupported is a real, tappable row (it explains itself) — only a
        // provider with neither an adapter nor that explanation is disabled.
        let tappable = connectable || unsupported
        return Button {
            if unsupported {
                unsupportedExplanation = provider
                return
            }
            guard connectable else { return }
            storage.select(provider)
            onSelect(provider)
            dismiss()
        } label: {
            HStack(spacing: 14) {
                icon(for: provider)
                    .frame(width: 28, height: 28)

                VStack(alignment: .leading, spacing: 2) {
                    Text(provider.displayName)
                        .font(.body.weight(.medium))
                        .foregroundStyle(tappable ? .primary : .secondary)
                    Text(tappable ? provider.subtitle : "Coming soon")
                        .font(.caption)
                        .foregroundStyle(unsupported ? .orange : .secondary)
                }

                Spacer()

                if storage.selected == provider && connectable {
                    Image(systemName: "checkmark")
                        .font(.footnote.bold())
                        .foregroundStyle(.red)
                } else if unsupported {
                    Image(systemName: "info.circle")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            // On the label, not on the Button: a plain button's hit area is
            // the label's drawn content, so without this a tap on the empty
            // right-hand side of a row does nothing at all.
            .contentShape(Rectangle())
        }
        // Plain, not the default button style: inside a List a tinted button
        // makes its whole label take the accent colour, and because .primary
        // and .secondary are *hierarchical* styles they resolve against that
        // tint rather than overriding it — which rendered every provider name
        // in the app's red. Plain keeps the colours literal.
        .buttonStyle(.plain)
        .disabled(!tappable)
    }

    @ViewBuilder
    private func icon(for provider: StorageProviderID) -> some View {
        if let asset = provider.iconAssetName {
            Image(asset)
                .resizable()
                .scaledToFit()
        } else {
            Image(systemName: provider.fallbackSymbol)
                .font(.system(size: 20))
                .foregroundStyle(.secondary)
        }
    }
}
