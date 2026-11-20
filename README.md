# StreamForge

**A free, local-first desktop broadcasting studio.**

StreamForge is an independent broadcasting application in development. Its core workflow runs on the user's computer and sends streams directly to YouTube, Kick, Twitch, Facebook Live and custom RTMP/RTMPS destinations.

## Product principles

- No mandatory StreamForge media servers or relay.
- No account required for basic local recording and direct streaming.
- No watermark or artificial time limit.
- Native desktop application for Windows, macOS and Linux.
- Free core software.
- Optional online integrations never block basic broadcasting.

## Current status

**Alpha 0.1.0 — desktop UI scaffold.**

The current application is a Qt 6 desktop shell. It is not yet a functioning capture, recording or streaming application. Output buttons remain disabled until libobs is integrated.

## Planned technology

- C++20
- Qt 6
- CMake
- OBS libobs
- FFmpeg where appropriate

## Build

Requirements: CMake 3.21+, a C++20 compiler and Qt 6.5+ Widgets development packages.

    cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
    cmake --build build --config Release

If Qt 6 is installed in a non-standard location, set CMAKE_PREFIX_PATH to the Qt installation prefix.

The first build does not require libobs; it builds the UI shell only. CMake can discover and link libobs when installed, but engine functionality is not implemented yet.

## Repository layout

- app/ — desktop application
- docs/ARCHITECTURE.md — system boundaries and security model
- docs/ROADMAP.md — implementation milestones

## Licensing

The desktop application is intended to use a GPL-compatible open-source distribution because the planned integration with OBS libobs carries GPL obligations. Third-party dependency notices and complete applicable license texts must be added and reviewed before public binary distribution. See docs/LICENSING.md.

## Contributing

Please open an issue before large changes. Keep platform-specific capture code behind interfaces and never commit stream keys, OAuth tokens, personal recordings or machine-specific configuration.
