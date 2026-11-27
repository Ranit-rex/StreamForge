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
      db.collection("notifications").createIndex({ uid: 1, createdAt: -1 })
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
  const publicRelease = url.pathname === "/api/releases/stable";
  if (req.method === "GET" && publicRelease) {
    try { const d = await database(); const release = await d.collection("app_releases").findOne({_id:"stable"}); return send(res,200,release || {track:"stable",versionName:"1.0.0",versionCode:1,minimumVersionCode:1,forceUpdate:false,updateUrl:"",releaseNotes:""}); }
    catch(error) { return send(res,error.status||503,{error:"Release information unavailable"}); }
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

  if (req.method === "POST" && url.pathname === "/api/devices/fcm/register") {
    try {
      const identity = await firebaseUser(req);
      if (!identity?.uid) return send(res,SERVICE_ACCOUNT ? 401 : 503,{error:SERVICE_ACCOUNT ? "Sign in required" : "CastDoo Firebase Admin credentials are not configured on the server."});
      const input = await body(req);
      const token = String(input.token || "").trim();
      if (token.length < 40 || token.length > 4096) return send(res,400,{error:"A valid Firebase messaging token is required"});
      const d = await database();
      await d.collection("push_devices").updateOne(
        {uid:identity.uid,endpoint:`fcm:${token}`},
        {$set:{uid:identity.uid,endpoint:`fcm:${token}`,fcmToken:token,platform:"android",enabled:true,updatedAt:new Date()},$setOnInsert:{createdAt:new Date()}},
        {upsert:true}
      );
      return send(res,200,{registered:true,transport:"FCM"});
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
    const defaults={versionName:"1.0.0",versionCode:1,minimumVersionCode:1,forceUpdate:false,maintenanceMode:false,maintenanceMessage:"",features:{announcements:true}};
    if (req.method==="GET" && url.pathname==="/api/overview") return send(res,200,{announcements:0,events24h:0,config:defaults,storageConfigured:false});
    if (req.method==="GET" && url.pathname==="/api/announcements") return send(res,200,[]);
    if (req.method==="GET" && url.pathname==="/api/config") return send(res,200,defaults);
    return send(res,503,{error:"MongoDB is not connected yet. Add MONGODB_URI in this Render service to enable saving."});
  }
  try {
    const d = await database();

    const releaseMatch = url.pathname === "/api/releases/stable";
    if (releaseMatch && req.method === "POST") {
      const input = await body(req), track = "stable";
      const versionCode = Math.max(1, Number(input.versionCode) || 1);
      const minimumVersionCode = Math.max(1, Number(input.minimumVersionCode) || 1);
      const updateUrl = String(input.updateUrl || "").trim();
      if (!/^https:\/\//i.test(updateUrl)) return send(res,400,{error:"A secure HTTPS update URL is required"});
      const release = {track,versionName:String(input.versionName || "1.0.0").slice(0,40),versionCode,minimumVersionCode,forceUpdate:Boolean(input.forceUpdate),updateUrl,releaseNotes:String(input.releaseNotes || "").slice(0,3000),updatedAt:new Date()};
      await d.collection("app_releases").updateOne({_id:track},{$set:release},{upsert:true});
      await d.collection("admin_audit_logs").insertOne({action:"release.published",track,versionCode,createdAt:new Date()});
      return send(res,200,release);
    }
    if (req.method === "GET" && url.pathname === "/api/push/status") {
      const registeredDevices = await d.collection("push_devices").countDocuments({enabled:true,$or:[{fcmToken:{$type:"string"}},{endpoint:{$type:"string"}}]});
      const fcmDevices = await d.collection("push_devices").countDocuments({enabled:true,fcmToken:{$type:"string"}});
      return send(res,200,{configured:Boolean(SERVICE_ACCOUNT) || pushConfigured(),fcmConfigured:Boolean(SERVICE_ACCOUNT),firebaseProject:SERVICE_ACCOUNT?.project_id || null,registeredDevices,fcmDevices,transport:"Firebase Cloud Messaging + Web Push"});
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
    if (req.method === "GET" && url.pathname === "/api/overview") {
      const [announcements, events, config] = await Promise.all([
        d.collection("admin_announcements").countDocuments(),
        d.collection("admin_events").countDocuments({ createdAt: { $gte: new Date(Date.now()-86400000) } }),
        d.collection("app_config").findOne({ _id:"global" })
      ]);
      return send(res,200,{announcements,events24h:events,config:config || {versionName:"1.0.0",versionCode:1,minimumVersionCode:1,forceUpdate:false,maintenanceMode:false,maintenanceMessage:"",features:{announcements:true}}});
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
      return send(res,200,await d.collection("app_config").findOne({_id:"global"}) || {versionName:"1.0.0",versionCode:1,minimumVersionCode:1,forceUpdate:false,maintenanceMode:false,maintenanceMessage:"",features:{announcements:true}});
    }
    if (req.method==="PATCH" && url.pathname==="/api/config") {
      const input=await body(req);
      const allowed={versionName:String(input.versionName||"1.0.0").slice(0,30),versionCode:Math.max(1,Number(input.versionCode)||1),minimumVersionCode:Math.max(1,Number(input.minimumVersionCode)||1),forceUpdate:Boolean(input.forceUpdate),maintenanceMode:Boolean(input.maintenanceMode),maintenanceMessage:String(input.maintenanceMessage||"").slice(0,300),features:{announcements:input.features?.announcements!==false}};
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
