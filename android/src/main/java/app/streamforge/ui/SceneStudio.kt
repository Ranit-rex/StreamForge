package app.streamforge.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.LinearGradient
import android.graphics.Shader
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class SceneLayer(
    val id: Long, val name: String, val kind: String, val text: String = "",
    val x: Float = .08f, val y: Float = .08f, val width: Float = .52f, val height: Float = .22f, val opacity: Float = 1f,
    val visible: Boolean = true, val locked: Boolean = false,
    val rotation: Float = 0f, val cropLeft: Float = 0f, val cropTop: Float = 0f, val cropRight: Float = 1f, val cropBottom: Float = 1f
)
private data class SceneModel(val id: Long, val name: String, val layers: List<SceneLayer>)

private fun sceneDefaults() = listOf(
    SceneModel(1, "Default", listOf(SceneLayer(11, "Game Capture", "screen", x=0f, y=0f, width=1f, height=1f, locked=true)))
)

private fun encodeSceneList(items: List<SceneModel>): String {
    val all = JSONArray()
    items.forEach { scene ->
        val layers = JSONArray()
        scene.layers.forEach { layer ->
            layers.put(JSONObject().apply {
                put("id", layer.id); put("name", layer.name); put("kind", layer.kind)
                put("text", layer.text); put("x", layer.x); put("y", layer.y); put("width", layer.width); put("height", layer.height)
                put("opacity", layer.opacity); put("visible", layer.visible); put("locked", layer.locked); put("rotation", layer.rotation); put("cropLeft", layer.cropLeft); put("cropTop", layer.cropTop); put("cropRight", layer.cropRight); put("cropBottom", layer.cropBottom)
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
                    item.optBoolean("visible", true), item.optBoolean("locked", false), item.optDouble("rotation", 0.0).toFloat(), item.optDouble("cropLeft", 0.0).toFloat(), item.optDouble("cropTop", 0.0).toFloat(), item.optDouble("cropRight", 1.0).toFloat(), item.optDouble("cropBottom", 1.0).toFloat())
            })
    }
}.getOrDefault(sceneDefaults()).ifEmpty { sceneDefaults() }


@Composable
fun SceneCompositionPreview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("streamforge_studio", 0) }
    var savedScenes by remember { mutableStateOf(decodeSceneList(prefs.getString("scenes", "") ?: "")) }
    var activeIndex by remember { mutableIntStateOf(prefs.getInt("active_scene", 0)) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { shared, key ->
            if (key == "scenes" || key == "active_scene") {
                savedScenes = decodeSceneList(shared.getString("scenes", "") ?: "")
                activeIndex = shared.getInt("active_scene", 0)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val scene = savedScenes.getOrNull(activeIndex) ?: savedScenes.firstOrNull() ?: sceneDefaults().first()
    val bitmaps = remember(scene.layers.map { it.id to it.text }) {
        scene.layers.filter { (it.kind == "image" || it.kind == "gif") && it.text.isNotBlank() }.mapNotNull { item ->
            runCatching { context.contentResolver.openInputStream(Uri.parse(item.text))?.use { BitmapFactory.decodeStream(it) } }
                .getOrNull()?.let { item.id to it }
        }.toMap()
    }
    Canvas(modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
        drawRect(Color(0xFF101827))
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            }
            scene.layers.filter { it.visible }.forEach { item ->
                val rect = RectF(item.x * size.width, item.y * size.height,
                    (item.x + item.width) * size.width, (item.y + item.height) * size.height)
                native.save()
                native.rotate(item.rotation, rect.centerX(), rect.centerY())
                paint.alpha = (item.opacity.coerceIn(0f, 1f) * 255).toInt()
                when (item.kind) {
                    "background" -> {
                        paint.style = Paint.Style.FILL
                        paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, android.graphics.Color.rgb(9, 15, 34), android.graphics.Color.rgb(35, 18, 62), Shader.TileMode.CLAMP)
                        native.drawRect(rect, paint)
                        paint.shader = null
                        paint.color = android.graphics.Color.argb(55, 95, 135, 255)
                        native.drawCircle(rect.right * .82f, rect.top + rect.height() * .22f, rect.height() * .22f, paint)
                    }
                    "frame" -> {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = (rect.width() * .008f).coerceIn(3f, 10f)
                        paint.color = android.graphics.Color.rgb(89, 220, 255)
                        native.drawRoundRect(rect, 24f, 24f, paint)
                        paint.strokeWidth = (rect.width() * .0025f).coerceIn(1.5f, 4f)
                        paint.color = android.graphics.Color.rgb(181, 112, 255)
                        val corner = rect.width() * .10f
                        native.drawLine(rect.left, rect.top + corner, rect.left, rect.top, paint)
                        native.drawLine(rect.left, rect.top, rect.left + corner, rect.top, paint)
                        native.drawLine(rect.right - corner, rect.bottom, rect.right, rect.bottom, paint)
                        native.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - corner, paint)
                        paint.style = Paint.Style.FILL
                    }
                    "screen" -> {
                        paint.style = Paint.Style.FILL
                        paint.color = android.graphics.Color.rgb(24, 39, 60)
                        native.drawRoundRect(rect, 12f, 12f, paint)
                        paint.color = android.graphics.Color.rgb(105, 157, 255)
                        paint.textSize = (rect.height() * .15f).coerceIn(20f, 54f)
                        val title = "GAME CAPTURE"
                        native.drawText(title, rect.centerX() - paint.measureText(title) / 2f, rect.centerY() - 5f, paint)
                        paint.color = android.graphics.Color.LTGRAY
                        paint.textSize = (rect.height() * .065f).coerceIn(12f, 24f)
                        val hint = "Your screen appears here when capture is active"
                        native.drawText(hint, rect.centerX() - paint.measureText(hint) / 2f, rect.centerY() + 26f, paint)
                    }
                    "text" -> {
                        paint.style = Paint.Style.FILL
                        paint.color = android.graphics.Color.WHITE
                        paint.textSize = (rect.height() * .58f).coerceIn(12f, 48f)
                        native.drawText(item.text.take(40), rect.left + 5f, rect.centerY(), paint)
                    }
                    "image", "gif" -> bitmaps[item.id]?.let { bitmap ->
                        val src = android.graphics.Rect(
                            (bitmap.width * item.cropLeft).toInt().coerceIn(0, bitmap.width - 1),
                            (bitmap.height * item.cropTop).toInt().coerceIn(0, bitmap.height - 1),
                            (bitmap.width * item.cropRight).toInt().coerceIn(1, bitmap.width),
                            (bitmap.height * item.cropBottom).toInt().coerceIn(1, bitmap.height)
                        )
                        paint.style = Paint.Style.FILL
                        native.drawBitmap(bitmap, src, rect, paint)
                    }
                    "camera" -> {
                        paint.style = Paint.Style.FILL
                        paint.color = android.graphics.Color.rgb(35, 48, 65)
                        native.drawRoundRect(rect, 10f, 10f, paint)
                        paint.color = android.graphics.Color.WHITE
                        paint.textSize = (rect.height() * .18f).coerceIn(14f, 28f)
                        native.drawText("CAMERA SOURCE", rect.left + 10f, rect.centerY(), paint)
                    }
                }
                native.restore()
            }
        }
    }
}

@Composable
fun SceneStudio(
    previewReady: Boolean,
    isStreaming: Boolean,
    onPreparePreview: () -> Unit,
    onStopStudioPreview: () -> Unit,
    onChangeCaptureSource: () -> Unit,
    onApplyScene: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("streamforge_studio", 0) }
    val scenes = remember {
        mutableStateListOf<SceneModel>().apply {
            val loaded = decodeSceneList(prefs.getString("scenes", "") ?: "")
            val needsMigration = prefs.getInt("scene_layout_version", 0) < 2
            val resetLegacyDefaults = prefs.getInt("scene_layout_version", 0) < 3 && loaded.map { it.name } == listOf("Starting Soon", "Gameplay", "BRB", "Ending")
            val normalized = if (needsMigration) loaded.map { savedScene ->
                savedScene.copy(layers = savedScene.layers.map { savedLayer ->
                    when (savedLayer.kind) {
                        "screen" -> savedLayer.copy(x = 0f, y = 0f, width = 1f, height = 1f)
                        "text" -> savedLayer.copy(width = .42f, height = .16f)
                        "camera" -> savedLayer.copy(width = .30f, height = .24f)
                        "image" -> savedLayer.copy(width = .34f, height = .34f)
                        else -> savedLayer
                    }
                })
            } else loaded
            addAll(if (resetLegacyDefaults) sceneDefaults() else normalized)
            if (needsMigration || resetLegacyDefaults) prefs.edit().putInt("scene_layout_version", 3).apply()
        }
    }
    var active by remember { mutableIntStateOf(prefs.getInt("active_scene", 0)) }
    var selected by remember { mutableLongStateOf(-1L) }
    var addDialog by remember { mutableStateOf(false) }
    var assetsDialog by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var sourceMessage by remember { mutableStateOf("") }
    var canvasWidth by remember { mutableFloatStateOf(1f) }
    var canvasHeight by remember { mutableFloatStateOf(1f) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val idx = active.coerceIn(0, scenes.lastIndex)
            val current = scenes[idx]
            val mime = context.contentResolver.getType(uri).orEmpty()
            val kind = if (mime.equals("image/gif", true)) "gif" else "image"
            val layer = SceneLayer(System.nanoTime(), "${kind.uppercase()} ${current.layers.count { it.kind == kind } + 1}", kind, uri.toString(), .12f, .12f, .34f, .34f)
            scenes[idx] = current.copy(layers = current.layers + layer)
            selected = layer.id
        }
    }

    LaunchedEffect(scenes.toList()) { prefs.edit().putString("scenes", encodeSceneList(scenes)).apply() }
    val index = active.coerceIn(0, scenes.lastIndex)
    val scene = scenes[index]
    LaunchedEffect(index, scene) {
        prefs.edit()
            .putString("scenes", encodeSceneList(scenes))
            .putInt("active_scene", index)
            .apply()
        onApplyScene(index)
    }
    val layer = scene.layers.firstOrNull { it.id == selected }

    fun updateLayer(id: Long, transform: (SceneLayer) -> SceneLayer) {
        val current = scenes[index]
        scenes[index] = current.copy(layers = current.layers.map { if (it.id == id) transform(it) else it })
    }
    val latestScene = rememberUpdatedState(scene)
    val latestUpdateLayer = rememberUpdatedState<(Long, (SceneLayer) -> SceneLayer) -> Unit>({ id, transform -> updateLayer(id, transform) })

    fun addSource(kind: String) {
        if (kind == "image" || kind == "gif") { imagePicker.launch(arrayOf("image/*")); return }
        val current = scenes[index]
        if (kind == "screen" && current.layers.any { it.kind == "screen" }) {
            selected = current.layers.first { it.kind == "screen" }.id
            sourceMessage = "This scene already has Game Capture. Unlock it before moving or resizing."
            return
        }
        val count = current.layers.count { it.kind == kind } + 1
        val item = SceneLayer(
            System.nanoTime(),
            when (kind) { "text" -> "Text ${count}"; "camera" -> "Camera ${count}"; else -> "Game Capture" },
            kind,
            if (kind == "text") "YOUR TEXT" else "",
            if (kind == "screen") 0f else .08f,
            if (kind == "screen") 0f else .08f,
            when (kind) { "screen" -> 1f; "text" -> .42f; "camera" -> .30f; else -> .34f },
            when (kind) { "screen" -> 1f; "text" -> .16f; "camera" -> .24f; else -> .34f },
            locked = kind == "screen"
        )
        sourceMessage = ""
        scenes[index] = current.copy(layers = current.layers + item)
        selected = item.id
    }

    fun addPreset(sceneName: String, title: String, subtitle: String) {
        val stamp = System.nanoTime()
        val presetLayers = listOfNotNull(
            if (sceneName == "Neon Border") null else SceneLayer(stamp, "Gradient background", "background", sceneName, 0f, 0f, 1f, 1f, locked = true),
            SceneLayer(stamp + 1, "Neon frame", "frame", "", .025f, .04f, .95f, .92f),
            SceneLayer(stamp + 2, "Main title", "text", title, .10f, .40f, .80f, .16f),
            SceneLayer(stamp + 3, "Subtitle", "text", subtitle, .14f, .57f, .72f, .10f)
        )
        scenes.add(SceneModel(stamp, sceneName, presetLayers))
        active = scenes.lastIndex
        selected = -1L
        assetsDialog = false
        sourceMessage = "Free overlay pack added. Import your own background or GIF from Add source."
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SCENE PREVIEW", color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(if (isStreaming) { if (StreamService.streaming.value) "LIVE OUTPUT" else "REC OUTPUT" } else "AUTO · UPDATES LIVE", color = Color(0xFF9DB4FF), fontSize = 9.sp)
        }
        if (isStreaming) {
            StudioPreviewSurface(
                previewReady = previewReady,
                isStreaming = true,
                onPrepare = onPreparePreview,
                onStop = onStopStudioPreview,
                onChangeSource = onChangeCaptureSource
            )
        } else {
            SceneCompositionPreview()
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SCENES", color = Color.White, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
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
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("LAYOUT", color = Color.White, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    Text("16:9 · full-frame sources", color = Color.Gray, fontSize = 10.sp)
                }
                val imageBitmaps = remember(scene.layers.filter { it.kind == "image" || it.kind == "gif" }.map { it.id to it.text }) {
                    scene.layers.filter { (it.kind == "image" || it.kind == "gif") && it.text.isNotBlank() }.mapNotNull { item ->
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
                                    !item.locked && item.visible &&
                                        point.x >= item.x * size.width &&
                                        point.x <= (item.x + item.width) * size.width &&
                                        point.y >= item.y * size.height &&
                                        point.y <= (item.y + item.height) * size.height
                                }
                                selected = hit?.id ?: -1L
                            }
                        }
                        .pointerInput(index) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val target = latestScene.value.layers.asReversed().firstOrNull { item ->
                                    !item.locked && item.visible &&
                                        down.position.x >= item.x * size.width &&
                                        down.position.x <= (item.x + item.width) * size.width &&
                                        down.position.y >= item.y * size.height &&
                                        down.position.y <= (item.y + item.height) * size.height
                                }
                                if (target != null) {
                                    selected = target.id
                                    val left = target.x * size.width
                                    val top = target.y * size.height
                                    val right = (target.x + target.width) * size.width
                                    val bottom = (target.y + target.height) * size.height
                                    val edgeTouch = 96f
                                    val resizeLeft = abs(down.position.x - left) < edgeTouch
                                    val resizeRight = abs(down.position.x - right) < edgeTouch
                                    val resizeTop = abs(down.position.y - top) < edgeTouch
                                    val resizeBottom = abs(down.position.y - bottom) < edgeTouch
                                    val resizeX = resizeLeft || resizeRight
                                    val resizeY = resizeTop || resizeBottom
                                    val resizeWithOneFinger = resizeX || resizeY
                                    var previousCentroid = down.position
                                    var previousDistance = 0f
                                    var previousAngle = 0f
                                    do {
                                        val event = awaitPointerEvent()
                                        val activePointers = event.changes.filter { it.pressed }
                                        if (activePointers.isNotEmpty()) {
                                            val centroid = androidx.compose.ui.geometry.Offset(
                                                activePointers.map { it.position.x }.average().toFloat(),
                                                activePointers.map { it.position.y }.average().toFloat()
                                            )
                                            if (activePointers.size == 1) {
                                                val delta = centroid - previousCentroid
                                                latestUpdateLayer.value(target.id) { current ->
                                                    if (resizeWithOneFinger) {
                                                        val dx = if (resizeX) delta.x / size.width else 0f
                                                        val dy = if (resizeY) delta.y / size.height else 0f
                                                        var newLeft = current.x + if (resizeLeft) dx else 0f
                                                        var newRight = current.x + current.width + if (resizeRight) dx else 0f
                                                        var newTop = current.y + if (resizeTop) dy else 0f
                                                        var newBottom = current.y + current.height + if (resizeBottom) dy else 0f
                                                        newLeft = newLeft.coerceIn(0f, (newRight - .06f).coerceAtLeast(0f))
                                                        newRight = newRight.coerceIn((newLeft + .06f).coerceAtMost(1f), 1f)
                                                        newTop = newTop.coerceIn(0f, (newBottom - .05f).coerceAtLeast(0f))
                                                        newBottom = newBottom.coerceIn((newTop + .05f).coerceAtMost(1f), 1f)
                                                        current.copy(x = newLeft, y = newTop, width = newRight - newLeft, height = newBottom - newTop)
                                                    } else {
                                                        current.copy(
                                                            x = (current.x + delta.x / size.width).coerceIn(0f, 1f - current.width),
                                                            y = (current.y + delta.y / size.height).coerceIn(0f, 1f - current.height)
                                                        )
                                                    }
                                                }
                                                previousDistance = 0f
                                            } else {
                                                val p1 = activePointers[0].position
                                                val p2 = activePointers[1].position
                                                val dx = p2.x - p1.x
                                                val dy = p2.y - p1.y
                                                val distance = sqrt(dx * dx + dy * dy)
                                                val angle = atan2(dy, dx)
                                                if (previousDistance > 0f) {
                                                    val zoom = (distance / previousDistance).coerceIn(.85f, 1.15f)
                                                    val rotationDelta = Math.toDegrees((angle - previousAngle).toDouble()).toFloat()
                                                    val pan = centroid - previousCentroid
                                                    latestUpdateLayer.value(target.id) { current ->
                                                        val width = (current.width * zoom).coerceIn(.06f, 1f)
                                                        val height = (current.height * zoom).coerceIn(.05f, 1f)
                                                        val centerX = current.x + current.width / 2f + pan.x / size.width
                                                        val centerY = current.y + current.height / 2f + pan.y / size.height
                                                        current.copy(
                                                            width = width,
                                                            height = height,
                                                            x = (centerX - width / 2f).coerceIn(0f, 1f - width),
                                                            y = (centerY - height / 2f).coerceIn(0f, 1f - height),
                                                            rotation = (current.rotation + rotationDelta).coerceIn(-180f, 180f)
                                                        )
                                                    }
                                                }
                                                previousDistance = distance
                                                previousAngle = angle
                                            }
                                            previousCentroid = centroid
                                            event.changes.forEach { it.consume() }
                                        } else {
                                            previousDistance = 0f
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
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
                            native.save()
                            native.rotate(item.rotation, rect.centerX(), rect.centerY())
                            paint.alpha = (item.opacity.coerceIn(0f, 1f) * 255).toInt()
                            val importedBitmap = if (item.kind == "image" || item.kind == "gif") imageBitmaps[item.id] else null
                            if (importedBitmap != null) {
                                paint.style = Paint.Style.FILL
                                native.drawBitmap(importedBitmap, android.graphics.Rect((importedBitmap.width * item.cropLeft).toInt(), (importedBitmap.height * item.cropTop).toInt(), (importedBitmap.width * item.cropRight).toInt().coerceAtLeast(1), (importedBitmap.height * item.cropBottom).toInt().coerceAtLeast(1)), rect, paint)
                            } else {
                                paint.style = Paint.Style.FILL
                                when (item.kind) {
                                    "background" -> {
                                        paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, android.graphics.Color.rgb(9, 15, 34), android.graphics.Color.rgb(35, 18, 62), Shader.TileMode.CLAMP)
                                        native.drawRect(rect, paint)
                                        paint.shader = null
                                        paint.color = android.graphics.Color.argb(55, 95, 135, 255)
                                        native.drawCircle(rect.right * .82f, rect.top + rect.height() * .22f, rect.height() * .22f, paint)
                                    }
                                    "frame" -> {
                                        paint.style = Paint.Style.STROKE
                                        paint.strokeWidth = 5f
                                        paint.color = android.graphics.Color.rgb(89, 220, 255)
                                        native.drawRoundRect(rect, 22f, 22f, paint)
                                        paint.style = Paint.Style.FILL
                                    }
                                    "screen" -> {
                                        paint.color = android.graphics.Color.rgb(15, 31, 52)
                                        native.drawRoundRect(rect, 12f, 12f, paint)
                                        paint.color = android.graphics.Color.rgb(63, 106, 167)
                                        paint.strokeWidth = 3f
                                        paint.style = Paint.Style.STROKE
                                        native.drawRoundRect(rect, 12f, 12f, paint)
                                        paint.style = Paint.Style.FILL
                                        paint.color = android.graphics.Color.WHITE
                                        val iconSize = (rect.height() * .22f).coerceIn(28f, 64f)
                                        val icon = RectF(rect.centerX() - iconSize * .72f, rect.centerY() - iconSize * 1.05f, rect.centerX() + iconSize * .72f, rect.centerY() + iconSize * .12f)
                                        paint.style = Paint.Style.STROKE
                                        paint.strokeWidth = (iconSize * .09f).coerceAtLeast(3f)
                                        native.drawRoundRect(icon, 9f, 9f, paint)
                                        paint.style = Paint.Style.FILL
                                        native.drawCircle(icon.centerX(), icon.bottom - iconSize * .13f, iconSize * .045f, paint)
                                        paint.textSize = (rect.height() * .10f).coerceIn(16f, 30f)
                                        val title = "YOUR GAME SCREEN"
                                        native.drawText(title, rect.centerX() - paint.measureText(title) / 2f, rect.centerY() + iconSize * .48f, paint)
                                        paint.textSize = (rect.height() * .052f).coerceIn(11f, 18f)
                                        val help = "Your game or phone screen appears here"
                                        native.drawText(help, rect.centerX() - paint.measureText(help) / 2f, rect.centerY() + iconSize * .85f, paint)
                                    }
                                    "text" -> {
                                        paint.color = android.graphics.Color.WHITE
                                        paint.textSize = (rect.height() * .58f).coerceIn(12f, 48f)
                                        paint.setShadowLayer(4f, 1f, 1f, android.graphics.Color.BLACK)
                                        val baseline = rect.top + (rect.height() - (paint.descent() + paint.ascent())) / 2f
                                        native.drawText(item.text.take(36), rect.left + 4f, baseline, paint)
                                        paint.clearShadowLayer()
                                    }
                                    "camera" -> {
                                        paint.color = android.graphics.Color.rgb(54, 83, 103)
                                        native.drawRoundRect(rect, 10f, 10f, paint)
                                        paint.color = android.graphics.Color.WHITE
                                        paint.textSize = (rect.height() * .16f).coerceIn(10f, 24f)
                                        native.drawText("CAMERA SOURCE", left + 8f, rect.centerY(), paint)
                                    }
                                    "image", "gif" -> {
                                        paint.color = android.graphics.Color.rgb(80, 66, 116)
                                        native.drawRoundRect(rect, 10f, 10f, paint)
                                        paint.color = android.graphics.Color.WHITE
                                        paint.textSize = 12f
                                        native.drawText(item.kind.uppercase() + " · " + item.name, left + 8f, rect.centerY(), paint)
                                    }
                                    else -> {
                                        paint.color = android.graphics.Color.WHITE
                                        paint.textSize = 12f
                                        native.drawText("SCREEN CAPTURE", left + 8f, top + 22f, paint)
                                    }
                                }
                            }
                            paint.style = Paint.Style.STROKE
                            paint.strokeWidth = if (item.id == selected) 3f else 1.5f
                            paint.color = if (item.id == selected) android.graphics.Color.CYAN else android.graphics.Color.argb(175, 210, 224, 245)
                            native.drawRoundRect(rect, 10f, 10f, paint)
                            if (item.id == selected && !item.locked) {
                                paint.style = Paint.Style.FILL
                                val handleSize = 28f
                                paint.color = android.graphics.Color.WHITE
                                val handles = listOf(
                                    rect.left to rect.top, rect.centerX() to rect.top, rect.right to rect.top,
                                    rect.left to rect.centerY(), rect.right to rect.centerY(),
                                    rect.left to rect.bottom, rect.centerX() to rect.bottom, rect.right to rect.bottom
                                )
                                handles.forEach { (hx, hy) ->
                                    native.drawRect(hx - handleSize / 2f, hy - handleSize / 2f, hx + handleSize / 2f, hy + handleSize / 2f, paint)
                                    paint.color = android.graphics.Color.rgb(20, 35, 55)
                                    native.drawRect(hx - 3f, hy - 3f, hx + 3f, hy + 3f, paint)
                                    paint.color = android.graphics.Color.WHITE
                                }
                            }
                            paint.style = Paint.Style.FILL
                            native.restore()
                        }
                    }
                }
                Text("Large touch handles: drag any corner or side grip to resize. Drag inside the layer to move; import free image/GIF overlays from Free Assets.", color = Color(0xFFB8C5D8), fontSize = 10.sp)
                if (sourceMessage.isNotBlank()) Text(sourceMessage, color = Color(0xFFFFC46B), fontSize = 10.sp)
                if (scene.layers.isEmpty()) Text("Abhi empty scene hai. + Add source se screen, text ya image add karo.", color = Color(0xFFFFC46B), fontSize = 11.sp)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("LAYERS", color = Color.White, fontSize = 14.sp)
                Text("Top layer appears above lower layers.", color = Color.Gray, fontSize = 10.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { assetsDialog = true }) { Text("FREE ASSETS") }
                Button(onClick = { addDialog = true }) { Text("+ Add source") }
            }
        }
        scene.layers.asReversed().forEach { item ->
            Surface(
                color = if (item.id == selected) Color(0xFF253A55) else Color(0xFF151B25),
                shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth(),
                onClick = { if (!item.locked) selected = item.id }
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (item.visible) "◉" else "○", color = Color(0xFF8EA5FF))
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(item.name + if (item.locked) "  ·  LOCKED" else "", color = Color.White, fontSize = 12.sp)
                        Text(if (item.kind == "screen") "YOUR PHONE / GAME SCREEN" else item.kind.uppercase(), color = Color.Gray, fontSize = 9.sp)
                    }
                    TextButton(onClick = { updateLayer(item.id) { it.copy(visible = !it.visible) } }) { Text(if (item.visible) "Hide" else "Show") }
                    TextButton(onClick = {
                        val locking = !item.locked
                        updateLayer(item.id) { it.copy(locked = locking) }
                        if (locking && selected == item.id) selected = -1L
                    }) { Text(if (item.locked) "Unlock" else "Lock") }
                }
            }
        }

        if (layer != null) {
            Surface(color = Color(0xFF151B25), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("SELECTED: ${layer.name}", color = Color.White, fontSize = 13.sp)
                    if (layer.kind == "text") {
                        OutlinedTextField(
                            value = layer.text, enabled = !layer.locked, onValueChange = { value -> updateLayer(layer.id) { it.copy(text = value) } },
                            label = { Text("Text shown on screen") }, modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (layer.kind == "image" || layer.kind == "gif") {
                        Text("Image/GIF source added. Replace to choose another visual.", color = Color(0xFFB8C5D8), fontSize = 11.sp)
                        OutlinedButton(onClick = { imagePicker.launch(arrayOf("image/*")) }, enabled = !layer.locked, modifier = Modifier.fillMaxWidth()) { Text("Replace image / GIF") }
                    }
                    Text("Width · ${(layer.width * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                    Slider(value = layer.width, enabled = !layer.locked, onValueChange = { v -> updateLayer(layer.id) { it.copy(width = v.coerceIn(.08f, 1f), x = it.x.coerceAtMost(1f - v)) } }, valueRange = .08f..1f)
                    Text("Height · ${(layer.height * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                    Slider(value = layer.height, enabled = !layer.locked, onValueChange = { v -> updateLayer(layer.id) { it.copy(height = v.coerceIn(.05f, 1f), y = it.y.coerceAtMost(1f - v)) } }, valueRange = .05f..1f)
                    if (layer.width < .999f) {
                        Text("Position X · ${(layer.x * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                        Slider(value = layer.x, enabled = !layer.locked, onValueChange = { v -> updateLayer(layer.id) { it.copy(x = v.coerceIn(0f, 1f - it.width)) } }, valueRange = 0f..(1f - layer.width).coerceAtLeast(0f))
                    } else Text("Position X · Full width", color = Color.Gray, fontSize = 11.sp)
                    if (layer.height < .999f) {
                        Text("Position Y · ${(layer.y * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                        Slider(value = layer.y, enabled = !layer.locked, onValueChange = { v -> updateLayer(layer.id) { it.copy(y = v.coerceIn(0f, 1f - it.height)) } }, valueRange = 0f..(1f - layer.height).coerceAtLeast(0f))
                    } else Text("Position Y · Full height", color = Color.Gray, fontSize = 11.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (layer.locked) "POSITION LOCKED" else "EDITING UNLOCKED", color = if (layer.locked) Color(0xFFFFC46B) else Color(0xFF55D6A1), modifier = Modifier.weight(1f), fontSize = 10.sp)
                        OutlinedButton(onClick = {
                            val locking = !layer.locked
                            updateLayer(layer.id) { it.copy(locked = locking) }
                            if (locking) selected = -1L
                        }) { Text(if (layer.locked) "UNLOCK TO EDIT" else "LOCK POSITION") }
                    }
                    Text("Rotation · ${layer.rotation.toInt()}°", color = Color.White, fontSize = 11.sp)
                    Slider(value = layer.rotation, enabled = !layer.locked, onValueChange = { v -> updateLayer(layer.id) { it.copy(rotation = v) } }, valueRange = -180f..180f)
                    Text("Crop left · ${(layer.cropLeft * 100).toInt()}%", color = Color.White, fontSize = 10.sp)
                    Slider(value = layer.cropLeft, enabled = !layer.locked, onValueChange = { v -> updateLayer(layer.id) { it.copy(cropLeft = v.coerceIn(0f, it.cropRight - .02f)) } }, valueRange = 0f..0.95f)
                    Text("Crop top · ${(layer.cropTop * 100).toInt()}%", color = Color.White, fontSize = 10.sp)
                    Slider(value = layer.cropTop, enabled = !layer.locked, onValueChange = { v -> updateLayer(layer.id) { it.copy(cropTop = v.coerceIn(0f, it.cropBottom - .02f)) } }, valueRange = 0f..0.95f)
                    Text("Crop right · ${(layer.cropRight * 100).toInt()}%", color = Color.White, fontSize = 10.sp)
                    Slider(value = layer.cropRight, enabled = !layer.locked, onValueChange = { v -> updateLayer(layer.id) { it.copy(cropRight = v.coerceIn(it.cropLeft + .02f, 1f)) } }, valueRange = .05f..1f)
                    Text("Crop bottom · ${(layer.cropBottom * 100).toInt()}%", color = Color.White, fontSize = 10.sp)
                    Slider(value = layer.cropBottom, enabled = !layer.locked, onValueChange = { v -> updateLayer(layer.id) { it.copy(cropBottom = v.coerceIn(it.cropTop + .02f, 1f)) } }, valueRange = .05f..1f)
                    Text("Opacity · ${(layer.opacity * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                    Slider(value = layer.opacity, enabled = !layer.locked, onValueChange = { v -> updateLayer(layer.id) { it.copy(opacity = v) } })
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        OutlinedButton(onClick = {
                            val items = scene.layers.toMutableList(); val i = items.indexOfFirst { it.id == layer.id }
                            if (i > 0) { val moving = items.removeAt(i); items.add(i - 1, moving); scenes[index] = scene.copy(layers = items) }
                        }, enabled = !layer.locked, modifier = Modifier.weight(1f)) { Text("Move up") }
                        OutlinedButton(onClick = {
                            val items = scene.layers.toMutableList(); val i = items.indexOfFirst { it.id == layer.id }
                            if (i >= 0 && i < items.lastIndex) { val moving = items.removeAt(i); items.add(i + 1, moving); scenes[index] = scene.copy(layers = items) }
                        }, enabled = !layer.locked, modifier = Modifier.weight(1f)) { Text("Move down") }
                        OutlinedButton(onClick = { scenes[index] = scene.copy(layers = scene.layers.filterNot { it.id == layer.id }); selected = -1L }, enabled = !layer.locked) { Text("Delete") }
                    }
                }
            }
        }

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
        Text("Unlock a layer. Drag it to move; drag the bottom-right dot with one finger to resize, or pinch with two fingers. Twist to rotate.", color = Color.Gray, fontSize = 9.sp)
    }

    if (assetsDialog) AlertDialog(
        onDismissRequest = { assetsDialog = false },
        title = { Text("Free overlay assets") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Built-in 16:9 screens and borders. Add your own PNG, JPG or animated GIF from Add source.", color = Color.Gray, fontSize = 11.sp)
            TextButton(onClick = { addPreset("Starting Soon", "STREAM STARTING SOON", "Thanks for waiting · We will be live shortly") }, modifier = Modifier.fillMaxWidth()) { Text("Starting Soon screen") }
            TextButton(onClick = { addPreset("BRB", "BE RIGHT BACK", "Taking a short break · Stay tuned") }, modifier = Modifier.fillMaxWidth()) { Text("BRB screen") }
            TextButton(onClick = { addPreset("Stream Ending", "THANKS FOR WATCHING", "Like · Follow · See you next time") }, modifier = Modifier.fillMaxWidth()) { Text("Stream Ending screen") }
            TextButton(onClick = { addPreset("Neon Border", "YOUR STREAM", "Customise this title and add your own GIF") }, modifier = Modifier.fillMaxWidth()) { Text("Neon border overlay") }
            TextButton(onClick = { assetsDialog = false; imagePicker.launch(arrayOf("image/gif")) }, modifier = Modifier.fillMaxWidth()) { Text("Import animated GIF overlay") }
            TextButton(onClick = { assetsDialog = false; imagePicker.launch(arrayOf("image/png", "image/jpeg", "image/webp")) }, modifier = Modifier.fillMaxWidth()) { Text("Import image / frame") }
        } },
        confirmButton = { TextButton(onClick = { assetsDialog = false }) { Text("Close") } },
        containerColor = Color(0xFF151B25), titleContentColor = Color.White, textContentColor = Color.White
    )

    if (addDialog) AlertDialog(
        onDismissRequest = { addDialog = false }, title = { Text("Add to this scene") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Choose what you want to place on the canvas.", color = Color.Gray, fontSize = 11.sp)
            listOf("screen" to "Game capture / screen", "text" to "Text overlay", "image" to "Image or animated GIF", "camera" to "Camera placeholder").forEach { (kind, label) ->
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
