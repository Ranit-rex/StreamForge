package app.streamforge.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.graphics.Color as AndroidColor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
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
import android.view.TextureView
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
        const val ACTION_START_LOCAL_RECORDING = "app.streamforge.START_LOCAL_RECORDING"
        const val ACTION_STOP_RECORDING = "app.streamforge.STOP_RECORDING"
        const val ACTION_UPDATE_COLOR = "app.streamforge.UPDATE_COLOR"
        const val ACTION_APPLY_SCENE = "app.streamforge.APPLY_SCENE"
        const val EXTRA_SCENE_INDEX = "scene_index"
        const val ACTION_PREVIEW_SURFACE_READY = "app.streamforge.PREVIEW_SURFACE_READY"
        const val ACTION_STOP_PREVIEW = "app.streamforge.STOP_PREVIEW"
        const val ACTION_TOGGLE_MIC = "app.streamforge.TOGGLE_MIC"
        @Volatile var previewView: TextureView? = null
        const val EXTRA_COLOR_BRIGHTNESS = "color_brightness"
        const val EXTRA_COLOR_CONTRAST = "color_contrast"
        const val EXTRA_COLOR_SATURATION = "color_saturation"
        const val EXTRA_COLOR_SHARPNESS = "color_sharpness"
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
        val colorBrightness = MutableStateFlow(1.0f)
        val colorContrast = MutableStateFlow(1.0f)
        val colorSaturation = MutableStateFlow(1.0f)
        val colorSharpness = MutableStateFlow(0.0f)
    }

    private var stream: GenericStream? = null
    private val microphoneSource = MicrophoneSource()
    private var colorGradingFilter: ColorGradingFilterRender? = null
    private var projection: MediaProjection? = null
    private var recordFile: File? = null
    private var requestedVideoBitrate = 4_500_000
    private var activeVideoBitrate = 4_500_000
    private var lastBitrateAdjustmentAt = 0L
    private var stableNetworkSince = 0L
    private var activeStreamWidth = 1280
    private var activeStreamHeight = 720
    private var overlayRoot: LinearLayout? = null
    private var overlayExpanded = false
    private var overlayMicLabel: TextView? = null
    private var overlayTimerLabel: TextView? = null
    private var overlayStatsLabel: TextView? = null
    private var overlayChatText: TextView? = null
    private val overlayHandler = Handler(Looper.getMainLooper())
    private val overlayTicker = object : Runnable {
        override fun run() {
            if (overlayRoot == null) return
            val elapsed = ((System.currentTimeMillis() - streamStartedAt.value).coerceAtLeast(0L) / 1000L)
            overlayTimerLabel?.text = String.format(Locale.US, "%02d:%02d:%02d", elapsed / 3600, (elapsed % 3600) / 60, elapsed % 60)
            overlayStatsLabel?.text = "${streamResolution.value}  •  ${bitrateKbps.value} kbps  •  ${streamFps.value} FPS"
            overlayHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        stream = GenericStream(this, this, NoVideoSource(), microphoneSource).apply {
            getGlInterface().setForceRender(true, 15)
            prepareAudio(44_100, true, 128_000, echoCanceler = true, noiseSuppressor = true)
            // A smaller queue detects upload congestion sooner on mobile networks.
            getStreamClient().resizeCache(200)
            getStreamClient().setReTries(5)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_STREAM -> startScreenStream(intent)
            ACTION_STOP_STREAM -> stopStream()
            ACTION_START_RECORDING -> startRecording()
            ACTION_START_LOCAL_RECORDING -> startLocalRecording(intent)
            ACTION_STOP_RECORDING -> stopRecording()
            ACTION_PREVIEW_SURFACE_READY -> startPreviewIfAvailable()
            ACTION_STOP_PREVIEW -> stream?.let { if (it.isOnPreview) it.stopPreview() }
            ACTION_TOGGLE_MIC -> toggleMicrophone()
            ACTION_APPLY_SCENE -> applyScene(intent.getIntExtra(EXTRA_SCENE_INDEX, 0))
            ACTION_UPDATE_COLOR -> updateColorTuning(
                intent.getFloatExtra(EXTRA_COLOR_BRIGHTNESS, colorBrightness.value),
                intent.getFloatExtra(EXTRA_COLOR_CONTRAST, colorContrast.value),
                intent.getFloatExtra(EXTRA_COLOR_SATURATION, colorSaturation.value),
                intent.getFloatExtra(EXTRA_COLOR_SHARPNESS, colorSharpness.value)
            )
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
        activeStreamWidth = width
        activeStreamHeight = height
        val bitrate = intent.getIntExtra(EXTRA_BITRATE, 4_500_000).coerceIn(800_000, 35_000_000)
        if (projectionData == null || endpoint.isBlank()) {
            status.value = "Missing screen permission or destination"
            stopForeground(STOP_FOREGROUND_REMOVE)
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
            activeStreamWidth = width
            activeStreamHeight = height
            bitrateKbps.value = 0L
            // RootEncoder requires encoder color metadata to be configured before prepareVideo.
            // Calling forceBt709Color after prepareVideo throws:
            // "Encoder already prepared, this must be called before prepareVideo".
            stream?.forceBt709Color(true)
            val prepared = stream?.prepareVideo(width, height, bitrate, fps, rotation = 0) == true
            if (!prepared) throw IllegalStateException("Video encoder could not prepare this quality")
            // MediaProjection already provides display orientation. RootEncoder otherwise
            // applies its camera orientation transform, which can produce a blank/white
            // GL frame for ScreenSource on some devices.
            stream?.getGlInterface()?.setCameraOrientation(0)
            stream?.changeVideoSource(ScreenSource(applicationContext, projection!!))
            // Install the entire colour chain before the GL renderer begins streaming.
            // RootEncoder queues filter changes asynchronously on its GL thread. Building
            // the chain from a live slider callback can briefly expose a partial chain
            // (and turn the outgoing frame white). Slider callbacks only update values.
            installColorFilters()
            // Start the broadcast first. ScreenSource must create exactly one
            // MediaProjection virtual display; preview attaches to the running GL
            // pipeline only after streaming has been started.
            stream?.startStream(endpoint)
            status.value = "Connecting…"
            startPreviewIfAvailable()
        } catch (error: Exception) {
            stopStream("Could not start: ${error.message ?: error.javaClass.simpleName}")
        }
    }


    private fun startPreviewIfAvailable() {
        val activeStream = stream ?: return
        val view = previewView ?: return
        // Never initialise a screen-capture preview while idle. It can create a
        // VirtualDisplay before the broadcast and collide with MediaProjection.
        if (!activeStream.isStreaming || projection == null || activeStream.isOnPreview) return
        if (!view.isAvailable || view.width <= 0 || view.height <= 0) return
        runCatching { activeStream.startPreview(view) }
            .onFailure { status.value = "Preview unavailable: ${it.message ?: "surface error"}" }
    }

    private fun installColorFilters() {
        val activeStream = stream ?: return
        // Load the last user-selected tuning only when the stream pipeline is being prepared.
        val prefs = getSharedPreferences("streamforge_studio", 0)
        colorBrightness.value = prefs.getFloat("color_brightness", colorBrightness.value).coerceIn(0.8f, 1.35f)
        colorContrast.value = prefs.getFloat("color_contrast", colorContrast.value).coerceIn(0.8f, 1.4f)
        colorSaturation.value = prefs.getFloat("color_saturation", colorSaturation.value).coerceIn(0f, 2f)
        colorSharpness.value = prefs.getFloat("color_sharpness", colorSharpness.value).coerceIn(0f, 0.8f)
        val filter = ColorGradingFilterRender().apply {
            setColorTuning(
                colorBrightness.value - 1.0f,
                colorContrast.value,
                colorSaturation.value,
                colorSharpness.value
            )
        }
        // Exactly one GL pass is installed before streaming starts. Slider
        // changes update uniforms only; no filter-list mutations happen live.
        activeStream.getGlInterface().setFilter(filter)
        colorGradingFilter = filter
        applyScene(getSharedPreferences("streamforge_studio", 0).getInt("active_scene", 0))
    }

    private fun applyScene(sceneIndex: Int) {
        val filter = colorGradingFilter ?: return
        val prefs = getSharedPreferences("streamforge_studio", 0)
        val raw = prefs.getString("scenes", "[]").orEmpty()
        val scenes = runCatching { org.json.JSONArray(raw) }.getOrNull()
        if (scenes == null || scenes.length() == 0) {
            filter.setSceneOverlayBitmap(null)
            return
        }
        val safeIndex = sceneIndex.coerceIn(0, scenes.length() - 1)
        val scene = scenes.optJSONObject(safeIndex) ?: return
        val layers = scene.optJSONArray("layers") ?: org.json.JSONArray()
        val screenLayer = (0 until layers.length())
            .mapNotNull { layers.optJSONObject(it) }
            .firstOrNull { it.optString("kind") == "screen" && it.optBoolean("visible", true) }
        if (screenLayer != null) {
            filter.setScreenRect(
                screenLayer.optDouble("x", 0.0).toFloat(),
                screenLayer.optDouble("y", 0.0).toFloat(),
                screenLayer.optDouble("width", 1.0).toFloat(),
                screenLayer.optDouble("height", 1.0).toFloat()
            )
        } else {
            filter.setScreenRect(0f, 0f, 1f, 1f)
        }
        val outputWidth = activeStreamWidth.coerceAtMost(1920).coerceAtLeast(640)
        val outputHeight = activeStreamHeight.coerceAtMost(1080).coerceAtLeast(360)
        val bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        for (i in 0 until layers.length()) {
            val item = layers.optJSONObject(i) ?: continue
            if (!item.optBoolean("visible", true)) continue
            val kind = item.optString("kind")
            if (kind == "screen") continue
            val left = item.optDouble("x", .08).toFloat().coerceIn(0f, 1f) * outputWidth
            val top = item.optDouble("y", .08).toFloat().coerceIn(0f, 1f) * outputHeight
            val width = item.optDouble("width", .42).toFloat().coerceIn(.02f, 1f) * outputWidth
            val height = item.optDouble("height", .16).toFloat().coerceIn(.02f, 1f) * outputHeight
            val rect = RectF(left, top, (left + width).coerceAtMost(outputWidth.toFloat()), (top + height).coerceAtMost(outputHeight.toFloat()))
            val alpha = (item.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f) * 255).toInt()

            when (kind) {
                "text" -> {
                    val value = item.optString("text").trim()
                    if (value.isNotEmpty()) {
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = AndroidColor.WHITE
                            this.alpha = alpha
                            typeface = Typeface.create("sans-serif", Typeface.BOLD)
                            textSize = (rect.height() * .58f).coerceIn(20f, 112f)
                            setShadowLayer(5f, 2f, 2f, AndroidColor.BLACK)
                        }
                        val baseline = rect.top + (rect.height() - (paint.descent() + paint.ascent())) / 2f
                        canvas.save()
                        canvas.clipRect(rect)
                        canvas.drawText(value, rect.left + rect.width() * .025f, baseline, paint)
                        canvas.restore()
                    }
                }
                "image" -> {
                    val uri = runCatching { Uri.parse(item.optString("text")) }.getOrNull()
                    val image = uri?.let { parsed ->
                        runCatching { contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
                    }
                    if (image != null) {
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alpha }
                        canvas.drawBitmap(image, null, rect, paint)
                        image.recycle()
                    }
                }
                "camera" -> {
                    val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = AndroidColor.argb((alpha * .72f).toInt(), 28, 42, 58)
                    }
                    canvas.drawRoundRect(rect, 16f, 16f, background)
                    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = AndroidColor.WHITE
                        this.alpha = alpha
                        textSize = (rect.height() * .16f).coerceIn(14f, 32f)
                        typeface = Typeface.DEFAULT_BOLD
                    }
                    canvas.drawText("CAMERA SOURCE", rect.left + 12f, rect.centerY(), label)
                }
            }
        }
        filter.setSceneOverlayBitmap(bitmap)
    }

    private fun updateColorTuning(brightness: Float, contrast: Float, saturation: Float, sharpness: Float) {
        colorBrightness.value = brightness.coerceIn(0.8f, 1.35f)
        colorContrast.value = contrast.coerceIn(0.8f, 1.4f)
        colorSaturation.value = saturation.coerceIn(0f, 2f)
        colorSharpness.value = sharpness.coerceIn(0f, 0.8f)

        // Mutate only volatile shader parameters on the existing single filter.
        colorGradingFilter?.setColorTuning(
            colorBrightness.value - 1.0f,
            colorContrast.value,
            colorSaturation.value,
            colorSharpness.value
        )
    }

    private fun startLocalRecording(intent: Intent) {
        if (streaming.value || recording.value) {
            startRecording()
            return
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val projectionData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val width = intent.getIntExtra(EXTRA_WIDTH, 1280).coerceIn(640, 3840)
        val height = intent.getIntExtra(EXTRA_HEIGHT, 720).coerceIn(360, 2160)
        val fps = intent.getIntExtra(EXTRA_FPS, 30).coerceIn(24, 60)
        val bitrate = intent.getIntExtra(EXTRA_BITRATE, 4_500_000).coerceIn(1_000_000, 25_000_000)
        if (resultCode != android.app.Activity.RESULT_OK || projectionData == null) {
            status.value = "Screen recording permission was not granted"
            stopSelf()
            return
        }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification("Preparing local screen recording…"),
                if (Build.VERSION.SDK_INT >= 29) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else 0
            )
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, projectionData)
                ?: throw IllegalStateException("Screen capture permission was not granted")
            activeStreamWidth = width
            activeStreamHeight = height
            streamResolution.value = "$width × $height"
            streamFps.value = fps
            stream?.forceBt709Color(true)
            val prepared = stream?.prepareVideo(width, height, bitrate, fps, rotation = 0) == true
            if (!prepared) throw IllegalStateException("Video encoder could not prepare this recording quality")
            stream?.getGlInterface()?.setCameraOrientation(0)
            stream?.changeVideoSource(ScreenSource(applicationContext, projection!!))
            installColorFilters()
            startRecording()
        } catch (error: Exception) {
            status.value = "Could not start recording: ${error.message ?: error.javaClass.simpleName}"
            projection?.stop()
            projection = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startRecording() {
        val activeStream = stream ?: return
        if (activeStream.isRecording) return
        val directory = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        if (!directory.exists()) directory.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        recordFile = File(directory, "StreamForge_$stamp.mp4")
        runCatching { activeStream.startRecord(recordFile!!.absolutePath) { } }
            .onSuccess {
                recording.value = true
                status.value = if (streaming.value) "Recording locally" else "Recording screen locally"
            }
            .onFailure { error ->
                recording.value = false
                status.value = "Recording failed: ${error.message ?: error.javaClass.simpleName}"
                if (!streaming.value) {
                    projection?.stop()
                    projection = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
    }

    private fun stopRecording() {
        val activeStream = stream
        if (activeStream?.isRecording == true) activeStream.stopRecord()
        recording.value = false
        status.value = if (streaming.value) "LIVE" else "Recording saved"
        if (!streaming.value && projection != null) {
            runCatching { activeStream?.let { if (it.isOnPreview) it.stopPreview() } }
            projection?.stop()
            projection = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopStream(finalStatus: String = "Stream stopped") {
        stream?.let {
            if (it.isStreaming) it.stopStream()
            if (it.isOnPreview) it.stopPreview()
        }
        streaming.value = false
        streamStartedAt.value = 0L
        hideFloatingControls()
        projection?.stop()
        projection = null
        status.value = finalStatus
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

    private var overlayPanel: LinearLayout? = null

    private fun showFloatingControls() {
        if (overlayRoot != null || !android.provider.Settings.canDrawOverlays(this)) return
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun shape(color: Int, radius: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
        fun text(value: String, size: Float, color: Int = AndroidColor.WHITE, bold: Boolean = false) =
            TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(6), dp(8), dp(6))
            }

        // Small circular live bubble, inspired by common mobile-streaming controls,
        // with StreamForge's own colors and visual identity.
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF526EFF.toInt(), 0xFF253B80.toInt())
            ).apply { shape = GradientDrawable.OVAL; setStroke(dp(2), 0xFF8EA5FF.toInt()) }
            elevation = dp(10).toFloat()
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val live = text("● LIVE", 9f, 0xFFFF777E.toInt(), true)
        val viewers = text("◉ —", 12f, AndroidColor.WHITE, true)
        bubble.addView(live)
        bubble.addView(viewers)
        val bubbleParams = WindowManager.LayoutParams(
            dp(62), dp(62), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = dp(12); y = dp(155) }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = shape(0xF21A2434.toInt(), 20)
            elevation = dp(18).toFloat()
            visibility = View.GONE
        }
        overlayPanel = panel
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(text("STREAMFORGE  •  LIVE", 12f, 0xFF9DB2FF.toInt(), true), LinearLayout.LayoutParams(0, dp(38), 1f))
        val close = text("×", 23f, 0xFFDDE6F5.toInt(), true)
        close.setOnClickListener { panel.visibility = View.GONE }
        heading.addView(close, LinearLayout.LayoutParams(dp(38), dp(38)))
        panel.addView(heading)

        val timer = text("00:00:00", 22f, AndroidColor.WHITE, true)
        overlayTimerLabel = timer
        panel.addView(timer)
        val stats = text("Viewers —  •  ${streamResolution.value}  •  ${bitrateKbps.value} kbps", 10f, 0xFFB9C8DC.toInt())
        overlayStatsLabel = stats
        panel.addView(stats)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        val mic = text(if (microphoneMuted.value) "MIC OFF" else "MIC ON", 11f, AndroidColor.WHITE, true).apply {
            background = shape(0xFF344B6A.toInt(), 12)
            setOnClickListener { toggleMicrophone() }
        }
        overlayMicLabel = mic
        val chat = text("CHAT", 11f, AndroidColor.WHITE, true).apply {
            background = shape(0xFF344B6A.toInt(), 12)
            setOnClickListener {
                android.widget.Toast.makeText(this@StreamService, "Chat source integration is coming next.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        val stop = text("END LIVE", 11f, AndroidColor.WHITE, true).apply {
            background = shape(0xFFB83D50.toInt(), 12)
            setOnClickListener { stopStream("Stream stopped") }
        }
        val buttonParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) }
        buttons.addView(mic, buttonParams)
        buttons.addView(chat, buttonParams)
        buttons.addView(stop, LinearLayout.LayoutParams(0, dp(42), 1f))
        panel.addView(buttons)

        val panelParams = WindowManager.LayoutParams(
            dp(280), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = bubbleParams.x; y = bubbleParams.y + dp(70) }

        // Keep the control window physically attached to the bubble. Both overlays
        // share the same right-edge offset; the panel flips above the bubble near
        // the bottom of the screen so it remains visible.
        fun positionPanelWithBubble() {
            val panelHeight = panel.height.takeIf { it > 0 } ?: dp(190)
            val screenHeight = resources.displayMetrics.heightPixels
            panelParams.x = bubbleParams.x
            val below = bubbleParams.y + dp(70)
            panelParams.y = if (below + panelHeight <= screenHeight - dp(12)) {
                below
            } else {
                (bubbleParams.y - panelHeight - dp(8)).coerceAtLeast(dp(8))
            }
            if (panel.parent != null) runCatching { manager.updateViewLayout(panel, panelParams) }
        }

        bubble.setOnClickListener {
            if (panel.visibility == View.VISIBLE) {
                panel.visibility = View.GONE
            } else {
                positionPanelWithBubble()
                panel.visibility = View.VISIBLE
                panel.post { positionPanelWithBubble() }
            }
        }
        bubble.setOnTouchListener(object : View.OnTouchListener {
            private var sx = 0
            private var sy = 0
            private var tx = 0f
            private var ty = 0f
            private var dragged = false
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = bubbleParams.x; sy = bubbleParams.y
                        tx = event.rawX; ty = event.rawY; dragged = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - tx).toInt()
                        val dy = (event.rawY - ty).toInt()
                        if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(5)) dragged = true
                        bubbleParams.x = (sx - dx).coerceAtLeast(0)
                        bubbleParams.y = (sy + dy).coerceAtLeast(0)
                        runCatching { manager.updateViewLayout(bubble, bubbleParams) }
                        positionPanelWithBubble()
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!dragged) bubble.performClick()
                        return true
                    }
                }
                return false
            }
        })

        runCatching {
            manager.addView(bubble, bubbleParams)
            manager.addView(panel, panelParams)
            overlayRoot = bubble
            overlayHandler.removeCallbacks(overlayTicker)
            overlayHandler.post(overlayTicker)
        }.onFailure {
            runCatching { manager.removeView(bubble) }
            runCatching { manager.removeView(panel) }
            overlayRoot = null
            overlayPanel = null
            status.value = "Floating controls unavailable: ${it.message ?: "overlay error"}"
        }
    }

    private fun updateOverlayLabels(root: LinearLayout) {
        overlayMicLabel?.text = if (microphoneMuted.value) "MIC OFF" else "MIC ON"
        overlayStatsLabel?.text = "Viewers —  •  ${streamResolution.value}  •  ${bitrateKbps.value} kbps  •  ${streamFps.value} FPS"
    }

    private fun hideFloatingControls() {
        val root = overlayRoot ?: return
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayHandler.removeCallbacks(overlayTicker)
        runCatching { manager.removeView(root) }
        overlayPanel?.let { panel -> runCatching { manager.removeView(panel) } }
        overlayPanel = null
        overlayRoot = null
        overlayExpanded = false
        overlayMicLabel = null
        overlayTimerLabel = null
        overlayStatsLabel = null
        overlayChatText = null
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
        val retrying = runCatching { stream?.getStreamClient()?.reTry(3_000L, reason) ?: false }.getOrDefault(false)
        if (retrying) {
            status.value = "Reconnecting… $reason"
            return
        }
        stopStream("Connection failed: $reason")
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
    override fun onAuthError() { stopStream("Authentication failed · check your stream key") }
    override fun onAuthSuccess() { status.value = "Destination authenticated" }

    override fun onDestroy() {
        stopRecording()
        hideFloatingControls()
        stream?.let { if (it.isStreaming) it.stopStream(); if (it.isOnPreview) it.stopPreview(); it.release() }
        stream = null
        previewView = null
        colorGradingFilter = null
        projection?.stop()
        projection = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
