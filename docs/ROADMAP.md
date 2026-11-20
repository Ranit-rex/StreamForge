# StreamForge roadmap — Android only

## A0 — Android conversion
- [x] Remove desktop Qt/CMake product target.
- [x] Establish Android Gradle project and Compose UI.
- [x] Add browser PWA companion.
- [x] Add Android APK CI workflow.
- [x] Add RootEncoder integration dependency.
- [x] Add MediaProjection and foreground-service streaming path.
- [x] Add direct RTMP/RTMPS destination input.
- [x] Add local MP4 recording path.
- [ ] Verify clean APK build in CI and fix build errors.
- [ ] Test on physical Android devices.

## A1 — Reliable mobile broadcaster
- [ ] Add live preview and source controls.
- [ ] Add microphone/system-audio selection and monitoring.
- [ ] Add bitrate, resolution, FPS and orientation controls.
- [ ] Add connection health, dropped frames and reconnect.
- [ ] Securely persist stream profiles using Android Keystore.
- [ ] Improve service lifecycle and MediaProjection revocation handling.

## A2 — Studio features
- [x] Add a persistent on-device scene collection editor with Starting Soon, Gameplay, BRB and Ending presets.
- [x] Add source/layer management UI with visibility, lock, opacity, position, ordering, rename, duplicate and delete controls.
- [ ] Composite scene sources into the actual outgoing GL encoder frames (screen, camera, text and image assets).
- [ ] Add real image picking and camera capture sources; preview must match outgoing composition.
- [ ] Add local recording library, gallery export, playback and storage management.
- [ ] Support standalone recording without a live RTMP destination.
- [ ] Hotkeys / Bluetooth controls where Android permits.
- [ ] Stream presets and per-platform setup guides.

## A3 — Browser companion
- [ ] Pair browser PWA with installed APK using explicit local pairing.
- [ ] Add remote control over authenticated local network connections.
- [ ] Sync non-secret scene/profile settings.
- [ ] Keep remote control opt-in and local-network scoped.

## Release gate
A feature is complete only after successful builds and real-device validation. Do not claim browser-based Android screen capture. Do not ship APKs without testing actual platform ingest and foreground-service behavior.
