import crypto from "node:crypto";

let cachedToken = null, expiresAt = 0;
const b64 = value => Buffer.from(value).toString("base64url");

function serviceAccount() {
  const raw = (process.env.FIREBASE_SERVICE_ACCOUNT_JSON || "").trim();
  if (!raw) return null;
  try { return JSON.parse(raw); } catch { throw new Error("Invalid Firebase service account JSON"); }
}

async function accessToken(account) {
  if (cachedToken && Date.now() < expiresAt - 60000) return cachedToken;
  const now = Math.floor(Date.now() / 1000);
  const unsigned = b64(JSON.stringify({alg:"RS256",typ:"JWT"})) + "." +
    b64(JSON.stringify({iss:account.client_email,scope:"https://www.googleapis.com/auth/firebase.messaging",aud:"https://oauth2.googleapis.com/token",iat:now,exp:now+3600}));
  const signer = crypto.createSign("RSA-SHA256");
  signer.update(unsigned);
  const assertion = unsigned + "." + signer.sign(account.private_key, "base64url");
  const response = await fetch("https://oauth2.googleapis.com/token", {
    method:"POST", headers:{"content-type":"application/x-www-form-urlencoded"},
    body:new URLSearchParams({grant_type:"urn:ietf:params:oauth:grant-type:jwt-bearer",assertion})
  });
  const data = await response.json();
  if (!response.ok || !data.access_token) throw new Error("Firebase authentication failed");
  cachedToken = data.access_token; expiresAt = Date.now() + Number(data.expires_in || 3600) * 1000;
  return cachedToken;
}

export function pushConfigured() { return Boolean(serviceAccount()?.project_id); }

export async function sendPush(tokens, title, message, db) {
  const account = serviceAccount();
  if (!account?.project_id) return {configured:false,sent:0,failed:0};
  const bearer = await accessToken(account);
  let sent = 0, failed = 0;
  for (let i=0; i<tokens.length; i+=500) {
    const batch = tokens.slice(i,i+500);
    const results = await Promise.all(batch.map(async token => {
      const response = await fetch("https://fcm.googleapis.com/v1/projects/" + account.project_id + "/messages:send", {
        method:"POST", headers:{authorization:"Bearer " + bearer,"content-type":"application/json"},
        body:JSON.stringify({message:{token,notification:{title,body:message},data:{type:"admin_push"},android:{priority:"high",notification:{channel_id:"streamforge_updates",sound:"default"}}}})
      });
      if (response.ok) return true;
      const detail = await response.text();
      if (detail.includes("UNREGISTERED")) await db.collection("push_devices").deleteOne({token});
      return false;
    }));
    sent += results.filter(Boolean).length; failed += results.length - results.filter(Boolean).length;
  }
  return {configured:true,sent,failed};
}
