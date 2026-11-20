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
import app.streamforge.stream.StreamService
import app.streamforge.ui.StreamForgeApp

class MainActivity : ComponentActivity() {
    private var pendingEndpoint = ""
    private var pendingWidth = 1280
    private var pendingHeight = 720
    private var pendingFps = 30
    private var pendingBitrate = 4_500_000
    private var waitingForOverlayPermission = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            requestScreenCapture()
        } else {
            StreamService.status.value = "Microphone permission is required to start a broadcast"
        }
    }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null && pendingEndpoint.isNotBlank()) {
            StreamService.status.value = "Screen access granted · starting stream service…"
            val serviceIntent = Intent(this, StreamService::class.java).apply {
                action = StreamService.ACTION_START_STREAM
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
                StreamService.status.value = "Could not launch stream service: ${error.message ?: error.javaClass.simpleName}"
            }
        } else {
            StreamService.status.value = if (pendingEndpoint.isBlank()) {
                "Stream destination is missing"
            } else {
                "Screen sharing was cancelled or permission was denied"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 enforces edge-to-edge for targetSdk 35. Compose applies system-bar
        // insets to the content so the app header never sits underneath the status bar.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            StreamForgeApp(
                onStartStream = { endpoint, width, height, fps, bitrate ->
                    pendingEndpoint = endpoint
                    pendingWidth = width
                    pendingHeight = height
                    pendingFps = fps
                    pendingBitrate = bitrate
                    if (!Settings.canDrawOverlays(this)) {
                        waitingForOverlayPermission = true
                        startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)), 7402)
                    } else beginBroadcastPermissions()
                },
                onStopStream = { sendServiceAction(StreamService.ACTION_STOP_STREAM) },
                onStartRecording = { sendServiceAction(StreamService.ACTION_START_RECORDING) },
                onStopRecording = { sendServiceAction(StreamService.ACTION_STOP_RECORDING) }
            )
        }
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

    private fun requestScreenCapture() {
        StreamService.status.value = "Choose what to share, then tap Start now"
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
        } else {
            manager.createScreenCaptureIntent()
        }
        captureLauncher.launch(captureIntent)
    }
}
