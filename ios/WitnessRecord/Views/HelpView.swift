import SwiftUI

/// Answers for somebody who thinks they have lost a recording.
///
/// Written for the worst moment to be reading it: an encounter has just ended,
/// the footage is not where they expected, and they are deciding whether it is
/// gone. So the reassurance that nothing is deleted comes first, before any
/// question, and every answer names the exact switch or screen to look at
/// rather than describing the mechanism in general terms.
///
/// The questions are the ones users have actually asked. A journalist lost an
/// afternoon's recording to a Drive account that was full — the app said only
/// that it could not create the folder — and the answer she needed was two
/// sentences long. This page is where those two sentences live.
struct HelpView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var showProtectFootage = false

    private let supportEmail = "athingby@gmail.com"

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Label {
                        Text("Witness has no delete button.")
                            .font(.headline)
                    } icon: {
                        Image(systemName: "checkmark.shield.fill")
                            .foregroundStyle(.green)
                    }
                    Text("It removes its own copy of a piece of footage only after your storage account has confirmed that the piece arrived. If a recording cannot be saved anywhere yet, it stays on this phone and Witness tries again every time you open the app.")
                    Text("Recordings are almost never lost. They are usually somewhere you have not looked yet.")
                        .foregroundStyle(.secondary)
                }

                // Always visible, not collapsed like the rest: this is the
                // single scenario people have actually lost sleep over — an
                // encounter ended by the app closing rather than the stop
                // button, followed by not finding the video afterwards — so
                // it does not wait to be found inside a disclosure list.
                Section {
                    Label {
                        Text("Closed the app, or the phone locked, before you pressed stop?")
                            .font(.subheadline.weight(.semibold))
                    } icon: {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundStyle(.orange)
                    }
                    answer("That already finished the recording. Witness treats the app closing — force-quit, a lock screen, the phone dying — exactly like pressing stop: it saves and hands on whatever was filmed, right away, not the next time you open the app.")
                    answer("A long recording, or one made at a heavy Witness Pro quality setting, can take longer to finish putting together than the few seconds iOS allows an app to keep working once it's closed. If that happens, Witness sends a notification asking you to reopen it — that's the only time it will, and only because reopening genuinely still helps in that one case.")
                    answer("So reopen Witness now. Check the main screen: if an upload count is showing, that recording made it. Then look in Photos and your cloud folder, below.")
                    answer("Still not there? Open the Files app, Browse, “On My iPhone”, Witness, then “sessions”. That folder holds the finished video until Photos or your cloud storage has it — nothing in it is ever deleted automatically, so if it exists at all, it is there.")
                } header: {
                    Text("If you think you just lost one")
                }

                Section {
                    destination("icloud", "Your cloud folder",
                                "Uploaded while you film, in 15-second pieces, one at a time. Each session gets its own folder named after the date, time and place.")
                    destination("photo.on.rectangle", "Your Photos library",
                                "One video per camera for each session, if “Save recordings to Photos” is on in Settings.")
                    destination("iphone", "This phone",
                                "Every piece is a complete, playable video file, and you can reach them yourself through the Files app.")
                } header: {
                    Text("Where a recording goes")
                }

                Section {
                    question("Recording an event with other people") {
                        answer("Tap the people icon at the top left of the main screen and start a crew. Others join by scanning your code with the iPhone Camera, or opening the link you send.")
                        answer("Every phone keeps uploading to its own cloud, and each recording's folder is shared with the crew's emails. Keep the crew screen open near each other for a moment before you start: the phones match their clocks, so every angle lines up.")
                        answer("Afterwards, open the crew and tap “Make multicam edit files” for Final Cut, Premiere or DaVinci Resolve.")
                    }
                } header: {
                    Text("Crew")
                }

                Section {
                    question("It isn't in my Photos library") {
                        answer("Open Settings, the gear at the top right, and check that “Save recordings to Photos” is switched on. It only affects recordings made after you turn it on.")
                        answer("Then open the iPhone's own Settings app, find Witness, and check that Photos is set to “Add Photos Only” or “Full Access”. If permission was refused, the library rejected the video — but Witness did not throw it away. It keeps it and offers it to the library again each time you open the app, so granting permission now is usually enough to get it back.")
                        answer("After a long recording, give it a few seconds. The video is assembled as you film, so it should appear almost immediately.")
                    }

                    question("It isn't in my cloud folder") {
                        answer("Tap the orange upload count on the main screen. That shows the queue itself: every piece still waiting, how far the current one has got, and what has already arrived.")
                        answer("The most common cause by far is a storage account with no room left. Witness cannot create the session folder, and everything stops behind it. Free up space, or connect a different account, and the queue starts moving again on its own — nothing is lost while it waits.")
                        answer("The next most common cause is the connection. Pieces upload one at a time so that each one finishes, and when the network is struggling a small preview copy of each piece goes up first. A folder holding previews is not a failure; the full-quality files follow and replace them.")
                    }

                    question("The app closed, or the phone was taken, mid-recording") {
                        answer("Every 15 seconds Witness closes the piece it is writing and starts a new one, so the recording is never held hostage by a file that has not finished. Whatever has already uploaded is beyond the reach of the phone entirely.")
                        answer("Leaving the app now ends the recording exactly as if you had pressed stop: it is finished, saved and handed on straight away, not the next time you open Witness.")
                        answer("At worst you lose the few seconds that were still being written when the app stopped.")
                        answer("On an older version of Witness this could instead need the app reopened to finish. If a video from before an update seems to be missing, open Witness once — that alone can complete it — and see “If you think you just lost one” above for where to look next.")
                    }

                    question("How do I get the footage off the phone myself?") {
                        answer("Open the Files app, tap Browse, then “On My iPhone”, then Witness.")
                        answer("“sessions” holds the combined video for each recording. “chunks” holds the individual pieces that are still waiting to upload.")
                        answer("From there you can AirDrop them, copy them to a computer, or save them into any other app. Move copies out rather than deleting anything — Witness is still keeping track of what has and has not been delivered.")
                    }

                    question("Why is my recording in pieces instead of one video?") {
                        answer("Because a single continuous file cannot be uploaded until it has finished. If the phone is taken during an encounter, a recording made that way goes with it. Pieces start leaving this phone after fifteen seconds.")
                        answer("Witness puts them back together for you: one video per camera into Photos, and, when “Combine into one video per camera” is on in Settings, one into your cloud folder as well — but only once every individual piece is already confirmed safe.")
                    }

                    question("Something has been queued for a long time") {
                        answer("Uploads carry on when Witness is closed — iOS runs them for you — but it schedules them at its own pace. Opening Witness on Wi-Fi and leaving it on screen is the fastest way to clear a backlog.")
                        answer("Nothing in the queue expires, and it survives restarting the phone. A piece waits until it is delivered.")
                    }

                    question("Can anyone delete my recordings?") {
                        answer("Not through this app. There is no delete button anywhere in Witness, and it only ever adds files to your storage account.")
                        answer("The real risk is different: somebody holding your unlocked phone could sign in to your storage account through that provider's own app or website and delete the footage there. That is worth guarding against in advance.")
                        // A Button presenting a sheet, not a NavigationLink:
                        // nested this deep inside a DisclosureGroup's content
                        // within a List row, a NavigationLink's tap is
                        // swallowed by the disclosure's own hit-testing and
                        // never reaches it.
                        Button { showProtectFootage = true } label: {
                            Label("Protect footage from deletion", systemImage: "lock.shield")
                        }
                    }
                } header: {
                    Text("Common questions")
                }

                Section {
                    Link(destination: URL(string: "mailto:\(supportEmail)")!) {
                        Label("Email us", systemImage: "envelope")
                    }
                    Text("Say roughly when you recorded, which storage account you were using, and whether “Save recordings to Photos” was on. A photo of the upload queue screen helps most of all.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } header: {
                    Text("Still can't find it")
                } footer: {
                    Text("Nothing is given up on. If a recording cannot reach your storage or your photo library, it stays on this phone until it can.")
                }
            }
            .navigationTitle("Finding your footage")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                Button("Done") { dismiss() }
            }
        }
        .sheet(isPresented: $showProtectFootage) {
            // ProtectFootageView normally relies on a NavigationStack's own
            // back button (as pushed from Settings); presented as a sheet
            // here, it needs its own Done button instead.
            NavigationStack {
                ProtectFootageView()
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) {
                            Button("Done") { showProtectFootage = false }
                        }
                    }
            }
        }
    }

    private func destination(_ symbol: String, _ title: LocalizedStringKey,
                             _ detail: LocalizedStringKey) -> some View {
        Label {
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.subheadline.weight(.semibold))
                Text(detail).font(.footnote).foregroundStyle(.secondary)
            }
        } icon: {
            Image(systemName: symbol)
                .foregroundStyle(.red)
        }
        .padding(.vertical, 2)
    }

    /// One collapsed question. Collapsed by default because somebody looking
    /// for their footage needs to find their own question quickly, not read
    /// the other six.
    private func question<Content: View>(_ title: LocalizedStringKey,
                                         @ViewBuilder content: () -> Content) -> some View {
        // Built eagerly rather than passed straight to DisclosureGroup's own
        // trailing closure: DisclosureGroup's closures escape, and a
        // non-escaping ViewBuilder parameter can't be captured by one.
        let body = VStack(alignment: .leading, spacing: 12) { content() }
            .padding(.vertical, 4)
        return DisclosureGroup {
            body
        } label: {
            Text(title).font(.subheadline.weight(.semibold))
        }
    }

    private func answer(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .font(.callout)
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}
