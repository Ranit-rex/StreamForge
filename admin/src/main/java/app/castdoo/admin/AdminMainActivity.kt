package app.castdoo.admin

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.withContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val ADMIN_API = "https://streamforge-admin-api-y6ie.onrender.com"
private const val PREFS_NAME = "castdoo_admin_secure"
private val PRIVATE_ADMIN_PAIRING_CODE: String get() = BuildConfig.PRIVATE_ADMIN_PAIRING_CODE

class AdminMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val masterKey = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val prefs = EncryptedSharedPreferences.create(
            this, PREFS_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF55D6FF),
                background = Color(0xFF0B1018),
                surface = Color(0xFF151F2B)
            )) {
                AdminVersionGate {
                AdminRoot(
                    savedToken = prefs.getString("admin_device_token", "").orEmpty(),
                    saveToken = { prefs.edit().putString("admin_device_token", it).apply() },
                    clearToken = { prefs.edit().remove("admin_device_token").apply() }
                )
                }
            }
        }
    }
}


@Composable
private fun AdminVersionGate(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var retry by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var release by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(retry) {
        loading = true
        release = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val connection = (URL("$ADMIN_API/api/releases/admin").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 12000
                    requestMethod = "GET"
                }
                try {
                    if (connection.responseCode !in 200..299) null
                    else JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                } finally { connection.disconnect() }
            }.getOrNull()
        }
        loading = false
    }
    val minimum = release?.optLong("minimumVersionCode", 1) ?: Long.MAX_VALUE
    val blocked = !loading && release != null && minimum > BuildConfig.VERSION_CODE.toLong()
    val unavailable = !loading && release == null
    when {
        loading -> AdminGateScreen("Checking Admin app version…", showProgress = true)
        blocked -> {
            val url = release?.optString("updateUrl").orEmpty()
            AdminGateScreen(
                "Admin app update required. This version is no longer supported. Install the latest Admin APK to continue.",
                actionLabel = "Update Admin app",
                actionEnabled = url.startsWith("https://"),
                onAction = { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            )
        }
        unavailable -> AdminGateScreen(
            "Could not verify the Admin app version. Connect to the internet and try again.",
            actionLabel = "Retry",
            onAction = { retry++ }
        )
        else -> content()
    }
}

@Composable
private fun AdminGateScreen(
    message: String,
    showProgress: Boolean = false,
    actionLabel: String? = null,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null
) {
    Surface(Modifier.fillMaxSize(), color = Color(0xFF0B1018)) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("CASTDOO ADMIN", color = Color(0xFF55D6FF), fontSize = 13.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(14.dp))
            Text(if (showProgress) "Please wait" else "Update required", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(message, color = Color(0xFF9BAABD), fontSize = 14.sp)
            if (showProgress) {
                Spacer(Modifier.height(20.dp))
                CircularProgressIndicator()
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(22.dp))
                Button(onClick = onAction, enabled = actionEnabled, modifier = Modifier.fillMaxWidth()) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun AdminRoot(savedToken: String, saveToken: (String) -> Unit, clearToken: () -> Unit) {
    var token by remember { mutableStateOf(savedToken) }
    var section by remember { mutableStateOf("overview") }
    var pairError by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf(false) }
    var pairAttempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(pairAttempt) {
        if (token.isBlank()) {
            pairing = true
            pairError = ""
            pairAdminDevice(PRIVATE_ADMIN_PAIRING_CODE) { ok, data ->
                pairing = false
                if (ok) {
                    val newToken = data.optString("token")
                    if (newToken.isNotBlank()) {
                        saveToken(newToken)
                        token = newToken
                    } else {
                        pairError = "The server did not return a device session."
                    }
                } else {
                    pairError = data.optString("error", "Private admin connection failed")
                }
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = Color(0xFF0B1018)) {
        if (token.isBlank()) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("CASTDOO", color = Color(0xFF55D6FF), fontSize = 14.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(10.dp))
                Text("Private Admin", color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (pairing) "Connecting this private device securely…" else "The admin service could not be reached.",
                    color = Color(0xFF9BAABD), fontSize = 14.sp, lineHeight = 21.sp
                )
                if (pairError.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(pairError, color = Color(0xFFFF8585), fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { pairAttempt++ }, modifier = Modifier.fillMaxWidth()) {
                        Text("Retry connection")
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("CASTDOO ADMIN", color = Color(0xFF55D6FF), fontSize = 12.sp, fontWeight = FontWeight.Black)
                        Text("Control Center", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("PRIVATE DEVICE", color = Color(0xFF55D6A1), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("overview" to "Overview", "push" to "Push", "versions" to "Versions").forEach { (key, label) ->
                        FilterChip(selected = section == key, onClick = { section = key }, label = { Text(label) })
                    }
                }
                Spacer(Modifier.height(18.dp))
                when (section) {
                    "push" -> PushComposer(token)
                    "versions" -> ReleaseManager(token)
                    else -> {
                        AdminCard("Push notifications", "Send phone notifications to all registered devices or selected users.")
                        AdminCard("Stable releases", "Manage the public stable Android release.")
                        AdminCard("Device security", "This private installation uses a protected device session.")
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth().padding(bottom = 10.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF151F2B)), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(5.dp))
            Text(body, color = Color(0xFF9BAABD), fontSize = 13.sp)
        }
    }
}

@Composable
private fun PushComposer(token: String) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("all") }
    var userIds by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("normal") }
    var actionUrl by remember { mutableStateOf("") }
    var resultText by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var registeredDevices by remember { mutableIntStateOf(-1) }
    var pushConfigured by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Checking registered devices…") }
    var resultColor by remember { mutableStateOf(Color(0xFF55D6A1)) }
    LaunchedEffect(token) {
        adminRequest(token, "GET", "/api/push/status", JSONObject()) { ok, data ->
            if (ok) {
                registeredDevices = data.optInt("registeredDevices", 0)
                pushConfigured = data.optBoolean("configured", false)
                statusMessage = if (pushConfigured) "App users: ${data.opt("totalUsers").let { if (it == JSONObject.NULL) "unknown" else it.toString() }} · Push users: ${data.optInt("registeredUsers", 0)} · Devices: $registeredDevices · FCM: ${data.optInt("fcmDevices", 0)}" else "Firebase Admin credentials are missing on the server; device registration cannot be verified."
            } else statusMessage = data.optString("error", "Could not check push status")
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Send push notification", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(statusMessage, color = if (pushConfigured && registeredDevices > 0) Color(0xFF55D6A1) else Color(0xFFFFC56B), fontSize = 12.sp)
        OutlinedTextField(title, { title = it }, label = { Text("Notification title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(body, { body = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(target == "all", { target = "all" }, label = { Text("All users") })
            FilterChip(target == "selected", { target = "selected" }, label = { Text("Selected users") })
        }
        if (target == "selected") OutlinedTextField(userIds, { userIds = it }, label = { Text("User UIDs (comma separated)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        OutlinedTextField(actionUrl, { actionUrl = it }, label = { Text("Optional HTTPS action URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(priority == "normal", { priority = "normal" }, label = { Text("Normal") })
            FilterChip(priority == "important", { priority = "important" }, label = { Text("Important") })
        }
        Button(
            enabled = !sending && title.isNotBlank() && body.isNotBlank() && (target == "all" || userIds.isNotBlank()),
            onClick = {
                sending = true
                resultText = ""
                val ids = userIds.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                val json = JSONObject().put("title", title.trim()).put("message", body.trim())
                    .put("audience", target).put("priority", priority).put("targetUids", org.json.JSONArray(ids))
                    .put("actionUrl", actionUrl.trim())
                adminRequest(token, "POST", "/api/push/send", json) { ok, data ->
                    resultText = if (ok) "Sent: ${data.optInt("sent")} · Failed: ${data.optInt("failed")} · Devices: ${data.optInt("devices")}" else data.optString("error", "Send failed")
                    resultColor = if (ok && data.optInt("sent") > 0) Color(0xFF55D6A1) else Color(0xFFFFC56B)
                    if (ok) {
                        registeredDevices = data.optInt("devices")
                        statusMessage = if (registeredDevices == 0) "No registered devices. Open CastDoo on a signed-in phone with notifications enabled." else "Push attempt completed for $registeredDevices device(s)."
                    }
                    sending = false
                }
            }, modifier = Modifier.fillMaxWidth()
        ) { Text(if (sending) "Sending…" else "Send notification") }
        if (resultText.isNotBlank()) Text(resultText, color = resultColor, fontSize = 13.sp)
    }
}

@Composable
private fun ReleaseManager(token: String) {
    var versionCode by remember { mutableStateOf("1088") }
    var minimumVersionCode by remember { mutableStateOf("1") }
    var versionName by remember { mutableStateOf("Alpha build 1088") }
    var updateUrl by remember { mutableStateOf("https://github.com/Ranit-rex/StreamForge/releases/download/castdoo-stable-build-1088/CastDoo-Stable-build-1088.apk") }
    var notes by remember { mutableStateOf("") }
    var resultText by remember { mutableStateOf("") }
    var publishing by remember { mutableStateOf(false) }
    var currentRelease by remember { mutableStateOf("Loading current stable release…") }
    LaunchedEffect(token) {
        adminRequest(token, "GET", "/api/releases/stable", JSONObject()) { ok, data ->
            if (ok) {
                versionName = data.optString("versionName", "Alpha build 1088")
                versionCode = data.optInt("versionCode", 1088).toString()
                minimumVersionCode = data.optInt("minimumVersionCode", 1).toString()
                updateUrl = data.optString("updateUrl", updateUrl).ifBlank { updateUrl }
                notes = data.optString("releaseNotes", "")
                currentRelease = "Published stable: ${data.optString("versionName")} · build ${data.optInt("versionCode")} · minimum ${data.optInt("minimumVersionCode")}"
                resultText = ""
            } else currentRelease = data.optString("error", "Could not load current release")
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Release management", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(currentRelease, color = Color(0xFF55D6A1), fontSize = 12.sp)
        OutlinedTextField(versionName, { versionName = it }, label = { Text("Version name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(versionCode, { versionCode = it }, label = { Text("Version code") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(minimumVersionCode, { minimumVersionCode = it }, label = { Text("Minimum supported build") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(updateUrl, { updateUrl = it }, label = { Text("APK / Play Store update URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(notes, { notes = it }, label = { Text("Release notes") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        Button(
            enabled = !publishing && versionCode.toIntOrNull() != null && minimumVersionCode.toIntOrNull() != null && updateUrl.startsWith("https://"),
            onClick = {
                publishing = true
                val json = JSONObject().put("versionCode", versionCode.toIntOrNull() ?: 1)
                    .put("minimumVersionCode", minimumVersionCode.toIntOrNull() ?: 1)
                    .put("versionName", versionName.trim()).put("updateUrl", updateUrl.trim())
                    .put("releaseNotes", notes.trim()).put("forceUpdate", false)
                adminRequest(token, "POST", "/api/releases/stable", json) { ok, data ->
                    resultText = if (ok) "Published successfully · build ${data.optInt("versionCode")}" else data.optString("error", "Publish failed")
                    if (ok) currentRelease = "Published stable: ${data.optString("versionName")} · build ${data.optInt("versionCode")} · minimum ${data.optInt("minimumVersionCode")}"
                    publishing = false
                }
            }, modifier = Modifier.fillMaxWidth()
        ) { Text(if (publishing) "Publishing…" else "Publish release") }
        if (resultText.isNotBlank()) Text(resultText, color = if (resultText.startsWith("Published successfully")) Color(0xFF55D6A1) else Color(0xFFFF8585), fontSize = 13.sp)
    }
}

private fun pairAdminDevice(code: String, done: (Boolean, JSONObject) -> Unit) {
    Thread {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("$ADMIN_API/api/admin/pair").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 15000; readTimeout = 20000
                setRequestProperty("Content-Type", "application/json"); doOutput = true
            }
            val payload = JSONObject().put("code", code).put("label", "CastDoo Admin Android")
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            val data = runCatching { JSONObject(text) }.getOrElse { JSONObject().put("error", "Invalid server response") }
            Handler(Looper.getMainLooper()).post { done(status in 200..299, data) }
        } catch (error: Exception) {
            Handler(Looper.getMainLooper()).post { done(false, JSONObject().put("error", error.message ?: "Network request failed")) }
        } finally { connection?.disconnect() }
    }.start()
}

private fun adminRequest(token: String, method: String, path: String, payload: JSONObject, done: (Boolean, JSONObject) -> Unit) {
    Thread {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(ADMIN_API + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method; connectTimeout = 15000; readTimeout = 20000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                doOutput = method != "GET"
            }
            if (method != "GET") connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            val data = runCatching { JSONObject(text) }.getOrElse { JSONObject().put("error", "Invalid server response") }
            Handler(Looper.getMainLooper()).post { done(code in 200..299, data) }
        } catch (error: Exception) {
            Handler(Looper.getMainLooper()).post { done(false, JSONObject().put("error", error.message ?: "Network request failed")) }
        } finally { connection?.disconnect() }
    }.start()
}
