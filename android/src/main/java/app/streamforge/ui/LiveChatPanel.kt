package app.streamforge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.streamforge.stream.YouTubeLiveChat

@Composable
fun LiveChatPanel() {
    val messages by YouTubeLiveChat.messages.collectAsState()
    val state by YouTubeLiveChat.connectionState.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("LIVE CHAT", color = Color(0xFF9DB4FF), fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
        Text("Viewer messages", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Surface(color = Color(0xFF121923), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state, color = Color(0xFF91A0B4), fontSize = 11.sp)
                Text("Connect or change your platform in More → Chat Connect.", color = Color(0xFF91A0B4), fontSize = 10.sp)
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
