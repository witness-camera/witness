# Witness — Android

An Android port of the Witness iOS app: a tool for civilians to document
police encounters. Recording runs behind a black screen at minimum
brightness, footage is chopped into ~15-second .mp4 chunks, and each chunk
uploads to your Google Drive **while you're still recording** — so the footage
survives even if the phone is seized or destroyed.

Feature-for-feature and screen-for-screen the same app as the iOS build,
including the same five languages (en, it, es, fr, de) using the same
translations. Where Android genuinely differs, see
[Platform differences](#platform-differences).

## Project layout

```
app/src/main/java/com/witnessrecord/app/
  WitnessApplication.kt   Process setup; recovers unfinished work at launch
  MainActivity.kt         Compose host, window blackout, permissions, sign-in
  AppState.kt             Instant-start latch shared by every entry point
  entry/        StartRecordingTileService (quick-settings tile), WitnessWidget
  ui/           RootScreen, BlackoutRecordingScreen, CameraPreview, HomeScreen,
                OnboardingScreen (paged tutorial), PracticeRecordingScreen
                (one-time hands-on stop-gesture walkthrough), SettingsScreen,
                SupportScreen, ProtectFootageScreen, Components, Gestures
  viewmodel/    CaptureViewModel (session lifecycle, viewfinder, storage)
  capture/      CaptureSessionController (single- and dual-camera), ChunkWriter,
                VideoEncoder, AudioEncoder, CameraSelection, RecordingService
  service/      UploadManager, UploadWorker, GoogleDriveClient, GoogleAuthService,
                SessionStitcher, MediaStoreService, SecureStore, ChunkStore,
                EventLogger, LocationService, SessionFolderNaming,
                DriveFolderCache, AppSettings, Prefs, Hashing
  model/        ChunkRecord, SessionManifest, EmergencyContact, CameraSource
tools/          String-table generator (reuses the iOS translations)
```

## Building

Requires **JDK 17+** and the Android SDK (platform 35). No Android Studio
needed for a command-line build.

```sh
./gradlew :app:assembleDebug
```

If `JAVA_HOME` is not already a JDK 17+, prefix the command:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :app:assembleDebug
```

`local.properties` points at the SDK and is git-ignored; recreate it with
`sdk.dir=/path/to/Android/sdk` on a new machine.

**minSdk is 30 (Android 11).** Every defining behaviour of the app needs it:
the camera/microphone/location foreground-service types that keep a recording
alive with the screen off, `getConcurrentCameraIds` for dual-camera, and
`CONTROL_ZOOM_RATIO` for the gapless 0.5×/1× lens crossover.

Camera capture does not work on an emulator in any meaningful way — test on a
physical device.

## Google Drive setup

The Android app needs its **own OAuth client**; the iOS one cannot be reused.

1. In the [Google Cloud console](https://console.cloud.google.com), open the
   same project as the iOS app (Drive API already enabled) and create an
   **OAuth client ID** of type **Android** with:
   - Package name: `com.witnessrecord.app`
   - SHA-1 certificate fingerprint: the fingerprint of the key you sign with.
2. There is **nothing to paste into the app**. Unlike the iOS build, no client
   ID appears in the source: Play Services identifies the app by its package
   name and signing certificate, so registering the fingerprint is the whole
   configuration step.
3. Register a fingerprint for *each* signing key you use — the debug key for
   local builds, and the upload key (or Play App Signing key) for release.

The debug key on the machine this was built on:

```
SHA1: 87:DD:78:AD:6C:D0:D3:A1:49:F8:C9:D1:43:E6:8F:C1:4D:28:5B:E4
```

Read it from any machine with:

```sh
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android
```

The scope is `drive.file` only — access to files this app creates, and nothing
else in your Drive. It is a non-sensitive scope, so no verification review is
required, but the consent screen must be **Published**, not left in "Testing".

Until sign-in happens, chunks queue locally and upload the moment an account is
connected — nothing is ever discarded before Drive confirms receipt.

## Onboarding

First launch runs a paged, full-bleed tutorial (`OnboardingScreen`) — one idea
per screen (welcome, how it works, instant start, permissions, connecting
Drive, adding an emergency contact, then rights), swipeable or advanced with a
"Next"/"Get Started" pill.

Right after that, a one-time **practice run** (`PracticeRecordingScreen`)
teaches the stop gesture hands-on: it reproduces the real black screen and the
same recognisers (triple-tap, then a 1-second hold), coaching the user through
the gesture before they ever need it for real. No camera, capture, or upload
happens there — it's UI-only and skippable. Both gates live in `RootScreen`,
and an instant-start path bypasses them the same way it bypasses onboarding —
the user is already mid-action.

## Instant start

Android has no Action Button, so the iOS build's one-press story is delivered
by the platform's own equivalents. All of them land on the same
`witnessrecord://start` deep link and drop straight into recording.

- **Quick Settings tile** — the closest equivalent, and what onboarding leads
  with: one pull-down and one tap, reachable from the lock screen.
- **Home screen widget** — one tap. (Android has had no general lock screen
  widgets since Android 5; the tile covers that case instead.)
- **Assistant** — "Hey Google, start recording with Witness", via
  `res/xml/shortcuts.xml`. Also the long-press app shortcut.
- **Power-button double-press** — off by default, enabled in Settings. Android
  routes that gesture only to camera apps, so switching it on also offers
  Witness as a camera app; the toggle enables/disables a manifest
  `activity-alias` to opt in and out cleanly.

To stop: **triple-tap** the screen, then **press and hold** the stop icon.

## Camera behaviour

- **Lens choice** mirrors iOS. A rear camera whose `CONTROL_ZOOM_RATIO_RANGE`
  reaches below 1.0 is preferred, because Android crosses the ultra-wide↔wide
  handoff optically as the ratio changes — that is what makes the viewfinder's
  live 0.5×/1× toggle gapless. Where no such camera exists, a dedicated
  ultra-wide sensor is selected up front and the lens control is hidden, the
  same distinction iOS draws between a virtual device and a physical lens.
- **Single-camera** records at 1080p, 30 fps.
- **Dual-camera** (Settings → Camera → *Record front camera too*, default on)
  records rear and front simultaneously, using the pairs the device reports
  through `getConcurrentCameraIds`. Each camera writes its own parallel chunk
  stream, rotating together so the two stay aligned, and both carry the same
  audio so each file is independently watchable.
- Video stabilization is enabled where the camera offers it (optical first,
  then digital).
- Configuration degrades gracefully: dual → single → error, honouring the lens
  preference at each step.

### Viewfinder

Off by default (a dark screen is the discreet default). Toggle it in Settings,
or live while recording: triple-tap, then tap the **eye** icon. The rear
camera fills the screen and the front camera appears as a rounded
picture-in-picture in the top-right corner. Brightness follows automatically —
minimum when blacked out, normal when the viewfinder is shown.

Both preview surfaces are attached to the capture session for the whole
recording and simply drawn over when hidden, so toggling never reconfigures
the camera and never interrupts the footage.

## How footage is preserved

- ~15 s chunks. The H.264 and AAC encoders run continuously for the whole
  recording and only the **muxer** is swapped at a boundary, at a keyframe
  requested on demand — so a rotation costs no frames at all.
- Each finalized chunk is SHA-256 hashed, then uploaded through Drive's
  **resumable** upload API. An interrupted upload asks the server how many
  bytes it holds and resumes from there. Cellular is always allowed.
- Each recording session uploads into its own Drive subfolder, named from a
  **template** configurable in Settings (`{date}`, `{time}`, `{location}`,
  `{session}`) — default `Witness {date} {time}, {location}`, e.g.
  `Witness 2026-07-17 13-04-22, San Francisco, CA` (reverse-geocoded, falling
  back to coordinates).
- A single **`manifest_<sessionID>.json`** per session is the chain-of-custody
  record — one JSON file listing every chunk's hash, camera, capture
  start/end (UTC), GPS, and accuracy. It is rewritten and re-uploaded (PATCHed
  over the same Drive file) as each new chunk is hashed, so Drive holds
  current custody records throughout the recording — not just at the end —
  even if the phone is seized mid-encounter. Timestamps are written in the
  same ISO-8601 shape as the iOS build, so a manifest from either platform
  parses identically.
- GPS and orientation are embedded in each .mp4's container metadata.
- An append-only `witness_events.jsonl` logs start/stop/interruption/upload
  events independently of the video files and is uploaded after each session.
- Local chunk files are deleted **only after** Drive confirms the upload.
  Failed uploads retry with exponential backoff, flush immediately when
  connectivity returns, and are re-driven by a **WorkManager** job that
  survives the app being force-stopped or the device rebooting.
- Optionally (Settings → Google Drive → *Combine into one video per camera*,
  on by default): once every chunk of a session is confirmed in Drive, they're
  merged into one video per camera (`recording_rear.mp4` /
  `recording_front.mp4`) and uploaded, then the small chunks are deleted from
  Drive — the manifest is rewritten one final time to document the merge. This
  never starts until the small chunks are already safely in Drive, so nothing
  is at risk while it runs. Deletion is scoped per-camera, so a camera whose
  merge failed keeps its chunks.
- The on-disk upload queue carries a schema version and decodes each record
  independently, so a future format change can only ever drop the one affected
  record — never silently lose the whole queue of an in-progress recording
  across an app update.

## Gallery backup

Unless disabled in Settings, each session is stitched into a single continuous
video per camera and saved into a **Movies/Witness** folder in the gallery.
The merge is lossless: already-encoded samples are copied straight from one
container into another, never re-encoded.

While recording, each chunk is **hard-linked** into a staging directory so the
uploader can delete its own copy without affecting the stitch; staging is
cleaned up after the save, and a merge left pending by a killed process is
recovered at next launch and resumed.

Writes go through MediaStore, which grants an app insert access to its own
media with **no storage permission and no read access** to the user's existing
library — the same add-but-never-read posture the iOS build gets from add-only
Photos access.

## Platform differences

Everything above matches iOS. These are the places where the platform forced a
real difference, rather than a cosmetic one:

- **Recording survives the screen going off.** iOS suspends camera frames when
  the app is backgrounded; Android does not, provided capture runs inside a
  typed foreground service — which it does. This is strictly better than the
  iOS behaviour.
- **A notification is unavoidable.** Android requires a foreground service to
  post one. It is mitigated as far as the platform allows: the channel is
  `IMPORTANCE_MIN`, which suppresses the status-bar icon, sound, and heads-up
  banner, and the notification is marked secret so it does not render on the
  lock screen. It is still visible to anyone who deliberately pulls down the
  notification shade. There is no way around this, and the text is deliberately
  plain rather than alarming.
- **Dual-camera is capped at 720p per camera.** Android only guarantees
  concurrent two-camera streaming at that resolution. Single-camera recordings
  are 1080p.
- **No client ID in the source.** Play Services authorizes by package name plus
  signing certificate, so there is no `GIDClientID` equivalent to configure.
- **Recordings land in a `Movies/Witness` folder**, not loose in the camera
  roll. MediaStore lets an app name its own destination on insert; iOS could
  not, because an album lookup would need a Photos *read* the app deliberately
  never has.
- **Instant start** is a set of entry points rather than one button — see
  above.

## Known limitations

- Dual-camera is power- and thermally-expensive; long sessions may warm the
  device. Device support for concurrent cameras is also far from universal.
- The rights summary links to the ACLU rather than hardcoding legal claims;
  laws vary by jurisdiction and change. This app is not legal advice.
- The anti-deletion limitation is the same as on iOS: in consumer Drive the
  owner can always delete, and sharing gives a contact a view rather than an
  independent copy. `ProtectFootageScreen` explains the mitigations; the app
  itself never deletes from Drive.
