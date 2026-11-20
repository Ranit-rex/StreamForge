package app.streamforge.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.graphics.Color as AndroidColor
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.SilenceAudioSource
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
        val networkHealth = MutableStateFlow("Network monitoring")
        val microphoneMuted = MutableStateFlow(false)
    }

    private var stream: GenericStream? = null
    private val microphoneSource = MicrophoneSource()
    private var projection: MediaProjection? = null
    private var recordFile: File? = null
    private var requestedVideoBitrate = 4_500_000
    private var activeVideoBitrate = 4_500_000
    private var lastBitrateAdjustmentAt = 0L
    private var stableNetworkSince = 0L
    private var overlayRoot: LinearLayout? = null
    private var overlayExpanded = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        stream = GenericStream(this, this, NoVideoSource(), microphoneSource).apply {
            getGlInterface().setForceRender(true, 15)
            prepareAudio(44_100, true, 128_000, echoCanceler = true, noiseSuppressor = true)
            // A smaller queue detects upload congestion sooner on mobile networks.
            getStreamClient().resizeCache(200)
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
            requestedVideoBitrate = bitrate
            activeVideoBitrate = bitrate
            lastBitrateAdjustmentAt = 0L
            stableNetworkSince = 0L
            networkHealth.value = "Network monitoring"
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
        hideFloatingControls()
        projection?.stop()
        projection = null
        status.value = "Stream stopped"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun toggleMicrophone() {
        val muted = !microphoneMuted.value
        val activeStream = stream
        if (activeStream != null) {
            runCatching {
                if (muted) activeStream.changeAudioSource(SilenceAudioSource())
                else activeStream.changeAudioSource(microphoneSource)
            }.onFailure { status.value = "Microphone toggle failed: ${it.message ?: "audio source error"}" }
        }
        microphoneMuted.value = muted
        overlayRoot?.let { updateOverlayLabels(it) }
    }

    private fun showFloatingControls() {
        if (overlayRoot != null || !android.provider.Settings.canDrawOverlays(this)) return
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(7), dp(7), dp(7), dp(7)); background = rounded(0xE91B2330.toInt(), 20) }
        val bubble = TextView(this).apply { text = "SF  •  LIVE"; textSize = 12f; setTextColor(AndroidColor.WHITE); setPadding(dp(14), dp(11), dp(14), dp(11)); background = rounded(0xFF586FE8.toInt(), 24); gravity = Gravity.CENTER }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, dp(6), 0, 0) }
        val mic = Button(this).apply { text = "Mic on · Tap to mute"; setOnClickListener { toggleMicrophone() } }
        val chat = Button(this).apply { text = "View chat" }
        val chatText = TextView(this).apply { text = "Live chat\nConnect your YouTube account to load real-time messages."; textSize = 11f; setTextColor(AndroidColor.WHITE); setPadding(dp(10), dp(8), dp(10), dp(8)); visibility = View.GONE }
        chat.setOnClickListener { chatText.visibility = if (chatText.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        val hide = Button(this).apply { text = "Minimise"; setOnClickListener { controls.visibility = View.GONE; overlayExpanded = false } }
        controls.addView(mic); controls.addView(chat); controls.addView(chatText); controls.addView(hide)
        bubble.setOnClickListener { overlayExpanded = !overlayExpanded; controls.visibility = if (overlayExpanded) View.VISIBLE else View.GONE }
        bubble.setOnTouchListener(object : View.OnTouchListener {
            private var sx = 0; private var sy = 0; private var tx = 0f; private var ty = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val p = root.layoutParams as? WindowManager.LayoutParams ?: return false
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = p.x; sy = p.y; tx = e.rawX; ty = e.rawY; return true }
                    MotionEvent.ACTION_MOVE -> { p.x = sx - (e.rawX - tx).toInt(); p.y = sy + (e.rawY - ty).toInt(); runCatching { manager.updateViewLayout(root, p) }; return true }
                    MotionEvent.ACTION_UP -> { overlayExpanded = !overlayExpanded; controls.visibility = if (overlayExpanded) View.VISIBLE else View.GONE; return true }
                }; return false
            }
        })
        root.addView(bubble); root.addView(controls)
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_SECURE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; x = dp(12); y = dp(150) }
        runCatching { manager.addView(root, params); overlayRoot = root }
    }

    private fun updateOverlayLabels(root: LinearLayout) {
        val controls = root.getChildAt(1) as? LinearLayout ?: return
        val mic = controls.getChildAt(0) as? Button ?: return
        mic.text = if (microphoneMuted.value) "Mic off · Tap to unmute" else "Mic on · Tap to mute"
    }

    private fun hideFloatingControls() {
        val root = overlayRoot ?: return
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        runCatching { manager.removeView(root) }; overlayRoot = null; overlayExpanded = false
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply { setColor(color); cornerRadius = radius * resources.displayMetrics.density }
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
        microphoneMuted.value = false
        Handler(Looper.getMainLooper()).post { showFloatingControls() }
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
        val activeStream = stream
        val now = SystemClock.elapsedRealtime()
        if (streaming.value && activeStream != null && now - lastBitrateAdjustmentAt >= 5_000L) {
            lastBitrateAdjustmentAt = now
            val congested = runCatching { activeStream.getStreamClient().hasCongestion() }.getOrDefault(false)
            if (congested) {
                stableNetworkSince = 0L
                val reduced = (activeVideoBitrate * 0.80).toInt().coerceAtLeast(600_000)
                if (reduced < activeVideoBitrate) {
                    activeVideoBitrate = reduced
                    runCatching { activeStream.setVideoBitrateOnFly(reduced) }
                    networkHealth.value = "Network congested · reducing bitrate"
                } else {
                    networkHealth.value = "Network congested · minimum bitrate reached"
                }
            } else {
                if (stableNetworkSince == 0L) stableNetworkSince = now
                if (now - stableNetworkSince >= 30_000L && activeVideoBitrate < requestedVideoBitrate) {
                    activeVideoBitrate = (activeVideoBitrate * 1.05).toInt().coerceAtMost(requestedVideoBitrate)
                    runCatching { activeStream.setVideoBitrateOnFly(activeVideoBitrate) }
                    stableNetworkSince = now
                }
                networkHealth.value = if (activeVideoBitrate < requestedVideoBitrate) {
                    "Connection recovering · ${activeVideoBitrate / 1000} kbps target"
                } else {
                    "Connection stable"
                }
            }
        }
        status.value = if (streaming.value) "LIVE • ${bitrate / 1000} kbps" else status.value
    }
    override fun onDisconnect() {
        streaming.value = false
        streamStartedAt.value = 0L
        Handler(Looper.getMainLooper()).post { hideFloatingControls() }
        status.value = "Disconnected"
    }
    override fun onAuthError() { status.value = "Destination authentication failed" }
    override fun onAuthSuccess() { status.value = "Destination authenticated" }

    override fun onDestroy() {
        stopRecording()
        hideFloatingControls()
        stream?.let { if (it.isStreaming) it.stopStream(); it.release() }
        stream = null
        projection?.stop()
        projection = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
