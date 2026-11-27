package app.streamforge.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
                        minimumVersionCode = json.optLong("minimumVersionCode", 1),
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
    val updateUrlAvailable = current?.updateUrl?.startsWith("https://") == true

    when {
        !checked -> ReleaseCheckingScreen()
        forceUpdate -> {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Update required", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(14.dp))
                    Text("This version of CastDoo is no longer supported. Update the app to continue.")
                    if (!current?.versionName.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Latest version: ${current?.versionName}")
                    }
                    if (current?.releaseNotes?.isNotBlank() == true) {
                        Spacer(Modifier.height(14.dp))
                        Text(current.releaseNotes)
                    }
                    Spacer(Modifier.height(24.dp))
                    Button(
                        enabled = updateUrlAvailable,
                        onClick = {
                            current?.updateUrl?.let { url ->
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        }
                    ) { Text("Update now") }
                    if (!updateUrlAvailable) {
                        Spacer(Modifier.height(8.dp))
                        Text("Update link is not available. Please try again later.")
                    }
                }
            }
        }
        else -> {
            content()
            if (updateAvailable && !dismissed) {
                AlertDialog(
                    onDismissRequest = { dismissed = true },
                    title = { Text("Update available") },
                    text = {
                        Text(buildString {
                            append(current?.versionName ?: "A new version")
                            if (current?.releaseNotes?.isNotBlank() == true) {
                                append("\n\n")
                                append(current.releaseNotes)
                            }
                        })
                    },
                    confirmButton = {
                        Button(
                            enabled = updateUrlAvailable,
                            onClick = {
                                current?.updateUrl?.let { url ->
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }
                            }
                        ) { Text("Update now") }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { dismissed = true }) { Text("Later") }
                    }
                )
            }
        }
    }
}

@Composable
private fun ReleaseCheckingScreen() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Checking for updates…")
        }
    }
}
