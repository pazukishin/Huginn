# Huginn

**A dual-screen file explorer and media player for Android, built for the AYN Thor.**

> **Note:** This project was developed with the help of an **AI coding agent**
> (DeepSeek Harness). The agent wrote most of the code, tests and documentation under
> the author's direction and review. Bugs are still possible — please open an issue if
> you find one.

Huginn turns a dual-screen handheld into a proper file browser: the **bottom screen**
shows the explorer (folders, grid, gallery, settings) and the **top screen** shows the
selected item's details, previews and media playback. It can browse local storage (via
Android's Storage Access Framework) and SMB shares on your local network.

## Features

- **Dual-screen UI**: explorer on the lower display, content/details on the upper one.
  On a single-screen device it degrades to a normal one-screen app.
- **Local + SMB browsing**: SAF folders and SMB2/3 shares (SMB1 opt-in for legacy
  servers). Read-only for now.
- **Media playback with libVLC**: video and audio straight from SMB (no full download),
  subtitle support, audio/subtitle track picker, gamepad-friendly controls, on-screen
  gestures and synced/plain lyrics.
- **Previews**: image thumbnails with downsampling, video posters (a frame taken at 10%
  of the duration, avoiding black intros), audio artwork and a persistent metadata cache
  (duration, resolution, dimensions, tags). All cached on disk for instant reopening.
- **External apps**: non-media files from SMB are downloaded to a cache with progress and
  cancel, shared through a `FileProvider` and opened on the **top screen** only. An
  “Open with” picker lists real handlers for the file's MIME type, with a
  “Remember for this type” option.
- **Recents and favorites**, stored locally.
- **Local search** (SAF) with debounce and caps (depth 6, 5,000 entries, 200 results).
- **Settings**: clear cache, SMB servers (credentials encrypted with Android Keystore),
  button mapping (gamepad learn-mode), language, about.
- **Languages**: English (default) and Spanish, switchable in Settings → Language.
- **Gamepad-first**: the d-pad and face buttons drive the explorer; keyboard/mouse also
  work.

## Requirements

- Android 13+ (`minSdk 29`, `targetSdk 36`).
- `arm64-v8a` device (the APK only ships arm64 native libraries).
- A dual-screen device (AYN Thor) for the intended experience. Single-screen devices
  work in a degraded mode.

## Install

Download the signed APK from the [Releases](https://github.com/pazukishin/Huginn/releases/latest) page:

```bash
adb install -r Huginn-1.0.0-arm64.apk
```

The release APK is signed with the project's release key. If you build your own release,
use your own keystore — updates must be signed with the same key.

## Build from source

Requirements: JDK 17, Android SDK with platform 36 and build-tools, and the Gradle
wrapper (included).

```bash
# debug build + tests + lint
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

# release build (signed with your key, see below)
./gradlew :app:assembleRelease
```

Artifacts:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

### Release signing

Release builds read their signing config from
`.secrets/release-signing.properties` (git-ignored). If the file is missing, Gradle falls
back to the debug signing key so CI can still build. Example:

```properties
storeFile=.secrets/your-release.jks
storePassword=YOUR_PASSWORD
keyAlias=your_alias
keyPassword=YOUR_PASSWORD
```

### Optional local SMB config (development)

Some device scripts read an SMB configuration from `.secrets/smb.properties`
(git-ignored). It is **not needed** to build or run the app; configure servers from the
app's Settings instead.

## Tests

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
```

The project has 128 JVM unit tests covering the pure domain: SMB URL/credential handling,
metadata and thumbnail caches, search limits, recents/favorites, display routing, button
mapping, codecs and localization helpers. Device testing notes are kept under `docs/`
(in Spanish, written during development). See [`docs/testing-m6.md`](docs/testing-m6.md)
for the release test matrix and known limitations.

## Project structure

```
app/src/main/java/dev/huginn/
├── data/        file sources (SAF/SMB), random access
├── media/       libVLC engine, metadata probe, audio metadata
├── ui/          Compose UI: explorer (bottom) and top panel
├── display/     multi-display routing and Presentation
├── input/       gamepad/keyboard routing and button mapping
├── core/        settings, caches, SMB servers, recents/favorites, i18n
└── external/    external app launching (“Open with”)
```

## Limitations (v1)

- **Read-only**: no create, rename, delete or move.
- The content stays on the screen where the app is launched; moving it to the other
  display is not supported.
- Opening PDFs was not validated with a real PDF viewer on the test device; the “Open
  with” picker is expected to work once a viewer is installed.
- Local search only scans the configured SAF folder (or the current location if it is
  local).
- SMB1 support exists behind an opt-in but has not been validated against an SMB1-only
  server.
- Performance with 5,000 entries is covered by JVM tests; on-device testing used a
  561-entry folder.

## Security and privacy

- No telemetry, no network access other than the SMB servers you configure.
- SMB passwords are encrypted with AES-GCM, with the key stored in the Android Keystore;
  they are never written in plain text.
- Release signing keys and local secrets are **not** part of the repository.

## License

Released under the [MIT License](LICENSE) © 2026 pazukishin.

Third-party components keep their own licenses (libVLC LGPL-2.1+, jcifs-ng LGPL-2.1,
Bouncy Castle, AndroidX/Kotlin Apache-2.0, SLF4J MIT). See
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) for the full list and license texts.
