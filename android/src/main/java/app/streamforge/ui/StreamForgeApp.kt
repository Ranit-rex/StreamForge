package app.streamforge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.streamforge.stream.StreamService

private val Background = Color(0xFF0B0E13)
private val Panel = Color(0xFF151B25)
private val Accent = Color(0xFF8EA5FF)
private val Muted = Color(0xFF9AA6B8)

@Composable
fun StreamForgeApp(
    onStartStream: (String) -> Unit,
    onStopStream: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit
) {
    var platform by remember { mutableStateOf("YouTube") }
    var server by remember { mutableStateOf("rtmps://a.rtmp.youtube.com/live2") }
    var streamKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    val status by StreamService.status.collectAsState()
    val isStreaming by StreamService.streaming.collectAsState()
    val isRecording by StreamService.recording.collectAsState()

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Background, surface = Panel)) {
        Surface(Modifier.fillMaxSize(), color = Background) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("STREAMFORGE", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                        Text("MOBILE BROADCAST STUDIO", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
                    }
                    Surface(color = Color(0xFF1E2938), shape = RoundedCornerShape(50)) {
                        Text("ANDROID ALPHA", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Surface(color = Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("▣", color = Accent, fontSize = 36.sp)
                        Text("Screen broadcast", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Share your Android screen directly to your streaming platform.", color = Muted, fontSize = 12.sp)
                        Surface(color = if (isStreaming) Color(0xFF3B1720) else Color(0xFF202A38), shape = RoundedCornerShape(50)) {
                            Text(status, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), color = if (isStreaming) Color(0xFFFF6B7A) else Color(0xFFB7C5D9), fontSize = 12.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    if (isStreaming) onStopStream()
                                    else {
                                        val cleanServer = server.trim().trimEnd('/')
                                        val cleanKey = streamKey.trim().trimStart('/')
                                        if ((cleanServer.startsWith("rtmp://") || cleanServer.startsWith("rtmps://")) && cleanKey.isNotBlank()) {
                                            onStartStream("$cleanServer/$cleanKey")
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = isStreaming || (streamKey.isNotBlank() && (server.startsWith("rtmp://") || server.startsWith("rtmps://")))
                            ) { Text(if (isStreaming) "Stop live" else "Go live") }
                            OutlinedButton(
                                onClick = { if (isRecording) onStopRecording() else onStartRecording() },
                                modifier = Modifier.weight(1f)
                            ) { Text(if (isRecording) "Stop recording" else "Record") }
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
                    Text("Stream key stays in this app session. It is not uploaded to StreamForge servers.", color = Muted, fontSize = 11.sp)
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("QUICK SETTINGS", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        InfoTile("Resolution", "720p", Modifier.weight(1f))
                        InfoTile("Video", "H.264", Modifier.weight(1f))
                        InfoTile("Audio", "AAC", Modifier.weight(1f))
                    }
                }
                Text("StreamForge Android alpha • Direct-to-platform • No relay server", color = Muted, fontSize = 10.sp)
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
