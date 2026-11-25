import SwiftUI

/// What is still on the phone, and how far each piece has got.
///
/// The home screen says "8 chunks queued for upload", which answers how many
/// but not whether anything is actually moving. On a bad connection those are
/// very different questions, and this app exists precisely for the situations
/// where the answer matters. This page shows the queue itself: which pieces,
/// how large, which one is transferring right now and how far through it is.
struct UploadQueueView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var uploads = UploadManager.shared

    var body: some View {
        NavigationStack {
            List {
                if uploads.queued.isEmpty {
                    Section { everythingUploaded }
                } else {
                    // Above the per-piece rows, not just on the home screen:
                    // a failure that happens before any piece is even
                    // attempted — no storage account, a server that refuses
                    // to create the folder — would otherwise leave every row
                    // below saying only "retrying", with nothing here to say
                    // why. This is exactly what got reported as a folder
                    // stuck at "Could not create Drive folder" with no
                    // visible cause on the one screen someone would open to
                    // find out.
                    if let error = uploads.lastError {
                        Section { rootCauseRow(error) }
                    }
                    Section {
                        ForEach(uploads.queued) { chunk in
                            row(for: chunk)
                        }
                    } header: {
                        Text(headerText)
                    } footer: {
                        Text(footerText)
                    }
                }
                if let receipt = uploads.lastUpload { deliveredSection(receipt) }
            }
            .navigationTitle("Upload queue")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                Button("Done") { dismiss() }
            }
        }
        .preferredColorScheme(.dark)
    }

    /// Says what the list above actually is: the order things will go in, one
    /// at a time. The preview sentence only appears when previews exist, since
    /// they are only made on a connection that is losing ground.
    private var footerText: String {
        let tail = "Nothing is deleted from this phone until your storage confirms it arrived."
        if uploads.queued.contains(where: { $0.kind == .lowRes }) {
            return "Small preview copies go up straight away, without waiting behind the full "
                + "piece they stand in for. Full pieces go one at a time, oldest first, so each "
                + "one finishes instead of all of them crawling together. " + tail
        }
        return "Pieces upload one at a time, oldest first, so each one finishes instead of all "
            + "of them crawling together. " + tail
    }

    // MARK: - Rows

    private func row(for chunk: QueuedChunk) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack {
                Image(systemName: chunk.camera == .front ? "person.crop.square" : (chunk.camera == .ultraWide ? "camera.aperture" : "video"))
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
                    .frame(width: 18)

                Text(title(for: chunk))
                    .font(.system(size: 15, weight: .medium))

                Spacer()

                if let bytes = chunk.byteCount {
                    Text(ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file))
                        .font(.system(size: 13))
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                }
            }

            // A determinate bar only while bytes are actually moving. Showing
            // one at zero for a queued chunk would imply an upload is underway
            // when nothing has started.
            if let fraction = uploads.chunkProgress[chunk.id] {
                ProgressView(value: fraction)
                    .tint(.orange)
                Text("\(Int(fraction * 100))% uploaded")
                    .font(.system(size: 12))
                    .foregroundStyle(.orange)
                    .monospacedDigit()
            } else {
                Text(statusText(for: chunk))
                    .font(.system(size: 12))
                    .foregroundStyle(statusColour(for: chunk))
                // What the last attempt actually said, not just that it's
                // being retried. Kept even while a fresh attempt is
                // implicitly under way (state briefly out of sync with a
                // pump that hasn't ticked yet) rather than cleared eagerly —
                // a stale reason for a few seconds beats none at all, and it
                // disappears the moment this piece actually uploads, since
                // an uploaded record drops out of this list entirely.
                if let reason = chunk.lastErrorMessage {
                    Text(reason)
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
            }
        }
        .padding(.vertical, 3)
    }

    /// The one message that can explain every row at once — set only when a
    /// failure happened before any specific piece was even attempted.
    private func rootCauseRow(_ message: String) -> some View {
        Label {
            VStack(alignment: .leading, spacing: 2) {
                Text("Nothing can upload right now")
                    .font(.system(size: 14, weight: .semibold))
                Text(message)
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
            }
        } icon: {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
        }
    }

    private func title(for chunk: QueuedChunk) -> String {
        let camera = chunk.camera.displayName
        let piece = "\(camera) piece \(chunk.index + 1)"
        // The stand-in is a different thing from the recording it precedes,
        // and someone counting pieces should not have to wonder why there are
        // more than they filmed.
        return chunk.kind == .lowRes ? "\(piece) (preview copy)" : piece
    }

    private func statusText(for chunk: QueuedChunk) -> String {
        if let retry = chunk.nextRetryAt, retry > Date() {
            let seconds = max(1, Int(retry.timeIntervalSinceNow))
            // Zero attempts means this chunk was never actually sent: setup
            // failed ahead of it — no account, or a server that would not give
            // us the folder — and it was handed back untried. Saying "after 0
            // attempts" described that as a retry, which reads like the upload
            // is being attempted and failing, and sent at least one person
            // looking for a fault in their upload rather than their account.
            guard chunk.attemptCount > 0 else {
                return "Waiting for your storage — retrying in \(seconds)s"
            }
            let tried = chunk.attemptCount == 1 ? "1 attempt" : "\(chunk.attemptCount) attempts"
            return "Retrying in \(seconds)s, after \(tried)"
        }
        switch chunk.state {
        case .pendingHash: return "Fingerprinting"
        case .queued:      return "Waiting to upload"
        case .uploading:   return "Starting upload"
        case .uploaded:    return "Uploaded"
        }
    }

    private func statusColour(for chunk: QueuedChunk) -> Color {
        if let retry = chunk.nextRetryAt, retry > Date() { return .orange }
        return .secondary
    }

    private var headerText: String {
        let count = uploads.queued.count
        let total = uploads.queued.compactMap(\.byteCount).reduce(0, +)
        guard total > 0 else {
            return count == 1 ? "1 piece remaining" : "\(count) pieces remaining"
        }
        let size = ByteCountFormatter.string(fromByteCount: Int64(total), countStyle: .file)
        return count == 1 ? "1 piece remaining, \(size)" : "\(count) pieces remaining, \(size)"
    }

    // MARK: - What already landed

    /// The counterpart to the queue above: not what is still waiting, but what
    /// is confirmed to be on the user's storage right now.
    ///
    /// The queue answers "is it moving", which is the anxious question during
    /// an encounter. This answers the one that matters afterwards — how much
    /// of the recording actually got out — and it stays answerable after the
    /// queue has emptied and there is nothing else on this screen to see.
    private func deliveredSection(_ receipt: SessionUploadReceipt) -> some View {
        Section {
            LabeledContent("Pieces delivered", value: "\(receipt.pieces)")
            if receipt.previews > 0 {
                LabeledContent("Preview copies sent", value: "\(receipt.previews)")
            }
            if receipt.bytes > 0 {
                LabeledContent("Total", value: ByteCountFormatter.string(
                    fromByteCount: Int64(receipt.bytes), countStyle: .file))
            }
            LabeledContent("Last confirmed", value: relative(receipt.lastConfirmedAt))
            if receipt.mergedVideos > 0 {
                Label(receipt.mergedVideos == 1
                      ? "Combined into one video"
                      : "Combined into \(receipt.mergedVideos) videos",
                      systemImage: "square.stack.3d.down.right")
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text("Recording of \(started(receipt.startedAt))")
        } footer: {
            Text(receipt.mergedVideos > 0
                 ? "Confirmed by your storage. The separate pieces have been replaced there by one video per camera, which is why the folder holds fewer files than the count above."
                 : "Confirmed by your storage — these are on it now, not on their way.")
        }
    }

    /// Absolute for the recording (which one), relative for the confirmation
    /// (how stale). Mixing them deliberately: the first is an identity, the
    /// second is a freshness check.
    private func started(_ date: Date) -> String {
        date.formatted(date: .abbreviated, time: .shortened)
    }

    private func relative(_ date: Date) -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .full
        return formatter.localizedString(for: date, relativeTo: Date())
    }

    // MARK: - Empty

    private var everythingUploaded: some View {
        VStack(spacing: 12) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 34))
                .foregroundStyle(.green)
            Text("Everything has been uploaded")
                .font(.system(size: 19, weight: .semibold))
            Text("Nothing is waiting on this phone.")
                .font(.system(size: 14))
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 24)
        .frame(maxWidth: .infinity)
        .listRowBackground(Color.clear)
    }
}
