package app.streamforge.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
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
private data class VoiceRoom(
    val id: String,
    val name: String,
    val visibility: String,
    val count: Int,
    val members: List<String>,
    val voiceToken: String = ""
)

private suspend fun voiceRequest(baseUrl: String, path: String, method: String = "GET", payload: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
    val connection = URL(baseUrl.trim().trimEnd('/') + path).openConnection() as HttpURLConnection
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
    if (members != null) for (i in 0 until members.length()) {
        names.add(members.optJSONObject(i)?.optString("displayName", "Guest") ?: "Guest")
    }
    val you = json.optJSONObject("you") ?: JSONObject()
    return VoiceRoom(
        room.optString("roomId"), room.optString("name", "Voice Room"),
        room.optString("visibility", "public"), room.optInt("memberCount", names.size),
        names, you.optString("voiceToken")
    )
}

@Composable
fun PartyVoicePanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("streamforge_party", 0) }
    val memberId = remember { prefs.getString("member_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("member_id", it).apply() } }
    var displayName by remember { mutableStateOf(prefs.getString("display_name", "Streamer") ?: "Streamer") }
    var serverUrl by remember { mutableStateOf(VoiceServerStore.read(context, VOICE_API)) }
    var roomName by remember { mutableStateOf("Gaming Party") }
    var roomIdInput by remember { mutableStateOf("") }
    var visibility by remember { mutableStateOf("public") }
    var rooms by remember { mutableStateOf<List<VoiceRoom>>(emptyList()) }
    var joined by remember { mutableStateOf<VoiceRoom?>(null) }
    var pendingVoiceRoom by remember { mutableStateOf<VoiceRoom?>(null) }
    val voiceState by PartyVoiceService.state.collectAsState()
    val voiceActive by PartyVoiceService.active.collectAsState()
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var showRoomCode by remember { mutableStateOf(false) }

    fun startVoice(room: VoiceRoom) {
        prefs.edit()
            .putString("server_url", serverUrl)
            .putString("room_id", room.id)
            .putString("room_name", room.name)
            .putString("room_visibility", room.visibility)
            .putString("voice_token", room.voiceToken)
            .putString("display_name", displayName)
            .apply()
        PartyVoiceService.join(
            context, serverUrl, room.id, memberId, room.voiceToken,
            displayName.ifBlank { "Guest" }, room.name
        )
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val target = pendingVoiceRoom
        if (granted && target != null) {
            startVoice(target)
        } else {
            error = "Microphone permission is required for voice chat"
        }
        pendingVoiceRoom = null
    }

    fun requestVoice(room: VoiceRoom) {
        pendingVoiceRoom = room
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoice(room)
            pendingVoiceRoom = null
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    suspend fun refreshRooms() {
        runCatching {
            val response = voiceRequest(serverUrl, "/api/voice/rooms")
            val array = response.optJSONArray("rooms")
            val list = mutableListOf<VoiceRoom>()
            if (array != null) for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val memberNames = mutableListOf<String>()
                val members = item.optJSONArray("members")
                if (members != null) for (j in 0 until members.length()) {
                    memberNames.add(members.optJSONObject(j)?.optString("displayName", "Guest") ?: "Guest")
                }
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
            val room = parseVoiceRoom(voiceRequest(serverUrl, "/api/voice/rooms/join", "POST", body))
            if (room.voiceToken.isBlank()) throw IllegalStateException("Voice security token missing. Update the voice server.")
            joined = room
            prefs.edit().putString("display_name", displayName).putString("room_id", id).putString("room_name", room.name).putString("room_visibility", room.visibility).putString("server_url", serverUrl).putString("voice_token", room.voiceToken).apply()
            requestVoice(room)
            roomIdInput = ""
        }.onFailure { error = it.message ?: "Could not join room" }
        loading = false
    }

    // Restore the previous room when the user returns from another app page.
    // The service owns the actual WebRTC engine, so this screen can be recreated safely.
    LaunchedEffect(Unit) {
        val savedId = prefs.getString("room_id", "").orEmpty()
        val savedToken = prefs.getString("voice_token", "").orEmpty()
        if (savedId.isNotBlank() && savedToken.isNotBlank()) {
            val savedName = prefs.getString("room_name", "Voice Room") ?: "Voice Room"
            val savedVisibility = prefs.getString("room_visibility", "public") ?: "public"
            val restored = runCatching {
                parseVoiceRoom(voiceRequest(serverUrl, "/api/voice/rooms/$savedId")).copy(voiceToken = savedToken)
            }.getOrElse {
                VoiceRoom(savedId, savedName, savedVisibility, 1, listOf(displayName.ifBlank { "You" }), savedToken)
            }
            joined = restored
            if (!PartyVoiceService.active.value) startVoice(restored)
        }
    }

    LaunchedEffect(voiceActive, voiceState.status) {
        if (!voiceActive && voiceState.status == "Left voice room") {
            joined = null
            prefs.edit().remove("room_id").remove("voice_token").remove("room_name").apply()
        }
    }

    // Keep the public lobby live while the user is outside a room.
    // This panel sits inside the app's main vertical scroll, so avoid a nested LazyColumn.
    LaunchedEffect(joined == null, serverUrl) {
        if (joined == null) {
            while (true) {
                refreshRooms()
                delay(5000)
            }
        }
    }
    LaunchedEffect(joined?.id) {
        val room = joined ?: return@LaunchedEffect
        while (true) {
            delay(4000)
            runCatching {
                val updated = parseVoiceRoom(voiceRequest(serverUrl, "/api/voice/rooms/${room.id}"))
                joined = updated.copy(voiceToken = room.voiceToken)
            }.onFailure { error = it.message ?: "Room update failed" }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("PARTY VOICE", color = VoiceAccent, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp)
                Text("Hang out while you stream.", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Surface(color = Color(0xFF202B3B), shape = RoundedCornerShape(30.dp)) {
                Text("LIVE VOICE", color = Color(0xFFB8C7FF), fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }

        if (joined == null) {
            Surface(color = VoiceCard, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("PRIVATE, TEMPORARY ROOMS", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("Room presence and connection signaling stay in server memory. No voice database.", color = VoiceMuted, fontSize = 10.sp)
                    }
                    Text("● RAM ONLY", color = Color(0xFF75D9AE), fontSize = 9.sp, fontWeight = FontWeight.Black)
                }
            }
            Surface(color = VoiceCard, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Join with Room ID", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(value = displayName, onValueChange = { displayName = it.take(24) }, modifier = Modifier.fillMaxWidth(), label = { Text("Your name") }, singleLine = true)
                    OutlinedTextField(value = roomIdInput, onValueChange = { roomIdInput = it.filter(Char::isDigit).take(6) }, modifier = Modifier.fillMaxWidth(), label = { Text("6-digit Room ID") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Button(onClick = { scope.launch { joinRoom(roomIdInput) } }, enabled = roomIdInput.length == 6 && !loading, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = VoiceAccent)) {
                        Text(if (loading) "Joining…" else "JOIN VOICE ROOM", fontWeight = FontWeight.Bold)
                    }
                    Text("Public rooms appear below. Private rooms are joined by Room ID.", color = VoiceMuted, fontSize = 10.sp)
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Bound lobby composition on low-memory phones; refresh keeps newest rooms visible.
                    rooms.asReversed().take(20).forEach { room ->
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
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = Color(0xFF202638), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = VoiceAccent, shape = RoundedCornerShape(12.dp)) { Text("🎙", modifier = Modifier.padding(11.dp), fontSize = 20.sp) }
                            Column(Modifier.weight(1f).padding(start = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(room.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(color = Color(0xFF245D4A), shape = RoundedCornerShape(20.dp)) { Text("● ROOM ACTIVE", color = Color(0xFF8CE0B8), fontSize = 8.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
                                    Text("  ·  ${room.visibility.uppercase()} VOICE", color = VoiceMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Text("${room.count}/6", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("VOICE CHANNEL", color = Color(0xFFB8C7FF), fontSize = 9.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                            TextButton(onClick = { showRoomCode = !showRoomCode }) { Text(if (showRoomCode) "HIDE ID" else "INVITE / ID") }
                        }
                        Surface(color = Color(0xFF171C2A), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(if (showRoomCode) room.id else "••••••", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                                    Text("Share this code with friends to join", color = VoiceMuted, fontSize = 9.sp)
                                }
                                TextButton(onClick = { showRoomCode = !showRoomCode }) { Text(if (showRoomCode) "HIDE" else "SHOW") }
                            }
                        }
                    }
                }
                Surface(color = Color(0xFF181D29), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("VOICE MEMBERS", color = Color.White, fontWeight = FontWeight.Black, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Text("${room.members.size} HERE", color = VoiceMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                        val displayMembers = room.members.ifEmpty { listOf(displayName.ifBlank { "You" }) }
                        displayMembers.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                                pair.forEach { name ->
                                    val active = if (name == displayName.ifBlank { "You" }) voiceState.localSpeaking else name in voiceState.speaking
                                    Surface(
                                        color = Color(0xFF242B3B),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.weight(1f).border(
                                            width = if (active) 2.dp else 0.dp,
                                            color = if (active) Color(0xFF48E39A) else Color.Transparent,
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                    ) {
                                        Row(
                                            Modifier.padding(vertical = 16.dp, horizontal = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            if (active) {
                                                Surface(
                                                    color = Color(0xFF48E39A),
                                                    shape = RoundedCornerShape(3.dp),
                                                    modifier = Modifier.width(10.dp).height(10.dp)
                                                ) {}
                                            } else {
                                                Spacer(Modifier.width(10.dp).height(10.dp))
                                            }
                                            Column(
                                                Modifier.weight(1f),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(7.dp)
                                            ) {
                                            Surface(
                                                color = if (active) Color(0xFF237D5B) else VoiceAccent,
                                                shape = CircleShape,
                                                modifier = Modifier.border(if (active) 2.dp else 0.dp, if (active) Color(0xFF65FFB2) else Color.Transparent, CircleShape)
                                            ) { Text(name.take(1).uppercase(), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 17.dp, vertical = 12.dp)) }
                                            Text(name, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                            Text(if (active) "● SPEAKING" else if (name == displayName) "YOU · CONNECTED" else "CONNECTED", color = if (active) Color(0xFF65FFB2) else VoiceMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        Text(
                            if (!voiceState.micEnabled) "MIC MUTED · voice room stays connected"
                            else if (voiceState.micCapturing) "MIC CAPTURING · speak to light up your tile"
                            else voiceState.status,
                            color = if (voiceState.micCapturing && voiceState.micEnabled) Color(0xFF75D9AE) else if (voiceState.connected) Color(0xFFFFC46B) else Color(0xFFFF8B8B),
                            fontSize = 9.sp
                        )
                        Text(voiceState.status, color = VoiceMuted, fontSize = 9.sp)
                    }
                }
                Surface(color = Color(0xFF202638), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("VOICE CONTROLS", color = VoiceMuted, fontSize = 9.sp, fontWeight = FontWeight.Black)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { PartyVoiceService.setMicEnabled(context, !voiceState.micEnabled) },
                                modifier = Modifier.weight(1f),
                                enabled = voiceActive
                            ) { Text(if (voiceState.micEnabled) "🎙 MIC ON" else "🔇 MUTED", fontWeight = FontWeight.Bold) }
                            OutlinedButton(
                                onClick = { PartyVoiceService.setSpeakerEnabled(context, !voiceState.speakerEnabled) },
                                modifier = Modifier.weight(1f),
                                enabled = voiceActive
                            ) { Text(if (voiceState.speakerEnabled) "🔊 SPEAKER" else "🔈 OFF", fontWeight = FontWeight.Bold) }
                            Button(onClick = {
                                PartyVoiceService.leave(context)
                                joined = null
                                showRoomCode = false
                                prefs.edit().remove("room_id").remove("voice_token").remove("room_name").apply()
                                scope.launch { refreshRooms() }
                            }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB94150))) { Text("LEAVE", fontWeight = FontWeight.Black) }
                        }
                        Text("Peer-to-peer WebRTC audio · no voice recordings or database storage", color = VoiceMuted, fontSize = 9.sp)
                    }
                }
            }
        }
        if (error.isNotBlank()) Text(error, color = Color(0xFFFF8B8B), fontSize = 11.sp)
        Text("Rooms and live connection state are temporary. A server restart clears them.", color = VoiceMuted, fontSize = 10.sp)
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
                            val room = parseVoiceRoom(voiceRequest(serverUrl, "/api/voice/rooms", "POST", payload))
                            if (room.voiceToken.isBlank()) throw IllegalStateException("Voice security token missing. Update the voice server.")
                            joined = room
                            prefs.edit().putString("display_name", displayName).putString("room_id", room.id).putString("room_name", room.name).putString("room_visibility", room.visibility).putString("server_url", serverUrl).putString("voice_token", room.voiceToken).apply()
                            requestVoice(room)
                        }.onFailure { error = it.message ?: "Room creation failed" }
                        loading = false
                    }
                }, enabled = !loading) { Text("CREATE") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("CANCEL") } }
        )
    }
}
