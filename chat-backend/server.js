import express from "express";

const app = express();
const PORT = Number(process.env.PORT || 3000);
const API_KEY = (process.env.YOUTUBE_API_KEY || "").trim();
const cache = new Map();
const rate = new Map();

app.disable("x-powered-by");
app.use((req, res, next) => {
  res.setHeader("Cache-Control", "no-store");
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type");
  if (req.method === "OPTIONS") return res.sendStatus(204);
  const ip = req.headers["x-forwarded-for"]?.split(",")[0]?.trim() || req.socket.remoteAddress || "unknown";
  const now = Date.now();
  const entry = rate.get(ip) || { start: now, count: 0 };
  if (now - entry.start > 60_000) { entry.start = now; entry.count = 0; }
  entry.count += 1;
  rate.set(ip, entry);
  if (entry.count > 90) return res.status(429).json({ error: "Too many requests. Try again shortly." });
  next();
});

app.get("/health", (_req, res) => res.json({ ok: true, configured: Boolean(API_KEY) }));

async function youtube(path, params) {
  const url = new URL("https://www.googleapis.com/youtube/v3/" + path);
  for (const [key, value] of Object.entries({ ...params, key: API_KEY })) {
    if (value !== undefined && value !== "") url.searchParams.set(key, String(value));
  }
  const response = await fetch(url, { headers: { Accept: "application/json" }, signal: AbortSignal.timeout(12_000) });
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
    const error = new Error("No active YouTube Live Chat found for this video. Make sure the broadcast is live and chat is enabled.");
    error.status = 404;
    throw error;
  }
  cache.set(videoId, { chatId, expires: Date.now() + 120_000 });
  return chatId;
}

app.get("/api/youtube/live-chat", async (req, res) => {
  if (!API_KEY) return res.status(503).json({ error: "StreamForge chat service is not configured yet." });
  const videoId = String(req.query.videoId || "").trim();
  const pageToken = String(req.query.pageToken || "").trim();
  if (!/^[A-Za-z0-9_-]{11}$/.test(videoId)) return res.status(400).json({ error: "Enter a valid YouTube LIVE video URL or 11-character video ID." });
  try {
    const liveChatId = await resolveChatId(videoId);
    const data = await youtube("liveChat/messages", {
      liveChatId, part: "snippet,authorDetails", maxResults: 200, pageToken
    });
    res.json(data);
  } catch (error) {
    const status = Number(error.status) || 502;
    res.status(status >= 400 && status < 600 ? status : 502).json({ error: error.message || "Unable to fetch YouTube chat." });
  }
});

app.listen(PORT, "0.0.0.0", () => console.log(`StreamForge Chat API listening on ${PORT}`));
