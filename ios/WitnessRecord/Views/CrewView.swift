import SwiftUI

/// Starting, joining and running a crew: several phones recording one event
/// into a shared case on a common clock.
struct CrewView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var store = CrewStore.shared
    @ObservedObject private var nearby = CrewNearby.shared
    @ObservedObject private var cloud = CrewCloud.shared
    @ObservedObject private var storage = StorageProviders.shared
    @State private var newTitle = ""
    @State private var shownCrewID: UUID?
    @State private var exported: [URL] = []
    @State private var exportError: String?
    /// Decided when a crew is opened, not live, so the section doesn't jump
    /// away from under someone's finger as they finish typing.
    @State private var detailsFirst = false
    @State private var searchingLong = false

    private var shownCrew: Crew? {
        shownCrewID.flatMap(store.crew) ?? store.activeCrew
    }

    var body: some View {
        NavigationStack {
            Form {
                if let crew = shownCrew {
                    crewSections(crew)
                } else {
                    startSections
                }
                if !otherCrews.isEmpty {
                    Section("Earlier crews") {
                        ForEach(otherCrews) { crew in
                            Button(crew.title) { shownCrewID = crew.id }
                        }
                    }
                }
            }
            .navigationTitle("Crew")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .onAppear { syncNearby() }
            .onChange(of: shownCrew?.id) { _, _ in syncNearby() }
            .onDisappear {
                nearby.stop()
                cloud.focusedCrewID = nil
            }
        }
    }

    private var otherCrews: [Crew] {
        store.crews.filter { $0.id != shownCrew?.id }.sorted { $0.createdAt > $1.createdAt }
    }

    /// Looks for the crew's phones only while this screen is open, never
    /// during a recording.
    private func syncNearby() {
        detailsFirst = shownCrew.map { store.needsMyDetails(in: $0.id) } ?? false
        searchingLong = false
        cloud.focusedCrewID = shownCrew?.id
        cloud.syncNow()
        if let crew = shownCrew {
            nearby.start(crewID: crew.id)
            CrewPreviews.shared.sync(crew)
        } else { nearby.stop() }
    }

    // MARK: - Starting

    @ViewBuilder
    private var startSections: some View {
        Section {
            VStack(alignment: .leading, spacing: 6) {
                Text("Record together").font(.title2.weight(.bold))
                Text("Several phones film the same protest, raid or event into one shared case.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            .padding(.vertical, 4)
            reason("eye", "Every angle",
                   "What one phone misses, another catches: the start of an arrest, a face, a badge number.")
            reason("person.2", "Nobody holds it all",
                   "Each recording's folder is shared with the whole crew as it uploads. If one phone is taken, the others already have its footage.")
            reason("clock", "One timeline",
                   "Clocks are matched, so every angle lines up to the second. Export it as one multicam edit for Final Cut, Premiere or Resolve.")
            reason("lock", "No Witness server",
                   "Each phone keeps uploading to its own cloud. The crew shares only names, emails and timings, through iCloud, encrypted so only the crew can read them.")
        }
        Section("How it works") {
            step(1, "Start a crew and add your name and email below.")
            step(2, "The others scan your code with the iPhone Camera, or open the link you send.")
            step(3, "Before you start, keep this screen open near each other for a few seconds, so the phones match clocks.")
            step(4, "Record as usual. Afterwards, make the multicam edit files from here.")
        }
        myDetailsSection
        Section {
            TextField("Event name, e.g. March 12 protest", text: $newTitle)
            Button("Start a crew") {
                let title = newTitle.trimmingCharacters(in: .whitespaces)
                store.updateMyDetails()
                let crew = store.start(titled: title.isEmpty ? String(localized: "Crew") : title)
                shownCrewID = crew.id
                newTitle = ""
            }
        } footer: {
            Text("To join someone else's crew, scan their code with the iPhone Camera or open the link they sent.")
        }
    }

    private func reason(_ icon: String, _ title: LocalizedStringKey, _ text: LocalizedStringKey) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundStyle(.red)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.weight(.semibold))
                Text(text)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.vertical, 2)
    }

    private func step(_ number: Int, _ text: LocalizedStringKey) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text("\(number)")
                .font(.footnote.weight(.bold))
                .frame(width: 22, height: 22)
                .background(Circle().fill(.red.opacity(0.25)))
            Text(text)
                .font(.footnote)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var myDetailsSection: some View {
        Section {
            // Saved as typed, not on Return: the field used to keep what was
            // typed to itself until Return was pressed, which nobody does in
            // a form, so the crew never learned who had joined.
            TextField("Your name", text: Binding(get: { store.myName }, set: {
                store.myName = $0
                store.updateMyDetails()
            }))
                .textContentType(.name)
            TextField("Your email, to receive the crew's footage",
                      text: Binding(get: { store.myEmail }, set: {
                          store.myEmail = $0
                          store.updateMyDetails()
                      }))
                .textContentType(.emailAddress)
                .keyboardType(.emailAddress)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
        } header: {
            Text("You")
        } footer: {
            if detailsFirst {
                Text("Add your name and email so the crew can see you've joined and send you their footage. Shared only with your crew.")
            } else {
                Text("Shared only with your crew, inside the crew's code and link.")
            }
        }
    }

    // MARK: - A crew

    @ViewBuilder
    private func crewSections(_ crew: Crew) -> some View {
        let isActive = store.activeCrewID == crew.id
        Section {
            VStack(alignment: .leading, spacing: 4) {
                Text(crew.title).font(.headline)
                Text(isActive ? "New recordings join this crew." : "New recordings don't join this crew.")
                    .font(.footnote)
                    .foregroundStyle(isActive ? .green : .secondary)
            }
            if isActive {
                Button("Stop recording into this crew") { store.leave() }
            } else {
                Button("Record into this crew") { store.rejoin(crew.id) }
            }
        }

        if detailsFirst && store.myMemberID(in: crew.id) != nil {
            myDetailsSection
        }

        // Without an account nothing is uploaded, so there is no folder for
        // the crew to be given — the one way a member's angle never arrives.
        if !(storage.current?.isSignedIn ?? false) {
            Section {
                Label("Connect cloud storage on the home screen so your recordings reach the crew. Until then they stay on this iPhone.",
                      systemImage: "exclamationmark.icloud")
                    .font(.footnote)
                    .foregroundStyle(.orange)
            }
        }

        if let url = CrewLink.url(for: crew) {
            Section {
                if let image = CrewLink.qrImage(for: url) {
                    Image(uiImage: image)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: 260)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                        .accessibilityLabel("Crew code")
                }
                ShareLink(item: url, subject: Text(crew.title),
                          message: Text("Join my Witness crew for \(crew.title)")) {
                    Label("Send invite link", systemImage: "square.and.arrow.up")
                }
            } header: {
                Text("Invite")
            } footer: {
                Text("Scan in person to match clocks on the spot. Anyone with the code or link can join, so share it only with your crew. Whoever joins appears under Members within a minute.")
            }
        }

        Section {
            cloudRow(crew)
            clockRow(crew)
            if nearby.connectedNames.isEmpty {
                Label("Looking for crew phones nearby…", systemImage: "antenna.radiowaves.left.and.right")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .task(id: shownCrew?.id) {
                        try? await Task.sleep(for: .seconds(15))
                        searchingLong = true
                    }
                // The one failure iOS gives no sign of: a declined Local
                // Network prompt silently stops phones finding each other.
                if searchingLong {
                    Text("Nothing found yet? Both phones need this screen open, and Local Network allowed for Witness in Settings > Privacy & Security > Local Network.")
                        .font(.caption)
                        .foregroundStyle(.orange)
                        .fixedSize(horizontal: false, vertical: true)
                }
            } else {
                ForEach(nearby.connectedNames, id: \.self) { name in
                    Label(name, systemImage: "iphone.radiowaves.left.and.right")
                }
            }
        } header: {
            Text("Staying in sync")
        } footer: {
            Text("Over the internet, the crew list and recordings sync through iCloud, encrypted with a key only the crew has. Nearby, phones with this screen open also swap them and match clocks over Wi-Fi or Bluetooth, with no internet needed.")
        }

        Section("Members") {
            ForEach(crew.members) { member in
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(member.name)
                        if member.id == crew.organizerID {
                            Text("Organizer").font(.caption2).foregroundStyle(.secondary)
                        }
                        if member.id == store.myMemberID(in: crew.id) {
                            Text("You").font(.caption2).foregroundStyle(.secondary)
                        }
                        if member.isRecordingNow {
                            Label("Filming now", systemImage: "record.circle")
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(.red)
                        }
                    }
                    Text(member.email ?? String(localized: "No email: won't receive footage"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        if !detailsFirst && store.myMemberID(in: crew.id) != nil {
            myDetailsSection
        }

        Section {
            if crew.angles.isEmpty {
                Text("No recordings yet.").foregroundStyle(.secondary)
            } else {
                NavigationLink {
                    CrewPlayerView(crewID: crew.id)
                } label: {
                    Label("Watch all angles in sync", systemImage: "rectangle.stack.badge.play")
                        .font(.body.weight(.semibold))
                }
                ForEach(crew.angles.sorted { $0.startedAt < $1.startedAt }) { angle in
                    recordingRow(angle, in: crew)
                }
                Button("Make multicam edit files") { export(crew) }
                if !exported.isEmpty {
                    ShareLink(items: exported) {
                        Label("Share for Final Cut, Premiere or Resolve", systemImage: "film.stack")
                    }
                }
                if let exportError {
                    Text(exportError).font(.caption).foregroundStyle(.red)
                }
            }
        } header: {
            Text("Recordings")
        } footer: {
            Text("Recordings from other phones appear here when they stop, once their footage starts uploading. The folder opens in Google Drive or Dropbox for everyone who gave their email.")
        }

        Section {
            Button("Delete crew from this iPhone", role: .destructive) {
                CrewPreviews.shared.forget(crew)
                store.delete(crew.id)
                shownCrewID = nil
            }
        } footer: {
            Text("Recordings stay in your case and cloud. Folders already shared stay shared.")
        }
    }

    private func recordingRow(_ angle: CrewAngle, in crew: Crew) -> some View {
        let member = crew.members.first { $0.id == angle.memberID }?.name ?? ""
        let isMine = angle.memberID == store.myMemberID(in: crew.id)
        let minutes = Int((angle.duration / 60).rounded(.up))
        return VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(isMine ? String(localized: "You") : member)
                Spacer()
                Text("\(angle.startedAt.formatted(date: .omitted, time: .shortened)), \(minutes) min")
                    .foregroundStyle(.secondary)
            }
            if let url = angle.folderURL(isMine: isMine) {
                Link(destination: url) {
                    Label(angle.providerID == StorageProviderID.dropbox.rawValue
                            ? (isMine ? String(localized: "Open in Dropbox") : String(localized: "Open Dropbox shared folders"))
                            : String(localized: "Open in Google Drive"),
                          systemImage: "arrow.up.right.square")
                        .font(.footnote)
                }
            } else {
                Text(angle.providerID == StorageProviderID.webDAV.rawValue
                     ? String(localized: "On their own server, which can't be shared automatically.")
                     : String(localized: "Waiting for the footage to start uploading."))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func cloudRow(_ crew: Crew) -> some View {
        let text: String
        let icon: String
        if crew.secret == nil {
            text = String(localized: "This crew was started before iCloud sync. It syncs only nearby until the organizer shows the code or sends the link again.")
            icon = "icloud.slash"
        } else {
            switch cloud.status {
            case .synced(let date):
                text = String(localized: "Synced through iCloud \(date.formatted(.relative(presentation: .named))).")
                icon = "checkmark.icloud"
            case .noAccount:
                text = String(localized: "Sign in to iCloud in Settings to see the crew from anywhere. Nearby still works.")
                icon = "icloud.slash"
            case .notSetUp, .failed:
                text = String(localized: "iCloud sync isn't available right now. Nearby still works.")
                icon = "exclamationmark.icloud"
            case .idle:
                text = String(localized: "Syncing through iCloud…")
                icon = "icloud"
            }
        }
        return Label(text, systemImage: icon).font(.footnote)
    }

    private func clockRow(_ crew: Crew) -> some View {
        let clock = store.clock(for: crew.id)
        let text: String
        if store.myMemberID(in: crew.id) == crew.organizerID {
            text = String(localized: "Your clock is the crew's reference.")
        } else if let u = clock?.uncertainty {
            text = String(localized: "Clock matched to within \(Int((u * 1000).rounded())) ms.")
        } else {
            text = String(localized: "Clock not matched yet: open this screen near a crew member who is. Until then the phone's own time is used, usually within a fraction of a second.")
        }
        return Label(text, systemImage: "clock")
            .font(.footnote)
    }

    private func export(_ crew: Crew) {
        do {
            exported = try MulticamExport.export(crew)
            exportError = nil
        } catch {
            exportError = error.localizedDescription
        }
    }
}
