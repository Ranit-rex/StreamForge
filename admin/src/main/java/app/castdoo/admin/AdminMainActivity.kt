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


private const val ADMIN_RELEASE_PREFS = "castdoo_admin_release_policy"
private const val ADMIN_RELEASE_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L

@Composable
private fun AdminVersionGate(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val releasePrefs = remember(context) {
        context.getSharedPreferences(ADMIN_RELEASE_PREFS, android.content.Context.MODE_PRIVATE)
    }
    var release by remember(context) {
        mutableStateOf(
            releasePrefs.getString("policy_cache", null)?.let { raw ->
                runCatching { JSONObject(raw) }.getOrNull()
            }
        )
    }
    var continueWithoutUpdate by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val lastAttempt = releasePrefs.getLong("last_attempt_at", 0L)
        val now = System.currentTimeMillis()
        // One request per day, including when the API is temporarily unavailable.
        if (lastAttempt == 0L || now - lastAttempt >= ADMIN_RELEASE_CHECK_INTERVAL_MS) {
            releasePrefs.edit().putLong("last_attempt_at", now).apply()
            val loaded = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val connection = (URL("$ADMIN_API/api/releases/admin").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 30000
                        requestMethod = "GET"
                    }
                    try {
                        if (connection.responseCode !in 200..299) null
                        else JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    } finally {
                        connection.disconnect()
                    }
                }.getOrNull()
            }
            if (loaded != null && loaded.optLong("versionCode", 0L) > 0L) {
                releasePrefs.edit()
                    .putString("policy_cache", loaded.toString())
                    .putLong("last_success_at", System.currentTimeMillis())
                    .apply()
                release = loaded
            }
        }
    }

    val current = release
    val installedCode = BuildConfig.VERSION_CODE.toLong()
    val latestCode = current?.optLong("versionCode", 0L) ?: 0L
    val minimumCode = current?.optLong("minimumVersionCode", 1L) ?: 1L
    val optionalUpdate = current != null && latestCode > installedCode
    val updateType = current?.let { it.optString("updateType", if (it.optBoolean("forceUpdate", false)) "major" else "mini") } ?: "mini"
    val mandatory = current != null && (
        minimumCode > installedCode ||
            (optionalUpdate && (updateType == "major" || current.optBoolean("forceUpdate", false)))
        )
    val updateUrl = current?.optString("updateUrl").orEmpty()
    val validUrl = runCatching {
        val uri = android.net.Uri.parse(updateUrl)
        uri.scheme == "https" && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    when {
        mandatory -> AdminGateScreen(
            message = "This Admin build is no longer supported. Install the latest version to continue.",
            versionName = current!!.optString("versionName", "Admin update"),
            versionCode = latestCode,
            releaseNotes = current!!.optString("releaseNotes", "Security, stability and performance improvements."),
            mandatory = true,
            actionLabel = "Download Admin update",
            actionEnabled = validUrl,
            onAction = { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(current!!.optString("updateUrl")))) }
        )
        optionalUpdate && !continueWithoutUpdate -> AdminGateScreen(
            message = "A newer Admin app version is available.",
            versionName = current!!.optString("versionName", "Admin update"),
            versionCode = latestCode,
            releaseNotes = current!!.optString("releaseNotes", "Security, stability and performance improvements."),
            mandatory = false,
            actionLabel = "Download Admin update",
            actionEnabled = validUrl,
            onAction = { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(current!!.optString("updateUrl")))) },
            onContinue = { continueWithoutUpdate = true }
        )
        // Cached policy remains active offline. A first launch without cache stays usable.
        else -> content()
    }
}

@Composable
private fun AdminGateScreen(
    message: String,
    versionName: String = "",
    versionCode: Long = 0,
    releaseNotes: String = "",
    mandatory: Boolean = true,
    actionLabel: String? = null,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
    onContinue: (() -> Unit)? = null
) {
    Surface(Modifier.fillMaxSize(), color = Color(0xFF0B1018)) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("CASTDOO ADMIN", color = Color(0xFF55D6FF), fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Spacer(Modifier.height(18.dp))
            Text(if (mandatory) "Update required" else "Update available", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(message, color = Color(0xFF9BAABD), fontSize = 14.sp, lineHeight = 21.sp)
            if (versionName.isNotBlank()) {
                Spacer(Modifier.height(22.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151F2B)), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("UPDATE DETAILS", color = Color(0xFF9BAABD), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(versionName, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Version code  $versionCode", color = Color(0xFF55D6FF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Divider(color = Color(0xFF263445))
                        Text("WHAT'S NEW", color = Color(0xFF9BAABD), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(releaseNotes, color = Color(0xFFE1EAF4), fontSize = 14.sp, lineHeight = 21.sp)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction, enabled = actionEnabled, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(actionLabel) }
            }
            if (!actionEnabled && actionLabel != null) {
                Spacer(Modifier.height(8.dp))
                Text("Update link is not available yet.", color = Color(0xFFFFC56B), fontSize = 12.sp)
            }
            if (!mandatory && onContinue != null) {
                TextButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue without updating", color = Color(0xFF9BAABD)) }
            } else if (mandatory) {
                Spacer(Modifier.height(8.dp))
                Text("This update is required. You cannot skip it.", color = Color(0xFF9BAABD), fontSize = 11.sp)
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
                        AdminCard("Stable + Beta releases", "Publish Live and Test Android updates from this private Admin app.")
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
    var selectedTrack by remember { mutableStateOf("stable") }
    var versionCode by remember { mutableStateOf("") }
    var minimumVersionCode by remember { mutableStateOf("1") }
    var versionName by remember { mutableStateOf("") }
    var updateUrl by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var updateType by remember { mutableStateOf("mini") }
    var resultText by remember { mutableStateOf("") }
    var publishing by remember { mutableStateOf(false) }
    var currentRelease by remember { mutableStateOf("Loading current Stable release…") }
    var adminMinimumVersion by remember { mutableStateOf("1") }
    var adminUpdateUrl by remember { mutableStateOf("") }
    var adminUpdateType by remember { mutableStateOf("mini") }
    var adminReleaseStatus by remember { mutableStateOf("Loading Admin app policy…") }
    var publishingAdminPolicy by remember { mutableStateOf(false) }

    LaunchedEffect(token, selectedTrack) {
        val requestedTrack = selectedTrack
        currentRelease = "Loading current ${if (requestedTrack == "stable") "Stable · Live" else "Beta · Test"} release…"
        resultText = ""
        adminRequest(token, "GET", "/api/releases/$requestedTrack", JSONObject()) { ok, data ->
            if (selectedTrack == requestedTrack) {
                if (ok) {
                    val url = data.optString("updateUrl", "")
                    versionName = data.optString("versionName", "")
                    versionCode = if (url.isBlank()) "" else data.optInt("versionCode", 0).takeIf { it > 0 }?.toString().orEmpty()
                    minimumVersionCode = data.optInt("minimumVersionCode", 1).toString()
                    updateUrl = url
                    notes = data.optString("releaseNotes", "")
                    updateType = data.optString("updateType", if (data.optBoolean("forceUpdate", false)) "major" else "mini")
                    currentRelease = if (url.isBlank()) {
                        "No published ${requestedTrack.uppercase()} release yet. Enter the APK build details below."
                    } else {
                        "Published ${requestedTrack.uppercase()}: ${data.optString("versionName")} · build ${data.optInt("versionCode")} · minimum ${data.optInt("minimumVersionCode")}"
                    }
                } else currentRelease = data.optString("error", "Could not load current release")
            }
        }
    }

    LaunchedEffect(token) {
        adminRequest(token, "GET", "/api/releases/admin", JSONObject()) { ok, data ->
            if (ok) {
                adminMinimumVersion = data.optInt("minimumVersionCode", 1).toString()
                adminUpdateUrl = data.optString("updateUrl", "")
                adminUpdateType = data.optString("updateType", if (data.optBoolean("forceUpdate", false)) "major" else "mini")
                adminReleaseStatus = "Admin policy · minimum build ${data.optInt("minimumVersionCode", 1)}"
            } else adminReleaseStatus = data.optString("error", "Could not load Admin app policy")
        }
    }

    val parsedVersionCode = versionCode.toIntOrNull()
    val parsedMinimumCode = minimumVersionCode.toIntOrNull()
    val validReleaseNumbers = parsedVersionCode != null && parsedMinimumCode != null &&
        parsedVersionCode > 0 && parsedMinimumCode > 0 && parsedMinimumCode <= parsedVersionCode

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Release management", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Choose the Live or Test release feed. Both channels update the same CastDoo app package.", color = Color(0xFF9BAABD), fontSize = 12.sp, lineHeight = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = selectedTrack == "stable", onClick = { selectedTrack = "stable" }, label = { Text("Stable · Live") })
            FilterChip(selected = selectedTrack == "beta", onClick = { selectedTrack = "beta" }, label = { Text("Beta · Test") })
        }
        Text(currentRelease, color = Color(0xFF55D6A1), fontSize = 12.sp)
        OutlinedTextField(versionName, { versionName = it }, label = { Text("Version name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(versionCode, { versionCode = it }, label = { Text("Version code from APK build") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(minimumVersionCode, { minimumVersionCode = it }, label = { Text("Minimum supported build") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(updateUrl, { updateUrl = it }, label = { Text("APK HTTPS download URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text("Update type", color = Color(0xFF9BAABD), fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = updateType == "major", onClick = { updateType = "major" }, label = { Text("Major · Mandatory") })
            FilterChip(selected = updateType == "mini", onClick = { updateType = "mini" }, label = { Text("Mini · Optional") })
        }
        OutlinedTextField(notes, { notes = it }, label = { Text("Release notes") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        Button(
            enabled = !publishing && validReleaseNumbers && updateUrl.startsWith("https://") && versionName.isNotBlank(),
            onClick = {
                publishing = true
                val json = JSONObject()
                    .put("versionCode", parsedVersionCode ?: 1)
                    .put("minimumVersionCode", parsedMinimumCode ?: 1)
                    .put("versionName", versionName.trim())
                    .put("updateUrl", updateUrl.trim())
                    .put("releaseNotes", notes.trim())
                    .put("updateType", updateType)
                    .put("forceUpdate", updateType == "major")
                val publishingTrack = selectedTrack
                adminRequest(token, "POST", "/api/releases/$publishingTrack", json) { ok, data ->
                    resultText = if (ok) "Published successfully · build ${data.optInt("versionCode")}" else data.optString("error", "Publish failed")
                    if (ok) currentRelease = "Published ${publishingTrack.uppercase()}: ${data.optString("versionName")} · build ${data.optInt("versionCode")} · minimum ${data.optInt("minimumVersionCode")}"
                    publishing = false
                }
            }, modifier = Modifier.fillMaxWidth()
        ) { Text(if (publishing) "Publishing…" else "Publish ${selectedTrack.uppercase()} release") }
        if (resultText.isNotBlank()) Text(resultText, color = if (resultText.startsWith("Published successfully")) Color(0xFF55D6A1) else Color(0xFFFF8585), fontSize = 13.sp)

        Spacer(Modifier.height(18.dp))
        Divider(color = Color(0xFF263445))
        Text("Admin app version lock", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(adminReleaseStatus, color = Color(0xFF55D6A1), fontSize = 12.sp)
        OutlinedTextField(adminMinimumVersion, { adminMinimumVersion = it }, label = { Text("Admin minimum supported build") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(adminUpdateUrl, { adminUpdateUrl = it }, label = { Text("Latest Admin APK HTTPS URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text("Admin update type", color = Color(0xFF9BAABD), fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = adminUpdateType == "major", onClick = { adminUpdateType = "major" }, label = { Text("Major update") })
            FilterChip(selected = adminUpdateType == "mini", onClick = { adminUpdateType = "mini" }, label = { Text("Mini update") })
        }
        val adminMinimum = adminMinimumVersion.toIntOrNull()
        Button(
            enabled = !publishingAdminPolicy && adminMinimum != null && adminMinimum > 0 &&
                adminMinimum <= BuildConfig.VERSION_CODE && adminUpdateUrl.startsWith("https://"),
            onClick = {
                publishingAdminPolicy = true
                val currentAdminCode = BuildConfig.VERSION_CODE
                val json = JSONObject()
                    .put("versionCode", currentAdminCode)
                    .put("minimumVersionCode", adminMinimum ?: 1)
                    .put("versionName", "Admin build $currentAdminCode")
                    .put("updateUrl", adminUpdateUrl.trim())
                    .put("releaseNotes", "CastDoo Admin app update")
                    .put("updateType", adminUpdateType)
                    .put("forceUpdate", adminUpdateType == "major")
                adminRequest(token, "POST", "/api/releases/admin", json) { ok, data ->
                    adminReleaseStatus = if (ok) "Admin policy published · minimum build ${data.optInt("minimumVersionCode")}" else data.optString("error", "Admin policy publish failed")
                    publishingAdminPolicy = false
                }
            }, modifier = Modifier.fillMaxWidth()
        ) { Text(if (publishingAdminPolicy) "Publishing…" else "Publish Admin version policy") }
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
