# StreamForge Chat API

Small server-side proxy for the official YouTube Data API v3. The Google API key is read only from the Render environment variable `YOUTUBE_API_KEY`; it is never shipped in the Android APK.

## Run

- `npm ci`
- Set `YOUTUBE_API_KEY` in the server environment.
- `npm start`

## Endpoints

- `GET /health` — health and configured status (never returns the key).
- `GET /api/youtube/live-chat?videoId=VIDEO_ID&pageToken=...` — resolves the active chat ID from the live video and returns messages plus YouTube's next page token and polling interval.

The public endpoint applies a basic per-IP request limit. Restrict the Google API key to YouTube Data API v3 and monitor its quota in Google Cloud.
