package app.streamforge.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class SceneLayer(
    val id: Long, val name: String, val kind: String, val text: String = "",
    val x: Float = .08f, val y: Float = .08f, val width: Float = .52f, val height: Float = .22f, val opacity: Float = 1f,
    val visible: Boolean = true, val locked: Boolean = false
)
private data class SceneModel(val id: Long, val name: String, val layers: List<SceneLayer>)

private fun sceneDefaults() = listOf(
    SceneModel(1, "Starting Soon", listOf(SceneLayer(11, "Screen Capture", "screen", x=0f, y=0f, width=1f, height=1f), SceneLayer(12, "Main title", "text", "STREAM STARTING SOON", .12f, .4f, .48f, .16f))),
    SceneModel(2, "Gameplay", listOf(SceneLayer(21, "Screen Capture", "screen", x=0f, y=0f, width=1f, height=1f), SceneLayer(22, "Channel label", "text", "PLAYING LIVE • STREAMFORGE", .04f, .05f, .42f, .12f))),
    SceneModel(3, "BRB", listOf(SceneLayer(31, "Screen Capture", "screen", x=0f, y=0f, width=1f, height=1f), SceneLayer(32, "BRB title", "text", "BE RIGHT BACK", .2f, .42f, .45f, .16f))),
    SceneModel(4, "Ending", listOf(SceneLayer(41, "Screen Capture", "screen", x=0f, y=0f, width=1f, height=1f), SceneLayer(42, "Ending title", "text", "THANKS FOR WATCHING", .12f, .4f, .52f, .16f)))
)

private fun encodeSceneList(items: List<SceneModel>): String {
    val all = JSONArray()
    items.forEach { scene ->
        val layers = JSONArray()
        scene.layers.forEach { layer ->
            layers.put(JSONObject().apply {
                put("id", layer.id); put("name", layer.name); put("kind", layer.kind)
                put("text", layer.text); put("x", layer.x); put("y", layer.y); put("width", layer.width); put("height", layer.height)
                put("opacity", layer.opacity); put("visible", layer.visible); put("locked", layer.locked)
            })
        }
        all.put(JSONObject().apply { put("id", scene.id); put("name", scene.name); put("layers", layers) })
    }
    return all.toString()
}

private fun decodeSceneList(raw: String): List<SceneModel> = runCatching {
    val all = JSONArray(raw)
    (0 until all.length()).map { i ->
        val scene = all.getJSONObject(i)
        val layers = scene.optJSONArray("layers") ?: JSONArray()
        SceneModel(scene.optLong("id", i.toLong()), scene.optString("name", "Scene"),
            (0 until layers.length()).map { j ->
                val item = layers.getJSONObject(j)
                SceneLayer(item.optLong("id"), item.optString("name"), item.optString("kind"),
                    item.optString("text"), item.optDouble("x", .08).toFloat(),
                    item.optDouble("y", .08).toFloat(), item.optDouble("width", .52).toFloat(), item.optDouble("height", .22).toFloat(), item.optDouble("opacity", 1.0).toFloat(),
                    item.optBoolean("visible", true), item.optBoolean("locked", false))
            })
    }
}.getOrDefault(sceneDefaults()).ifEmpty { sceneDefaults() }

@Composable
fun SceneStudio(onApplyScene: (Int) -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("streamforge_studio", 0) }
    val scenes = remember { mutableStateListOf<SceneModel>().apply { addAll(decodeSceneList(prefs.getString("scenes", "") ?: "")) } }
    var active by remember { mutableIntStateOf(prefs.getInt("active_scene", 0)) }
    var selected by remember { mutableLongStateOf(-1L) }
    var addDialog by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var sourceMessage by remember { mutableStateOf("") }
    var applyMessage by remember { mutableStateOf("") }
    var canvasWidth by remember { mutableFloatStateOf(1f) }
    var canvasHeight by remember { mutableFloatStateOf(1f) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val idx = active.coerceIn(0, scenes.lastIndex)
            val current = scenes[idx]
            val layer = SceneLayer(System.nanoTime(), "Image ${current.layers.count { it.kind == "image" } + 1}", "image", uri.toString(), .12f, .12f, .34f, .34f)
            scenes[idx] = current.copy(layers = current.layers + layer)
            selected = layer.id
        }
    }

    LaunchedEffect(scenes.toList()) { prefs.edit().putString("scenes", encodeSceneList(scenes)).apply() }
    val index = active.coerceIn(0, scenes.lastIndex)
    val scene = scenes[index]
    val layer = scene.layers.firstOrNull { it.id == selected }

    fun updateLayer(id: Long, transform: (SceneLayer) -> SceneLayer) {
        val current = scenes[index]
        scenes[index] = current.copy(layers = current.layers.map { if (it.id == id) transform(it) else it })
    }
    val latestScene = rememberUpdatedState(scene)
    val latestUpdateLayer = rememberUpdatedState<(Long, (SceneLayer) -> SceneLayer) -> Unit>({ id, transform -> updateLayer(id, transform) })

    fun addSource(kind: String) {
        if (kind == "image") { imagePicker.launch(arrayOf("image/*")); return }
        val current = scenes[index]
        if (kind == "screen" && current.layers.any { it.kind == "screen" }) {
            selected = current.layers.first { it.kind == "screen" }.id
            sourceMessage = "This scene already has its screen capture source. Resize or move that layer."
            return
        }
        val count = current.layers.count { it.kind == kind } + 1
        val item = SceneLayer(
            System.nanoTime(),
            when (kind) { "text" -> "Text ${count}"; "camera" -> "Camera ${count}"; else -> "Screen capture" },
            kind,
            if (kind == "text") "YOUR TEXT" else "",
            if (kind == "screen") 0f else .08f,
            if (kind == "screen") 0f else .08f,
            when (kind) { "screen" -> 1f; "text" -> .42f; "camera" -> .30f; else -> .34f },
            when (kind) { "screen" -> 1f; "text" -> .16f; "camera" -> .24f; else -> .34f }
        )
        sourceMessage = ""
        scenes[index] = current.copy(layers = current.layers + item)
        selected = item.id
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SCENES", color = Color.White, fontSize = 18.sp)
                Text("Choose a scene, then tap or drag items on the canvas.", color = Color(0xFF9AA6B8), fontSize = 11.sp)
            }
            Button(onClick = {
                scenes.add(SceneModel(System.nanoTime(), "Scene ${scenes.size + 1}", emptyList()))
                active = scenes.lastIndex; selected = -1L
            }) { Text("+ New") }
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            scenes.forEachIndexed { i, item ->
                FilterChip(selected = i == index, onClick = { active = i; selected = -1L }, label = { Text(item.name) })
            }
        }

        Surface(color = Color(0xFF151B25), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("LIVE LAYOUT EDITOR", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("16:9 · full-frame sources", color = Color.Gray, fontSize = 10.sp)
                }
                val imageBitmaps = remember(scene.layers.filter { it.kind == "image" }.map { it.id to it.text }) {
                    scene.layers.filter { it.kind == "image" && it.text.isNotBlank() }.mapNotNull { item ->
                        val bitmap = runCatching {
                            context.contentResolver.openInputStream(Uri.parse(item.text))?.use { BitmapFactory.decodeStream(it) }
                        }.getOrNull()
                        if (bitmap != null) item.id to bitmap else null
                    }.toMap()
                }
                Canvas(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        .background(Color(0xFF0B1220), RoundedCornerShape(9.dp))
                        .pointerInput(index) {
                            detectTapGestures { point ->
                                val hit = latestScene.value.layers.asReversed().firstOrNull { item ->
                                    item.visible &&
                                        point.x >= item.x * size.width &&
                                        point.x <= (item.x + item.width) * size.width &&
                                        point.y >= item.y * size.height &&
                                        point.y <= (item.y + item.height) * size.height
                                }
                                selected = hit?.id ?: -1L
                            }
                        }
                        .pointerInput(index) {
                            var draggingLayerId: Long? = null
                            detectDragGestures(
                                onDragStart = { point ->
                                    val hit = latestScene.value.layers.asReversed().firstOrNull { item ->
                                        item.visible && point.x >= item.x * size.width && point.x <= (item.x + item.width) * size.width &&
                                            point.y >= item.y * size.height && point.y <= (item.y + item.height) * size.height
                                    }
                                    draggingLayerId = hit?.id
                                    if (hit != null) selected = hit.id
                                },
                                onDragEnd = { draggingLayerId = null },
                                onDragCancel = { draggingLayerId = null },
                                onDrag = { change, drag ->
                                    change.consume()
                                    val chosen = latestScene.value.layers.firstOrNull { it.id == draggingLayerId }
                                    if (chosen != null && !chosen.locked) {
                                        latestUpdateLayer.value(chosen.id) {
                                            it.copy(
                                                x = (it.x + drag.x / size.width).coerceIn(0f, (1f - it.width).coerceAtLeast(0f)),
                                                y = (it.y + drag.y / size.height).coerceIn(0f, (1f - it.height).coerceAtLeast(0f))
                                            )
                                        }
                                    }
                                }
                            )
                        }
                ) {
                    canvasWidth = size.width; canvasHeight = size.height
                    drawRect(Color(0xFF111D30))
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD) }
                        scene.layers.filter { it.visible }.forEach { item ->
                            val left = item.x * size.width
                            val top = item.y * size.height
                            val rect = RectF(left, top, left + size.width * item.width, top + size.height * item.height)
                            paint.alpha = (item.opacity.coerceIn(0f, 1f) * 255).toInt()
                            val importedBitmap = if (item.kind == "image") imageBitmaps[item.id] else null
                            if (importedBitmap != null) {
                                native.drawBitmap(importedBitmap, null, rect, paint)
                            } else {
                                paint.color = when (item.kind) {
                                    "text" -> android.graphics.Color.rgb(48, 72, 120)
                                    "camera" -> android.graphics.Color.rgb(54, 83, 103)
                                    "image" -> android.graphics.Color.rgb(80, 66, 116)
                                    else -> android.graphics.Color.rgb(25, 42, 65)
                                }
                                native.drawRoundRect(rect, 12f, 12f, paint)
                                paint.color = android.graphics.Color.WHITE
                                paint.textSize = size.width * .026f
                                val label = when (item.kind) {
                                    "text" -> item.text
                                    "image" -> "IMAGE · ${item.name}"
                                    "camera" -> "CAMERA SOURCE · ${item.name}"
                                    else -> "SCREEN CAPTURE"
                                }
                                native.drawText(label.take(36), left + 10, top + size.height * .13f, paint)
                            }
                            if (item.id == selected) {
                                paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
                                paint.color = android.graphics.Color.CYAN
                                native.drawRoundRect(rect, 12f, 12f, paint)
                                paint.style = Paint.Style.FILL
                            }
                        }
                    }
                }
                Text("Select a layer to resize it. Dragging now uses that layer's real bounds; each source has its own starting size.", color = Color(0xFFB8C5D8), fontSize = 10.sp)
                if (sourceMessage.isNotBlank()) Text(sourceMessage, color = Color(0xFFFFC46B), fontSize = 10.sp)
                if (scene.layers.isEmpty()) Text("Abhi empty scene hai. + Add source se screen, text ya image add karo.", color = Color(0xFFFFC46B), fontSize = 11.sp)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("LAYERS", color = Color.White, fontSize = 14.sp)
                Text("Top layer appears above lower layers.", color = Color.Gray, fontSize = 10.sp)
            }
            Button(onClick = { addDialog = true }) { Text("+ Add source") }
        }
        scene.layers.asReversed().forEach { item ->
            Surface(
                color = if (item.id == selected) Color(0xFF253A55) else Color(0xFF151B25),
                shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth(),
                onClick = { selected = item.id }
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (item.visible) "◉" else "○", color = Color(0xFF8EA5FF))
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(item.name, color = Color.White, fontSize = 12.sp)
                        Text(item.kind.uppercase(), color = Color.Gray, fontSize = 9.sp)
                    }
                    TextButton(onClick = { updateLayer(item.id) { it.copy(visible = !it.visible) } }) { Text(if (item.visible) "Hide" else "Show") }
                    TextButton(onClick = { updateLayer(item.id) { it.copy(locked = !it.locked) } }) { Text(if (item.locked) "Unlock" else "Lock") }
                }
            }
        }

        if (layer != null) {
            Surface(color = Color(0xFF151B25), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("SELECTED: ${layer.name}", color = Color.White, fontSize = 13.sp)
                    if (layer.kind == "text") {
                        OutlinedTextField(
                            value = layer.text, onValueChange = { value -> updateLayer(layer.id) { it.copy(text = value) } },
                            label = { Text("Text shown on screen") }, modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (layer.kind == "image") {
                        Text("Custom image added. Use Replace to choose another image.", color = Color(0xFFB8C5D8), fontSize = 11.sp)
                        OutlinedButton(onClick = { imagePicker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Replace image") }
                    }
                    Text("Width · ${(layer.width * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                    Slider(value = layer.width, onValueChange = { v -> updateLayer(layer.id) { it.copy(width = v.coerceIn(.08f, 1f), x = it.x.coerceAtMost(1f - v)) } }, valueRange = .08f..1f)
                    Text("Height · ${(layer.height * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                    Slider(value = layer.height, onValueChange = { v -> updateLayer(layer.id) { it.copy(height = v.coerceIn(.05f, 1f), y = it.y.coerceAtMost(1f - v)) } }, valueRange = .05f..1f)
                    Text("Position X · ${(layer.x * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                    Slider(value = layer.x, onValueChange = { v -> updateLayer(layer.id) { it.copy(x = v.coerceIn(0f, 1f - it.width)) } }, valueRange = 0f..(1f - layer.width).coerceAtLeast(0f))
                    Text("Position Y · ${(layer.y * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                    Slider(value = layer.y, onValueChange = { v -> updateLayer(layer.id) { it.copy(y = v.coerceIn(0f, 1f - it.height)) } }, valueRange = 0f..(1f - layer.height).coerceAtLeast(0f))
                    Text("Opacity · ${(layer.opacity * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                    Slider(value = layer.opacity, onValueChange = { v -> updateLayer(layer.id) { it.copy(opacity = v) } })
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        OutlinedButton(onClick = {
                            val items = scene.layers.toMutableList(); val i = items.indexOfFirst { it.id == layer.id }
                            if (i > 0) { val moving = items.removeAt(i); items.add(i - 1, moving); scenes[index] = scene.copy(layers = items) }
                        }, modifier = Modifier.weight(1f)) { Text("Move up") }
                        OutlinedButton(onClick = {
                            val items = scene.layers.toMutableList(); val i = items.indexOfFirst { it.id == layer.id }
                            if (i >= 0 && i < items.lastIndex) { val moving = items.removeAt(i); items.add(i + 1, moving); scenes[index] = scene.copy(layers = items) }
                        }, modifier = Modifier.weight(1f)) { Text("Move down") }
                        OutlinedButton(onClick = { scenes[index] = scene.copy(layers = scene.layers.filterNot { it.id == layer.id }); selected = -1L }) { Text("Delete") }
                    }
                }
            }
        }

        Button(
            onClick = {
                prefs.edit().putInt("active_scene", index).apply()
                onApplyScene(index)
                applyMessage = "“${scene.name}” selected for live output."
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Apply scene to LIVE output") }
        if (applyMessage.isNotBlank()) Text(applyMessage, color = Color(0xFF91E6B0), fontSize = 11.sp)

        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            OutlinedButton(onClick = { draft = scene.name; renameDialog = true }, modifier = Modifier.weight(1f)) { Text("Rename scene") }
            OutlinedButton(onClick = {
                scenes.add(scene.copy(id = System.nanoTime(), name = "${scene.name} Copy", layers = scene.layers.map { it.copy(id = System.nanoTime()) }))
                active = scenes.lastIndex; selected = -1L
            }, modifier = Modifier.weight(1f)) { Text("Duplicate") }
            OutlinedButton(onClick = {
                if (scenes.size > 1) { scenes.removeAt(index); active = (index - 1).coerceAtLeast(0); selected = -1L }
            }, enabled = scenes.size > 1) { Text("Delete") }
        }
        Text("Scene layouts are saved on this phone. Apply a scene before or during a broadcast to use its text and image overlays.", color = Color.Gray, fontSize = 10.sp)
    }

    if (addDialog) AlertDialog(
        onDismissRequest = { addDialog = false }, title = { Text("Add to this scene") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Choose what you want to place on the canvas.", color = Color.Gray, fontSize = 11.sp)
            listOf("screen" to "Screen capture", "text" to "Text overlay", "image" to "Choose image from phone", "camera" to "Camera placeholder").forEach { (kind, label) ->
                TextButton(onClick = { addSource(kind); addDialog = false }, modifier = Modifier.fillMaxWidth()) { Text(label) }
            }
        } },
        confirmButton = { TextButton(onClick = { addDialog = false }) { Text("Close") } }
    )
    if (renameDialog) AlertDialog(
        onDismissRequest = { renameDialog = false }, title = { Text("Rename scene") },
        text = { OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (draft.isNotBlank()) scenes[index] = scene.copy(name = draft.trim()); renameDialog = false }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("Cancel") } }
    )
}
