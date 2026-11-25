import Foundation
import UIKit

/// Dropbox as an upload destination.
///
/// Registered with **App folder** access, Dropbox's equivalent of Google's
/// `drive.file`: everything the app reads or writes is confined to its own
/// directory, and the rest of the user's Dropbox is invisible to it.
///
/// Unlike Google, Dropbox has no resumable-session negotiation for files this
/// size — a chunk goes up in one request. The protocol's resumable shape is
/// honoured anyway by treating the destination path as the "session", which
/// keeps every transfer on the background URLSession where it belongs.
final class DropboxProvider: StorageProvider {
    let id: StorageProviderID = .dropbox

    private static let uploadEndpoint = URL(string: "https://content.dropboxapi.com/2/files/upload")!
    private static let rpcBase = "https://api.dropboxapi.com/2"

    struct DropboxError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    // MARK: Account

    @MainActor
    var accountLabel: String? { DropboxAuthService.shared.accountEmail }
    @MainActor
    var isSignedIn: Bool { DropboxAuthService.shared.isSignedIn }
    @MainActor
    func restore() { DropboxAuthService.shared.restore() }
    @MainActor
    func signOut() { DropboxAuthService.shared.signOut() }

    @MainActor
    func signIn(presenting viewController: UIViewController) async throws {
        // Dropbox presents its own web sheet, so the caller's view controller
        // isn't needed.
        try await DropboxAuthService.shared.signIn()
    }

    func freshAccessToken() async throws -> String {
        try await DropboxAuthService.shared.freshAccessToken()
    }

    // MARK: Folders

    /// Dropbox paths are the identity of a file, and always absolute from the
    /// app folder's root.
    func ensureRootFolder(named name: String, token: String) async throws -> String {
        let path = "/\(sanitise(name))"
        try await createFolder(path: path, token: token)
        return path
    }

    func ensureSubfolder(named name: String, parentID: String, token: String) async throws -> String {
        let path = "\(parentID)/\(sanitise(name))"
        try await createFolder(path: path, token: token)
        return path
    }

    func findFile(named name: String, parentID: String, token: String) async throws -> String? {
        let path = "\(parentID)/\(sanitise(name))"
        do {
            _ = try await rpc("files/get_metadata", body: ["path": path], token: token)
            return path
        } catch {
            // Absent is the normal answer here, not a failure: this is what
            // makes retrying an upload idempotent.
            return nil
        }
    }

    func deleteFile(fileID: String, token: String) async throws {
        _ = try await rpc("files/delete_v2", body: ["path": fileID], token: token)
    }

    // MARK: Transfers

    /// No session to negotiate: the returned URL is the upload endpoint, with
    /// the destination carried in the fragment so `resumableRequest` can read
    /// it back without extra plumbing. Chunks are far below Dropbox's 150 MB
    /// single-request ceiling, and ProRes — the only thing that would exceed
    /// it — never uploads at all.
    func beginResumableSession(fileName: String, mimeType: String, fileSize: Int,
                               folderID: String, token: String) async throws -> URL {
        let path = "\(folderID)/\(sanitise(fileName))"
        var components = URLComponents(url: Self.uploadEndpoint, resolvingAgainstBaseURL: false)!
        components.fragment = path
        guard let url = components.url else {
            throw DropboxError(message: "Could not build an upload URL")
        }
        return url
    }

    func resumableRequest(sessionURI: URL, fileSize: Int, mimeType: String,
                          token: String) -> URLRequest {
        let path = sessionURI.fragment ?? ""
        var request = URLRequest(url: Self.uploadEndpoint)
        request.httpMethod = "POST"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        request.setValue(Self.uploadArgument(path: path), forHTTPHeaderField: "Dropbox-API-Arg")
        return request
    }

    func multipartUploadRequest(fileURL: URL, uploadName: String, mimeType: String,
                                folderID: String, token: String) throws -> (URLRequest, URL) {
        let path = "\(folderID)/\(sanitise(uploadName))"
        var request = URLRequest(url: Self.uploadEndpoint)
        request.httpMethod = "POST"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        request.setValue(Self.uploadArgument(path: path), forHTTPHeaderField: "Dropbox-API-Arg")
        // Dropbox takes the raw bytes, so the original file is the body — no
        // multipart envelope has to be built.
        return (request, fileURL)
    }

    func updateFileRequest(fileID: String, mimeType: String, token: String) -> URLRequest {
        var request = URLRequest(url: Self.uploadEndpoint)
        request.httpMethod = "POST"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        request.setValue(Self.uploadArgument(path: fileID), forHTTPHeaderField: "Dropbox-API-Arg")
        return request
    }

    func fileID(fromResponseBody body: Data) -> String? {
        guard let json = try? JSONSerialization.jsonObject(with: body) as? [String: Any] else {
            return nil
        }
        // path_lower is what every other endpoint here accepts, so it is more
        // useful downstream than Dropbox's opaque id.
        return json["path_lower"] as? String ?? json["id"] as? String
    }

    /// `"mode": "overwrite"` matters: a retried upload must land on the same
    /// path rather than becoming "file (1)", or a duplicate would appear in
    /// the folder every time a flaky connection forced a retry.
    private static func uploadArgument(path: String) -> String {
        let argument: [String: Any] = [
            "path": path,
            "mode": "overwrite",
            "autorename": false,
            "mute": true,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: argument),
              let json = String(data: data, encoding: .utf8) else { return "{}" }
        return json
    }

    // MARK: Sharing

    let supportsEmailSharing = true

    /// Two steps, because Dropbox will only add members to a folder that has
    /// been turned into a shared folder first. Sharing an already-shared
    /// folder returns an error that means "already done", which is success
    /// here.
    func share(fileID: String, withEmail email: String, token: String) async throws {
        var sharedFolderID: String?
        if let result = try? await rpc("sharing/share_folder",
                                       body: ["path": fileID, "force_async": false],
                                       token: token) {
            sharedFolderID = (result["shared_folder_id"] as? String)
                ?? ((result["complete"] as? [String: Any])?["shared_folder_id"] as? String)
        }
        if sharedFolderID == nil,
           let metadata = try? await rpc("sharing/get_folder_metadata",
                                         body: ["shared_folder_id": fileID], token: token) {
            sharedFolderID = metadata["shared_folder_id"] as? String
        }
        guard let sharedFolderID else {
            throw DropboxError(message: "Could not share that folder")
        }

        _ = try await rpc("sharing/add_folder_member", body: [
            "shared_folder_id": sharedFolderID,
            "members": [["member": ["\(".tag")": "email", "email": email],
                         "access_level": ["\(".tag")": "viewer"]]],
            "quiet": false,
        ], token: token)
    }

    // MARK: - Plumbing

    private func createFolder(path: String, token: String) async throws {
        do {
            _ = try await rpc("files/create_folder_v2", body: ["path": path], token: token)
        } catch let error as DropboxError {
            // Existing is the normal case: this runs before every batch.
            guard error.message.contains("conflict") else { throw error }
        }
    }

    @discardableResult
    private func rpc(_ path: String, body: [String: Any], token: String) async throws -> [String: Any] {
        guard let url = URL(string: "\(Self.rpcBase)/\(path)") else {
            throw DropboxError(message: "Bad Dropbox endpoint")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        request.timeoutInterval = 30

        let (data, response) = try await URLSession.shared.data(for: request)
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            let summary = (json["error_summary"] as? String)
                ?? String(data: data, encoding: .utf8)
                ?? "unknown"
            throw DropboxError(message: summary)
        }
        return json
    }

    /// Dropbox rejects a path containing its reserved characters outright, so
    /// a folder name typed by the user is cleaned before it becomes one.
    private func sanitise(_ component: String) -> String {
        component
            .replacingOccurrences(of: "/", with: "-")
            .replacingOccurrences(of: "\\", with: "-")
            .replacingOccurrences(of: ":", with: "-")
            .replacingOccurrences(of: "?", with: "")
            .replacingOccurrences(of: "*", with: "")
            .replacingOccurrences(of: "\"", with: "'")
            .replacingOccurrences(of: "<", with: "(")
            .replacingOccurrences(of: ">", with: ")")
            .replacingOccurrences(of: "|", with: "-")
    }
}
