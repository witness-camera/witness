import Foundation

/// Builds Google Drive REST API requests. The small control calls (folder
/// lookup, resumable-session initiation) run on a foreground URLSession; the
/// large file bodies are handed to UploadManager's background session.
enum GoogleDriveClient {
    static let filesEndpoint = URL(string: "https://www.googleapis.com/drive/v3/files")!
    static let uploadEndpoint = URL(string: "https://www.googleapis.com/upload/drive/v3/files")!

    struct DriveError: LocalizedError {
        let message: String
        var statusCode: Int? = nil
        var errorDescription: String? { message }

        /// Whether Drive is reporting that a referenced file/folder — most
        /// often a cached parent ID — no longer exists, so the caller should
        /// evict its cache and re-resolve rather than keep retrying the same
        /// dead ID forever.
        var isNotFound: Bool { statusCode == 404 }
    }

    /// How much room is left in the account.
    ///
    /// `limit` is absent on accounts with unlimited storage, which is not an
    /// error and not a shortage — nil means "no answer", never "none left".
    static func availableBytes(accessToken: String) async throws -> Int64? {
        var components = URLComponents(string: "https://www.googleapis.com/drive/v3/about")!
        components.queryItems = [.init(name: "fields", value: "storageQuota")]
        var request = URLRequest(url: components.url!)
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        request.timeoutInterval = 15

        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200,
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let quota = json["storageQuota"] as? [String: Any],
              let limit = (quota["limit"] as? String).flatMap(Int64.init) else { return nil }
        let used = (quota["usage"] as? String).flatMap(Int64.init) ?? 0
        return max(0, limit - used)
    }

    /// Turns a failed folder creation into something a person can act on.
    ///
    /// This used to read "Could not create Drive folder" and nothing else, and
    /// it cost a journalist an afternoon: her queue had stopped at six hundred
    /// segments and the app gave no hint whether the problem was her account,
    /// her connection or the app itself. Drive says exactly what is wrong in
    /// the reply body, and the status code was already being captured and then
    /// thrown away.
    ///
    /// A full Drive is called out by name because it is both the likeliest
    /// cause of a long recording stalling — an hour of video will fill a free
    /// account — and the only one the user can fix in a minute.
    private static func folderFailureMessage(status: Int?, body: Data) -> String {
        let json = try? JSONSerialization.jsonObject(with: body) as? [String: Any]
        let error = json?["error"] as? [String: Any]
        let reason = (error?["errors"] as? [[String: Any]])?.first?["reason"] as? String
        let detail = error?["message"] as? String

        if reason == "storageQuotaExceeded" || detail?.lowercased().contains("quota") == true {
            return String(localized: "Your Google Drive is full, so nothing can be uploaded. Free up space in Drive, or connect a different storage account — everything already recorded is still on this phone and will upload once there is room.")
        }
        if status == 401 || status == 403 {
            let code = status ?? 0
            return String(localized: "Google Drive refused the upload (\(code)). Try signing out and back in under Storage.")
        }
        var text = String(localized: "Could not create the Drive folder")
        if let status { text += " (\(status))" }
        if let detail, !detail.isEmpty { text += ": \(detail)" }
        return text
    }

    // MARK: - Folder

    /// Finds or creates the destination folder, caching its ID in the
    /// Keychain. Routed through `DriveFolderCache.sharedRoot` so concurrent
    /// callers (several chunks uploading at once, right after sign-in) share
    /// one resolution instead of each independently creating a folder.
    static func ensureFolder(named name: String, accessToken: String) async throws -> String {
        try await DriveFolderCache.sharedRoot.folderID(for: "root") {
            // Search for an existing folder with this name.
            let escaped = name.replacingOccurrences(of: "'", with: "\\'")
            var components = URLComponents(url: filesEndpoint, resolvingAgainstBaseURL: false)!
            components.queryItems = [
                .init(name: "q", value: "name = '\(escaped)' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"),
                .init(name: "fields", value: "files(id)"),
            ]
            var searchRequest = URLRequest(url: components.url!)
            searchRequest.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")

            let (searchData, _) = try await URLSession.shared.data(for: searchRequest)
            if let json = try? JSONSerialization.jsonObject(with: searchData) as? [String: Any],
               let files = json["files"] as? [[String: Any]],
               let id = files.first?["id"] as? String {
                return id
            }

            // Create it.
            var createRequest = URLRequest(url: filesEndpoint)
            createRequest.httpMethod = "POST"
            createRequest.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
            createRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
            createRequest.httpBody = try JSONSerialization.data(withJSONObject: [
                "name": name,
                "mimeType": "application/vnd.google-apps.folder",
            ])
            let (createData, createResponse) = try await URLSession.shared.data(for: createRequest)
            let status = (createResponse as? HTTPURLResponse)?.statusCode
            guard status == 200,
                  let json = try? JSONSerialization.jsonObject(with: createData) as? [String: Any],
                  let id = json["id"] as? String else {
                throw DriveError(message: Self.folderFailureMessage(status: status, body: createData),
                                 statusCode: status)
            }
            return id
        }
    }

    /// Finds a file or folder named `name` directly inside `parentID`, or
    /// `nil` if none exists. Used to make uploads idempotent — safe to retry
    /// after a crash without creating a duplicate.
    static func findFile(named name: String,
                         parentID: String,
                         accessToken: String) async throws -> String? {
        let escaped = name.replacingOccurrences(of: "'", with: "\\'")
        var components = URLComponents(url: filesEndpoint, resolvingAgainstBaseURL: false)!
        components.queryItems = [
            .init(name: "q", value: "name = '\(escaped)' and '\(parentID)' in parents and trashed = false"),
            .init(name: "fields", value: "files(id)"),
        ]
        var request = URLRequest(url: components.url!)
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")

        let (data, _) = try await URLSession.shared.data(for: request)
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let files = json["files"] as? [[String: Any]] else { return nil }
        return files.first?["id"] as? String
    }

    /// Permanently deletes a file or folder the app created. Used only after
    /// a superseding artifact (a merged session video) is confirmed uploaded —
    /// never as part of normal chunk handling.
    static func deleteFile(fileID: String, accessToken: String) async throws {
        var request = URLRequest(url: filesEndpoint.appendingPathComponent(fileID))
        request.httpMethod = "DELETE"
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        let (_, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) || http.statusCode == 404 else {
            throw DriveError(message: "Could not delete Drive file \(fileID) (\((response as? HTTPURLResponse)?.statusCode ?? -1))")
        }
    }

    /// Finds or creates a subfolder with `name` inside `parentID`. Searches
    /// first so repeated calls (and relaunches) reuse the same folder rather
    /// than creating duplicates. Not Keychain-cached — the caller caches the
    /// per-session mapping.
    static func ensureSubfolder(named name: String,
                                parentID: String,
                                accessToken: String) async throws -> String {
        let escaped = name.replacingOccurrences(of: "'", with: "\\'")
        var components = URLComponents(url: filesEndpoint, resolvingAgainstBaseURL: false)!
        components.queryItems = [
            .init(name: "q", value: "name = '\(escaped)' and '\(parentID)' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false"),
            .init(name: "fields", value: "files(id)"),
        ]
        var searchRequest = URLRequest(url: components.url!)
        searchRequest.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")

        let (searchData, _) = try await URLSession.shared.data(for: searchRequest)
        if let json = try? JSONSerialization.jsonObject(with: searchData) as? [String: Any],
           let files = json["files"] as? [[String: Any]],
           let id = files.first?["id"] as? String {
            return id
        }

        var createRequest = URLRequest(url: filesEndpoint)
        createRequest.httpMethod = "POST"
        createRequest.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        createRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        createRequest.httpBody = try JSONSerialization.data(withJSONObject: [
            "name": name,
            "mimeType": "application/vnd.google-apps.folder",
            "parents": [parentID],
        ])
        let (createData, createResponse) = try await URLSession.shared.data(for: createRequest)
        let status = (createResponse as? HTTPURLResponse)?.statusCode
        guard status == 200,
              let json = try? JSONSerialization.jsonObject(with: createData) as? [String: Any],
              let id = json["id"] as? String else {
            throw DriveError(message: "Could not create Drive session folder", statusCode: status)
        }
        return id
    }

    // MARK: - Resumable upload (large video chunks)

    /// Initiates a resumable upload session; returns the session URI to PUT
    /// the file body to.
    static func beginResumableSession(fileName: String,
                                      mimeType: String,
                                      fileSize: Int,
                                      folderID: String,
                                      accessToken: String) async throws -> URL {
        var components = URLComponents(url: uploadEndpoint, resolvingAgainstBaseURL: false)!
        components.queryItems = [.init(name: "uploadType", value: "resumable")]

        var request = URLRequest(url: components.url!)
        request.httpMethod = "POST"
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json; charset=UTF-8", forHTTPHeaderField: "Content-Type")
        request.setValue(mimeType, forHTTPHeaderField: "X-Upload-Content-Type")
        request.setValue(String(fileSize), forHTTPHeaderField: "X-Upload-Content-Length")
        request.httpBody = try JSONSerialization.data(withJSONObject: [
            "name": fileName,
            "parents": [folderID],
        ])

        let (_, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200,
              let location = http.value(forHTTPHeaderField: "Location"),
              let uri = URL(string: location) else {
            throw DriveError(message: "Resumable session initiation failed (\((response as? HTTPURLResponse)?.statusCode ?? -1))")
        }
        return uri
    }

    /// The PUT request whose body file is uploaded by the background session.
    static func resumablePUTRequest(sessionURI: URL, fileSize: Int, mimeType: String) -> URLRequest {
        var request = URLRequest(url: sessionURI)
        request.httpMethod = "PUT"
        request.setValue(mimeType, forHTTPHeaderField: "Content-Type")
        request.setValue(String(fileSize), forHTTPHeaderField: "Content-Length")
        return request
    }

    /// PATCHes new contents over an existing Drive file, keeping the same file
    /// ID. Used to keep one live session manifest current rather than
    /// accumulating a new file per revision.
    static func updateFileRequest(fileID: String,
                                  mimeType: String,
                                  accessToken: String) -> URLRequest {
        var components = URLComponents(
            url: uploadEndpoint.appendingPathComponent(fileID),
            resolvingAgainstBaseURL: false)!
        components.queryItems = [.init(name: "uploadType", value: "media")]

        var request = URLRequest(url: components.url!)
        request.httpMethod = "PATCH"
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue(mimeType, forHTTPHeaderField: "Content-Type")
        return request
    }

    /// Grants `email` read access to a file/folder the app created. Drive
    /// sends the recipient a notification email, which doubles as the
    /// "footage is being recorded" alert to the emergency contact.
    static func shareFile(fileID: String,
                          withEmail email: String,
                          accessToken: String) async throws {
        var components = URLComponents(
            url: filesEndpoint.appendingPathComponent(fileID).appendingPathComponent("permissions"),
            resolvingAgainstBaseURL: false)!
        components.queryItems = [.init(name: "sendNotificationEmail", value: "true")]

        var request = URLRequest(url: components.url!)
        request.httpMethod = "POST"
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: [
            "role": "reader",
            "type": "user",
            "emailAddress": email,
        ])

        let (_, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw DriveError(message: "Sharing failed (\((response as? HTTPURLResponse)?.statusCode ?? -1)) for \(email)")
        }
    }

    // MARK: - Multipart upload (small manifest / log files)

    /// Builds a multipart-upload request with the body written to a temp
    /// file, as background sessions can only upload from files.
    static func multipartUploadRequest(fileURL: URL,
                                       uploadName: String,
                                       mimeType: String,
                                       folderID: String,
                                       accessToken: String) throws -> (URLRequest, URL) {
        var components = URLComponents(url: uploadEndpoint, resolvingAgainstBaseURL: false)!
        components.queryItems = [.init(name: "uploadType", value: "multipart")]

        let boundary = "witness-\(UUID().uuidString)"
        let metadata = try JSONSerialization.data(withJSONObject: [
            "name": uploadName,
            "parents": [folderID],
        ])
        let fileData = try Data(contentsOf: fileURL)

        var body = Data()
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Type: application/json; charset=UTF-8\r\n\r\n".data(using: .utf8)!)
        body.append(metadata)
        body.append("\r\n--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Type: \(mimeType)\r\n\r\n".data(using: .utf8)!)
        body.append(fileData)
        body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)

        let bodyURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("multipart-\(UUID().uuidString).body")
        try body.write(to: bodyURL)

        var request = URLRequest(url: components.url!)
        request.httpMethod = "POST"
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue("multipart/related; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        return (request, bodyURL)
    }
}
