import Foundation
import UIKit
import SwiftUI

/// Which cloud account footage is uploaded to.
///
/// Kept as a small enum rather than free-form strings so the stored choice
/// can't drift out of sync with what the app can actually talk to.
enum StorageProviderID: String, CaseIterable, Identifiable, Codable {
    case googleDrive
    case dropbox
    case webDAV
    /// Listed for transparency, not because it works: Proton has no public
    /// API or WebDAV access, so there is no way for Witness to connect to it.
    /// See `StorageProviders.isUnsupported`.
    case proton

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .googleDrive: return "Google Drive"
        case .dropbox: return "Dropbox"
        case .webDAV: return String(localized: "Nextcloud or WebDAV")
        case .proton: return "Proton Drive"
        }
    }

    /// Shown under the name in the picker: what choosing this actually means,
    /// not marketing copy.
    var subtitle: LocalizedStringKey {
        switch self {
        case .googleDrive: return "Sign in with Google"
        case .dropbox: return "Sign in with Dropbox"
        case .webDAV: return "Your own server, or any WebDAV host"
        case .proton: return "Not supported — tap to find out why"
        }
    }

    /// Asset name for the provider's mark, where one is bundled.
    var iconAssetName: String? {
        switch self {
        case .googleDrive: return "GoogleDriveIcon"
        case .dropbox: return "DropboxIcon"
        case .proton: return "ProtonDriveIcon"
        default: return nil
        }
    }

    /// SF Symbol used when no brand mark is bundled.
    var fallbackSymbol: String {
        switch self {
        case .dropbox: return "shippingbox.fill"
        case .webDAV: return "server.rack"
        case .googleDrive: return "cloud.fill"
        case .proton: return "lock.shield"
        }
    }
}

/// Everything the upload pipeline needs from a storage backend.
///
/// Modelled on what the pipeline actually does rather than on a tidy
/// "upload(file:)" abstraction, because the pipeline hands its transfers to a
/// *background* URLSession: it needs providers to hand back `URLRequest`s it
/// can enqueue there, not to perform the transfers themselves. That is what
/// keeps uploads alive when the app is suspended, which is the whole reason
/// footage survives a phone being taken.
protocol StorageProvider: AnyObject {
    var id: StorageProviderID { get }

    // MARK: Account

    // Main-actor isolated: these drive UI and, for providers backed by an SDK
    // (Google's, today), the SDK itself demands the main actor. Everything
    // below this section stays off it, since the upload pipeline runs on its
    // own queue.

    /// Identifies the signed-in account to the user (usually an email).
    @MainActor var accountLabel: String? { get }
    @MainActor var isSignedIn: Bool { get }

    @MainActor func restore()
    @MainActor func signIn(presenting viewController: UIViewController) async throws
    @MainActor func signOut()

    /// A token valid right now, refreshed if needed.
    func freshAccessToken() async throws -> String

    // MARK: Folders

    /// Bytes still free in the account, or nil where the backend will not say.
    ///
    /// Checked so that somebody is told their storage is full *before* they
    /// record into it rather than after. Video fills an account quickly — half
    /// an hour is several gigabytes — and a full account blocks uploads
    /// completely, including creating the folder to upload into. A journalist
    /// recorded half an hour into a full Drive and found out when she went
    /// looking for the footage.
    func availableBytes(token: String) async throws -> Int64?

    func ensureRootFolder(named name: String, token: String) async throws -> String
    func ensureSubfolder(named name: String, parentID: String, token: String) async throws -> String
    func findFile(named name: String, parentID: String, token: String) async throws -> String?
    func deleteFile(fileID: String, token: String) async throws

    // MARK: Transfers

    /// Starts a resumable upload and returns the URI to PUT the body to.
    func beginResumableSession(fileName: String, mimeType: String, fileSize: Int,
                               folderID: String, token: String) async throws -> URL
    func resumableRequest(sessionURI: URL, fileSize: Int, mimeType: String,
                          token: String) -> URLRequest
    /// Returns the request plus a temporary file holding the encoded body, for
    /// small files sent in one shot.
    func multipartUploadRequest(fileURL: URL, uploadName: String, mimeType: String,
                                folderID: String, token: String) throws -> (URLRequest, URL)
    func updateFileRequest(fileID: String, mimeType: String, token: String) -> URLRequest

    /// Pulls the created file's identifier out of a completed upload's body.
    func fileID(fromResponseBody body: Data) -> String?

    // MARK: Sharing

    /// Whether this backend can grant a named person access programmatically.
    /// False for stores with no such API, so the UI can say plainly that
    /// emergency contacts won't be notified rather than failing silently.
    var supportsEmailSharing: Bool { get }
    func share(fileID: String, withEmail email: String, token: String) async throws
}

/// The app's single source of truth for which backend is in use.
@MainActor
final class StorageProviders: ObservableObject {
    static let shared = StorageProviders()

    nonisolated static let selectedProviderKey = "storageProviderID"

    @Published private(set) var selected: StorageProviderID

    /// Room left in the connected account, or nil where it is unknown —
    /// unlimited storage, a backend that will not say, or not asked yet.
    ///
    /// Checked so a full account is something you are told before recording
    /// into it, not something you discover afterwards. A full account does not
    /// merely slow uploads down: it blocks them outright, down to refusing to
    /// create the folder, so a recording made into one goes nowhere at all.
    @Published private(set) var availableBytes: Int64?

    /// How long the current capture settings could record before filling the
    /// account. Nil when the room left is unknown.
    var recordableSeconds: Double? {
        guard let availableBytes else { return nil }
        let bytesPerSecond = AppSettings.videoCodec.estimatedBitRate(
            resolution: AppSettings.videoResolution,
            frameRate: AppSettings.videoFrameRate) / 8.0
        guard bytesPerSecond > 0 else { return nil }
        return Double(availableBytes) / bytesPerSecond
    }

    /// Asks the connected account how much room is left. Quiet on failure:
    /// this is a courtesy, and an account that will not answer should not
    /// produce an error in front of somebody about to record.
    func refreshAvailableSpace() {
        Task { @MainActor in
            guard let provider = current, provider.isSignedIn else {
                availableBytes = nil
                return
            }
            guard let token = try? await provider.freshAccessToken(),
                  let bytes = try? await provider.availableBytes(token: token) else {
                availableBytes = nil
                return
            }
            availableBytes = bytes
        }
    }

    private var providers: [StorageProviderID: StorageProvider] = [:]

    private init() {
        let stored = UserDefaults.standard.string(forKey: Self.selectedProviderKey)
        selected = stored.flatMap(StorageProviderID.init(rawValue:)) ?? .googleDrive
        providers[.googleDrive] = GoogleDriveProvider()
        providers[.dropbox] = DropboxProvider()
        providers[.webDAV] = WebDAVProvider()
    }

    /// Nil for a provider that isn't implemented yet, so callers must decide
    /// what to do rather than getting a stub that silently does nothing.
    func provider(_ id: StorageProviderID) -> StorageProvider? {
        providers[id]
    }

    var current: StorageProvider? { providers[selected] }

    /// Off-actor-safe access to the active provider, for the upload pipeline,
    /// which runs on its own serial queue rather than the main actor. One
    /// cheap hop onto the actor to read a dictionary, not a real dependency.
    nonisolated func currentProvider() async -> StorageProvider? {
        await MainActor.run { self.current }
    }

    var isImplemented: (StorageProviderID) -> Bool {
        { [providers] id in providers[id] != nil }
    }

    /// Whether choosing this provider leads to a setup form rather than a
    /// hosted sign-in page.
    func usesManualSetup(_ id: StorageProviderID) -> Bool { id == .webDAV }

    /// True for a backend that's listed for transparency but genuinely can't
    /// be connected — not merely unimplemented (like an SDK we haven't
    /// written yet), but structurally impossible with what the service
    /// exposes. Proton Drive is the case today: every file is encrypted
    /// client-side with keys derived from the account before it ever reaches
    /// their servers, and they publish no API or WebDAV access an outside
    /// app could use — people can upload through Proton's own web app or
    /// apps, just never through a third-party one. Tapping the row explains
    /// this instead of attempting a connection that would only fail, likely
    /// at the worst possible moment.
    func isUnsupported(_ id: StorageProviderID) -> Bool { id == .proton }

    static var unsupportedExplanationTitle: String { String(localized: "Proton Drive isn't supported") }
    static var unsupportedExplanationMessage: String { String(localized: "Proton encrypts every file with keys tied to your account before it ever leaves their app, and doesn't publish any way for other apps to connect — not even the shared-server access Nextcloud offers. There's no way to add real automatic upload here. You can still move footage into Proton Drive yourself afterward; until you connect a supported backend, footage stays on this device.") }

    func select(_ id: StorageProviderID) {
        selected = id
        UserDefaults.standard.set(id.rawValue, forKey: Self.selectedProviderKey)
    }

    /// Non-isolated read for the upload pipeline, which runs off the main
    /// actor and only needs to know which backend was chosen.
    nonisolated static var selectedID: StorageProviderID {
        let stored = UserDefaults.standard.string(forKey: selectedProviderKey)
        return stored.flatMap(StorageProviderID.init(rawValue:)) ?? .googleDrive
    }
}



extension StorageProvider {
    /// Most backends are not asked to answer this. WebDAV can in principle,
    /// via a quota PROPFIND, but servers disagree about reporting it and a
    /// wrong number here would be worse than none.
    func availableBytes(token: String) async throws -> Int64? { nil }
}
