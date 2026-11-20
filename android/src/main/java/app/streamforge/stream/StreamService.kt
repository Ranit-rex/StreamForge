package app.streamforge.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.NoVideoSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.generic.GenericStream
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StreamService : Service(), ConnectChecker {
    companion object {
        const val ACTION_START_STREAM = "app.streamforge.START_STREAM"
        const val ACTION_STOP_STREAM = "app.streamforge.STOP_STREAM"
        const val ACTION_START_RECORDING = "app.streamforge.START_RECORDING"
        const val ACTION_STOP_RECORDING = "app.streamforge.STOP_RECORDING"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        const val EXTRA_ENDPOINT = "stream_endpoint"
        const val EXTRA_WIDTH = "stream_width"
        const val EXTRA_HEIGHT = "stream_height"
        const val EXTRA_FPS = "stream_fps"
        const val EXTRA_BITRATE = "stream_bitrate"
        private const val CHANNEL_ID = "streamforge_live"
        private const val NOTIFICATION_ID = 7401
        val status = MutableStateFlow("Ready")
        val streaming = MutableStateFlow(false)
        val recording = MutableStateFlow(false)
        val bitrateKbps = MutableStateFlow(0L)
        val streamResolution = MutableStateFlow("1280 × 720")
        val streamFps = MutableStateFlow(30)
        val streamStartedAt = MutableStateFlow(0L)
    }

    private var stream: GenericStream? = null
    private var projection: MediaProjection? = null
    private var recordFile: File? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        stream = GenericStream(this, this, NoVideoSource(), MicrophoneSource()).apply {
            getGlInterface().setForceRender(true, 15)
            prepareAudio(44_100, true, 128_000, echoCanceler = true, noiseSuppressor = true)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_STREAM -> startScreenStream(intent)
            ACTION_STOP_STREAM -> stopStream()
            ACTION_START_RECORDING -> startRecording()
            ACTION_STOP_RECORDING -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startScreenStream(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val projectionData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val endpoint = intent.getStringExtra(EXTRA_ENDPOINT).orEmpty()
        val width = intent.getIntExtra(EXTRA_WIDTH, 1280).coerceIn(640, 3840)
        val height = intent.getIntExtra(EXTRA_HEIGHT, 720).coerceIn(360, 2160)
        val fps = intent.getIntExtra(EXTRA_FPS, 30).coerceIn(24, 60)
        val bitrate = intent.getIntExtra(EXTRA_BITRATE, 4_500_000).coerceIn(800_000, 35_000_000)
        if (projectionData == null || endpoint.isBlank()) {
            status.value = "Missing screen permission or destination"
            stopSelf()
            return
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Preparing stream…"),
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else 0
        )

        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, projectionData)
                ?: throw IllegalStateException("Screen capture permission was not granted")
            streamResolution.value = "$width × $height"
            streamFps.value = fps
            bitrateKbps.value = 0L
            stream?.prepareVideo(width, height, bitrate, fps, rotation = 0)
            stream?.changeVideoSource(ScreenSource(applicationContext, projection!!))
            stream?.startStream(endpoint)
            status.value = "Connecting…"
        } catch (error: Exception) {
            status.value = "Could not start: ${error.message ?: "unknown error"}"
            stopStream()
        }
    }

    private fun startRecording() {
        val activeStream = stream ?: return
        if (activeStream.isRecording) return
        val directory = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        if (!directory.exists()) directory.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        recordFile = File(directory, "StreamForge_$stamp.mp4")
        activeStream.startRecord(recordFile!!.absolutePath) { }
        recording.value = true
        status.value = "Recording locally"
    }

    private fun stopRecording() {
        val activeStream = stream ?: return
        if (activeStream.isRecording) activeStream.stopRecord()
        recording.value = false
        status.value = if (streaming.value) "LIVE" else "Ready"
    }

    private fun stopStream() {
        stream?.let { if (it.isStreaming) it.stopStream() }
        streaming.value = false
        streamStartedAt.value = 0L
        projection?.stop()
        projection = null
        status.value = "Stream stopped"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("StreamForge is active")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Live streaming", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onConnectionStarted(url: String) { status.value = "Connecting to destination…" }
    override fun onConnectionSuccess() {
        streaming.value = true
        streamStartedAt.value = System.currentTimeMillis()
        status.value = "LIVE"
    }
    override fun onConnectionFailed(reason: String) {
        streaming.value = false
        streamStartedAt.value = 0L
        status.value = "Connection failed: $reason"
        stopStream()
    }
    override fun onNewBitrate(bitrate: Long) {
        bitrateKbps.value = bitrate / 1000
        status.value = if (streaming.value) "LIVE • ${bitrate / 1000} kbps" else status.value
    }
    override fun onDisconnect() {
        streaming.value = false
        streamStartedAt.value = 0L
        status.value = "Disconnected"
    }
    override fun onAuthError() { status.value = "Destination authentication failed" }
    override fun onAuthSuccess() { status.value = "Destination authenticated" }

    override fun onDestroy() {
        stopRecording()
        stream?.let { if (it.isStreaming) it.stopStream(); it.release() }
        stream = null
        projection?.stop()
        projection = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
