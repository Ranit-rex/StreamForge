# StreamForge

**Browser-first live streaming studio, followed by a native Android broadcaster.**

StreamForge is being built web-first. The browser studio captures a shared screen, optionally mixes a microphone, records locally as WebM, and can broadcast to a WebRTC WHIP-compatible ingest endpoint. It is a static website/PWA and does not require a StreamForge media relay.

## Web studio (current priority)

- Screen/window/tab capture with live preview (HTTPS and browser permission required).
- Optional microphone capture.
- Local WebM recording.
- WebRTC WHIP output with optional bearer token.
- Responsive installable PWA.
- No account, no watermark, and no StreamForge relay.

**Protocol limitation:** browsers cannot open native RTMP/RTMPS connections directly. Standard RTMP destinations such as YouTube Live, Twitch and Kick therefore require a compatible WHIP ingest endpoint/gateway or the later Android APK. Do not paste an RTMP stream key into the WHIP fields.

## Deploy the website

The GitHub Actions workflow publishes the contents of `browser/` to GitHub Pages whenever browser files change on `main`. Enable GitHub Pages with **Source: GitHub Actions** in the repository's Pages settings if it is not already enabled. The resulting website URL is shown in the workflow's deployment environment.

## Android APK (later phase)

The Android project remains in `android/`. APK builds are intentionally manual while the web studio is stabilized. Run the **Android APK build** workflow from Actions when beginning the Android phase.

Requirements: JDK 17 and Android SDK platform 35.

    gradle --no-daemon :android:assembleDebug

The APK is an early alpha; compile, device, microphone, screen-capture and platform-ingest validation are still required.

## Streaming engine and licensing

The Android app uses RootEncoder for on-device encoding and RTMP/RTMPS transport. Review dependency notices and the complete license inventory before distributing builds.
