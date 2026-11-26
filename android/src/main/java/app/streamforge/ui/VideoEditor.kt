package app.streamforge.ui

import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.Locale

private val EditorBg = Color(0xFF0D1014)
private val EditorPanel = Color(0xFF171D23)
private val EditorAccent = Color(0xFFFF7043)

@OptIn(UnstableApi::class)
@Composable
fun VideoEditorPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<Uri?>(null) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var startMs by remember { mutableFloatStateOf(0f) }
    var endMs by remember { mutableFloatStateOf(0f) }
    var removeAudio by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Import a video to start editing.") }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var transformer by remember { mutableStateOf<Transformer?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            source = uri
            durationMs = runCatching {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val length = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                retriever.release()
                length
            }.getOrDefault(0L).coerceAtLeast(1000L)
            startMs = 0f
            endMs = durationMs.toFloat()
            message = "Video loaded · ${formatEditorTime(durationMs)}"
        }
    }

    DisposableEffect(source) {
        val uri = source
        if (uri != null) {
            val next = ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = false
            }
            player = next
        }
        onDispose {
            player?.release()
            player = null
        }
    }
    DisposableEffect(Unit) { onDispose { transformer?.cancel() } }

    fun exportVideo() {
        val uri = source ?: return
        if (exporting) return
        exporting = true
        message = "Preparing export…"
        scope.launch {
            val output = File(context.cacheDir, "streamforge_edit_${System.currentTimeMillis()}.mp4")
            try {
                val clipping = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs.toLong())
                    .setEndPositionMs(endMs.toLong())
                    .build()
                val mediaItem = MediaItem.Builder().setUri(uri).setClippingConfiguration(clipping).build()
                val edited = EditedMediaItem.Builder(mediaItem).setRemoveAudio(removeAudio).build()
                val instance = Transformer.Builder(context).addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
                        scope.launch {
                            try {
                                val saved = withContext(Dispatchers.IO) { saveEditorExport(context, output) }
                                message = if (saved != null) "Export complete · saved to Movies/StreamForge" else "Export finished, but gallery save failed."
                            } catch (error: Exception) {
                                message = "Save failed: ${error.message ?: "unknown error"}"
                            } finally {
                                exporting = false
                                output.delete()
                                transformer = null
                            }
                        }
                    }
                    override fun onError(composition: androidx.media3.transformer.Composition, exportResult: ExportResult, exportException: ExportException) {
                        exporting = false
                        transformer = null
                        message = "Export failed: ${exportException.message ?: "unsupported video"}"
                        output.delete()
                    }
                }).build()
                transformer = instance
                instance.start(edited, output.absolutePath)
            } catch (error: Exception) {
                exporting = false
                message = "Could not export: ${error.message ?: error.javaClass.simpleName}"
                output.delete()
            }
        }
    }

    Column(Modifier.fillMaxWidth().background(EditorBg).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("VIDEO EDITOR", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                Text("Trim · Preview · Export", color = Color(0xFF9AA5AF), fontSize = 11.sp)
            }
            Surface(color = Color(0xFF24352F), shape = RoundedCornerShape(20.dp)) {
                Text("ON-DEVICE", color = Color(0xFF73D7A7), fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        Surface(color = EditorPanel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (source == null) {
                    Box(Modifier.fillMaxWidth().height(190.dp).background(Color(0xFF10151A), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("▣", color = EditorAccent, fontSize = 34.sp)
                            Text("Import a video to preview it", color = Color.White, fontSize = 12.sp)
                        }
                    }
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().height(210.dp),
                        factory = { viewContext -> PlayerView(viewContext).apply { useController = true; this.player = player } },
                        update = { it.player = player }
                    )
                }
                Button(onClick = { picker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = EditorAccent, contentColor = Color.Black), shape = RoundedCornerShape(12.dp)) {
                    Text(if (source == null) "＋  IMPORT VIDEO" else "CHANGE VIDEO", fontWeight = FontWeight.Black)
                }
            }
        }
        Surface(color = EditorPanel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TIMELINE · TRIM", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatEditorTime(startMs.toLong()), color = EditorAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(formatEditorTime(endMs.toLong()), color = Color(0xFFB6C0C8), fontSize = 11.sp)
                }
                Slider(value = startMs, onValueChange = { startMs = it.coerceAtMost(endMs - 500f) }, valueRange = 0f..durationMs.coerceAtLeast(1000L).toFloat(), enabled = source != null && !exporting, colors = SliderDefaults.colors(thumbColor = EditorAccent, activeTrackColor = EditorAccent))
                Slider(value = endMs.coerceIn(0f, durationMs.coerceAtLeast(1000L).toFloat()), onValueChange = { endMs = it.coerceAtLeast(startMs + 500f) }, valueRange = 0f..durationMs.coerceAtLeast(1000L).toFloat(), enabled = source != null && !exporting, colors = SliderDefaults.colors(thumbColor = EditorAccent, activeTrackColor = EditorAccent))
                Text("Selected length: ${formatEditorTime((endMs - startMs).toLong().coerceAtLeast(0L))}", color = Color(0xFF9AA5AF), fontSize = 10.sp)
                HorizontalDivider(color = Color(0xFF2A333B))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Original audio", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("Remove source video's sound", color = Color(0xFF9AA5AF), fontSize = 10.sp)
                    }
                    Switch(checked = removeAudio, onCheckedChange = { removeAudio = it }, enabled = source != null && !exporting)
                }
            }
        }
        Surface(color = EditorPanel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("EXPORT", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("MP4 · Original resolution · Processed on your phone", color = Color(0xFF9AA5AF), fontSize = 10.sp)
                Button(onClick = { exportVideo() }, enabled = source != null && !exporting && endMs - startMs >= 500f, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = EditorAccent, contentColor = Color.Black), shape = RoundedCornerShape(12.dp)) {
                    Text(if (exporting) "EXPORTING…" else "EXPORT VIDEO", fontWeight = FontWeight.Black)
                }
                if (exporting) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = EditorAccent)
                Text(message, color = Color(0xFFB7C0C8), fontSize = 10.sp)
            }
        }
        Text("Source files stay on your device. Exports are saved in Movies/StreamForge.", color = Color(0xFF7F8A94), fontSize = 9.sp)
    }
}

private suspend fun saveEditorExport(context: android.content.Context, file: File): Uri? = withContext(Dispatchers.IO) {
    if (!file.exists() || file.length() == 0L) return@withContext null
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "StreamForge_Edit_${System.currentTimeMillis()}.mp4")
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/StreamForge")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
    }
    val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
    try {
        context.contentResolver.openOutputStream(uri)?.use { output: OutputStream -> FileInputStream(file).use { input -> input.copyTo(output) } }
            ?: throw IllegalStateException("Gallery destination unavailable")
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, done, null, null)
        }
        uri
    } catch (error: Exception) {
        context.contentResolver.delete(uri, null, null)
        throw error
    }
}

private fun formatEditorTime(ms: Long): String {
    val seconds = (ms / 1000L).coerceAtLeast(0L)
    return String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L)
}
