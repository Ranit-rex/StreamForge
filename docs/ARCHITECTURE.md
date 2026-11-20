# StreamForge Android-first architecture

## Android APK
- Kotlin and Jetpack Compose interface.
- Android MediaProjection for user-approved screen capture.
- Foreground service for continued streaming while backgrounded.
- RootEncoder for hardware H.264/AAC encoding and RTMP/RTMPS transport.
- App-private recording storage by default.
- Direct-to-platform output; no StreamForge media relay.

## Browser PWA
- Static HTML, CSS and JavaScript.
- Installable responsive browser experience.
- Platform destination presets and setup guidance.
- Deep link into the Android application.
- No screen-capture claims, no streaming-key persistence, no video relay.

## Data path
Android screen -> MediaProjection -> ScreenSource -> RootEncoder / MediaCodec -> RTMP(S) -> selected platform ingest.

## Security
- Stream keys must not be sent to StreamForge servers.
- Do not persist stream keys in browser localStorage, IndexedDB or URLs.
- Android stream keys are held in the active app session only in this alpha.
- Use RTMPS where supported.
- MediaProjection consent is requested through Android system UI.
- Foreground service notification remains visible while broadcasting.

## Constraints
- Android system audio capture is OS-version and app-policy dependent; microphone capture is the baseline.
- Some apps prevent screen capture using secure-window flags.
- Platform ingest URLs and stream-key formats can change; custom RTMP/RTMPS endpoints are supported.
