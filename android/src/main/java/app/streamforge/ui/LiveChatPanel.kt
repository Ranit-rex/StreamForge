package app.streamforge.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.streamforge.stream.YouTubeLiveChat

@Composable
fun LiveChatPanel(onConfigure: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("streamforge_chat", Context.MODE_PRIVATE) }
    val configured = remember { !prefs.getString("youtube_video_id", "").isNullOrBlank() }
    val messages by YouTubeLiveChat.messages.collectAsState()
    val state by YouTubeLiveChat.connectionState.collectAsState()
    val connected = state.equals("YouTube chat connected", ignoreCase = true)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("LIVE CHAT", color = Color(0xFF9DB4FF), fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
        Text("Viewer messages", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Surface(color = Color(0xFF121923), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (connected) "● CONNECTED" else if (configured) state else "NOT CONFIGURED", color = if (connected) Color(0xFF55D6A1) else Color(0xFFFFC46B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (!configured || !connected) {
                    Text(
                        if (configured) "Your chat details are saved. Start a stream or review the connection settings." else "Connect YouTube Live Chat to see viewer messages here.",
                        color = Color(0xFF91A0B4), fontSize = 11.sp
                    )
                    Button(
                        onClick = onConfigure,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9DB4FF), contentColor = Color(0xFF101827))
                    ) { Text(if (configured) "CONFIGURE CHAT" else "CONFIGURE CHAT · SETUP GUIDE", fontWeight = FontWeight.Black) }
                }
                if (messages.isEmpty()) Text("No live messages yet.", color = Color(0xFF91A0B4), fontSize = 12.sp)
                else messages.takeLast(40).asReversed().forEach { message ->
                    Surface(color = Color(0xFF1B2634), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(message, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
                    }
                }
            }
        }
    }
}
