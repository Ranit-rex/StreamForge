package app.streamforge.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.RemoteMessage
import app.streamforge.MainActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val CASTDOO_ADMIN_API = "https://streamforge-admin-api-y6ie.onrender.com"
private const val CHANNEL_ID = "castdoo_updates"

object CastDooFcmRegistration {
    fun register(context: Context) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            Log.d("CastDooFCM", "Registration deferred: no signed-in Firebase user")
            return
        }
        Log.d("CastDooFCM", "Requesting FCM token for signed-in user")
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                if (token.isNotBlank()) user.getIdToken(true)
                    .addOnSuccessListener { result -> result.token?.let { sendToken(context.applicationContext, token, it) } }
                    .addOnFailureListener { error -> Log.e("CastDooFCM", "Could not authenticate FCM registration", error) }
            }
            .addOnFailureListener { error -> Log.e("CastDooFCM", "Could not get FCM token; check google-services.json and Firebase Cloud Messaging", error) }
    }

    internal fun sendToken(context: Context, token: String, idToken: String) {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("$CASTDOO_ADMIN_API/api/devices/fcm/register").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("Authorization", "Bearer $idToken")
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
                val payload = JSONObject().put("token", token).put("platform", "android")
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    Log.e("CastDooFCM", "Token registration rejected ($code): $error")
                } else Log.i("CastDooFCM", "FCM device registered for uid=${FirebaseAuth.getInstance().currentUser?.uid?.take(10)}")
            } catch (error: Exception) {
                Log.e("CastDooFCM", "FCM registration failed", error)
            } finally { connection?.disconnect() }
        }.start()
    }
}

class CastDooFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        CastDooFcmRegistration.register(applicationContext)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val title = message.notification?.title ?: data["title"] ?: "CastDoo"
        val body = message.notification?.body ?: data["body"] ?: "You have a new update."
        val actionUrl = data["actionUrl"].orEmpty()
        val campaignId = data["campaignId"].orEmpty().ifBlank { title }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "CastDoo updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Push announcements and important CastDoo updates"
                })
        }
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(CastDooUnifiedPushService.EXTRA_ACTION_URL, actionUrl)
            putExtra(CastDooUnifiedPushService.EXTRA_CAMPAIGN_ID, campaignId)
        }
        val pendingIntent = PendingIntent.getActivity(this, campaignId.hashCode(), openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true).setContentIntent(pendingIntent).build()
        runCatching { NotificationManagerCompat.from(this).notify(campaignId.hashCode(), notification) }
    }
}
