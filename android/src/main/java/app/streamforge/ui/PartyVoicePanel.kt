package app.streamforge.ui

import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.RadioButton
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

private const val VOICE_API = "https://streamforge-chat-api.onrender.com"
private val VOICE_POOL = listOf(
    "https://streamforge-signal-a.onrender.com",
    "https://streamforge-signal-b.onrender.com",
    "https://streamforge-signal-c.onrender.com"
)
private fun voiceServerCandidates(preferred: String): List<String> =
    (listOf(preferred) + VOICE_POOL + listOf(VOICE_API)).map { it.trim().trimEnd('/') }.distinct()

private suspend fun voiceRequestAcross(
    preferred: String,
    path: String,
    method: String = "GET",
    payload: JSONObject? = null
): Pair<JSONObject, String> {
    var lastError: Throwable? = null
    for (endpoint in voiceServerCandidates(preferred)) {
        try {
            return voiceRequest(endpoint, path, method, payload) to endpoint
        } catch (error: Throwable) {
            lastError = error
        }
    }
    throw IllegalStateException(lastError?.message ?: "All Party voice servers are unavailable")
}
private val VoiceBg = Color(0xFF11151A)
private val VoiceCard = Color(0xFF1A2028)
private val VoiceMuted = Color(0xFF9AA6B2)
private val VoiceAccent = Color(0xFFF08A52)
private data class VoiceMember(val id: String, val name: String, val muted: Boolean = false)

private data class VoiceRoom(
    val id: String,
    val name: String,
    val visibility: String,
    val count: Int,
    val members: List<VoiceMember>,
    val voiceToken: String = "",
    val serverUrl: String = ""
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
    val parsedMembers = mutableListOf<VoiceMember>()
    if (members != null) for (i in 0 until members.length()) {
        val item = members.optJSONObject(i) ?: continue
        parsedMembers.add(VoiceMember(item.optString("memberId"), item.optString("displayName", "Guest"), item.optBoolean("muted")))
    }
    val you = json.optJSONObject("you") ?: JSONObject()
    return VoiceRoom(
        room.optString("roomId"), room.optString("name", "Voice Room"),
        room.optString("visibility", "public"), room.optInt("memberCount", parsedMembers.size),
        parsedMembers, you.optString("voiceToken")
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
    var serverReady by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var showRoomCode by remember { mutableStateOf(false) }
    var selectedMember by remember { mutableStateOf<VoiceMember?>(null) }
    var selectedMemberVolume by remember { mutableStateOf(1f) }
    var showScreenQuality by remember { mutableStateOf(false) }
    var showRemoteScreen by remember { mutableStateOf(false) }
    var remoteScreenFullscreen by remember { mutableStateOf(false) }
    var screenQuality by remember { mutableStateOf(720) }
    var requestingScreenShare by remember { mutableStateOf(false) }

    fun startVoice(room: VoiceRoom) {
        val roomServer = room.serverUrl.ifBlank { serverUrl }
        serverUrl = roomServer
        prefs.edit()
            .putString("server_url", roomServer)
            .putString("room_id", room.id)
            .putString("room_name", room.name)
            .putString("room_visibility", room.visibility)
            .putString("voice_token", room.voiceToken)
            .putString("display_name", displayName)
            .apply()
        PartyVoiceService.join(
            context, roomServer, room.id, memberId, room.voiceToken,
            displayName.ifBlank { "Guest" }, room.name
        )
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.RECORD_AUDIO] == true || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val target = pendingVoiceRoom
        if (granted && target != null) {
            startVoice(target)
        } else {
            error = "Microphone permission is required for voice chat"
        }
        pendingVoiceRoom = null
    }

    val screenSharePermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        requestingScreenShare = false
        val permissionData = result.data
        if (result.resultCode == Activity.RESULT_OK && permissionData != null) {
            val metrics = context.resources.displayMetrics
            // Scale the longer edge to the selected quality while preserving the
            // actual display aspect ratio. Never clamp width and height separately:
            // that distorts portrait captures and produces cropped/black video.
            val sourceWidth = metrics.widthPixels.coerceAtLeast(2)
            val sourceHeight = metrics.heightPixels.coerceAtLeast(2)
            val scale = screenQuality.toFloat() / maxOf(sourceWidth, sourceHeight)
            val width = ((sourceWidth * scale).toInt().coerceAtLeast(2) / 2) * 2
            val height = ((sourceHeight * scale).toInt().coerceAtLeast(2) / 2) * 2
            PartyVoiceService.startScreenShare(context, permissionData, width, height, 30)
        } else {
            // Surface the actual Android activity result instead of assuming the user cancelled.
            error = if (permissionData == null) {
                "Android returned no screen-capture data (result code: " + result.resultCode + ")."
            } else {
                "Android did not grant screen capture (result code: " + result.resultCode + ")."
            }
        }
    }

    fun requestVoice(room: VoiceRoom) {
        pendingVoiceRoom = room
        val needsMic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        val needsNotifications = Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (!needsMic && !needsNotifications) {
            startVoice(room)
            pendingVoiceRoom = null
        } else {
            val permissions = mutableListOf<String>()
            if (needsMic) permissions += Manifest.permission.RECORD_AUDIO
            if (needsNotifications) permissions += Manifest.permission.POST_NOTIFICATIONS
            micPermission.launch(permissions.toTypedArray())
        }
    }

    suspend fun findRoomOwner(roomId: String): String {
        val known = rooms.firstOrNull { it.id == roomId }?.serverUrl.orEmpty()
        if (known.isNotBlank() && runCatching { voiceRequest(known, "/api/voice/rooms/$roomId") }.isSuccess) return known
        for (endpoint in voiceServerCandidates(serverUrl)) {
            if (endpoint == known) continue
            if (runCatching { voiceRequest(endpoint, "/api/voice/rooms/$roomId") }.isSuccess) return endpoint
        }
        throw IllegalStateException("Room not found on any Party server. It may have expired.")
    }

    suspend fun selectLeastLoadedVoiceServer(): String {
        val health = VOICE_POOL.mapNotNull { endpoint ->
            runCatching {
                val status = voiceRequest(endpoint, "/api/voice/health")
                if (!status.optBoolean("ok", false)) return@runCatching null
                endpoint to (status.optInt("roomCount") * 1000 + status.optInt("connectedMembers"))
            }.getOrNull()
        }
        return health.minByOrNull { it.second }?.first
            ?: throw IllegalStateException("All Party servers are unavailable")
    }

    suspend fun refreshRooms() {
        var responding = 0
        val combined = linkedMapOf<String, VoiceRoom>()
        for (endpoint in voiceServerCandidates(serverUrl)) {
            runCatching {
                val health = voiceRequest(endpoint, "/api/voice/health")
                if (!health.optBoolean("ok", false)) return@runCatching
                responding++
                val response = voiceRequest(endpoint, "/api/voice/rooms")
                val array = response.optJSONArray("rooms")
                if (array != null) for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val parsedMembers = mutableListOf<VoiceMember>()
                    val members = item.optJSONArray("members")
                    if (members != null) for (j in 0 until members.length()) {
                        val member = members.optJSONObject(j) ?: continue
                        parsedMembers.add(VoiceMember(member.optString("memberId"), member.optString("displayName", "Guest"), member.optBoolean("muted")))
                    }
                    val id = item.optString("roomId")
                    combined[id] = VoiceRoom(id, item.optString("name"), item.optString("visibility"), item.optInt("memberCount"), parsedMembers, serverUrl = endpoint)
                }
            }
        }
        serverReady = responding > 0
        rooms = combined.values.toList()
        error = ""
    }

    suspend fun joinRoom(id: String) {
        loading = true
        error = ""
        runCatching {
            val body = JSONObject().put("roomId", id).put("memberId", memberId).put("displayName", displayName.ifBlank { "Guest" })
            val ownerEndpoint = findRoomOwner(id)
            val joinJson = voiceRequest(ownerEndpoint, "/api/voice/rooms/join", "POST", body)
            val room = parseVoiceRoom(joinJson)
            if (room.voiceToken.isBlank()) throw IllegalStateException("Voice security token missing. Update the voice server.")
            serverUrl = ownerEndpoint
            joined = room
            prefs.edit().putString("display_name", displayName).putString("room_id", id).putString("room_name", room.name).putString("room_visibility", room.visibility).putString("server_url", ownerEndpoint).putString("voice_token", room.voiceToken).apply()
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
            val restoredResult = runCatching {
                val owner = findRoomOwner(savedId)
                val json = voiceRequest(owner, "/api/voice/rooms/$savedId")
                json to owner
            }
            val restored = restoredResult.getOrNull()?.let { (json, owner) ->
                serverUrl = owner
                prefs.edit().putString("server_url", owner).apply()
                parseVoiceRoom(json).copy(voiceToken = savedToken, serverUrl = owner)
            } ?: VoiceRoom(savedId, savedName, savedVisibility, 1, listOf(VoiceMember(memberId, displayName.ifBlank { "You" })), savedToken, serverUrl)
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
                val roomServer = room.serverUrl.ifBlank { serverUrl }
                val updated = parseVoiceRoom(voiceRequest(roomServer, "/api/voice/rooms/${room.id}"))
                joined = updated.copy(voiceToken = room.voiceToken, serverUrl = roomServer)
            }.onFailure { error = it.message ?: "Room update failed" }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            PartyMark(Modifier.size(42.dp))
            Column(Modifier.weight(1f).padding(start = 11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Party", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                Text("Your people. One room.", color = VoiceMuted, fontSize = 11.sp)
            }
            Surface(color = Color(0xFF26352F), shape = CircleShape) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(6.dp).background(Color(0xFF68D6A2), CircleShape))
                    Text(if (joined != null) "IN PARTY" else "LOBBY", color = Color(0xFF9BE4BD), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (joined == null) {
            Surface(color = VoiceCard, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Jump into a room", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text("Join friends with a code or start your own.", color = VoiceMuted, fontSize = 11.sp)
                        }
                        PartyGlyph("spark", Modifier.size(28.dp), VoiceAccent)
                    }
                    OutlinedTextField(value = displayName, onValueChange = { displayName = it.take(24) }, modifier = Modifier.fillMaxWidth(), label = { Text("Your display name") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = roomIdInput, onValueChange = { roomIdInput = it.filter(Char::isDigit).take(6) }, modifier = Modifier.weight(1f), label = { Text("6-digit room code") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        Button(onClick = { scope.launch { joinRoom(roomIdInput) } }, enabled = roomIdInput.length == 6 && !loading, shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = VoiceAccent)) {
                            Text(if (loading) "…" else "Join", fontWeight = FontWeight.Bold)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Public rooms", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { scope.launch { refreshRooms() } }) { Text("Refresh", color = VoiceMuted) }
                        Button(onClick = { showCreate = true }, shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34302E))) {
                            Text("+ Create", color = Color(0xFFFFB58A), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            if (!serverReady) {
                Surface(color = VoiceCard, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(22.dp), color = VoiceAccent, strokeWidth = 2.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Connecting to Party", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("The room list will appear when the server is ready.", color = VoiceMuted, fontSize = 10.sp)
                        }
                    }
                }
            } else if (rooms.isEmpty()) {
                Surface(color = VoiceCard, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        PartyMark(Modifier.size(58.dp))
                        Text("No one's hanging out yet", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Create a room and invite your friends with its code.", color = VoiceMuted, fontSize = 11.sp)
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rooms.asReversed().take(20).forEach { room ->
                        Surface(color = VoiceCard, shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth().clickable { scope.launch { joinRoom(room.id) } }) {
                            Row(Modifier.padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                                PartyMark(Modifier.size(40.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(room.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text("${room.count} here  ·  ${room.visibility.replaceFirstChar { it.uppercase() }}", color = VoiceMuted, fontSize = 10.sp)
                                }
                                Surface(color = Color(0xFF332B27), shape = CircleShape) {
                                    Text("Join  ↗", color = Color(0xFFFFB58A), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val room = joined!!
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = VoiceCard, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PartyMark(Modifier.size(46.dp))
                            Column(Modifier.weight(1f).padding(start = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(room.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
                                Text(if (voiceState.connected) "Connected · ${room.visibility} room" else voiceState.status, color = if (voiceState.connected) Color(0xFF8DD9B2) else VoiceMuted, fontSize = 10.sp, maxLines = 1)
                            }
                            Surface(color = Color(0xFF26352F), shape = CircleShape) {
                                Text("${room.count}/6", color = Color(0xFF9BE4BD), fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("ROOM CODE", color = VoiceMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                Text(if (showRoomCode) room.id else "••••••", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp)
                            }
                            TextButton(onClick = { showRoomCode = !showRoomCode }) { Text(if (showRoomCode) "Hide" else "Show", color = Color(0xFFFFB58A)) }
                        }
                    }
                }

                if (voiceState.screenSharing || voiceState.remoteScreenActive || voiceState.remoteScreenTrack != null || voiceState.screenShareStatus.isNotBlank()) {
                    val localIsSharing = voiceState.screenSharing || (!voiceState.remoteScreenActive && voiceState.screenShareStatus.isNotBlank())
                    val displayedTrack = if (localIsSharing) voiceState.localScreenTrack else voiceState.remoteScreenTrack
                    Surface(
                        color = Color(0xFF1A2025),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth().then(if (displayedTrack != null) Modifier.clickable {
                            remoteScreenFullscreen = false
                            showRemoteScreen = true
                        } else Modifier)
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                Surface(color = Color(0xFF8D3E36), shape = CircleShape) {
                                    Text("LIVE", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(if (localIsSharing) "Your screen" else "${voiceState.remoteScreenOwner ?: "A member"}'s screen", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text(if (displayedTrack != null) "Tap to open full screen" else voiceState.screenShareStatus.ifBlank { "Waiting for screen video…" }, color = VoiceMuted, fontSize = 10.sp)
                                }
                                if (displayedTrack != null) PartyGlyph("expand", Modifier.size(20.dp), Color.White)
                            }
                            if (displayedTrack != null && !showRemoteScreen) {
                                PartyScreenVideo(displayedTrack, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                            } else if (displayedTrack != null) {
                                Surface(color = Color(0xFF101519), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Screen open in viewer", color = VoiceMuted, fontSize = 10.sp) }
                                }
                            } else {
                                Surface(color = Color(0xFF101519), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        PartyGlyph("screen", Modifier.size(34.dp), VoiceAccent)
                                        Text(voiceState.screenShareStatus.ifBlank { "Waiting for screen video…" }, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("People here", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("Tap a member for volume and mute controls.", color = VoiceMuted, fontSize = 10.sp)
                    }
                    Text("${room.members.size} / 6", color = VoiceMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                val displayMembers = room.members.ifEmpty { listOf(VoiceMember(memberId, displayName.ifBlank { "You" })) }
                Column(
                    modifier = Modifier.fillMaxWidth().height(224.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    displayMembers.chunked(3).forEach { group ->
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                            group.forEach { member ->
                                val own = member.id == memberId
                                val active = if (own) voiceState.localSpeaking else member.id in voiceState.speaking
                                val muted = if (own) !voiceState.micEnabled else member.id in voiceState.mutedMembers || member.muted
                                Surface(
                                    color = if (active) Color(0xFF202C29) else Color(0xFF1B2229),
                                    shape = RoundedCornerShape(15.dp),
                                    modifier = Modifier.weight(1f).aspectRatio(1f)
                                        .border(if (active) 2.dp else 1.dp, if (active) Color(0xFF65D99D) else Color(0xFF303A42), RoundedCornerShape(15.dp))
                                        .clickable {
                                            if (!own) {
                                                selectedMember = member
                                                selectedMemberVolume = 1f
                                            }
                                        }
                                ) {
                                    Column(Modifier.fillMaxSize().padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Surface(color = if (active) Color(0xFF285B49) else Color(0xFF35302C), shape = RoundedCornerShape(13.dp), modifier = Modifier.size(40.dp)) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(member.name.take(1).uppercase(), color = if (active) Color(0xFFB6F1D0) else Color(0xFFFFC29F), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                                                }
                                            }
                                            if (muted) {
                                                Surface(color = Color(0xFF6B3434), shape = CircleShape, modifier = Modifier.align(Alignment.BottomEnd).size(17.dp)) {
                                                    Box(contentAlignment = Alignment.Center) { PartyGlyph("mute", Modifier.size(10.dp), Color(0xFFFFC3BD)) }
                                                }
                                            }
                                        }
                                        Spacer(Modifier.height(5.dp))
                                        Text(member.name + if (own) " (you)" else "", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                        Text(if (muted) "Muted" else if (active) "Speaking" else "In voice", color = if (active) Color(0xFF8FE2B2) else VoiceMuted, fontSize = 8.sp, maxLines = 1)
                                    }
                                }
                            }
                            repeat(3 - group.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                Text(
                    if (!voiceState.micEnabled) "Microphone muted · you're still connected"
                    else if (voiceState.micCapturing) "Microphone ready · speak to light up your tile"
                    else voiceState.status,
                    color = if (voiceState.micCapturing && voiceState.micEnabled) Color(0xFF8FE2B2) else if (voiceState.connected) VoiceMuted else Color(0xFFFF9A91),
                    fontSize = 10.sp
                )

                Surface(color = Color(0xFF20272D), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        PartyDockButton("Mic", active = voiceState.micEnabled, glyph = "mic") {
                            PartyVoiceService.setMicEnabled(context, !voiceState.micEnabled)
                        }
                        PartyDockButton("Share", active = voiceState.screenSharing, glyph = "screen") {
                            showScreenQuality = true
                        }
                        PartyDockButton("Speaker", active = voiceState.speakerEnabled, glyph = "speaker") {
                            PartyVoiceService.setSpeakerEnabled(context, !voiceState.speakerEnabled)
                        }
                        PartyDockButton("Leave", active = false, glyph = "leave") {
                            PartyVoiceService.leave(context)
                            joined = null
                            showRoomCode = false
                            showRemoteScreen = false
                            prefs.edit().remove("room_id").remove("voice_token").remove("room_name").apply()
                            scope.launch { refreshRooms() }
                        }
                    }
                }
                Text("Voice stays between members. No recordings.", color = VoiceMuted, fontSize = 9.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
        if (error.isNotBlank()) Text(error, color = Color(0xFFFF8B8B), fontSize = 11.sp)
        Text("Rooms and live connection state are temporary. A server restart clears them.", color = VoiceMuted, fontSize = 10.sp)
    }

    if (showRemoteScreen) {
        val remoteTrack = if (voiceState.screenSharing) voiceState.localScreenTrack else voiceState.remoteScreenTrack
        Dialog(
            onDismissRequest = {
                showRemoteScreen = false
                remoteScreenFullscreen = false
            },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                color = Color(0xFF080B10),
                shape = if (remoteScreenFullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(20.dp),
                modifier = if (remoteScreenFullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 24.dp)
            ) {
                Column(
                    Modifier.fillMaxSize().padding(if (remoteScreenFullscreen) 12.dp else 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(if (voiceState.screenSharing) "YOUR SCREEN" else "${voiceState.remoteScreenOwner ?: "PARTY MEMBER"}'S SCREEN", color = Color.White, fontWeight = FontWeight.Black, fontSize = 12.sp)
                            Text("● LIVE · PARTY VOICE", color = Color(0xFFFF747B), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                        TextButton(onClick = { remoteScreenFullscreen = !remoteScreenFullscreen }) {
                            Text(if (remoteScreenFullscreen) "EXIT ZOOM" else "⛶ ZOOM")
                        }
                        TextButton(onClick = {
                            showRemoteScreen = false
                            remoteScreenFullscreen = false
                        }) { Text("CLOSE") }
                    }
                    if (remoteTrack != null) {
                        PartyScreenVideo(
                            remoteTrack,
                            Modifier.fillMaxWidth().weight(1f),
                            useSourceAspectRatio = false
                        )
                    } else {
                        Surface(color = Color(0xFF111823), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("▣", color = Color(0xFF8CE0B8), fontSize = 38.sp)
                                Text("Connecting to screen…", color = Color.White, fontWeight = FontWeight.Bold)
                                Text("The preview will switch to live video as soon as the stream arrives.", color = VoiceMuted, fontSize = 10.sp)
                            }
                        }
                    }
                    Text("Full phone screen stays visible · use FULL for edge-to-edge mode", color = VoiceMuted, fontSize = 9.sp)
                }
            }
        }
    }

    if (showScreenQuality) {
        AlertDialog(
            onDismissRequest = { showScreenQuality = false },
            title = { Text(if (voiceState.screenSharing) "Your screen is live" else "Start screen share") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("SCREEN SHARE QUALITY", fontSize = 9.sp, fontWeight = FontWeight.Black, color = VoiceMuted)
                    listOf(480, 720, 1080).forEach { quality ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = screenQuality == quality, onClick = { screenQuality = quality }, enabled = !voiceState.screenSharing)
                            Text("${quality}p · 30 FPS", color = if (voiceState.screenSharing) VoiceMuted else Color.Unspecified)
                        }
                    }
                    Text(if (voiceState.screenSharing) "Your screen is being shared with everyone in this voice room." else "Choose quality, then start. Android will ask you to confirm screen capture.", color = VoiceMuted, fontSize = 10.sp)
                }
            },
            confirmButton = {
                if (voiceState.screenSharing) {
                    Button(onClick = {
                        PartyVoiceService.stopScreenShare(context)
                        showScreenQuality = false
                    }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB94150))) { Text("■ STOP SHARE") }
                } else {
                    Button(onClick = {
                        if (!requestingScreenShare) {
                            requestingScreenShare = true
                            showScreenQuality = false
                            val manager = context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            screenSharePermission.launch(manager.createScreenCaptureIntent())
                        }
                    }, enabled = !requestingScreenShare, colors = ButtonDefaults.buttonColors(containerColor = VoiceAccent)) {
                        Text(if (requestingScreenShare) "WAITING FOR ANDROID…" else "▣ START SCREEN SHARE")
                    }
                }
            },
            dismissButton = { TextButton(onClick = { showScreenQuality = false }) { Text("CANCEL") } }
        )
    }

    if (selectedMember != null) {
        val member = selectedMember!!
        Dialog(onDismissRequest = { selectedMember = null }) {
            Surface(color = Color(0xFF20272D), shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PartyMark(Modifier.size(38.dp))
                        Text(member.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 10.dp))
                        TextButton(onClick = { selectedMember = null }) { Text("Close", color = VoiceMuted) }
                    }
                    Text("Individual volume", color = VoiceMuted, fontSize = 11.sp)
                    Slider(value = selectedMemberVolume, onValueChange = {
                        selectedMemberVolume = it
                        PartyVoiceService.setRemoteVolume(context, member.id, it)
                    }, valueRange = 0f..1f)
                    Text("${(selectedMemberVolume * 100).toInt()}%", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Button(
                        onClick = {
                            val currentlyMuted = member.id in voiceState.mutedMembers
                            PartyVoiceService.setRemoteMuted(context, member.id, !currentlyMuted)
                            selectedMember = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34302E))
                    ) { Text(if (member.id in voiceState.mutedMembers || member.muted) "Unmute for me" else "Mute for me", color = Color(0xFFFFB58A)) }
                }
            }
        }
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
                            val ownerEndpoint = selectLeastLoadedVoiceServer()
                            val createJson = voiceRequest(ownerEndpoint, "/api/voice/rooms", "POST", payload)
                            val room = parseVoiceRoom(createJson).copy(serverUrl = ownerEndpoint)
                            if (room.voiceToken.isBlank()) throw IllegalStateException("Voice security token missing. Update the voice server.")
                            serverUrl = ownerEndpoint
                            joined = room
                            prefs.edit().putString("display_name", displayName).putString("room_id", room.id).putString("room_name", room.name).putString("room_visibility", room.visibility).putString("server_url", ownerEndpoint).putString("voice_token", room.voiceToken).apply()
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


@Composable
private fun PartyMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawCircle(Color(0xFF3B2C25), radius = size.minDimension * 0.48f)
        drawCircle(Color(0xFFF08A52), radius = size.minDimension * 0.39f, style = Stroke(width = size.minDimension * 0.055f))
        val star = Path().apply {
            moveTo(cx, cy - size.height * 0.25f)
            lineTo(cx + size.width * 0.055f, cy - size.height * 0.055f)
            lineTo(cx + size.width * 0.25f, cy)
            lineTo(cx + size.width * 0.055f, cy + size.height * 0.055f)
            lineTo(cx, cy + size.height * 0.25f)
            lineTo(cx - size.width * 0.055f, cy + size.height * 0.055f)
            lineTo(cx - size.width * 0.25f, cy)
            lineTo(cx - size.width * 0.055f, cy - size.height * 0.055f)
            close()
        }
        drawPath(star, Color(0xFFFFB58A))
        drawCircle(Color(0xFFFFD7C0), radius = size.minDimension * 0.055f)
    }
}

@Composable
private fun PartyGlyph(kind: String, modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = (size.minDimension * 0.085f).coerceAtLeast(1.5f))
        when (kind) {
            "mic", "mute" -> {
                drawRoundRect(color, Offset(w * .38f, h * .10f), Size(w * .24f, h * .48f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .12f))
                drawArc(color, 0f, 180f, false, Offset(w * .22f, h * .26f), Size(w * .56f, h * .48f), style = stroke)
                drawLine(color, Offset(w * .50f, h * .73f), Offset(w * .50f, h * .88f), stroke.width)
                drawLine(color, Offset(w * .34f, h * .90f), Offset(w * .66f, h * .90f), stroke.width)
                if (kind == "mute") drawLine(Color(0xFFFF8D83), Offset(w * .18f, h * .18f), Offset(w * .82f, h * .82f), stroke.width * 1.25f)
            }
            "screen", "expand" -> {
                drawRoundRect(color, Offset(w * .12f, h * .15f), Size(w * .76f, h * .58f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .08f), style = stroke)
                drawLine(color, Offset(w * .50f, h * .74f), Offset(w * .50f, h * .87f), stroke.width)
                drawLine(color, Offset(w * .32f, h * .88f), Offset(w * .68f, h * .88f), stroke.width)
                if (kind == "expand") {
                    drawLine(color, Offset(w * .25f, h * .33f), Offset(w * .25f, h * .22f), stroke.width)
                    drawLine(color, Offset(w * .25f, h * .22f), Offset(w * .38f, h * .22f), stroke.width)
                    drawLine(color, Offset(w * .75f, h * .55f), Offset(w * .75f, h * .66f), stroke.width)
                    drawLine(color, Offset(w * .75f, h * .66f), Offset(w * .62f, h * .66f), stroke.width)
                }
            }
            "speaker" -> {
                val p = Path().apply {
                    moveTo(w * .12f, h * .40f); lineTo(w * .32f, h * .40f); lineTo(w * .60f, h * .18f)
                    lineTo(w * .60f, h * .82f); lineTo(w * .32f, h * .60f); lineTo(w * .12f, h * .60f); close()
                }
                drawPath(p, color)
                drawArc(color, -48f, 96f, false, Offset(w * .42f, h * .26f), Size(w * .48f, h * .48f), style = stroke)
                drawArc(color, -48f, 96f, false, Offset(w * .52f, h * .12f), Size(w * .48f, h * .76f), style = stroke)
            }
            "infinity" -> {
                val p = Path().apply {
                    moveTo(w * .50f, h * .50f)
                    cubicTo(w * .28f, h * .18f, w * .10f, h * .28f, w * .10f, h * .50f)
                    cubicTo(w * .10f, h * .74f, w * .30f, h * .82f, w * .50f, h * .50f)
                    cubicTo(w * .70f, h * .18f, w * .90f, h * .28f, w * .90f, h * .50f)
                    cubicTo(w * .90f, h * .74f, w * .70f, h * .82f, w * .50f, h * .50f)
                }
                drawPath(p, color, style = Stroke(width = size.minDimension * .12f))
            }
            "leave" -> {
                drawRoundRect(color, Offset(w * .12f, h * .12f), Size(w * .55f, h * .76f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .07f), style = stroke)
                drawLine(color, Offset(w * .53f, h * .50f), Offset(w * .92f, h * .50f), stroke.width)
                drawLine(color, Offset(w * .75f, h * .32f), Offset(w * .92f, h * .50f), stroke.width)
                drawLine(color, Offset(w * .75f, h * .68f), Offset(w * .92f, h * .50f), stroke.width)
            }
            "spark" -> {
                val p = Path().apply {
                    moveTo(w * .50f, h * .06f); lineTo(w * .61f, h * .39f); lineTo(w * .94f, h * .50f)
                    lineTo(w * .61f, h * .61f); lineTo(w * .50f, h * .94f); lineTo(w * .39f, h * .61f)
                    lineTo(w * .06f, h * .50f); lineTo(w * .39f, h * .39f); close()
                }
                drawPath(p, color)
            }
        }
    }
}

@Composable
private fun PartyDockButton(
    caption: String,
    active: Boolean,
    glyph: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            color = if (active) Color(0xFF3A302A) else Color(0xFF293139),
            shape = CircleShape,
            modifier = Modifier.size(48.dp).clickable(onClick = onClick)
        ) {
            Box(contentAlignment = Alignment.Center) {
                PartyGlyph(glyph, Modifier.size(23.dp), when {
                    caption == "Leave" -> Color(0xFFFF9189)
                    active -> Color(0xFFFFB58A)
                    else -> Color.White
                })
            }
        }
        Text(caption, color = when {
            caption == "Leave" -> Color(0xFFFF9189)
            active -> Color(0xFFFFB58A)
            else -> VoiceMuted
        }, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PartyScreenVideo(
    track: VideoTrack,
    modifier: Modifier = Modifier,
    useSourceAspectRatio: Boolean = false
) {
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    // Both the lobby preview and viewer use FIT. The preview is always a compact
    // 16:9 viewport; portrait captures are letterboxed, never cropped or stretched.
    val eglContext = PartyVoiceService.rendererEglContext()

    if (eglContext == null) {
        Surface(
            color = Color(0xFF090D14),
            shape = RoundedCornerShape(11.dp),
            modifier = modifier
        ) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Preparing video renderer…", color = Color.White, fontSize = 11.sp)
            }
        }
        return
    }

    AndroidView(
        factory = { viewContext ->
            SurfaceViewRenderer(viewContext).apply {
                init(eglContext, null)
                // Let WebRTC's FIT transform own scaling; hardware scaler can crop on some devices.
                setEnableHardwareScaler(false)
                setMirror(false)
                setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                renderer = this
                Log.i("StreamForgeScreen", "Renderer initialized; trackState=${track.state()}")
            }
        },
        modifier = modifier,
        update = { view ->
            view.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            if (renderer !== view) renderer = view
        }
    )
    DisposableEffect(renderer, track) {
        val target = renderer
        if (target != null) {
            Log.i("StreamForgeScreen", "Attaching video sink; trackState=${track.state()}")
            track.addSink(target)
        }
        onDispose { if (target != null) track.removeSink(target) }
    }
    // Capture the renderer instance belonging to this effect. Reading the mutable
    // Compose state inside onDispose can release the *new* renderer during a state
    // transition (null -> SurfaceViewRenderer), which produces a brief frame then black.
    DisposableEffect(renderer) {
        val target = renderer
        onDispose { target?.release() }
    }
}
