import Foundation
import UIKit

/// Uploads to any WebDAV server: Nextcloud, ownCloud, Infomaniak kDrive, a
/// self-hosted box, or anything else speaking the protocol.
///
/// This is the option for people who won't put footage of a police encounter
/// into an account held by a large US company — which, for the journalists and
/// organisers this app is built for, is often the whole question. It is also
/// the only provider here that needs no app registration by anyone: the user
/// supplies a server, a username and a password, and that is the entire setup.
///
/// Two consequences worth stating plainly rather than discovering in the
/// field. WebDAV has no file identifiers, so a file's path *is* its identity —
/// which is why `fileID` values here are paths. And plain WebDAV has no
/// sharing API at all, so emergency contacts cannot be granted access
/// automatically; `supportsEmailSharing` is false and the UI says so.
final class WebDAVProvider: StorageProvider {
    let id: StorageProviderID = .webDAV

    static let serverURLKey = "webDAVServerURL"
    static let usernameKey = "webDAVUsername"

    struct WebDAVError: LocalizedError {
        let message: String
        /// The server understood us and said no. Distinguished because it is
        /// the one failure that trying a different path cannot fix.
        var isCredentialFailure = false
        var errorDescription: String? { message }
    }

    // MARK: Account

    @MainActor
    var accountLabel: String? {
        guard let user = Self.username, let host = Self.serverURL?.host() else { return nil }
        return "\(user) @ \(host)"
    }

    @MainActor
    var isSignedIn: Bool { Self.credentials != nil }

    @MainActor
    func restore() { /* Credentials live in the Keychain; nothing to refresh. */ }

    /// Unused: WebDAV is configured through `WebDAVSetupView`, which collects a
    /// server address and credentials, rather than through a web sign-in.
    @MainActor
    func signIn(presenting viewController: UIViewController) async throws {
        throw WebDAVError(message: "Set up WebDAV from the storage settings.")
    }

    @MainActor
    func signOut() {
        UserDefaults.standard.removeObject(forKey: Self.serverURLKey)
        UserDefaults.standard.removeObject(forKey: Self.usernameKey)
        KeychainService.shared.delete(.webDAVPassword)
    }

    /// There is no token exchange in WebDAV; every request carries HTTP Basic
    /// credentials. The pipeline still asks for a "token" before each batch,
    /// so this doubles as the check that the account is still configured.
    func freshAccessToken() async throws -> String {
        guard Self.credentials != nil else {
            throw WebDAVError(message: "WebDAV is not set up")
        }
        return ""
    }

    // MARK: Folders

    func ensureRootFolder(named name: String, token: String) async throws -> String {
        let path = sanitise(name)
        try await makeCollection(atPath: path)
        return path
    }

    func ensureSubfolder(named name: String, parentID: String, token: String) async throws -> String {
        let path = "\(parentID)/\(sanitise(name))"
        try await makeCollection(atPath: path)
        return path
    }

    func findFile(named name: String, parentID: String, token: String) async throws -> String? {
        let path = "\(parentID)/\(sanitise(name))"
        var request = try authorisedRequest(path: path)
        request.httpMethod = "HEAD"
        let (_, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { return nil }
        return (200...299).contains(http.statusCode) ? path : nil
    }

    func deleteFile(fileID: String, token: String) async throws {
        var request = try authorisedRequest(path: fileID)
        request.httpMethod = "DELETE"
        let (_, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse,
              (200...299).contains(http.statusCode) || http.statusCode == 404 else {
            throw WebDAVError(message: "Could not delete \(fileID)")
        }
    }

    // MARK: Transfers

    /// WebDAV uploads are a single PUT, so there is no session to negotiate:
    /// the "session URI" is simply where the file will live. Mapping it this
    /// way lets the pipeline treat WebDAV exactly like the resumable
    /// providers, and keeps the transfer on the background session.
    func beginResumableSession(fileName: String, mimeType: String, fileSize: Int,
                               folderID: String, token: String) async throws -> URL {
        try absoluteURL(forPath: "\(folderID)/\(sanitise(fileName))")
    }

    func resumableRequest(sessionURI: URL, fileSize: Int, mimeType: String,
                          token: String) -> URLRequest {
        var request = URLRequest(url: sessionURI)
        request.httpMethod = "PUT"
        request.setValue(mimeType, forHTTPHeaderField: "Content-Type")
        request.setValue("\(fileSize)", forHTTPHeaderField: "Content-Length")
        // Credentials are attached here rather than passed in, because unlike
        // the OAuth providers this one holds its own and there is no token.
        if let header = Self.basicAuthHeader {
            request.setValue(header, forHTTPHeaderField: "Authorization")
        }
        return request
    }

    /// Small files take the same single PUT; the "body file" handed back is
    /// just the original, since WebDAV needs no multipart envelope.
    func multipartUploadRequest(fileURL: URL, uploadName: String, mimeType: String,
                                folderID: String, token: String) throws -> (URLRequest, URL) {
        let destination = try absoluteURL(forPath: "\(folderID)/\(sanitise(uploadName))")
        let attributes = try? FileManager.default.attributesOfItem(atPath: fileURL.path)
        let size = (attributes?[.size] as? Int) ?? 0
        return (resumableRequest(sessionURI: destination, fileSize: size, mimeType: mimeType,
                                 token: token), fileURL)
    }

    func updateFileRequest(fileID: String, mimeType: String, token: String) -> URLRequest {
        // Overwriting is the same PUT to the same path.
        guard let url = try? absoluteURL(forPath: fileID) else {
            return URLRequest(url: URL(string: "about:blank")!)
        }
        return resumableRequest(sessionURI: url, fileSize: 0, mimeType: mimeType, token: token)
    }

    /// A successful PUT returns no body. The path was already known before the
    /// upload, so the pipeline's own record of it stands as the identifier.
    func fileID(fromResponseBody body: Data) -> String? { nil }

    // MARK: Sharing

    /// Plain WebDAV defines no way to grant another person access. Nextcloud
    /// and ownCloud each add their own non-standard API for it, so supporting
    /// this would mean detecting and special-casing particular servers rather
    /// than speaking WebDAV.
    let supportsEmailSharing = false

    func share(fileID: String, withEmail email: String, token: String) async throws {
        throw WebDAVError(message: "This server can't share files automatically")
    }

    // MARK: - Credentials

    static var serverURL: URL? {
        UserDefaults.standard.string(forKey: serverURLKey).flatMap(URL.init(string:))
    }

    static var username: String? {
        UserDefaults.standard.string(forKey: usernameKey)
    }

    static var credentials: (url: URL, user: String, password: String)? {
        guard let url = serverURL,
              let user = username,
              let password = KeychainService.shared.string(for: .webDAVPassword),
              !user.isEmpty, !password.isEmpty else { return nil }
        return (url, user, password)
    }

    static var basicAuthHeader: String? {
        guard let credentials else { return nil }
        let raw = "\(credentials.user):\(credentials.password)"
        guard let encoded = raw.data(using: .utf8)?.base64EncodedString() else { return nil }
        return "Basic \(encoded)"
    }

    /// Stores a configuration after `verify` has confirmed the server accepts
    /// it, so the app never keeps credentials it has never seen work.
    static func store(serverURL: URL, username: String, password: String) {
        UserDefaults.standard.set(serverURL.absoluteString, forKey: serverURLKey)
        UserDefaults.standard.set(username, forKey: usernameKey)
        KeychainService.shared.set(password, for: .webDAVPassword)
    }

    /// Refuses redirects while verifying.
    ///
    /// A Nextcloud web address answers PROPFIND with a 301 to its login page.
    /// Followed, that can come back 200 and a server that cannot store a
    /// single byte looks like a working one — the failure then surfaces
    /// mid-encounter instead of during setup, which is the one place this
    /// check exists to prevent.
    private final class RefusingRedirects: NSObject, URLSessionTaskDelegate {
        func urlSession(_ session: URLSession, task: URLSessionTask,
                        willPerformHTTPRedirection response: HTTPURLResponse,
                        newRequest request: URLRequest) async -> URLRequest? { nil }
    }

    /// The addresses worth trying for what the user typed, in order.
    ///
    /// People paste the address they use in a browser, because that is the one
    /// they have. On Nextcloud and ownCloud that is the web interface, not the
    /// WebDAV endpoint, and the two are different paths on the same host — so
    /// the obvious thing to enter is the one thing that cannot work. The
    /// endpoint is derived by a fixed rule, so rather than explaining the rule
    /// in a footer nobody reads, this applies it.
    static func candidateURLs(for serverURL: URL, username: String) -> [URL] {
        var candidates = [serverURL]
        // Compared as path components, not as a substring of `path`: that
        // property drops a trailing slash, so a server already given as
        // ".../dav/" read as ".../dav" and slipped through, earning a pointless
        // second probe at ".../dav/remote.php/dav/files/...".
        let components = Set(serverURL.pathComponents.map { $0.lowercased() })
        guard components.isDisjoint(with: ["remote.php", "dav", "webdav"]),
              !username.isEmpty else { return candidates }
        var derived = serverURL
        for component in ["remote.php", "dav", "files", username] {
            derived.append(path: component)
        }
        candidates.append(derived)
        return candidates
    }

    /// Checks a server and credentials before they are saved, so a typo
    /// surfaces during setup rather than as silently failing uploads during an
    /// encounter.
    ///
    /// Returns the address that actually answered, which may not be the one
    /// that was typed — see `candidateURLs`. The caller stores that one.
    @discardableResult
    static func verify(serverURL: URL, username: String, password: String) async throws -> URL {
        var lastError = WebDAVError(message: "No response from the server")
        for candidate in candidateURLs(for: serverURL, username: username) {
            do {
                try await probe(candidate, username: username, password: password)
                return candidate
            } catch let error as WebDAVError {
                // Rejected credentials are rejected at every path on the host,
                // so there is nothing to gain from trying the next one, and
                // "isn't a WebDAV folder" would be the wrong thing to report.
                if error.isCredentialFailure { throw error }
                lastError = error
            }
        }
        throw lastError
    }

    private static func probe(_ url: URL, username: String, password: String) async throws {
        var request = URLRequest(url: url)
        request.httpMethod = "PROPFIND"
        request.setValue("0", forHTTPHeaderField: "Depth")
        request.setValue("application/xml", forHTTPHeaderField: "Content-Type")
        let raw = "\(username):\(password)"
        if let encoded = raw.data(using: .utf8)?.base64EncodedString() {
            request.setValue("Basic \(encoded)", forHTTPHeaderField: "Authorization")
        }
        request.timeoutInterval = 20

        let (_, response) = try await URLSession.shared.data(
            for: request, delegate: RefusingRedirects())
        guard let http = response as? HTTPURLResponse else {
            throw WebDAVError(message: "No response from the server")
        }
        switch http.statusCode {
        case 200...299:
            return
        case 401, 403:
            throw WebDAVError(
                message: "The server rejected that username or password. On Nextcloud, "
                    + "use an app password from Settings → Security, not your account password.",
                isCredentialFailure: true)
        case 300...399:
            throw WebDAVError(message: "That address redirects elsewhere, so it isn't the "
                + "WebDAV folder. On Nextcloud it is under Settings → WebDAV.")
        default:
            throw WebDAVError(message: "That address isn't a WebDAV folder (the server "
                + "returned \(http.statusCode)). On Nextcloud it is under Settings → WebDAV.")
        }
    }

    // MARK: - Requests

    private func authorisedRequest(path: String) throws -> URLRequest {
        var request = URLRequest(url: try absoluteURL(forPath: path))
        if let header = Self.basicAuthHeader {
            request.setValue(header, forHTTPHeaderField: "Authorization")
        }
        request.timeoutInterval = 30
        return request
    }

    private func absoluteURL(forPath path: String) throws -> URL {
        guard let base = Self.serverURL else {
            throw WebDAVError(message: "WebDAV is not set up")
        }
        var url = base
        for component in path.split(separator: "/") {
            url.append(path: String(component))
        }
        return url
    }

    /// MKCOL, treating "already exists" as success — the pipeline calls this
    /// on every chunk and a folder existing is the normal case, not an error.
    ///
    /// Servers disagree about how to say "already exists". RFC 4918 specifies
    /// 405, and that is what this used to accept. Apache's mod_dav instead
    /// reports the underlying `EEXIST` as **403**:
    ///
    ///     mod_dav.c(619): (17)File exists: Unable to create collection. [403]
    ///
    /// which was read as a hard failure, so on those servers the first chunk
    /// created the folder and every chunk after it threw. Others use 409, and
    /// some redirect.
    ///
    /// Rather than keep collecting status codes, an unexpected one now asks
    /// the server the question that actually matters: is the collection there?
    /// If it is, the goal is met however the server phrased it. If it is not,
    /// this was a real failure and still throws.
    private func makeCollection(atPath path: String) async throws {
        var request = try authorisedRequest(path: path)
        request.httpMethod = "MKCOL"
        let (_, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw WebDAVError(message: "No response creating \(path)")
        }

        // 201 created, 405 already exists per spec.
        if (200...299).contains(http.statusCode) || http.statusCode == 405 { return }

        if try await collectionExists(atPath: path) { return }

        throw WebDAVError(message: "Could not create folder (\(http.statusCode))")
    }

    /// PROPFIND with `Depth: 0`, which is the WebDAV way to ask whether a
    /// single resource exists. HEAD would be simpler but is unreliable here:
    /// plenty of servers answer 405 for HEAD on a collection, which is
    /// indistinguishable from the collection being absent.
    private func collectionExists(atPath path: String) async throws -> Bool {
        var request = try authorisedRequest(path: path)
        request.httpMethod = "PROPFIND"
        request.setValue("0", forHTTPHeaderField: "Depth")
        request.setValue("application/xml; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data("""
        <?xml version="1.0" encoding="utf-8"?>
        <D:propfind xmlns:D="DAV:"><D:prop><D:resourcetype/></D:prop></D:propfind>
        """.utf8)

        let (_, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { return false }
        // 207 Multi-Status is the expected answer; accept any 2xx so a server
        // that answers 200 to a Depth-0 PROPFIND is not called a failure.
        return (200...299).contains(http.statusCode)
    }

    /// Strips characters that would change the meaning of a path. Folder names
    /// are user-supplied, and a stray slash would silently write somewhere
    /// other than intended.
    private func sanitise(_ component: String) -> String {
        component
            .replacingOccurrences(of: "/", with: "-")
            .replacingOccurrences(of: "\\", with: "-")
            .addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? component
    }
}
