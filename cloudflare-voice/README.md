# StreamForge Cloudflare Voice

Independent Cloudflare-only voice signaling backend. No Render endpoint, fallback, registry, or runtime dependency.

## Architecture
- Cloudflare Worker handles the HTTP and WebSocket API.
- One SQLite-backed Durable Object per room owns membership and WebRTC signaling.
- A separate SQLite-backed Durable Object stores the public-room directory.
- Durable Object WebSockets use Cloudflare's WebSocket hibernation API.
- Maximum six participants per room. Audio/video media is peer-to-peer; Cloudflare carries signaling, not the media stream.

## Deploy
1. Install Node.js and Wrangler: `npm install -g wrangler`.
2. Authenticate to your Cloudflare account: `wrangler login`.
3. From this directory run: `wrangler deploy`.
4. Copy the Worker URL returned by Wrangler.
5. Configure that URL as the sole voice API endpoint in the Android app; no Render endpoint is used.

No D1 database or external server is required.

## Free-plan considerations
Workers Free has 100,000 requests/day and 10 ms CPU per Worker invocation. SQLite-backed Durable Objects have their own request, duration, and storage limits. WebSocket hibernation reduces idle duration, but it does not make active signaling unlimited. STUN-only ICE is configured initially; TURN relay is not included, so some NAT/network combinations may fail to establish peer connections until a TURN provider is configured.
