// swift-tools-version: 5.9
import PackageDescription

/// The watermark algorithm, shared by the app (which embeds) and the offline
/// verification tool (which reads). Kept as a package rather than app source
/// so the tool can be published and audited on its own: everything here is
/// meant to be public, and only the HMAC key is secret.
let package = Package(
    name: "WatermarkKit",
    platforms: [.iOS(.v17), .macOS(.v13)],
    products: [
        .library(name: "WatermarkKit", targets: ["WatermarkKit"]),
        .executable(name: "wmverify", targets: ["wmverify"]),
        .executable(name: "wmdemo", targets: ["wmdemo"]),
    ],
    targets: [
        .target(name: "WatermarkKit"),
        .executableTarget(name: "wmverify", dependencies: ["WatermarkKit"]),
        // Writes a known watermarked clip, so the verifier can be exercised
        // against a file whose expected answer is known in advance.
        .executableTarget(name: "wmdemo", dependencies: ["WatermarkKit"]),
        .testTarget(name: "WatermarkKitTests", dependencies: ["WatermarkKit"]),
    ]
)
