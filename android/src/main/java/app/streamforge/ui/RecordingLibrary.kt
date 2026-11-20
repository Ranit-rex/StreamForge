package app.streamforge.ui

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordingLibrary() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files = remember { mutableStateListOf<File>() }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        files.clear()
        files.addAll(
            dir.listFiles()?.filter { it.isFile && it.extension.equals("mp4", true) }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
        )
    }

    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("RECORDING LIBRARY", color = Color.White, fontSize = 17.sp)
                Text("Play, export or delete your local MP4 videos", color = Color(0xFF9AA6B8), fontSize = 11.sp)
            }
            TextButton(onClick = { refresh() }) { Text("Refresh") }
        }
        if (files.isEmpty()) {
            Text(
                "No recordings yet. Tap “Record screen” in Studio to capture without going live.",
                color = Color(0xFF9AA6B8), fontSize = 12.sp
            )
        }
        files.forEach { file ->
            Surface(
                color = Color(0xFF151B25),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(file.name, color = Color.White, fontSize = 12.sp)
                    Text(
                        "${String.format(Locale.US, "%.1f", file.length() / 1048576.0)} MB  ·  ${SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(file.lastModified()))}",
                        color = Color(0xFF9AA6B8), fontSize = 10.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                runCatching {
                                    val uri = FileProvider.getUriForFile(
                                        context, "${context.packageName}.fileprovider", file
                                    )
                                    val playIntent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "video/mp4")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(playIntent)
                                }.onFailure {
                                    Toast.makeText(context, "No video player found or file unavailable", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("▶ Play") }

                        OutlinedButton(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { exportRecordingToGallery(context, file) }
                                    busy = false
                                    Toast.makeText(
                                        context,
                                        if (result != null) "Saved to Movies / StreamForge" else "Export failed",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(if (busy) "Exporting…" else "Export") }

                        TextButton(onClick = {
                            file.delete()
                            refresh()
                        }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

private fun exportRecordingToGallery(context: android.content.Context, file: File): Uri? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/StreamForge")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("Cannot open gallery output")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            uri
        } catch (error: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    } else {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "StreamForge"
        ).apply { mkdirs() }
        val destination = File(dir, file.name)
        file.copyTo(destination, true)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DATA, destination.absolutePath)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        }
        context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
    }
}.getOrNull()
