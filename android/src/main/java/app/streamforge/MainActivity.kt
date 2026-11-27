package app.streamforge

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.gms.ads.MobileAds
import app.streamforge.stream.StreamService
import app.streamforge.ui.StreamForgeApp
import app.streamforge.ui.CastDooAuthGate
import app.streamforge.ui.EditorAssetDownloads
import app.streamforge.ui.CastDooReleaseGate
import app.streamforge.notifications.CastDooUnifiedPushService

class MainActivity : ComponentActivity() {
    private var pendingEndpoint = ""
    private var pendingWidth = 1280
    private var pendingHeight = 720
    private var pendingFps = 30
    private var pendingBitrate = 4_500_000
    private var pendingRecordingOnly = false
    private var pendingStudioPreview = false
    private var pendingChangeCaptureSource = false
    private var pendingCaptureMode = "GAME"
    private var waitingForOverlayPermission = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            requestScreenCapture()
        } else {
            StreamService.status.value = "Microphone permission is required for audio recording"
        }
    }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val recordingOnly = pendingRecordingOnly
            val studioPreview = pendingStudioPreview
            val changeCaptureSource = pendingChangeCaptureSource
            pendingStudioPreview = false
            pendingChangeCaptureSource = false
            StreamService.status.value = when {
                changeCaptureSource -> "Switching capture source…"
                studioPreview -> "App selected · preparing Studio preview…"
                recordingOnly -> "Screen access granted · starting local recording…"
                else -> "Screen access granted · starting stream service…"
            }
            val serviceIntent = Intent(this, StreamService::class.java).apply {
                action = when {
                    changeCaptureSource -> StreamService.ACTION_CHANGE_CAPTURE_SOURCE
                    studioPreview -> StreamService.ACTION_START_STUDIO_PREVIEW
                    recordingOnly -> StreamService.ACTION_START_LOCAL_RECORDING
                    else -> StreamService.ACTION_START_STREAM
                }
                putExtra(StreamService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(StreamService.EXTRA_RESULT_DATA, data)
                putExtra(StreamService.EXTRA_ENDPOINT, pendingEndpoint)
                putExtra(StreamService.EXTRA_WIDTH, pendingWidth)
                putExtra(StreamService.EXTRA_HEIGHT, pendingHeight)
                putExtra(StreamService.EXTRA_FPS, pendingFps)
                putExtra(StreamService.EXTRA_BITRATE, pendingBitrate)
            }
            try {
                ContextCompat.startForegroundService(this, serviceIntent)
            } catch (error: Exception) {
                StreamService.status.value = "Could not launch capture service: ${error.message ?: error.javaClass.simpleName}"
            }
        } else {
            pendingStudioPreview = false
            pendingChangeCaptureSource = false
            pendingRecordingOnly = false
            StreamService.status.value = "Screen sharing was cancelled or permission was denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EditorAssetDownloads.schedule(this)
        MobileAds.initialize(this)
        // Android 15 enforces edge-to-edge for targetSdk 35. Compose applies system-bar
        // insets to the content so the app header never sits underneath the status bar.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        // Match the Android gesture/navigation region to the app bottom navigation.
        @Suppress("DEPRECATION")
        run { window.navigationBarColor = android.graphics.Color.rgb(21, 25, 29) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            CastDooAuthGate {
              CastDooReleaseGate {
            StreamForgeApp(
                onStartStream = { endpoint, width, height, fps, bitrate, captureMode ->
                    pendingRecordingOnly = false
                    pendingStudioPreview = false
                    pendingEndpoint = endpoint
                    pendingWidth = width
                    pendingHeight = height
                    pendingFps = fps
                    pendingBitrate = bitrate
                    pendingCaptureMode = if (Build.VERSION.SDK_INT >= 34) captureMode else "FULL"
                    if (StreamService.studioPreviewReady.value) {
                        ContextCompat.startForegroundService(this, Intent(this, StreamService::class.java).apply {
                            action = StreamService.ACTION_START_STREAM
                            putExtra(StreamService.EXTRA_ENDPOINT, endpoint)
                            putExtra(StreamService.EXTRA_WIDTH, width)
                            putExtra(StreamService.EXTRA_HEIGHT, height)
                            putExtra(StreamService.EXTRA_FPS, fps)
                            putExtra(StreamService.EXTRA_BITRATE, bitrate)
                        })
                    } else if (!Settings.canDrawOverlays(this)) {
                        waitingForOverlayPermission = true
                        startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)), 7402)
                    } else beginBroadcastPermissions()
                },
                onPreparePreview = { width, height, fps, bitrate, captureMode ->
                    if (!StreamService.studioPreviewReady.value) {
                        pendingRecordingOnly = false
                        pendingStudioPreview = true
                        pendingEndpoint = ""
                        pendingWidth = width
                        pendingHeight = height
                        pendingFps = fps
                        pendingBitrate = bitrate
                        pendingCaptureMode = if (Build.VERSION.SDK_INT >= 34) captureMode else "FULL"
                        if (!Settings.canDrawOverlays(this)) {
                            waitingForOverlayPermission = true
                            startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)), 7402)
                        } else {
                            val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
                            permissionLauncher.launch(permissions.toTypedArray())
                        }
                    }
                },
                onStopStudioPreview = { sendServiceAction(StreamService.ACTION_STOP_STUDIO_PREVIEW) },
                onChangeCaptureSource = { endpoint, width, height, fps, bitrate, captureMode ->
                    pendingRecordingOnly = false
                    pendingStudioPreview = false
                    pendingChangeCaptureSource = true
                    pendingEndpoint = endpoint
                    pendingWidth = width
                    pendingHeight = height
                    pendingFps = fps
                    pendingBitrate = bitrate
                    pendingCaptureMode = if (Build.VERSION.SDK_INT >= 34) captureMode else "FULL"
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) requestScreenCapture()
                    else permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                },
                onStopStream = { sendServiceAction(StreamService.ACTION_STOP_STREAM) },
                onStartRecording = { width, height, fps, bitrate ->
                    if (StreamService.streaming.value || StreamService.studioPreviewReady.value) {
                        sendServiceAction(StreamService.ACTION_START_RECORDING)
                    } else {
                        beginStandaloneRecording(width, height, fps, bitrate)
                    }
                },
                onStopRecording = { sendServiceAction(StreamService.ACTION_STOP_RECORDING) },
                compactMode = false
            )
              }
            }
        }
        handleNotificationAction(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationAction(intent)
    }

    private fun handleNotificationAction(source: Intent?) {
        val actionUrl = source?.getStringExtra(CastDooUnifiedPushService.EXTRA_ACTION_URL).orEmpty()
        source?.removeExtra(CastDooPushMessagingService.EXTRA_ACTION_URL)
        if (actionUrl.startsWith("https://")) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(actionUrl))) }
        }
    }

    override fun onResume() {
        super.onResume()
        val keepAwake = getSharedPreferences("streamforge_studio", MODE_PRIVATE)
            .getBoolean("keep_screen_awake", true)
        if (keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun beginBroadcastPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(permissions.toTypedArray())
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7402 && waitingForOverlayPermission) {
            waitingForOverlayPermission = false
            if (Settings.canDrawOverlays(this)) beginBroadcastPermissions()
            else StreamService.status.value = "Allow display over other apps to use floating stream controls"
        }
    }
    private fun sendServiceAction(action: String) {
        val intent = Intent(this, StreamService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(this, intent)
        else startService(intent)
    }

    private fun beginStandaloneRecording(width: Int, height: Int, fps: Int, bitrate: Int) {
        pendingRecordingOnly = true
        pendingEndpoint = ""
        pendingWidth = width
        pendingHeight = height
        pendingFps = fps
        pendingBitrate = bitrate
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun requestScreenCapture() {
        StreamService.status.value = when {
            pendingStudioPreview -> "Choose one app or the full display to preview before going live"
            pendingRecordingOnly -> "Choose the screen or app to record, then tap Start now"
            else -> "Choose what to share, then tap Start now"
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
            val config = if (!pendingRecordingOnly && pendingCaptureMode == "FULL") MediaProjectionConfig.createConfigForDefaultDisplay() else MediaProjectionConfig.createConfigForUserChoice()
            manager.createScreenCaptureIntent(config)
        } else {
            manager.createScreenCaptureIntent()
        }
        captureLauncher.launch(captureIntent)
    }
}
