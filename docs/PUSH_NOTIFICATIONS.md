# CastDoo push notifications (MongoDB + UnifiedPush)

## Delivery architecture
- Notification campaigns, recipient records, device endpoints, delivery status, and admin audit entries are stored in MongoDB Atlas through the Render Admin API.
- Android push transport uses UnifiedPush with Web Push encryption. Firebase Cloud Messaging and Firebase notification functions are not used.
- Android must have a UnifiedPush distributor installed. The ntfy Android app can act as the distributor; install it, grant notification permission, and exempt it from battery optimization where the device allows it. The user selects ntfy when CastDoo first requests a push distributor.
- The distributor is a separate Android app. Without an installed UnifiedPush distributor, CastDoo cannot receive background push while closed.

## Render Admin API environment
Set these variables on the `streamforge-admin-api` Render service:
- `WEB_PUSH_PUBLIC_KEY`: VAPID public key.
- `WEB_PUSH_PRIVATE_KEY`: matching VAPID private key (keep secret).
- `WEB_PUSH_SUBJECT`: a contact URI such as `mailto:support@castdoo.app`.
- `CASTDOO_ADMIN_PAIRING_CODE`: a long random one-time code used only to pair the Admin APK.
- Keep `MONGODB_URI`, `MONGODB_DATABASE`, and `FIREBASE_SERVICE_ACCOUNT_JSON` for the existing user-auth token verification endpoint. The Firebase service account is not used to send notifications.

Generate VAPID keys locally with:
```bash
npx web-push generate-vapid-keys
```
Never commit private keys or pairing codes to Git.

## Admin app security
The Admin APK no longer uses Google sign-in. On first launch, enter the one-time pairing code configured as `CASTDOO_ADMIN_PAIRING_CODE`. The API issues a revocable device token, stored using Android encrypted preferences. Later launches open directly to the dashboard. The API checks the token against MongoDB on every admin request.

## Android setup
1. Install ntfy from F-Droid or Google Play.
2. Open ntfy once and allow notifications.
3. Sign in to CastDoo normally; choose ntfy as the UnifiedPush distributor when prompted.
4. Keep ntfy allowed to run in the background for best delivery reliability.
5. Android 13+ must grant CastDoo notification permission.

## Send a push
Open the Admin APK, select Push, enter a title and message, choose the audience, and send. The API stores the campaign and per-device delivery result in MongoDB, then sends encrypted Web Push messages to each registered UnifiedPush endpoint.

## Important Android limits
Force-stopping CastDoo or the distributor from Android Settings can stop delivery until the user opens it again. Battery optimization and OEM background restrictions may delay delivery. UnifiedPush avoids FCM but requires a distributor app to be installed and running.
