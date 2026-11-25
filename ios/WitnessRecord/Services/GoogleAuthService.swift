import Foundation
import UIKit
import GoogleSignIn

/// Wraps GoogleSignIn: sign-in with the drive.file scope, session restore,
/// and fresh access tokens for the upload pipeline. A copy of the refresh
/// token is mirrored into the Keychain via KeychainService.
@MainActor
final class GoogleAuthService: ObservableObject {
    static let shared = GoogleAuthService()

    static let driveScope = "https://www.googleapis.com/auth/drive.file"

    @Published private(set) var userEmail: String?
    var isSignedIn: Bool { userEmail != nil }

    private init() {}

    /// Call once at launch to restore a previous session.
    func restore() {
        GIDSignIn.sharedInstance.restorePreviousSignIn { [weak self] user, _ in
            Task { @MainActor in
                self?.userEmail = user?.profile?.email
                if let token = user?.refreshToken.tokenString {
                    KeychainService.shared.set(token, for: .googleRefreshToken)
                }
            }
        }
    }

    func signIn(presenting viewController: UIViewController) async throws {
        let result = try await GIDSignIn.sharedInstance.signIn(
            withPresenting: viewController,
            hint: nil,
            additionalScopes: [Self.driveScope]
        )
        userEmail = result.user.profile?.email
        KeychainService.shared.set(result.user.refreshToken.tokenString, for: .googleRefreshToken)
        await EventLogger.shared.log("drive.signed_in", detail: userEmail)
    }

    func signOut() {
        GIDSignIn.sharedInstance.signOut()
        userEmail = nil
        KeychainService.shared.delete(.googleRefreshToken)
        KeychainService.shared.delete(.driveFolderID)
    }

    /// Returns a valid access token, refreshing via the SDK if expired.
    func freshAccessToken() async throws -> String {
        guard let user = GIDSignIn.sharedInstance.currentUser else {
            throw AuthError.notSignedIn
        }
        let refreshed = try await user.refreshTokensIfNeeded()
        return refreshed.accessToken.tokenString
    }

    enum AuthError: LocalizedError {
        case notSignedIn
        var errorDescription: String? {
            "Not signed in to Google Drive. Footage stays queued locally until you sign in."
        }
    }
}
