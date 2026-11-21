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
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit
) {
    var platform by remember { mutableStateOf("YouTube") }
    var server by remember { mutableStateOf("rtmps://a.rtmp.youtube.com/live2") }
    var streamKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var selectedQuality by remember { mutableStateOf(qualityOptions[5]) }
    var captureMode by remember { mutableStateOf("GAME") }
    var showLiveDashboard by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    var colorBrightness by remember { mutableFloatStateOf(1f) }
    var colorContrast by remember { mutableFloatStateOf(1f) }
    var colorSaturation by remember { mutableFloatStateOf(1f) }
    var colorSharpness by remember { mutableFloatStateOf(0f) }
    var destinationExpanded by remember { mutableStateOf(true) }
    var qualityExpanded by remember { mutableStateOf(false) }
    var colorExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val status by StreamService.status.collectAsState()
    val isStreaming by StreamService.streaming.collectAsState()
    val isRecording by StreamService.recording.collectAsState()
    val bitrate by StreamService.bitrateKbps.collectAsState()
    val liveResolution by StreamService.streamResolution.collectAsState()
    val liveFps by StreamService.streamFps.collectAsState()
    val startedAt by StreamService.streamStartedAt.collectAsState()
    val networkHealth by StreamService.networkHealth.collectAsState()
    val microphoneMuted by StreamService.microphoneMuted.collectAsState()

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
                        .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(15.dp)
                ) {
                    StudioHeader(isStreaming, isRecording, onSettings = { selectedTab = 4 })

                    when (selectedTab) {
                        1 -> {
                            SectionHeading("MONITOR", "Live output preview")
                            Surface(color = Color.Black, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                                AndroidView(
                                    factory = { viewContext ->
                                        TextureView(viewContext).also { view ->
                                            StreamService.previewView = view
                                            view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                                                    if (StreamService.streaming.value) {
                                                        viewContext.startService(Intent(viewContext, StreamService::class.java).setAction(StreamService.ACTION_PREVIEW_SURFACE_READY))
                                                    }
                                                }
                                                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                                                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                                                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                                    if (StreamService.streaming.value) viewContext.startService(Intent(viewContext, StreamService::class.java).setAction(StreamService.ACTION_STOP_PREVIEW))
                                                    StreamService.previewView = null
                                                    return true
                                                }
                                            }
                                        }
                                    },
                                    update = { StreamService.previewView = it },
                                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                                )
                            }
                            Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    StatusLine(isStreaming, status)
                                    Text(if (isStreaming) "Preview is connected to the active encoder output." else "Start a broadcast from Studio. Your captured screen preview will appear here.", color = Muted, fontSize = 12.sp)
                                    PrimaryAction("Back to Studio") { selectedTab = 0 }
                                }
                            }
                        }
                        2 -> SceneStudio(onApplyScene = { sceneIndex ->
                            if (StreamService.streaming.value) {
                                context.startService(Intent(context, StreamService::class.java).apply {
                                    action = StreamService.ACTION_APPLY_SCENE
                                    putExtra(StreamService.EXTRA_SCENE_INDEX, sceneIndex)
                                })
                            }
                        })
                        3 -> RecordingLibrary()
                        5 -> AiAssistantPanel()
                        4 -> {
                        SettingsPanel(
                            quality = selectedQuality,
                            onQuality = { selectedQuality = it },
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

                            SectionHeading("BROADCAST", "Your control room")
                            Surface(color = Panel, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("SCREEN SOURCE", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                                            Text("Entire screen or one app", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                            Text("Choose the capture target in Android's system prompt.", color = Muted, fontSize = 11.sp)
                                        }
                                        Surface(color = PanelRaised, shape = RoundedCornerShape(14.dp)) {
                                            Text("▣", Modifier.padding(15.dp), color = Accent, fontSize = 26.sp)
                                        }
                                    }
                                    Text("CAPTURE SOURCE", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        CaptureModeCard("GAME / APP", "Capture one selected app. Best for clean floating controls.", captureMode == "GAME", Modifier.weight(1f)) { captureMode = "GAME" }
                                        CaptureModeCard("FULL SCREEN", "Capture everything on your display.", captureMode == "FULL", Modifier.weight(1f)) { captureMode = "FULL" }
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
                                                        onStartStream("$cleanServer/$cleanKey", selectedQuality.width, selectedQuality.height, selectedQuality.fps, selectedQuality.bitrate, captureMode)
                                                    }
                                                }
                                            },
                                            modifier = Modifier.weight(1.25f).height(52.dp),
                                            enabled = isStreaming || (!isRecording && streamKey.isNotBlank() && (server.startsWith("rtmp://") || server.startsWith("rtmps://"))),
                                            colors = ButtonDefaults.buttonColors(containerColor = if (isStreaming) Color(0xFF542332) else Accent, contentColor = if (isStreaming) Color.White else Color(0xFF101827)),
                                            shape = RoundedCornerShape(14.dp)
                                        ) { Text(if (isStreaming) "■  END LIVE" else "●  GO LIVE", fontWeight = FontWeight.Black, fontSize = 13.sp) }
                                        OutlinedButton(
                                            onClick = { if (isRecording) onStopRecording() else onStartRecording() },
                                            modifier = Modifier.weight(1f).height(52.dp),
                                            shape = RoundedCornerShape(14.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (isRecording) LiveRed else Color.White)
                                        ) { Text(if (isRecording) "■  STOP REC" else "◉  RECORD", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                                    }
                                    if (isRecording) Text("Local MP4 recording is active.", color = LiveRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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

                            SectionHeading("STREAM SETUP", "Destination & output")
                            StudioAccordion("01", "Destination", platform, destinationExpanded, { destinationExpanded = !destinationExpanded }) {
                                PlatformPicker(platform) { selected ->
                                    platform = selected
                                    server = when (selected) {
                                        "YouTube" -> "rtmps://a.rtmp.youtube.com/live2"
                                        "Kick" -> "rtmps://fa723fc1b171.global-contribute.live-video.net/app"
                                        "Twitch" -> "rtmps://live.twitch.tv/app"
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
                                QualityPicker(selectedQuality) { selectedQuality = it }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    InfoTile("OUTPUT", "${selectedQuality.width}×${selectedQuality.height}", Modifier.weight(1f))
                                    InfoTile("FRAME RATE", "${selectedQuality.fps} FPS", Modifier.weight(1f))
                                    InfoTile("BITRATE", "${selectedQuality.bitrate / 1_000_000.0} Mbps", Modifier.weight(1f))
                                }
                                Text("4K requires a capable encoder and strong, stable upload speed. Adaptive bitrate responds to network congestion.", color = Muted, fontSize = 10.sp)
                            }
                            SectionHeading("QUICK ACCESS", "Your production workspace")
                            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                QuickTile("▣", "Preview", "Monitor output", Modifier.weight(1f)) { selectedTab = 1 }
                                QuickTile("▦", "Scenes", "Arrange layers", Modifier.weight(1f)) { selectedTab = 2 }
                                QuickTile("▤", "Recordings", "Review MP4s", Modifier.weight(1f)) { selectedTab = 3 }
                            }
                            Text("StreamForge · Direct-to-platform streaming · No relay server", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 5.dp))
                        }
                    }
                }
                NavigationBar(containerColor = Panel, contentColor = Accent, tonalElevation = 0.dp) {
                    val labels = listOf("Studio", "Preview", "Scenes", "Recordings", "Settings", "AI")
                    val icons = listOf("●", "▣", "▦", "▤", "⚙", "✦")
                    labels.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = selectedTab == index, onClick = { selectedTab = index },
                            icon = { Text(icons[index], fontSize = 17.sp) },
                            label = { Text(label, fontSize = 9.sp, maxLines = 1) },
                            alwaysShowLabel = true
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioHeader(isStreaming: Boolean, isRecording: Boolean, onSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painter = painterResource(id = app.streamforge.R.drawable.ic_streamforge), contentDescription = "StreamForge logo", modifier = Modifier.size(42.dp).padding(end = 9.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("STREAMFORGE", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
            Text("CREATOR STUDIO  /  ANDROID", color = Muted, fontSize = 9.sp, letterSpacing = 1.2.sp)
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
        Text(kicker, color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.6.sp)
        Text(title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
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
    Surface(color = Panel, shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
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
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(icon, color = Accent, fontSize = 21.sp)
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(subtitle, color = Muted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun SettingsPanel(
    quality: StreamQuality, onQuality: (StreamQuality) -> Unit,
    brightness: Float, contrast: Float, saturation: Float, sharpness: Float,
    onColor: (Float, Float, Float, Float) -> Unit
) {
    SectionHeading("OUTPUT", "Video settings")
    Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Resolution & frame rate", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            QualityPicker(quality, onQuality)
            Text("Choose a preset that matches your device encoder and upload connection.", color = Muted, fontSize = 10.sp)
        }
    }
    SectionHeading("IMAGE", "Colour tuning")
    Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Adjust outgoing stream image", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { onColor(1f, 1f, 1f, 0f) }) { Text("RESET") }
            }
            ColourSlider("Brightness", brightness, 0.8f..1.35f) { onColor(it, contrast, saturation, sharpness) }
            ColourSlider("Contrast", contrast, 0.8f..1.4f) { onColor(brightness, it, saturation, sharpness) }
            ColourSlider("Vibrance", saturation, 0f..2f) { onColor(brightness, contrast, it, sharpness) }
            ColourSlider("Sharpness", sharpness, 0f..0.8f) { onColor(brightness, contrast, saturation, it) }
        }
    }
    Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("StreamForge system", color = Color.White, fontWeight = FontWeight.Bold)
            Text("Direct RTMP / RTMPS output. Your stream key remains stored only in the active app session.", color = Muted, fontSize = 11.sp)
            Text("4K availability depends on Android encoder support, device thermals and network upload speed.", color = Muted, fontSize = 11.sp)
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
            listOf("YouTube", "Kick", "Twitch", "Custom RTMP").forEach { name ->
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
