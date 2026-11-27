package app.streamforge.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import app.streamforge.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private data class ReleaseInfo(
    val latestVersionCode: Long,
    val minimumVersionCode: Long,
    val versionName: String,
    val updateUrl: String,
    val releaseNotes: String
)

private const val CASTDOO_ADMIN_API = "https://streamforge-admin-api-y6ie.onrender.com"

@Composable
fun CastDooReleaseGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var release by remember { mutableStateOf<ReleaseInfo?>(null) }
    var checked by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }

    LaunchedEffect(BuildConfig.APP_TRACK) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL("$CASTDOO_ADMIN_API/api/releases/${BuildConfig.APP_TRACK}").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 10000
                    connection.readTimeout = 12000
                    connection.requestMethod = "GET"
                    if (connection.responseCode !in 200..299) return@runCatching null
                    val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    ReleaseInfo(
                        latestVersionCode = json.optLong("versionCode", 0),
                        minimumVersionCode = json.optLong("minimumVersionCode", 0),
                        versionName = json.optString("versionName"),
                        updateUrl = json.optString("updateUrl"),
                        releaseNotes = json.optString("releaseNotes")
                    )
                } finally { connection.disconnect() }
            }.getOrNull()
        }
        release = loaded
        checked = true
    }

    val current = release
    val currentCode = BuildConfig.VERSION_CODE.toLong()
    val updateAvailable = current != null && current.latestVersionCode > currentCode
    val forceUpdate = current != null && current.minimumVersionCode > currentCode

    content()

    if (checked && updateAvailable && (!dismissed || forceUpdate)) {
        AlertDialog(
            onDismissRequest = { if (!forceUpdate) dismissed = true },
            title = { Text(if (forceUpdate) "Update required" else "Update available") },
            text = {
                Text(buildString {
                    append(current?.versionName ?: "A new version")
                    if (current?.releaseNotes?.isNotBlank() == true) {
                        append("\n\n")
                        append(current.releaseNotes)
                    }
                    if (forceUpdate) append("\n\nThis version is no longer supported.")
                })
            },
            confirmButton = {
                Button(
                    enabled = current?.updateUrl?.startsWith("https://") == true,
                    onClick = {
                        current?.updateUrl?.let { url ->
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    }
                ) { Text("Update now") }
            },
            dismissButton = {
                if (!forceUpdate) OutlinedButton(onClick = { dismissed = true }) { Text("Later") }
            }
        )
    }
}
