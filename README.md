<div align="center">

<img src="docs/logo.svg" width="96" alt="PASUPASASTRA logo">

# PASUPASASTRA Bridge · DJI Matrice 4T

**The app on the Matrice 4T's remote controller that connects the aircraft to the PASUPASASTRA
ground control station: telemetry at 10 Hz over WebSocket, the camera over RTMP, and, when the
pilot allows it, flight commands and KMZ waypoint missions from the GCS.**

[![CI](https://github.com/DarmawanWeb/pasupasastra-bridge-dji-matrice-4t/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/DarmawanWeb/pasupasastra-bridge-dji-matrice-4t/actions/workflows/ci.yml)
![DJI MSDK](https://img.shields.io/badge/DJI%20MSDK-v5.17-000000?logo=dji&logoColor=white)
![Flutter](https://img.shields.io/badge/Flutter-stable-02569B?logo=flutter&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-native%20bridge-7F52FF?logo=kotlin&logoColor=white)
![Android](https://img.shields.io/badge/Android-arm64%2C%20minSdk%2024-3DDC84?logo=android&logoColor=white)
![Protocol](https://img.shields.io/badge/PASUPASASTRA%20protocol-v2-39D0D8)
[![License](https://img.shields.io/badge/license-proprietary-B42318)](LICENSE)

[Features](#features) · [Commands](#commands) · [Build](#build) · [Install on the RC](#install-on-the-rc) ·
[Use](#use) · [Troubleshooting](#troubleshooting)

</div>

---

Part of [PASUPASASTRA](https://github.com/DarmawanWeb/Pasupasastra), mounted as `dji-matrice-4t/` in
[PasupasastraBridge](https://github.com/DarmawanWeb/PasupasastraBridge). It runs on the DJI RC Plus 2
Enterprise, talks to the aircraft through the DJI Mobile SDK v5.17 and to the
[GCS backend](https://github.com/DarmawanWeb/pasupasastra-gcs-be) through the
[bridge protocol](https://github.com/DarmawanWeb/PasupasastraBridge/blob/main/PROTOCOL.md). The UI
is Flutter; the SDK, the WebSocket and the video run natively in Kotlin inside a foreground service,
so the bridge keeps running when the app is in the background.

## Features

- **Telemetry at 10 Hz**: position, attitude, velocity, heading, satellites, battery, flying state,
  flight mode, motors and home point, with `hello` (capabilities), `ack` and events on the same
  socket.
- **Video**: RTMP push to MediaMTX with the MSDK's own `LiveStreamManager`, no decoding or
  re-encoding in the app; RGB, thermal or split (thermal and visual side by side) switched from the
  GCS.
- **Commands and missions**: takeoff, land, return to home, hold, go to point and waypoint missions
  built as KMZ (WPML, Waypoint V3) in the app.
- **Interlock**: nothing that moves the aircraft runs unless **Izinkan kendali dari GCS** is on;
  the GCS's safety limits are checked again here against the latest GPS fix (altitude 5–120 m, at
  most 1000 m away, 10 m/s, 99 waypoints).

## Commands

| GCS command | On the Matrice 4T (MSDK v5) |
|---|---|
| Arm, disarm | Not available separately in MSDK v5: motors start with takeoff and stop after landing; the bridge answers with that explanation |
| Takeoff | `KeyStartTakeoff`: climbs about 1.2 m and hovers |
| Land | `KeyStartAutoLanding` |
| Return to home | `KeyStartGoHome` |
| Hold | Pauses the mission, or `KeyStopGoHome` / `KeyStopAutoLanding` |
| Go to point | One-waypoint KMZ mission that ends in a hover |
| Waypoint mission | KMZ written in the app, `pushKMZFileToAircraft`, `startMission`; altitudes relative to the takeoff point, RC signal loss returns home |
| Lens | RGB, thermal or split, allowed without the interlock |
| Payload release | Not supported (needs a PSDK payload) |

The bridge answers every command with an `ack` (the server waits up to 10 s, 30 s for a mission)
whose message, in Bahasa Indonesia, is what the operator sees in the console.

## Build

Requirements: Flutter (stable), Android Studio with JDK 17 and the Android SDK, and an app key from
[developer.dji.com](https://developer.dji.com) registered for the package `com.darmawan.dronebridge`.
The MSDK comes from Maven Central.

```bash
cp android/local.properties.example android/local.properties   # sdk.dir, flutter.sdk, DJI_API_KEY
flutter pub get
flutter build apk --release --target-platform android-arm64
# -> build/app/outputs/flutter-apk/app-release.apk
```

The key is read from `android/local.properties` (ignored by git) or the `DJI_API_KEY` environment
variable and injected into the manifest at build time; never write it in `AndroidManifest.xml`.
`android/app/src/main/res/xml/accessory_filter.xml` is a placeholder: replace it with the original
from DJI's sample (`Mobile-SDK-Android-V5/SampleCode-V5/android-sdk-v5-as/sample/src/main/res/xml/`).

## Install on the RC

1. On the RC: **Settings → About**, tap **Build number** seven times, then enable
   **Developer options → USB debugging**.
2. Connect the RC to the computer with a USB-C data cable and accept the prompt on the RC.
3. Install and watch the log:
   ```bash
   adb devices
   adb install -r build/app/outputs/flutter-apk/app-release.apk
   adb logcat | grep -Ei "dji|m4t|Helper|SDKManager"
   ```

Without a computer, copy the APK to a microSD card or USB drive and open it from the RC's file
manager (allow installing unknown apps).

## Use

| Field | Value |
|---|---|
| WebSocket URL | `ws://<server>:8080/telemetry/m4t-001`; the last segment is the drone's ID in the console |
| RTMP URL | `rtmp://<server>:1935/m4t` |
| Izinkan kendali dari GCS | Off for telemetry only. On only while the pilot is ready to take over; it applies when the bridge starts |

Press **START BRIDGE**. In the console, register the drone with the same ID and add the same RTMP
URL as its video source. With `GCS_BRIDGE_TOKEN` set on the server, append `?token=<token>` to the
WebSocket URL. **STOP BRIDGE** only stops the bridge; the aircraft is not affected.

Before the first flight, test on the ground with the propellers off: the SDK shows `registered`,
the drone connects, telemetry and video appear in the console, the lens switches, and closing the
app leaves the sticks and the aircraft working normally. Then fly with a pilot and an observer
following your SOP.

- The MSDK can be held by one app at a time: close DJI Pilot 2 completely while the bridge runs.
  There is no Pilot 2 FPV view meanwhile, so fly within sight or watch the stream in the console.
- Turn off battery optimisation for the app so Android does not stop it.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Registration fails | Wrong app key, the package name differs from the one registered, or the RC has no internet |
| Crash in `Helper.install` | `android:name=".MApplication"` missing from the manifest |
| Build error about `.so` files or duplicates | Keep `useLegacyPackaging = true` and the `pickFirsts` in `android/app/build.gradle.kts` |
| Telemetry stays empty | The aircraft is not connected yet, or a key is named differently in your MSDK version |
| RTMP fails | Wrong URL or port 1935 closed; check the MediaMTX log |
| The bridge stops in the background | Battery optimisation is still on for the app |

## Structure

```
lib/                               Flutter UI: URLs, interlock, lens, live values, log
android/app/src/main/kotlin/com/darmawan/dronebridge/
  MApplication.kt, DjiSdk.kt       MSDK install, registration and product connection
  BridgeService.kt                 foreground service that owns the bridge
  TelemetryBridge.kt               10 Hz telemetry over WebSocket, lens and display commands
  DjiCommands.kt, DjiMission.kt    GCS commands with interlock and limits; plans to KMZ (WPML)
  VideoBridge.kt                   RTMP through LiveStreamManager
  MainActivity.kt, BridgeEvents.kt method and event channels to Flutter (m4t/bridge, m4t/events)
```

```bash
flutter analyze                    # also run by CI on every push
lefthook install                   # git hooks: analyze, conventional commits
```

Proprietary: © 2026 I Wayan Agus Darmawan (DarmawanWeb), all rights reserved. See [LICENSE](LICENSE).
The DJI Mobile SDK is subject to DJI's own terms.
