# Current implementation status

The desktop shell and libobs lifecycle wrapper are present.

- The application initializes and shuts down libobs when a compatible SDK/library is found at configure time.
- When libobs is absent, the application still builds as a UI-only shell and reports that the SDK is unavailable.
- No capture sources, audio graph, preview renderer, encoder, recording output or RTMP output are implemented yet.
- Streaming and recording controls intentionally remain disabled until those features exist.
