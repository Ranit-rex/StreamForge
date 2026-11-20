# StreamForge development roadmap

## Milestone 0 — Repository and desktop shell
- [x] Establish Qt 6 / CMake desktop application skeleton
- [x] Create first dark studio workspace
- [x] Document local-first, direct-to-platform architecture
- [ ] Add CI builds for Windows, macOS and Linux
- [ ] Add dependency and third-party license inventory

## Milestone 1 — libobs engine integration
- [ ] Pin a supported OBS Studio/libobs version
- [ ] Add reproducible dependency setup for all desktop platforms
- [ ] Initialize and shut down libobs safely
- [ ] Add engine diagnostics and structured logging
- [ ] Add a real preview render surface

## Milestone 2 — Capture and scenes
- [ ] Display capture
- [ ] Game/window capture
- [ ] Camera capture
- [ ] Audio input/output capture
- [ ] Scene/source CRUD and persistence
- [ ] Source transforms, crop and visibility

## Milestone 3 — Audio and recording
- [ ] Audio mixer and meters
- [ ] Audio filters and monitoring
- [ ] Hardware/software encoder selection
- [ ] Local recording and output profiles
- [ ] Multi-track audio

## Milestone 4 — Direct streaming
- [ ] RTMP/RTMPS output
- [ ] User-configurable service and server URL
- [ ] Secure stream-key storage via OS credential vault
- [ ] Stream health, dropped frames and reconnect
- [ ] YouTube, Kick, Twitch and custom RTMP setup guides

## Milestone 5 — Studio features
- [ ] Studio mode and transitions
- [ ] Hotkeys
- [ ] Replay buffer
- [ ] Virtual camera
- [ ] Browser sources and overlays
- [ ] Plugin API

## Milestone 6 — Cross-platform and companion apps
- [ ] Windows stable
- [ ] macOS stable
- [ ] Linux stable
- [ ] Android remote control and broadcaster
- [ ] iOS remote control and broadcaster

## Milestone 7 — Optional ecosystem
- [ ] Unified chat and alerts
- [ ] Optional OAuth integrations
- [ ] Optional encrypted settings sync
- [ ] Optional authenticated remote control
- [ ] Optional direct multi-output support

## Release gates
A milestone is complete only after clean builds on claimed platforms, automated core tests, real hardware capture/record/stream validation, dependency license review, secret-safe logs and documented limitations.

Unchecked items are not implemented.
