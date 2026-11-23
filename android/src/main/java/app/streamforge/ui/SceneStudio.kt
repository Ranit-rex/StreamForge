package app.streamforge.ui

import app.streamforge.stream.StreamService

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.LinearGradient
import android.graphics.Shader
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import android.graphics.SurfaceTexture
import android.view.TextureView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class SceneLayer(
    val id: Long, val name: String, val kind: String, val text: String = "",
    val x: Float = .08f, val y: Float = .08f, val width: Float = .52f, val height: Float = .22f, val opacity: Float = 1f,
    val visible: Boolean = true, val locked: Boolean = false,
    val rotation: Float = 0f, val cropLeft: Float = 0f, val cropTop: Float = 0f, val cropRight: Float = 1f, val cropBottom: Float = 1f,
    val textColor: String = "#FFFFFFFF", val fontFamily: String = "sans-serif", val fontSizeScale: Float = 1f,
    val loop: Boolean = true, val fps: Int = 5, val soundVolume: Float = 1f, val soundMuted: Boolean = false
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
                put("textColor", layer.textColor); put("fontFamily", layer.fontFamily); put("fontSizeScale", layer.fontSizeScale); put("loop", layer.loop); put("fps", layer.fps); put("soundVolume", layer.soundVolume); put("soundMuted", layer.soundMuted)
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
                    item.optBoolean("visible", true), item.optBoolean("locked", false), item.optDouble("rotation", 0.0).toFloat(), item.optDouble("cropLeft", 0.0).toFloat(), item.optDouble("cropTop", 0.0).toFloat(), item.optDouble("cropRight", 1.0).toFloat(), item.optDouble("cropBottom", 1.0).toFloat(),
                    item.optString("textColor", "#FFFFFFFF"), item.optString("fontFamily", "sans-serif"), item.optDouble("fontSizeScale", 1.0).toFloat(), item.optBoolean("loop", true), item.optInt("fps", if (item.optString("kind") == "video") 0 else 5).coerceIn(0, 60), item.optDouble("soundVolume", 1.0).toFloat().coerceIn(0f, 1f), item.optBoolean("soundMuted", false))
            })
    }
}.getOrDefault(sceneDefaults()).ifEmpty { sceneDefaults() }


/**
 * Keeps scene video players alive while the user moves between Studio pages.
 * TextureView surfaces may be detached/recreated, but the MediaPlayer itself is retained.
 */
private object SceneVideoPlayback {
    class State internal constructor(val uri: String, val player: MediaPlayer) {
        val ready = mutableStateOf(false)
        val playing = mutableStateOf(false)
        val positionMs = mutableIntStateOf(0)
        val durationMs = mutableIntStateOf(0)
        val error = mutableStateOf<String?>(null)
        var wantsPlayback: Boolean = true
        var requestedVolume: Float = 0f
        var requestedMuted: Boolean = false
        var surfaceTexture: SurfaceTexture? = null
        var surface: Surface? = null
    }

    private val states = mutableMapOf<String, State>()

    @Synchronized
    fun get(context: android.content.Context, uri: String, loop: Boolean): State {
        val existing = states[uri]
        if (existing != null) {
            runCatching { existing.player.isLooping = loop }
            return existing
        }
        val player = MediaPlayer()
        val state = State(uri, player)
        states[uri] = state
        runCatching {
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        setAllowedCapturePolicy(android.media.AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                    }
                }.build()
            player.setAudioAttributes(audioAttributes)
            player.setVolume(0f, 0f)
            player.isLooping = loop
            player.setOnPreparedListener { media ->
                state.durationMs.value = media.duration.coerceAtLeast(0)
                state.ready.value = true
                if (state.wantsPlayback) {
                    runCatching { media.start(); state.playing.value = true }
                }
            }
            player.setOnCompletionListener { media ->
                state.positionMs.value = media.duration.coerceAtLeast(0)
                state.playing.value = false
            }
            player.setOnErrorListener { _, what, extra ->
                state.error.value = "Playback error ($what/$extra)"
                state.playing.value = false
                true
            }
            player.setDataSource(context, Uri.parse(uri))
            player.prepareAsync()
        }.onFailure {
            state.error.value = it.message ?: "Could not open video"
        }
        return state
    }

    @Synchronized
    fun attach(context: android.content.Context, uri: String, loop: Boolean, texture: SurfaceTexture) {
        val state = get(context, uri, loop)
        if (state.surfaceTexture === texture) return
        runCatching { state.surface?.let { state.player.setSurface(null); it.release() } }
        val surface = Surface(texture)
        state.surfaceTexture = texture
        state.surface = surface
        runCatching {
            state.player.setSurface(surface)
            if (state.ready.value && state.wantsPlayback && !state.player.isPlaying) {
                state.player.start()
                state.playing.value = true
            }
        }.onFailure {
            state.error.value = it.message ?: "Could not attach video surface"
        }
    }

    @Synchronized
    fun detach(uri: String, texture: SurfaceTexture) {
        val state = states[uri] ?: return
        if (state.surfaceTexture !== texture) return
        runCatching { state.player.setSurface(null) }
        runCatching { state.surface?.release() }
        state.surface = null
        state.surfaceTexture = null
    }

    @Volatile
    private var appInForeground: Boolean = true

    private fun applyVolume(state: State) {
        val audible = appInForeground && !state.requestedMuted
        val volume = if (audible) state.requestedVolume.coerceIn(0f, 1f) else 0f
        runCatching { state.player.setVolume(volume, volume) }
    }

    @Synchronized
    fun pauseForBackground() {
        // Keep video frames advancing while backgrounded, but silence preview audio.
        appInForeground = false
        states.values.forEach(::applyVolume)
    }

    @Synchronized
    fun resumeInForeground() {
        appInForeground = true
        states.values.forEach { state ->
            applyVolume(state)
            if (state.wantsPlayback && state.ready.value) {
                runCatching {
                    if (!state.player.isPlaying) state.player.start()
                    state.playing.value = true
                }
            }
        }
    }

    fun setAudio(state: State, volume: Float, muted: Boolean) {
        state.requestedVolume = volume.coerceIn(0f, 1f)
        state.requestedMuted = muted
        applyVolume(state)
    }

    fun toggle(state: State) {
        state.wantsPlayback = !state.wantsPlayback
        if (!state.ready.value) return
        runCatching {
            if (state.wantsPlayback) {
                if (!state.player.isPlaying) state.player.start()
                state.playing.value = true
            } else {
                if (state.player.isPlaying) state.player.pause()
                state.playing.value = false
            }
        }.onFailure { state.error.value = it.message ?: "Playback control failed" }
    }

    fun seek(state: State, positionMs: Int) {
        val target = positionMs.coerceIn(0, state.durationMs.value.coerceAtLeast(0))
        state.positionMs.value = target
        if (state.ready.value) runCatching { state.player.seekTo(target) }
    }

    fun refresh(state: State) {
        if (state.ready.value) {
            runCatching {
                state.positionMs.value = state.player.currentPosition.coerceIn(0, state.durationMs.value.coerceAtLeast(0))
                state.playing.value = state.player.isPlaying
            }
        }
    }
}

@Composable
private fun SceneVideoPlaybackCard(
    uri: String,
    loop: Boolean,
    onUseHardware: () -> Unit,
    volume: Float,
    muted: Boolean
) {
    val context = LocalContext.current
    val state = remember(uri) { SceneVideoPlayback.get(context, uri, loop) }
    LaunchedEffect(state, volume, muted) { SceneVideoPlayback.setAudio(state, volume, muted) }
    LaunchedEffect(state) {
        while (isActive) {
            SceneVideoPlayback.refresh(state)
            delay(250L)
        }
    }
    Surface(color = Color(0xFF202832), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("VIDEO PLAYER", color = Color.White, fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    if (state.ready.value) "READY" else if (state.error.value != null) "ERROR" else "LOADING",
                    color = if (state.error.value != null) Color(0xFFFF8A80) else Color(0xFF9FB5CE),
                    fontSize = 9.sp
                )
            }
            Slider(
                value = state.positionMs.value.toFloat().coerceIn(0f, state.durationMs.value.toFloat().coerceAtLeast(1f)),
                onValueChange = { onUseHardware(); SceneVideoPlayback.seek(state, it.toInt()) },
                valueRange = 0f..state.durationMs.value.toFloat().coerceAtLeast(1f),
                enabled = state.ready.value
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatSceneVideoTime(state.positionMs.value), color = Color.White, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                Text(formatSceneVideoTime(state.durationMs.value), color = Color.Gray, fontSize = 10.sp)
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onUseHardware(); SceneVideoPlayback.toggle(state) },
                    enabled = state.ready.value,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 3.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(if (state.playing.value) "Ⅱ Pause" else "▶ Play", fontSize = 10.sp)
                }
            }
            state.error.value?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 9.sp) }
        }
    }
}

private fun formatSceneVideoTime(milliseconds: Int): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0) / 1000)
    return String.format(java.util.Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}


@Composable
fun SceneCompositionPreview(modifier: Modifier = Modifier, compact: Boolean = false) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> SceneVideoPlayback.resumeInForeground()
                Lifecycle.Event.ON_STOP -> SceneVideoPlayback.pauseForBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            SceneVideoPlayback.pauseForBackground()
        }
    }
    val prefs = remember { context.getSharedPreferences("streamforge_studio", 0) }
    var savedScenes by remember { mutableStateOf(decodeSceneList(prefs.getString("scenes", "") ?: "")) }
    var activeIndex by remember { mutableIntStateOf(prefs.getInt("active_scene", 0)) }
    var previewMode by remember { mutableStateOf(prefs.getString("video_preview_mode", "decode") ?: "decode") }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { shared, key ->
            if (key == "scenes" || key == "active_scene") {
                savedScenes = decodeSceneList(shared.getString("scenes", "") ?: "")
                activeIndex = shared.getInt("active_scene", 0)
            }
            if (key == "video_preview_mode") {
                previewMode = shared.getString("video_preview_mode", "decode") ?: "decode"
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
    val videoFrames = remember { androidx.compose.runtime.mutableStateMapOf<Long, Bitmap>() }
    val videoErrors = remember { androidx.compose.runtime.mutableStateMapOf<Long, String>() }
    val videoSources = remember(scene.layers.filter { it.kind == "video" }.map { it.id to (it.text to (it.loop to it.fps)) }) {
        scene.layers.filter { it.kind == "video" && it.visible && it.text.isNotBlank() }
            .map { Triple(it.id, it.text, Pair(it.loop, it.fps)) }
    }
    // The retained native player is the persistent playback clock in both modes.
    // Frame-decode preview samples this clock instead of restarting at zero on navigation.
    val videoPlayers = remember(videoSources) {
        videoSources.associate { (id, uriString, settings) ->
            id to SceneVideoPlayback.get(context, uriString, settings.first)
        }
    }
    LaunchedEffect(videoSources, previewMode) {
        if (previewMode != "decode") {
            videoFrames.values.toList().forEach { if (!it.isRecycled) it.recycle() }
            videoFrames.clear()
            return@LaunchedEffect
        }
        val sources = videoSources.mapNotNull { (id, uriString, settings) ->
            runCatching {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, Uri.parse(uriString))
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceAtLeast(1L) ?: 1L
                Triple(id, retriever, Pair(duration, settings))
            }.getOrNull()
        }
        try {
            while (isActive && sources.isNotEmpty()) {
                val currentPositions = videoPlayers.mapValues { (_, state) ->
                    if (state.ready.value) {
                        runCatching { state.player.currentPosition.toLong() }
                            .getOrDefault(state.positionMs.value.toLong())
                    } else 0L
                }
                val frames = withContext(Dispatchers.IO) {
                    sources.mapNotNull { (id, retriever, settings) ->
                        val duration = settings.first
                        val timeMs = (currentPositions[id] ?: 0L).coerceIn(0L, (duration - 1L).coerceAtLeast(0L))
                        // Program Preview is a small thumbnail surface. Never decode the
                        // source at its native resolution (large 4K clips can exhaust heap).
                        runCatching {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                                retriever.getScaledFrameAtTime(
                                    timeMs * 1000L,
                                    MediaMetadataRetriever.OPTION_CLOSEST,
                                    1920,
                                    1080
                                )
                            } else {
                                retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                                    ?.let { full ->
                                        Bitmap.createScaledBitmap(
                                            full,
                                            1920,
                                            (full.height * (1920f / full.width)).toInt().coerceAtLeast(1),
                                            true
                                        ).also { if (it !== full && !full.isRecycled) full.recycle() }
                                    }
                            }
                        }.getOrNull()?.let { id to it }
                    }
                }
                frames.forEach { (id, frame) ->
                    val previous = videoFrames.put(id, frame)
                    if (previous !== frame && previous?.isRecycled == false) previous.recycle()
                }
                // Target a 60 FPS preview refresh cadence. Actual decode FPS depends on
                // device decoder throughput and source frame rate.
                delay(16L)
            }
        } finally {
            sources.forEach { runCatching { it.second.release() } }
            videoFrames.values.toList().forEach { if (!it.isRecycled) it.recycle() }
            videoFrames.clear()
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            val previewWidth = maxWidth
            val previewHeight = maxHeight
            Canvas(Modifier.matchParentSize()) {
        drawRect(Color(0xFF101316))
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            }
            scene.layers.filter { it.visible && !(previewMode == "hardware" && it.kind == "video") }.forEach { item ->
                val rect = RectF(item.x * size.width, item.y * size.height,
                    (item.x + item.width) * size.width, (item.y + item.height) * size.height)
                native.save()
                native.rotate(item.rotation, rect.centerX(), rect.centerY())
                paint.alpha = (item.opacity.coerceIn(0f, 1f) * 255).toInt()
                when (item.kind) {
                    "background" -> {
                        paint.style = Paint.Style.FILL
                        paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, android.graphics.Color.rgb(13, 15, 17), android.graphics.Color.rgb(53, 29, 21), Shader.TileMode.CLAMP)
                        native.drawRect(rect, paint)
                        paint.shader = null
                        paint.color = android.graphics.Color.argb(55, 255, 106, 53)
                        native.drawCircle(rect.right * .82f, rect.top + rect.height() * .22f, rect.height() * .22f, paint)
                    }
                    "frame" -> {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = (rect.width() * .008f).coerceIn(3f, 10f)
                        paint.color = android.graphics.Color.rgb(255, 106, 53)
                        native.drawRoundRect(rect, 24f, 24f, paint)
                        paint.strokeWidth = (rect.width() * .0025f).coerceIn(1.5f, 4f)
                        paint.color = android.graphics.Color.rgb(255, 190, 145)
                        val corner = rect.width() * .10f
                        native.drawLine(rect.left, rect.top + corner, rect.left, rect.top, paint)
                        native.drawLine(rect.left, rect.top, rect.left + corner, rect.top, paint)
                        native.drawLine(rect.right - corner, rect.bottom, rect.right, rect.bottom, paint)
                        native.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - corner, paint)
                        paint.style = Paint.Style.FILL
                    }
                    "screen" -> {
                        paint.style = Paint.Style.FILL
                        paint.color = android.graphics.Color.rgb(30, 37, 41)
                        native.drawRoundRect(rect, 12f, 12f, paint)
                        paint.color = android.graphics.Color.rgb(255, 134, 89)
                        paint.textSize = (rect.height() * .15f).coerceIn(20f, 54f)
                        val title = "GAMEPLAY"
                        native.drawText(title, rect.centerX() - paint.measureText(title) / 2f, rect.centerY() - 5f, paint)
                        paint.color = android.graphics.Color.LTGRAY
                        paint.textSize = (rect.height() * .065f).coerceIn(12f, 24f)
                        val hint = "Your screen appears here when capture is active"
                        native.drawText(hint, rect.centerX() - paint.measureText(hint) / 2f, rect.centerY() + 26f, paint)
                    }
                    "text", "timer" -> {
                        paint.style = Paint.Style.FILL
                        paint.color = runCatching { android.graphics.Color.parseColor(item.textColor) }.getOrDefault(android.graphics.Color.WHITE)
                        paint.typeface = android.graphics.Typeface.create(item.fontFamily, android.graphics.Typeface.BOLD)
                        paint.textSize = ((rect.height() * .58f).coerceIn(12f, 48f) * item.fontSizeScale.coerceIn(.5f, 2f))
                        val shown = if (item.kind == "timer") {
                            val seconds = (System.currentTimeMillis() / 1000L) % 3600L
                            String.format(java.util.Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L)
                        } else item.text.take(40)
                        native.drawText(shown, rect.left + 5f, rect.centerY(), paint)
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
                    "video" -> {
                        val frame = videoFrames[item.id]
                        paint.style = Paint.Style.FILL
                        if (frame != null && !frame.isRecycled) {
                            val src = android.graphics.Rect(
                                (frame.width * item.cropLeft).toInt().coerceIn(0, frame.width - 1),
                                (frame.height * item.cropTop).toInt().coerceIn(0, frame.height - 1),
                                (frame.width * item.cropRight).toInt().coerceIn(1, frame.width),
                                (frame.height * item.cropBottom).toInt().coerceIn(1, frame.height)
                            )
                            native.drawBitmap(frame, src, rect, paint)
                        } else {
                            paint.color = android.graphics.Color.rgb(48, 65, 90)
                            native.drawRoundRect(rect, 10f, 10f, paint)
                            paint.color = android.graphics.Color.WHITE
                            paint.textSize = (rect.height() * .12f).coerceIn(12f, 22f)
                            val sourceError = videoErrors[item.id]
                            val statusText = if (sourceError != null) "VIDEO ERROR: ${sourceError.take(46)}" else "VIDEO PREVIEW LOADING"
                            paint.textSize = (rect.height() * .075f).coerceIn(9f, 16f)
                            native.drawText(statusText, rect.left + 8f, rect.centerY(), paint)
                        }
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
            if (previewMode == "hardware") {
                scene.layers.filter { it.visible && it.kind == "video" && it.text.isNotBlank() }.forEach { item ->
                    HardwareVideoPreview(
                        uriString = item.text,
                        loop = item.loop,
                        modifier = Modifier
                            .offset(x = previewWidth * item.x, y = previewHeight * item.y)
                            .size(width = previewWidth * item.width, height = previewHeight * item.height)
                            .graphicsLayer(rotationZ = item.rotation, alpha = item.opacity.coerceIn(0f, 1f))
                    )
                }
            }
        }
    }
}

@Composable
private fun HardwareVideoPreview(uriString: String, loop: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state = remember(uriString) { SceneVideoPlayback.get(context, uriString, loop) }
    Box(modifier.background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                TextureView(viewContext).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                            SceneVideoPlayback.attach(viewContext, uriString, loop, surfaceTexture)
                        }
                        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                            SceneVideoPlayback.detach(uriString, surfaceTexture)
                            return true
                        }
                    }
                }
            },
            update = { view ->
                view.surfaceTexture?.let { SceneVideoPlayback.attach(context, uriString, loop, it) }
            }
        )
        state.error.value?.let { Text(it, color = Color.White, fontSize = 9.sp, modifier = Modifier.align(Alignment.Center)) }
    }
}

@Composable
private fun SceneVideoPlaybackModeSelector() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("streamforge_studio", 0) }
    var mode by remember { mutableStateOf(prefs.getString("video_preview_mode", "decode") ?: "decode") }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { shared, key ->
            if (key == "video_preview_mode") mode = shared.getString(key, "decode") ?: "decode"
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("VIDEO PREVIEW", color = Color(0xFFA1A8AF), fontSize = 9.sp, modifier = Modifier.weight(1f))
        FilterChip(
            selected = mode == "decode",
            onClick = { mode = "decode"; prefs.edit().putString("video_preview_mode", "decode").apply() },
            label = { Text("DECODE", fontSize = 9.sp) }
        )
        FilterChip(
            selected = mode == "hardware",
            onClick = { mode = "hardware"; prefs.edit().putString("video_preview_mode", "hardware").apply() },
            label = { Text("HARDWARE", fontSize = 9.sp) }
        )
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
            val needsVideoFpsMigration = prefs.getInt("video_fps_mode_version", 0) < 1
            val resetLegacyDefaults = prefs.getInt("scene_layout_version", 0) < 3 && loaded.map { it.name } == listOf("Starting Soon", "Gameplay", "BRB", "Ending")
            val normalized = if (needsMigration || needsVideoFpsMigration) loaded.map { savedScene ->
                savedScene.copy(layers = savedScene.layers.map { savedLayer ->
                    when {
                        savedLayer.kind == "video" && needsVideoFpsMigration -> savedLayer.copy(fps = 0)
                        needsMigration && savedLayer.kind == "screen" -> savedLayer.copy(x = 0f, y = 0f, width = 1f, height = 1f)
                        needsMigration && savedLayer.kind == "text" -> savedLayer.copy(width = .42f, height = .16f)
                        needsMigration && savedLayer.kind == "camera" -> savedLayer.copy(width = .30f, height = .24f)
                        needsMigration && savedLayer.kind == "image" -> savedLayer.copy(width = .34f, height = .34f)
                        else -> savedLayer
                    }
                })
            } else loaded
            addAll(if (resetLegacyDefaults) sceneDefaults() else normalized)
            if (needsMigration || resetLegacyDefaults) prefs.edit().putInt("scene_layout_version", 3).apply()
            if (needsVideoFpsMigration) prefs.edit().putInt("video_fps_mode_version", 1).apply()
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
    var showMoreSettings by remember { mutableStateOf(false) }

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
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val idx = active.coerceIn(0, scenes.lastIndex)
            val current = scenes[idx]
            val fallbackName = "Video ${current.layers.count { it.kind == "video" } + 1}"
            val sourceName = runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0)?.substringBeforeLast('.', cursor.getString(0))?.takeIf { it.isNotBlank() } else null
                }
            }.getOrNull() ?: fallbackName
            val layer = SceneLayer(System.nanoTime(), sourceName, "video", uri.toString(), .12f, .12f, .42f, .32f, fps = 0)
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
        if (kind == "video") { videoPicker.launch(arrayOf("video/*")); return }
        val current = scenes[index]
        if (kind == "screen" && current.layers.any { it.kind == "screen" }) {
            selected = current.layers.first { it.kind == "screen" }.id
            sourceMessage = "This scene already has Game Capture. Unlock it before moving or resizing."
            return
        }
        val count = current.layers.count { it.kind == kind } + 1
        val item = SceneLayer(
            System.nanoTime(),
            when (kind) { "text" -> "Text ${count}"; "camera" -> "Camera ${count}"; "timer" -> "Timer ${count}"; else -> "Game Capture" },
            kind,
            if (kind == "text") "YOUR TEXT" else if (kind == "timer") "00:00" else "",
            if (kind == "screen") 0f else .08f,
            if (kind == "screen") 0f else .08f,
            when (kind) { "screen" -> 1f; "text" -> .42f; "camera" -> .30f; "timer" -> .22f; "video" -> .42f; else -> .34f },
            when (kind) { "screen" -> 1f; "text" -> .16f; "camera" -> .24f; "timer" -> .12f; "video" -> .32f; else -> .34f },
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

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { showMoreSettings = !showMoreSettings }, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp), modifier = Modifier.height(26.dp)) {
                Text(if (showMoreSettings) "⋯  Less" else "⋯", fontSize = 12.sp)
            }
        }
        if (showMoreSettings) {
            Surface(color = Color(0xFF15191D), shape = RoundedCornerShape(9.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                    Text("ADVANCED OPTIONS", color = Color(0xFFA1A8AF), fontSize = 9.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("Advanced controls are grouped here to keep the Scene page compact.", color = Color.Gray, fontSize = 9.sp)
                }
            }
        }
        if (scene.layers.any { it.kind == "video" }) {
            SceneVideoPlaybackModeSelector()
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.widthIn(max = 260.dp)) {
        if (isStreaming) {
            StudioPreviewSurface(
                previewReady = previewReady,
                isStreaming = true,
                onPrepare = onPreparePreview,
                onStop = onStopStudioPreview,
                onChangeSource = onChangeCaptureSource,
                compact = true
            )
        } else {
            SceneCompositionPreview(compact = true, modifier = Modifier.fillMaxWidth())
        }
        }
        }
        Surface(color = Color(0xFF15191D), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
                        .background(Color(0xFF111518), RoundedCornerShape(9.dp))
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
                                    val edgeTouch = 38f
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
                                            event.changes.filter { it.pressed }.forEach { it.consume() }
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
                    drawRect(Color(0xFF20262B))
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
                                        paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, android.graphics.Color.rgb(13, 15, 17), android.graphics.Color.rgb(53, 29, 21), Shader.TileMode.CLAMP)
                                        native.drawRect(rect, paint)
                                        paint.shader = null
                                        paint.color = android.graphics.Color.argb(55, 255, 106, 53)
                                        native.drawCircle(rect.right * .82f, rect.top + rect.height() * .22f, rect.height() * .22f, paint)
                                    }
                                    "frame" -> {
                                        paint.style = Paint.Style.STROKE
                                        paint.strokeWidth = 5f
                                        paint.color = android.graphics.Color.rgb(255, 106, 53)
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
                                    "text", "timer" -> {
                                        paint.color = runCatching { android.graphics.Color.parseColor(item.textColor) }.getOrDefault(android.graphics.Color.WHITE)
                                        paint.typeface = android.graphics.Typeface.create(item.fontFamily, android.graphics.Typeface.BOLD)
                                        paint.textSize = ((rect.height() * .58f).coerceIn(12f, 48f) * item.fontSizeScale.coerceIn(.5f, 2f))
                                        paint.setShadowLayer(4f, 1f, 1f, android.graphics.Color.BLACK)
                                        val baseline = rect.top + (rect.height() - (paint.descent() + paint.ascent())) / 2f
                                        val shown = if (item.kind == "timer") {
                                            val seconds = (System.currentTimeMillis() / 1000L) % 3600L
                                            String.format(java.util.Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L)
                                        } else item.text.take(36)
                                        native.drawText(shown, rect.left + 4f, baseline, paint)
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
                                        native.drawText("GAMEPLAY AREA", left + 8f, top + 22f, paint)
                                    }
                                }
                            }
                            paint.style = Paint.Style.STROKE
                            paint.strokeWidth = if (item.id == selected) 3f else 1.5f
                            paint.color = if (item.id == selected) android.graphics.Color.CYAN else android.graphics.Color.argb(175, 210, 224, 245)
                            native.drawRoundRect(rect, 10f, 10f, paint)
                            if (item.kind == "video" || item.kind == "image" || item.kind == "gif") {
                                paint.style = Paint.Style.FILL
                                paint.color = android.graphics.Color.argb(210, 8, 14, 22)
                                val labelHeight = 30f.coerceAtMost(rect.height() * .28f)
                                native.drawRoundRect(RectF(rect.left, rect.top, rect.right, rect.top + labelHeight), 8f, 8f, paint)
                                paint.color = android.graphics.Color.WHITE
                                paint.textSize = 12f
                                paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                                native.drawText(item.kind.uppercase() + " · " + item.name.take(16) + " · ID " + item.id.toString().takeLast(7), rect.left + 8f, rect.top + labelHeight * .68f, paint)
                            }
                            if (item.id == selected && !item.locked) {
                                paint.style = Paint.Style.FILL
                                val handleSize = 48f
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
                Text("Large touch handles: drag any corner or side grip to resize. Drag inside the layer to move; import free image/GIF overlays from Free Assets.", color = Color(0xFFA1A8AF), fontSize = 10.sp)
                if (sourceMessage.isNotBlank()) Text(sourceMessage, color = Color(0xFFFFB27F), fontSize = 10.sp)
                if (scene.layers.isEmpty()) Text("Abhi empty scene hai. + Add source se screen, text ya image add karo.", color = Color(0xFFFFB27F), fontSize = 11.sp)
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            scenes.forEachIndexed { i, item ->
                Surface(
                    color = if (i == index) Color(0xFF35231D) else Color(0xFF20262B),
                    shape = RoundedCornerShape(9.dp),
                    onClick = { active = i; selected = -1L }
                ) {
                    Text("Scene ${i + 1} · ${item.name}", color = if (i == index) Color(0xFFFFA27A) else Color.White,
                        fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
                }
            }
            Surface(color = Color(0xFF20262B), shape = RoundedCornerShape(9.dp), onClick = {
                scenes.add(SceneModel(System.nanoTime(), "Scene ${scenes.size + 1}", emptyList()))
                active = scenes.lastIndex; selected = -1L
            }) { Text("+", color = Color(0xFFFFA27A), fontSize = 16.sp, modifier = Modifier.padding(horizontal = 13.dp, vertical = 5.dp)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Top) {
            Surface(
                color = Color(0xFF15191D), shape = RoundedCornerShape(11.dp),
                modifier = Modifier.weight(.78f).heightIn(min = 170.dp, max = 300.dp)
            ) {
                Column(Modifier.padding(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("LAYERS", color = Color.White, fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { assetsDialog = true }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("Assets", fontSize = 9.sp) }
                        TextButton(onClick = { addDialog = true }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("+ Add", fontSize = 9.sp) }
                    }
                    if (scene.layers.isEmpty()) {
                        Text("No layers yet", color = Color.Gray, fontSize = 10.sp)
                    } else {
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            scene.layers.asReversed().forEach { item ->
                                var reorderDrag by remember(item.id) { mutableFloatStateOf(0f) }
                                Surface(
                                    color = if (item.id == selected) Color(0xFF35231D) else Color(0xFF20262B),
                                    shape = RoundedCornerShape(5.dp),
                                    modifier = Modifier.fillMaxWidth().pointerInput(item.id, scene.layers) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { selected = item.id; reorderDrag = 0f },
                                            onDragEnd = { reorderDrag = 0f },
                                            onDragCancel = { reorderDrag = 0f },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                reorderDrag += dragAmount.y
                                                if (reorderDrag >= 28f || reorderDrag <= -28f) {
                                                    val currentItems = scenes[index].layers.toMutableList()
                                                    val currentIndex = currentItems.indexOfFirst { it.id == item.id }
                                                    val step = if (reorderDrag > 0f) -1 else 1
                                                    val targetIndex = (currentIndex + step).coerceIn(0, currentItems.lastIndex)
                                                    if (currentIndex >= 0 && currentIndex != targetIndex) {
                                                        val moving = currentItems.removeAt(currentIndex)
                                                        currentItems.add(targetIndex, moving)
                                                        scenes[index] = scenes[index].copy(layers = currentItems)
                                                    }
                                                    reorderDrag = 0f
                                                }
                                            }
                                        )
                                    },
                                    onClick = { selected = item.id }
                                ) {
                                    Row(Modifier.padding(horizontal = 2.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                            TextButton(onClick = {
                                                val items = scenes[index].layers.toMutableList()
                                                val pos = items.indexOfFirst { it.id == item.id }
                                                if (pos > 0) { val moving = items.removeAt(pos); items.add(pos - 1, moving); scenes[index] = scenes[index].copy(layers = items) }
                                            }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(20.dp)) {
                                                Text("↑", color = Color(0xFFFFA27A), fontSize = 11.sp)
                                            }
                                            TextButton(onClick = {
                                                val items = scenes[index].layers.toMutableList()
                                                val pos = items.indexOfFirst { it.id == item.id }
                                                if (pos >= 0 && pos < items.lastIndex) { val moving = items.removeAt(pos); items.add(pos + 1, moving); scenes[index] = scenes[index].copy(layers = items) }
                                            }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(20.dp)) {
                                                Text("↓", color = Color(0xFFFFA27A), fontSize = 11.sp)
                                            }
                                        }
                                        Column(Modifier.weight(1f).padding(start = 3.dp)) {
                                            Text(item.name + if (item.locked) " · 🔒" else "", color = Color.White, fontSize = 8.sp, maxLines = 1)
                                        }
                                        TextButton(onClick = { updateLayer(item.id) { it.copy(visible = !it.visible) } },
                                            contentPadding = PaddingValues(0.dp), modifier = Modifier.size(24.dp)) {
                                            Text(if (item.visible) "●" else "○", color = if (item.visible) Color(0xFF65D6A4) else Color.Gray, fontSize = 11.sp)
                                        }
                                        TextButton(onClick = {
                                            val locking = !item.locked
                                            updateLayer(item.id) { it.copy(locked = locking) }
                                            if (locking) selected = item.id
                                        }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(24.dp)) {
                                            Text(if (item.locked) "🔒" else "↗", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        if (layer != null) {
            Surface(color = Color(0xFF15191D), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1.55f).heightIn(min = 170.dp, max = 300.dp)) {
                Column(Modifier.padding(7.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("LAYER SETTINGS", color = Color.White, fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("EDITING · ${layer.name}", color = Color(0xFFFFA27A), fontSize = 9.sp, maxLines = 1)
                    if (layer.kind == "text" || layer.kind == "timer") {
                        OutlinedTextField(
                            value = layer.text, enabled = !layer.locked && layer.kind == "text", onValueChange = { value -> updateLayer(layer.id) { it.copy(text = value) } },
                            label = { Text(if (layer.kind == "timer") "Timer label" else "Text shown on screen") }, modifier = Modifier.fillMaxWidth()
                        )
                        Text("TEXT COLOR", color = Color(0xFFA1A8AF), fontSize = 9.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("#FFFFFFFF", "#FFFFD166", "#FF68DDFD", "#FFFF6B8A", "#FF9BFFB0", "#FFB99CFF").forEach { hex ->
                                Surface(
                                    modifier = Modifier.size(28.dp),
                                    shape = RoundedCornerShape(50),
                                    color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.White),
                                    onClick = { updateLayer(layer.id) { it.copy(textColor = hex) } },
                                    border = androidx.compose.foundation.BorderStroke(if (layer.textColor == hex) 2.dp else 0.dp, Color.White)
                                ) {}
                            }
                        }
                        Text("FONT", color = Color(0xFFA1A8AF), fontSize = 9.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            listOf("sans-serif" to "Modern", "serif" to "Serif", "monospace" to "Mono", "casual" to "Casual").forEach { (family, label) ->
                                FilterChip(selected = layer.fontFamily == family, onClick = { updateLayer(layer.id) { it.copy(fontFamily = family) } }, label = { Text(label, fontSize = 10.sp) })
                            }
                        }
                        Text("FONT SIZE · ${(layer.fontSizeScale * 100).toInt()}%", color = Color.White, fontSize = 10.sp)
                        Slider(value = layer.fontSizeScale, enabled = !layer.locked, onValueChange = { v -> updateLayer(layer.id) { it.copy(fontSizeScale = v) } }, valueRange = .5f..2f)
                    }
                    if (layer.kind == "image" || layer.kind == "gif" || layer.kind == "video") {
                        Text("${layer.kind.uppercase()} source added. Replace to choose another visual.", color = Color(0xFFA1A8AF), fontSize = 11.sp)
                        OutlinedButton(onClick = { if (layer.kind == "video") videoPicker.launch(arrayOf("video/*")) else imagePicker.launch(arrayOf("image/*")) }, enabled = !layer.locked, modifier = Modifier.fillMaxWidth()) { Text("Replace media") }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Loop playback", color = Color.White, modifier = Modifier.weight(1f), fontSize = 11.sp)
                            Switch(checked = layer.loop, enabled = !layer.locked, onCheckedChange = { value -> updateLayer(layer.id) { it.copy(loop = value) } })
                        }
                        if (layer.kind == "video") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (layer.soundMuted) "VIDEO SOUND · OFF" else "VIDEO SOUND · ${(layer.soundVolume * 100).toInt()}%", color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                Switch(checked = !layer.soundMuted, enabled = !layer.locked, onCheckedChange = { enabled -> updateLayer(layer.id) { it.copy(soundMuted = !enabled) } })
                            }
                            Slider(value = layer.soundVolume, enabled = !layer.locked && !layer.soundMuted, onValueChange = { value -> updateLayer(layer.id) { it.copy(soundVolume = value.coerceIn(0f, 1f)) } }, valueRange = 0f..1f)
                            SceneVideoPlaybackCard(
                                uri = layer.text,
                                loop = layer.loop,
                                onUseHardware = {
                                    prefs.edit().putString("video_preview_mode", "hardware").apply()
                                },
                                volume = layer.soundVolume,
                                muted = layer.soundMuted
                            )
                            if (showMoreSettings) {
                            Text(
                                if (layer.fps == 0) "VIDEO FPS · ORIGINAL SOURCE (AUTO)" else "VIDEO FPS · ${layer.fps}",
                                color = Color.White, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = layer.fps == 0,
                                    onClick = { updateLayer(layer.id) { it.copy(fps = 0) } },
                                    enabled = !layer.locked,
                                    label = { Text("Original FPS", fontSize = 11.sp) }
                                )
                                FilterChip(
                                    selected = layer.fps != 0,
                                    onClick = { updateLayer(layer.id) { it.copy(fps = 30) } },
                                    enabled = !layer.locked,
                                    label = { Text("Custom FPS", fontSize = 11.sp) }
                                )
                            }
                            if (layer.fps != 0) {
                                Slider(
                                    value = layer.fps.toFloat(),
                                    enabled = !layer.locked,
                                    onValueChange = { value -> updateLayer(layer.id) { it.copy(fps = (value / 5f).toInt().coerceIn(1, 12) * 5) } },
                                    valueRange = 5f..60f,
                                    steps = 10
                                )
                                if (layer.fps > 30) {
                                    Text("⚠ High FPS can increase heat, battery use and dropped frames. Use only if your phone can sustain it.", color = Color(0xFFFFB27F), fontSize = 10.sp, lineHeight = 14.sp)
                                }
                            } else {
                                Text("Uses the video's source frame rate automatically, up to 60 FPS. Device performance may limit playback.", color = Color(0xFFA6ADB5), fontSize = 10.sp, lineHeight = 14.sp)
                            }
                            Text("Text, image and other static overlays do not need a video FPS setting.", color = Color(0xFFFF8659), fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            }
                        }
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
                        Text(if (layer.locked) "POSITION LOCKED" else "EDITING UNLOCKED", color = if (layer.locked) Color(0xFFFFB27F) else Color(0xFF55D6A1), modifier = Modifier.weight(1f), fontSize = 10.sp)
                        OutlinedButton(onClick = {
                            val locking = !layer.locked
                            updateLayer(layer.id) { it.copy(locked = locking) }
                            if (locking) selected = layer.id
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
        } else {
            Surface(color = Color(0xFF15191D), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1.2f).heightIn(min = 180.dp, max = 360.dp)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("LAYERS SETTING", color = Color.White, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("Select any layer on the left to edit it here.", color = Color(0xFFA1A8AF), fontSize = 9.sp)
                }
            }
        }
        }

        if (showMoreSettings) Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            OutlinedButton(onClick = { draft = scene.name; renameDialog = true }, modifier = Modifier.weight(1f)) { Text("Rename scene") }
            OutlinedButton(onClick = {
                scenes.add(scene.copy(id = System.nanoTime(), name = "${scene.name} Copy", layers = scene.layers.map { it.copy(id = System.nanoTime()) }))
                active = scenes.lastIndex; selected = -1L
            }, modifier = Modifier.weight(1f)) { Text("Duplicate") }
            OutlinedButton(onClick = {
                if (scenes.size > 1) { scenes.removeAt(index); active = (index - 1).coerceAtLeast(0); selected = -1L }
            }, enabled = scenes.size > 1) { Text("Delete") }
        }
        Text("Tap a layer to edit · drag inside to move · drag an edge to resize.", color = Color.Gray, fontSize = 9.sp)
    }

    if (assetsDialog) AlertDialog(
        onDismissRequest = { assetsDialog = false },
        title = { Text("Free overlay assets") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Built-in 16:9 screens and borders. Import images, animated GIFs or looping video overlays.", color = Color.Gray, fontSize = 11.sp)
            TextButton(onClick = { addPreset("Starting Soon", "STREAM STARTING SOON", "Thanks for waiting · We will be live shortly") }, modifier = Modifier.fillMaxWidth()) { Text("Starting Soon screen") }
            TextButton(onClick = { addPreset("BRB", "BE RIGHT BACK", "Taking a short break · Stay tuned") }, modifier = Modifier.fillMaxWidth()) { Text("BRB screen") }
            TextButton(onClick = { addPreset("Stream Ending", "THANKS FOR WATCHING", "Like · Follow · See you next time") }, modifier = Modifier.fillMaxWidth()) { Text("Stream Ending screen") }
            TextButton(onClick = { addPreset("Neon Border", "YOUR STREAM", "Customise this title and add your own GIF") }, modifier = Modifier.fillMaxWidth()) { Text("Neon border overlay") }
            TextButton(onClick = { assetsDialog = false; imagePicker.launch(arrayOf("image/gif")) }, modifier = Modifier.fillMaxWidth()) { Text("Import animated GIF overlay") }
            TextButton(onClick = { assetsDialog = false; imagePicker.launch(arrayOf("image/png", "image/jpeg", "image/webp")) }, modifier = Modifier.fillMaxWidth()) { Text("Import image / frame") }
            TextButton(onClick = { assetsDialog = false; videoPicker.launch(arrayOf("video/mp4", "video/webm", "video/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Import video overlay") }
        } },
        confirmButton = { TextButton(onClick = { assetsDialog = false }) { Text("Close") } },
        containerColor = Color(0xFF15191D), titleContentColor = Color.White, textContentColor = Color.White
    )

    if (addDialog) AlertDialog(
        onDismissRequest = { addDialog = false }, title = { Text("Add to this scene") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Choose what you want to place on the canvas.", color = Color.Gray, fontSize = 11.sp)
            listOf("screen" to "Game capture / screen", "text" to "Text overlay", "timer" to "Live timer", "image" to "Image or animated GIF", "video" to "Video overlay", "camera" to "Camera placeholder").forEach { (kind, label) ->
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
