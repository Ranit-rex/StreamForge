import http from "node:http";
import { randomBytes, timingSafeEqual, createHmac } from "node:crypto";
import { WebSocketServer, WebSocket } from "ws";

const PORT = Number(process.env.PORT || 3000);
const API_KEY = (process.env.YOUTUBE_API_KEY || "").trim();
const cache = new Map();
const rate = new Map();
// Voice rooms are intentionally ephemeral: no database or persistent storage.
// A room is addressed by one six-digit roomId for both public and private rooms.
const voiceRooms = new Map();
const ROOM_TTL_MS = 3 * 60 * 60 * 1000;
const SERVICE_SLOT = Math.min(7, Math.max(1, Number(process.env.VOICE_SERVICE_SLOT || 1)));
const ROOM_QUOTAS = [0, 72, 72, 72, 71, 71, 71, 71];
const MAX_ROOMS_PER_SERVICE = ROOM_QUOTAS[SERVICE_SLOT];
const MAX_ROOM_MEMBERS = 6;
const voiceSockets = new Map();
const memberDisconnectTimers = new Map();
const MEMBER_RECONNECT_GRACE_MS = 10000;
const wss = new WebSocketServer({ noServer: true, maxPayload: 64 * 1024, perMessageDeflate: false });
const SIGNAL_TYPES = new Set(["offer", "answer", "ice", "speaking", "mute", "screen-share"]);
const DEFAULT_VOICE_SERVERS = [
  { id: "render-legacy", provider: "render", region: "singapore", endpoint: "https://streamforge-chat-api.onrender.com", enabled: true, weight: 1 },
  { id: "render-a", provider: "render", region: "singapore", endpoint: "https://streamforge-signal-a.onrender.com", enabled: true, weight: 1 },
  { id: "render-b", provider: "render", region: "singapore", endpoint: "https://streamforge-signal-b.onrender.com", enabled: true, weight: 1 },
  { id: "render-c", provider: "render", region: "singapore", endpoint: "https://streamforge-signal-c.onrender.com", enabled: true, weight: 1 },
  { id: "render-d", provider: "render", region: "singapore", endpoint: "https://streamforge-signal-d.onrender.com", enabled: true, weight: 1 },
  { id: "render-e", provider: "render", region: "singapore", endpoint: "https://streamforge-signal-e.onrender.com", enabled: true, weight: 1 },
  { id: "render-f", provider: "render", region: "singapore", endpoint: "https://streamforge-signal-f.onrender.com", enabled: true, weight: 1 }
];

function getVoiceServerRegistry() {
  let configured = [];
  try {
    const parsed = JSON.parse(process.env.VOICE_SERVER_REGISTRY || "[]");
    if (Array.isArray(parsed)) configured = parsed;
  } catch { /* Keep the safe built-in registry when config is invalid. */ }
  const source = configured.length ? configured : DEFAULT_VOICE_SERVERS;
  const normalized = source.map(item => {
    let endpoint = String(item?.endpoint || "").trim();
    while (endpoint.endsWith("/")) endpoint = endpoint.slice(0, -1);
    return {
      id: String(item?.id || endpoint),
      provider: String(item?.provider || "custom"),
      region: String(item?.region || "unknown"),
      endpoint,
      enabled: item?.enabled !== false,
      weight: Math.max(1, Number(item?.weight) || 1)
    };
  });
  const seen = new Set();
  return normalized.filter(item => {
    if (!item.enabled || !item.endpoint.startsWith("https://") || seen.has(item.endpoint)) return false;
    seen.add(item.endpoint);
    return true;
  }).map(({ enabled, ...item }) => item);
}


function removeVoiceMember(roomId, memberId, reason = "Member left room") {
  const room = voiceRooms.get(roomId);
  if (!room) return;
  const timerKey = roomId + ":" + memberId;
  const timer = memberDisconnectTimers.get(timerKey);
  if (timer) clearTimeout(timer);
  memberDisconnectTimers.delete(timerKey);
  room.members = room.members.filter(member => member.memberId !== memberId);
  const peers = voiceSockets.get(roomId);
  const socket = peers?.get(memberId);
  if (socket) { peers.delete(memberId); socket.close(1000, reason); }
  for (const peer of peers?.values() || []) {
    if (peer.readyState === WebSocket.OPEN) peer.send(JSON.stringify({ type: "peer-left", memberId }));
  }
  if (room.members.length === 0) {
    voiceRooms.delete(roomId);
    for (const peer of peers?.values() || []) peer.close(1000, "Room closed");
    voiceSockets.delete(roomId);
    console.log(JSON.stringify({ event: "voice_room_closed", roomId, reason: "empty" }));
  } else { room.updatedAt = Date.now(); }
}
function cleanRooms() {
  const now = Date.now();
  for (const [id, room] of voiceRooms) {
    if (now - room.updatedAt > ROOM_TTL_MS || room.members.length === 0) voiceRooms.delete(id);
  }
}
function publicRoom(room) {
  return { roomId: room.roomId, name: room.name, visibility: room.visibility,
    memberCount: room.members.length, maxMembers: MAX_ROOM_MEMBERS,
    members: room.members.map(({ memberId, displayName, joinedAt, muted, screenSharing }) => ({ memberId, displayName, joinedAt, muted: Boolean(muted), screenSharing: Boolean(screenSharing) })) };
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
      if (req.method === "GET" && url.pathname === "/api/voice/health") {
        const connectedMembers = [...voiceSockets.values()].reduce((sum, peers) => sum + peers.size, 0);
        return send(res, 200, {
          ok: true, service: "streamforge-chat-api", voiceSignaling: "ready",
          serviceSlot: SERVICE_SLOT, roomCount: voiceRooms.size, roomQuota: MAX_ROOMS_PER_SERVICE,
          connectedMembers, turnConfigured: true,
          timestamp: new Date().toISOString()
        });
      }
      if (req.method === "GET" && url.pathname === "/api/voice/servers") {
        return send(res, 200, {
          ok: true,
          servers: getVoiceServerRegistry(),
          updatedAt: new Date().toISOString()
        });
      }
      if (req.method === "GET" && url.pathname === "/api/voice/ice-servers") {
        // Built-in Open Relay TURN credentials: no Render environment variables
        // or user-managed provider account are required. Credentials are short-lived.
        const username = String(Math.floor(Date.now() / 1000) + 3600) + ":streamforge";
        const credential = createHmac("sha1", "openrelayprojectsecret")
          .update(username).digest("base64");
        const iceServers = [
          { urls: ["stun:stun.l.google.com:19302", "stun:staticauth.openrelay.metered.ca:80"] },
          {
            urls: [
              "turn:staticauth.openrelay.metered.ca:80",
              "turn:staticauth.openrelay.metered.ca:443",
              "turn:staticauth.openrelay.metered.ca:80?transport=tcp",
              "turns:staticauth.openrelay.metered.ca:443?transport=tcp"
            ],
            username,
            credential
          }
        ];
        return send(res, 200, { iceServers, relayConfigured: true });
      }
      if (req.method === "GET" && url.pathname === "/api/voice/rooms") {
        return send(res, 200, { rooms: [...voiceRooms.values()].filter(r => r.visibility === "public").map(publicRoom) });
      }
      if (req.method === "POST" && url.pathname === "/api/voice/rooms") {
        const body = await readJson(req);
        const visibility = body.visibility === "private" ? "private" : "public";
        const name = String(body.name || "Voice Room").trim().slice(0, 32) || "Voice Room";
        if (voiceRooms.size >= MAX_ROOMS_PER_SERVICE) {
          return send(res, 429, { error: "This Party node has reached its room quota. Please retry to use another node." });
        }
        const requestedRoomId = String(body.roomId || "");
        let roomId;
        if (requestedRoomId) {
          if (!/^[1-7]\d{5}$/.test(requestedRoomId) || Number(requestedRoomId[0]) !== SERVICE_SLOT) {
            return send(res, 400, { error: "Room code does not belong to this Party node." });
          }
          if (voiceRooms.has(requestedRoomId)) return send(res, 409, { error: "Room code collision. Please retry." });
          roomId = requestedRoomId;
        } else {
          do { roomId = String(Math.floor(100000 + Math.random() * 900000)); } while (voiceRooms.has(roomId));
        }
        const memberId = String(body.memberId || "").slice(0, 64);
        const displayName = String(body.displayName || "Host").trim().slice(0, 24) || "Host";
        if (!memberId) return send(res, 400, { error: "Member ID is required" });
        const room = { roomId, name, visibility, createdAt: Date.now(), updatedAt: Date.now(),
          members: [{ memberId, displayName, joinedAt: Date.now(), muted: false, screenSharing: false, voiceToken: randomBytes(24).toString("base64url") }] };
        voiceRooms.set(roomId, room);
        return send(res, 201, { room: publicRoom(room), you: { memberId, voiceToken: room.members[0].voiceToken } });
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
        if (!existing) room.members.push({ memberId, displayName, joinedAt: Date.now(), muted: false, screenSharing: false, voiceToken: randomBytes(24).toString("base64url") });
        room.updatedAt = Date.now();
        return send(res, 200, { room: publicRoom(room), you: { memberId, voiceToken: room.members.find(m => m.memberId === memberId).voiceToken } });
      }
      if (req.method === "POST" && url.pathname === "/api/voice/rooms/leave") {
        const body = await readJson(req);
        const room = voiceRooms.get(String(body.roomId || ""));
        if (!room) return send(res, 200, { ok: true });
        const leavingId = String(body.memberId || "");
        removeVoiceMember(room.roomId, leavingId, "Member left room");
        return send(res, 200, { ok: true, roomClosed: !voiceRooms.has(room.roomId) });
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


server.on("upgrade", (req, socket, head) => {
  const url = new URL(req.url || "/", "http://localhost");
  if (url.pathname !== "/api/voice/signaling") {
    socket.destroy();
    return;
  }
  const roomId = url.searchParams.get("roomId") || "";
  const memberId = url.searchParams.get("memberId") || "";
  const authHeader = String(req.headers.authorization || "");
  const token = authHeader.startsWith("Bearer ") ? authHeader.slice(7) : "";
  const room = voiceRooms.get(roomId);
  const member = room?.members.find(m => m.memberId === memberId);
  const expected = Buffer.from(member?.voiceToken || "");
  const supplied = Buffer.from(token);
  const validToken = expected.length > 0 && expected.length === supplied.length && timingSafeEqual(expected, supplied);
  if (!validRoomId(roomId) || !member || !validToken) {
    socket.write("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n");
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, ws => wss.emit("connection", ws, req, { roomId, memberId }));
});

wss.on("connection", (ws, _req, identity) => {
  const { roomId, memberId } = identity;
  const room = voiceRooms.get(roomId);
  let peers = voiceSockets.get(roomId);
  if (!peers) { peers = new Map(); voiceSockets.set(roomId, peers); }
  const existingPeers = [...peers.entries()].filter(([id]) => id !== memberId).map(([id, peer]) => ({
    memberId: id, displayName: peer.displayName, muted: Boolean(room?.members.find(m => m.memberId === id)?.muted), screenSharing: Boolean(room?.members.find(m => m.memberId === id)?.screenSharing)
  }));
  const member = room?.members.find(m => m.memberId === memberId);
  if (!room || !member) { ws.close(1008, "Room membership expired"); return; }
  ws.memberId = memberId;
  ws.roomId = roomId;
  ws.displayName = member.displayName;
  ws.isAlive = true;
  const disconnectTimerKey = roomId + ":" + memberId;
  const pendingDisconnect = memberDisconnectTimers.get(disconnectTimerKey);
  if (pendingDisconnect) clearTimeout(pendingDisconnect);
  memberDisconnectTimers.delete(disconnectTimerKey);
  const previousSocket = peers.get(memberId);
  if (previousSocket && previousSocket !== ws) previousSocket.close(4001, "Replaced by a newer connection");
  peers.set(memberId, ws);
  console.log(JSON.stringify({ event: "voice_socket_connected", roomId, memberId, peerCount: existingPeers.length }));
  ws.send(JSON.stringify({ type: "ready", selfId: memberId, peers: existingPeers }));
  for (const [id, peer] of peers) {
    if (id !== memberId && peer.readyState === WebSocket.OPEN) {
      peer.send(JSON.stringify({ type: "peer-joined", memberId, displayName: member.displayName, muted: Boolean(member.muted), screenSharing: Boolean(member.screenSharing) }));
    }
  }

  ws.on("pong", () => { ws.isAlive = true; });
  ws.on("message", raw => {
    let message;
    try { message = JSON.parse(raw.toString()); } catch { return; }
    if (!message || !SIGNAL_TYPES.has(message.type)) return;
    if (message.type === "speaking" || message.type === "mute" || message.type === "screen-share") {
      const active = Boolean(message.active);
      const roomMember = room?.members.find(m => m.memberId === memberId);
      if (message.type === "mute" && roomMember) roomMember.muted = active;
      if (message.type === "screen-share" && roomMember) roomMember.screenSharing = active;
      for (const [id, peer] of peers) {
        if (id !== memberId && peer.readyState === WebSocket.OPEN) {
          peer.send(JSON.stringify({ type: message.type, memberId, displayName: ws.displayName, active }));
        }
      }
      return;
    }
    const targetId = String(message.to || "");
    const target = peers.get(targetId);
    if (!target || targetId === memberId || target.readyState !== WebSocket.OPEN) {
      console.warn(JSON.stringify({ event: "voice_signal_target_unavailable", roomId, from: memberId, to: targetId, signalType: message.type, targetExists: Boolean(target) }));
      return;
    }
    // Forward only SDP/ICE payloads; never trust a client-supplied sender identity.
    const payload = message.type === "ice"
      ? { candidate: message.candidate }
      : { description: message.description };
    target.send(JSON.stringify({ type: message.type, from: memberId, ...payload }));
  });

  ws.on("close", () => {
    const current = voiceSockets.get(roomId);
    // Ignore close events from a previous socket replaced by a reconnect.
    if (current?.get(memberId) !== ws) return;
    current.delete(memberId);
    if (current.size === 0) voiceSockets.delete(roomId);
    const roomMember = voiceRooms.get(roomId)?.members.find(m => m.memberId === memberId);
    if (roomMember) roomMember.screenSharing = false;
    for (const peer of current?.values() || []) {
      if (peer.readyState === WebSocket.OPEN) peer.send(JSON.stringify({ type: "peer-left", memberId }));
    }
    // Brief grace period lets mobile clients reconnect without losing membership.
    const timerKey = roomId + ":" + memberId;
    const timer = setTimeout(() => {
      memberDisconnectTimers.delete(timerKey);
      if (!voiceSockets.get(roomId)?.has(memberId)) removeVoiceMember(roomId, memberId, "Disconnected");
    }, MEMBER_RECONNECT_GRACE_MS);
    timer.unref?.();
    memberDisconnectTimers.set(timerKey, timer);
  });
  ws.on("error", () => ws.terminate());
});

const heartbeat = setInterval(() => {
  for (const peers of voiceSockets.values()) {
    for (const ws of peers.values()) {
      if (!ws.isAlive) { ws.terminate(); continue; }
      ws.isAlive = false;
      ws.ping();
    }
  }
}, 25000);
heartbeat.unref();

server.listen(PORT, "0.0.0.0", () => console.log(`StreamForge Chat API listening on ${PORT}`));
