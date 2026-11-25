# Witness Camera: source code

This is the source code of Witness Camera for iPhone and Android, published so
that anyone can check what the app does with their footage: that it uploads only
to the cloud account you connect, that Witness runs no server, and how the
SHA-256 fingerprints and crew encryption work.

**It is source-available, not open source.** You can read, build and run it to
verify it. You cannot republish it, ship it to any app store, or base another app
on it. See [LICENSE.md](LICENSE.md), [NOTICE](NOTICE) and [TRADEMARK.md](TRADEMARK.md).
Copies published in breach of the licence will be reported to Apple, Google and
hosting providers for removal.

Get the official app: [App Store](https://apps.apple.com/us/app/witness-camera-beyond-reach/id6800375426) ·
[Google Play](https://play.google.com/store/apps/details?id=com.witnessrecord.app) ·
[witnesscamera.com](https://witnesscamera.com)

## Building to verify

- iOS: install XcodeGen, in `ios/`, copy `Config/Secrets.example.xcconfig` to
  `Config/Secrets.xcconfig`, run `xcodegen generate`, open the project.
- Android: in `android/`, copy `local.properties.example` to `local.properties`, then
  `./gradlew assembleDebug` with JDK 17.

Without your own Google and Dropbox client IDs, those two sign-ins are disabled;
WebDAV and the rest of the app work.

## Security

Found a vulnerability? See [SECURITY.md](SECURITY.md). Please report privately.
