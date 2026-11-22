package app.streamforge.ui

import android.content.Intent
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import app.streamforge.stream.StreamService
import kotlinx.coroutines.delay

private val Background = Color(0xFF080B10)
private val Panel = Color(0xFF121923)
private val PanelRaised = Color(0xFF192331)
private val Accent = Color(0xFF9DB4FF)
private val Muted = Color(0xFF91A0B4)
private val LiveRed = Color(0xFFFF536B)
private val Good = Color(0xFF55D6A1)

private data class StreamQuality(val label: String, val width: Int, val height: Int, val fps: Int, val bitrate: Int)

private val qualityOptions = listOf(
    StreamQuality("4K Ultra HD · 30 FPS", 3840, 2160, 30, 18_000_000),
    StreamQuality("1440p QHD · 30 FPS", 2560, 1440, 30, 12_000_000),
    StreamQuality("1080p Full HD · 60 FPS", 1920, 1080, 60, 7_500_000),
    StreamQuality("1080p Full HD · 30 FPS", 1920, 1080, 30, 5_000_000),
    StreamQuality("720p HD · 60 FPS", 1280, 720, 60, 4_500_000),
    StreamQuality("720p HD · 30 FPS", 1280, 720, 30, 3_000_000),
    StreamQuality("480p Data Saver · 30 FPS", 854, 480, 30, 1_500_000)
)

@Composable
fun StreamForgeApp(
    onStartStream: (String, Int, Int, Int, Int, String) -> Unit,
    onStopStream: () -> Unit,
    onStartRecording: (Int, Int, Int, Int) -> Unit,
    onStopRecording: () -> Unit,
    onPreparePreview: (Int, Int, Int, Int, String) -> Unit,
    onStopStudioPreview: () -> Unit
 ) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("streamforge_studio", android.content.Context.MODE_PRIVATE) }
    var platform by remember { mutableStateOf("YouTube") }
    var server by remember { mutableStateOf("rtmps://a.rtmp.youtube.com/live2") }
    var streamKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var selectedQuality by remember { mutableStateOf(qualityOptions.getOrElse(prefs.getInt("quality_index", 5)) { qualityOptions[5] }) }
    var captureMode by remember { mutableStateOf("GAME") }
    var showLiveDashboard by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    var colorBrightness by remember { mutableFloatStateOf(prefs.getFloat("color_brightness", 1f)) }
    var colorContrast by remember { mutableFloatStateOf(prefs.getFloat("color_contrast", 1f)) }
    var colorSaturation by remember { mutableFloatStateOf(prefs.getFloat("color_saturation", 1f)) }
    var colorSharpness by remember { mutableFloatStateOf(prefs.getFloat("color_sharpness", 0f)) }
    var targetBitrateMbps by remember { mutableFloatStateOf(prefs.getFloat("video_bitrate_mbps", qualityOptions[5].bitrate / 1_000_000f).coerceIn(0.8f, 35f)) }
    var adaptiveBitrate by remember { mutableStateOf(prefs.getBoolean("adaptive_bitrate", true)) }
    var audioBitrateKbps by remember { mutableFloatStateOf(prefs.getInt("audio_bitrate_kbps", 128).toFloat()) }
    var audioEnhancements by remember { mutableStateOf(prefs.getBoolean("audio_enhancements", true)) }
    var microphoneGain by remember { mutableFloatStateOf(prefs.getFloat("audio_mic_gain", 1f).coerceIn(0f, 2f)) }
    var gameGain by remember { mutableFloatStateOf(prefs.getFloat("audio_game_gain", 1f).coerceIn(0f, 2f)) }
    var keepScreenAwake by remember { mutableStateOf(prefs.getBoolean("keep_screen_awake", true)) }
    var showRecordingConfirm by remember { mutableStateOf(false) }
    var recordingElapsedSeconds by remember { mutableLongStateOf(0L) }
    var destinationExpanded by remember { mutableStateOf(false) }
    var audioExpanded by remember { mutableStateOf(false) }
    var qualityExpanded by remember { mutableStateOf(false) }
    var colorExpanded by remember { mutableStateOf(false) }
    val status by StreamService.status.collectAsState()
    val isStreaming by StreamService.streaming.collectAsState()
    val isRecording by StreamService.recording.collectAsState()
    val studioPreviewReady by StreamService.studioPreviewReady.collectAsState()
    val bitrate by StreamService.bitrateKbps.collectAsState()
    val liveResolution by StreamService.streamResolution.collectAsState()
    val liveFps by StreamService.streamFps.collectAsState()
    val startedAt by StreamService.streamStartedAt.collectAsState()
    val networkHealth by StreamService.networkHealth.collectAsState()
    val microphoneMuted by StreamService.microphoneMuted.collectAsState()
    val recordingStartedAt by StreamService.recordingStartedAt.collectAsState()

    LaunchedEffect(isRecording, recordingStartedAt) {
        if (isRecording && recordingStartedAt > 0L) {
            while (isRecording) {
                recordingElapsedSeconds = ((System.currentTimeMillis() - recordingStartedAt) / 1000L).coerceAtLeast(0L)
                delay(1000)
            }
        } else recordingElapsedSeconds = 0L
    }

    LaunchedEffect(isStreaming, startedAt) {
        if (isStreaming && startedAt > 0L) {
            while (isStreaming) {
                elapsedSeconds = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L)
                delay(1000)
            }
        } else elapsedSeconds = 0L
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Background, surface = Panel)) {
        Surface(Modifier.fillMaxSize(), color = Background) {
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().systemBarsPadding().imePadding()
                        .verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
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
                            isStreaming = isStreaming,
                            onPreparePreview = { onPreparePreview(selectedQuality.width, selectedQuality.height, selectedQuality.fps, (targetBitrateMbps * 1_000_000f).toInt(), captureMode) },
                            onStopStudioPreview = onStopStudioPreview,
                            onApplyScene = { sceneIndex ->
                                if (StreamService.streaming.value || StreamService.studioPreviewReady.value) {
                                    context.startService(Intent(context, StreamService::class.java).apply {
                                        action = StreamService.ACTION_APPLY_SCENE
                                        putExtra(StreamService.EXTRA_SCENE_INDEX, sceneIndex)
                                    })
                                }
                            }
                        )
                        3 -> RecordingLibrary()
                        5 -> AiAssistantPanel()
                        6 -> ChatIntegrationPanel()
                        8 -> MoreToolsPanel(onNavigate = { selectedTab = it })
                        4 -> {
                        SettingsPanel(
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
                            if (isStreaming) {
                                if (showLiveDashboard) LiveDashboard(
                                    elapsed = elapsedSeconds, bitrate = bitrate, resolution = liveResolution,
                                    fps = liveFps, networkHealth = networkHealth,
                                    onHide = { showLiveDashboard = false }, onStop = onStopStream
                                ) else Surface(color = Color(0xFF251720), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                    Row(Modifier.fillMaxWidth().clickable { showLiveDashboard = true }.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                                        LiveDot()
                                        Text("LIVE  ·  ${formatDuration(elapsedSeconds)}", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                        Text("VIEW STATS  ↗", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("PREVIEW", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                TextButton(onClick = { selectedTab = 2 }, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) {
                                    Text("SCENES  ↗", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            StudioPreviewSurface(
                                previewReady = studioPreviewReady,
                                isStreaming = isStreaming,
                                onPrepare = { onPreparePreview(selectedQuality.width, selectedQuality.height, selectedQuality.fps, (targetBitrateMbps * 1_000_000f).toInt(), captureMode) },
                                onStop = onStopStudioPreview
                            )
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
                                            Text("SOURCE & LIVE", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                }
                                        Surface(color = PanelRaised, shape = RoundedCornerShape(14.dp)) {
                                            Text("▣", Modifier.padding(15.dp), color = Accent, fontSize = 26.sp)
                                        }
                                    }
                                    Text("SOURCE", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        CaptureModeCard("APP", "Single app.", captureMode == "GAME", Modifier.weight(1f)) { captureMode = "GAME" }
                                        CaptureModeCard("DISPLAY", "Entire screen.", captureMode == "FULL", Modifier.weight(1f)) { captureMode = "FULL" }
                                    }
                                    if (captureMode == "GAME" && android.os.Build.VERSION.SDK_INT < 34) {
                                        Text("This Android version uses full-screen capture; app-only selection needs Android 14 or newer.", color = Color(0xFFFFC46B), fontSize = 10.sp)
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
                                            colors = ButtonDefaults.buttonColors(containerColor = if (isStreaming) Color(0xFF542332) else Accent, contentColor = if (isStreaming) Color.White else Color(0xFF101827)),
                                            shape = RoundedCornerShape(14.dp)
                                        ) { Text(if (isStreaming) "■  END LIVE" else "●  GO LIVE", fontWeight = FontWeight.Black, fontSize = 13.sp) }
                                        OutlinedButton(
                                            onClick = { if (isRecording) onStopRecording() else showRecordingConfirm = true },
                                            modifier = Modifier.weight(1f).height(52.dp),
                                            shape = RoundedCornerShape(14.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (isRecording) LiveRed else Color.White)
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
                NavigationBar(containerColor = Panel, contentColor = Accent, tonalElevation = 0.dp) {
                    val destinations = listOf(
                        Triple(0, "Studio", "●"),
                        Triple(2, "Scenes", "▦"),
                        Triple(8, "More", "•••")
                    )
                    destinations.forEach { (tab, label, icon) ->
                        NavigationBarItem(
                            selected = if (tab == 8) selectedTab == 8 || selectedTab in 3..6 else selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = { Text(icon, fontSize = 17.sp) },
                            label = { Text(label, fontSize = 10.sp, maxLines = 1) },
                            alwaysShowLabel = true
                        )
                    }
                }
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
                    titleContentColor = Color.White,
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
    onStop: () -> Unit
) {
    Surface(color = Color.Black, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Box {
            AndroidView(
                factory = { viewContext ->
                    TextureView(viewContext).also { view ->
                        StreamService.previewView = view
                        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                                if (StreamService.streaming.value || StreamService.studioPreviewReady.value) {
                                    viewContext.startService(Intent(viewContext, StreamService::class.java).setAction(StreamService.ACTION_PREVIEW_SURFACE_READY))
                                }
                            }
                            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                if (StreamService.streaming.value || StreamService.studioPreviewReady.value) {
                                    viewContext.startService(Intent(viewContext, StreamService::class.java).setAction(StreamService.ACTION_STOP_PREVIEW))
                                }
                                StreamService.previewView = null
                                return true
                            }
                        }
                    }
                },
                update = { StreamService.previewView = it },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            )
            if (!previewReady && !isStreaming) {
                Surface(color = Color(0xDD101722), shape = RoundedCornerShape(12.dp), modifier = Modifier.align(Alignment.Center)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("PREVIEW OFF", color = Color.White, fontWeight = FontWeight.Black, fontSize = 10.sp)
                        Button(onClick = onPrepare, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp), modifier = Modifier.height(34.dp)) {
                            Text("START PREVIEW", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                Surface(color = Color(0xCC111923), shape = RoundedCornerShape(7.dp), modifier = Modifier.align(Alignment.TopStart).padding(7.dp)) {
                    Text(if (isStreaming) "● LIVE" else "● PREVIEW", color = if (isStreaming) LiveRed else Good, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), fontSize = 9.sp, fontWeight = FontWeight.Black)
                }
                if (previewReady && !isStreaming) {
                    TextButton(onClick = onStop, modifier = Modifier.align(Alignment.TopEnd), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("STOP PREVIEW", color = Color.White, fontSize = 9.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingDashboard(elapsed: Long, resolution: String, onStop: () -> Unit) {
    Surface(color = Color(0xFF301A20), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(color = LiveRed, shape = CircleShape, modifier = Modifier.size(9.dp)) {}
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("RECORDING  ·  ${formatDuration(elapsed)}", color = Color.White, fontWeight = FontWeight.Black, fontSize = 13.sp)
                Text("MP4 · $resolution · saved locally", color = Muted, fontSize = 10.sp)
            }
            TextButton(onClick = onStop) { Text("STOP", color = LiveRed, fontWeight = FontWeight.Black) }
        }
    }
}

@Composable
private fun StudioHeader(isStreaming: Boolean, isRecording: Boolean, onSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painter = painterResource(id = app.streamforge.R.drawable.ic_streamforge), contentDescription = "StreamForge logo", modifier = Modifier.size(34.dp).padding(end = 7.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("STREAMFORGE", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (isStreaming) LiveDot() else Surface(color = Good, shape = CircleShape, modifier = Modifier.size(6.dp)) {}
                Text(if (isStreaming) "ON AIR" else if (isRecording) "RECORDING" else "STANDBY", color = if (isStreaming) LiveRed else if (isRecording) Color(0xFFFFB86B) else Muted, fontSize = 9.sp, fontWeight = FontWeight.Black)
            }
            TextButton(onClick = onSettings, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("SETUP  ⚙", color = Accent, fontSize = 9.sp) }
        }
    }
}

@Composable
private fun SectionHeading(kicker: String, title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(kicker, color = Accent, fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = 1.1.sp)
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusLine(isStreaming: Boolean, status: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(color = if (isStreaming) Color(0xFF3C1B28) else Color(0xFF202D3B), shape = RoundedCornerShape(50)) {
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
                    Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            if (subtitle.isNotBlank()) Text(subtitle, color = Muted, fontSize = 8.sp, maxLines = 1)
        }
    }
}

@Composable
private fun SettingsPanel(
    quality: StreamQuality, onQuality: (StreamQuality) -> Unit,
    bitrateMbps: Float, onBitrate: (Float) -> Unit,
    adaptiveBitrate: Boolean, onAdaptiveBitrate: (Boolean) -> Unit,
    audioBitrateKbps: Float, onAudioBitrate: (Float) -> Unit,
    audioEnhancements: Boolean, onAudioEnhancements: (Boolean) -> Unit,
    keepScreenAwake: Boolean, onKeepScreenAwake: (Boolean) -> Unit,
    brightness: Float, contrast: Float, saturation: Float, sharpness: Float,
    onColor: (Float, Float, Float, Float) -> Unit
) {
    SectionHeading("OUTPUT", "Video & encoder settings")
    Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Resolution & frame rate", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            QualityPicker(quality, onQuality)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoTile("REQUESTED OUTPUT", "${quality.width}×${quality.height}", Modifier.weight(1f))
                InfoTile("FRAME RATE", "${quality.fps} FPS", Modifier.weight(1f))
            }
            Text("Target video bitrate · ${"%.1f".format(bitrateMbps)} Mbps", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Slider(value = bitrateMbps, onValueChange = onBitrate, valueRange = 0.8f..35f, steps = 169)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Adaptive bitrate", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                Text("Live output colour controls", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
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
            Text("Audio bitrate · ${audioBitrateKbps.toInt()} kbps", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Slider(value = audioBitrateKbps, onValueChange = onAudioBitrate, valueRange = 64f..320f, steps = 11)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mic noise suppression & echo cancellation", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("Applied when the audio encoder starts.", color = Muted, fontSize = 10.sp)
                }
                Switch(checked = audioEnhancements, onCheckedChange = onAudioEnhancements)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep screen awake", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
        Text("AUDIO", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(if (isStreaming) "LIVE" else "MIX", color = if (isStreaming) LiveRed else Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text(if (expanded) "−" else "+", color = Accent, fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp))
    }
    if (expanded) Surface(color = Panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MIC", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${(microphoneGain * 100).toInt()}%", color = Accent, fontSize = 10.sp)
            }
            Slider(value = microphoneGain, onValueChange = onMicrophoneGain, valueRange = 0f..2f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("GAME / APP", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${(gameGain * 100).toInt()}%", color = Accent, fontSize = 10.sp)
            }
            Slider(value = gameGain, onValueChange = onGameGain, valueRange = 0f..2f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Noise cancellation", color = Color.White, fontSize = 10.sp, modifier = Modifier.weight(1f))
                Switch(checked = noiseCancellation, onCheckedChange = onNoiseCancellation)
            }
        }
    }
}

@Composable
private fun ColourSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text("$label  ${(value * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
    Slider(value = value, onValueChange = onChange, valueRange = range)
}

@Composable
private fun LiveDashboard(
    elapsed: Long, bitrate: Long, resolution: String, fps: Int, networkHealth: String,
    onHide: () -> Unit, onStop: () -> Unit
) {
    Surface(color = Color(0xFF24151D), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = LiveRed, shape = RoundedCornerShape(50)) {
                    Text("● LIVE", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(9.dp))
                Text(formatDuration(elapsed), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onHide) { Text("HIDE", color = Muted, fontSize = 10.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                LiveMetric("BITRATE", if (bitrate > 0) "$bitrate kbps" else "Starting…", Modifier.weight(1f))
                LiveMetric("OUTPUT", resolution, Modifier.weight(1f))
                LiveMetric("FPS", "$fps", Modifier.weight(1f))
            }
            Text(networkHealth, color = if (networkHealth.contains("congested", ignoreCase = true)) Color(0xFFFFC46B) else Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("END BROADCAST") }
        }
    }
}

@Composable
private fun LiveMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = Color(0xFF351E29), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(9.dp)) {
            Text(label, color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Text(value, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
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
            Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
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
        color = if (selected) Color(0xFF1B3150) else Panel,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) Accent else Color(0xFF2A3748))
    ) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (selected) "●  $title" else "○  $title", color = if (selected) Accent else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black)
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
            QuickTile("☷", "Live Chat", "YouTube chat setup", Modifier.weight(1f)) { onNavigate(6) }
        }
        Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("STREAMFORGE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.1.sp)
                Text("Direct-to-platform streaming · Android-native capture · Local MP4 recordings", color = Muted, fontSize = 10.sp)
                Text("Your stream key stays on this device. Platform chat and AI integrations require their own connection.", color = Muted, fontSize = 10.sp)
            }
        }
    }
}
