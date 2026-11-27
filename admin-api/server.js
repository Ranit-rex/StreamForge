import http from "node:http";
import { MongoClient } from "mongodb";
import { initializeApp, cert, getApps } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { pushConfigured, sendPush } from "./fcm.js";

const PORT = Number(process.env.PORT || 3000);
const URI = (process.env.MONGODB_URI || "").trim();
const DB_NAME = process.env.MONGODB_DATABASE || "streamforge";
const SERVICE_ACCOUNT = process.env.FIREBASE_SERVICE_ACCOUNT_JSON ? JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_JSON) : null;
if (SERVICE_ACCOUNT && !getApps().length) initializeApp({ credential: cert(SERVICE_ACCOUNT) });
let client;
let db;
let connecting;

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
      db.collection("admin_audit_logs").createIndex({ createdAt: -1 })
    ]);
    return db;
  })().catch(error => { connecting = null; throw error; });
  return connecting;
}
function send(res, status, data) {
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "access-control-allow-origin": "*", "access-control-allow-headers": "content-type,x-admin-key", "access-control-allow-methods": "GET,POST,PATCH,DELETE,OPTIONS" });
  res.end(JSON.stringify(data));
}
async function body(req) {
  let raw = "";
  for await (const chunk of req) { raw += chunk; if (raw.length > 20000) throw Object.assign(new Error("Request too large"), { status: 413 }); }
  return raw ? JSON.parse(raw) : {};
}
async function firebaseUser(req) {
  if (!SERVICE_ACCOUNT || !getApps().length) return null;
  const match = String(req.headers.authorization || "").match(/^Bearer\s+(.+)$/i);
  if (!match) return null;
  try { return await getAuth().verifyIdToken(match[1]); } catch { return null; }
}
async function authorized(req) { const decoded = await firebaseUser(req); return decoded?.admin === true; }
async function handler(req,res) {
  if (req.method === "OPTIONS") return send(res,204,{});
  const url = new URL(req.url,"http://localhost");
  if (url.pathname === "/health") return send(res,200,{ok:true,service:"streamforge-admin-api",databaseConfigured:Boolean(URI),authConfigured:Boolean(SERVICE_ACCOUNT)});
  const publicRelease = url.pathname.match(/^\/api\/releases\/stable$/);
  if (req.method === "GET" && publicRelease) {
    try { const d = await database(); const release = await d.collection("app_releases").findOne({_id:publicRelease[1]}); return send(res,200,release || {track:publicRelease[1],versionName:"1.0.0",versionCode:1,minimumVersionCode:1,forceUpdate:false,updateUrl:"",releaseNotes:""}); }
    catch(error) { return send(res,error.status||503,{error:"Release information unavailable"}); }
  }

  if (req.method==="POST" && url.pathname==="/api/devices/register") {
    try {
      const identity=await firebaseUser(req);
      if(!identity?.uid)return send(res,401,{error:"Sign in required"});
      const input=await body(req),token=String(input.token||"").trim();
      if(!token||token.length>8192)return send(res,400,{error:"Valid FCM token required"});
      const d=await database();
      await d.collection("push_devices").updateOne({token},{$set:{token,uid:identity.uid,platform:"android",enabled:true,updatedAt:new Date()},$setOnInsert:{createdAt:new Date()}},{upsert:true});
      return send(res,200,{registered:true});
    } catch(error){return send(res,error.status||500,{error:error.status?error.message:"Device registration failed"});}
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

    const releaseMatch = url.pathname.match(/^\/api\/releases\/stable$/);
    if (releaseMatch && req.method === "POST") {
      const input = await body(req), track = releaseMatch[1];
      const versionCode = Math.max(1, Number(input.versionCode) || 1);
      const minimumVersionCode = Math.max(1, Number(input.minimumVersionCode) || 1);
      const updateUrl = String(input.updateUrl || "").trim();
      if (!/^https:\/\//i.test(updateUrl)) return send(res,400,{error:"A secure HTTPS update URL is required"});
      const release = {track,versionName:String(input.versionName || "1.0.0").slice(0,40),versionCode,minimumVersionCode,forceUpdate:Boolean(input.forceUpdate),updateUrl,releaseNotes:String(input.releaseNotes || "").slice(0,3000),updatedAt:new Date()};
      await d.collection("app_releases").updateOne({_id:track},{$set:release},{upsert:true});
      await d.collection("admin_audit_logs").insertOne({action:"release.published",track,versionCode,createdAt:new Date()});
      return send(res,200,release);
    }
    if(req.method==="GET"&&url.pathname==="/api/push/status"){
      const registeredDevices=await d.collection("push_devices").countDocuments({enabled:true});
      return send(res,200,{configured:pushConfigured(),registeredDevices});
    }
    if(req.method==="POST"&&url.pathname==="/api/push/send"){
      const input=await body(req),title=String(input.title||"").trim(),message=String(input.message||"").trim();
      if(!title||!message)return send(res,400,{error:"Title and message are required"});
      const targetUids=Array.isArray(input.targetUids)?input.targetUids.map(x=>String(x).trim()).filter(Boolean).slice(0,500):[];
      if(input.audience==="selected"&&!targetUids.length)return send(res,400,{error:"Select at least one valid user UID"});
      const filter={enabled:true,...(input.audience==="selected"?{uid:{$in:targetUids}}:{})};
      const rows=await d.collection("push_devices").find(filter).project({token:1}).toArray();
      const result=await sendPush(rows.map(x=>x.token),title.slice(0,100),message.slice(0,2000),d);
      await d.collection("admin_audit_logs").insertOne({action:"push.sent",sent:result.sent,failed:result.failed,createdAt:new Date()});
      return send(res,200,{...result,devices:rows.length});
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
