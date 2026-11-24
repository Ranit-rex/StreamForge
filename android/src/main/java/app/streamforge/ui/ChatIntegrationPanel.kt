package app.streamforge.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.streamforge.stream.StreamService
import app.streamforge.stream.YouTubeLiveChat

private val ChatBg = Color(0xFF121923)
private val ChatMuted = Color(0xFF91A0B4)
private val ChatAccent = Color(0xFF9DB4FF)

@Composable
fun ChatIntegrationPanel() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("streamforge_chat", Context.MODE_PRIVATE) }
    var videoUrl by remember { mutableStateOf(prefs.getString("youtube_video_id", "").orEmpty()) }
    var apiKey by remember { mutableStateOf(YouTubeApiKeyStore.read(context)) }
    var showKey by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    val state by YouTubeLiveChat.connectionState.collectAsState()
    val messages by YouTubeLiveChat.messages.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("LIVE CHAT", color = ChatAccent, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
        Text("Bring your viewers into StreamForge.", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Surface(color = ChatBg, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("YouTube Live Chat", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Paste your public or unlisted YouTube LIVE video link. Chat is read-only inside StreamForge.", color = ChatMuted, fontSize = 11.sp)
                OutlinedTextField(value = videoUrl, onValueChange = { videoUrl = it }, modifier = Modifier.fillMaxWidth(), label = { Text("YouTube LIVE video URL or ID") }, singleLine = true)
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Your YouTube API key (optional)") }, singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "HIDE" else "SHOW") } }
                )
                Text("Optional: use your own Google Cloud API key and quota. It is encrypted on this device and sent directly to Google, never to StreamForge's server.", color = ChatMuted, fontSize = 10.sp)
                TextButton(onClick = { showGuide = !showGuide }) { Text(if (showGuide) "Hide setup guide" else "How to get your free API key  ·  Step-by-step") }
                if (showGuide) {
                    Surface(color = Color(0xFF1B2634), shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("Create your YouTube API key", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("1. Open console.cloud.google.com and sign in with your Google account.", color = ChatMuted, fontSize = 11.sp)
                            Text("2. Create/select a project, then open APIs & Services → Library.", color = ChatMuted, fontSize = 11.sp)
                            Text("3. Search for YouTube Data API v3 and tap Enable.", color = ChatMuted, fontSize = 11.sp)
                            Text("4. Open APIs & Services → Credentials → Create credentials → API key.", color = ChatMuted, fontSize = 11.sp)
                            Text("5. Open that key's restrictions. Set API restrictions to YouTube Data API v3, then Save.", color = ChatMuted, fontSize = 11.sp)
                            Text("6. Copy the key and paste it above. No OAuth client or YouTube sign-in inside StreamForge is required for public live chat.", color = ChatMuted, fontSize = 11.sp)
                            Text("Use only your own key. Never share it publicly. Google may require billing/profile setup and applies its own quota and policies.", color = Color(0xFFFFC46B), fontSize = 10.sp)
                        }
                    }
                }
                Text(state, color = if (state == "YouTube chat connected") Color(0xFF55D6A1) else ChatMuted, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            prefs.edit().putString("youtube_video_id", videoUrl.trim()).remove("youtube_active_chat_id").apply()
                            if (apiKey.isBlank()) YouTubeApiKeyStore.clear(context) else YouTubeApiKeyStore.save(context, apiKey)
                            YouTubeLiveChat.stop()
                            YouTubeLiveChat.clear()
                            if (StreamService.streaming.value) YouTubeLiveChat.start(context)
                        },
                        enabled = videoUrl.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ChatAccent, contentColor = Color(0xFF101827))
                    ) { Text("SAVE & CONNECT", fontWeight = FontWeight.Black) }
                    TextButton(onClick = {
                        YouTubeLiveChat.stop()
                        YouTubeLiveChat.clear()
                    }) { Text("STOP") }
                }
                Text("When you go LIVE, StreamForge connects automatically. If you leave the API key blank, the configured StreamForge chat service is used when available.", color = ChatMuted, fontSize = 10.sp)
            }
        }
        Surface(color = ChatBg, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Other platforms", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Twitch · OAuth + IRC  |  Kick · authenticated chat  |  Facebook / TikTok · approved integrations", color = ChatMuted, fontSize = 11.sp)
                Text("These integrations are not connected yet.", color = Color(0xFFFFC46B), fontSize = 11.sp)
            }
        }
        Surface(color = ChatBg, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Recent messages · ${messages.size}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (messages.isEmpty()) Text("No messages received yet. Start a YouTube LIVE after saving the video link.", color = ChatMuted, fontSize = 11.sp)
                else messages.takeLast(12).asReversed().forEach { Text(it, color = Color.White, fontSize = 12.sp) }
            }
        }
    }
}
