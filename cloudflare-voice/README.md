# StreamForge Cloudflare Voice

Independent Cloudflare-only voice signaling backend. It has no Render endpoint, fallback, registry, or runtime dependency.

## Architecture
- Workers routes the HTTP and WebSocket API.
- One SQLite-backed Durable Object per room owns membership and WebRTC signaling.
- Durable Object WebSockets use the hibernation API.
- D1 stores the public-room directory only; media stays peer-to-peer between clients.
- Six participants maximum per room.

## Deploy
1. Install Wrangler and authenticate with `npx wrangler login`.
2. From this directory run `npx wrangler d1 create streamforge-voice-directory`.
3. Put the returned database ID into `wrangler.toml`.
4. Run `npx wrangler d1 migrations apply streamforge-voice-directory --remote`.
5. Run `npx wrangler deploy`.
6. Configure the returned Worker URL in the Android app as the sole voice API endpoint.

## Free-plan notes
Workers Free currently allows 100,000 requests/day and 10 ms CPU per Worker invocation. Durable Objects have separate daily request, duration, and SQLite row limits. WebSocket hibernation reduces idle duration, but it does not make active signaling unlimited. STUN-only ICE is configured initially; TURN relay is not included, so some NAT/network combinations may fail to establish peer connections until a TURN provider is configured.
