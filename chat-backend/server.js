import http from "node:http";

const PORT = Number(process.env.PORT || 3000);
const API_KEY = (process.env.YOUTUBE_API_KEY || "").trim();
const cache = new Map();
const rate = new Map();
// Voice rooms are intentionally ephemeral: no database or persistent storage.
// A room is addressed by one six-digit roomId for both public and private rooms.
const voiceRooms = new Map();
const ROOM_TTL_MS = 3 * 60 * 60 * 1000;
const MAX_ROOM_MEMBERS = 6;

function cleanRooms() {
  const now = Date.now();
  for (const [id, room] of voiceRooms) {
    if (now - room.updatedAt > ROOM_TTL_MS || room.members.length === 0) voiceRooms.delete(id);
  }
}
function publicRoom(room) {
  return { roomId: room.roomId, name: room.name, visibility: room.visibility,
    memberCount: room.members.length, maxMembers: MAX_ROOM_MEMBERS,
    members: room.members.map(({ memberId, displayName, joinedAt }) => ({ memberId, displayName, joinedAt })) };
}
async function readJson(req) {
  let raw = "";
  for await (const chunk of req) {
    raw += chunk;
    if (raw.length > 4096) throw new Error("Request too large");
  }
  return raw ? JSON.parse(raw) : {};
}
function validRoomId(value) { return /^\d{6}$/.test(String(value || "")); }

function send(res, status, body) {
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
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
  const url = new URL(req.url || "/", "http://localhost");
  if (url.pathname.startsWith("/api/voice/")) {
    cleanRooms();
    try {
      if (req.method === "GET" && url.pathname === "/api/voice/rooms") {
        return send(res, 200, { rooms: [...voiceRooms.values()].filter(r => r.visibility === "public").map(publicRoom) });
      }
      if (req.method === "POST" && url.pathname === "/api/voice/rooms") {
        const body = await readJson(req);
        const visibility = body.visibility === "private" ? "private" : "public";
        const name = String(body.name || "Voice Room").trim().slice(0, 32) || "Voice Room";
        let roomId;
        do { roomId = String(Math.floor(100000 + Math.random() * 900000)); } while (voiceRooms.has(roomId));
        const memberId = String(body.memberId || "").slice(0, 64);
        const displayName = String(body.displayName || "Host").trim().slice(0, 24) || "Host";
        if (!memberId) return send(res, 400, { error: "Member ID is required" });
        const room = { roomId, name, visibility, createdAt: Date.now(), updatedAt: Date.now(),
          members: [{ memberId, displayName, joinedAt: Date.now() }] };
        voiceRooms.set(roomId, room);
        return send(res, 201, { room: publicRoom(room), you: { memberId } });
      }
      if (req.method === "POST" && url.pathname === "/api/voice/rooms/join") {
        const body = await readJson(req);
        const roomId = String(body.roomId || "");
        const room = voiceRooms.get(roomId);
        if (!validRoomId(roomId) || !room) return send(res, 404, { error: "Room not found. Check the Room ID." });
        const memberId = String(body.memberId || "").slice(0, 64);
        const displayName = String(body.displayName || "Guest").trim().slice(0, 24) || "Guest";
        if (!memberId) return send(res, 400, { error: "Member ID is required" });
        const existing = room.members.find(m => m.memberId === memberId);
        if (!existing && room.members.length >= MAX_ROOM_MEMBERS) return send(res, 409, { error: "This room is full." });
        if (!existing) room.members.push({ memberId, displayName, joinedAt: Date.now() });
        room.updatedAt = Date.now();
        return send(res, 200, { room: publicRoom(room), you: { memberId } });
      }
      if (req.method === "POST" && url.pathname === "/api/voice/rooms/leave") {
        const body = await readJson(req);
        const room = voiceRooms.get(String(body.roomId || ""));
        if (!room) return send(res, 200, { ok: true });
        room.members = room.members.filter(m => m.memberId !== String(body.memberId || ""));
        room.updatedAt = Date.now();
        if (room.members.length === 0) voiceRooms.delete(room.roomId);
        return send(res, 200, { ok: true });
      }
      if (req.method === "GET" && /^\/api\/voice\/rooms\/\d{6}$/.test(url.pathname)) {
        const room = voiceRooms.get(url.pathname.split("/").pop());
        return room ? send(res, 200, { room: publicRoom(room) }) : send(res, 404, { error: "Room not found." });
      }
      return send(res, 404, { error: "Not found" });
    } catch (error) {
      return send(res, 400, { error: error.message || "Invalid voice room request" });
    }
  }
  const ip = String(req.headers["x-forwarded-for"] || req.socket.remoteAddress || "unknown").split(",")[0].trim();
  const now = Date.now();
  const entry = rate.get(ip) || { start: now, count: 0 };
  if (now - entry.start > 60000) { entry.start = now; entry.count = 0; }
  if (++entry.count > 90) return send(res, 429, { error: "Too many requests. Try again shortly." });
  rate.set(ip, entry);
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
