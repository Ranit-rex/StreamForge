# StreamForge architecture

## Product boundary
StreamForge is a local-first broadcasting application. The desktop client captures, composes, encodes and sends media directly to the user's chosen platform ingest endpoint. StreamForge does not operate a mandatory media relay.

## Desktop data path
1. Capture: display, game/window, camera and audio devices.
2. Composition: scenes, sources, transforms, filters and transitions.
3. Audio: mixing, monitoring, filters and track routing.
4. Encoding: hardware encoder when supported; software fallback.
5. Output: direct RTMP/RTMPS or platform-specific supported output.

## Core technology
- C++20
- Qt 6 for native desktop UI
- OBS libobs for media graph, capture, composition, encoding and output
- FFmpeg where appropriate for media utilities and interoperability
- CMake for cross-platform builds

Do not duplicate libobs functionality unnecessarily. Keep platform-specific capture and encoder integrations behind engine interfaces.

## Optional integrations
Accounts, OAuth, chat, alerts, cloud sync and remote control are optional integrations. Basic local capture, recording and direct RTMP streaming must work without a StreamForge account or StreamForge-operated server.

## Security requirements
- Store stream keys in the operating system credential store, never in plaintext config files.
- Never log stream keys, OAuth tokens or RTMP URLs containing secrets.
- Require explicit consent before remote-control pairing.
- Authenticate and encrypt remote-control sessions.
- Do not expose local control endpoints to the public internet by default.

## Current implementation status
The initial application is a Qt desktop shell only. It does not yet capture media, encode, record or stream. Output controls remain disabled until the engine is integrated.
