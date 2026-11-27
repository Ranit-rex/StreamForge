package app.streamforge.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.google.firebase.firestore.FirebaseFirestore
import app.streamforge.BuildConfig

private data class ReleaseInfo(
    val latestVersionCode: Long,
    val minimumVersionCode: Long,
    val versionName: String,
    val updateUrl: String,
    val releaseNotes: String
)

@Composable
fun CastDooReleaseGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var release by remember { mutableStateOf<ReleaseInfo?>(null) }
    var checked by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }

    LaunchedEffect(BuildConfig.APP_TRACK) {
        FirebaseFirestore.getInstance().collection("appReleases")
            .document(BuildConfig.APP_TRACK)
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists() && snapshot.getBoolean("enabled") != false) {
                    release = ReleaseInfo(
                        latestVersionCode = snapshot.getLong("versionCode") ?: 0L,
                        minimumVersionCode = snapshot.getLong("minimumVersionCode") ?: 0L,
                        versionName = snapshot.getString("versionName").orEmpty(),
                        updateUrl = snapshot.getString("updateUrl").orEmpty(),
                        releaseNotes = snapshot.getString("releaseNotes").orEmpty()
                    )
                }
                checked = true
            }
            .addOnFailureListener { checked = true }
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
