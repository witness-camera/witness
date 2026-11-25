import Foundation
import Security

/// Thin wrapper over the iOS Keychain for the app's sensitive values:
/// the Google OAuth refresh token and the Drive destination folder ID.
struct KeychainService {
    enum Key: String {
        case googleRefreshToken = "com.witnessrecord.google.refreshToken"
        case driveFolderID = "com.witnessrecord.drive.folderID"
        /// WebDAV has no token exchange, so the password itself is the
        /// long-lived credential and belongs in the Keychain rather than
        /// alongside the server address in preferences.
        case webDAVPassword = "com.witnessrecord.webdav.password"
        case dropboxRefreshToken = "com.witnessrecord.dropbox.refreshToken"
    }

    static let shared = KeychainService()
    private let service = "com.witnessrecord.app"

    func set(_ value: String, for key: Key) {
        set(Data(value.utf8), for: key)
    }

    func set(_ data: Data, for key: Key) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key.rawValue,
        ]
        // kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly so background upload
        // retries can read credentials while the phone is locked mid-encounter,
        // without the OAuth refresh token migrating to a new device via an
        // encrypted backup/restore.
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var addQuery = query
            addQuery.merge(attributes) { _, new in new }
            SecItemAdd(addQuery as CFDictionary, nil)
        }
    }

    func string(for key: Key) -> String? {
        data(for: key).flatMap { String(data: $0, encoding: .utf8) }
    }

    func data(for key: Key) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key.rawValue,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    func delete(_ key: Key) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key.rawValue,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
