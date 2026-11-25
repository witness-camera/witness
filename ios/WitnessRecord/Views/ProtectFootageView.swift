import SwiftUI

/// Plain-language explainer of how footage can still be deleted from a seized
/// phone, and the optional steps that guard against it. Guidance only — nothing
/// here is enforced, so users who prefer their everyday account can ignore it.
///
/// Written per storage backend rather than generically: the actual risk and
/// mitigation differ by account type (an OAuth account reachable through the
/// phone's own apps, versus a WebDAV server with no such phone-level
/// integration and no sharing at all), and naming the wrong app or feature
/// here would be actively misleading rather than merely generic.
struct ProtectFootageView: View {
    @ObservedObject private var storage = StorageProviders.shared

    private var providerName: String {
        storage.current?.id.displayName ?? String(localized: "your cloud account")
    }

    var body: some View {
        List {
            Section {
                Text("Witness never deletes your recordings, there is no delete button anywhere in this app, and it only ever adds files to your storage account.")
            } header: {
                Text("What this app does")
            }

            if storage.selected == .webDAV {
                Section {
                    Text("Your footage uploads to the server you configured. Anyone who can sign in to that server — through its own web interface, another app, or a different device — can delete files there, the same as with any account. WebDAV has no sharing feature, so emergency contacts are never granted a view to lose in the first place.")
                } header: {
                    Text("The risk")
                }

                Section {
                    Text("Use credentials on that server dedicated to Witness (an app password, where the server offers one) rather than your main account password, and don't sign in to that same server from apps on this phone. Then there's no way to reach it from the phone to delete anything.")
                } header: {
                    Text("Reducing the risk (optional)")
                }
            } else {
                Section {
                    Text("Your footage uploads to your own \(providerName) account. There, the account owner can always delete their files. So if someone has your unlocked phone and can open the \(providerName) app or its website — or, for Google, Gmail too, since it shares the same sign-in — they can delete the recordings there, and deleting them removes access for your emergency contacts too.")
                    Text("Sharing with an emergency contact does not, by itself, give them a separate copy: it grants them a view of your file. If your copy is deleted, their view disappears with it (though deleted files usually sit in a trash/recently-deleted folder for a while first, unless that is also emptied).")
                } header: {
                    Text("The risk")
                }

                Section {
                    Label("Use a dedicated account", systemImage: "person.crop.circle.badge.plus")
                        .font(.headline)
                    Text("Create a \(providerName) account used only for Witness, and sign in to it here. Crucially, do not add that account to your iPhone's Settings, any of that provider's apps, or any browser you're signed into. Then there is no way to open that account from your phone to delete anything, it simply isn't reachable through the phone's apps. This app keeps its own private login and offers no way to view or delete the footage.")
                } header: {
                    Text("Strongest protection (optional)")
                }

                Section {
                    Label("Have contacts keep their own copy", systemImage: "doc.on.doc")
                        .font(.headline)
                    Text("When an emergency contact receives the shared folder, they can save their own copy of the footage into their own account. That copy belongs to them and cannot be deleted from your phone or account.")
                } header: {
                    Text("Extra backstop (optional)")
                }
            }

            Section {
                Text("These steps are optional. If you'd rather use your everyday account, Witness still works exactly the same, this only affects how hard it is for someone with your phone to delete footage after the fact.")
                    .foregroundStyle(.secondary)
                    .font(.footnote)
            }
        }
        .navigationTitle("Protect footage")
        .navigationBarTitleDisplayMode(.inline)
    }
}
