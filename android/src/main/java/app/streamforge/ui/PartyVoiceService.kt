package app.streamforge.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Owns Party Voice independently of Compose screens and Activity navigation.
 * The microphone foreground-service notification remains visible while the
 * user switches tabs or backgrounds StreamForge. Android force-stop still
 * terminates all app services by design.
 */
class PartyVoiceService : Service() {
    companion object {
        const val ACTION_JOIN = "app.streamforge.PARTY_VOICE_JOIN"
        const val ACTION_LEAVE = "app.streamforge.PARTY_VOICE_LEAVE"
        const val ACTION_SET_MIC = "app.streamforge.PARTY_VOICE_SET_MIC"
        const val ACTION_SET_SPEAKER = "app.streamforge.PARTY_VOICE_SET_SPEAKER"
        const val ACTION_TOGGLE_MIC = "app.streamforge.PARTY_VOICE_TOGGLE_MIC"
        const val ACTION_SET_REMOTE_VOLUME = "app.streamforge.PARTY_VOICE_SET_REMOTE_VOLUME"
        const val ACTION_SET_REMOTE_MUTED = "app.streamforge.PARTY_VOICE_SET_REMOTE_MUTED"
        const val ACTION_START_SCREEN_SHARE = "app.streamforge.PARTY_VOICE_START_SCREEN_SHARE"
        const val ACTION_STOP_SCREEN_SHARE = "app.streamforge.PARTY_VOICE_STOP_SCREEN_SHARE"

        const val EXTRA_ENDPOINT = "voice_endpoint"
        const val EXTRA_ROOM_ID = "voice_room_id"
        const val EXTRA_MEMBER_ID = "voice_member_id"
        const val EXTRA_TOKEN = "voice_token"
        const val EXTRA_DISPLAY_NAME = "voice_display_name"
        const val EXTRA_ROOM_NAME = "voice_room_name"
        const val EXTRA_ENABLED = "voice_enabled"
        const val EXTRA_PEER_ID = "voice_peer_id"
        const val EXTRA_VOLUME = "voice_peer_volume"
        const val EXTRA_PROJECTION_DATA = "screen_projection_data"
        const val EXTRA_SCREEN_WIDTH = "screen_width"
        const val EXTRA_SCREEN_HEIGHT = "screen_height"
        const val EXTRA_SCREEN_FPS = "screen_fps"

        private const val CHANNEL_ID = "streamforge_party_voice"
        private const val NOTIFICATION_ID = 7410
        private const val PREFS = "streamforge_party"

        internal val state = MutableStateFlow(VoiceEngineState(status = "Not in a voice room"))
        internal val active = MutableStateFlow(false)

        fun join(context: Context, endpoint: String, roomId: String, memberId: String, token: String, displayName: String, roomName: String) {
            val intent = Intent(context, PartyVoiceService::class.java).apply {
                action = ACTION_JOIN
                putExtra(EXTRA_ENDPOINT, endpoint)
                putExtra(EXTRA_ROOM_ID, roomId)
                putExtra(EXTRA_MEMBER_ID, memberId)
                putExtra(EXTRA_TOKEN, token)
                putExtra(EXTRA_DISPLAY_NAME, displayName)
                putExtra(EXTRA_ROOM_NAME, roomName)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun setMicEnabled(context: Context, enabled: Boolean) {
            context.startService(Intent(context, PartyVoiceService::class.java).apply {
                action = ACTION_SET_MIC
                putExtra(EXTRA_ENABLED, enabled)
            })
        }

        fun setSpeakerEnabled(context: Context, enabled: Boolean) {
            context.startService(Intent(context, PartyVoiceService::class.java).apply {
                action = ACTION_SET_SPEAKER
                putExtra(EXTRA_ENABLED, enabled)
            })
        }

        fun setRemoteVolume(context: Context, peerId: String, volume: Float) {
            context.startService(Intent(context, PartyVoiceService::class.java).apply {
                action = ACTION_SET_REMOTE_VOLUME
                putExtra(EXTRA_PEER_ID, peerId)
                putExtra(EXTRA_VOLUME, volume)
            })
        }

        fun setRemoteMuted(context: Context, peerId: String, muted: Boolean) {
            context.startService(Intent(context, PartyVoiceService::class.java).apply {
                action = ACTION_SET_REMOTE_MUTED
                putExtra(EXTRA_PEER_ID, peerId)
                putExtra(EXTRA_ENABLED, muted)
            })
        }

        fun startScreenShare(context: Context, projectionData: Intent, width: Int, height: Int, fps: Int) {
            context.startService(Intent(context, PartyVoiceService::class.java).apply {
                action = ACTION_START_SCREEN_SHARE
                putExtra(EXTRA_PROJECTION_DATA, projectionData)
                putExtra(EXTRA_SCREEN_WIDTH, width)
                putExtra(EXTRA_SCREEN_HEIGHT, height)
                putExtra(EXTRA_SCREEN_FPS, fps)
            })
        }

        fun stopScreenShare(context: Context) {
            context.startService(Intent(context, PartyVoiceService::class.java).setAction(ACTION_STOP_SCREEN_SHARE))
        }

        fun leave(context: Context) {
            context.startService(Intent(context, PartyVoiceService::class.java).setAction(ACTION_LEAVE))
        }
    }

    private var engine: PartyVoiceEngine? = null
    private var endpoint = ""
    private var roomId = ""
    private var memberId = ""
    private var displayName = "Guest"
    private var roomName = "Voice room"
    private var micEnabled = true
    private var speakerEnabled = true

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_JOIN -> {
                endpoint = intent.getStringExtra(EXTRA_ENDPOINT).orEmpty()
                roomId = intent.getStringExtra(EXTRA_ROOM_ID).orEmpty()
                memberId = intent.getStringExtra(EXTRA_MEMBER_ID).orEmpty()
                val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
                displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty().ifBlank { "Guest" }
                roomName = intent.getStringExtra(EXTRA_ROOM_NAME).orEmpty().ifBlank { "Voice room" }
                if (endpoint.isNotBlank() && roomId.isNotBlank() && memberId.isNotBlank() && token.isNotBlank()) {
                    micEnabled = true
                    speakerEnabled = true
                    startForegroundNow()
                    saveSession(token)
                    startEngine(token)
                } else {
                    stopSelf(startId)
                }
            }
            ACTION_SET_MIC -> {
                micEnabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
                engine?.setMicEnabled(micEnabled)
                state.value = state.value.copy(micEnabled = micEnabled, localSpeaking = if (micEnabled) state.value.localSpeaking else false, localLevel = if (micEnabled) state.value.localLevel else 0f)
                updateNotification()
            }
            ACTION_TOGGLE_MIC -> {
                micEnabled = !state.value.micEnabled
                engine?.setMicEnabled(micEnabled)
                state.value = state.value.copy(micEnabled = micEnabled, localSpeaking = if (micEnabled) state.value.localSpeaking else false, localLevel = if (micEnabled) state.value.localLevel else 0f)
                updateNotification()
            }
            ACTION_SET_SPEAKER -> {
                speakerEnabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
                engine?.setSpeakerEnabled(speakerEnabled)
                state.value = state.value.copy(speakerEnabled = speakerEnabled)
            }
            ACTION_SET_REMOTE_VOLUME -> {
                engine?.setRemoteVolume(intent.getStringExtra(EXTRA_PEER_ID).orEmpty(), intent.getFloatExtra(EXTRA_VOLUME, 1f))
            }
            ACTION_SET_REMOTE_MUTED -> {
                engine?.setRemoteMuted(intent.getStringExtra(EXTRA_PEER_ID).orEmpty(), intent.getBooleanExtra(EXTRA_ENABLED, false))
            }
            ACTION_START_SCREEN_SHARE -> {
                val projection = intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
                if (projection != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                    }
                    engine?.startScreenShare(projection, intent.getIntExtra(EXTRA_SCREEN_WIDTH, 1280), intent.getIntExtra(EXTRA_SCREEN_HEIGHT, 720), intent.getIntExtra(EXTRA_SCREEN_FPS, 30))
                }
            }
            ACTION_STOP_SCREEN_SHARE -> {
                engine?.stopScreenShare()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && active.value) {
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                }
            }
            ACTION_LEAVE -> leaveRoom()
            null -> restoreSession()
        }
        return START_STICKY
    }

    private fun startEngine(token: String) {
        if (engine != null && active.value && state.value.connected) {
            if (micEnabled != state.value.micEnabled) engine?.setMicEnabled(micEnabled)
            return
        }
        engine?.close()
        state.value = VoiceEngineState(micEnabled = micEnabled, speakerEnabled = speakerEnabled, status = "Starting microphone…")
        active.value = true
        engine = PartyVoiceEngine(this, endpoint, roomId, memberId, token, displayName) { next ->
            val previous = state.value
            state.value = next
            micEnabled = next.micEnabled
            speakerEnabled = next.speakerEnabled
            if (previous.micEnabled != next.micEnabled || previous.micCapturing != next.micCapturing || previous.connected != next.connected) {
                updateNotification()
            }
        }.also { it.connect() }
        updateNotification()
    }

    private fun startForegroundNow() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun restoreSession() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        endpoint = prefs.getString("server_url", "https://streamforge-chat-api.onrender.com") ?: "https://streamforge-chat-api.onrender.com"
        roomId = prefs.getString("room_id", "").orEmpty()
        memberId = prefs.getString("member_id", "").orEmpty()
        displayName = prefs.getString("display_name", "Guest") ?: "Guest"
        roomName = prefs.getString("room_name", "Voice room") ?: "Voice room"
        if (roomId.isBlank() || memberId.isBlank() || endpoint.isBlank()) {
            stopSelf()
            return
        }
        startForegroundNow()
        // Refresh the room token and presence after Android recreates a sticky service.
        thread(name = "sf-party-voice-restore") {
            runCatching {
                val connection = URL(endpoint.trimEnd('/') + "/api/voice/rooms/join").openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 10000
                    connection.readTimeout = 12000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    val payload = JSONObject().put("roomId", roomId).put("memberId", memberId).put("displayName", displayName)
                    connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                    val code = connection.responseCode
                    val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (code !in 200..299) throw IllegalStateException("Room restore failed ($code)")
                    val token = JSONObject(body).optJSONObject("you")?.optString("voiceToken").orEmpty()
                    if (token.isBlank()) throw IllegalStateException("Room security token missing")
                    saveSession(token)
                    android.os.Handler(mainLooper).post { startEngine(token) }
                } finally {
                    connection.disconnect()
                }
            }.onFailure {
                Log.e("StreamForgePartyVoice", "Could not restore voice session", it)
                state.value = VoiceEngineState(status = "Voice reconnect failed · reopen Party Voice")
                updateNotification()
            }
        }
    }

    private fun saveSession(token: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("server_url", endpoint)
            .putString("room_id", roomId)
            .putString("member_id", memberId)
            .putString("display_name", displayName)
            .putString("room_name", roomName)
            .putString("voice_token", token)
            .apply()
    }

    private fun leaveRoom() {
        val oldEndpoint = endpoint
        val oldRoomId = roomId
        val oldMemberId = memberId
        engine?.close()
        engine = null
        active.value = false
        state.value = VoiceEngineState(status = "Left voice room")
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .remove("room_id").remove("room_name").remove("room_visibility").remove("voice_token")
            .apply()
        if (oldEndpoint.isNotBlank() && oldRoomId.isNotBlank() && oldMemberId.isNotBlank()) {
            thread(name = "sf-party-voice-leave") {
                runCatching {
                    val connection = URL(oldEndpoint.trimEnd('/') + "/api/voice/rooms/leave").openConnection() as HttpURLConnection
                    try {
                        connection.requestMethod = "POST"
                        connection.connectTimeout = 5000
                        connection.readTimeout = 5000
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json")
                        val payload = JSONObject().put("roomId", oldRoomId).put("memberId", oldMemberId)
                        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                        connection.responseCode
                    } finally { connection.disconnect() }
                }
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Party Voice microphone", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps Party Voice connected while you use other apps"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 7410, Intent(this, app.streamforge.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleMic = PendingIntent.getService(
            this, 7411, Intent(this, PartyVoiceService::class.java).setAction(ACTION_TOGGLE_MIC),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val leave = PendingIntent.getService(
            this, 7412, Intent(this, PartyVoiceService::class.java).setAction(ACTION_LEAVE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val muted = !state.value.micEnabled
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("StreamForge · Party Voice")
            .setContentText(if (muted) "Microphone muted · $roomName" else "Microphone on · $roomName")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_lock_silent_mode, if (muted) "Unmute" else "Mute", toggleMic)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Leave", leave)
            .build()
    }

    private fun updateNotification() {
        if (active.value || engine != null) {
            runCatching {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Intentionally keep the microphone service alive when the task is swiped away.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        engine?.close()
        engine = null
        active.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
