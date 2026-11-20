# StreamForge

**Free, Android-first broadcasting studio with a browser companion.**

StreamForge is being developed as a native Android broadcaster. It captures the Android screen, encodes on-device and sends directly to YouTube, Kick, Twitch or any compatible RTMP/RTMPS destination. No StreamForge media relay is required.

## Product targets

- Android APK: native screen capture, microphone audio, local recording and direct streaming.
- Browser PWA: responsive companion studio for destination setup, platform guides and app handoff.
- No mandatory StreamForge account or relay server.
- No watermark or artificial streaming time limit.
- Free core application.

The browser companion does not claim to capture the Android screen or stream by itself. Android browser screen-capture support is restricted; the APK handles MediaProjection and foreground streaming.

## Build Android APK

Requirements: JDK 17 and Android SDK platform 35.

    gradle --no-daemon :android:assembleDebug

APK output:

    android/build/outputs/apk/debug/android-debug.apk

GitHub Actions builds and uploads a debug APK artifact on pushes and pull requests.

## Browser companion

Serve the browser directory from any static HTTPS host. It is an installable PWA. It deliberately does not save stream keys in browser storage.

## Streaming engine

The Android app uses RootEncoder for on-device hardware encoding and RTMP/RTMPS transport. RootEncoder is Apache-2.0 licensed. Review dependency notices and the complete license inventory before distributing builds.

## Current alpha

- Native Android Compose studio UI
- MediaProjection permission flow
- Foreground screen-streaming service
- Direct RTMP/RTMPS destination input
- Local MP4 recording path
- Browser PWA companion
- Automated Android debug APK build

This is an early alpha. A successful build and real-device/platform-ingest validation are required before production claims.
