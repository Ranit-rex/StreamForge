import { initializeApp } from "firebase-admin/app";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { onCall, HttpsError } from "firebase-functions/v2/https";

initializeApp();
const db = getFirestore();

export const publishAppRelease = onCall(
  { region: "asia-south1", maxInstances: 2 },
  async (request) => {
    if (!request.auth || request.auth.token.admin !== true) {
      throw new HttpsError("permission-denied", "Admin access required.");
    }
    const data = request.data ?? {};
    const track = data.track === "beta" || data.track === "stable" ? data.track : "";
    const versionCode = Number(data.versionCode);
    const minimumVersionCode = Number(data.minimumVersionCode);
    const versionName = typeof data.versionName === "string" ? data.versionName.trim() : "";
    const updateUrl = typeof data.updateUrl === "string" ? data.updateUrl.trim() : "";
    const releaseNotes = typeof data.releaseNotes === "string" ? data.releaseNotes.trim() : "";
    if (!track || !Number.isInteger(versionCode) || versionCode < 1 ||
        !Number.isInteger(minimumVersionCode) || minimumVersionCode < 1 ||
        minimumVersionCode > versionCode || !versionName || versionName.length > 60 ||
        !updateUrl.startsWith("https://") || updateUrl.length > 2048 ||
        releaseNotes.length > 4000) {
      throw new HttpsError("invalid-argument", "Invalid release metadata.");
    }
    await db.collection("appReleases").doc(track).set({
      track,
      versionCode,
      minimumVersionCode,
      versionName,
      updateUrl,
      releaseNotes,
      enabled: true,
      publishedAt: FieldValue.serverTimestamp(),
      publishedBy: request.auth.uid
    });
    return { track, versionCode, versionName };
  }
);
