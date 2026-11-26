import http from "node:http";
import { MongoClient } from "mongodb";
import { timingSafeEqual } from "node:crypto";

const PORT = Number(process.env.PORT || 3000);
const URI = (process.env.MONGODB_URI || "").trim();
const DB_NAME = process.env.MONGODB_DATABASE || "streamforge";
const ADMIN_KEY = (process.env.ADMIN_API_KEY || "").trim();
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
function authorized(req) {
  if (!ADMIN_KEY) return false;
  const got = Buffer.from(String(req.headers["x-admin-key"] || ""));
  const expected = Buffer.from(ADMIN_KEY);
  return got.length === expected.length && timingSafeEqual(got, expected);
}
async function handler(req,res) {
  if (req.method === "OPTIONS") return send(res,204,{});
  const url = new URL(req.url,"http://localhost");
  if (url.pathname === "/health") return send(res,200,{ok:true,service:"streamforge-admin-api",databaseConfigured:Boolean(URI),adminKeyConfigured:Boolean(ADMIN_KEY)});
  if (!url.pathname.startsWith("/api/")) return send(res,404,{error:"Not found"});
  if (!authorized(req)) return send(res,401,{error:"Admin key invalid or not configured"});
  try {
    const d = await database();
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
