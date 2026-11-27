package app.streamforge.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val VERSION_API = "https://castdoo-version-api.mranit66.workers.dev/api/releases/stable"
private const val VERSION_PREFS = "streamforge_version_policy"

private data class AppRelease(
    val latestCode: Long,
    val minimumCode: Long,
    val name: String,
    val url: String,
    val notes: String,
    val mandatory: Boolean
)

private fun parseAppRelease(response: JSONObject): AppRelease? {
    val json = response.optJSONObject("policy") ?: response
    val latest = json.optLong("versionCode", json.optLong("latestVersionCode", 0L))
    if (latest <= 0L) return null
    val rawUrl = json.optString("updateUrl", "").trim()
    val safeUrl = runCatching {
        val uri = Uri.parse(rawUrl)
        if (uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()) rawUrl else ""
    }.getOrDefault("")
    return AppRelease(
        latestCode = latest,
        minimumCode = json.optLong("minimumVersionCode", json.optLong("minimumSupportedVersionCode", 1L)).coerceAtLeast(1L),
        name = json.optString("versionName", "StreamForge update"),
        url = safeUrl.ifBlank { "https://github.com/Ranit-rex/StreamForge/releases" },
        notes = json.optString("releaseNotes", ""),
        mandatory = json.optBoolean("forceUpdate", json.optBoolean("isMandatory", false))
    )
}

private fun readCachedRelease(context: Context): AppRelease? {
    val raw = context.getSharedPreferences(VERSION_PREFS, Context.MODE_PRIVATE)
        .getString("stable_policy", null) ?: return null
    return runCatching { parseAppRelease(JSONObject(raw)) }.getOrNull()
}

private suspend fun fetchAppRelease(): AppRelease? = withContext(Dispatchers.IO) {
    runCatching {
        val connection = URL(VERSION_API).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 12000
            connection.readTimeout = 18000
            connection.requestMethod = "GET"
            if (connection.responseCode !in 200..299) return@runCatching null
            parseAppRelease(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

@Composable
fun CastDooReleaseGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var release by remember { mutableStateOf(readCachedRelease(context)) }
    var continueAnyway by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences(VERSION_PREFS, Context.MODE_PRIVATE)
        // No cooldown: check the stable release policy on every app launch.
        // Keep the last successful policy cached so offline launches still enforce it.
        val fresh = fetchAppRelease()
        if (fresh != null) {
            prefs.edit()
                .putString("stable_policy", JSONObject()
                    .put("versionCode", fresh.latestCode)
                    .put("minimumVersionCode", fresh.minimumCode)
                    .put("versionName", fresh.name)
                    .put("updateUrl", fresh.url)
                    .put("releaseNotes", fresh.notes)
                    .put("forceUpdate", fresh.mandatory)
                    .toString())
                .remove("last_success")
                .apply()
            release = fresh
        }
    }

    val installedCode = remember(context) {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        }.getOrDefault(0L)
    }
    val current = release
    val newer = current != null && current.latestCode > installedCode
    val mustUpdate = current != null && (current.minimumCode > installedCode || (newer && current.mandatory))
    val validUrl = current?.let {
        runCatching { Uri.parse(it.url).let { uri -> uri.scheme == "https" && !uri.host.isNullOrBlank() } }.getOrDefault(false)
    } ?: false

    if (current != null && (mustUpdate || (newer && !continueAnyway))) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF0A101B)) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.Start
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("STREAMFORGE", color = Color(0xFF53D7FF), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
                    Text(
                        if (mustUpdate) "Update required to continue" else "A new version is available",
                        color = Color.White, fontSize = 27.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (mustUpdate) "This installed version is no longer supported. Install the latest version to keep using the app."
                        else "Install the latest version for improvements and fixes.",
                        color = Color(0xFFA8B6C8), fontSize = 14.sp, lineHeight = 21.sp
                    )
                    Surface(color = Color(0xFF121E2D), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("LATEST VERSION", color = Color(0xFFA8B6C8), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                            Text(current.name.ifBlank { "StreamForge update" }, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text("Version code: ${current.latestCode}", color = Color(0xFF53D7FF), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            if (current.notes.isNotBlank()) Text(current.notes, color = Color(0xFFE5EDF6), fontSize = 13.sp, lineHeight = 19.sp)
                            Text("Installed version code: $installedCode", color = Color(0xFFA8B6C8), fontSize = 11.sp)
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(current.url))) },
                        enabled = validUrl,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF53D7FF), contentColor = Color(0xFF06121C))
                    ) { Text("Download update", fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                    if (!mustUpdate) {
                        TextButton(onClick = { continueAnyway = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Continue without updating", color = Color(0xFFA8B6C8))
                        }
                    } else {
                        Text("Updating is required. You cannot skip this version.", color = Color(0xFFA8B6C8), fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                }
            }
        }
    } else {
        content()
    }
}
