# SecurityCam

Turns an old Android phone into a WiFi security camera. Runs fully on-device — no
cloud service, no subscription, no SIM card needed.

## Features

- **On-device detection** of humans, vehicles, and animals using a TensorFlow Lite
  EfficientDet-Lite0 model (COCO classes) — works even with no internet connection.
- **Event-triggered recording**: saves a short video clip per detection, or falls back
  automatically to a still-frame burst on older camera hardware that can't record video
  and run live detection at the same time.
- **Alerts** via email (SMTP), [ntfy](https://ntfy.sh) push notifications, and a built-in
  local web page — all optional and each independent, so you can enable any combination
  (or none) and one failing doesn't block the others. Detection and recording work fine
  without any alert method configured.
- **Optional AI descriptions** of detections using the Google Gemini free tier.
- **Configurable**: per-category detection toggles and confidence thresholds, clip
  length, storage cap and age-based auto-delete, camera mounting-rotation correction,
  and more — all from an in-app settings screen.

## Requirements

- An Android phone/tablet running Android 8.0 (API 26) or newer, with a working camera
  and WiFi.
- No SIM card or cellular plan needed.
- Whether you get real video clips or a still-frame burst per event depends on what the
  device's camera hardware supports — the app detects this automatically at runtime (see
  [SETUP.md](SETUP.md)). Confirmed working: real video on a Samsung Galaxy Tab A (2016,
  Android 8.1); still-frame burst fallback on a Xiaomi Mi A1 (Android 9, LEGACY-level
  camera hardware).

## Building

```
./gradlew assembleRelease
```

Produces an installable APK at `app/build/outputs/apk/release/app-release.apk`.
Requires a JDK with `jlink` (a full JDK, not just a JRE) — see `gradle.properties` for
how the JDK location is configured.

## Setup

See [SETUP.md](SETUP.md) for the full walkthrough: installing the app, configuring
email/ntfy/Gemini alerts, tuning detection, and troubleshooting notes for specific
devices/camera hardware.

## Architecture

- `detect/` — TFLite detector wrapper, COCO-label-to-category mapping, and the
  frame-streak/cooldown trigger logic (unit tested).
- `service/MonitorService.kt` — foreground service owning the camera pipeline:
  live analysis, event-triggered recording, and alert dispatch.
- `alert/` — email, ntfy, and Gemini-description senders.
- `storage/EventStore.kt` — on-disk event layout and retention (size cap + age).
- `web/WebServer.kt` — the built-in LAN status page (NanoHTTPD).
- `ui/` + `MainActivity.kt` / `EventDetailActivity.kt` / `SettingsActivity.kt` — app UI.
