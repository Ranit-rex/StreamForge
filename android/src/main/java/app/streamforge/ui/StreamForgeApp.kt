package app.streamforge.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.alpha
import android.view.TextureView
import android.graphics.SurfaceTexture
import android.content.Intent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.streamforge.stream.StreamService
import kotlinx.coroutines.delay

private val Background = Color(0xFF0B0E13)
private val Panel = Color(0xFF151B25)
private val Accent = Color(0xFF8EA5FF)
private val Muted = Color(0xFF9AA6B8)
private val LiveRed = Color(0xFFFF536B)

private data class StreamQuality(
    val label: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int
)

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
    onStartStream: (String, Int, Int, Int, Int) -> Unit,
    onStopStream: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit
) {
    var platform by remember { mutableStateOf("YouTube") }
    var server by remember { mutableStateOf("rtmps://a.rtmp.youtube.com/live2") }
    var streamKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var selectedQuality by remember { mutableStateOf(qualityOptions[5]) }
    var showLiveDashboard by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    var colorBrightness by remember { mutableFloatStateOf(1.0f) }
    var colorContrast by remember { mutableFloatStateOf(1.0f) }
    var colorSaturation by remember { mutableFloatStateOf(1.0f) }
    var colorSharpness by remember { mutableFloatStateOf(0.0f) }
    val context = LocalContext.current

    val status by StreamService.status.collectAsState()
    val isStreaming by StreamService.streaming.collectAsState()
    val isRecording by StreamService.recording.collectAsState()
    val bitrate by StreamService.bitrateKbps.collectAsState()
    val liveResolution by StreamService.streamResolution.collectAsState()
    val liveFps by StreamService.streamFps.collectAsState()
    val startedAt by StreamService.streamStartedAt.collectAsState()
    val networkHealth by StreamService.networkHealth.collectAsState()

    LaunchedEffect(isStreaming, startedAt) {
        if (isStreaming && startedAt > 0L) {
            showLiveDashboard = true
            while (isStreaming) {
                elapsedSeconds = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L)
                delay(1000)
            }
        } else {
            elapsedSeconds = 0L
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Background, surface = Panel)) {
        Surface(Modifier.fillMaxSize(), color = Background) {
            Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .systemBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = app.streamforge.R.drawable.ic_streamforge),
                        contentDescription = "StreamForge logo",
                        modifier = Modifier.size(46.dp).padding(end = 10.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text("STREAMFORGE", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                        Text("MOBILE BROADCAST STUDIO", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
                    }
                    Surface(color = Color(0xFF1E2938), shape = RoundedCornerShape(50)) {
                        Text("ANDROID", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (selectedTab == 1) {
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
                                        if (StreamService.streaming.value) {
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
                }

                if (selectedTab == 1) {
                    Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("LIVE OUTPUT PREVIEW", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(if (isStreaming) "This is the local encoder preview of the frames being sent to your stream." else "Preview surface is ready. Start a broadcast from Studio to see the captured screen here.", color = Muted, fontSize = 12.sp)
                            Text(if (isStreaming) "● STREAMING" else "● WAITING FOR BROADCAST", color = if (isStreaming) LiveRed else Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            OutlinedButton(onClick = { selectedTab = 0 }, modifier = Modifier.fillMaxWidth()) { Text("Back to Studio") }
                        }
                    }
                } else if (selectedTab == 2) {
                    SceneStudio(onApplyScene = { sceneIndex ->
                        if (StreamService.streaming.value) {
                            context.startService(Intent(context, StreamService::class.java).apply {
                                action = StreamService.ACTION_APPLY_SCENE
                                putExtra(StreamService.EXTRA_SCENE_INDEX, sceneIndex)
                            })
                        }
                    })
                } else if (selectedTab == 3) {
                    RecordingLibrary()
                } else {
                if (isStreaming && showLiveDashboard) {
                    LiveDashboard(
                        elapsed = elapsedSeconds,
                        bitrate = bitrate,
                        resolution = liveResolution,
                        fps = liveFps,
                        networkHealth = networkHealth,
                        onHide = { showLiveDashboard = false },
                        onStop = onStopStream
                    )
                } else if (isStreaming) {
                    OutlinedButton(onClick = { showLiveDashboard = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("● LIVE  ·  Open live dashboard  ·  ${formatDuration(elapsedSeconds)}")
                    }
                }

                Surface(color = Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("▣", color = Accent, fontSize = 36.sp)
                        Text("Screen broadcast", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Choose your entire screen or a single app in Android’s screen-sharing prompt, then broadcast up to 4K.", color = Muted, fontSize = 12.sp)
                        Text("SOURCE · FULL SCREEN / SINGLE APP", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Surface(color = if (isStreaming) Color(0xFF3B1720) else Color(0xFF202A38), shape = RoundedCornerShape(50)) {
                            Text(status, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = if (isStreaming) LiveRed else Color(0xFFB7C5D9), fontSize = 12.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    if (isStreaming) onStopStream()
                                    else {
                                        val cleanServer = server.trim().trimEnd('/')
                                        val cleanKey = streamKey.trim().trimStart('/')
                                        if ((cleanServer.startsWith("rtmp://") || cleanServer.startsWith("rtmps://")) && cleanKey.isNotBlank()) {
                                            onStartStream("$cleanServer/$cleanKey", selectedQuality.width, selectedQuality.height, selectedQuality.fps, selectedQuality.bitrate)
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = isStreaming || (!isRecording && streamKey.isNotBlank() && (server.startsWith("rtmp://") || server.startsWith("rtmps://")))
                            ) { Text(if (isStreaming) "Stop live" else "Choose screen & Go Live") }
                            OutlinedButton(
                                onClick = { if (isRecording) onStopRecording() else onStartRecording() },
                                modifier = Modifier.weight(1f),
                                enabled = true
                            ) { Text(if (isRecording) "Stop recording" else if (isStreaming) "Record" else "Record screen") }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("DESTINATION", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
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
                        value = streamKey, onValueChange = { streamKey = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("Stream key") }, singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } }
                    )
                    Text("Your stream key stays on this device and is never sent to StreamForge servers.", color = Muted, fontSize = 11.sp)
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("STREAM QUALITY", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    QualityPicker(selectedQuality) { selectedQuality = it }
                    Text("Adaptive bitrate lowers video data automatically when the upload queue gets congested, then cautiously recovers after the connection stabilises. 4K still needs strong, consistent upload speed and a capable hardware encoder.", color = Muted, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        InfoTile("Resolution", selectedQuality.label.substringBefore(" · "), Modifier.weight(1f))
                        InfoTile("Frame rate", "${selectedQuality.fps} FPS", Modifier.weight(1f))
                        InfoTile("Target bitrate", "${selectedQuality.bitrate / 1_000_000.0} Mbps", Modifier.weight(1f))
                    }
                }


                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("STREAM COLOUR", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                            Text("Vivid live colour tuning · applies to the outgoing stream", color = Muted, fontSize = 10.sp)
                        }
                        TextButton(onClick = {
                            colorBrightness = 1.0f; colorContrast = 1.0f; colorSaturation = 1.0f; colorSharpness = 0.0f
                            context.startService(Intent(context, StreamService::class.java).apply {
                                action = StreamService.ACTION_UPDATE_COLOR
                                putExtra(StreamService.EXTRA_COLOR_BRIGHTNESS, colorBrightness)
                                putExtra(StreamService.EXTRA_COLOR_CONTRAST, colorContrast)
                                putExtra(StreamService.EXTRA_COLOR_SATURATION, colorSaturation)
                                putExtra(StreamService.EXTRA_COLOR_SHARPNESS, colorSharpness)
                            })
                        }) { Text("Reset") }
                    }
                    Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Brightness  ${(colorBrightness * 100).toInt()}%", color = Color.White, fontSize = 12.sp)
                            Slider(value = colorBrightness, onValueChange = {
                                colorBrightness = it
                                context.startService(Intent(context, StreamService::class.java).apply {
                                    action = StreamService.ACTION_UPDATE_COLOR
                                    putExtra(StreamService.EXTRA_COLOR_BRIGHTNESS, colorBrightness)
                                    putExtra(StreamService.EXTRA_COLOR_CONTRAST, colorContrast)
                                    putExtra(StreamService.EXTRA_COLOR_SATURATION, colorSaturation)
                                    putExtra(StreamService.EXTRA_COLOR_SHARPNESS, colorSharpness)
                                })
                            }, valueRange = 0.8f..1.35f)
                            Text("Contrast  ${(colorContrast * 100).toInt()}%", color = Color.White, fontSize = 12.sp)
                            Slider(value = colorContrast, onValueChange = {
                                colorContrast = it
                                context.startService(Intent(context, StreamService::class.java).apply {
                                    action = StreamService.ACTION_UPDATE_COLOR
                                    putExtra(StreamService.EXTRA_COLOR_BRIGHTNESS, colorBrightness)
                                    putExtra(StreamService.EXTRA_COLOR_CONTRAST, colorContrast)
                                    putExtra(StreamService.EXTRA_COLOR_SATURATION, colorSaturation)
                                    putExtra(StreamService.EXTRA_COLOR_SHARPNESS, colorSharpness)
                                })
                            }, valueRange = 0.8f..1.4f)
                            Text("Colour vibrance  ${(colorSaturation * 100).toInt()}%", color = Color.White, fontSize = 12.sp)
                            Slider(value = colorSaturation, onValueChange = {
                                colorSaturation = it
                                context.startService(Intent(context, StreamService::class.java).apply {
                                    action = StreamService.ACTION_UPDATE_COLOR
                                    putExtra(StreamService.EXTRA_COLOR_BRIGHTNESS, colorBrightness)
                                    putExtra(StreamService.EXTRA_COLOR_CONTRAST, colorContrast)
                                    putExtra(StreamService.EXTRA_COLOR_SATURATION, colorSaturation)
                                    putExtra(StreamService.EXTRA_COLOR_SHARPNESS, colorSharpness)
                                })
                            }, valueRange = 0f..2f)
                            Text("Sharpness  ${(colorSharpness * 100).toInt()}%", color = Color.White, fontSize = 12.sp)
                            Slider(value = colorSharpness, onValueChange = {
                                colorSharpness = it
                                context.startService(Intent(context, StreamService::class.java).apply {
                                    action = StreamService.ACTION_UPDATE_COLOR
                                    putExtra(StreamService.EXTRA_COLOR_BRIGHTNESS, colorBrightness)
                                    putExtra(StreamService.EXTRA_COLOR_CONTRAST, colorContrast)
                                    putExtra(StreamService.EXTRA_COLOR_SATURATION, colorSaturation)
                                    putExtra(StreamService.EXTRA_COLOR_SHARPNESS, colorSharpness)
                                })
                            }, valueRange = 0f..0.8f)
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("LIVE TOOLS", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Surface(color = Panel, shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("• Live dashboard with stream timer and encoder bitrate", color = Color.White, fontSize = 12.sp)
                            Text("• Record locally with or without going live · MP4", color = Color.White, fontSize = 12.sp)
                            Text("• YouTube, Twitch, Kick and custom RTMP destinations", color = Color.White, fontSize = 12.sp)
                            Text("• Floating gameplay controls with mic mute and chat panel", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
                Text("StreamForge Android • Direct-to-platform • No relay server", color = Muted, fontSize = 10.sp)
                }
            }
            NavigationBar(containerColor = Panel, contentColor = Accent) {
                listOf("Studio", "Preview", "Scenes", "Recordings").forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Text(listOf("●", "▣", "▦", "▤")[index], fontSize = 18.sp) },
                        label = { Text(label, fontSize = 10.sp) },
                        alwaysShowLabel = true
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun LiveDashboard(
    elapsed: Long,
    bitrate: Long,
    resolution: String,
    fps: Int,
    networkHealth: String,
    onHide: () -> Unit,
    onStop: () -> Unit
) {
    Surface(color = Color(0xFF24151D), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = LiveRed, shape = RoundedCornerShape(50)) {
                    Text("● LIVE", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(10.dp))
                Text(formatDuration(elapsed), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onHide) { Text("Minimise", color = Muted) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveMetric("BITRATE", if (bitrate > 0) "$bitrate kbps" else "Starting…", Modifier.weight(1f))
                LiveMetric("QUALITY", resolution, Modifier.weight(1f))
                LiveMetric("FPS", "$fps", Modifier.weight(1f))
            }
            Text(networkHealth, color = if (networkHealth.contains("congested", ignoreCase = true)) Color(0xFFFFC46B) else Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text("Audience count is not exposed by a standard RTMP connection. Platform analytics / OAuth is needed for verified viewer numbers.", color = Muted, fontSize = 10.sp)
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("End stream") }
        }
    }
}

@Composable
private fun LiveMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = Color(0xFF351E29), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text(label, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, secs)
    else String.format("%02d:%02d", minutes, secs)
}

@Composable
private fun QualityPicker(selected: StreamQuality, onSelect: (StreamQuality) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected.label, Modifier.weight(1f))
            Text("▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            qualityOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = { onSelect(option); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun PlatformPicker(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected, Modifier.weight(1f))
            Text("▾")
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
    Surface(modifier, color = Panel, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, color = Muted, fontSize = 10.sp)
            Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}
