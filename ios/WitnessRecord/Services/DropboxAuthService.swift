import Foundation
import AuthenticationServices
import CryptoKit

/// Dropbox sign-in over OAuth 2 with PKCE.
///
/// PKCE rather than a client secret because this is a public client: the app
/// key below ships inside the binary and anyone can read it out. That is
/// expected and safe — the key alone authorises nothing. Each sign-in mints a
/// random verifier on the device and sends only its hash to Dropbox, so an
/// intercepted authorisation code is worthless without the verifier, which
/// never leaves the phone. An app *secret* would be the opposite: a real
/// credential that could not be protected here, which is why none is used.
@MainActor
final class DropboxAuthService: NSObject, ObservableObject {
    static let shared = DropboxAuthService()

    /// Read from Info.plist so the value lives with the other client
    /// identifiers rather than being buried in code.
    static var appKey: String {
        Bundle.main.object(forInfoDictionaryKey: "DropboxAppKey") as? String ?? ""
    }

    private static let redirectURI = "witnessrecord://dropbox-auth"
    private static let authorizeEndpoint = "https://www.dropbox.com/oauth2/authorize"
    private static let tokenEndpoint = URL(string: "https://api.dropboxapi.com/oauth2/token")!
    private static let accountEndpoint = URL(string: "https://api.dropboxapi.com/2/users/get_current_account")!

    /// Least privilege, matching Google's `drive.file`: with App-folder access
    /// Dropbox confines the app to its own directory, so these grant nothing
    /// outside it.
    /// One scope per endpoint the app actually calls, and nothing beyond that.
    /// Kept in this order so the list can be checked against the code:
    ///
    ///   account_info.read   users/get_current_account, to show which account
    ///                       is connected in Settings.
    ///   files.content.write files/upload, files/create_folder_v2, and
    ///                       files/delete_v2 when chunks are superseded by the
    ///                       consolidated recording.
    ///   files.metadata.read files/get_metadata, which is how an upload retry
    ///                       checks whether the file already arrived. Without
    ///                       it a retry after a dropped connection duplicates.
    ///   sharing.read        sharing/get_folder_metadata, the fallback path for
    ///                       resolving a folder that is already shared.
    ///   sharing.write       sharing/share_folder and sharing/add_folder_member,
    ///                       which is how an emergency contact is granted access.
    ///
    /// files.content.read is deliberately absent: the app never downloads. It
    /// was requested here previously and never used, which is the kind of
    /// unjustifiable scope that gets a production request declined.
    private static let scopes = [
        "account_info.read",
        "files.content.write",
        "files.metadata.read",
        "sharing.read",
        "sharing.write",
    ]

    struct DropboxError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    @Published private(set) var accountEmail: String?

    private var accessToken: String?
    private var accessTokenExpiry: Date?
    private var session: ASWebAuthenticationSession?

    private override init() {
        super.init()
    }

    var isSignedIn: Bool { refreshToken != nil }

    private var refreshToken: String? {
        KeychainService.shared.string(for: .dropboxRefreshToken)
    }

    func restore() {
        accountEmail = UserDefaults.standard.string(forKey: "dropboxAccountEmail")
    }

    func signOut() {
        KeychainService.shared.delete(.dropboxRefreshToken)
        UserDefaults.standard.removeObject(forKey: "dropboxAccountEmail")
        accessToken = nil
        accessTokenExpiry = nil
        accountEmail = nil
    }

    // MARK: - Sign in

    func signIn() async throws {
        guard !Self.appKey.isEmpty else {
            throw DropboxError(message: "Dropbox isn't configured in this build")
        }
        let verifier = Self.makeCodeVerifier()
        let challenge = Self.codeChallenge(for: verifier)

        var components = URLComponents(string: Self.authorizeEndpoint)!
        components.queryItems = [
            .init(name: "client_id", value: Self.appKey),
            .init(name: "response_type", value: "code"),
            .init(name: "redirect_uri", value: Self.redirectURI),
            .init(name: "code_challenge", value: challenge),
            .init(name: "code_challenge_method", value: "S256"),
            // Without this Dropbox issues only a short-lived token, and the
            // app would silently stop uploading a few hours into the day.
            .init(name: "token_access_type", value: "offline"),
            .init(name: "scope", value: Self.scopes.joined(separator: " ")),
        ]

        let code = try await authorize(url: components.url!)
        try await exchange(code: code, verifier: verifier)
        try? await fetchAccountEmail()
    }

    private func authorize(url: URL) async throws -> String {
        try await withCheckedThrowingContinuation { continuation in
            let session = ASWebAuthenticationSession(
                url: url, callbackURLScheme: "witnessrecord") { callback, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                guard let callback,
                      let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems,
                      let code = items.first(where: { $0.name == "code" })?.value else {
                    continuation.resume(throwing: DropboxError(message: "Dropbox didn't return an authorisation code"))
                    return
                }
                continuation.resume(returning: code)
            }
            session.presentationContextProvider = self
            // A fresh session each time, so signing in with a different
            // account isn't blocked by a cookie from the last one.
            session.prefersEphemeralWebBrowserSession = true
            self.session = session
            session.start()
        }
    }

    private func exchange(code: String, verifier: String) async throws {
        let body = [
            "code": code,
            "grant_type": "authorization_code",
            "client_id": Self.appKey,
            "redirect_uri": Self.redirectURI,
            "code_verifier": verifier,
        ]
        let json = try await postForm(body)
        guard let refresh = json["refresh_token"] as? String else {
            throw DropboxError(message: "Dropbox didn't return a refresh token")
        }
        KeychainService.shared.set(refresh, for: .dropboxRefreshToken)
        cache(accessToken: json)
    }

    // MARK: - Tokens

    func freshAccessToken() async throws -> String {
        if let accessToken, let expiry = accessTokenExpiry, expiry > Date().addingTimeInterval(60) {
            return accessToken
        }
        guard let refreshToken else {
            throw DropboxError(message: "Not signed in to Dropbox")
        }
        let json = try await postForm([
            "grant_type": "refresh_token",
            "refresh_token": refreshToken,
            "client_id": Self.appKey,
        ])
        cache(accessToken: json)
        guard let token = accessToken else {
            throw DropboxError(message: "Dropbox didn't return an access token")
        }
        return token
    }

    private func cache(accessToken json: [String: Any]) {
        accessToken = json["access_token"] as? String
        let lifetime = (json["expires_in"] as? Double) ?? 14_400
        accessTokenExpiry = Date().addingTimeInterval(lifetime)
    }

    private func postForm(_ fields: [String: String]) async throws -> [String: Any] {
        var request = URLRequest(url: Self.tokenEndpoint)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = fields
            .map { "\($0.key)=\($0.value.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? $0.value)" }
            .joined(separator: "&")
            .data(using: .utf8)

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw DropboxError(message: "Dropbox rejected the sign-in request")
        }
        return json
    }

    private func fetchAccountEmail() async throws {
        let token = try await freshAccessToken()
        var request = URLRequest(url: Self.accountEndpoint)
        request.httpMethod = "POST"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, _) = try await URLSession.shared.data(for: request)
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let email = json["email"] as? String else { return }
        accountEmail = email
        UserDefaults.standard.set(email, forKey: "dropboxAccountEmail")
    }

    // MARK: - PKCE

    private static func makeCodeVerifier() -> String {
        var bytes = [UInt8](repeating: 0, count: 64)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes).base64URLEncoded()
    }

    private static func codeChallenge(for verifier: String) -> String {
        let digest = SHA256.hash(data: Data(verifier.utf8))
        return Data(digest).base64URLEncoded()
    }
}

extension DropboxAuthService: ASWebAuthenticationPresentationContextProviding {
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.keyWindow }
            .first ?? ASPresentationAnchor()
    }
}

private extension Data {
    /// base64url per RFC 7636: standard base64 would be rejected, since '+',
    /// '/' and '=' are not URL-safe.
    func base64URLEncoded() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
