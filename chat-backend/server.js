import http from "node:http";

const PORT = Number(process.env.PORT || 3000);
const API_KEY = (process.env.YOUTUBE_API_KEY || "").trim();
const cache = new Map();
const rate = new Map();

function send(res, status, body) {
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type"
  });
  res.end(JSON.stringify(body));
}

async function youtube(path, params) {
  const url = new URL("https://www.googleapis.com/youtube/v3/" + path);
  for (const [key, value] of Object.entries({ ...params, key: API_KEY })) {
    if (value !== undefined && value !== "") url.searchParams.set(key, String(value));
  }
  const response = await fetch(url, { headers: { Accept: "application/json" }, signal: AbortSignal.timeout(12000) });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(body?.error?.message || "YouTube API request failed");
    error.status = response.status;
    throw error;
  }
  return body;
}

async function resolveChatId(videoId) {
  const cached = cache.get(videoId);
  if (cached && cached.expires > Date.now()) return cached.chatId;
  const data = await youtube("videos", { part: "liveStreamingDetails", id: videoId });
  const chatId = data.items?.[0]?.liveStreamingDetails?.activeLiveChatId;
  if (!chatId) {
    const error = new Error("No active YouTube Live Chat found. Make sure the broadcast is live and chat is enabled.");
    error.status = 404;
    throw error;
  }
  cache.set(videoId, { chatId, expires: Date.now() + 120000 });
  return chatId;
}

const server = http.createServer(async (req, res) => {
  if (req.method === "OPTIONS") return send(res, 204, {});
  const ip = String(req.headers["x-forwarded-for"] || req.socket.remoteAddress || "unknown").split(",")[0].trim();
  const now = Date.now();
  const entry = rate.get(ip) || { start: now, count: 0 };
  if (now - entry.start > 60000) { entry.start = now; entry.count = 0; }
  if (++entry.count > 90) return send(res, 429, { error: "Too many requests. Try again shortly." });
  rate.set(ip, entry);
  const url = new URL(req.url || "/", "http://localhost");
  if (req.method === "GET" && url.pathname === "/health") return send(res, 200, { ok: true, configured: Boolean(API_KEY) });
  if (req.method !== "GET" || url.pathname !== "/api/youtube/live-chat") return send(res, 404, { error: "Not found" });
  if (!API_KEY) return send(res, 503, { error: "StreamForge chat service is not configured yet." });
  const videoId = (url.searchParams.get("videoId") || "").trim();
  if (!/^[A-Za-z0-9_-]{11}$/.test(videoId)) return send(res, 400, { error: "Enter a valid YouTube LIVE video URL or 11-character video ID." });
  try {
    const liveChatId = await resolveChatId(videoId);
    const data = await youtube("liveChat/messages", {
      liveChatId, part: "snippet,authorDetails", maxResults: 200,
      pageToken: url.searchParams.get("pageToken") || ""
    });
    send(res, 200, data);
  } catch (error) {
    const status = Number(error.status) || 502;
    send(res, status >= 400 && status < 600 ? status : 502, { error: error.message || "Unable to fetch YouTube chat." });
  }
});

server.listen(PORT, "0.0.0.0", () => console.log(`StreamForge Chat API listening on ${PORT}`));
