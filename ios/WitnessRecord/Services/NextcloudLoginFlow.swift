import Foundation
import AuthenticationServices
import UIKit

/// Signs in to Nextcloud the way Nextcloud's own clients do, so nobody has to
/// go and make an app password by hand.
///
/// The friction this removes is real: Nextcloud rejects an account password for
/// WebDAV whenever two-factor authentication is on, which is the configuration
/// anyone running their own server for this kind of footage is likely to have.
/// The fix has been "create an app password first", which is a detour through a
/// settings page most people have never opened, on a server they set up once.
///
/// Login Flow v2 removes the detour without weakening anything. The app asks
/// the server to start a flow, opens the server's *own* login page, and polls
/// until the server hands back a device-specific app password. The account
/// password is typed into Nextcloud's page and never reaches this app — which
/// is better than what it replaces, not merely easier: there is no moment at
/// which Witness holds a credential that could unlock the whole account, and
/// the user can revoke this one device from Nextcloud without changing
/// anything else.
///
/// https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/
@MainActor
final class NextcloudLoginFlow: NSObject {
    static let shared = NextcloudLoginFlow()
    private override init() {}

    struct FlowError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    struct Credentials {
        let server: URL
        let loginName: String
        let appPassword: String
    }

    /// Named so the credential shows up as "Witness" in Nextcloud's Devices &
    /// sessions list, where it can be recognised and revoked on its own.
    private static let userAgent = "Witness"
    /// The server keeps the flow open for 20 minutes. This gives up sooner:
    /// past a couple of minutes the browser has been abandoned, and a poll
    /// running on in the background is a surprise rather than a convenience.
    private static let pollTimeout: TimeInterval = 150
    private static let pollInterval: Duration = .seconds(1)

    private var session: ASWebAuthenticationSession?

    func signIn(serverURL: URL) async throws -> Credentials {
        let flow = try await begin(at: serverURL)
        present(flow.login)
        // Dismisses the browser however this ends — the server shows its own
        // "account connected" page, and leaving it up after we already have
        // the credential reads as the sign-in having not worked.
        defer { session?.cancel(); session = nil }
        return try await awaitCredentials(token: flow.token, endpoint: flow.endpoint)
    }

    // MARK: - Steps

    private struct Flow {
        let login: URL
        let token: String
        let endpoint: URL
    }

    private func begin(at serverURL: URL) async throws -> Flow {
        var url = serverURL
        url.append(path: "index.php")
        url.append(path: "login")
        url.append(path: "v2")

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 20

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            throw FlowError(message: "That server didn't offer a Nextcloud sign-in "
                + "(it returned \(code)). Check the address, or enter a username and "
                + "app password instead.")
        }
        struct Payload: Decodable {
            struct Poll: Decodable { let token: String; let endpoint: String }
            let poll: Poll
            let login: String
        }
        guard let payload = try? JSONDecoder().decode(Payload.self, from: data),
              let login = URL(string: payload.login),
              let endpoint = URL(string: payload.poll.endpoint) else {
            throw FlowError(message: "That server answered, but not like a Nextcloud server.")
        }
        return Flow(login: login, token: payload.poll.token, endpoint: endpoint)
    }

    private func present(_ login: URL) {
        // The callback scheme is never reached: Login Flow v2 finishes on the
        // server and is collected by polling, not by a redirect back here. The
        // scheme is required by the initialiser, and the browser is dismissed
        // from `signIn` once the poll succeeds.
        let session = ASWebAuthenticationSession(
            url: login, callbackURLScheme: "witnessrecord") { _, _ in }
        session.presentationContextProvider = self
        // A fresh session, so signing in to a different account is not blocked
        // by a cookie from the last one.
        session.prefersEphemeralWebBrowserSession = true
        self.session = session
        session.start()
    }

    private func awaitCredentials(token: String, endpoint: URL) async throws -> Credentials {
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        request.httpBody = Data("token=\(token)".utf8)
        request.timeoutInterval = 20

        let deadline = Date().addingTimeInterval(Self.pollTimeout)
        while Date() < deadline {
            try Task.checkCancellation()
            let (data, response) = try await URLSession.shared.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            if status == 200 {
                struct Payload: Decodable {
                    let server: String
                    let loginName: String
                    let appPassword: String
                }
                guard let payload = try? JSONDecoder().decode(Payload.self, from: data),
                      let server = URL(string: payload.server) else {
                    throw FlowError(message: "The server confirmed the sign-in but sent "
                        + "back something unreadable.")
                }
                return Credentials(server: server, loginName: payload.loginName,
                                   appPassword: payload.appPassword)
            }
            // 404 is the documented "not finished yet"; anything else is the
            // flow having expired or been refused, and polling on would only
            // delay saying so.
            guard status == 404 else {
                throw FlowError(message: "The sign-in was refused (\(status)). Try again.")
            }
            try await Task.sleep(for: Self.pollInterval)
        }
        throw FlowError(message: "The sign-in wasn't completed in time. Try again.")
    }
}

extension NextcloudLoginFlow: ASWebAuthenticationPresentationContextProviding {
    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated {
            UIApplication.shared.connectedScenes
                .compactMap { ($0 as? UIWindowScene)?.keyWindow }
                .first ?? ASPresentationAnchor()
        }
    }
}
