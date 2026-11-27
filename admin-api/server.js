import http from "node:http";
import { MongoClient } from "mongodb";
import crypto from "node:crypto";
import webpush from "web-push";
import { initializeApp, cert, getApps } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getMessaging } from "firebase-admin/messaging";
import { getFirestore } from "firebase-admin/firestore";

const PORT = Number(process.env.PORT || 3000);
const URI = (process.env.MONGODB_URI || "").trim();
const DB_NAME = process.env.MONGODB_DATABASE || "streamforge";
function readServiceAccount() {
  const raw = (process.env.FIREBASE_SERVICE_ACCOUNT_CASTDOO || process.env.FIREBASE_SERVICE_ACCOUNT_CASTDOO_JSON || process.env.FIREBASE_SERVICE_ACCOUNT_JSON || "").trim();
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw);
    if (parsed.project_id && parsed.project_id !== "castdoo") {
      console.error("firebase_admin_project_mismatch", parsed.project_id);
      return null;
    }
    return parsed;
  } catch (error) {
    console.error("firebase_admin_credentials_invalid_json", error.message);
    return null;
  }
}
const SERVICE_ACCOUNT = readServiceAccount();
if (SERVICE_ACCOUNT && !getApps().length) initializeApp({ credential: cert(SERVICE_ACCOUNT), projectId: "castdoo" });
const PAIRING_CODE = (process.env.CASTDOO_ADMIN_PAIRING_CODE || "").trim();
const ADMIN_API_KEY = (process.env.ADMIN_API_KEY || "").trim();
const VAPID_PUBLIC_KEY = (process.env.WEB_PUSH_PUBLIC_KEY || "").trim();
const VAPID_PRIVATE_KEY = (process.env.WEB_PUSH_PRIVATE_KEY || "").trim();
const VAPID_SUBJECT = (process.env.WEB_PUSH_SUBJECT || "mailto:support@castdoo.app").trim();
const pushConfigured = () => Boolean(VAPID_PUBLIC_KEY && VAPID_PRIVATE_KEY);
if (pushConfigured()) webpush.setVapidDetails(VAPID_SUBJECT, VAPID_PUBLIC_KEY, VAPID_PRIVATE_KEY);
let client;
let db;
let connecting;
const pairingFailures = new Map();

async function database() {
  if (!URI) throw Object.assign(new Error("MongoDB connection is not configured on the admin service."), { status: 503 });
  if (db) return db;
  if (!connecting) connecting = (async () => {
    const c = new MongoClient(URI, { serverSelectionTimeoutMS: 8000, maxPoolSize: 5 });
    await c.connect();
    client = c;
    db = c.db(DB_NAME);
    await Promise.all([
      db.collection("admin_announcements").createIndex({ createdAt: -1 }),
      db.collection("admin_events").createIndex({ createdAt: -1 }),
      db.collection("admin_audit_logs").createIndex({ createdAt: -1 }),
      db.collection("push_devices").createIndex({ uid: 1, endpoint: 1 }, { unique: true, partialFilterExpression: { endpoint: { $type: "string" } } }),
      db.collection("admin_devices").createIndex({ tokenHash: 1 }, { unique: true }),
      db.collection("notifications").createIndex({ uid: 1, createdAt: -1 }),
      db.collection("notification_campaigns").createIndex({ createdAt: -1 }),
      db.collection("app_users").createIndex({ lastSeenAt: -1 }),
      db.collection("castdoo_profiles").createIndex({ uid: 1 }, { unique: true }),
      db.collection("castdoo_profiles").createIndex({ email: 1 }, { unique: true }),
      db.collection("castdoo_friend_requests").createIndex({ requestId: 1 }, { unique: true }),
      db.collection("castdoo_friend_requests").createIndex({ recipientEmail: 1, status: 1, createdAt: -1 }),
      db.collection("castdoo_friend_requests").createIndex({ senderUid: 1, status: 1, createdAt: -1 })
    ]);
    return db;
  })().catch(error => { connecting = null; throw error; });
  return connecting;
}
function send(res, status, data) {
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "access-control-allow-origin": "*", "access-control-allow-headers": "content-type,authorization,x-admin-key", "access-control-allow-methods": "GET,POST,PATCH,DELETE,OPTIONS" });
  res.end(JSON.stringify(data));
}
async function body(req) {
  let raw = "";
  for await (const chunk of req) { raw += chunk; if (raw.length > 20000) throw Object.assign(new Error("Request too large"), { status: 413 }); }
  return raw ? JSON.parse(raw) : {};
}
async function firebaseUser(req) {
  if (!SERVICE_ACCOUNT || !getApps().length) throw Object.assign(new Error("CastDoo Firebase Admin service-account credentials are missing on the server."), {status:503});
  const match = String(req.headers.authorization || "").match(/^Bearer\s+(.+)$/i);
  if (!match) return null;
  try { return await getAuth().verifyIdToken(match[1]); } catch { return null; }
}
async function googleProfile(req) {
  const match = String(req.headers.authorization || "").match(/^Bearer\s+(.+)$/i);
  if (!match) throw Object.assign(new Error("Sign in with Google to continue."), { status: 401 });
  const response = await fetch("https://www.googleapis.com/oauth2/v3/userinfo", {
    headers: { authorization: "Bearer " + match[1] },
    signal: AbortSignal.timeout(8000)
  });
  if (!response.ok) throw Object.assign(new Error("Google sign-in expired. Please reconnect your account."), { status: 401 });
  const data = await response.json();
  if (!data.sub || !data.email || data.email_verified !== true) {
    throw Object.assign(new Error("Google did not provide a verified account identity."), { status: 401 });
  }
  return {
    uid: String(data.sub),
    email: String(data.email).trim().toLowerCase(),
    name: String(data.name || data.given_name || "CastDoo user").trim().slice(0, 80),
    photoUrl: String(data.picture || "").startsWith("https://") ? String(data.picture).slice(0, 1000) : ""
  };
}
async function syncGoogleProfile(d, user) {
  await d.collection("castdoo_profiles").updateOne(
    { uid: user.uid },
    { $set: { email: user.email, name: user.name, photoUrl: user.photoUrl, updatedAt: new Date() },
      $setOnInsert: { uid: user.uid, friends: [], createdAt: new Date() } },
    { upsert: true }
  );
  return d.collection("castdoo_profiles").findOne({ uid: user.uid });
}
async function friendRoutes(req, res, url) {
  const user = await googleProfile(req);
  const d = await database();
  const profiles = d.collection("castdoo_profiles");
  const requests = d.collection("castdoo_friend_requests");
  const me = await syncGoogleProfile(d, user);

  if (req.method === "GET" && url.pathname === "/api/friends") {
    const ids = Array.isArray(me.friends) ? me.friends.slice(0, 6) : [];
    const friends = ids.length ? await profiles.find({ uid: { $in: ids } }, { projection: { uid: 1, email: 1, name: 1, photoUrl: 1 } }).toArray() : [];
    const [incoming, outgoing] = await Promise.all([
      requests.find({ recipientEmail: user.email, status: "pending" }).sort({ createdAt: -1 }).limit(30).toArray(),
      requests.find({ senderUid: user.uid, status: "pending" }).sort({ createdAt: -1 }).limit(30).toArray()
    ]);
    const senderIds = [...new Set(incoming.map(item => item.senderUid))];
    const senders = senderIds.length ? await profiles.find({ uid: { $in: senderIds } }, { projection: { uid: 1, email: 1, name: 1, photoUrl: 1 } }).toArray() : [];
    const senderMap = new Map(senders.map(item => [item.uid, item]));
    return send(res, 200, {
      profile: { uid: user.uid, email: user.email, name: user.name, photoUrl: user.photoUrl },
      friends: friends.map(({ _id, ...item }) => item),
      incomingRequests: incoming.map(item => ({ requestId: item.requestId, createdAt: item.createdAt, sender: senderMap.get(item.senderUid) || { uid: item.senderUid, name: "CastDoo user", email: "" } })),
      outgoingRequests: outgoing.map(item => ({ requestId: item.requestId, recipientEmail: item.recipientEmail, createdAt: item.createdAt })),
      friendLimit: 6
    });
  }

  if (req.method === "POST" && url.pathname === "/api/friends/request") {
    const input = await body(req);
    const email = String(input.email || "").trim().toLowerCase();
    if (!/^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$/.test(email) || email.length > 254) return send(res, 400, { error: "Enter a valid Google account email." });
    if (email === user.email) return send(res, 400, { error: "You cannot add your own account." });
    if ((me.friends || []).length >= 6) return send(res, 409, { error: "Friend limit reached. CastDoo allows up to 6 friends." });
    const target = await profiles.findOne({ email }, { projection: { uid: 1, friends: 1 } });
    if (target && (target.friends || []).includes(user.uid)) return send(res, 409, { error: "You are already friends." });
    const existing = await requests.findOne({ senderUid: user.uid, recipientEmail: email, status: "pending" });
    if (existing) return send(res, 409, { error: "A friend request is already pending." });
    const reverse = await requests.findOne({ senderUid: target?.uid || "", recipientEmail: user.email, status: "pending" });
    if (reverse) return send(res, 409, { error: "This person has already sent you a request. Accept it from Incoming requests." });
    const requestId = crypto.randomUUID();
    await requests.insertOne({ requestId, senderUid: user.uid, senderEmail: user.email, recipientUid: target?.uid || null,
      recipientEmail: email, status: "pending", createdAt: new Date() });
    return send(res, 201, { ok: true, requestId, message: "Friend request sent." });
  }

  if (req.method === "POST" && url.pathname === "/api/friends/requests/accept") {
    const input = await body(req);
    const requestId = String(input.requestId || "");
    if (!requestId || requestId.length > 80) return send(res, 400, { error: "Invalid friend request." });
    const request = await requests.findOne({ requestId, recipientEmail: user.email, status: "pending" });
    if (!request) return send(res, 404, { error: "Friend request not found or already handled." });
    const sender = await profiles.findOne({ uid: request.senderUid });
    if (!sender) return send(res, 404, { error: "The sender profile is no longer available." });
    const session = client.startSession();
    try {
      await session.withTransaction(async () => {
        const currentMe = await profiles.findOne({ uid: user.uid }, { session });
        const currentSender = await profiles.findOne({ uid: request.senderUid }, { session });
        if (!currentMe || !currentSender) throw Object.assign(new Error("Both users must have an active CastDoo profile."), { status: 409 });
        if ((currentMe.friends || []).includes(request.senderUid)) {
          await requests.updateOne({ requestId, status: "pending" }, { $set: { status: "accepted", handledAt: new Date() } }, { session });
          return;
        }
        const max = { $expr: { $lt: [{ $size: { $ifNull: ["$friends", []] } }, 6] } };
        const a = await profiles.updateOne({ uid: user.uid, ...max }, { $addToSet: { friends: request.senderUid }, $set: { updatedAt: new Date() } }, { session });
        const b = await profiles.updateOne({ uid: request.senderUid, ...max }, { $addToSet: { friends: user.uid }, $set: { updatedAt: new Date() } }, { session });
        if (a.modifiedCount !== 1 || b.modifiedCount !== 1) throw Object.assign(new Error("One of the accounts has reached the 6 friend limit."), { status: 409 });
        await requests.updateOne({ requestId, status: "pending" }, { $set: { status: "accepted", handledAt: new Date() } }, { session });
      });
      return send(res, 200, { ok: true, message: "Friend added." });
    } finally {
      await session.endSession();
    }
  }

  if (req.method === "POST" && url.pathname === "/api/friends/requests/decline") {
    const input = await body(req);
    const requestId = String(input.requestId || "");
    const result = await requests.updateOne({ requestId, recipientEmail: user.email, status: "pending" }, { $set: { status: "declined", handledAt: new Date() } });
    return result.modifiedCount ? send(res, 200, { ok: true }) : send(res, 404, { error: "Friend request not found or already handled." });
  }

  const removeMatch = url.pathname.startsWith("/api/friends/") ? url.pathname.slice("/api/friends/".length) : "";
  if (req.method === "DELETE" && removeMatch) {
    const friendUid = decodeURIComponent(removeMatch).slice(0, 200);
    if (friendUid === user.uid) return send(res, 400, { error: "Invalid friend." });
    const session = client.startSession();
    try {
      await session.withTransaction(async () => {
        await profiles.updateOne({ uid: user.uid }, { $pull: { friends: friendUid }, $set: { updatedAt: new Date() } }, { session });
        await profiles.updateOne({ uid: friendUid }, { $pull: { friends: user.uid }, $set: { updatedAt: new Date() } }, { session });
      });
      return send(res, 200, { ok: true });
    } finally {
      await session.endSession();
    }
  }
  return send(res, 404, { error: "Friend endpoint not found." });
}
async function authorized(req) {
  const apiKey = String(req.headers["x-admin-key"] || "");
  if (ADMIN_API_KEY && apiKey && apiKey.length === ADMIN_API_KEY.length &&
      crypto.timingSafeEqual(Buffer.from(apiKey), Buffer.from(ADMIN_API_KEY))) return true;
  const match = String(req.headers.authorization || "").match(/^Bearer\s+(.+)$/i);
  if (!match) return false;
  const bearer = match[1];
  if (bearer.length >= 40 && URI) {
    try {
      const d = await database();
      const tokenHash = crypto.createHash("sha256").update(bearer).digest("hex");
      const device = await d.collection("admin_devices").findOne({ tokenHash, revokedAt: null });
      if (device) {
        await d.collection("admin_devices").updateOne({ _id: device._id }, { $set: { lastSeenAt: new Date() } });
        return true;
      }
    } catch { return false; }
  }
  const decoded = await firebaseUser(req);
  return decoded?.admin === true;
}
async function handler(req,res) {
  if (req.method === "OPTIONS") return send(res,204,{});
  const url = new URL(req.url,"http://localhost");
  if (url.pathname === "/health") return send(res,200,{ok:true,service:"streamforge-admin-api",databaseConfigured:Boolean(URI),authConfigured:Boolean(SERVICE_ACCOUNT),firebaseProject:SERVICE_ACCOUNT?.project_id || null});
  if (url.pathname === "/api/friends" || url.pathname.startsWith("/api/friends/")) {
    try { return await friendRoutes(req, res, url); }
    catch (error) {
      console.error("castdoo_friends_request_failed", error.message);
      return send(res, Number(error.status) || 500, { error: error.message || "Friends service is temporarily unavailable." });
    }
  }
  // Legacy Render release/version API retired. The Cloudflare version service is now authoritative.
  if (url.pathname.startsWith("/api/releases/")) {
    if (req.method === "GET" && /^\/api\/releases\/(stable|beta|admin)$/.test(url.pathname)) {
      const track = url.pathname.split("/").pop();
      return send(res, 200, { track, versionName: "", versionCode: 1, minimumVersionCode: 1, forceUpdate: false, updateType: "mini", updateUrl: "", releaseNotes: "", disabled: true });
    }
    return send(res, 410, { error: "Legacy Render release/version management has been retired." });
  }

  if (req.method === "POST" && url.pathname === "/api/admin/pair") {
    try {
      if (!PAIRING_CODE) return send(res,503,{error:"Admin device pairing is not configured on the server"});
      const input = await body(req);
      const supplied = String(input.code || "").trim();
      const ip = String(req.socket.remoteAddress || "unknown");
      const attempt = pairingFailures.get(ip) || {count:0,startedAt:Date.now()};
      if (Date.now() - attempt.startedAt > 10 * 60 * 1000) { attempt.count = 0; attempt.startedAt = Date.now(); }
      if (attempt.count >= 8) return send(res,429,{error:"Too many pairing attempts. Try again in 10 minutes."});
      if (!supplied || supplied.length !== PAIRING_CODE.length ||
          !crypto.timingSafeEqual(Buffer.from(supplied), Buffer.from(PAIRING_CODE))) {
        attempt.count += 1;
        pairingFailures.set(ip, attempt);
        return send(res,401,{error:"Invalid pairing code"});
      }
      pairingFailures.delete(ip);
      const d = await database();
      const token = crypto.randomBytes(32).toString("base64url");
      const now = new Date();
      await d.collection("admin_devices").insertOne({
        tokenHash: crypto.createHash("sha256").update(token).digest("hex"),
        label: String(input.label || "CastDoo Admin Android").slice(0,80),
        createdAt: now, lastSeenAt: now, revokedAt: null
      });
      return send(res,200,{paired:true,token});
    } catch(error) { return send(res,error.status||500,{error:error.status?error.message:"Device pairing failed"}); }
  }

  if (req.method === "POST" && url.pathname === "/api/usage/session") {
    try {
      const identity = await firebaseUser(req);
      if (!identity?.uid) return send(res,SERVICE_ACCOUNT ? 401 : 503,{error:SERVICE_ACCOUNT ? "Sign in required" : "CastDoo Firebase Admin credentials are not configured on the server."});
      const input = await body(req);
      const d = await database();
      const now = new Date();
      const previous = await d.collection("app_users").findOne({uid:identity.uid},{projection:{lastSeenAt:1}});
      await d.collection("app_users").updateOne(
        {uid:identity.uid},
        {$set:{uid:identity.uid,platform:String(input.platform || "android").slice(0,24),device:String(input.device || "").slice(0,100),lastSeenAt:now},$setOnInsert:{firstSeenAt:now}},
        {upsert:true}
      );
      const lastSeen = previous?.lastSeenAt ? new Date(previous.lastSeenAt).getTime() : 0;
      if (!lastSeen || now.getTime() - lastSeen >= 10 * 60 * 1000) {
        await d.collection("admin_events").insertOne({type:"app.session",uid:identity.uid,platform:String(input.platform || "android").slice(0,24),createdAt:now});
      }
      return send(res,200,{tracked:true,source:"authenticated_app_session"});
    } catch(error) { return send(res,error.status||500,{error:error.status?error.message:"App session tracking failed"}); }
  }

  if (req.method === "POST" && url.pathname === "/api/devices/fcm/register") {
    try {
      const identity = await firebaseUser(req);
      if (!identity?.uid) return send(res,SERVICE_ACCOUNT ? 401 : 503,{error:SERVICE_ACCOUNT ? "Sign in required" : "CastDoo Firebase Admin credentials are not configured on the server."});
      const input = await body(req);
      const token = String(input.token || "").trim();
      if (token.length < 40 || token.length > 4096) return send(res,400,{error:"A valid Firebase messaging token is required"});
      const d = await database();
      const now = new Date();
      await d.collection("push_devices").updateOne(
        {uid:identity.uid,endpoint:`fcm:${token}`},
        {$set:{uid:identity.uid,endpoint:`fcm:${token}`,fcmToken:token,token,platform:"android",transport:"fcm",enabled:true,updatedAt:now,lastSeenAt:now},$setOnInsert:{createdAt:now}},
        {upsert:true}
      );
      await d.collection("app_users").updateOne(
        {uid:identity.uid},
        {$set:{uid:identity.uid,platform:"android",lastSeenAt:now},$setOnInsert:{firstSeenAt:now}},
        {upsert:true}
      );
      await d.collection("admin_events").insertOne({type:"push.fcm_device_registered",uid:identity.uid,platform:"android",createdAt:now});
      return send(res,200,{registered:true,transport:"FCM",tracked:true});
    } catch(error) { return send(res,error.status||500,{error:error.status?error.message:"FCM device registration failed"}); }
  }
  if (req.method === "POST" && url.pathname === "/api/devices/register") {
    try {
      const identity = await firebaseUser(req);
      if (!identity?.uid) return send(res,401,{error:"Sign in required"});
      const input = await body(req);
      const endpoint = String(input.endpoint || "").trim();
      const p256dh = String(input.p256dh || "").trim();
      const auth = String(input.auth || "").trim();
      if (!endpoint.startsWith("https://") || endpoint.length > 4096 ||
          !p256dh || p256dh.length > 512 || !auth || auth.length > 256) {
        return send(res,400,{error:"A valid UnifiedPush endpoint and Web Push keys are required"});
      }
      const d = await database();
      await d.collection("push_devices").updateOne(
        {uid: identity.uid, endpoint},
        {$set:{uid:identity.uid,endpoint,keys:{p256dh,auth},instance:String(input.instance || "default"),platform:"android",enabled:true,updatedAt:new Date()},$setOnInsert:{createdAt:new Date()}},
        {upsert:true}
      );
      return send(res,200,{registered:true});
    } catch(error) { return send(res,error.status||500,{error:error.status?error.message:"Device registration failed"}); }
  }
  if (req.method === "POST" && url.pathname === "/api/admin/unpair") {
    try {
      const match = String(req.headers.authorization || "").match(/^Bearer\s+(.+)$/i);
      if (!match) return send(res,401,{error:"Admin device token required"});
      const tokenHash = crypto.createHash("sha256").update(match[1]).digest("hex");
      const d = await database();
      const result = await d.collection("admin_devices").updateOne({tokenHash,revokedAt:null},{$set:{revokedAt:new Date()}});
      return send(res,result.modifiedCount ? 200 : 404,{revoked:Boolean(result.modifiedCount)});
    } catch(error) { return send(res,error.status||500,{error:error.status?error.message:"Device unpair failed"}); }
  }
  if (!url.pathname.startsWith("/api/")) return send(res,404,{error:"Not found"});
  if (!(await authorized(req))) return send(res,401,{error:"Admin account not authorized"});
  if (!URI) {
    const defaults={maintenanceMode:false,maintenanceMessage:"",features:{announcements:true}};
    if (req.method==="GET" && url.pathname==="/api/overview") return send(res,200,{announcements:0,events24h:0,config:defaults,storageConfigured:false});
    if (req.method==="GET" && url.pathname==="/api/announcements") return send(res,200,[]);
    if (req.method==="GET" && url.pathname==="/api/config") return send(res,200,defaults);
    return send(res,503,{error:"MongoDB is not connected yet. Add MONGODB_URI in this Render service to enable saving."});
  }
  try {
    const d = await database();

    if (req.method === "GET" && url.pathname === "/api/push/status") {
      const registeredDevices = await d.collection("push_devices").countDocuments({enabled:true,$or:[{fcmToken:{$type:"string"}},{endpoint:{$type:"string"}}]});
      const fcmDevices = await d.collection("push_devices").countDocuments({enabled:true,fcmToken:{$type:"string"}});
      const registeredUsers = await d.collection("push_devices").distinct("uid",{enabled:true,$or:[{fcmToken:{$type:"string"}},{endpoint:{$type:"string"}}]});
      let totalUsers = null;
      let totalUsersSource = "unavailable";
      if (SERVICE_ACCOUNT) {
        try { const count = await getFirestore().collection("users").count().get(); totalUsers = count.data().count; totalUsersSource = "firebase_firestore"; }
        catch (error) { console.error("firebase_user_count_failed",error.message); }
      }
      if (totalUsers === null) {
        totalUsers = await d.collection("app_users").countDocuments();
        totalUsersSource = "observed_signed_in_sessions";
      }
      const unifiedPushDevices = await d.collection("push_devices").countDocuments({enabled:true,endpoint:{$type:"string"},fcmToken:{$exists:false}});
      return send(res,200,{configured:Boolean(SERVICE_ACCOUNT) || pushConfigured(),fcmConfigured:Boolean(SERVICE_ACCOUNT),firebaseAdminConfigured:Boolean(SERVICE_ACCOUNT),firebaseProject:SERVICE_ACCOUNT?.project_id || null,totalUsers,totalUsersSource,registeredUsers:registeredUsers.length,registeredDevices,fcmDevices,unifiedPushDevices,transport:"Firebase Cloud Messaging + Web Push"});
    }
    if (req.method === "POST" && url.pathname === "/api/push/send") {
      const input = await body(req);
      const title = String(input.title || "").trim().slice(0,100);
      const message = String(input.message || input.body || "").trim().slice(0,2000);
      if (!title || !message) return send(res,400,{error:"Title and message are required"});
      if (!SERVICE_ACCOUNT && !pushConfigured()) return send(res,503,{error:"Push delivery is not configured. Connect Firebase Admin credentials or configure Web Push keys."});
      const targetUids = Array.isArray(input.targetUids) ? [...new Set(input.targetUids.map(x=>String(x).trim()).filter(Boolean))].slice(0,500) : [];
      if (input.audience === "selected" && !targetUids.length) return send(res,400,{error:"Select at least one valid user UID"});
      const filter = {enabled:true,$or:[{fcmToken:{$type:"string"}},{endpoint:{$type:"string"}}],... (input.audience === "selected" ? {uid:{$in:targetUids}} : {})};
      const rows = await d.collection("push_devices").find(filter).toArray();
      const campaignId = crypto.randomUUID();
      const campaign = {campaignId,title,message,audience:input.audience === "selected" ? "selected" : "all",targetUids,priority:input.priority === "important" ? "high" : "normal",actionUrl:String(input.actionUrl || "").startsWith("https://") ? String(input.actionUrl).slice(0,400) : "",createdAt:new Date(),deviceCount:rows.length,status:"sending"};
      await d.collection("notification_campaigns").insertOne(campaign);
      let sent = 0, failed = 0;
      for (const device of rows) {
        const notification = {title,body:message.slice(0,700),campaignId,actionUrl:campaign.actionUrl.slice(0,400),priority:campaign.priority};
        try {
          if (device.fcmToken) {
            if (!SERVICE_ACCOUNT) throw new Error("Firebase Cloud Messaging is not configured");
            await getMessaging().send({token:device.fcmToken,notification:{title,body:notification.body},data:{campaignId,actionUrl:notification.actionUrl,priority:notification.priority}});
          } else if (device.endpoint && device.keys && pushConfigured()) {
            await webpush.sendNotification({endpoint:device.endpoint,keys:device.keys},JSON.stringify(notification),{TTL:86400,urgency:campaign.priority === "high" ? "high" : "normal"});
          } else { throw new Error("Device has no supported push transport"); }
          sent++;
          await d.collection("notifications").insertOne({uid:device.uid,...notification,read:false,createdAt:new Date(),deliveryStatus:"sent"});
          if (SERVICE_ACCOUNT && device.uid) {
            try { await getFirestore().collection("users").doc(device.uid).collection("notifications").add({title:notification.title,body:notification.body,read:false,createdAt:new Date(),campaignId,actionUrl:notification.actionUrl}); }
            catch (firestoreError) { console.error("push_inbox_write_failed",firestoreError.message); }
          }
        } catch (error) {
          failed++;
          const code = String(error.code || "");
          const status = Number(error.statusCode || 0);
          if (status === 404 || status === 410 || code.includes("registration-token-not-registered") || code.includes("invalid-registration-token")) await d.collection("push_devices").updateOne({_id:device._id},{$set:{enabled:false,disabledAt:new Date()}});
          await d.collection("notifications").insertOne({uid:device.uid,...notification,read:false,createdAt:new Date(),deliveryStatus:"failed",deliveryError:String(error.message || "Delivery failed").slice(0,300)});
        }
      }
      await d.collection("notification_campaigns").updateOne({campaignId},{$set:{status:"sent",sent,failed,completedAt:new Date()}});
      await d.collection("admin_audit_logs").insertOne({action:"push.sent",campaignId,sent,failed,createdAt:new Date()});
      return send(res,200,{configured:true,sent,failed,devices:rows.length,campaignId});
    }
    if (req.method === "GET" && url.pathname === "/api/admin/devices") {
      const devices = await d.collection("push_devices").find({}).sort({updatedAt:-1}).limit(200).toArray();
      return send(res,200,{devices:devices.map(device=>({
        uid:device.uid || null,
        platform:device.platform || "android",
        transport:device.fcmToken ? "FCM" : "UnifiedPush",
        enabled:device.enabled === true,
        createdAt:device.createdAt || null,
        updatedAt:device.updatedAt || null,
        lastSeenAt:device.lastSeenAt || null
      }))});
    }
    if (req.method === "GET" && url.pathname === "/api/admin/campaigns") {
      const campaigns = await d.collection("notification_campaigns").find({}).sort({createdAt:-1}).limit(50).toArray();
      return send(res,200,{campaigns:campaigns.map(({_id,...campaign})=>campaign)});
    }
    if (req.method === "GET" && url.pathname === "/api/admin/audit") {
      const entries = await d.collection("admin_audit_logs").find({}).sort({createdAt:-1}).limit(100).toArray();
      return send(res,200,{entries:entries.map(({_id,...entry})=>entry)});
    }
    if (req.method === "GET" && url.pathname === "/api/analytics/summary") {
      const since = new Date(Date.now() - 24 * 60 * 60 * 1000);
      const [observedUsers,activeUsers24h,registeredDevices,registeredUsers,profileUsers,pushSent,pushFailed] = await Promise.all([
        d.collection("app_users").countDocuments(),
        d.collection("app_users").countDocuments({lastSeenAt:{$gte:since}}),
        d.collection("push_devices").countDocuments({enabled:true,$or:[{fcmToken:{$type:"string"}},{endpoint:{$type:"string"}}]}),
        d.collection("push_devices").distinct("uid",{enabled:true,uid:{$type:"string"},$or:[{fcmToken:{$type:"string"}},{endpoint:{$type:"string"}}]}),
        d.collection("castdoo_profiles").countDocuments(),
        d.collection("notification_campaigns").aggregate([{$match:{createdAt:{$gte:since}}},{$group:{_id:null,total:{$sum:{$ifNull:["$sent",0]}}}}]).toArray(),
        d.collection("notification_campaigns").aggregate([{$match:{createdAt:{$gte:since}}},{$group:{_id:null,total:{$sum:{$ifNull:["$failed",0]}}}}]).toArray()
      ]);
      let totalUsers = observedUsers;
      let totalUsersSource = "observed_signed_in_sessions";
      if (SERVICE_ACCOUNT) {
        try {
          const count = await getFirestore().collection("users").count().get();
          totalUsers = count.data().count;
          totalUsersSource = "firebase_firestore_users";
        } catch (error) {
          console.error("analytics_firestore_user_count_failed", error.message);
        }
      }
      if (!totalUsers) {
        totalUsers = Math.max(observedUsers, registeredUsers.length, profileUsers);
        if (totalUsers > 0) totalUsersSource = profileUsers > 0 ? "castdoo_google_profiles" : "observed_sessions_or_registered_devices";
      }
      return send(res,200,{totalUsers,totalUsersSource,profileUsers,activeUsers24h,registeredDevices,registeredUsers:registeredUsers.length,pushSent24h:pushSent[0]?.total||0,pushFailed24h:pushFailed[0]?.total||0,hourlyEvents:[]});
    }
    if (req.method === "GET" && url.pathname === "/api/overview") {
      const [announcements, events, config] = await Promise.all([
        d.collection("admin_announcements").countDocuments(),
        d.collection("admin_events").countDocuments({ createdAt: { $gte: new Date(Date.now()-86400000) } }),
        d.collection("app_config").findOne({ _id:"global" })
      ]);
      return send(res,200,{announcements,events24h:events,config:config || {maintenanceMode:false,maintenanceMessage:"",features:{announcements:true}}});
    }
    if (req.method === "GET" && url.pathname === "/api/announcements") {
      const rows = await d.collection("admin_announcements").find({}).sort({createdAt:-1}).limit(100).toArray();
      return send(res,200,rows);
    }
    if (req.method === "POST" && url.pathname === "/api/announcements") {
      const input=await body(req);
      const title=String(input.title||"").trim(), message=String(input.message||"").trim();
      if (!title || !message) return send(res,400,{error:"Title and message are required"});
      const doc={title:title.slice(0,100),message:message.slice(0,2000),audience:["all","android"].includes(input.audience)?input.audience:"all",active:input.active!==false,createdAt:new Date(),updatedAt:new Date()};
      const result=await d.collection("admin_announcements").insertOne(doc);
      await d.collection("admin_audit_logs").insertOne({action:"announcement.created",targetId:result.insertedId,createdAt:new Date()});
      return send(res,201,{...doc,_id:result.insertedId});
    }
    const announcementMatch=url.pathname.match(/^\/api\/announcements\/([a-f0-9]{24})$/);
    if (announcementMatch && req.method==="DELETE") {
      const result=await d.collection("admin_announcements").deleteOne({_id:new (await import("mongodb")).ObjectId(announcementMatch[1])});
      return send(res,result.deletedCount?200:404,{deleted:Boolean(result.deletedCount)});
    }
    if (req.method==="GET" && url.pathname==="/api/config") {
      return send(res,200,await d.collection("app_config").findOne({_id:"global"}) || {maintenanceMode:false,maintenanceMessage:"",features:{announcements:true}});
    }
    if (req.method==="PATCH" && url.pathname==="/api/config") {
      const input=await body(req);
      const allowed={maintenanceMode:Boolean(input.maintenanceMode),maintenanceMessage:String(input.maintenanceMessage||"").slice(0,300),features:{announcements:input.features?.announcements!==false}};
      await d.collection("app_config").updateOne({_id:"global"},{$set:{...allowed,updatedAt:new Date()}},{upsert:true});
      await d.collection("admin_audit_logs").insertOne({action:"config.updated",createdAt:new Date()});
      return send(res,200,{_id:"global",...allowed});
    }
    return send(res,404,{error:"Route not found"});
  } catch(error) {
    console.error("admin_api_error",error.message);
    return send(res,error.status||500,{error:error.status?error.message:"Admin API error. Check service logs."});
  }
}
http.createServer((req,res)=>handler(req,res).catch(e=>send(res,500,{error:"Internal server error"}))).listen(PORT,"0.0.0.0",()=>console.log("StreamForge Admin API listening on",PORT));
process.on("SIGTERM",async()=>{if(client) await client.close();process.exit(0);});
