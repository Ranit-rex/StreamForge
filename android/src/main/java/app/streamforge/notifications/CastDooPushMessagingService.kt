package app.streamforge.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import app.streamforge.MainActivity

class CastDooPushMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        FirebaseAuth.getInstance().currentUser?.let { CastDooPushRegistration.saveToken(it.uid, token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val title = data["title"]?.takeIf { it.isNotBlank() } ?: "CastDoo"
        val body = data["body"]?.takeIf { it.isNotBlank() } ?: "You have a new update."
        createNotificationChannel()
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_ACTION_URL, data["actionUrl"].orEmpty())
            putExtra(EXTRA_CAMPAIGN_ID, data["campaignId"].orEmpty())
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            (data["campaignId"] ?: title).hashCode(),
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (data["priority"] == "important") NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        runCatching {
            NotificationManagerCompat.from(this).notify((data["campaignId"] ?: title).hashCode(), notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "CastDoo updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Announcements and important updates from CastDoo"
                }
            )
        }
    }

    companion object {
        const val EXTRA_ACTION_URL = "castdoo.notification.action_url"
        const val EXTRA_CAMPAIGN_ID = "castdoo.notification.campaign_id"
        private const val CHANNEL_ID = "castdoo_updates"
    }
}
