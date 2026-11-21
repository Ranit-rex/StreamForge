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
    val state by YouTubeLiveChat.connectionState.collectAsState()
    val messages by YouTubeLiveChat.messages.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("LIVE CHAT", color = ChatAccent, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
        Text("Connect platform chat to your floating LIVE controls.", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Surface(color = ChatBg, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("YouTube Live", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Paste your public or unlisted YouTube LIVE video link. StreamForge finds the active chat automatically. No API key needed in the app.", color = ChatMuted, fontSize = 11.sp)
                OutlinedTextField(
                    value = videoUrl,
                    onValueChange = { videoUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("YouTube LIVE video URL or ID") },
                    singleLine = true
                )
                Text(state, color = if (state == "YouTube chat connected") Color(0xFF55D6A1) else ChatMuted, fontSize = 11.sp)
                Button(
                    onClick = {
                        prefs.edit().putString("youtube_video_id", videoUrl.trim()).remove("youtube_api_key").remove("youtube_live_chat_id").apply()
                        YouTubeLiveChat.stop()
                        YouTubeLiveChat.clear()
                        if (StreamService.streaming.value) YouTubeLiveChat.start(context)
                    },
                    enabled = videoUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ChatAccent, contentColor = Color(0xFF101827))
                ) { Text("SAVE YOUTUBE LIVE", fontWeight = FontWeight.Black) }
                Text("After saving, chat connects automatically when you go LIVE. Your API credentials stay on StreamForge's secure server.", color = ChatMuted, fontSize = 10.sp)
            }
        }

        Surface(color = ChatBg, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Other platforms", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Twitch · OAuth + IRC", color = ChatMuted, fontSize = 12.sp)
                Text("Kick · authenticated chat connection", color = ChatMuted, fontSize = 12.sp)
                Text("Facebook Live · Graph API permissions", color = ChatMuted, fontSize = 12.sp)
                Text("TikTok LIVE · approved LIVE access / integration", color = ChatMuted, fontSize = 12.sp)
                Text("These platforms are not connected yet. Each requires its own approved account authorization.", color = Color(0xFFFFC46B), fontSize = 11.sp)
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
