package app.streamforge.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import app.streamforge.MainActivity
import java.net.HttpURLConnection
import java.net.URL

private const val CASTDOO_ADMIN_API = "https://streamforge-admin-api-y6ie.onrender.com"

class CastDooUnifiedPushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val keys = endpoint.pubKeySet ?: return
        val user = FirebaseAuth.getInstance().currentUser ?: return
        Thread {
            var connection: HttpURLConnection? = null
            try {
                val idToken = Tasks.await(user.getIdToken(true)).token ?: return@Thread
                connection = (URL("$CASTDOO_ADMIN_API/api/devices/register").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("Authorization", "Bearer $idToken")
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
                val payload = JSONObject().put("endpoint", endpoint.url).put("p256dh", keys.pubKey)
                    .put("auth", keys.auth).put("instance", instance)
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                connection.responseCode
            } catch (_: Exception) {
                // The next app launch re-registers with the UnifiedPush distributor.
            } finally { connection?.disconnect() }
        }.start()
    }

    override fun onMessage(message: PushMessage, instance: String) {
        if (!message.decrypted) return
        val data = runCatching { JSONObject(message.content.toString(Charsets.UTF_8)) }.getOrNull() ?: return
        val title = data.optString("title").ifBlank { "CastDoo" }
        val body = data.optString("body").ifBlank { "You have a new update." }
        val campaignId = data.optString("campaignId").ifBlank { title }
        createNotificationChannel()
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_ACTION_URL, data.optString("actionUrl"))
            putExtra(EXTRA_CAMPAIGN_ID, campaignId)
        }
        val pendingIntent = PendingIntent.getActivity(this, campaignId.hashCode(), openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (data.optString("priority") == "high") NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true).setContentIntent(pendingIntent).build()
        runCatching { NotificationManagerCompat.from(this).notify(campaignId.hashCode(), notification) }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) = Unit
    override fun onTempUnavailable(instance: String) = Unit
    override fun onUnregistered(instance: String) = Unit

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "CastDoo updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Push announcements and important CastDoo updates"
                })
        }
    }

    companion object {
        const val EXTRA_ACTION_URL = "castdoo.notification.action_url"
        const val EXTRA_CAMPAIGN_ID = "castdoo.notification.campaign_id"
        private const val CHANNEL_ID = "castdoo_updates"
    }
}
