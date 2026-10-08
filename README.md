<div align="center">
  <img src="https://raw.githubusercontent.com/shilapi/xcertplay/refs/heads/master/asset/xcertplay_small.png" width="180" height="180" alt="xcertplay icon" />
<h1><strong><font size="6">xcertplay for BYD</font></strong></h1>
  <a href="README.md">English</a> | <a href="README.zh-CN.md">中文</a>
  <p>A wireless CarPlay receiver for BYD DiLink head units, based on the original <a href="https://github.com/shilapi/xcertplay">xcertplay</a> 1.3.3.</p>
</div>

This branch, `byd-song-plus-2021`, is tested in a Song PLUS 2021 (DiLink, Android 10). It adds BYD head-unit features and fixes on top of the original and translates the interface into Simplified Chinese. For the original features, MFi connection options, and build requirements, see [Original project](#original-project) at the end.

## Download and install

- Download the latest `xcertplay-mobile-<version>.apk` from [Releases](https://github.com/code-new-bie/xcertplay/releases) and install it on the head unit. The `automotive` package is only for Android Automotive OS head units; BYD head units do not need it.
- The package name is `com.shilapi.xcertplay.byd` and the launcher name is "CarPlay", so it can be installed next to the original.
- Builds use a debug signing key, so every build in this series installs over the previous one.
- MFi authentication is required, for example the [CH341-to-MFI](https://github.com/shilapi/ch341-to-mfi-chip) adapter board. For other options, see [Original project](#original-project).
- The first time the MFi adapter is connected, Android asks whether to open CarPlay for this USB device. Tick the option to use it by default and confirm; it does not ask again at later power-ons.

## First-time setup

1. Pair the iPhone with the head unit over Bluetooth, and keep the iPhone's Bluetooth and Wi-Fi on.
2. Open CarPlay and tap Settings on the home screen, or swipe down with three fingers. Categories are on the left: Connection, Display, Sound, Vehicle, Advanced, Diagnostics. Tap Save at the bottom after making changes.
3. Authorize head-unit ADB. The BYD features marked as needing ADB in the table below rely on it:
   - Network ADB (`127.0.0.1:5555`) must be enabled on the head unit; USB debugging alone is not enough. How to enable it depends on the head-unit firmware.
   - Open Settings → Vehicle → BYD → Head-unit ADB, tap "Check and authorize ADB", and allow the debugging prompt on the head unit.
   - The result shows whether speed and battery can be read. Background use never asks again.
4. Turn on the BYD features you want (below).
5. Go back to the home screen; the app starts the wireless CarPlay connection by itself.

## BYD features

| Feature | Where | Default | Needs ADB |
| --- | --- | --- | --- |
| Disconnect iPhone Bluetooth calls during CarPlay (recommended): calls go through CarPlay and the stock phone shows no popup | Connection | On | No |
| Disconnect iPhone Bluetooth music during CarPlay: music goes through CarPlay instead of Bluetooth | Connection | On | No |
| Pause head-unit Wi-Fi search during CarPlay (recommended), and restrict Gaode and Baidu network location scans, to reduce stutter | Connection | On | Yes |
| Auto-start on boot | Connection | Off | No |
| Keep CarPlay connected while the reversing or 360 camera is shown | Automatic | — | Yes |
| Exit CarPlay when the car is switched off and undo the head-unit changes | Automatic | — | Yes |
| Hide stock call popups (experimental) | Vehicle → BYD → Calls | Off | No |
| Lower AC fan during CarPlay calls, to level 1–3, restored when the call ends | Vehicle → BYD → Calls | Off | Yes |
| Song on the instrument cluster; with "car lyrics" on in the music app, the current lyric line | Vehicle → BYD → Instrument cluster | Off | Yes |
| Vehicle speed and gear for tunnel navigation (also needs "Report location to iPhone") | Vehicle → BYD → Vehicle data | Off | Yes |
| Electric battery and range for Apple Maps (experimental) | Vehicle → BYD → Vehicle data | Off | Yes |
| Read the head unit's real Bluetooth address and send it to the iPhone | Vehicle → BYD → Head-unit ADB | Manual | Yes |
| Report location to iPhone: head-unit positioning with speed and satellite data | Vehicle | Off | No |
| Separate head-unit audio channels for media, navigation, and calls, with a test tone | Sound | Automatic | No |
| Steering-wheel buttons control playback; long-press the voice button for Siri | Automatic | — | No |

## What CarPlay changes on the head unit

To reduce stutter and keep calls and music on CarPlay, wireless CarPlay changes the following on the head unit. The previous state is saved first and restored afterwards:

| Change | Notes |
| --- | --- |
| Disconnect the iPhone's Bluetooth calls (HFP) and Bluetooth music (A2DP) | Sets the head unit's connection priority for this iPhone to off; other phones are unaffected |
| Pause the head unit's automatic Wi-Fi search | The head unit does not join saved Wi-Fi networks meanwhile |
| Gaode Auto: restrict its Wi-Fi scans and force-stop it once per connection | Stock Gaode navigation ends and is not reopened |
| Baidu network location: restrict its Wi-Fi scans without stopping it | The head unit's network location may be coarser meanwhile |

When they are restored:
- 15 s after CarPlay disconnects; reconnecting within 15 s keeps them.
- At once when the matching option is turned off, or when you exit the app from Settings.
- When the car is switched off: a BYD head unit sleeps about 10 s after the car is switched off and closes third-party apps first. CarPlay therefore exits about 2 s after the switch-off and restores everything at once (needs ADB).
- As a fallback, anything not restored at switch-off (for example the door was opened at once and the head unit slept quickly) is restored at the next power-on:
  - With auto-start off: in the background, at once.
  - With auto-start on: kept for 60 s and restored only if CarPlay has not connected, so the iPhone's Bluetooth does not connect only to be dropped again.

CarPlay also takes audio focus while it plays, which pauses the head unit's own media, and keeps Wi-Fi in high-performance mode during wireless sessions.

## Auto-start on boot

- On: CarPlay opens and connects after the head unit boots or powers on.
- Off: CarPlay does not connect at power-on, even when Android detects the MFi adapter; open the app yourself.

## Logs and feedback

Export them from Settings → Diagnostics → Export logs. The files go to "Download/xcertplay":

| File | Contents |
| --- | --- |
| `xcertplay-<time>.log` | This run's log; it starts over each time CarPlay opens |
| `xcertplay-boot-<time>.txt` | What happened when the car was switched off and at power-on |
| `xcertplay-logcat-<time>.txt` | The app's own system log |

Export before exiting the app, or this run's log is lost. The logs live in `/sdcard/Android/data/com.shilapi.xcertplay.byd/files/logs/`.

When opening an issue, attach all three files and name the car model and head-unit software version.

## Known issues

- Music occasionally stutters briefly during long playback; diagnostic logging has been added.
- If the car is switched on again within 10 s of switching it off, CarPlay does not come back by itself; open it manually.
- Keeping the session during reversing, location continuation, exit at switch-off, and the power-on restore need more in-car testing.

## Development

Build on Windows PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :common:testDebugUnitTest :shared:testDebugUnitTest :common:lintDebug :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug
```

On macOS or Linux:

```bash
./gradlew :common:testDebugUnitTest :shared:testDebugUnitTest :common:lintDebug :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug
```

Releases (GitHub Actions, `.github/workflows/android.yml`):
- A branch push runs the tests and lint and attaches debug APKs.
- A tag push builds the release APKs and publishes a release. The version name is the tag, passed as `-PreleaseVersionName=<tag>`.
- An annotated tag's message becomes the release notes. Create it with `git tag -a <tag> --cleanup=whitespace -F notes.md`; otherwise git drops heading lines that start with `#` as comments.
- Signing uses four secrets: `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. This repository uses a debug key so that every build installs over the others. Locally, set `ANDROID_KEYSTORE_PATH` and the related variables to sign; without them the release APKs are unsigned.

In-car findings, firmware analysis, and open verification items are in the handoff notes, [docs/handoff/2026-10-05-byd-song-plus-2021.md](docs/handoff/2026-10-05-byd-song-plus-2021.md) (Chinese).

## Original project

The following describes the original xcertplay and still applies to this branch.

### Original features

- CarPlay host applications for Android and Android Automotive OS.
- Support for MFi chips connected through a CH341 bridge or native `/dev/i2c-N` devices, local certificate/private-key files, and Remote MFI authentication (see the API below).
- Wired and wireless CarPlay connections.
- CarPlay Ultra triggering (the protocol stack is untested/incomplete, but it can trigger the CarPlay Ultra prompt on an iPhone).
- Voice, navigation, and music multi-channel audio output mapped to the corresponding Android channels.
- Dynamic Activity resizing with automatic re-handshaking to the new resolution.
- Vehicle head-unit location reporting.
- Android 9 (API 28) support.

MFi adapter board: [CH341-to-MFI](https://github.com/shilapi/ch341-to-mfi-chip). Choose the authentication method under Settings → Connection → Authentication.

### Local MFI files

Choose "Local files" under Authentication, then use the two "Choose" buttons to select the certificate and private key with Android's system document picker. The supported formats are a DER PKCS#7 certificate (`.p7b`) and its matching, unencrypted DER PKCS#8 private key (`.pk8`). The app validates that the files match before starting the phone connection and reloads them on MFI reconnect.

Store the private key in a protected location. Neither file is copied into app preferences; only Android's persistent read permission and document URI are saved.

### Remote MFI

The Remote MFi client treats a remote service as an MFi chip for remote calls, or uses BAA authentication. Remote authentication avoids connecting a local MFi chip.

| Method | Path | Purpose | Request body | Success response | Failure response |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/mfi/certificate` | Get the MFi chip version, certificate type, and certificate contents; cached by the client after the first call | None | Certificate JSON | `{"detail":"..."}` |
| `POST` | `/mfi/sign` | Sign the challenge | `{"challenge":"...","requestId":"..."}` | `{"signature":"..."}` | `{"detail":"..."}` |
| `POST` | `/mfi/reset` | Request a reset of the remote MFi chip | `{}` | `{"detail":""}` | `{"detail":"..."}` |

(Optional) Standard Bearer Authentication can be used for verification.

**Currently, only BAA Authentication has been tested.**

### Project structure

| Path | Purpose |
| --- | --- |
| `common/` | Shared CarPlay host activity, settings UI, persistence, and app resources used by both targets. |
| `mobile/` | Standard Android target using the shared CarPlay host UI. Install this on BYD head units. |
| `automotive/` | Android Automotive OS target with the shared host UI and advanced audio channel mapping. |
| `shared/` | Car App Library code plus the CH341, I2C, MFi, iPhone, iAP2, NCM, VPN, AirPlay, media, and BYD head-unit implementations. |

### Requirements

- JDK 17 or newer to launch Gradle. The daemon resolves Java 25 through the Gradle toolchain.
- Android SDK Platform 37.
- Android 9 (API 28) or newer. On Android 9, Wi-Fi P2P 5 GHz mode is unavailable and LocalOnlyHotspot is used instead.
- Android NDK `28.2.13676358`.
- A physical USB Host/OTG Android device and MFi hardware are required for hardware validation.

## Acknowledgements

Thanks to [LIVI](https://github.com/f-io/LIVI) for providing important reference for this project.
Thanks to the [showcase](https://github.com/amineross/showcase) project for providing important reference for the BAA authentication in this project.

## License

Licensed under the [GNU General Public License v3.0](LICENSE).
