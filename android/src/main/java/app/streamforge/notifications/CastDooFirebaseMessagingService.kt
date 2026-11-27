package app.streamforge.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import app.streamforge.MainActivity
import app.streamforge.R

class CastDooFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        CastDooPushRegistration.registerToken(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: "CastDoo"
        val body = message.notification?.body ?: message.data["body"] ?: "You have a new update."
        val campaignId = message.data["campaignId"] ?: title
        val actionUrl = message.data["actionUrl"].orEmpty()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "CastDoo updates", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(CastDooUnifiedPushService.EXTRA_ACTION_URL, actionUrl)
            putExtra(CastDooUnifiedPushService.EXTRA_CAMPAIGN_ID, campaignId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, campaignId.hashCode(), openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_streamforge)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(campaignId.hashCode(), notification) }
    }

    companion object {
        private const val CHANNEL_ID = "castdoo_updates"
    }
}
