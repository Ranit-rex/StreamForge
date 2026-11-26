export class RoomSession {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
    this.sql = ctx.storage.sql;
    this.sql.exec(`
      CREATE TABLE IF NOT EXISTS room (
        room_id TEXT PRIMARY KEY, name TEXT NOT NULL, visibility TEXT NOT NULL,
        created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS members (
        member_id TEXT PRIMARY KEY, display_name TEXT NOT NULL, joined_at INTEGER NOT NULL,
        muted INTEGER NOT NULL DEFAULT 0, screen_sharing INTEGER NOT NULL DEFAULT 0,
        voice_token TEXT NOT NULL, disconnected_at INTEGER
      );
    `);
  }

  json(status, data) {
    return new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "access-control-allow-origin": "*" } });
  }
  room() {
    return this.sql.exec("SELECT * FROM room LIMIT 1").toArray()[0] || null;
  }
  members() {
    return this.sql.exec("SELECT member_id, display_name, joined_at, muted, screen_sharing, voice_token, disconnected_at FROM members ORDER BY joined_at").toArray();
  }
  publicRoom() {
    const room = this.room();
    if (!room) return null;
    const members = this.members();
    return { roomId: room.room_id, name: room.name, visibility: room.visibility, memberCount: members.length, maxMembers: 6,
      members: members.map(m => ({ memberId: m.member_id, displayName: m.display_name, joinedAt: m.joined_at, muted: Boolean(m.muted), screenSharing: Boolean(m.screen_sharing) })) };
  }
  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/signaling" && request.headers.get("Upgrade") === "websocket") return this.connect(request);
    if (request.method === "GET" && url.pathname === "/info") {
      const room = this.publicRoom();
      return room ? this.json(200, { room }) : this.json(404, { error: "Room not found." });
    }
    if (request.method === "POST" && url.pathname === "/create") {
      if (this.room()) return this.json(409, { error: "Room already exists." });
      const body = await request.json();
      const roomId = String(body.roomId || "");
      const memberId = String(body.memberId || "").slice(0, 64);
      if (!/^\\d{6}$/.test(roomId) || !memberId) return this.json(400, { error: "Invalid room or member ID." });
      const now = Date.now();
      const token = crypto.randomUUID() + crypto.randomUUID();
      this.sql.exec("INSERT INTO room (room_id,name,visibility,created_at,updated_at) VALUES (?,?,?,?,?)", roomId, String(body.name || "Voice Room").slice(0,32), body.visibility === "private" ? "private" : "public", now, now);
      this.sql.exec("INSERT INTO members (member_id,display_name,joined_at,voice_token) VALUES (?,?,?,?)", memberId, String(body.displayName || "Host").slice(0,24), now, token);
      await this.ctx.storage.setAlarm(now + 3 * 60 * 60 * 1000);
      return this.json(201, { room: this.publicRoom(), you: { memberId, voiceToken: token } });
    }
    if (request.method === "POST" && url.pathname === "/join") {
      const body = await request.json();
      const room = this.room();
      if (!room) return this.json(404, { error: "Room not found. Check the Room ID." });
      const memberId = String(body.memberId || "").slice(0,64);
      if (!memberId) return this.json(400, { error: "Member ID is required." });
      let member = this.members().find(m => m.member_id === memberId);
      if (!member && this.members().length >= 6) return this.json(409, { error: "This room is full." });
      if (!member) {
        const token = crypto.randomUUID() + crypto.randomUUID();
        this.sql.exec("INSERT INTO members (member_id,display_name,joined_at,voice_token) VALUES (?,?,?,?)", memberId, String(body.displayName || "Guest").slice(0,24), Date.now(), token);
        member = this.members().find(m => m.member_id === memberId);
      } else {
        this.sql.exec("UPDATE members SET disconnected_at=NULL, display_name=? WHERE member_id=?", String(body.displayName || member.display_name).slice(0,24), memberId);
        member = this.members().find(m => m.member_id === memberId);
      }
      this.sql.exec("UPDATE room SET updated_at=? WHERE room_id=?", Date.now(), room.room_id);
      return this.json(200, { room: this.publicRoom(), you: { memberId, voiceToken: member.voice_token } });
    }
    if (request.method === "POST" && url.pathname === "/leave") {
      const body = await request.json();
      this.removeMember(String(body.memberId || ""));
      return this.json(200, { ok: true, roomClosed: !this.room() });
    }
    return this.json(404, { error: "Not found." });
  }
  connect(request) {
    const url = new URL(request.url);
    const room = this.room();
    const memberId = url.searchParams.get("memberId") || "";
    const auth = request.headers.get("authorization") || "";
    const token = auth.startsWith("Bearer ") ? auth.slice(7) : (url.searchParams.get("token") || "");
    const member = this.members().find(m => m.member_id === memberId);
    if (!room || !member || !token || token !== member.voice_token) return new Response("Unauthorized", { status: 401 });
    const pair = new WebSocketPair();
    const client = pair[0], server = pair[1];
    this.ctx.acceptWebSocket(server, [memberId]);
    server.serializeAttachment({ roomId: room.room_id, memberId, displayName: member.display_name });
    this.sql.exec("UPDATE members SET disconnected_at=NULL WHERE member_id=?", memberId);
    const peers = this.ctx.getWebSockets().filter(ws => ws !== server).map(ws => ws.deserializeAttachment()).filter(a => a?.memberId);
    server.send(JSON.stringify({ type: "ready", selfId: memberId, peers: peers.map(p => {
      const m = this.members().find(x => x.member_id === p.memberId);
      return { memberId: p.memberId, displayName: p.displayName, muted: Boolean(m?.muted), screenSharing: Boolean(m?.screen_sharing) };
    }) }));
    this.broadcast({ type: "peer-joined", memberId, displayName: member.display_name, muted: Boolean(member.muted), screenSharing: Boolean(member.screen_sharing) }, memberId);
    return new Response(null, { status: 101, webSocket: client });
  }
  broadcast(message, exceptId = "") {
    for (const ws of this.ctx.getWebSockets()) {
      const a = ws.deserializeAttachment();
      if (a?.memberId !== exceptId) {
        try { ws.send(JSON.stringify(message)); } catch {}
      }
    }
  }
  webSocketMessage(ws, message) {
    let data;
    try { data = JSON.parse(typeof message === "string" ? message : new TextDecoder().decode(message)); } catch { return; }
    const a = ws.deserializeAttachment();
    if (!a?.memberId) return;
    const allowed = new Set(["offer", "answer", "ice", "speaking", "mute", "screen-share"]);
    if (!allowed.has(data.type)) return;
    if (["speaking","mute","screen-share"].includes(data.type)) {
      const active = Boolean(data.active);
      if (data.type === "mute") this.sql.exec("UPDATE members SET muted=? WHERE member_id=?", active ? 1 : 0, a.memberId);
      if (data.type === "screen-share") this.sql.exec("UPDATE members SET screen_sharing=? WHERE member_id=?", active ? 1 : 0, a.memberId);
      this.broadcast({ type: data.type, memberId: a.memberId, displayName: a.displayName, active }, a.memberId);
      return;
    }
    const targetId = String(data.to || "");
    const target = this.ctx.getWebSockets().find(peer => peer.deserializeAttachment()?.memberId === targetId);
    if (!target || targetId === a.memberId) return;
    const payload = data.type === "ice" ? { candidate: data.candidate } : { description: data.description };
    try { target.send(JSON.stringify({ type: data.type, from: a.memberId, ...payload })); } catch {}
  }
  webSocketClose(ws) {
    const a = ws.deserializeAttachment();
    if (!a?.memberId) return;
    this.sql.exec("UPDATE members SET disconnected_at=? WHERE member_id=?", Date.now(), a.memberId);
    this.broadcast({ type: "peer-left", memberId: a.memberId }, a.memberId);
    this.ctx.storage.setAlarm(Date.now() + 12_000);
  }
  webSocketError(ws) { try { ws.close(1011, "Socket error"); } catch {} }
  removeMember(memberId) {
    if (!memberId) return;
    this.sql.exec("DELETE FROM members WHERE member_id=?", memberId);
    for (const ws of this.ctx.getWebSockets()) {
      if (ws.deserializeAttachment()?.memberId === memberId) { try { ws.close(1000, "Member left"); } catch {} }
    }
    if (this.members().length === 0) {
      this.sql.exec("DELETE FROM room");
      this.ctx.storage.deleteAll();
    } else this.sql.exec("UPDATE room SET updated_at=?", Date.now());
  }
  async alarm() {
    const now = Date.now();
    for (const m of this.members()) {
      if (m.disconnected_at && now - m.disconnected_at >= 10_000) this.removeMember(m.member_id);
    }
    const room = this.room();
    if (room && now - room.updated_at > 3 * 60 * 60 * 1000) {
      for (const ws of this.ctx.getWebSockets()) { try { ws.close(1000, "Room expired"); } catch {} }
      this.sql.exec("DELETE FROM members");
      this.sql.exec("DELETE FROM room");
    }
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const cors = { "access-control-allow-origin": "*", "access-control-allow-methods": "GET,POST,DELETE,OPTIONS", "access-control-allow-headers": "Content-Type,Authorization" };
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
    const json = (status, data) => new Response(JSON.stringify(data), { status, headers: { ...cors, "content-type": "application/json; charset=utf-8", "cache-control": "no-store" } });
    if (url.pathname === "/api/voice/health") return json(200, { ok: true, service: "streamforge-cloudflare-voice", provider: "cloudflare", maxMembers: 6 });
    if (url.pathname === "/api/voice/servers") return json(200, { ok: true, servers: [{ id: "cloudflare-voice", provider: "cloudflare", endpoint: url.origin, enabled: true, weight: 1 }] });
    if (url.pathname === "/api/voice/ice-servers") return json(200, { iceServers: [{ urls: ["stun:stun.l.google.com:19302"] }], relayConfigured: false });
    if (url.pathname === "/api/voice/rooms" && request.method === "GET") {
      const rows = await env.DB.prepare("SELECT room_id,name,visibility,member_count,updated_at FROM rooms WHERE visibility='public' AND updated_at > ? ORDER BY updated_at DESC LIMIT 100").bind(Date.now() - 3 * 60 * 60 * 1000).all();
      return json(200, { rooms: rows.results.map(r => ({ roomId:r.room_id, name:r.name, visibility:r.visibility, memberCount:r.member_count, maxMembers:6, members:[] })) });
    }
    if (url.pathname === "/api/voice/rooms" && request.method === "POST") {
      const body = await request.json();
      const roomId = String(body.roomId || (Math.floor(100000 + Math.random()*900000)));
      const id = env.ROOMS.idFromName(roomId);
      const stub = env.ROOMS.get(id);
      const response = await stub.fetch("https://room.internal/create", { method:"POST", headers:{"content-type":"application/json"}, body:JSON.stringify({...body, roomId}) });
      const result = await response.clone().json().catch(()=>({}));
      if (response.ok) await env.DB.prepare("INSERT OR REPLACE INTO rooms (room_id,name,visibility,member_count,updated_at) VALUES (?,?,?,?,?)").bind(roomId, result.room.name, result.room.visibility, result.room.memberCount, Date.now()).run();
      return new Response(JSON.stringify(result), { status:response.status, headers:{...cors,"content-type":"application/json"} });
    }
    const joinMatch = url.pathname.match(/^\\/api\\/voice\\/rooms\\/join$/);
    if (joinMatch && request.method === "POST") {
      const body = await request.json(); const roomId = String(body.roomId || "");
      const stub = env.ROOMS.get(env.ROOMS.idFromName(roomId));
      const response = await stub.fetch("https://room.internal/join", { method:"POST", headers:{"content-type":"application/json"}, body:JSON.stringify(body) });
      const result = await response.clone().json().catch(()=>({}));
      if (response.ok && result.room) await env.DB.prepare("UPDATE rooms SET member_count=?,updated_at=? WHERE room_id=?").bind(result.room.memberCount,Date.now(),roomId).run();
      return new Response(JSON.stringify(result), { status:response.status, headers:{...cors,"content-type":"application/json"} });
    }
    const roomMatch = url.pathname.match(/^\\/api\\/voice\\/rooms\\/(\\d{6})$/);
    if (roomMatch && request.method === "GET") {
      const stub = env.ROOMS.get(env.ROOMS.idFromName(roomMatch[1]));
      return stub.fetch("https://room.internal/info");
    }
    if (url.pathname === "/api/voice/rooms/leave" && request.method === "POST") {
      const body = await request.json(); const roomId = String(body.roomId || "");
      const stub = env.ROOMS.get(env.ROOMS.idFromName(roomId));
      const response = await stub.fetch("https://room.internal/leave", { method:"POST", headers:{"content-type":"application/json"}, body:JSON.stringify(body) });
      const result = await response.clone().json().catch(()=>({}));
      if (result.roomClosed) await env.DB.prepare("DELETE FROM rooms WHERE room_id=?").bind(roomId).run();
      else {
        const info = await (await stub.fetch("https://room.internal/info")).json().catch(()=>null);
        if (info?.room) await env.DB.prepare("UPDATE rooms SET member_count=?,updated_at=? WHERE room_id=?").bind(info.room.memberCount,Date.now(),roomId).run();
      }
      return new Response(JSON.stringify(result), { status:response.status, headers:{...cors,"content-type":"application/json"} });
    }
    const wsMatch = url.pathname.match(/^\\/api\\/voice\\/signaling$/);
    if (wsMatch && request.headers.get("Upgrade") === "websocket") {
      const roomId = url.searchParams.get("roomId") || "";
      const stub = env.ROOMS.get(env.ROOMS.idFromName(roomId));
      const target = new URL("https://room.internal/signaling");
      target.search = url.search;
      return stub.fetch(new Request(target, request));
    }
    return json(404, { error:"Not found" });
  }
};
