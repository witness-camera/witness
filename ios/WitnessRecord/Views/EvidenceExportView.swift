import SwiftUI

/// Lists finished recordings and turns any of them into an evidence package:
/// a readable PDF report plus the manifest and time-stamp proof it describes.
///
/// The list is drawn from the local archive rather than from Drive, so it
/// keeps working with no network, no Google account, and long after the
/// footage itself has been cleaned off the device.
struct EvidenceExportView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var pro = ProEntitlement.shared

    @State private var sessions: [SessionManifest] = []
    @State private var exportedItems: [URL] = []
    @State private var showShare = false
    @State private var exportError: String?
    @State private var showPaywall = false

    var body: some View {
        NavigationStack {
            Group {
                if sessions.isEmpty {
                    ContentUnavailableView(
                        "No finished recordings",
                        systemImage: "doc.text.magnifyingglass",
                        description: Text("A record is kept here once a recording finishes and its manifest is written."))
                } else {
                    list
                }
            }
            .navigationTitle("Evidence packages")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("Done") { dismiss() } }
            .onAppear { sessions = SessionArchive.sessions() }
            .sheet(isPresented: $showShare) { ShareSheet(items: exportedItems) }
            .sheet(isPresented: $showPaywall) { PaywallView() }
            .alert("Couldn't build the package", isPresented: .constant(exportError != nil)) {
                Button("OK") { exportError = nil }
            } message: {
                Text(exportError ?? "")
            }
        }
    }

    private var list: some View {
        List {
            if !pro.isPro {
                Section {
                    Button { showPaywall = true } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "lock.fill").foregroundStyle(.red)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Unlock Witness Pro")
                                    .font(.headline)
                                    .foregroundStyle(.primary)
                                Text("Export evidence packages")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.caption.bold())
                                .foregroundStyle(.tertiary)
                        }
                    }
                } footer: {
                    Text("Your recordings and their proofs are kept either way. Pro adds the report that turns them into something an attorney or an editor can read.")
                }
            }

            Section {
                ForEach(sessions, id: \.sessionID) { manifest in
                    row(for: manifest)
                }
            } footer: {
                Text("A package contains a PDF report, the manifest.json it describes, and the time-stamp proof where one was obtained. It contains no video: the footage stays wherever you already keep it.")
            }
        }
    }

    private func row(for manifest: SessionManifest) -> some View {
        Button {
            guard pro.isPro else { showPaywall = true; return }
            export(manifest)
        } label: {
            VStack(alignment: .leading, spacing: 4) {
                Text(Self.dayFormatter.string(from: manifest.recordingStartedUTC))
                    .font(.headline)
                    .foregroundStyle(.primary)
                HStack(spacing: 6) {
                    Text("\(manifest.chunkCount) segments")
                    if SessionArchive.hasTimestamp(manifest.sessionID) {
                        Text("·")
                        Label("Time-stamped", systemImage: "checkmark.seal.fill")
                            .foregroundStyle(.green)
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
        }
    }

    private func export(_ manifest: SessionManifest) {
        let hasTimestamp = SessionArchive.hasTimestamp(manifest.sessionID)
        do {
            let pdf = try EvidenceReport.pdf(for: manifest, hasTimestamp: hasTimestamp)
            var items = [pdf]
            // The report is the readable part; these are what an independent
            // check is actually run against, so they travel with it.
            let manifestURL = SessionArchive.manifestURL(for: manifest.sessionID)
            if FileManager.default.fileExists(atPath: manifestURL.path) {
                items.append(manifestURL)
            }
            if hasTimestamp {
                items.append(SessionArchive.timestampURL(for: manifest.sessionID))
            }
            exportedItems = items
            showShare = true
        } catch {
            exportError = error.localizedDescription
        }
    }

    private static let dayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .medium
        f.timeStyle = .short
        return f
    }()
}

/// Thin bridge to the system share sheet, which SwiftUI has no native
/// equivalent for when sharing several files at once.
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
