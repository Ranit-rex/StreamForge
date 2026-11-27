package app.streamforge.ui

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
        Text("Share a diagnostic summary when streaming fails, preview turns blank, audio stops, or the app behaves unexpectedly.", color = ReportMuted, fontSize = 12.sp)
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
                context.startActivity(Intent.createChooser(send, "Share CastDoo report"))
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) { Text("SHARE DIAGNOSTIC REPORT", fontWeight = FontWeight.Bold) }
        Text("Nothing is sent automatically. Review the report and choose your email or support channel in Android's share menu.", color = ReportMuted, fontSize = 10.sp)
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
