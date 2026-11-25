package app.streamforge.stream

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.media.MediaCodecList
import android.media.MediaMetadataRetriever
import android.app.NotificationManager
import android.app.Service
import android.graphics.Color as AndroidColor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Movie
import android.graphics.RectF
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.MediaFormat
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
import com.pedro.encoder.input.sources.audio.MixAudioSource
import com.pedro.encoder.input.sources.audio.SilenceAudioSource
import com.pedro.encoder.input.sources.video.NoVideoSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.generic.GenericStream
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class StreamService : Service(), ConnectChecker {
    companion object {
        const val ACTION_START_STREAM = "app.streamforge.START_STREAM"
        const val ACTION_STOP_STREAM = "app.streamforge.STOP_STREAM"
        const val ACTION_START_RECORDING = "app.streamforge.START_RECORDING"
        const val ACTION_START_LOCAL_RECORDING = "app.streamforge.START_LOCAL_RECORDING"
        const val ACTION_START_STUDIO_PREVIEW = "app.streamforge.START_STUDIO_PREVIEW"
        const val ACTION_STOP_STUDIO_PREVIEW = "app.streamforge.STOP_STUDIO_PREVIEW"
        const val ACTION_STOP_RECORDING = "app.streamforge.STOP_RECORDING"
        const val ACTION_TOGGLE_RECORDING_PAUSE = "app.streamforge.TOGGLE_RECORDING_PAUSE"
        const val ACTION_UPDATE_COLOR = "app.streamforge.UPDATE_COLOR"
        const val ACTION_APPLY_SCENE = "app.streamforge.APPLY_SCENE"
        const val EXTRA_SCENE_INDEX = "scene_index"
        const val EXTRA_SCENE_JSON = "scene_json"
        const val ACTION_PREVIEW_SURFACE_READY = "app.streamforge.PREVIEW_SURFACE_READY"
        const val ACTION_STOP_PREVIEW = "app.streamforge.STOP_PREVIEW"
        const val ACTION_CHANGE_CAPTURE_SOURCE = "app.streamforge.CHANGE_CAPTURE_SOURCE"
        const val EXTRA_PREVIEW_GENERATION = "preview_surface_generation"
        @Volatile var previewSurfaceGeneration = 0
        const val ACTION_TOGGLE_MIC = "app.streamforge.TOGGLE_MIC"
        const val ACTION_SET_LIVE_BITRATE = "app.streamforge.SET_LIVE_BITRATE"
        const val ACTION_UPDATE_AUDIO_MIX = "app.streamforge.UPDATE_AUDIO_MIX"
        const val EXTRA_MIC_GAIN = "audio_mic_gain"
        const val EXTRA_GAME_GAIN = "audio_game_gain"
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
        val recordingPaused = MutableStateFlow(false)
        val studioPreviewReady = MutableStateFlow(false)
        val recordingStartedAt = MutableStateFlow(0L)
        val bitrateKbps = MutableStateFlow(0L)
        val streamResolution = MutableStateFlow("1280 × 720")
        val streamFps = MutableStateFlow(30)
        val streamStartedAt = MutableStateFlow(0L)
        val networkHealth = MutableStateFlow("Network monitoring")
        val microphoneMuted = MutableStateFlow(false)
        val microphoneGain = MutableStateFlow(1f)
        val gameAudioGain = MutableStateFlow(1f)
        val gameAudioAvailable = MutableStateFlow(false)
        val colorBrightness = MutableStateFlow(1.0f)
        val colorContrast = MutableStateFlow(1.0f)
        val colorSaturation = MutableStateFlow(1.0f)
        val colorSharpness = MutableStateFlow(0.0f)
    }

    private var stream: GenericStream? = null
    private val microphoneSource = MicrophoneSource()
    private var mixedAudioSource: MixAudioSource? = null
    private var colorGradingFilter: ColorGradingFilterRender? = null
    private var projection: MediaProjection? = null
    private var activeScreenSource: ScreenSource? = null
    private var recordFile: File? = null
    private var requestedVideoBitrate = 4_500_000
    private var adaptiveBitrateEnabled = true
    private var activeVideoBitrate = 4_500_000
    private var lastBitrateAdjustmentAt = 0L
    private var stableNetworkSince = 0L
    private var activeStreamWidth = 1280
    private var activeStreamHeight = 720
    private var activeSceneIndex = 0
    private var lowRamDevice = false
    private val gifMovies = mutableMapOf<String, Movie>()
    private val sceneImages = mutableMapOf<String, Bitmap>()
    // Decode video frames away from the main/GL scheduling path. Only the newest
    // decoded frame is retained for each overlay so slow devices can skip frames.
    private val videoDecodeExecutor = Executors.newSingleThreadExecutor()
    private val videoDecodePending = ConcurrentHashMap.newKeySet<String>()
    private val videoDecoders = ConcurrentHashMap<String, HardwareVideoFrameDecoder>()
    private val videoStartedAt = ConcurrentHashMap<String, Long>()
    private val videoDurationMs = ConcurrentHashMap<String, Long>()
    private val videoSourceFps = ConcurrentHashMap<String, Int>()
    private val videoFrameBitmaps = ConcurrentHashMap<String, Bitmap>()
    private val videoLastFrameAt = ConcurrentHashMap<String, Long>()
    // Keep MediaCodec as the primary overlay decoder. Some Android codec
    // implementations advertise flexible YUV but stop returning Image frames
    // after the first output; in that case use timestamp-based frame extraction
    // for this overlay only, without changing the stream encoder or quality.
    private val videoFallbackRetrievers = ConcurrentHashMap<String, MediaMetadataRetriever>()
    private val videoNullFrameCounts = ConcurrentHashMap<String, Int>()
    private val videoLastPts = ConcurrentHashMap<String, Long>()
    private val videoLastPtsProgressAt = ConcurrentHashMap<String, Long>()
    // Allow one in-place hardware decoder restart before considering any alternate path.
    private val videoHardwareRestartCounts = ConcurrentHashMap<String, Int>()
    private val videoFrameLock = Any()
    @Volatile private var requestedVideoUris: Set<String> = emptySet()
    private val gifStartedAt = mutableMapOf<String, Long>()
    private val gifFrameHandler = Handler(Looper.getMainLooper())
    private val gifFrameRunnable = object : Runnable {
        override fun run() {
            if (streaming.value || recording.value || studioPreviewReady.value) {
                applyScene(activeSceneIndex)
            }
        }
    }
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
            val recentChat = YouTubeLiveChat.messages.value.takeLast(4).joinToString("\n")
            overlayChatText?.text = if (recentChat.isBlank()) YouTubeLiveChat.connectionState.value else recentChat
            overlayHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val activityManager = getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        lowRamDevice = activityManager?.isLowRamDevice == true || (memoryInfo.totalMem in 1L..2_500_000_000L)
        val studioPrefs = getSharedPreferences("streamforge_studio", 0)
        val audioBitrate = studioPrefs.getInt("audio_bitrate_kbps", 128).coerceIn(64, 320) * 1_000
        val audioEnhancements = studioPrefs.getBoolean("audio_enhancements", true)
        microphoneGain.value = studioPrefs.getFloat("audio_mic_gain", 1f).coerceIn(0f, 2f)
        gameAudioGain.value = studioPrefs.getFloat("audio_game_gain", 1f).coerceIn(0f, 2f)
        stream = GenericStream(this, this, NoVideoSource(), microphoneSource).apply {
            // Low-RAM devices render at a capped cadence to reduce CPU/GPU heat.
            getGlInterface().setForceRender(true, if (lowRamDevice) 33 else 5)
            prepareAudio(44_100, true, audioBitrate, echoCanceler = audioEnhancements, noiseSuppressor = audioEnhancements)
            // A smaller queue detects upload congestion sooner on mobile networks.
            getStreamClient().resizeCache(200)
            getStreamClient().setReTries(5)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_STREAM -> startScreenStream(intent)
            ACTION_START_STUDIO_PREVIEW -> startStudioPreview(intent)
            ACTION_CHANGE_CAPTURE_SOURCE -> changeCaptureSource(intent)
            ACTION_STOP_STUDIO_PREVIEW -> stopStudioPreview()
            ACTION_STOP_STREAM -> stopStream()
            ACTION_START_RECORDING -> startRecording()
            ACTION_START_LOCAL_RECORDING -> startLocalRecording(intent)
            ACTION_STOP_RECORDING -> stopRecording()
            ACTION_TOGGLE_RECORDING_PAUSE -> toggleRecordingPause()
            ACTION_PREVIEW_SURFACE_READY -> attachPreviewSurface(intent)
            ACTION_STOP_PREVIEW -> detachPreviewSurface(intent)
            ACTION_TOGGLE_MIC -> toggleMicrophone()
            ACTION_SET_LIVE_BITRATE -> setLiveBitrate(intent.getIntExtra(EXTRA_BITRATE, requestedVideoBitrate))
            ACTION_UPDATE_AUDIO_MIX -> updateAudioMix(
                intent.getFloatExtra(EXTRA_MIC_GAIN, microphoneGain.value),
                intent.getFloatExtra(EXTRA_GAME_GAIN, gameAudioGain.value)
            )
            ACTION_APPLY_SCENE -> applyScene(intent.getIntExtra(EXTRA_SCENE_INDEX, 0), intent.getStringExtra(EXTRA_SCENE_JSON))
            ACTION_UPDATE_COLOR -> updateColorTuning(
                intent.getFloatExtra(EXTRA_COLOR_BRIGHTNESS, colorBrightness.value),
                intent.getFloatExtra(EXTRA_COLOR_CONTRAST, colorContrast.value),
                intent.getFloatExtra(EXTRA_COLOR_SATURATION, colorSaturation.value),
                intent.getFloatExtra(EXTRA_COLOR_SHARPNESS, colorSharpness.value)
            )
        }
        return START_NOT_STICKY
    }

    private data class EncoderProfile(val width: Int, val height: Int, val fps: Int, val minBitrate: Int, val maxBitrate: Int)

    /** Resolve a supported H.264 profile; requested UI qualities are not hardware guarantees. */
    private fun resolveEncoderProfile(requestedWidth: Int, requestedHeight: Int, requestedFps: Int): EncoderProfile {
        val supportedSizes = if (lowRamDevice) listOf(1280 to 720, 854 to 480) else listOf(3840 to 2160, 2560 to 1440, 1920 to 1080, 1280 to 720, 854 to 480)
        val sizes = supportedSizes.filter { it.first <= requestedWidth && it.second <= requestedHeight }
            .sortedByDescending { it.first * it.second }
        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals("video/avc", ignoreCase = true) }
        }
        for ((width, height) in sizes) {
            val fpsOptions = if (lowRamDevice) listOf(30, 24) else listOf(requestedFps, 30, 24).distinct()
            for (fps in fpsOptions) {
                val caps = encoders.firstNotNullOfOrNull { info ->
                    runCatching {
                        val videoCaps = info.getCapabilitiesForType("video/avc").videoCapabilities
                        if (videoCaps.areSizeAndRateSupported(width, height, fps.toDouble())) videoCaps else null
                    }.getOrNull()
                }
                if (caps != null) return EncoderProfile(width, height, fps, caps.bitrateRange.lower, caps.bitrateRange.upper)
            }
        }
        throw IllegalStateException("This device has no compatible H.264 screen encoder profile")
    }

    /** Keep app-window capture dimensions in sync with Android's selected-content picker. */
    private fun createScreenSource(activeProjection: MediaProjection): ScreenSource {
        val callback = object : MediaProjection.Callback() {
            override fun onCapturedContentResize(width: Int, height: Int) {
                if (width > 0 && height > 0) {
                    runCatching { activeScreenSource?.resize(width, height) }
                    status.value = "Captured app · ${width}×${height}"
                }
            }

            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                status.value = if (isVisible) {
                    "Selected app visible"
                } else {
                    "Selected app hidden · keep it visible in split-screen/PiP or choose Full display"
                }
            }
        }
        return ScreenSource(applicationContext, activeProjection, callback).also {
            activeScreenSource = it
        }
    }

    private fun startStudioPreview(intent: Intent) {
        if (streaming.value || recording.value || studioPreviewReady.value) return
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
        if (projectionData == null) {
            status.value = "Screen permission was not granted"
            stopSelf()
            return
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification("Studio preview readying…"),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            )
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, projectionData)
                ?: throw IllegalStateException("Screen capture permission was not granted")
            val profile = resolveEncoderProfile(width, height, fps)
            activeStreamWidth = profile.width
            activeStreamHeight = profile.height
            streamResolution.value = "${profile.width} × ${profile.height}"
            streamFps.value = profile.fps
            stream?.forceBt709Color(true)
            val prepared = stream?.prepareVideo(profile.width, profile.height, 4_500_000.coerceIn(profile.minBitrate, profile.maxBitrate), profile.fps, rotation = 0) == true
            if (!prepared) throw IllegalStateException("Could not prepare the preview encoder")
            stream?.getGlInterface()?.setCameraOrientation(0)
            stream?.changeVideoSource(createScreenSource(projection!!))
            configureAudioMix()
            installColorFilters()
            studioPreviewReady.value = true
            status.value = "Studio preview ready · not live"
            startPreviewIfAvailable()
            applyScene(getSharedPreferences("streamforge_studio", 0).getInt("active_scene", 0))
        } catch (error: Exception) {
            studioPreviewReady.value = false
            projection?.stop()
            projection = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            status.value = "Preview failed: ${error.message ?: error.javaClass.simpleName}"
            stopSelf()
        }
    }

    private fun stopStudioPreview() {
        if (streaming.value || recording.value) return
        runCatching { stream?.let { if (it.isOnPreview) it.stopPreview() } }
        projection?.stop()
        projection = null
        activeScreenSource = null
        studioPreviewReady.value = false
        gifFrameHandler.removeCallbacks(gifFrameRunnable)
        colorGradingFilter = null
        status.value = "Preview stopped"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startScreenStream(intent: Intent) {
        val endpoint = intent.getStringExtra(EXTRA_ENDPOINT).orEmpty()
        val width = intent.getIntExtra(EXTRA_WIDTH, 1280).coerceIn(640, 3840)
        val height = intent.getIntExtra(EXTRA_HEIGHT, 720).coerceIn(360, 2160)
        val fps = intent.getIntExtra(EXTRA_FPS, 30).coerceIn(24, 60)
        val bitrate = intent.getIntExtra(EXTRA_BITRATE, 4_500_000).coerceIn(800_000, 35_000_000)
        val usePreparedPreview = studioPreviewReady.value && projection != null
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val projectionData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (endpoint.isBlank() || (!usePreparedPreview && projectionData == null)) {
            status.value = "Missing screen permission or destination"
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification("Preparing stream…"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        )
        try {
            if (!usePreparedPreview) {
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = manager.getMediaProjection(resultCode, projectionData!!)
                    ?: throw IllegalStateException("Screen capture permission was not granted")
                val profile = resolveEncoderProfile(width, height, fps)
                val encoderBitrate = bitrate.coerceIn(profile.minBitrate, profile.maxBitrate)
                requestedVideoBitrate = encoderBitrate
                activeVideoBitrate = encoderBitrate
                adaptiveBitrateEnabled = getSharedPreferences("streamforge_studio", 0).getBoolean("adaptive_bitrate", true)
                lastBitrateAdjustmentAt = 0L
                stableNetworkSince = 0L
                networkHealth.value = if (profile.width != width || profile.height != height || profile.fps != fps || encoderBitrate != bitrate) {
                    "Device encoder limit · ${profile.width}×${profile.height} @ ${profile.fps} FPS · ${encoderBitrate / 1_000_000f} Mbps"
                } else "Network monitoring"
                streamResolution.value = "${profile.width} × ${profile.height}"
                streamFps.value = profile.fps
                activeStreamWidth = profile.width
                activeStreamHeight = profile.height
                bitrateKbps.value = 0L
                stream?.forceBt709Color(true)
                val prepared = stream?.prepareVideo(profile.width, profile.height, encoderBitrate, profile.fps, rotation = 0) == true
                if (!prepared) throw IllegalStateException("The device rejected ${profile.width}×${profile.height} @ ${profile.fps} FPS. Choose a lower preset.")
                stream?.getGlInterface()?.setCameraOrientation(0)
                stream?.changeVideoSource(createScreenSource(projection!!))
                configureAudioMix()
                installColorFilters()
            } else {
                requestedVideoBitrate = bitrate
                activeVideoBitrate = bitrate
                adaptiveBitrateEnabled = getSharedPreferences("streamforge_studio", 0).getBoolean("adaptive_bitrate", true)
                lastBitrateAdjustmentAt = 0L
                stableNetworkSince = 0L
            }
            stream?.startStream(endpoint)
            status.value = "Connecting…"
            startPreviewIfAvailable()
        } catch (error: Exception) {
            stopStream("Could not start: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun attachPreviewSurface(intent: Intent) {
        val generation = intent.getIntExtra(EXTRA_PREVIEW_GENERATION, -1)
        if (generation != previewSurfaceGeneration) return
        val activeStream = stream ?: return
        if (activeStream.isOnPreview) runCatching { activeStream.stopPreview() }
        Handler(Looper.getMainLooper()).postDelayed({
            if (generation == previewSurfaceGeneration && previewView?.isAvailable == true) startPreviewIfAvailable()
        }, 120L)
    }

    private fun detachPreviewSurface(intent: Intent) {
        val generation = intent.getIntExtra(EXTRA_PREVIEW_GENERATION, -1)
        if (generation != previewSurfaceGeneration) return
        runCatching { stream?.let { if (it.isOnPreview) it.stopPreview() } }
    }

    private fun changeCaptureSource(intent: Intent) {
        val wasStreaming = streaming.value
        if (recording.value) {
            status.value = "Stop local recording before changing capture source"
            return
        }
        if (wasStreaming) {
            runCatching { stream?.let { if (it.isStreaming) it.stopStream() } }
            streaming.value = false
            streamStartedAt.value = 0L
            hideFloatingControls()
            YouTubeLiveChat.stop()
        } else {
            runCatching { stream?.let { if (it.isOnPreview) it.stopPreview() } }
        }
        projection?.stop()
        projection = null
        activeScreenSource = null
        studioPreviewReady.value = false
        if (wasStreaming) {
            status.value = "Capture source changed · reconnecting stream…"
            Handler(Looper.getMainLooper()).postDelayed({ startScreenStream(intent) }, 500L)
        } else {
            startStudioPreview(intent)
        }
    }

    private fun startPreviewIfAvailable() {
        val activeStream = stream ?: return
        val view = previewView ?: return
        if ((!activeStream.isStreaming && !studioPreviewReady.value && !recording.value) || projection == null || activeStream.isOnPreview) return
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

    private fun applyScene(sceneIndex: Int, sceneJsonOverride: String? = null) {
        val filter = colorGradingFilter ?: return
        val prefs = getSharedPreferences("streamforge_studio", 0)
        val raw = sceneJsonOverride ?: prefs.getString("scenes", "[]").orEmpty()
        val scenes = runCatching { org.json.JSONArray(raw) }.getOrNull()
        if (scenes == null || scenes.length() == 0) {
            filter.setSceneOverlayBitmap(null)
            return
        }
        val safeIndex = sceneIndex.coerceIn(0, scenes.length() - 1)
        activeSceneIndex = safeIndex
        val scene = scenes.optJSONObject(safeIndex) ?: return
        val layers = scene.optJSONArray("layers") ?: org.json.JSONArray()
        val screenLayer = (0 until layers.length())
            .mapNotNull { layers.optJSONObject(it) }
            .firstOrNull { it.optString("kind") == "screen" && it.optBoolean("visible", true) }
        val hasBackground = (0 until layers.length()).any { i ->
            layers.optJSONObject(i)?.let { it.optString("kind") == "background" && it.optBoolean("visible", true) } == true
        }
        if (screenLayer != null && !hasBackground) {
            filter.setScreenRect(
                screenLayer.optDouble("x", 0.0).toFloat(),
                screenLayer.optDouble("y", 0.0).toFloat(),
                screenLayer.optDouble("width", 1.0).toFloat(),
                screenLayer.optDouble("height", 1.0).toFloat()
            )
        } else if (hasBackground) {
            filter.setScreenRect(0f, 0f, .001f, .001f)
        } else {
            filter.setScreenRect(0f, 0f, 1f, 1f)
        }
        if (screenLayer != null && !hasBackground) {
            filter.setScreenTransform(
                screenLayer.optDouble("rotation", 0.0).toFloat(),
                screenLayer.optDouble("cropLeft", 0.0).toFloat(),
                screenLayer.optDouble("cropTop", 0.0).toFloat(),
                screenLayer.optDouble("cropRight", 1.0).toFloat(),
                screenLayer.optDouble("cropBottom", 1.0).toFloat()
            )
        } else filter.setScreenTransform(0f, 0f, 0f, 1f, 1f)
        // Overlay textures are composited separately from the gameplay texture.
        // Keep overlay composition at up to Full HD for crisp 1080p output without
        // allocating a full 4K ARGB bitmap on every compositor refresh.
        val overlayScale = minOf(1f, 1920f / activeStreamWidth.coerceAtLeast(1), 1080f / activeStreamHeight.coerceAtLeast(1))
        val outputWidth = (activeStreamWidth * overlayScale).toInt().coerceAtLeast(320)
        val outputHeight = (activeStreamHeight * overlayScale).toInt().coerceAtLeast(180)
        val visibleVideoUris = (0 until layers.length()).mapNotNull { index ->
            layers.optJSONObject(index)?.takeIf {
                it.optBoolean("visible", true) && it.optString("kind") == "video"
            }?.optString("text")?.takeIf { it.isNotBlank() }
        }.distinct().take(2).toSet()
        if (visibleVideoUris != requestedVideoUris) {
            requestedVideoUris = visibleVideoUris
            // Release codecs for removed layers on the decoder worker, never
            // concurrently with an in-flight decode operation.
            runCatching {
                videoDecodeExecutor.execute {
                    val stale = videoDecoders.keys.filter { it !in visibleVideoUris }
                    stale.forEach { uri ->
                        videoDecoders.remove(uri)?.let { runCatching { it.close() } }
                        videoFallbackRetrievers.remove(uri)?.let { runCatching { it.release() } }
                        synchronized(videoFrameLock) {
                            videoFrameBitmaps.remove(uri)?.let { if (!it.isRecycled) it.recycle() }
                        }
                        videoStartedAt.remove(uri)
                        videoDurationMs.remove(uri)
                        videoSourceFps.remove(uri)
                        videoLastFrameAt.remove(uri)
                        videoLastPts.remove(uri)
                        videoLastPtsProgressAt.remove(uri)
                        videoHardwareRestartCounts.remove(uri)
                    }
                }
            }
        }
        val bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        var activeVideoLayerCount = 0
        for (i in 0 until layers.length()) {
            val item = layers.optJSONObject(i) ?: continue
            if (!item.optBoolean("visible", true)) continue
            val kind = item.optString("kind")
            if (kind == "video") {
                activeVideoLayerCount += 1
                // Most phones expose only one or two reliable simultaneous
                // flexible-YUV decoders. Keep extra clips in the editable scene,
                // but don't allocate codecs for them during live composition.
                if (activeVideoLayerCount > 2) continue
            }
            if (kind == "screen") continue
            val left = item.optDouble("x", .08).toFloat().coerceIn(0f, 1f) * outputWidth
            val top = item.optDouble("y", .08).toFloat().coerceIn(0f, 1f) * outputHeight
            val width = item.optDouble("width", .42).toFloat().coerceIn(.02f, 1f) * outputWidth
            val height = item.optDouble("height", .16).toFloat().coerceIn(.02f, 1f) * outputHeight
            val rect = RectF(left, top, (left + width).coerceAtMost(outputWidth.toFloat()), (top + height).coerceAtMost(outputHeight.toFloat()))
            val alpha = (item.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f) * 255).toInt()

            canvas.save()
            canvas.rotate(item.optDouble("rotation", 0.0).toFloat(), rect.centerX(), rect.centerY())
            when (kind) {
                "background" -> {
                    val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = LinearGradient(0f, 0f, outputWidth.toFloat(), outputHeight.toFloat(), AndroidColor.rgb(9, 15, 34), AndroidColor.rgb(35, 18, 62), Shader.TileMode.CLAMP)
                    }
                    canvas.drawRect(0f, 0f, outputWidth.toFloat(), outputHeight.toFloat(), backgroundPaint)
                    val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(55, 95, 135, 255) }
                    canvas.drawCircle(outputWidth * .82f, outputHeight * .22f, outputHeight * .22f, glow)
                }
                "frame" -> {
                    val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = (outputWidth * .008f).coerceIn(4f, 12f)
                        color = AndroidColor.rgb(89, 220, 255)
                    }
                    canvas.drawRoundRect(rect, 24f, 24f, framePaint)
                    framePaint.strokeWidth = (outputWidth * .0025f).coerceIn(2f, 5f)
                    framePaint.color = AndroidColor.rgb(181, 112, 255)
                    val corner = rect.width() * .10f
                    canvas.drawLine(rect.left, rect.top + corner, rect.left, rect.top, framePaint)
                    canvas.drawLine(rect.left, rect.top, rect.left + corner, rect.top, framePaint)
                    canvas.drawLine(rect.right - corner, rect.bottom, rect.right, rect.bottom, framePaint)
                    canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - corner, framePaint)
                }
                "text" -> {
                    val value = item.optString("text").trim()
                    if (value.isNotEmpty()) {
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = AndroidColor.WHITE
                            this.alpha = alpha
                            color = runCatching { AndroidColor.parseColor(item.optString("textColor", "#FFFFFFFF")) }.getOrDefault(AndroidColor.WHITE)
                            typeface = Typeface.create(item.optString("fontFamily", "sans-serif"), Typeface.BOLD)
                            textSize = ((rect.height() * .58f).coerceIn(20f, 112f) * item.optDouble("fontSizeScale", 1.0).toFloat().coerceIn(.5f, 2f))
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
                    val uriString = uri?.toString().orEmpty()
                    val image = if (uriString.isNotBlank()) sceneImages[uriString] ?: runCatching {
                        contentResolver.openInputStream(Uri.parse(uriString))?.use { BitmapFactory.decodeStream(it) }
                    }.getOrNull()?.also { sceneImages[uriString] = it } else null
                    if (image != null && !image.isRecycled) {
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alpha }
                        val src = RectF(
                            image.width * item.optDouble("cropLeft", 0.0).toFloat(),
                            image.height * item.optDouble("cropTop", 0.0).toFloat(),
                            image.width * item.optDouble("cropRight", 1.0).toFloat(),
                            image.height * item.optDouble("cropBottom", 1.0).toFloat()
                        )
                        canvas.drawBitmap(image, android.graphics.Rect(src.left.toInt(), src.top.toInt(), src.right.toInt(), src.bottom.toInt()), rect, paint)
                    }
                }
                "gif" -> {
                    val uriString = item.optString("text")
                    val movie = gifMovies[uriString] ?: runCatching {
                        contentResolver.openInputStream(Uri.parse(uriString))?.use { Movie.decodeStream(it) }
                    }.getOrNull()?.also {
                        gifMovies[uriString] = it
                        gifStartedAt[uriString] = SystemClock.uptimeMillis()
                    }
                    if (movie != null) {
                        val duration = movie.duration().coerceAtLeast(100)
                        val elapsed = SystemClock.uptimeMillis() - (gifStartedAt[uriString] ?: SystemClock.uptimeMillis())
                        val movieTime = if (item.optBoolean("loop", true)) elapsed % duration else elapsed.coerceAtMost((duration - 1).toLong())
                        movie.setTime(movieTime.toInt())
                        canvas.save()
                        canvas.clipRect(rect)
                        val cropL = item.optDouble("cropLeft", 0.0).toFloat()
                        val cropT = item.optDouble("cropTop", 0.0).toFloat()
                        val cropR = item.optDouble("cropRight", 1.0).toFloat()
                        val cropB = item.optDouble("cropBottom", 1.0).toFloat()
                        val scaleX = rect.width() / (movie.width().coerceAtLeast(1) * (cropR - cropL).coerceAtLeast(.02f))
                        val scaleY = rect.height() / (movie.height().coerceAtLeast(1) * (cropB - cropT).coerceAtLeast(.02f))
                        canvas.translate(rect.left - movie.width() * cropL * scaleX, rect.top - movie.height() * cropT * scaleY)
                        canvas.scale(scaleX, scaleY)
                        movie.draw(canvas, 0f, 0f)
                        canvas.restore()
                    }
                }
                "timer" -> {
                    val elapsed = if (recordingStartedAt.value > 0L) System.currentTimeMillis() - recordingStartedAt.value
                        else if (streamStartedAt.value > 0L) System.currentTimeMillis() - streamStartedAt.value else 0L
                    val seconds = (elapsed / 1000L).coerceAtLeast(0L)
                    val label = String.format(Locale.US, "%02d:%02d", (seconds / 60L) % 100L, seconds % 60L)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = runCatching { AndroidColor.parseColor(item.optString("textColor", "#FFFFFFFF")) }.getOrDefault(AndroidColor.WHITE)
                        this.alpha = alpha
                        typeface = Typeface.create(item.optString("fontFamily", "sans-serif"), Typeface.BOLD)
                        textSize = (rect.height() * .62f).coerceIn(22f, 100f) * item.optDouble("fontSizeScale", 1.0).toFloat().coerceIn(.5f, 2f)
                    }
                    canvas.drawText(label, rect.left + rect.width() * .03f, rect.centerY() - (paint.ascent() + paint.descent()) / 2f, paint)
                }
                "video" -> {
                    val uriString = item.optString("text")
                    val now = SystemClock.uptimeMillis()
                    val manualFps = item.optInt("fps", 0).coerceIn(0, 60)
                    val sourceFps = videoSourceFps[uriString] ?: 30
                    val targetFps = (if (manualFps == 0) sourceFps else manualFps).coerceIn(5, 60)
                    val frameInterval = (1000L / targetFps).coerceAtLeast(16L)
                    val lastFrameAt = videoLastFrameAt[uriString] ?: 0L
                    if (now - lastFrameAt >= frameInterval && videoDecodePending.add(uriString)) {
                        videoLastFrameAt[uriString] = now
                        val shouldLoop = item.optBoolean("loop", true)
                        videoDecodeExecutor.execute {
                            try {
                                val nowElapsed = SystemClock.uptimeMillis()
                                videoStartedAt.putIfAbsent(uriString, nowElapsed)
                                val elapsed = (nowElapsed - (videoStartedAt[uriString] ?: nowElapsed)).coerceAtLeast(0L)
                                var frame: Bitmap? = null
                                // Stream overlay playback must honor the user's selected mode.
                                // Recovery is mode-local: Hardware restarts/recreates MediaCodec;
                                // Decode resets/recreates MediaMetadataRetriever. Never cross-switch.
                                val selectedMode = getSharedPreferences("streamforge_studio", 0)
                                    .getString("video_preview_mode", "decode") ?: "decode"
                                if (selectedMode == "hardware") {
                                    videoFallbackRetrievers.remove(uriString)?.let { runCatching { it.release() } }
                                    try {
                                        val decoder = videoDecoders[uriString] ?: HardwareVideoFrameDecoder(
                                            this@StreamService, Uri.parse(uriString)
                                        ).also {
                                            videoDecoders[uriString] = it
                                            videoDurationMs[uriString] = it.durationMs
                                            videoSourceFps[uriString] = it.frameRate
                                        }
                                        frame = decoder.nextFrame(shouldLoop)
                                        val pts = decoder.lastFramePresentationTimeUs
                                        if (pts >= 0L) {
                                            val previousPts = videoLastPts.put(uriString, pts)
                                            if (previousPts == null || pts > previousPts || (previousPts - pts) > 500_000L) {
                                                videoLastPtsProgressAt[uriString] = nowElapsed
                                            } else if (nowElapsed - (videoLastPtsProgressAt[uriString] ?: nowElapsed) >= 1800L) {
                                                throw IllegalStateException("Hardware decoder frame timestamp stalled")
                                            }
                                        } else if (frame == null && nowElapsed - (videoLastPtsProgressAt[uriString] ?: nowElapsed) >= 1800L) {
                                            throw IllegalStateException("Hardware decoder stopped producing frames")
                                        }
                                    } catch (_: Exception) {
                                        val currentDecoder = videoDecoders[uriString]
                                        val restartCount = videoHardwareRestartCounts[uriString] ?: 0
                                        if (currentDecoder != null && restartCount < 1) {
                                            // First recovery: reset the currently selected hardware codec.
                                            runCatching { currentDecoder.restartPlayback() }
                                            videoHardwareRestartCounts[uriString] = restartCount + 1
                                        } else {
                                            // Second recovery: recreate a hardware decoder for the same source.
                                            videoDecoders.remove(uriString)?.let { runCatching { it.close() } }
                                            videoHardwareRestartCounts[uriString] = 0
                                        }
                                        videoLastPts.remove(uriString)
                                        videoLastPtsProgressAt[uriString] = nowElapsed
                                    }
                                } else {
                                    videoDecoders.remove(uriString)?.let { runCatching { it.close() } }
                                    videoLastPts.remove(uriString)
                                    videoLastPtsProgressAt.remove(uriString)
                                    try {
                                        val retriever = videoFallbackRetrievers[uriString] ?: MediaMetadataRetriever().also {
                                            it.setDataSource(this@StreamService, Uri.parse(uriString))
                                            videoFallbackRetrievers[uriString] = it
                                            val durationText = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                            val fpsText = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                                            videoDurationMs[uriString] = durationText?.toLongOrNull()?.coerceAtLeast(1L) ?: 1L
                                            videoSourceFps[uriString] = fpsText?.toFloatOrNull()?.toInt()?.coerceIn(5, 60) ?: 30
                                        }
                                        val duration = (videoDurationMs[uriString] ?: 1L).coerceAtLeast(1L)
                                        val positionMs = if (shouldLoop) elapsed % duration else elapsed.coerceAtMost(duration - 1L)
                                        frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                                            retriever.getScaledFrameAtTime(
                                                positionMs * 1000L,
                                                MediaMetadataRetriever.OPTION_CLOSEST,
                                                960,
                                                540
                                            )
                                        } else {
                                            retriever.getFrameAtTime(positionMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                                                ?.let { original ->
                                                    Bitmap.createScaledBitmap(
                                                        original,
                                                        minOf(960, original.width).coerceAtLeast(1),
                                                        (original.height * (minOf(960, original.width).toFloat() / original.width)).toInt().coerceAtLeast(1),
                                                        true
                                                    ).also { scaled -> if (scaled !== original && !original.isRecycled) original.recycle() }
                                                }
                                        } ?: throw IllegalStateException("Decode returned no frame")
                                    } catch (_: Exception) {
                                        // Decode recovery stays in Decode: release the failed retriever
                                        // so the next refresh creates a fresh one for this same URI.
                                        videoFallbackRetrievers.remove(uriString)?.let { runCatching { it.release() } }
                                    }
                                }
                                if (frame != null) {
                                    synchronized(videoFrameLock) {
                                        videoFrameBitmaps.put(uriString, frame!!)?.let { previous ->
                                            if (previous !== frame && !previous.isRecycled) previous.recycle()
                                        }
                                    }
                                }
                            } catch (_: OutOfMemoryError) {
                                synchronized(videoFrameLock) {
                                    videoFrameBitmaps.remove(uriString)?.let { if (!it.isRecycled) it.recycle() }
                                }
                                videoDecoders.remove(uriString)?.close()
                                videoSourceFps.remove(uriString)
                                videoDurationMs.remove(uriString)
                            } catch (_: Exception) {
                                // Keep the last good frame; timestamp extraction is retried
                                // on the next overlay refresh.
                                videoFallbackRetrievers.remove(uriString)?.let { runCatching { it.release() } }
                            } finally {
                                videoDecodePending.remove(uriString)
                            }
                        }
                    }
                    synchronized(videoFrameLock) {
                        videoFrameBitmaps[uriString]?.takeIf { !it.isRecycled }?.let { frame ->
                            canvas.drawBitmap(frame, null, rect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alpha })
                        }
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
            canvas.restore()
        }
        filter.setSceneOverlayBitmap(bitmap)
        val hasVideo = (0 until layers.length()).any { i ->
            layers.optJSONObject(i)?.let { it.optBoolean("visible", true) && it.optString("kind") == "video" } == true
        }
        val requestedVideoFps = (0 until layers.length()).mapNotNull { i ->
            layers.optJSONObject(i)?.takeIf { it.optBoolean("visible", true) && it.optString("kind") == "video" }?.let { item ->
                val manual = item.optInt("fps", 0)
                (if (manual == 0) videoSourceFps[item.optString("text")] ?: 30 else manual).coerceIn(5, 60)
            }
        }.maxOrNull() ?: 5
        val hasAnimatedLayer = (0 until layers.length()).any { i ->
            layers.optJSONObject(i)?.let {
                it.optBoolean("visible", true) && (it.optString("kind") == "gif" || it.optString("kind") == "video" || it.optString("kind") == "timer")
            } == true
        }
        // Match GL redraw cadence to the source frame rate, up to 60 FPS.
        stream?.getGlInterface()?.setForceRender(true, if (hasVideo) requestedVideoFps else 5)
        gifFrameHandler.removeCallbacks(gifFrameRunnable)
        if (hasAnimatedLayer && (streaming.value || recording.value || studioPreviewReady.value)) {
            val frameInterval = if (hasVideo) (1000L / requestedVideoFps).coerceAtLeast(16L) else 200L
            gifFrameHandler.postDelayed(gifFrameRunnable, frameInterval)
        }
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
            val profile = resolveEncoderProfile(width, height, fps)
            val encoderBitrate = bitrate.coerceIn(profile.minBitrate, profile.maxBitrate)
            activeStreamWidth = profile.width
            activeStreamHeight = profile.height
            streamResolution.value = "${profile.width} × ${profile.height}"
            streamFps.value = profile.fps
            stream?.forceBt709Color(true)
            val prepared = stream?.prepareVideo(profile.width, profile.height, encoderBitrate, profile.fps, rotation = 0) == true
            if (!prepared) throw IllegalStateException("The device rejected ${profile.width}×${profile.height} recording. Choose a lower preset.")
            stream?.getGlInterface()?.setCameraOrientation(0)
            stream?.changeVideoSource(createScreenSource(projection!!))
            configureAudioMix()
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
                recordingStartedAt.value = System.currentTimeMillis()
                status.value = if (streaming.value) "Recording locally" else "Recording screen locally"
                updateOngoingNotification(if (streaming.value) "LIVE · Local recording active" else "Local screen recording active")
                // The scene was initially composed while recording=false, so animated
                // overlays had no frame ticker. Start the ticker as soon as the recorder
                // is live; this advances video overlays beyond their first decoded frame.
                applyScene(activeSceneIndex)
                startPreviewIfAvailable()
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

    private fun toggleRecordingPause() {
        val activeStream = stream ?: return
        if (!recording.value) return
        runCatching {
            if (recordingPaused.value) {
                activeStream.resumeRecord()
                recordingStartedAt.value += (System.currentTimeMillis() - recordingPauseStartedAt).coerceAtLeast(0L)
                recordingPauseStartedAt = 0L
                recordingPaused.value = false
                status.value = "Recording resumed"
            } else {
                activeStream.pauseRecord()
                recordingPauseStartedAt = System.currentTimeMillis()
                recordingPaused.value = true
                status.value = "Recording paused"
            }
            updateOngoingNotification(if (recordingPaused.value) "Recording paused" else "Recording resumed")
        }.onFailure { status.value = "Could not change recording state: ${it.message ?: "recording error"}" }
    }

    private var recordingPauseStartedAt = 0L

    private fun stopRecording() {
        val activeStream = stream
        if (recording.value) runCatching { activeStream?.stopRecord() }
        recording.value = false
        recordingPaused.value = false
        recordingPauseStartedAt = 0L
        recordingStartedAt.value = 0L
        status.value = if (streaming.value) "LIVE" else "Recording saved"
        if (streaming.value) {
            updateOngoingNotification("LIVE · Streaming active")
        } else {
            // Stop the capture graph only after the recorder has been asked to
            // finalize its MP4. All teardown calls are guarded because Android's
            // projection/GL callbacks can arrive while the service is stopping.
            runCatching { activeStream?.let { if (it.isOnPreview) it.stopPreview() } }
            runCatching { projection?.stop() }
            projection = null
            activeScreenSource = null
            studioPreviewReady.value = false
            colorGradingFilter = null
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
    }

    private fun setLiveBitrate(requested: Int) {
        val active = stream ?: return
        if (!streaming.value || !active.isStreaming) return
        val safeBitrate = requested.coerceIn(600_000, 35_000_000)
        val applied = runCatching { active.setVideoBitrateOnFly(safeBitrate); true }.getOrDefault(false)
        if (applied) {
            requestedVideoBitrate = safeBitrate
            activeVideoBitrate = safeBitrate
            adaptiveBitrateEnabled = getSharedPreferences("streamforge_studio", 0).getBoolean("adaptive_bitrate", true)
            lastBitrateAdjustmentAt = SystemClock.elapsedRealtime()
            stableNetworkSince = 0L
            networkHealth.value = "Manual target · ${safeBitrate / 1000} kbps"
            status.value = "LIVE • target ${safeBitrate / 1000} kbps"
        } else {
            networkHealth.value = "Could not apply live bitrate"
        }
    }

    private fun stopStream(finalStatus: String = "Stream stopped") {
        // If live and local recording are active together, finalize the recording
        // before tearing down MediaProjection and the GL/encoder graph.
        if (recording.value) {
            runCatching { stream?.stopRecord() }
            recording.value = false
            recordingPaused.value = false
            recordingPauseStartedAt = 0L
            recordingStartedAt.value = 0L
        }
        runCatching {
            stream?.let {
                if (it.isStreaming) it.stopStream()
                if (it.isOnPreview) it.stopPreview()
            }
        }
        streaming.value = false
        studioPreviewReady.value = false
        streamStartedAt.value = 0L
        gifFrameHandler.removeCallbacks(gifFrameRunnable)
        hideFloatingControls()
        YouTubeLiveChat.stop()
        runCatching { projection?.stop() }
        projection = null
        activeScreenSource = null
        colorGradingFilter = null
        status.value = finalStatus
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }


    private fun configureAudioMix() {
        val activeStream = stream ?: return
        val activeProjection = projection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && activeProjection != null) {
            runCatching {
                val mix = MixAudioSource(activeProjection)
                activeStream.changeAudioSource(mix)
                val prefs = getSharedPreferences("streamforge_studio", 0)
                val mic = prefs.getFloat("audio_mic_gain", 1f).coerceIn(0f, 2f)
                val game = prefs.getFloat("audio_game_gain", 1f).coerceIn(0f, 2f)
                mix.microphoneVolume = if (microphoneMuted.value) 0f else mic
                mix.internalVolume = game
                mixedAudioSource = mix
                microphoneGain.value = mic
                gameAudioGain.value = game
                gameAudioAvailable.value = true
            }.onFailure {
                mixedAudioSource = null
                gameAudioAvailable.value = false
                status.value = "Game audio unavailable · microphone remains active"
            }
        } else {
            mixedAudioSource = null
            gameAudioAvailable.value = false
            activeStream.changeAudioSource(microphoneSource)
        }
    }

    private fun updateAudioMix(micGain: Float, gameGain: Float) {
        val safeMic = micGain.coerceIn(0f, 2f)
        val safeGame = gameGain.coerceIn(0f, 2f)
        microphoneGain.value = safeMic
        gameAudioGain.value = safeGame
        getSharedPreferences("streamforge_studio", 0).edit()
            .putFloat("audio_mic_gain", safeMic)
            .putFloat("audio_game_gain", safeGame)
            .apply()
        mixedAudioSource?.let { mix ->
            runCatching {
                mix.microphoneVolume = if (microphoneMuted.value) 0f else safeMic
                mix.internalVolume = safeGame
            }.onFailure { status.value = "Audio mix update failed: ${it.message ?: "audio error"}" }
        }
    }

    private fun toggleMicrophone() {
        val muted = !microphoneMuted.value
        val activeStream = stream
        if (activeStream != null) {
            runCatching {
                val mix = mixedAudioSource
                if (mix != null) {
                    mix.microphoneVolume = if (muted) 0f else microphoneGain.value
                } else {
                    if (muted) activeStream.changeAudioSource(SilenceAudioSource())
                    else activeStream.changeAudioSource(microphoneSource)
                }
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
        val chatFeed = text("Connect a chat source in the Chat tab.", 10f, 0xFFDDE6F5.toInt()).apply {
            gravity = Gravity.START
            maxLines = 5
            visibility = View.GONE
            setPadding(dp(4), dp(8), dp(4), dp(4))
        }
        overlayChatText = chatFeed
        panel.addView(chatFeed)

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
                chatFeed.visibility = if (chatFeed.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                val recent = YouTubeLiveChat.messages.value.takeLast(4).joinToString("\n")
                chatFeed.text = if (recent.isBlank()) YouTubeLiveChat.connectionState.value else recent
                panel.requestLayout()
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
    private fun buildNotification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 7401, Intent(this, app.streamforge.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(if (recording.value) "StreamForge · Recording" else "StreamForge is active")
            .setContentText(message)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (recording.value && recordingStartedAt.value > 0L) {
            builder.setWhen(recordingStartedAt.value).setUsesChronometer(true)
        }
        if (recording.value) {
            val stopRecording = PendingIntent.getService(
                this, 7402, Intent(this, StreamService::class.java).setAction(ACTION_STOP_RECORDING),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_pause, "Stop recording", stopRecording)
        }
        return builder.build()
    }

    private fun updateOngoingNotification(message: String) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(message))
        }
    }

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
        applyScene(activeSceneIndex)
        microphoneMuted.value = false
        Handler(Looper.getMainLooper()).post { showFloatingControls() }
        YouTubeLiveChat.start(this)
        status.value = "LIVE"
        updateOngoingNotification(if (recording.value) "LIVE · Local recording active" else "LIVE · Streaming active")
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
        if (streaming.value && adaptiveBitrateEnabled && activeStream != null && now - lastBitrateAdjustmentAt >= 5_000L) {
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
        YouTubeLiveChat.stop()
        status.value = "Disconnected"
    }
    override fun onAuthError() { stopStream("Authentication failed · check your stream key") }
    override fun onAuthSuccess() { status.value = "Destination authenticated" }

    override fun onDestroy() {
        // Do not call stopRecording() here: it can call stopSelf() and tear down
        // MediaProjection a second time while Android is already destroying us.
        if (recording.value) runCatching { stream?.stopRecord() }
        recording.value = false
        recordingPaused.value = false
        recordingStartedAt.value = 0L
        if (streaming.value) runCatching { stream?.let { if (it.isStreaming) it.stopStream() } }
        streaming.value = false
        streamStartedAt.value = 0L
        YouTubeLiveChat.stop()
        hideFloatingControls()
        gifFrameHandler.removeCallbacks(gifFrameRunnable)
        gifMovies.clear()
        gifStartedAt.clear()

        // Drain the decoder worker before releasing codecs so a frame task
        // cannot race native MediaCodec teardown.
        videoDecodeExecutor.shutdown()
        runCatching {
            if (!videoDecodeExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                videoDecodeExecutor.shutdownNow()
                videoDecodeExecutor.awaitTermination(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
        // close() synchronizes with nextFrame(), protecting native codec state
        // even if a device decoder takes longer than the worker grace period.
        videoDecoders.values.toList().forEach { runCatching { it.close() } }
        videoDecoders.clear()
        videoFallbackRetrievers.values.forEach { runCatching { it.release() } }
        videoFallbackRetrievers.clear()
        videoNullFrameCounts.clear()
        videoLastPts.clear()
        videoLastPtsProgressAt.clear()
        videoHardwareRestartCounts.clear()
        synchronized(videoFrameLock) {
            videoFrameBitmaps.values.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
            videoFrameBitmaps.clear()
        }
        videoLastFrameAt.clear()
        videoStartedAt.clear()
        videoDurationMs.clear()
        videoSourceFps.clear()
        sceneImages.values.forEach { if (!it.isRecycled) it.recycle() }
        sceneImages.clear()
        runCatching { stream?.let { if (it.isOnPreview) it.stopPreview(); it.release() } }
        stream = null
        previewView = null
        colorGradingFilter = null
        runCatching { projection?.stop() }
        projection = null
        activeScreenSource = null
        studioPreviewReady.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
