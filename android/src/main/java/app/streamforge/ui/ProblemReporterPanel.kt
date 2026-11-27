package app.streamforge.ui

import android.content.Intent
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import com.google.firebase.auth.FirebaseAuth
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.streamforge.stream.StreamService

private val ReportPanel = Color(0xFF121923)
private val ReportMuted = Color(0xFF91A0B4)
private val ReportAccent = Color(0xFF9DB4FF)

/**
 * Creates a user-controlled diagnostic summary. Nothing is uploaded automatically;
 * the Android Sharesheet lets the user review the report and choose where to send it.
 */
@Composable
fun ProblemReporterPanel() {
    val context = LocalContext.current
    var showReport by remember { mutableStateOf(false) }
    var description by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }
    val version = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")
    val report = buildString {
        appendLine("CastDoo diagnostic report")
        appendLine("App version: $version")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("App package: ${context.packageName}")
        appendLine("Streaming: ${StreamService.streaming.value}")
        appendLine("Recording: ${StreamService.recording.value}")
        appendLine("Status: ${StreamService.status.value}")
        appendLine("Output: ${StreamService.streamResolution.value} @ ${StreamService.streamFps.value} FPS")
        appendLine("Current bitrate: ${StreamService.bitrateKbps.value} kbps")
        appendLine("Network: ${StreamService.networkHealth.value}")
        appendLine()
        appendLine("Please describe what happened and include the steps to reproduce.")
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("SUPPORT & DIAGNOSTICS", color = ReportAccent, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.6.sp)
        Text("Report a problem", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text("Send a private diagnostic report to the CastDoo support inbox when streaming fails, preview turns blank, audio stops, or the app behaves unexpectedly.", color = ReportMuted, fontSize = 12.sp)
        Surface(color = ReportPanel, shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("DEVICE DIAGNOSTICS", color = ReportAccent, fontSize = 10.sp, fontWeight = FontWeight.Black)
                DiagnosticLine("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
                DiagnosticLine("Android", "${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}")
                DiagnosticLine("App version", version)
                DiagnosticLine("Stream state", if (StreamService.streaming.value) "LIVE" else "Not streaming")
                DiagnosticLine("Recording", if (StreamService.recording.value) "Active" else "Off")
                DiagnosticLine("Output", "${StreamService.streamResolution.value} · ${StreamService.streamFps.value} FPS")
                DiagnosticLine("Bitrate", "${StreamService.bitrateKbps.value} kbps")
                DiagnosticLine("Network", StreamService.networkHealth.value)
                DiagnosticLine("Last status", StreamService.status.value)
            }
        }
        Button(
            onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "CastDoo diagnostic report")
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                context.startActivity(Intent.createChooser(send, "Share StreamForge report"))
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) { Text("SHARE DIAGNOSTIC REPORT", fontWeight = FontWeight.Bold) }
        Text("Nothing is sent automatically. Review the report and choose your email or support channel in Android's share menu.", color = ReportMuted, fontSize = 10.sp)
        Button(
            onClick = { showReport = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) { Text("SEND REPORT TO CASTDOO") }
        if (resultMessage.isNotBlank()) Text(resultMessage, color = ReportMuted, fontSize = 11.sp)
        if (showReport) AlertDialog(
            onDismissRequest = { if (!submitting) showReport = false },
            title = { Text("Report a problem") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Describe what happened. Your device and stream diagnostics will be attached.", color = ReportMuted, fontSize = 12.sp)
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it.take(4000) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        label = { Text("What went wrong?") }
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = !submitting && description.trim().length >= 10, onClick = {
                    val current = FirebaseAuth.getInstance().currentUser
                    if (current == null) {
                        resultMessage = "Sign in to submit a report."
                        showReport = false
                    } else {
                        submitting = true
                        current.getIdToken(false).addOnSuccessListener { tokenResult ->
                            val idToken = tokenResult.token
                            if (idToken.isNullOrBlank()) {
                                submitting = false
                                resultMessage = "Could not authenticate the report. Please sign in again."
                            } else Thread {
                                var connection: HttpURLConnection? = null
                                var success = false
                                var responseText = ""
                                try {
                                    connection = (URL("https://streamforge-admin-api-y6ie.onrender.com/api/reports").openConnection() as HttpURLConnection).apply {
                                        requestMethod = "POST"
                                        connectTimeout = 15000
                                        readTimeout = 15000
                                        setRequestProperty("Authorization", "Bearer $idToken")
                                        setRequestProperty("Content-Type", "application/json")
                                        doOutput = true
                                    }
                                    val diagnostics = JSONObject()
                                        .put("appVersion", version)
                                        .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                                        .put("android", Build.VERSION.RELEASE)
                                        .put("sdk", Build.VERSION.SDK_INT)
                                        .put("streaming", StreamService.streaming.value)
                                        .put("recording", StreamService.recording.value)
                                        .put("status", StreamService.status.value)
                                        .put("resolution", StreamService.streamResolution.value.toString())
                                        .put("fps", StreamService.streamFps.value)
                                        .put("bitrateKbps", StreamService.bitrateKbps.value)
                                        .put("network", StreamService.networkHealth.value)
                                    val payload = JSONObject().put("category", "app").put("description", description.trim()).put("diagnostics", diagnostics)
                                    connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                                    success = connection.responseCode in 200..299
                                    responseText = (if (success) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                                } catch (error: Exception) {
                                    responseText = error.localizedMessage ?: "Network error"
                                } finally { connection?.disconnect() }
                                Handler(Looper.getMainLooper()).post {
                                    submitting = false
                                    showReport = false
                                    resultMessage = if (success) "Report submitted. The CastDoo team can review it in Admin." else "Report failed: $responseText"
                                    if (success) description = ""
                                }
                            }.start()
                        }.addOnFailureListener { error ->
                            submitting = false
                            resultMessage = error.localizedMessage ?: "Could not authenticate the report."
                        }
                    }
                }) { Text(if (submitting) "Sending…" else "Submit report") }
            },
            dismissButton = { TextButton(enabled = !submitting, onClick = { showReport = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = ReportMuted, fontSize = 10.sp)
        Text(value, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
    }
}
