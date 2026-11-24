package app.streamforge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

private const val VOICE_API = "https://streamforge-chat-api.onrender.com"
private val VoiceBg = Color(0xFF11151A)
private val VoiceCard = Color(0xFF1A2028)
private val VoiceMuted = Color(0xFF9AA6B2)
private val VoiceAccent = Color(0xFF5865F2)
private data class VoiceRoom(val id: String, val name: String, val visibility: String, val count: Int, val members: List<String>)

private suspend fun voiceRequest(path: String, method: String = "GET", payload: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
    val connection = URL(VOICE_API + path).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = method
        connection.connectTimeout = 10000
        connection.readTimeout = 12000
        connection.setRequestProperty("Accept", "application/json")
        if (payload != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        }
        val code = connection.responseCode
        val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = runCatching { JSONObject(body) }.getOrElse { JSONObject().put("error", "Invalid response from voice server") }
        if (code !in 200..299) throw IllegalStateException(json.optString("error").ifBlank { "Voice server error ($code)" })
        json
    } finally { connection.disconnect() }
}

private fun parseVoiceRoom(json: JSONObject): VoiceRoom {
    val room = json.optJSONObject("room") ?: JSONObject()
    val members = room.optJSONArray("members")
    val names = mutableListOf<String>()
    if (members != null) for (i in 0 until members.length()) names.add(members.optJSONObject(i)?.optString("displayName", "Guest") ?: "Guest")
    return VoiceRoom(room.optString("roomId"), room.optString("name", "Voice Room"), room.optString("visibility", "public"), room.optInt("memberCount", names.size), names)
}

@Composable
fun PartyVoicePanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("streamforge_party", 0) }
    val memberId = remember { prefs.getString("member_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("member_id", it).apply() } }
    var displayName by remember { mutableStateOf(prefs.getString("display_name", "Streamer") ?: "Streamer") }
    var roomName by remember { mutableStateOf("Gaming Party") }
    var roomIdInput by remember { mutableStateOf("") }
    var visibility by remember { mutableStateOf("public") }
    var rooms by remember { mutableStateOf<List<VoiceRoom>>(emptyList()) }
    var joined by remember { mutableStateOf<VoiceRoom?>(null) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var micEnabled by remember { mutableStateOf(false) }
    var speakerEnabled by remember { mutableStateOf(true) }

    suspend fun refreshRooms() {
        runCatching {
            val response = voiceRequest("/api/voice/rooms")
            val array = response.optJSONArray("rooms")
            val list = mutableListOf<VoiceRoom>()
            if (array != null) for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val memberNames = mutableListOf<String>()
                val members = item.optJSONArray("members")
                if (members != null) for (j in 0 until members.length()) memberNames.add(members.optJSONObject(j)?.optString("displayName", "Guest") ?: "Guest")
                list.add(VoiceRoom(item.optString("roomId"), item.optString("name"), item.optString("visibility"), item.optInt("memberCount"), memberNames))
            }
            rooms = list
        }.onFailure { error = it.message ?: "Unable to load public lobby" }
    }

    suspend fun joinRoom(id: String) {
        loading = true
        error = ""
        runCatching {
            val body = JSONObject().put("roomId", id).put("memberId", memberId).put("displayName", displayName.ifBlank { "Guest" })
            joined = parseVoiceRoom(voiceRequest("/api/voice/rooms/join", "POST", body))
            prefs.edit().putString("display_name", displayName).putString("room_id", id).apply()
            roomIdInput = ""
        }.onFailure { error = it.message ?: "Could not join room" }
        loading = false
    }

    LaunchedEffect(Unit) { refreshRooms() }
    LaunchedEffect(joined?.id) {
        val room = joined ?: return@LaunchedEffect
        while (true) {
            delay(4000)
            runCatching { joined = parseVoiceRoom(voiceRequest("/api/voice/rooms/${room.id}")) }
                .onFailure { error = it.message ?: "Room update failed" }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("PARTY VOICE", color = VoiceAccent, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
                Text("Hang out while you stream.", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Surface(color = Color(0xFF202B3B), shape = RoundedCornerShape(30.dp)) {
                Text("BETA", color = Color(0xFFB8C7FF), fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }

        if (joined == null) {
            Surface(color = VoiceCard, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Join with Room ID", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(value = displayName, onValueChange = { displayName = it.take(24) }, modifier = Modifier.fillMaxWidth(), label = { Text("Your name") }, singleLine = true)
                    OutlinedTextField(value = roomIdInput, onValueChange = { roomIdInput = it.filter(Char::isDigit).take(6) }, modifier = Modifier.fillMaxWidth(), label = { Text("6-digit Room ID") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Button(onClick = { scope.launch { joinRoom(roomIdInput) } }, enabled = roomIdInput.length == 6 && !loading, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = VoiceAccent)) {
                        Text(if (loading) "Joining…" else "JOIN VOICE ROOM", fontWeight = FontWeight.Bold)
                    }
                    Text("Private rooms are accessible only by entering their Room ID. Public rooms also appear below.", color = VoiceMuted, fontSize = 10.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Public Lobby", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { scope.launch { refreshRooms() } }) { Text("REFRESH") }
                Button(onClick = { showCreate = true }, colors = ButtonDefaults.buttonColors(containerColor = VoiceAccent)) { Text("＋ Create") }
            }
            if (rooms.isEmpty()) {
                Surface(color = VoiceCard, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("No public rooms right now. Create one and invite friends with its Room ID.", color = VoiceMuted, fontSize = 12.sp, modifier = Modifier.padding(15.dp))
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rooms, key = { it.id }) { room ->
                        Surface(color = VoiceCard, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().clickable { scope.launch { joinRoom(room.id) } }) {
                            Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(color = VoiceAccent, shape = CircleShape) { Text("🎙", modifier = Modifier.padding(10.dp), fontSize = 16.sp) }
                                Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(room.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text("Room ID ${room.id} · ${room.count}/6 members", color = VoiceMuted, fontSize = 10.sp)
                                }
                                Text("JOIN", color = Color(0xFFB8C7FF), fontSize = 10.sp, fontWeight = FontWeight.Black)
                            }
                        }
                    }
                }
            }
        } else {
            val room = joined!!
            Surface(color = VoiceCard, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(room.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(if (room.visibility == "public") "PUBLIC ROOM" else "PRIVATE ROOM", color = VoiceMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("${room.count}/6", color = Color(0xFFB8C7FF), fontWeight = FontWeight.Bold)
                    }
                    Surface(color = Color(0xFF10151D), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("ROOM ID", color = VoiceMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text(room.id, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
                            Text("Share this one ID with friends to invite them.", color = VoiceMuted, fontSize = 10.sp)
                        }
                    }
                    Text("IN THE ROOM", color = VoiceMuted, fontSize = 9.sp, fontWeight = FontWeight.Black)
                    room.members.forEach { name ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = Color(0xFF2A3443), shape = CircleShape) { Text(name.take(1).uppercase(), color = Color.White, modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp), fontWeight = FontWeight.Bold) }
                            Text(name, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(start = 9.dp))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { micEnabled = !micEnabled }, modifier = Modifier.weight(1f)) { Text(if (micEnabled) "MIC ON" else "MIC OFF") }
                        OutlinedButton(onClick = { speakerEnabled = !speakerEnabled }, modifier = Modifier.weight(1f)) { Text(if (speakerEnabled) "SPEAKER ON" else "SPEAKER OFF") }
                    }
                    Text("Room directory is connected. Live WebRTC voice and RTMP party-audio mixing are not enabled yet.", color = Color(0xFFFFC46B), fontSize = 10.sp)
                    Button(onClick = {
                        scope.launch {
                            runCatching { voiceRequest("/api/voice/rooms/leave", "POST", JSONObject().put("roomId", room.id).put("memberId", memberId)) }
                            joined = null
                            micEnabled = false
                            prefs.edit().remove("room_id").apply()
                            refreshRooms()
                        }
                    }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB63F4B))) { Text("LEAVE ROOM", fontWeight = FontWeight.Bold) }
                }
            }
        }
        if (error.isNotBlank()) Text(error, color = Color(0xFFFF8B8B), fontSize = 11.sp)
        Text("Rooms are temporary and stored only in server memory. They disappear after the server restarts or expires inactive rooms.", color = VoiceMuted, fontSize = 10.sp)
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("Create voice room") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = roomName, onValueChange = { roomName = it.take(32) }, label = { Text("Room name") }, singleLine = true)
                    Text("Room visibility", fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = visibility == "public", onClick = { visibility = "public" })
                        Text("Public lobby")
                        RadioButton(selected = visibility == "private", onClick = { visibility = "private" })
                        Text("Private")
                    }
                    Text("Both types use one six-digit Room ID. Private rooms won't appear in the public lobby.", color = VoiceMuted, fontSize = 10.sp)
                }
            },
            confirmButton = {
                Button(onClick = {
                    showCreate = false
                    scope.launch {
                        loading = true
                        runCatching {
                            val payload = JSONObject().put("name", roomName).put("visibility", visibility).put("memberId", memberId).put("displayName", displayName.ifBlank { "Host" })
                            joined = parseVoiceRoom(voiceRequest("/api/voice/rooms", "POST", payload))
                            prefs.edit().putString("display_name", displayName).putString("room_id", joined?.id).apply()
                        }.onFailure { error = it.message ?: "Room creation failed" }
                        loading = false
                    }
                }) { Text("CREATE") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("CANCEL") } }
        )
    }
}
