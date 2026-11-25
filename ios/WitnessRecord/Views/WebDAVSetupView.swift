import SwiftUI

/// Collects a WebDAV server and credentials, and refuses to save them until
/// the server has actually accepted them.
///
/// Verifying before saving matters more here than in a typical settings form:
/// a mistyped address or password wouldn't fail visibly, it would fail as
/// uploads that quietly never arrive — discovered, at the worst possible
/// moment, as an empty folder after an encounter.
struct WebDAVSetupView: View {
    @Environment(\.dismiss) private var dismiss

    var onConnected: () -> Void

    @State private var serverAddress = ""
    @State private var username = ""
    @State private var password = ""
    @State private var isVerifying = false
    @State private var isSigningIn = false
    @State private var errorMessage: String?

    private var canSubmit: Bool {
        !serverAddress.trimmingCharacters(in: .whitespaces).isEmpty
            && !username.isEmpty && !password.isEmpty && !isVerifying && !isSigningIn
    }

    /// The login flow needs the address and nothing else — the whole point is
    /// that the username and password are settled on the server's own page.
    private var canStartLoginFlow: Bool {
        !serverAddress.trimmingCharacters(in: .whitespaces).isEmpty
            && !isVerifying && !isSigningIn
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("https://cloud.example.org/remote.php/dav/files/you",
                              text: $serverAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                    TextField("Username", text: $username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("Password", text: $password)
                } header: {
                    Text("Server")
                } footer: {
                    Text("On Nextcloud or ownCloud, the address is under Settings, then WebDAV.")
                    + Text("\n\n")
                    + Text("Use an app password, not your account password. On Nextcloud it is under Settings → Security → Devices & sessions. This is not only for safety — with an account password Nextcloud may accept the connection, create the folder, and then reject every upload, so the app appears to be working while nothing arrives. An app password also lets you revoke this phone on its own if it is lost.")
                }

                if let errorMessage {
                    Section {
                        Label(errorMessage, systemImage: "exclamationmark.triangle.fill")
                            .font(.footnote)
                            .foregroundStyle(.orange)
                    }
                }

                Section {
                    Button {
                        Task { await signInWithNextcloud() }
                    } label: {
                        HStack {
                            if isSigningIn { ProgressView().padding(.trailing, 6) }
                            Text(isSigningIn ? "Waiting for your server…" : "Sign in with Nextcloud")
                        }
                    }
                    .disabled(!canStartLoginFlow)
                } footer: {
                    Text("Opens your own server's login page, where you sign in normally — password, two-factor, whatever you already use. Your server then gives this phone its own app password, so your account password is never typed into Witness and you can revoke this one device without changing anything else. Needs only the address above.")
                }

                Section {
                    Button {
                        Task { await connect() }
                    } label: {
                        HStack {
                            if isVerifying { ProgressView().padding(.trailing, 6) }
                            Text(isVerifying ? "Checking…" : "Connect")
                        }
                    }
                    .disabled(!canSubmit)
                } footer: {
                    Text("Emergency contacts can't be given access automatically on WebDAV: the protocol has no sharing feature, and each server adds its own. Everything else works the same, and you can still share a folder yourself from your server.")
                }
            }
            .navigationTitle("WebDAV")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("Cancel") { dismiss() } }
        }
    }

    /// Hands the whole credential question to the server and stores what it
    /// gives back. See `NextcloudLoginFlow`.
    private func signInWithNextcloud() async {
        errorMessage = nil
        guard let url = validatedServerURL() else { return }

        isSigningIn = true
        defer { isSigningIn = false }
        do {
            let credentials = try await NextcloudLoginFlow.shared.signIn(serverURL: url)
            // Verified rather than trusted: the flow proves the account, not
            // that WebDAV is reachable at the path derived from it, and a
            // stored setting that has never moved a byte is the failure this
            // screen exists to prevent.
            let resolved = try await WebDAVProvider.verify(
                serverURL: credentials.server,
                username: credentials.loginName,
                password: credentials.appPassword)
            WebDAVProvider.store(serverURL: resolved,
                                 username: credentials.loginName,
                                 password: credentials.appPassword)
            onConnected()
            dismiss()
        } catch is CancellationError {
            // The browser was dismissed; nothing to report.
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Shared by both routes, so neither can be the one that forgets to insist
    /// on https.
    private func validatedServerURL() -> URL? {
        let trimmed = serverAddress.trimmingCharacters(in: .whitespaces)
        guard let url = URL(string: trimmed), url.scheme != nil, url.host() != nil else {
            errorMessage = String(localized: "That doesn't look like a full web address.")
            return nil
        }
        // Credentials would otherwise travel in cleartext, and on the networks
        // this app is used on that is not a theoretical concern.
        guard url.scheme?.lowercased() == "https" else {
            errorMessage = String(localized: "The address must start with https:// so your password isn't sent in the clear.")
            return nil
        }
        return url
    }

    private func connect() async {
        errorMessage = nil
        guard let url = validatedServerURL() else { return }

        isVerifying = true
        defer { isVerifying = false }
        do {
            // Not necessarily `url`: a Nextcloud web address resolves to the
            // WebDAV endpoint underneath it, and that is what has to be stored.
            let resolved = try await WebDAVProvider.verify(
                serverURL: url, username: username, password: password)
            WebDAVProvider.store(serverURL: resolved, username: username, password: password)
            onConnected()
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
