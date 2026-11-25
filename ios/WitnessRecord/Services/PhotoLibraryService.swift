import Foundation
import AVFoundation
import Photos

/// Saves a local copy of each recorded session into the user's photo library.
/// This is redundancy independent of the Drive upload: footage survives
/// on-device (and via iCloud Photos, if enabled) even if the app or its
/// sandbox is removed.
///
/// The app requests only **add-only** Photos access, so it can write videos
/// but never read the user's existing library — the most privacy-preserving
/// mode. Add-only access cannot fetch or manage albums (that requires full
/// read/write access), so saved videos land in the main library rather than a
/// dedicated album. Attempting an album fetch under add-only permission
/// hard-crashes via TCC, so this service deliberately never does so.
final class PhotoLibraryService {
    static let shared = PhotoLibraryService()

    /// True unless the user has explicitly denied Photos add access.
    var isEnabled: Bool {
        PHPhotoLibrary.authorizationStatus(for: .addOnly) != .denied
    }

    func requestPermission() {
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { _ in }
    }

    /// Saves the video at `fileURL` to the photo library. Best-effort:
    /// failures are logged but never block recording or upload. `completion`
    /// runs once the save resolves (success or failure), so callers can clean
    /// up the source file.
    /// Reports whether the video actually reached the library.
    ///
    /// It used to report only that it had finished trying. That was safe while
    /// an upload was always the other half of the story — a denied library
    /// just meant Drive kept the copy. It stopped being safe once a recording
    /// could have the library as its *only* destination, because the caller
    /// deletes its copy afterwards and had no way to know the save had failed.
    func save(fileURL: URL, completion: ((Bool) -> Void)? = nil) {
        save(fileURL: fileURL, identified: { id in completion?(id != nil) })
    }

    /// As `save`, reporting the new asset's identifier, so the app can show
    /// the recording later without keeping its own copy.
    func save(fileURL: URL, identified completion: @escaping (String?) -> Void) {
        let status = PHPhotoLibrary.authorizationStatus(for: .addOnly)
        switch status {
        case .authorized, .limited:
            performSave(fileURL: fileURL, completion: completion)
        case .notDetermined:
            PHPhotoLibrary.requestAuthorization(for: .addOnly) { [weak self] newStatus in
                if newStatus == .authorized || newStatus == .limited {
                    self?.performSave(fileURL: fileURL, completion: completion)
                } else {
                    Task { await EventLogger.shared.log(
                        "photos.save_refused", detail: "permission declined") }
                    completion(nil)
                }
            }
        default:
            Task { await EventLogger.shared.log(
                "photos.save_refused", detail: "permission \(status.rawValue)") }
            completion(nil)
        }
    }

    func saveVideo(fileURL: URL) async -> String? {
        await withCheckedContinuation { cont in
            save(fileURL: fileURL, identified: { cont.resume(returning: $0) })
        }
    }

    /// A verified photo's copy for the library. Best effort; the app keeps
    /// the original either way.
    func saveImage(fileURL: URL) {
        guard isEnabled else { return }
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else { return }
            PHPhotoLibrary.shared().performChanges {
                PHAssetCreationRequest.creationRequestForAssetFromImage(atFileURL: fileURL)
            }
        }
    }

    private func performSave(fileURL: URL, completion: @escaping (String?) -> Void) {
        var identifier: String?
        PHPhotoLibrary.shared().performChanges {
            // Add-only safe: creates a new asset from the file. No library read.
            let request = PHAssetCreationRequest.creationRequestForAssetFromVideo(atFileURL: fileURL)
            identifier = request?.placeholderForCreatedAsset?.localIdentifier
        } completionHandler: { success, error in
            if !success {
                Task {
                    await EventLogger.shared.log(
                        "photos.save_failed",
                        detail: "\(fileURL.lastPathComponent): \(error?.localizedDescription ?? "unknown")")
                }
            }
            completion(success ? (identifier ?? "") : nil)
        }
    }

    // MARK: - Watching a recording back

    /// Reading needs more than the add-only access used for saving, so it is
    /// asked for only when someone first plays a recording. "Limited" is
    /// enough: iOS includes the videos this app saved in a limited selection.
    /// Nothing here touches the library before that access exists, because
    /// a fetch under add-only access crashes.
    func requestReadAccess() async -> Bool {
        var status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        if status == .notDetermined {
            status = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        }
        return status == .authorized || status == .limited
    }

    var canRead: Bool {
        let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        return status == .authorized || status == .limited
    }

    /// The saved videos of a recording: by identifier when the save recorded
    /// one, otherwise (recordings saved before identifiers were kept) the
    /// videos the library dated to the recording's end, closest first.
    func videoAssets(identifiers: [String], start: Date, end: Date?) -> [PHAsset] {
        guard canRead else { return [] }
        let known = identifiers.filter { !$0.isEmpty }
        if !known.isEmpty {
            let found = PHAsset.fetchAssets(withLocalIdentifiers: known, options: nil)
            var assets: [PHAsset] = []
            found.enumerateObjects { asset, _, _ in assets.append(asset) }
            if !assets.isEmpty { return assets }
        }
        let finish = end ?? start
        let options = PHFetchOptions()
        options.predicate = NSPredicate(format: "mediaType == %d AND creationDate >= %@ AND creationDate <= %@",
                                        PHAssetMediaType.video.rawValue,
                                        start.addingTimeInterval(-120) as NSDate,
                                        finish.addingTimeInterval(3600) as NSDate)
        let found = PHAsset.fetchAssets(with: options)
        var assets: [PHAsset] = []
        found.enumerateObjects { asset, _, _ in assets.append(asset) }
        let length = finish.timeIntervalSince(start)
        return assets
            .filter { abs($0.duration - length) < max(15, length * 0.1) }
            .sorted { abs($0.duration - length) < abs($1.duration - length) }
    }

    func playerItem(for asset: PHAsset) async -> AVPlayerItem? {
        let options = PHVideoRequestOptions()
        options.isNetworkAccessAllowed = true
        options.deliveryMode = .automatic
        return await withCheckedContinuation { cont in
            PHImageManager.default().requestPlayerItem(forVideo: asset, options: options) { item, _ in
                cont.resume(returning: item)
            }
        }
    }
}
