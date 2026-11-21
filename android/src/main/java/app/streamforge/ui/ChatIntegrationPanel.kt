package app.streamforge.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
    var apiKey by remember { mutableStateOf(prefs.getString("youtube_api_key", "").orEmpty()) }
    var liveChatId by remember { mutableStateOf(prefs.getString("youtube_live_chat_id", "").orEmpty()) }
    val state by YouTubeLiveChat.connectionState.collectAsState()
    val messages by YouTubeLiveChat.messages.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("LIVE CHAT", color = ChatAccent, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
        Text("Connect platform chat to your floating LIVE controls.", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Surface(color = ChatBg, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("YouTube Live", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Uses the official YouTube Data API v3. Create an API key in Google Cloud and enable YouTube Data API v3. Paste the active broadcast's liveChatId below.", color = ChatMuted, fontSize = 11.sp)
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("YouTube Data API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(
                    value = liveChatId,
                    onValueChange = { liveChatId = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Live chat ID") },
                    singleLine = true
                )
                Text(state, color = if (state == "YouTube chat connected") Color(0xFF55D6A1) else ChatMuted, fontSize = 11.sp)
                Button(
                    onClick = {
                        prefs.edit()
                            .putString("youtube_api_key", apiKey.trim())
                            .putString("youtube_live_chat_id", liveChatId.trim())
                            .apply()
                        YouTubeLiveChat.stop()
                        YouTubeLiveChat.clear()
                        if (StreamService.streaming.value) YouTubeLiveChat.start(context)
                    },
                    enabled = apiKey.isNotBlank() && liveChatId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ChatAccent, contentColor = Color(0xFF101827))
                ) { Text("SAVE & CONNECT ON NEXT LIVE", fontWeight = FontWeight.Black) }
                Text("The chat reader starts automatically when a broadcast connects. Your API key is stored only in this app's private preferences.", color = ChatMuted, fontSize = 10.sp)
            }
        }

        Surface(color = ChatBg, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Other platforms", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Twitch · OAuth + IRC", color = ChatMuted, fontSize = 12.sp)
                Text("Kick · authenticated chat connection", color = ChatMuted, fontSize = 12.sp)
                Text("Facebook Live · Graph API permissions", color = ChatMuted, fontSize = 12.sp)
                Text("TikTok LIVE · approved LIVE access / integration", color = ChatMuted, fontSize = 12.sp)
                Text("These are not marked connected yet. Each needs its own authorized account flow; an RTMP stream key cannot read chat.", color = Color(0xFFFFC46B), fontSize = 11.sp)
            }
        }

        Surface(color = ChatBg, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Recent messages · ${messages.size}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (messages.isEmpty()) {
                    Text("No messages received yet. Start a YouTube LIVE after saving valid credentials.", color = ChatMuted, fontSize = 11.sp)
                } else {
                    messages.takeLast(12).asReversed().forEach { message ->
                        Text(message, color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
