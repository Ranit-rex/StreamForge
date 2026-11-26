package app.streamforge.ui

import android.content.Intent
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import app.streamforge.stream.StreamService
import kotlinx.coroutines.delay

private var Background by mutableStateOf(Color(0xFF0D0F11))
private var Panel by mutableStateOf(Color(0xFF15191D))
private var PanelRaised by mutableStateOf(Color(0xFF20262B))
private var Accent by mutableStateOf(Color(0xFFFF6A35))
private var Muted by mutableStateOf(Color(0xFFA1A8AF))
private var AppText by mutableStateOf(Color(0xFFF3F1EE))
private val LiveRed = Color(0xFFFF5263)
private val Good = Color(0xFF55D6A1)

private fun applyAppPalette(mode: String, accentHex: String) {
    val light = mode == "light"
    Background = if (light) Color(0xFFF3F5F7) else Color(0xFF0D0F11)
    Panel = if (light) Color.White else Color(0xFF15191D)
    PanelRaised = if (light) Color(0xFFE7EBEF) else Color(0xFF20262B)
    Muted = if (light) Color(0xFF626C75) else Color(0xFFA1A8AF)
    AppText = if (light) Color(0xFF171A1D) else Color(0xFFF3F1EE)
    Accent = runCatching { Color(android.graphics.Color.parseColor(accentHex)) }.getOrDefault(Color(0xFFFF6A35))
}

private fun saveProgramPreviewScreenshot(context: android.content.Context): Boolean {
    val bitmap = runCatching { StreamService.previewView?.bitmap }.getOrNull() ?: return false
    return runCatching {
        val name = "StreamForge_Screenshot_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}.jpg"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StreamForge")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create screenshot file")
        context.contentResolver.openOutputStream(uri)?.use { output ->
            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 94, output)) {
                throw IllegalStateException("Could not save screenshot")
            }
        } ?: throw IllegalStateException("Could not write screenshot")
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        }
        true
    }.getOrDefault(false).also { if (!bitmap.isRecycled) bitmap.recycle() }
}

private data class StreamQuality(val label: String, val width: Int, val height: Int, val fps: Int, val bitrate: Int)

private val qualityOptions = listOf(
    StreamQuality("4K Ultra HD · 30 FPS", 3840, 2160, 30, 18_000_000),
    StreamQuality("1440p QHD · 30 FPS", 2560, 1440, 30, 12_000_000),
    StreamQuality("1080p Full HD · 60 FPS", 1920, 1080, 60, 7_500_000),
    StreamQuality("1080p Full HD · 30 FPS", 1920, 1080, 30, 5_000_000),
    StreamQuality("720p HD · 60 FPS", 1280, 720, 60, 4_500_000),
    StreamQuality("720p HD · 30 FPS", 1280, 720, 30, 2_500_000),
    StreamQuality("480p Data Saver · 30 FPS", 854, 480, 30, 1_500_000)
)

@Composable
fun StreamForgeApp(
    onStartStream: (String, Int, Int, Int, Int, String) -> Unit,
    onStopStream: () -> Unit,
    onStartRecording: (Int, Int, Int, Int) -> Unit,
    onStopRecording: () -> Unit,
    onPreparePreview: (Int, Int, Int, Int, String) -> Unit,
    onStopStudioPreview: () -> Unit,
    onChangeCaptureSource: (String, Int, Int, Int, Int, String) -> Unit,
    compactMode: Boolean = false
 ) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("streamforge_studio", android.content.Context.MODE_PRIVATE) }
    var platform by remember { mutableStateOf("YouTube") }
    var server by remember { mutableStateOf("rtmps://a.rtmp.youtube.com/live2") }
    var streamKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    val lowRamDevice = remember {
        val manager = context.getSystemService(android.app.ActivityManager::class.java)
        val memory = android.app.ActivityManager.MemoryInfo()
        manager?.getMemoryInfo(memory)
        manager?.isLowRamDevice == true || (memory.totalMem > 0L && memory.totalMem <= 2_500_000_000L)
    }
    // Start low-memory phones in Data Saver; users can still manually choose a higher profile.
    var selectedQuality by remember { mutableStateOf(qualityOptions.getOrElse(prefs.getInt("quality_index", if (lowRamDevice) 6 else 5)) { qualityOptions[if (lowRamDevice) 6 else 5] }) }
    var captureMode by remember { mutableStateOf(if (android.os.Build.VERSION.SDK_INT >= 34) "GAME" else "FULL") }
    var showLiveDashboard by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showMiniAssistant by remember { mutableStateOf(false) }
    var uiThemeMode by remember { mutableStateOf(prefs.getString("ui_theme_mode", "dark") ?: "dark") }
    var uiAccent by remember { mutableStateOf(prefs.getString("ui_accent", "#FF6A35") ?: "#FF6A35") }
    LaunchedEffect(uiThemeMode, uiAccent) { applyAppPalette(uiThemeMode, uiAccent) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    var colorBrightness by remember { mutableFloatStateOf(prefs.getFloat("color_brightness", 1f)) }
    var colorContrast by remember { mutableFloatStateOf(prefs.getFloat("color_contrast", 1f)) }
    var colorSaturation by remember { mutableFloatStateOf(prefs.getFloat("color_saturation", 1f)) }
    var colorSharpness by remember { mutableFloatStateOf(prefs.getFloat("color_sharpness", 0f)) }
    var targetBitrateMbps by remember { mutableFloatStateOf(prefs.getFloat("video_bitrate_mbps", 2.5f).coerceIn(0.8f, 35f)) }
    var adaptiveBitrate by remember { mutableStateOf(prefs.getBoolean("adaptive_bitrate", true)) }
    var audioBitrateKbps by remember { mutableFloatStateOf(prefs.getInt("audio_bitrate_kbps", 128).toFloat()) }
    var audioEnhancements by remember { mutableStateOf(prefs.getBoolean("audio_enhancements", true)) }
    var microphoneGain by remember { mutableFloatStateOf(prefs.getFloat("audio_mic_gain", 1f).coerceIn(0f, 2f)) }
    var gameGain by remember { mutableFloatStateOf(prefs.getFloat("audio_game_gain", 1f).coerceIn(0f, 2f)) }
    var keepScreenAwake by remember { mutableStateOf(prefs.getBoolean("keep_screen_awake", true)) }
    var showRecordingConfirm by remember { mutableStateOf(false) }
    var showRecordingStarted by remember { mutableStateOf(false) }
    var lastRecordingStartedAt by remember { mutableLongStateOf(0L) }
    var recordingElapsedSeconds by remember { mutableLongStateOf(0L) }
    var destinationExpanded by remember { mutableStateOf(false) }
    var audioExpanded by remember { mutableStateOf(false) }
    var qualityExpanded by remember { mutableStateOf(false) }
    var colorExpanded by remember { mutableStateOf(false) }
    val status by StreamService.status.collectAsState()
    val isStreaming by StreamService.streaming.collectAsState()
    val isRecording by StreamService.recording.collectAsState()
    val isRecordingPaused by StreamService.recordingPaused.collectAsState()
    val studioPreviewReady by StreamService.studioPreviewReady.collectAsState()
    val bitrate by StreamService.bitrateKbps.collectAsState()
    val liveResolution by StreamService.streamResolution.collectAsState()
    val liveFps by StreamService.streamFps.collectAsState()
    val startedAt by StreamService.streamStartedAt.collectAsState()
    val networkHealth by StreamService.networkHealth.collectAsState()
    val microphoneMuted by StreamService.microphoneMuted.collectAsState()
    val recordingStartedAt by StreamService.recordingStartedAt.collectAsState()

    LaunchedEffect(recordingStartedAt) {
        if (recordingStartedAt > 0L && lastRecordingStartedAt == 0L) {
            lastRecordingStartedAt = recordingStartedAt
            showRecordingStarted = true
        } else if (recordingStartedAt == 0L) {
            lastRecordingStartedAt = 0L
        }
    }

    LaunchedEffect(isRecording, isRecordingPaused, recordingStartedAt) {
        if (isRecording && !isRecordingPaused && recordingStartedAt > 0L) {
            while (isRecording && !isRecordingPaused) {
                recordingElapsedSeconds = ((System.currentTimeMillis() - recordingStartedAt) / 1000L).coerceAtLeast(0L)
                delay(1000)
            }
        } else if (!isRecording) recordingElapsedSeconds = 0L
    }

    LaunchedEffect(isStreaming, startedAt) {
        if (isStreaming && startedAt > 0L) {
            while (isStreaming) {
                elapsedSeconds = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L)
                delay(1000)
            }
        } else elapsedSeconds = 0L
    }

    MaterialTheme(colorScheme = if (uiThemeMode == "light") lightColorScheme(primary = Accent, onPrimary = Color.White, secondary = Accent, background = Background, surface = Panel, onSurface = AppText) else darkColorScheme(primary = Accent, onPrimary = Color(0xFF1B100B), secondary = Accent, background = Background, surface = Panel, onSurface = AppText)) {
        Surface(Modifier.fillMaxSize(), color = Background) {
            if (compactMode) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(if (isStreaming) "● STREAMFORGE LIVE" else if (isRecording) "● RECORDING" else "STREAMFORGE", color = if (isStreaming) LiveRed else Accent, fontSize = 11.sp, fontWeight = FontWeight.Black)
                        Text(if (isStreaming) "${formatDuration(elapsedSeconds)}  ·  ${bitrate} kbps" else status, color = AppText, fontSize = 10.sp, maxLines = 1)
                    }
                    TextButton(onClick = {
                        context.startService(Intent(context, StreamService::class.java).setAction(StreamService.ACTION_TOGGLE_MIC))
                    }, enabled = isStreaming) { Text(if (microphoneMuted) "MIC OFF" else "MIC", color = if (microphoneMuted) LiveRed else Accent, fontSize = 10.sp) }
                    TextButton(onClick = onStopStream, enabled = isStreaming) { Text("END", color = LiveRed, fontWeight = FontWeight.Black, fontSize = 10.sp) }
                }
            }
            Column(Modifier.then(if (compactMode) Modifier.size(0.dp) else Modifier.fillMaxSize())) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().statusBarsPadding().imePadding()
                        .then(if (selectedTab == 2) Modifier else Modifier.verticalScroll(rememberScrollState()))
                         .padding(horizontal = if (selectedTab == 2) 7.dp else 12.dp, vertical = if (selectedTab == 2) 2.dp else 8.dp),
                    verticalArrangement = Arrangement.spacedBy(if (selectedTab == 2) 3.dp else 8.dp)
                ) {
                    StudioHeader(isStreaming, isRecording, onSettings = { selectedTab = 4 })
                    if (isRecording) RecordingDashboard(
                        elapsed = recordingElapsedSeconds,
                        resolution = liveResolution,
                        onStop = onStopRecording
                    )

                    when (selectedTab) {
                        2 -> SceneStudio(
                            previewReady = studioPreviewReady,
                            isStreaming = isStreaming || isRecording,
                            onPreparePreview = { onPreparePreview(selectedQuality.width, selectedQuality.height, selectedQuality.fps, (targetBitrateMbps * 1_000_000f).toInt(), captureMode) },
                            onStopStudioPreview = onStopStudioPreview,
                            onChangeCaptureSource = { onChangeCaptureSource(server.trim().trimEnd('/') + "/" + streamKey.trim().trimStart('/'), selectedQuality.width, selectedQuality.height, selectedQuality.fps, (targetBitrateMbps * 1_000_000f).toInt(), captureMode) },
                            onApplyScene = { sceneIndex, sceneJson ->
                                if (StreamService.streaming.value || StreamService.studioPreviewReady.value || StreamService.recording.value) {
                                    context.startService(Intent(context, StreamService::class.java).apply {
                                        action = StreamService.ACTION_APPLY_SCENE
                                        putExtra(StreamService.EXTRA_SCENE_INDEX, sceneIndex)
                                        putExtra(StreamService.EXTRA_SCENE_JSON, sceneJson)
                                    })
                                }
                            }
                        )
                        3 -> RecordingLibrary()
                        5 -> MiniAiAssistant(
                            appContext = "section=AI Assistant, streaming=$isStreaming, recording=$isRecording, bitrateKbps=$bitrate, resolution=$liveResolution, fps=$liveFps, quality=${selectedQuality.label}",
                            onOpenFull = {},
                            onAction = { action ->
                                selectedTab = when (action) {
                                    "STUDIO" -> 0
                                    "SCENES" -> 2
                                    "SETTINGS" -> 4
                                    "RECORDINGS" -> 3
                                    else -> selectedTab
                                }
                            }
                        )
                        6 -> LiveChatPanel(onConfigure = { selectedTab = 10 })
                        10 -> ChatIntegrationPanel()
                        9 -> PartyVoicePanel()
                        11 -> VideoEditorPanel()
                        12 -> CastDooNotificationsPanel()
                        7 -> DestinationsPanel()
                        8 -> MoreToolsPanel(onNavigate = { selectedTab = it })
                        4 -> {
                        SettingsPanel(
                            uiThemeMode = uiThemeMode,
                            onThemeMode = { mode ->
                                uiThemeMode = mode
                                prefs.edit().putString("ui_theme_mode", mode).apply()
                            },
                            uiAccent = uiAccent,
                            onAccent = { hex ->
                                uiAccent = hex
                                prefs.edit().putString("ui_accent", hex).apply()
                            },
                            quality = selectedQuality,
                            onQuality = {
                                selectedQuality = it
                                targetBitrateMbps = it.bitrate / 1_000_000f
                                context.getSharedPreferences("streamforge_studio", android.content.Context.MODE_PRIVATE)
                                    .edit().putInt("quality_index", qualityOptions.indexOf(it))
                                    .putFloat("video_bitrate_mbps", targetBitrateMbps).apply()
                            },
                            bitrateMbps = targetBitrateMbps,
                            onBitrate = {
                                targetBitrateMbps = it
                                context.getSharedPreferences("streamforge_studio", android.content.Context.MODE_PRIVATE)
                                    .edit().putFloat("video_bitrate_mbps", it).apply()
                            },
                            adaptiveBitrate = adaptiveBitrate,
                            onAdaptiveBitrate = {
                                adaptiveBitrate = it
                                context.getSharedPreferences("streamforge_studio", android.content.Context.MODE_PRIVATE)
                                    .edit().putBoolean("adaptive_bitrate", it).apply()
                            },
                            audioBitrateKbps = audioBitrateKbps,
                            onAudioBitrate = {
                                audioBitrateKbps = it
                                context.getSharedPreferences("streamforge_studio", android.content.Context.MODE_PRIVATE)
                                    .edit().putInt("audio_bitrate_kbps", it.toInt()).apply()
                            },
                            audioEnhancements = audioEnhancements,
                            onAudioEnhancements = {
                                audioEnhancements = it
                                context.getSharedPreferences("streamforge_studio", android.content.Context.MODE_PRIVATE)
                                    .edit().putBoolean("audio_enhancements", it).apply()
                            },
                            keepScreenAwake = keepScreenAwake,
                            onKeepScreenAwake = {
                                keepScreenAwake = it
                                context.getSharedPreferences("streamforge_studio", android.content.Context.MODE_PRIVATE)
                                    .edit().putBoolean("keep_screen_awake", it).apply()
                                (context as? android.app.Activity)?.window?.let { window ->
                                    if (it) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                    else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                }
                            },
                            brightness = colorBrightness, contrast = colorContrast,
                            saturation = colorSaturation, sharpness = colorSharpness,
                            onColor = { b, c, s, sh ->
                                colorBrightness = b; colorContrast = c; colorSaturation = s; colorSharpness = sh
                                // Persist tuning locally; do not start the encoder service for every slider tick.
                                context.getSharedPreferences("streamforge_studio", android.content.Context.MODE_PRIVATE)
                                    .edit()
                                    .putFloat("color_brightness", b)
                                    .putFloat("color_contrast", c)
                                    .putFloat("color_saturation", s)
                                    .putFloat("color_sharpness", sh)
                                    .apply()
                                // Only send live updates when a stream is already running.
                                if (StreamService.streaming.value) {
                                    context.startService(Intent(context, StreamService::class.java).apply {
                                        action = StreamService.ACTION_UPDATE_COLOR
                                        putExtra(StreamService.EXTRA_COLOR_BRIGHTNESS, b)
                                        putExtra(StreamService.EXTRA_COLOR_CONTRAST, c)
                                        putExtra(StreamService.EXTRA_COLOR_SATURATION, s)
                                        putExtra(StreamService.EXTRA_COLOR_SHARPNESS, sh)
                                    })
                                }
                            }
                        )
                        RewardedAdsPanel()
                        ProblemReporterPanel()
                        }
                        else -> {
                            Text("PROGRAM PREVIEW", color = AppText, fontSize = 11.sp, fontWeight = FontWeight.Black)
                            StudioPreviewSurface(
                                previewReady = studioPreviewReady,
                                isStreaming = isStreaming || isRecording,
                                onPrepare = { onPreparePreview(selectedQuality.width, selectedQuality.height, selectedQuality.fps, (targetBitrateMbps * 1_000_000f).toInt(), captureMode) },
                                onStop = onStopStudioPreview,
                                onChangeSource = { onChangeCaptureSource(server.trim().trimEnd('/') + "/" + streamKey.trim().trimStart('/'), selectedQuality.width, selectedQuality.height, selectedQuality.fps, (targetBitrateMbps * 1_000_000f).toInt(), captureMode) }
                            )
                            if (isStreaming) {
                                if (showLiveDashboard) LiveDashboard(
                                    elapsed = elapsedSeconds, bitrate = bitrate, resolution = liveResolution,
                                    fps = liveFps, networkHealth = networkHealth, targetBitrateMbps = targetBitrateMbps,
                                    onBitrateChange = { value ->
                                        targetBitrateMbps = value
                                        prefs.edit().putFloat("video_bitrate_mbps", value).apply()
                                        context.startService(Intent(context, StreamService::class.java).apply {
                                            action = StreamService.ACTION_SET_LIVE_BITRATE
                                            putExtra(StreamService.EXTRA_BITRATE, (value * 1_000_000f).toInt())
                                        })
                                    },
                                    onHide = { showLiveDashboard = false }, onStop = onStopStream
                                ) else Surface(color = Color(0xFF2A1B17), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                    Row(Modifier.fillMaxWidth().clickable { showLiveDashboard = true }.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                                        LiveDot()
                                        Text("LIVE  ·  ${formatDuration(elapsedSeconds)}", color = AppText, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                        Text("VIEW STATS  ↗", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                InfoTile("OUTPUT", if (isStreaming || studioPreviewReady) liveResolution else selectedQuality.label.substringBefore(" · "), Modifier.weight(1f))
                                InfoTile("FPS", if (isStreaming || studioPreviewReady) "$liveFps" else "${selectedQuality.fps}", Modifier.weight(1f))
                                InfoTile("BITRATE", if (isStreaming && bitrate > 0) "$bitrate kbps" else if (studioPreviewReady) "Standby" else "—", Modifier.weight(1f))
                            }
                            SoundPanel(
                                microphoneGain = microphoneGain,
                                gameGain = gameGain,
                                expanded = audioExpanded,
                                onToggle = { audioExpanded = !audioExpanded },
                                noiseCancellation = audioEnhancements,
                                onMicrophoneGain = {
                                    microphoneGain = it
                                    prefs.edit().putFloat("audio_mic_gain", it).apply()
                                    if (isStreaming || isRecording) context.startService(Intent(context, StreamService::class.java).apply {
                                        action = StreamService.ACTION_UPDATE_AUDIO_MIX
                                        putExtra(StreamService.EXTRA_MIC_GAIN, it)
                                        putExtra(StreamService.EXTRA_GAME_GAIN, gameGain)
                                    })
                                },
                                onGameGain = {
                                    gameGain = it
                                    prefs.edit().putFloat("audio_game_gain", it).apply()
                                    if (isStreaming || isRecording) context.startService(Intent(context, StreamService::class.java).apply {
                                        action = StreamService.ACTION_UPDATE_AUDIO_MIX
                                        putExtra(StreamService.EXTRA_MIC_GAIN, microphoneGain)
                                        putExtra(StreamService.EXTRA_GAME_GAIN, it)
                                    })
                                },
                                onNoiseCancellation = {
                                    audioEnhancements = it
                                    prefs.edit().putBoolean("audio_enhancements", it).apply()
                                },
                                isStreaming = isStreaming
                            )

                            Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("SOURCE & LIVE", color = AppText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                }
                                        Surface(color = PanelRaised, shape = RoundedCornerShape(14.dp)) {
                                            Text("▣", Modifier.padding(15.dp), color = Accent, fontSize = 26.sp)
                                        }
                                    }
                                    Text("SOURCE", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                    if (android.os.Build.VERSION.SDK_INT >= 34) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            CaptureModeCard("APP", "Single app.", captureMode == "GAME", Modifier.weight(1f)) { captureMode = "GAME" }
                                            CaptureModeCard("DISPLAY", "Entire screen.", captureMode == "FULL", Modifier.weight(1f)) { captureMode = "FULL" }
                                        }
                                    } else {
                                        CaptureModeCard("DISPLAY", "Entire screen · compatible with this Android version.", true, Modifier.fillMaxWidth()) { captureMode = "FULL" }
                                    }
                                    StatusLine(isStreaming, status)
                                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                        Button(
                                            onClick = {
                                                if (isStreaming) onStopStream() else {
                                                    val cleanServer = server.trim().trimEnd('/')
                                                    val cleanKey = streamKey.trim().trimStart('/')
                                                    if ((cleanServer.startsWith("rtmp://") || cleanServer.startsWith("rtmps://")) && cleanKey.isNotBlank()) {
                                                        onStartStream("$cleanServer/$cleanKey", selectedQuality.width, selectedQuality.height, selectedQuality.fps, (targetBitrateMbps * 1_000_000f).toInt(), captureMode)
                                                    }
                                                }
                                            },
                                            modifier = Modifier.weight(1.25f).height(52.dp),
                                            enabled = isStreaming || (!isRecording && streamKey.isNotBlank() && (server.startsWith("rtmp://") || server.startsWith("rtmps://"))),
                                            colors = ButtonDefaults.buttonColors(containerColor = if (isStreaming) Color(0xFF57271F) else Accent, contentColor = if (isStreaming) AppText else Color(0xFF1B100B)),
                                            shape = RoundedCornerShape(14.dp)
                                        ) { Text(if (isStreaming) "■  END LIVE" else "●  GO LIVE", fontWeight = FontWeight.Black, fontSize = 13.sp) }
                                        OutlinedButton(
                                            onClick = { if (isRecording) onStopRecording() else showRecordingConfirm = true },
                                            modifier = Modifier.weight(1f).height(52.dp),
                                            shape = RoundedCornerShape(14.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (isRecording) LiveRed else AppText)
                                        ) { Text(if (isRecording) "■  STOP REC" else "◉  RECORD", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                                    }
                                    if (isRecording) Text("Local MP4 recording is active · ${formatDuration(recordingElapsedSeconds)}", color = LiveRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    if (isStreaming) {
                                        OutlinedButton(
                                            onClick = {
                                                context.startService(Intent(context, StreamService::class.java).setAction(StreamService.ACTION_TOGGLE_MIC))
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (microphoneMuted) LiveRed else Good)
                                        ) {
                                            Text(if (microphoneMuted) "MICROPHONE MUTED · TAP TO UNMUTE" else "MICROPHONE ON · TAP TO MUTE", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }

                            Text("STREAM SETUP", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Black)
                            StudioAccordion("01", "Destination", platform, destinationExpanded, { destinationExpanded = !destinationExpanded }) {
                                PlatformPicker(platform) { selected ->
                                    platform = selected
                                    server = when (selected) {
                                        "YouTube" -> "rtmps://a.rtmp.youtube.com/live2"
                                        "Kick" -> "rtmps://fa723fc1b171.global-contribute.live-video.net/app"
                                        "Twitch" -> "rtmps://live.twitch.tv/app"
                                        "Facebook Live" -> "rtmps://live-api-s.facebook.com:443/rtmp/"
                                        "TikTok Live" -> ""
                                        else -> ""
                                    }
                                }
                                if (platform == "YouTube") {
                                    val youtubePrefs = remember { context.getSharedPreferences("streamforge_youtube_link", android.content.Context.MODE_PRIVATE) }
                                    val youtubeLinked = remember { mutableStateOf(youtubePrefs.getBoolean("linked", false)) }
                                    LaunchedEffect(selectedTab) {
                                        youtubeLinked.value = youtubePrefs.getBoolean("linked", false)
                                    }
                                    OutlinedButton(
                                        onClick = { selectedTab = 7 },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(if (youtubeLinked.value) "YOUTUBE ACCOUNT LINKED · MANAGE" else "CONNECT YOUTUBE ACCOUNT")
                                    }
                                    Text(
                                        if (youtubeLinked.value) "Your channel is linked on this device. Open to manage the connection."
                                        else "Link your Google / YouTube account first. RTMP server and stream key below are still used for the actual broadcast.",
                                        color = Muted,
                                        fontSize = 10.sp
                                    )
                                }
                                OutlinedTextField(value = server, onValueChange = { server = it }, modifier = Modifier.fillMaxWidth(), label = { Text("RTMP / RTMPS server") }, singleLine = true)
                                OutlinedTextField(
                                    value = streamKey, onValueChange = { streamKey = it }, modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Stream key") }, singleLine = true,
                                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "HIDE" else "SHOW") } }
                                )
                                Text("Stream key stays on this device and is never sent to StreamForge servers.", color = Muted, fontSize = 10.sp)
                            }
                            StudioAccordion("02", "Video quality", selectedQuality.label, qualityExpanded, { qualityExpanded = !qualityExpanded }) {
                                QualityPicker(selectedQuality) {
                                    selectedQuality = it
                                    targetBitrateMbps = it.bitrate / 1_000_000f
                                    context.getSharedPreferences("streamforge_studio", android.content.Context.MODE_PRIVATE)
                                        .edit().putInt("quality_index", qualityOptions.indexOf(it))
                                        .putFloat("video_bitrate_mbps", targetBitrateMbps).apply()
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    InfoTile("OUTPUT", "${selectedQuality.width}×${selectedQuality.height}", Modifier.weight(1f))
                                    InfoTile("FRAME RATE", "${selectedQuality.fps} FPS", Modifier.weight(1f))
                                    InfoTile("BITRATE", "${selectedQuality.bitrate / 1_000_000.0} Mbps", Modifier.weight(1f))
                                }
                                Text("4K requires a capable encoder and strong, stable upload speed. Adaptive bitrate responds to network congestion.", color = Muted, fontSize = 10.sp)
                            }
                            Text("TOOLS", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Black)
                            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                QuickTile("▦", "Scenes", "", Modifier.weight(1f)) { selectedTab = 2 }
                                QuickTile("▤", "Recordings", "", Modifier.weight(1f)) { selectedTab = 3 }
                                QuickTile("⚙", "Settings", "", Modifier.weight(1f)) { selectedTab = 4 }
                            }

                        }
                    }
                }
                NavigationBar(containerColor = Panel, contentColor = Accent, tonalElevation = 0.dp, modifier = Modifier.fillMaxWidth().background(Panel)) {
                    val destinations = listOf(
                        Triple(0, "Studio", "●"),
                        Triple(2, "Scenes", "▦"),
                        Triple(9, "Party", "◉"),
                        Triple(6, "Live Chat", "▤"),
                        Triple(8, "More", "•••")
                    )
                    destinations.forEach { (tab, label, icon) ->
                        NavigationBarItem(
                            selected = if (tab == 8) selectedTab == 8 || selectedTab in 3..5 || selectedTab == 7 || selectedTab == 10 || selectedTab == 11 else selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = { Text(icon, fontSize = 17.sp) },
                            label = { Text(label, fontSize = 10.sp, maxLines = 1) },
                            alwaysShowLabel = true
                        )
                    }
                }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                FloatingActionButton(
                    onClick = { showMiniAssistant = true },
                    modifier = Modifier.padding(end = 16.dp, bottom = 78.dp),
                    containerColor = Accent,
                    contentColor = Color(0xFF17120F)
                ) { Text("AI", fontWeight = FontWeight.Black, fontSize = 13.sp) }
            }
            if (showMiniAssistant) {
                Dialog(onDismissRequest = { showMiniAssistant = false }) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 680.dp),
                        color = Panel,
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("STREAMFORGE MINI", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Black)
                                    Text("AI Assistant", color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                }
                                TextButton(onClick = { showMiniAssistant = false }) { Text("CLOSE") }
                            }
                            MiniAiAssistant(
                                appContext = "section=${when(selectedTab){0->"Studio";2->"Scenes";3->"Recordings";4->"Settings";5->"AI Assistant";else->"More"}}, streaming=$isStreaming, recording=$isRecording, bitrateKbps=$bitrate, resolution=$liveResolution, fps=$liveFps, quality=${selectedQuality.label}",
                                onOpenFull = { showMiniAssistant = false; selectedTab = 5 },
                                onAction = { action ->
                                    selectedTab = when(action) {
                                        "STUDIO" -> 0
                                        "SCENES" -> 2
                                        "SETTINGS" -> 4
                                        "RECORDINGS" -> 3
                                        "FULL_AI" -> 5
                                        else -> selectedTab
                                    }
                                    showMiniAssistant = false
                                }
                            )
                        }
                    }
                }
            }
            if (showRecordingStarted) {
                AlertDialog(
                    onDismissRequest = { showRecordingStarted = false },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LiveDot()
                            Text("Recording started", color = AppText)
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Your MP4 recording is active.", color = Muted)
                            Text("${if (isRecordingPaused) "PAUSED" else "REC"}  ·  ${formatDuration(recordingElapsedSeconds)}  ·  ${liveResolution}", color = if (isRecordingPaused) Muted else LiveRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        context.startService(Intent(context, StreamService::class.java).setAction(StreamService.ACTION_TOGGLE_RECORDING_PAUSE))
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text(if (isRecordingPaused) "RESUME" else "PAUSE", fontSize = 9.sp) }
                                OutlinedButton(
                                    onClick = {
                                        val saved = saveProgramPreviewScreenshot(context)
                                        android.widget.Toast.makeText(context, if (saved) "Screenshot saved to Pictures/StreamForge" else "Preview screenshot unavailable", android.widget.Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("TAKE SS", fontSize = 9.sp) }
                                OutlinedButton(
                                    onClick = { showRecordingStarted = false; selectedTab = 2 },
                                    modifier = Modifier.weight(1f)
                                ) { Text("SCENE", fontSize = 9.sp) }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showRecordingStarted = false; onStopRecording() }) {
                            Text("STOP RECORDING", color = LiveRed)
                        }
                    },
                    dismissButton = { TextButton(onClick = { showRecordingStarted = false }) { Text("CONTINUE") } },
                    containerColor = Panel,
                    titleContentColor = AppText,
                    textContentColor = Muted
                )
            }
            if (showRecordingConfirm) {
                AlertDialog(
                    onDismissRequest = { showRecordingConfirm = false },
                    title = { Text("Start screen recording?") },
                    text = {
                        Text("StreamForge will capture your selected screen/app and save an MP4 locally at ${selectedQuality.label} with a ${targetBitrateMbps.toInt()} Mbps target. Android will show its screen-capture permission prompt next.")
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showRecordingConfirm = false
                            onStartRecording(selectedQuality.width, selectedQuality.height, selectedQuality.fps, (targetBitrateMbps * 1_000_000f).toInt())
                        }) { Text("CONTINUE") }
                    },
                    dismissButton = { TextButton(onClick = { showRecordingConfirm = false }) { Text("CANCEL") } },
                    containerColor = Panel,
                    titleContentColor = AppText,
                    textContentColor = Muted
                )
            }
        }
    }
}

@Composable
fun StudioPreviewSurface(
    previewReady: Boolean,
    isStreaming: Boolean,
    onPrepare: () -> Unit,
    onStop: () -> Unit,
    onChangeSource: () -> Unit,
    compact: Boolean = false,
    sourceMonitorOnly: Boolean = false
) {
    Surface(color = Color.Black, shape = if (compact) RoundedCornerShape(0.dp) else RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = if (compact) 0.dp else 8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            if (isStreaming || (sourceMonitorOnly && previewReady)) {
                AndroidView(
                    factory = { viewContext ->
                        TextureView(viewContext).also { view ->
                            StreamService.previewView = view
                            view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                                    val generation = ++StreamService.previewSurfaceGeneration
                                    if (StreamService.streaming.value || StreamService.recording.value || StreamService.studioPreviewReady.value) {
                                        viewContext.startService(Intent(viewContext, StreamService::class.java)
                                            .setAction(StreamService.ACTION_PREVIEW_SURFACE_READY)
                                            .putExtra(StreamService.EXTRA_PREVIEW_GENERATION, generation))
                                    }
                                }
                                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                    val generation = ++StreamService.previewSurfaceGeneration
                                    if (StreamService.streaming.value || StreamService.recording.value || StreamService.studioPreviewReady.value) {
                                        viewContext.startService(Intent(viewContext, StreamService::class.java)
                                            .setAction(StreamService.ACTION_STOP_PREVIEW)
                                            .putExtra(StreamService.EXTRA_PREVIEW_GENERATION, generation))
                                    }
                                    if (StreamService.previewView === view) StreamService.previewView = null
                                    return true
                                }
                            }
                        }
                    },
                    update = { StreamService.previewView = it },
                    modifier = Modifier.fillMaxSize()
                )
                Surface(color = Color(0xCC15191D), shape = RoundedCornerShape(7.dp), modifier = Modifier.align(Alignment.TopStart).padding(7.dp)) {
                    Text(if (StreamService.streaming.value) "● LIVE OUTPUT" else if (StreamService.recording.value) "● REC OUTPUT" else "● SOURCE PREVIEW", color = if (StreamService.streaming.value || StreamService.recording.value) LiveRed else Accent, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), fontSize = 9.sp, fontWeight = FontWeight.Black)
                }
                if (isStreaming) {
                    TextButton(onClick = onChangeSource, modifier = Modifier.align(Alignment.TopEnd), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("CHANGE SOURCE", color = Accent, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    TextButton(onClick = onStop, modifier = Modifier.align(Alignment.TopEnd), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("STOP PREVIEW", color = Accent, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                // Always render the active scene while screen capture is inactive.
                // Decode-only prevents two preview panes fighting over one MediaPlayer Surface.
                SceneCompositionPreview(Modifier.fillMaxSize())
                Surface(color = Color(0xCC15191D), shape = RoundedCornerShape(7.dp), modifier = Modifier.align(Alignment.TopStart).padding(7.dp)) {
                    Text(if (sourceMonitorOnly) "● LIVE SCENE PREVIEW" else "● AUTO PREVIEW", color = Accent, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), fontSize = 9.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun RecordingDashboard(elapsed: Long, resolution: String, onStop: () -> Unit) {
    Surface(color = Color(0xFF321C1B), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(color = LiveRed, shape = CircleShape, modifier = Modifier.size(9.dp)) {}
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("RECORDING  ·  ${formatDuration(elapsed)}", color = AppText, fontWeight = FontWeight.Black, fontSize = 13.sp)
                Text("MP4 · $resolution · saved locally", color = Muted, fontSize = 10.sp)
            }
            TextButton(onClick = onStop) { Text("STOP", color = LiveRed, fontWeight = FontWeight.Black) }
        }
    }
}

@Composable
private fun StudioHeader(isStreaming: Boolean, isRecording: Boolean, onSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painter = painterResource(id = app.streamforge.R.drawable.ic_streamforge), contentDescription = "StreamForge logo", modifier = Modifier.size(25.dp).padding(end = 5.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("STREAMFORGE", color = AppText, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.1.sp)
            Text("MOBILE BROADCAST STUDIO", color = Muted, fontSize = 6.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .8.sp)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (isStreaming) LiveDot() else Surface(color = Good, shape = CircleShape, modifier = Modifier.size(6.dp)) {}
                Text(if (isStreaming) "ON AIR" else if (isRecording) "RECORDING" else "STANDBY", color = if (isStreaming) LiveRed else if (isRecording) Color(0xFFFFB27F) else Muted, fontSize = 9.sp, fontWeight = FontWeight.Black)
            }
            TextButton(onClick = onSettings, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp), modifier = Modifier.height(20.dp)) { Text("SETUP ⚙", color = Accent, fontSize = 8.sp) }
        }
    }
}

@Composable
private fun SectionHeading(kicker: String, title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(kicker, color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.35.sp)
        Text(title, color = AppText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusLine(isStreaming: Boolean, status: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(color = if (isStreaming) Color(0xFF3A2020) else Color(0xFF282D31), shape = RoundedCornerShape(50)) {
            Text(if (isStreaming) "●  ON AIR" else "○  READY", Modifier.padding(horizontal = 11.dp, vertical = 7.dp), color = if (isStreaming) LiveRed else Accent, fontSize = 10.sp, fontWeight = FontWeight.Black)
        }
        Text(status, color = Muted, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun LiveDot() {
    Surface(color = LiveRed, shape = CircleShape, modifier = Modifier.size(7.dp)) {}
}

@Composable
private fun StudioAccordion(
    number: String, title: String, summary: String, expanded: Boolean, onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
                Text(number, color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = AppText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(summary, color = Muted, fontSize = 10.sp, maxLines = 1)
                }
                Text(if (expanded) "−" else "+", color = Accent, fontSize = 22.sp)
            }
            if (expanded) content()
        }
    }
}

@Composable
private fun QuickTile(icon: String, title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(color = Panel, shape = RoundedCornerShape(15.dp), modifier = modifier.clickable(onClick = onClick)) {
        Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(icon, color = Accent, fontSize = 17.sp)
            Text(title, color = AppText, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            if (subtitle.isNotBlank()) Text(subtitle, color = Muted, fontSize = 8.sp, maxLines = 1)
        }
    }
}

@Composable
private fun SettingsPanel(
    uiThemeMode: String, onThemeMode: (String) -> Unit,
    uiAccent: String, onAccent: (String) -> Unit,
    quality: StreamQuality, onQuality: (StreamQuality) -> Unit,
    bitrateMbps: Float, onBitrate: (Float) -> Unit,
    adaptiveBitrate: Boolean, onAdaptiveBitrate: (Boolean) -> Unit,
    audioBitrateKbps: Float, onAudioBitrate: (Float) -> Unit,
    audioEnhancements: Boolean, onAudioEnhancements: (Boolean) -> Unit,
    keepScreenAwake: Boolean, onKeepScreenAwake: (Boolean) -> Unit,
    brightness: Float, contrast: Float, saturation: Float, sharpness: Float,
    onColor: (Float, Float, Float, Float) -> Unit
) {
    SectionHeading("APPEARANCE", "Theme & colour bank")
    Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Interface theme", color = AppText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = uiThemeMode == "dark", onClick = { onThemeMode("dark") }, label = { Text("Dark") })
                FilterChip(selected = uiThemeMode == "light", onClick = { onThemeMode("light") }, label = { Text("Light") })
            }
            Text("Accent colour", color = AppText, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            val colourBank = listOf("#FF6A35", "#4F8CFF", "#8B5CF6", "#D946EF", "#EC4899", "#EF4444", "#F59E0B", "#10B981", "#14B8A6", "#06B6D4", "#64748B", "#E5E7EB")
            colourBank.chunked(6).forEach { rowColours ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    rowColours.forEach { hex ->
                        val swatch = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Accent)
                        Surface(
                            color = swatch,
                            shape = CircleShape,
                            modifier = Modifier.size(30.dp)
                                .then(if (uiAccent.equals(hex, true)) Modifier.border(3.dp, AppText, CircleShape) else Modifier)
                                .clickable { onAccent(hex) }
                        ) {}
                    }
                }
            }
            Text("Your theme and accent are saved on this device.", color = Muted, fontSize = 10.sp)
        }
    }
    SectionHeading("OUTPUT", "Video & encoder settings")
    Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Resolution & frame rate", color = AppText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            QualityPicker(quality, onQuality)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoTile("REQUESTED OUTPUT", "${quality.width}×${quality.height}", Modifier.weight(1f))
                InfoTile("FRAME RATE", "${quality.fps} FPS", Modifier.weight(1f))
            }
            Text("Target video bitrate · ${"%.1f".format(bitrateMbps)} Mbps", color = AppText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Slider(value = bitrateMbps, onValueChange = onBitrate, valueRange = 0.8f..35f, steps = 169)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Adaptive bitrate", color = AppText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("Reduce bitrate on congestion, then recover gradually.", color = Muted, fontSize = 10.sp)
                }
                Switch(checked = adaptiveBitrate, onCheckedChange = onAdaptiveBitrate)
            }
            Text("Actual output is selected from the device's supported H.264 encoder modes. Unsupported 4K/1440p requests automatically fall back and show the real resolution.", color = Muted, fontSize = 10.sp)
        }
    }
    SectionHeading("IMAGE", "Colour grading")
    Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Live output colour controls", color = AppText, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { onColor(1f, 1f, 1f, 0f) }) { Text("RESET") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = false, onClick = { onColor(1f, 1f, 1f, 0f) }, label = { Text("Natural") })
                FilterChip(selected = false, onClick = { onColor(1.02f, 1.18f, 1.35f, 0.18f) }, label = { Text("Vivid") })
                FilterChip(selected = false, onClick = { onColor(0.96f, 1.22f, 0.82f, 0.12f) }, label = { Text("Cinema") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = false, onClick = { onColor(1.02f, 1.3f, 1.05f, 0.08f) }, label = { Text("Crisp") })
                FilterChip(selected = false, onClick = { onColor(1f, 0.95f, 0.72f, 0f) }, label = { Text("Soft") })
                FilterChip(selected = false, onClick = { onColor(1f, 1.1f, 0f, 0f) }, label = { Text("B&W") })
            }
            ColourSlider("Brightness", brightness, 0.8f..1.35f) { onColor(it, contrast, saturation, sharpness) }
            ColourSlider("Contrast", contrast, 0.8f..1.4f) { onColor(brightness, it, saturation, sharpness) }
            ColourSlider("Vibrance", saturation, 0f..2f) { onColor(brightness, contrast, it, sharpness) }
            ColourSlider("Sharpness", sharpness, 0f..0.8f) { onColor(brightness, contrast, saturation, it) }
        }
    }
    SectionHeading("APP", "Recording & behaviour")
    Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Audio bitrate · ${audioBitrateKbps.toInt()} kbps", color = AppText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Slider(value = audioBitrateKbps, onValueChange = onAudioBitrate, valueRange = 64f..320f, steps = 11)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mic noise suppression & echo cancellation", color = AppText, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("Applied when the audio encoder starts.", color = Muted, fontSize = 10.sp)
                }
                Switch(checked = audioEnhancements, onCheckedChange = onAudioEnhancements)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep screen awake", color = AppText, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("Prevent screen sleep while using StreamForge.", color = Muted, fontSize = 10.sp)
                }
                Switch(checked = keepScreenAwake, onCheckedChange = onKeepScreenAwake)
            }
            Text("Recording saves MP4 files to StreamForge's Movies folder. A persistent notification includes a Stop recording action while capture is active.", color = Muted, fontSize = 10.sp)
            Text("Stream key stays in the current app session and is not included in diagnostic reports.", color = Muted, fontSize = 10.sp)
        }
    }
}


@Composable
private fun SoundPanel(
    microphoneGain: Float,
    gameGain: Float,
    noiseCancellation: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onMicrophoneGain: (Float) -> Unit,
    onGameGain: (Float) -> Unit,
    onNoiseCancellation: (Boolean) -> Unit,
    isStreaming: Boolean
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
        Text("AUDIO", color = AppText, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(if (isStreaming) "LIVE" else "MIX", color = if (isStreaming) LiveRed else Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text(if (expanded) "−" else "+", color = Accent, fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp))
    }
    if (expanded) Surface(color = Panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MIC", color = AppText, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${(microphoneGain * 100).toInt()}%", color = Accent, fontSize = 10.sp)
            }
            Slider(value = microphoneGain, onValueChange = onMicrophoneGain, valueRange = 0f..2f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("GAME / APP", color = AppText, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${(gameGain * 100).toInt()}%", color = Accent, fontSize = 10.sp)
            }
            Slider(value = gameGain, onValueChange = onGameGain, valueRange = 0f..2f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Noise cancellation", color = AppText, fontSize = 10.sp, modifier = Modifier.weight(1f))
                Switch(checked = noiseCancellation, onCheckedChange = onNoiseCancellation)
            }
        }
    }
}

@Composable
private fun ColourSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text("$label  ${(value * 100).toInt()}%", color = AppText, fontSize = 11.sp)
    Slider(value = value, onValueChange = onChange, valueRange = range)
}

@Composable
private fun LiveDashboard(
    elapsed: Long, bitrate: Long, resolution: String, fps: Int, networkHealth: String,
    targetBitrateMbps: Float, onBitrateChange: (Float) -> Unit,
    onHide: () -> Unit, onStop: () -> Unit
) {
    Surface(color = Color(0xFF2A1B17), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = LiveRed, shape = RoundedCornerShape(50)) {
                    Text("● LIVE", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = AppText, fontSize = 10.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(9.dp))
                Text(formatDuration(elapsed), color = AppText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onHide) { Text("HIDE", color = Muted, fontSize = 10.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                LiveMetric("BITRATE", if (bitrate > 0) "$bitrate kbps" else "Starting…", Modifier.weight(1f))
                LiveMetric("OUTPUT", resolution, Modifier.weight(1f))
                LiveMetric("FPS", "$fps", Modifier.weight(1f))
            }
            Text("LIVE QUALITY  ·  ${"%.1f".format(targetBitrateMbps)} Mbps", color = AppText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Slider(value = targetBitrateMbps, onValueChange = onBitrateChange, valueRange = 0.8f..15f)
            Text("Network may still lower the actual bitrate automatically when connection quality drops.", color = Muted, fontSize = 9.sp)
            Text(networkHealth, color = if (networkHealth.contains("congested", ignoreCase = true)) Color(0xFFFFB27F) else Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("END BROADCAST") }
        }
    }
}

@Composable
private fun LiveMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = Color(0xFF35201C), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(9.dp)) {
            Text(label, color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Text(value, color = AppText, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, secs) else String.format("%02d:%02d", minutes, secs)
}

@Composable
private fun QualityPicker(selected: StreamQuality, onSelect: (StreamQuality) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected.label, Modifier.weight(1f), maxLines = 1)
            Text("▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            qualityOptions.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = { onSelect(option); expanded = false }) }
        }
    }
}

@Composable
private fun PlatformPicker(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected, Modifier.weight(1f)); Text("▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf("YouTube", "Twitch", "Kick", "Facebook Live", "TikTok Live", "Custom RTMP").forEach { name ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(name); expanded = false })
            }
        }
    }
}

@Composable
private fun InfoTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = PanelRaised, shape = RoundedCornerShape(11.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text(label, color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Text(value, color = AppText, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun PrimaryAction(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text(label) }
}


@Composable
private fun CaptureModeCard(title: String, detail: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = if (selected) Color(0xFF35231D) else Panel,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) Accent else Color(0xFF3B302A))
    ) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (selected) "●  $title" else "○  $title", color = if (selected) Accent else AppText, fontSize = 10.sp, fontWeight = FontWeight.Black)
            Text(detail, color = Muted, fontSize = 9.sp, lineHeight = 13.sp)
        }
    }
}

@Composable
private fun MoreToolsPanel(onNavigate: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeading("WORKSPACE", "More tools")
        Text("Everything beyond your main broadcast controls, grouped in one place.", color = Muted, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            QuickTile("▤", "Recordings", "Play, export & delete", Modifier.weight(1f)) { onNavigate(3) }
            QuickTile("⚙", "Settings", "Quality & behaviour", Modifier.weight(1f)) { onNavigate(4) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            QuickTile("✦", "AI Copilot", "Connect your AI model", Modifier.weight(1f)) { onNavigate(5) }
            QuickTile("☷", "Chat Connect", "YouTube chat setup", Modifier.weight(1f)) { onNavigate(10) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            QuickTile("✂", "Video Editor", "Trim & export videos", Modifier.weight(1f)) { onNavigate(11) }
            QuickTile("◎", "Destinations", "Link creator accounts", Modifier.weight(1f)) { onNavigate(7) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            QuickTile("♧", "Notifications", "Your announcements", Modifier.weight(1f)) { onNavigate(12) }
            QuickTile("◉", "Profile", "Account & preferences", Modifier.weight(1f)) { onNavigate(13) }
        }
        Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("STREAMFORGE", color = AppText, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.1.sp)
                Text("Direct-to-platform streaming · Android-native capture · Local MP4 recordings", color = Muted, fontSize = 10.sp)
                Text("Your stream key stays on this device. Platform chat and AI integrations require their own connection.", color = Muted, fontSize = 10.sp)
            }
        }
    }
}


private const val STREAMFORGE_YOUTUBE_SCOPE = "https://www.googleapis.com/auth/youtube"

private suspend fun fetchYouTubeChannel(accessToken: String): Pair<String, String> = withContext(Dispatchers.IO) {
    val connection = (URL("https://www.googleapis.com/youtube/v3/channels?part=snippet&mine=true").openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15000
        readTimeout = 20000
        setRequestProperty("Authorization", "Bearer $accessToken")
        setRequestProperty("Accept", "application/json")
    }
    try {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) {
            val message = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
            throw IllegalStateException(message?.takeIf { it.isNotBlank() } ?: "YouTube authorization failed (HTTP $code)")
        }
        val items = JSONObject(body).optJSONArray("items")
        if (items == null || items.length() == 0) throw IllegalStateException("This Google account does not have an accessible YouTube channel.")
        val item = items.getJSONObject(0)
        val snippet = item.optJSONObject("snippet")
        val title = snippet?.optString("title")?.takeIf { it.isNotBlank() } ?: "YouTube channel"
        val id = item.optString("id")
        if (id.isBlank()) throw IllegalStateException("YouTube did not return a channel ID.")
        title to id
    } finally {
        connection.disconnect()
    }
}

@Composable
private fun DestinationsPanel() {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val prefs = remember { context.getSharedPreferences("streamforge_youtube_link", android.content.Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var linked by remember { mutableStateOf(prefs.getBoolean("linked", false)) }
    var channelName by remember { mutableStateOf(prefs.getString("channel_name", "") ?: "") }
    var channelId by remember { mutableStateOf(prefs.getString("channel_id", "") ?: "") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var pendingAction by remember { mutableStateOf<((String) -> Unit)?>(null) }

    val authorizationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        try {
            val authorization = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data)
            val token = authorization.accessToken
            val action = pendingAction
            pendingAction = null
            if (!token.isNullOrBlank() && action != null) action(token)
            else {
                busy = false
                message = "YouTube permission was not granted."
            }
        } catch (error: Exception) {
            pendingAction = null
            busy = false
            message = error.message ?: "YouTube authorization was cancelled."
        }
    }

    fun requestYouTubeAccess(action: (String) -> Unit) {
        if (activity == null) {
            message = "Open StreamForge on an Android activity to connect YouTube."
            return
        }
        busy = true
        message = "Opening Google's secure permission screen…"
        pendingAction = action
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(STREAMFORGE_YOUTUBE_SCOPE)))
            .build()
        Identity.getAuthorizationClient(activity).authorize(request)
            .addOnSuccessListener { authorization ->
                if (authorization.hasResolution()) {
                    val pendingIntent = authorization.pendingIntent
                    if (pendingIntent != null) {
                        authorizationLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                    } else {
                        pendingAction = null
                        busy = false
                        message = "Google could not start the permission screen. Please try again."
                    }
                } else {
                    val token = authorization.accessToken
                    val next = pendingAction
                    pendingAction = null
                    if (!token.isNullOrBlank() && next != null) next(token)
                    else {
                        busy = false
                        message = "Google did not return an access token."
                    }
                }
            }
            .addOnFailureListener { error ->
                pendingAction = null
                busy = false
                message = error.message ?: "Could not connect to Google."
            }
    }

    fun loadChannel(token: String, announce: Boolean) {
        scope.launch {
            try {
                val result = fetchYouTubeChannel(token)
                channelName = result.first
                channelId = result.second
                linked = true
                prefs.edit()
                    .putBoolean("linked", true)
                    .putString("channel_name", result.first)
                    .putString("channel_id", result.second)
                    .apply()
                message = if (announce) "YouTube connected on this device." else ""
            } catch (error: Exception) {
                linked = false
                prefs.edit().putBoolean("linked", false).apply()
                message = error.message ?: "Could not read your YouTube channel."
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeading("CREATOR WORKSPACE", "Destinations")
        Text("Connect your YouTube channel directly from this phone. StreamForge does not receive or store your Google password or refresh token.", color = Muted, fontSize = 11.sp)
        Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("YOUTUBE", color = AppText, fontSize = 12.sp, fontWeight = FontWeight.Black)
                        Text(if (linked) "CONNECTED ON THIS DEVICE" else "NOT CONNECTED", color = if (linked) Good else Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    Surface(color = if (linked) Color(0xFF17372B) else PanelRaised, shape = RoundedCornerShape(50)) {
                        Text(if (linked) "● LINKED" else "○ OFFLINE", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = if (linked) Good else Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (linked && channelName.isNotBlank()) {
                    Surface(color = PanelRaised, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(channelName, color = AppText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("Channel ID: $channelId", color = Muted, fontSize = 9.sp)
                        }
                    }
                }
                Button(
                    onClick = {
                        requestYouTubeAccess { token -> loadChannel(token, true) }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "CONNECTING…" else if (linked) "REFRESH YOUTUBE CONNECTION" else "CONNECT YOUTUBE")
                }
                if (linked) {
                    OutlinedButton(
                        onClick = {
                            busy = true
                            val request = RevokeAccessRequest.builder()
                                .setScopes(listOf(Scope(STREAMFORGE_YOUTUBE_SCOPE)))
                                .build()
                            Identity.getAuthorizationClient(context).revokeAccess(request)
                                .addOnSuccessListener {
                                    linked = false
                                    channelName = ""
                                    channelId = ""
                                    prefs.edit().clear().apply()
                                    busy = false
                                    message = "YouTube access revoked and disconnected."
                                }
                                .addOnFailureListener { error ->
                                    busy = false
                                    message = error.message ?: "Google could not revoke access. Remove StreamForge from your Google Account permissions too."
                                }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("DISCONNECT & REVOKE ACCESS") }
                }
            }
        }
        Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("DEVICE-ONLY PRIVACY", color = AppText, fontSize = 11.sp, fontWeight = FontWeight.Black)
                Text("Google Play services manages the device authorization grant and short-lived access-token cache. StreamForge stores only your channel name and channel ID locally; it does not save OAuth access or refresh tokens, and no StreamForge database is used for this connection.", color = Muted, fontSize = 10.sp)
                Text("Disconnect revokes the app's granted Google access. You can also remove StreamForge anytime from your Google Account's third-party connections.", color = Muted, fontSize = 10.sp)
            }
        }
        Surface(color = PanelRaised, shape = RoundedCornerShape(13.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Broadcast creation, scheduled-stream selection and thumbnail upload will be enabled after the YouTube connection is verified in your Google Cloud project.", Modifier.padding(12.dp), color = Muted, fontSize = 9.sp)
        }
        if (message.isNotBlank()) {
            Text(message, color = if (message.contains("connected", true) || message.contains("revoked", true)) Good else Muted, fontSize = 10.sp)
        }
        Text("Other destinations will be added after YouTube is working end-to-end.", color = Muted, fontSize = 9.sp)
    }
}
