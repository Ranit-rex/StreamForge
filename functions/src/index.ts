import { initializeApp } from "firebase-admin/app";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { onCall, HttpsError } from "firebase-functions/v2/https";

initializeApp();
const db = getFirestore();

export const sendAdminNotification = onCall(
  { region: "asia-south1", maxInstances: 2 },
  async (request) => {
    if (!request.auth || request.auth.token.admin !== true) {
      throw new HttpsError("permission-denied", "Admin access required.");
    }

    const data = request.data ?? {};
    const title = typeof data.title === "string" ? data.title.trim() : "";
    const body = typeof data.body === "string" ? data.body.trim() : "";
    const target = data.target === "selected" ? "selected" : data.target === "all" ? "all" : "";
    const priority = data.priority === "important" ? "important" : "normal";
    const imageUrl = typeof data.imageUrl === "string" ? data.imageUrl.trim() : "";
    const actionUrl = typeof data.actionUrl === "string" ? data.actionUrl.trim() : "";
    const targetUids = Array.isArray(data.targetUids)
      ? [...new Set(data.targetUids.filter((v: unknown) => typeof v === "string" && v.length > 0))]
      : [];

    if (!title || title.length > 100 || !body || body.length > 2000 || !target) {
      throw new HttpsError("invalid-argument", "Enter a title, message and valid audience.");
    }
    if (target === "selected" && (targetUids.length === 0 || targetUids.length > 500)) {
      throw new HttpsError("invalid-argument", "Select between 1 and 500 user IDs.");
    }
    for (const value of [imageUrl, actionUrl]) {
      if (value && !value.startsWith("https://")) {
        throw new HttpsError("invalid-argument", "Image and action links must use HTTPS.");
      }
    }

    const campaignRef = db.collection("notificationCampaigns").doc();
    await campaignRef.set({
      title, body, target, ...(target === "selected" ? { targetUids } : {}),
      priority, ...(imageUrl ? { imageUrl } : {}), ...(actionUrl ? { actionUrl } : {}),
      createdAt: FieldValue.serverTimestamp(), createdBy: request.auth.uid, status: "sending"
    });

    let userRefs: FirebaseFirestore.DocumentReference[] = [];
    if (target === "selected") {
      userRefs = targetUids.map((uid: string) => db.collection("users").doc(uid));
    } else {
      let cursor: FirebaseFirestore.QueryDocumentSnapshot | undefined;
      while (true) {
        let query = db.collection("users").orderBy("__name__").limit(400);
        if (cursor) query = query.startAfter(cursor);
        const page = await query.get();
        if (page.empty) break;
        userRefs.push(...page.docs.map((doc) => doc.ref));
        cursor = page.docs[page.docs.length - 1];
        if (page.size < 400) break;
      }
    }

    let inboxCount = 0;
    let pushCount = 0;
    for (const userRef of userRefs) {
      const tokenSnap = await userRef.collection("deviceTokens").get();
      const inboxRef = userRef.collection("notifications").doc(campaignRef.id);
      await inboxRef.set({
        title, body, ...(imageUrl ? { imageUrl } : {}), ...(actionUrl ? { actionUrl } : {}),
        priority, campaignId: campaignRef.id, read: false, createdAt: FieldValue.serverTimestamp()
      });
      inboxCount++;

      const tokens = tokenSnap.docs.map((doc) => doc.get("token")).filter((v): v is string => typeof v === "string" && v.length > 0);
      for (let i = 0; i < tokens.length; i += 500) {
        const chunk = tokens.slice(i, i + 500);
        if (chunk.length) {
          const result = await getMessaging().sendEachForMulticast({
            tokens: chunk,
            notification: { title, body, ...(imageUrl ? { imageUrl } : {}) },
            data: { campaignId: campaignRef.id, actionUrl, priority },
            android: { priority: priority === "important" ? "high" : "normal" }
          });
          pushCount += result.successCount;
          const invalid = result.responses
            .map((response, index) => ({ response, token: chunk[index] }))
            .filter(({ response }) => !response.success && [
              "messaging/invalid-registration-token", "messaging/registration-token-not-registered"
            ].includes(response.error?.code ?? ""));
          if (invalid.length) {
            const batch = db.batch();
            for (const entry of invalid) {
              const tokenDoc = tokenSnap.docs.find((doc) => doc.get("token") === entry.token);
              if (tokenDoc) batch.delete(tokenDoc.ref);
            }
            await batch.commit();
          }
        }
      }
    }

    await campaignRef.update({ status: "sent", recipientCount: inboxCount, pushSuccessCount: pushCount, completedAt: FieldValue.serverTimestamp() });
    return { campaignId: campaignRef.id, recipientCount: inboxCount, pushSuccessCount: pushCount };
  }
);
