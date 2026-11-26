# StreamForge push notifications

## Current implementation
- Admin API has authenticated `GET /api/push/status` and `POST /api/push/send` routes.
- Android device tokens register at public `POST /api/devices/register`.
- Tokens are stored in MongoDB collection `push_devices`.
- The Admin dashboard includes a Push notifications tab.
- FCM sends use the HTTP v1 API with a service account JWT. No Firebase private key is stored in this repository.

## Firebase setup required before delivery
1. Create a dedicated Firebase project for StreamForge (do not reuse an unrelated app's Firebase project).
2. Add an Android app with package name `app.streamforge.mobile`.
3. Download `google-services.json` and place it at `android/google-services.json`.
4. In Firebase Console, enable Cloud Messaging.
5. Create/download a service-account private key with Firebase Cloud Messaging send permission.
6. In Render, add `FIREBASE_SERVICE_ACCOUNT_JSON` to the `streamforge-admin-api` service. Its value must be the complete service-account JSON object. Keep this secret out of GitHub and the Android app.

## Android client work still required
- Add Google Services Gradle plugin and Firebase Messaging SDK after the app's `google-services.json` is available.
- Register the FCM token with `POST https://streamforge-admin-api-y6ie.onrender.com/api/devices/register`.
- Request Android 13+ notification permission at a suitable onboarding point.
- Add a notification channel named `streamforge_updates` and a FirebaseMessagingService to handle token refresh and notification taps.

## Send a notification
After Firebase credentials and device registration are complete, open the Admin dashboard, select Push notifications, enter a title and message, and send. The API reports sent/failed counts.
