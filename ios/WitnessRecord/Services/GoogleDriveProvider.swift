import Foundation
import UIKit

/// Google Drive behind the common `StorageProvider` interface.
///
/// Purely an adapter: the request-building and auth logic still live in
/// `GoogleDriveClient` and `GoogleAuthService`, which were already correct and
/// well tested in the field. Nothing about Google's behaviour changes by being
/// reached through the protocol.
final class GoogleDriveProvider: StorageProvider {
    let id: StorageProviderID = .googleDrive

    @MainActor
    var accountLabel: String? { GoogleAuthService.shared.userEmail }
    @MainActor
    var isSignedIn: Bool { GoogleAuthService.shared.userEmail != nil }

    @MainActor
    func restore() { GoogleAuthService.shared.restore() }

    @MainActor
    func signIn(presenting viewController: UIViewController) async throws {
        try await GoogleAuthService.shared.signIn(presenting: viewController)
    }

    @MainActor
    func signOut() { GoogleAuthService.shared.signOut() }

    func freshAccessToken() async throws -> String {
        try await GoogleAuthService.shared.freshAccessToken()
    }

    // MARK: Folders

    func availableBytes(token: String) async throws -> Int64? {
        try await GoogleDriveClient.availableBytes(accessToken: token)
    }

    func ensureRootFolder(named name: String, token: String) async throws -> String {
        try await GoogleDriveClient.ensureFolder(named: name, accessToken: token)
    }

    func ensureSubfolder(named name: String, parentID: String, token: String) async throws -> String {
        try await GoogleDriveClient.ensureSubfolder(named: name, parentID: parentID, accessToken: token)
    }

    func findFile(named name: String, parentID: String, token: String) async throws -> String? {
        try await GoogleDriveClient.findFile(named: name, parentID: parentID, accessToken: token)
    }

    func deleteFile(fileID: String, token: String) async throws {
        try await GoogleDriveClient.deleteFile(fileID: fileID, accessToken: token)
    }

    // MARK: Transfers

    func beginResumableSession(fileName: String, mimeType: String, fileSize: Int,
                               folderID: String, token: String) async throws -> URL {
        try await GoogleDriveClient.beginResumableSession(
            fileName: fileName, mimeType: mimeType, fileSize: fileSize,
            folderID: folderID, accessToken: token)
    }

    /// `token` is unused: a Google resumable session URI already carries its
    /// own authorization, which is why the protocol has to pass one at all —
    /// every other backend needs it here.
    func resumableRequest(sessionURI: URL, fileSize: Int, mimeType: String,
                          token: String) -> URLRequest {
        GoogleDriveClient.resumablePUTRequest(
            sessionURI: sessionURI, fileSize: fileSize, mimeType: mimeType)
    }

    func multipartUploadRequest(fileURL: URL, uploadName: String, mimeType: String,
                                folderID: String, token: String) throws -> (URLRequest, URL) {
        try GoogleDriveClient.multipartUploadRequest(
            fileURL: fileURL, uploadName: uploadName, mimeType: mimeType,
            folderID: folderID, accessToken: token)
    }

    func updateFileRequest(fileID: String, mimeType: String, token: String) -> URLRequest {
        GoogleDriveClient.updateFileRequest(
            fileID: fileID, mimeType: mimeType, accessToken: token)
    }

    func fileID(fromResponseBody body: Data) -> String? {
        guard let json = try? JSONSerialization.jsonObject(with: body) as? [String: Any] else {
            return nil
        }
        return json["id"] as? String
    }

    // MARK: Sharing

    let supportsEmailSharing = true

    func share(fileID: String, withEmail email: String, token: String) async throws {
        try await GoogleDriveClient.shareFile(
            fileID: fileID, withEmail: email, accessToken: token)
    }
}
