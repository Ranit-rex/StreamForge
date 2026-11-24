package app.streamforge.ui

import app.streamforge.stream.StreamService
import app.streamforge.camera.local.CameraLayerPreview

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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
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
    val loop: Boolean = true, val fps: Int = 5, val soundVolume: Float = 1f, val soundMuted: Boolean = false,
    val autoPlay: Boolean = true,
    val cameraFacing: String = "front"
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
                put("textColor", layer.textColor); put("fontFamily", layer.fontFamily); put("fontSizeScale", layer.fontSizeScale); put("loop", layer.loop); put("fps", layer.fps); put("soundVolume", layer.soundVolume); put("soundMuted", layer.soundMuted); put("autoPlay", layer.autoPlay); put("cameraFacing", layer.cameraFacing)
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
                    item.optString("textColor", "#FFFFFFFF"), item.optString("fontFamily", "sans-serif"), item.optDouble("fontSizeScale", 1.0).toFloat(), item.optBoolean("loop", true), item.optInt("fps", if (item.optString("kind") == "video") 0 else 5).coerceIn(0, 60), item.optDouble("soundVolume", 1.0).toFloat().coerceIn(0f, 1f), item.optBoolean("soundMuted", false), item.optBoolean("autoPlay", true), item.optString("cameraFacing", "front"))
            })
    }
}.getOrDefault(sceneDefaults()).ifEmpty { sceneDefaults() }


/**
 * Keeps scene video players alive while the user moves between Studio pages.
 * TextureView surfaces may be detached/recreated, but the MediaPlayer itself is retained.
 */
private object SceneVideoPlayback {
    class State internal constructor(
        val uri: String,
        val context: android.content.Context,
        val loop: Boolean,
        player: MediaPlayer
    ) {
        var player: MediaPlayer = player
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
        var generation: Int = 0
        var recovering: Boolean = false
    }

    private val states = mutableMapOf<String, State>()
    @Volatile private var appInForeground: Boolean = true

    @Synchronized
    fun get(context: android.content.Context, uri: String, loop: Boolean, autoPlay: Boolean = true): State {
        states[uri]?.let { state ->
            state.wantsPlayback = if (state.ready.value) state.wantsPlayback else autoPlay
            return state
        }
        val state = State(uri, context.applicationContext, loop, MediaPlayer())
        state.wantsPlayback = autoPlay
        states[uri] = state
        prepare(state, 0)
        return state
    }

    private fun configureAndPrepare(state: State, resumeAt: Int) {
        val media = state.player
        val token = ++state.generation
        state.ready.value = false
        state.playing.value = false
        state.error.value = null
        try {
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        setAllowedCapturePolicy(android.media.AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                    }
                }.build()
            media.setAudioAttributes(audioAttributes)
            media.setVolume(0f, 0f)
            media.isLooping = state.loop
            media.setOnPreparedListener { prepared ->
                synchronized(this) {
                    if (state.player !== prepared || state.generation != token) return@synchronized
                    state.durationMs.value = prepared.duration.coerceAtLeast(0)
                    state.ready.value = true
                    if (resumeAt > 0) runCatching { prepared.seekTo(resumeAt.coerceIn(0, state.durationMs.value)) }
                    applyVolume(state)
                    if (state.wantsPlayback) {
                        runCatching { prepared.start(); state.playing.value = true }
                            .onFailure { state.error.value = it.message ?: "Could not resume video" }
                    }
                    state.recovering = false
                }
            }
            media.setOnCompletionListener { completed ->
                synchronized(this) {
                    if (state.player === completed && state.generation == token) {
                        state.positionMs.value = completed.duration.coerceAtLeast(0)
                        state.playing.value = false
                    }
                }
            }
            media.setOnErrorListener { failed, what, extra ->
                synchronized(this) {
                    if (state.player === failed && state.generation == token) {
                        recover(state, "Playback error ($what/$extra)")
                    }
                }
                true
            }
            media.setDataSource(state.context, Uri.parse(state.uri))
            state.surface?.takeIf { it.isValid }?.let(media::setSurface)
            media.prepareAsync()
        } catch (error: Exception) {
            state.error.value = error.message ?: "Could not prepare video"
            state.ready.value = false
            state.playing.value = false
            state.recovering = false
        }
    }

    @Synchronized
    private fun prepare(state: State, resumeAt: Int) {
        configureAndPrepare(state, resumeAt)
    }

    @Synchronized
    private fun recover(state: State, reason: String) {
        if (state.recovering) return
        state.recovering = true
        val old = state.player
        val position = if (state.ready.value) runCatching { old.currentPosition }.getOrDefault(state.positionMs.value) else state.positionMs.value
        state.positionMs.value = position.coerceAtLeast(0)
        state.ready.value = false
        state.playing.value = false
        state.error.value = "Recovering video…"
        runCatching { old.setOnErrorListener(null) }
        runCatching { old.reset() }
        runCatching { old.release() }
        state.player = MediaPlayer()
        configureAndPrepare(state, position)
        // If preparation itself fails, the visible error remains actionable; a later surface
        // attach can trigger another recovery rather than leaving the old dead player cached.
        if (state.player === old) state.error.value = reason
    }

    @Synchronized
    fun attach(context: android.content.Context, uri: String, loop: Boolean, texture: SurfaceTexture) {
        val state = states[uri] ?: get(context, uri, loop)
        val sameSurface = state.surfaceTexture === texture && state.surface?.isValid == true
        if (sameSurface) {
            if (state.ready.value && state.wantsPlayback && !state.player.isPlaying) {
                runCatching { state.player.start(); state.playing.value = true }
                    .onFailure { recover(state, it.message ?: "Could not resume video surface") }
            }
            return
        }
        val previous = state.surface
        val next = Surface(texture)
        state.surfaceTexture = texture
        state.surface = next
        runCatching {
            // Rebind the retained player to the newly-created TextureView surface.
            // Navigation can destroy the old view; the media player and its clock stay alive.
            state.player.setSurface(next)
            if (!sameSurface) previous?.release()
            if (state.ready.value) {
                val resumeAt = state.positionMs.value.coerceIn(0, state.durationMs.value.coerceAtLeast(0))
                if (!sameSurface && resumeAt > 0) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        state.player.seekTo(resumeAt.toLong(), MediaPlayer.SEEK_CLOSEST)
                    } else {
                        @Suppress("DEPRECATION")
                        state.player.seekTo(resumeAt)
                    }
                }
                if (state.wantsPlayback && !state.player.isPlaying) {
                    state.player.start()
                    state.playing.value = true
                }
            }
        }.onFailure {
            if (!sameSurface) runCatching { previous?.release() }
            recover(state, it.message ?: "Could not attach video surface")
        }
    }

    @Synchronized
    fun detach(uri: String, texture: SurfaceTexture) {
        val state = states[uri] ?: return
        if (state.surfaceTexture !== texture) return
        // Navigation is a temporary surface detach, not the end of playback.
        // Snapshot the real decoder position and pause before releasing the view surface.
        if (state.ready.value) runCatching {
            state.positionMs.value = state.player.currentPosition.coerceIn(0, state.durationMs.value.coerceAtLeast(0))
            if (state.player.isPlaying) state.player.pause()
            state.playing.value = false
        }
        runCatching { state.surface?.release() }
        state.surface = null
        state.surfaceTexture = null
    }

    private fun applyVolume(state: State) {
        val audible = appInForeground && !state.requestedMuted
        val volume = if (audible) state.requestedVolume.coerceIn(0f, 1f) else 0f
        runCatching { state.player.setVolume(volume, volume) }
    }

    @Synchronized
    fun activateScene(activeUris: Set<String>) {
        states.values.forEach { state ->
            if (state.uri !in activeUris) {
                if (state.ready.value) runCatching {
                    if (state.player.isPlaying) state.player.pause()
                    state.positionMs.value = state.player.currentPosition.coerceAtLeast(0)
                }
                state.playing.value = false
            } else if (state.wantsPlayback && state.ready.value) {
                runCatching {
                    if (!state.player.isPlaying) state.player.start()
                    state.playing.value = true
                }.onFailure { recover(state, it.message ?: "Scene video recovery") }
            }
        }
    }

    @Synchronized
    fun pauseForBackground() {
        appInForeground = false
        states.values.forEach(::applyVolume)
    }

    @Synchronized
    fun resumeInForeground() {
        appInForeground = true
        states.values.forEach { state ->
            applyVolume(state)
            if (state.wantsPlayback && state.ready.value) runCatching {
                if (!state.player.isPlaying) state.player.start()
                state.playing.value = true
            }.onFailure { recover(state, it.message ?: "Foreground video recovery") }
        }
    }

    @Synchronized
    fun setAutoPlay(state: State, autoPlay: Boolean) {
        state.wantsPlayback = autoPlay
        if (!state.ready.value) return
        runCatching {
            if (autoPlay && !state.player.isPlaying) state.player.start()
            else if (!autoPlay && state.player.isPlaying) state.player.pause()
            state.playing.value = autoPlay
        }.onFailure { recover(state, it.message ?: "Playback state recovery") }
    }

    @Synchronized
    fun setAudio(state: State, volume: Float, muted: Boolean) {
        state.requestedVolume = volume.coerceIn(0f, 1f)
        state.requestedMuted = muted
        applyVolume(state)
    }

    @Synchronized
    fun toggle(state: State) {
        state.wantsPlayback = !state.wantsPlayback
        if (!state.ready.value) return
        runCatching {
            if (state.wantsPlayback) state.player.start() else state.player.pause()
            state.playing.value = state.wantsPlayback
        }.onFailure { recover(state, it.message ?: "Playback control recovery") }
    }

    @Synchronized
    fun seek(state: State, positionMs: Int) {
        val target = positionMs.coerceIn(0, state.durationMs.value.coerceAtLeast(0))
        state.positionMs.value = target
        if (state.ready.value) runCatching { state.player.seekTo(target) }
            .onFailure { recover(state, it.message ?: "Seek recovery") }
    }

    @Synchronized
    fun refresh(state: State) {
        if (state.ready.value) runCatching {
            // Program/Live preview can remain mounted after the editor playback card
            // is removed. Keep the retained MediaPlayer clock advancing independently
            // of that card and recover if Android silently stopped playback.
            if (state.wantsPlayback && state.surface?.isValid == true && !state.player.isPlaying) {
                state.player.start()
            }
            state.positionMs.value = state.player.currentPosition.coerceIn(0, state.durationMs.value.coerceAtLeast(0))
            state.playing.value = state.player.isPlaying
        }.onFailure { recover(state, it.message ?: "Playback refresh recovery") }
    }
}

@Composable
private fun SceneVideoPlaybackCard(
    uri: String,
    loop: Boolean,
    autoPlay: Boolean,
    onUseHardware: () -> Unit,
    volume: Float,
    muted: Boolean,
    onVolumeChange: (Float) -> Unit,
    onMuteChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val state = remember(uri) { SceneVideoPlayback.get(context, uri, loop, autoPlay) }
    LaunchedEffect(state, autoPlay) { SceneVideoPlayback.setAutoPlay(state, autoPlay) }
    LaunchedEffect(state, volume, muted) { SceneVideoPlayback.setAudio(state, volume, muted) }
    LaunchedEffect(state) {
        while (isActive) {
            SceneVideoPlayback.refresh(state)
            delay(250L)
        }
    }
    Surface(color = Color(0xFF202832), shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 1.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            // Line 1: timeline and play/pause.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.height(19.dp)) {
                Text(formatSceneVideoTime(state.positionMs.value), color = Color.White, fontSize = 8.sp)
                Slider(
                    value = state.positionMs.value.toFloat().coerceIn(0f, state.durationMs.value.toFloat().coerceAtLeast(1f)),
                    onValueChange = { onUseHardware(); SceneVideoPlayback.seek(state, it.toInt()) },
                    valueRange = 0f..state.durationMs.value.toFloat().coerceAtLeast(1f),
                    enabled = state.ready.value,
                    modifier = Modifier.weight(1f).height(13.dp)
                )
                Text(formatSceneVideoTime(state.durationMs.value), color = Color.Gray, fontSize = 8.sp)
                androidx.compose.material3.IconButton(
                    onClick = { onUseHardware(); SceneVideoPlayback.toggle(state) },
                    enabled = state.ready.value,
                    modifier = Modifier.size(19.dp)
                ) {
                    Text(if (state.playing.value) "Ⅱ" else "▶", color = Color.White, fontSize = 10.sp)
                }
            }
            // Line 2: mute and volume.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.height(17.dp)) {
                TextButton(onClick = { onMuteChange(!muted) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(16.dp)) {
                    Text(if (muted) "MUTE" else "SOUND", fontSize = 7.sp, maxLines = 1)
                }
                Slider(value = volume, onValueChange = onVolumeChange, enabled = !muted, modifier = Modifier.weight(1f).height(12.dp), valueRange = 0f..1f)
                Text("${(volume * 100).toInt()}%", color = Color.Gray, fontSize = 7.sp)
            }
        }
    }
}

private fun formatSceneVideoTime(milliseconds: Int): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0) / 1000)
    return String.format(java.util.Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}


@Composable
fun SceneCompositionPreview(modifier: Modifier = Modifier, compact: Boolean = false, forceDecode: Boolean = false, editorMode: Boolean = false) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { SceneVideoPlayback.resumeInForeground(); HardwareScenePlayback.resumeInForeground() }
                Lifecycle.Event.ON_STOP -> { SceneVideoPlayback.pauseForBackground(); HardwareScenePlayback.pauseForBackground() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            // Removing a preview composable is not the same as app backgrounding.
            // Keep the active player running; the lifecycle observer handles real background mute.
            lifecycleOwner.lifecycle.removeObserver(observer)
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
    val renderMode = if (editorMode) "editor" else if (forceDecode) "decode" else previewMode
    val scene = savedScenes.getOrNull(activeIndex) ?: savedScenes.firstOrNull() ?: sceneDefaults().first()
    val bitmaps = remember(scene.layers.map { it.id to it.text }) {
        scene.layers.filter { (it.kind == "image" || it.kind == "gif") && it.text.isNotBlank() }.mapNotNull { item ->
            runCatching { context.contentResolver.openInputStream(Uri.parse(item.text))?.use { BitmapFactory.decodeStream(it) } }
                .getOrNull()?.let { item.id to it }
        }.toMap()
    }
    val videoFrames = remember { androidx.compose.runtime.mutableStateMapOf<Long, Bitmap>() }
    val videoErrors = remember { androidx.compose.runtime.mutableStateMapOf<Long, String>() }
    val videoSources = remember(editorMode, scene.layers.filter { it.kind == "video" }.map { it.id to (it.text to (it.loop to it.fps)) }) {
        if (editorMode) emptyList() else scene.layers.filter { it.kind == "video" && it.visible && it.text.isNotBlank() }
            .map { Triple(it.id, it.text, Triple(it.loop, it.fps, Triple(it.autoPlay, it.soundVolume to it.soundMuted, Unit))) }
    }
    // The retained native player is the persistent playback clock in both modes.
    // Frame-decode preview samples this clock instead of restarting at zero on navigation.
    val videoPlayers = remember(videoSources) {
        videoSources.associate { (id, uriString, settings) ->
            val player = SceneVideoPlayback.get(context, uriString, settings.first, settings.third.first && renderMode != "hardware")
            SceneVideoPlayback.setAutoPlay(player, settings.third.first && renderMode != "hardware")
            SceneVideoPlayback.setAudio(player, if (renderMode == "hardware") 0f else settings.third.second.first, settings.third.second.second || renderMode == "hardware")
            id to player
        }
    }
    // Keep the retained native player clock fresh even while its TextureView is temporarily
    // detached by page navigation. The player itself is held by SceneVideoPlayback.
    LaunchedEffect(videoPlayers, renderMode, videoSources) {
        while (isActive) {
            if (renderMode != "hardware") {
                videoSources.forEach { source -> videoPlayers[source.first]?.let { state ->
                    SceneVideoPlayback.setAutoPlay(state, source.third.third.first)
                    SceneVideoPlayback.setAudio(state, source.third.third.second.first, source.third.third.second.second)
                    SceneVideoPlayback.refresh(state)
                } }
            }
            delay(100L)
        }
    }
    // Decode mode remains available as a compatibility preview. Hardware mode below uses
    // the actual MediaPlayer output surface instead of repeatedly extracting still frames.
    val previewClockEpoch = remember(videoSources) { SystemClock.elapsedRealtime() }
    LaunchedEffect(videoSources, renderMode) {
        if (renderMode == "hardware") {
            videoFrames.values.toList().forEach { if (!it.isRecycled) it.recycle() }
            videoFrames.clear()
        } else {
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
                val now = SystemClock.elapsedRealtime()
                val frames = withContext(Dispatchers.IO) {
                    sources.mapNotNull { (id, retriever, settings) ->
                        val duration = settings.first
                        val autoPlay = settings.second.third.first
                        val loop = settings.second.first
                        val elapsed = if (autoPlay) (now - previewClockEpoch).coerceAtLeast(0L) else 0L
                        val rawPosition = if (loop && duration > 0L) elapsed % duration else elapsed.coerceAtMost((duration - 1L).coerceAtLeast(0L))
                        val timeMs = rawPosition.coerceIn(0L, (duration - 1L).coerceAtLeast(0L))
                        // Program Preview is a small thumbnail surface. Never decode the
                        // source at its native resolution (large 4K clips can exhaust heap).
                        runCatching {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                                retriever.getScaledFrameAtTime(
                                    timeMs * 1000L,
                                    MediaMetadataRetriever.OPTION_CLOSEST,
                                    960,
                                    540
                                )
                            } else {
                                retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                                    ?.let { full ->
                                        Bitmap.createScaledBitmap(
                                            full,
                                            960,
                                            (full.height * (960f / full.width)).toInt().coerceAtLeast(1),
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
                // A steady 30 FPS decode loop keeps the clip moving independently
                // of the native video surface and avoids 4K-sized bitmap churn.
                delay(33L)
            }
        } finally {
            sources.forEach { runCatching { it.second.release() } }
            videoFrames.values.toList().forEach { if (!it.isRecycled) it.recycle() }
            videoFrames.clear()
        }
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
            scene.layers.filter { it.visible && it.kind != "camera" && it.kind != "website" }.forEach { item ->
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
                    "video" -> if (renderMode != "hardware") {
                        val decoded = videoFrames[item.id]?.takeIf { !it.isRecycled }
                        if (decoded != null) {
                            val src = android.graphics.Rect(
                                (decoded.width * item.cropLeft).toInt().coerceIn(0, decoded.width - 1),
                                (decoded.height * item.cropTop).toInt().coerceIn(0, decoded.height - 1),
                                (decoded.width * item.cropRight).toInt().coerceIn(1, decoded.width),
                                (decoded.height * item.cropBottom).toInt().coerceIn(1, decoded.height)
                            )
                            paint.style = Paint.Style.FILL
                            native.drawBitmap(decoded, src, rect, paint)
                        } else {
                            paint.style = Paint.Style.FILL
                            paint.color = android.graphics.Color.rgb(31, 39, 49)
                            native.drawRoundRect(rect, 10f, 10f, paint)
                            paint.style = Paint.Style.STROKE
                            paint.strokeWidth = (rect.width() * .012f).coerceIn(2f, 4f)
                            paint.color = android.graphics.Color.rgb(255, 134, 89)
                            native.drawRoundRect(rect, 10f, 10f, paint)
                            paint.style = Paint.Style.FILL
                            paint.color = android.graphics.Color.WHITE
                            paint.textSize = (rect.height() * .14f).coerceIn(10f, 18f)
                            val label = item.name.ifBlank { "VIDEO" }.take(34)
                            native.drawText(label, rect.left + 8f, rect.centerY(), paint)
                        }
                    }
                }
                native.restore()
            }
        }
            }
            if (renderMode == "hardware" && !editorMode) {
                scene.layers.filter { it.visible && it.kind == "video" && it.text.isNotBlank() }.forEach { item ->
                    key(item.id, item.text) {
                        HardwareVideoPreview(
                            uriString = item.text,
                            loop = item.loop,
                            autoPlay = item.autoPlay,
                            volume = item.soundVolume,
                            muted = item.soundMuted,
                            modifier = Modifier
                                .offset(x = previewWidth * item.x, y = previewHeight * item.y)
                                .size(width = previewWidth * item.width, height = previewHeight * item.height)
                                .graphicsLayer(rotationZ = item.rotation, alpha = item.opacity.coerceIn(0f, 1f))
                        )
                    }
                }
            }
            scene.layers.filter { it.visible && it.kind == "website" && it.text.isNotBlank() }.forEach { item ->
                key(item.id, item.text) {
                    WebsiteLayerPreview(
                        url = item.text,
                        modifier = Modifier
                            .offset(x = previewWidth * item.x, y = previewHeight * item.y)
                            .size(width = previewWidth * item.width, height = previewHeight * item.height)
                            .graphicsLayer(rotationZ = item.rotation, alpha = item.opacity.coerceIn(0f, 1f))
                    )
                }
            }
            scene.layers.filter { it.visible && it.kind == "camera" }.forEach { item ->
                key(item.id) {
                    CameraLayerPreview(
                        lensFacing = item.cameraFacing,
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
private fun WebsiteLayerPreview(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val normalizedUrl = remember(url) {
        val trimmed = url.trim()
        when {
            trimmed.startsWith("https://", true) || trimmed.startsWith("http://", true) -> trimmed
            trimmed.isNotBlank() -> "https://$trimmed"
            else -> "about:blank"
        }
    }
    val webViewRef = remember { mutableStateOf<android.webkit.WebView?>(null) }
    AndroidView(
        modifier = modifier.background(Color.Black),
        factory = { viewContext ->
            android.webkit.WebView(viewContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.textZoom = 100
                settings.mediaPlaybackRequiresUserGesture = false
                webViewClient = android.webkit.WebViewClient()
                webViewRef.value = this
                loadUrl(normalizedUrl)
            }
        },
        update = { view ->
            webViewRef.value = view
            if (view.tag != normalizedUrl) {
                view.tag = normalizedUrl
                view.loadUrl(normalizedUrl)
            }
        }
    )
    DisposableEffect(Unit) {
        onDispose {
            webViewRef.value?.apply {
                stopLoading()
                loadUrl("about:blank")
                removeAllViews()
                destroy()
            }
            webViewRef.value = null
        }
    }
}

private object HardwareScenePlayback {
    class State(val uri: String, val player: ExoPlayer) {
        val ready = mutableStateOf(false)
        val playing = mutableStateOf(false)
        val positionMs = mutableIntStateOf(0)
        val durationMs = mutableIntStateOf(0)
        val error = mutableStateOf<String?>(null)
        var wantsPlayback: Boolean = true
        var attached: Boolean = false
        var textureView: TextureView? = null
    }

    private val states = mutableMapOf<String, State>()

    @Synchronized
    fun get(context: android.content.Context, uri: String, loop: Boolean, autoPlay: Boolean): State {
        states[uri]?.let { state ->
            state.wantsPlayback = autoPlay
            state.player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            if (!state.attached) state.player.playWhenReady = false
            return state
        }
        val player = ExoPlayer.Builder(context.applicationContext).build()
        val state = State(uri, player)
        state.wantsPlayback = autoPlay
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                state.ready.value = playbackState == Player.STATE_READY
                state.durationMs.value = player.duration.takeIf { it > 0L }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0
                state.positionMs.value = player.currentPosition.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                if (playbackState == Player.STATE_ENDED) state.playing.value = false
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                state.playing.value = isPlaying
                state.positionMs.value = player.currentPosition.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                state.error.value = error.errorCodeName
                state.ready.value = false
                state.playing.value = false
            }
        })
        player.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
        player.volume = 1f
        player.prepare()
        player.playWhenReady = autoPlay
        states[uri] = state
        return state
    }

    @Synchronized
    fun update(state: State, loop: Boolean, autoPlay: Boolean, volume: Float, muted: Boolean) {
        state.wantsPlayback = autoPlay
        state.player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        state.player.volume = if (muted) 0f else volume.coerceIn(0f, 1f)
        state.player.playWhenReady = state.attached && autoPlay
    }

    @Synchronized
    fun attach(state: State, view: TextureView) {
        if (state.textureView !== view) {
            state.textureView = view
            state.player.setVideoTextureView(view)
        }
        state.attached = true
        state.player.playWhenReady = state.wantsPlayback
    }

    @Synchronized
    fun detach(state: State, view: TextureView) {
        if (state.textureView !== view) return
        state.positionMs.value = state.player.currentPosition.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        state.wantsPlayback = state.player.playWhenReady || state.player.isPlaying || state.wantsPlayback
        state.attached = false
        state.player.clearVideoTextureView(view)
        state.textureView = null
        state.player.playWhenReady = false
        state.playing.value = false
    }

    @Synchronized
    fun toggle(state: State) {
        state.wantsPlayback = !state.wantsPlayback
        state.player.playWhenReady = state.attached && state.wantsPlayback
    }

    @Synchronized
    fun seek(state: State, positionMs: Int) {
        state.positionMs.value = positionMs.coerceAtLeast(0)
        state.player.seekTo(state.positionMs.value.toLong())
    }

    @Synchronized
    fun refresh(state: State) {
        state.positionMs.value = state.player.currentPosition.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        state.durationMs.value = state.player.duration.takeIf { it > 0L }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0
        state.playing.value = state.player.isPlaying
    }

    @Synchronized
    fun pauseForBackground() {
        states.values.forEach { state ->
            state.wantsPlayback = state.player.playWhenReady || state.player.isPlaying || state.wantsPlayback
            state.player.playWhenReady = false
        }
    }

    @Synchronized
    fun resumeInForeground() {
        states.values.filter { it.attached && it.wantsPlayback }.forEach { it.player.playWhenReady = true }
    }
}

@Composable
private fun HardwareVideoPreview(
    uriString: String, loop: Boolean, autoPlay: Boolean, volume: Float, muted: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state = remember(uriString) { HardwareScenePlayback.get(context, uriString, loop, autoPlay) }
    LaunchedEffect(state, autoPlay, volume, muted, loop) {
        HardwareScenePlayback.update(state, loop, autoPlay, volume, muted)
        while (isActive) {
            HardwareScenePlayback.refresh(state)
            delay(100L)
        }
    }
    Box(modifier.background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                TextureView(viewContext).apply {
                    isOpaque = false
                    HardwareScenePlayback.attach(state, this)
                }
            },
            update = { view -> HardwareScenePlayback.attach(state, view) },
            onRelease = { view -> HardwareScenePlayback.detach(state, view) }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SceneStudio(
    previewReady: Boolean,
    isStreaming: Boolean,
    onPreparePreview: () -> Unit,
    onStopStudioPreview: () -> Unit,
    onChangeCaptureSource: () -> Unit,
    onApplyScene: (Int, String) -> Unit = { _, _ -> }
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
            if (prefs.getInt("video_preview_mode_default_version", 0) < 1) {
                prefs.edit().putString("video_preview_mode", "decode").putInt("video_preview_mode_default_version", 1).apply()
            }
        }
    }
    var active by remember { mutableIntStateOf(prefs.getInt("active_scene", 0)) }
    var selected by remember { mutableLongStateOf(-1L) }
    var addDialog by remember { mutableStateOf(false) }
    var websiteDialog by remember { mutableStateOf(false) }
    var websiteUrl by remember { mutableStateOf("https://") }
    var editingWebsiteLayerId by remember { mutableStateOf<Long?>(null) }
    var assetsDialog by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var transformDialog by remember { mutableStateOf(false) }
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

    val index = active.coerceIn(0, scenes.lastIndex)
    val scene = scenes[index]
    val latestApplyScene = rememberUpdatedState(onApplyScene)
    // Observe the complete serialized scene list. This catches every nested layer
    // edit/reorder/add/delete and sends the exact same snapshot to the live renderer.
    // A single collector also avoids races between async preference writes and service intents.
    LaunchedEffect(Unit) {
        snapshotFlow { active.coerceIn(0, scenes.lastIndex) to encodeSceneList(scenes) }
            .distinctUntilChanged()
            .collect { (sceneIndex, sceneJson) ->
                val currentScenes = decodeSceneList(sceneJson)
                val currentScene = currentScenes.getOrNull(sceneIndex)
                SceneVideoPlayback.activateScene(currentScene?.layers?.filter { it.kind == "video" }?.map { it.text }?.toSet().orEmpty())
                prefs.edit()
                    .putString("scenes", sceneJson)
                    .putInt("active_scene", sceneIndex)
                    .commit()
                latestApplyScene.value(sceneIndex, sceneJson)
            }
    }
    val layer = scene.layers.firstOrNull { it.id == selected }

    fun updateLayer(id: Long, transform: (SceneLayer) -> SceneLayer) {
        val current = scenes[index]
        scenes[index] = current.copy(layers = current.layers.map { if (it.id == id) transform(it) else it })
    }
    val latestScene = rememberUpdatedState(scene)
    val latestUpdateLayer = rememberUpdatedState<(Long, (SceneLayer) -> SceneLayer) -> Unit>({ id, transform -> updateLayer(id, transform) })

    fun addSource(kind: String, websiteAddress: String = "") {
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
            if (kind == "text") "YOUR TEXT" else if (kind == "timer") "00:00" else if (kind == "website") websiteAddress.trim().let { if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "https://$it" } else "",
            if (kind == "screen") 0f else .08f,
            if (kind == "screen") 0f else .08f,
            when (kind) { "screen" -> 1f; "text" -> .42f; "camera" -> .30f; "website" -> .82f; "timer" -> .22f; "video" -> .42f; else -> .34f },
            when (kind) { "screen" -> 1f; "text" -> .16f; "camera" -> .24f; "website" -> .46f; "timer" -> .12f; "video" -> .32f; else -> .34f },
            locked = kind == "screen", cameraFacing = "front"
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

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("SOURCE", color = Color(0xFFFF8659), fontSize = 8.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Box(Modifier.fillMaxWidth(.82f).align(Alignment.CenterHorizontally)) {
                    StudioPreviewSurface(
                        previewReady = previewReady,
                        isStreaming = isStreaming,
                        sourceMonitorOnly = true,
                        onPrepare = onPreparePreview,
                        onStop = onStopStudioPreview,
                        onChangeSource = onChangeCaptureSource,
                        compact = true
                    )
                }
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SCENE EDITOR", color = Color(0xFFFF8659), fontSize = 8.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    if (scene.layers.any { it.kind == "video" }) SceneVideoPlaybackModeSelector()
                    TextButton(onClick = { showMoreSettings = !showMoreSettings }, contentPadding = PaddingValues(horizontal = 3.dp, vertical = 0.dp), modifier = Modifier.height(18.dp)) {
                        Text("⋯", fontSize = 10.sp)
                    }
                }
                        Surface(color = Color(0xFF292D30), shape = RoundedCornerShape(0.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF665044)),
                            modifier = Modifier.fillMaxWidth(.82f).align(Alignment.CenterHorizontally)) {
                            Column(Modifier.padding(2.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF111518))) {
                                    SceneCompositionPreview(Modifier.fillMaxSize(), editorMode = true)
                                    Canvas(
                                        Modifier.fillMaxSize()
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
                                        canvasWidth = size.width
                                        canvasHeight = size.height
                                        val item = scene.layers.firstOrNull { it.id == selected && it.visible }
                                        if (item != null) {
                                            drawIntoCanvas { canvas ->
                                                val native = canvas.nativeCanvas
                                                val left = item.x * size.width
                                                val top = item.y * size.height
                                                val rect = RectF(left, top, left + size.width * item.width, top + size.height * item.height)
                                                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                                    style = Paint.Style.STROKE
                                                    strokeWidth = 3f
                                                    color = android.graphics.Color.CYAN
                                                }
                                                native.save()
                                                native.rotate(item.rotation, rect.centerX(), rect.centerY())
                                                native.drawRoundRect(rect, 8f, 8f, paint)
                                                if (!item.locked) {
                                                    paint.style = Paint.Style.FILL
                                                    val handle = 36f
                                                    listOf(
                                                        rect.left to rect.top, rect.centerX() to rect.top, rect.right to rect.top,
                                                        rect.left to rect.centerY(), rect.right to rect.centerY(),
                                                        rect.left to rect.bottom, rect.centerX() to rect.bottom, rect.right to rect.bottom
                                                    ).forEach { (x, y) ->
                                                        paint.color = android.graphics.Color.WHITE
                                                        native.drawCircle(x, y, handle / 2f, paint)
                                                        paint.color = android.graphics.Color.rgb(20, 35, 55)
                                                        native.drawCircle(x, y, 3f, paint)
                                                    }
                                                }
                                                native.restore()
                                            }
                                        }
                                    }
                                }
                                if (sourceMessage.isNotBlank()) Text(sourceMessage, color = Color(0xFFFFB27F), fontSize = 10.sp)
                                if (scene.layers.isEmpty()) Text("Abhi empty scene hai. + Add source se screen, text ya image add karo.", color = Color(0xFFFFB27F), fontSize = 11.sp)
                            }
                        }
            }
        }

        // The two preview surfaces above remain pinned. Only this lower workspace scrolls.
        Column(
            Modifier.weight(1f).fillMaxWidth().heightIn(min = 0.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
        if (showMoreSettings) {
            Surface(color = Color(0xFF202326), shape = RoundedCornerShape(9.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                    Text("ADVANCED OPTIONS", color = Color(0xFFA1A8AF), fontSize = 9.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("Advanced controls are grouped here to keep the Scene page compact.", color = Color.Gray, fontSize = 9.sp)
                }
            }
        }
        val selectedVideo = scene.layers.firstOrNull { it.id == selected && it.kind == "video" }
        if (selectedVideo != null) {
            SceneVideoPlaybackCard(uri = selectedVideo.text, loop = selectedVideo.loop, autoPlay = selectedVideo.autoPlay, volume = selectedVideo.soundVolume, muted = selectedVideo.soundMuted,
                onUseHardware = { prefs.edit().putString("video_preview_mode", "hardware").apply() },
                onVolumeChange = { value -> updateLayer(selectedVideo.id) { it.copy(soundVolume = value) } },
                onMuteChange = { value -> updateLayer(selectedVideo.id) { it.copy(soundMuted = value) } })
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            scenes.forEachIndexed { i, item ->
                Surface(
                    color = if (i == index) Color(0xFF3B2A22) else Color(0xFF20262B),
                    shape = RoundedCornerShape(9.dp),
                    onClick = { active = i; selected = -1L }
                ) {
                    Text("${i + 1} · ${item.name}", color = if (i == index) Color(0xFFFFA27A) else Color.White,
                        fontSize = 9.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                }
            }
            Surface(color = Color(0xFF20262B), shape = RoundedCornerShape(9.dp), onClick = {
                scenes.add(SceneModel(System.nanoTime(), "Scene ${scenes.size + 1}", emptyList()))
                active = scenes.lastIndex; selected = -1L
            }) { Text("+", color = Color(0xFFFFA27A), fontSize = 16.sp, modifier = Modifier.padding(horizontal = 13.dp, vertical = 5.dp)) }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                color = Color(0xFF15191D), shape = RoundedCornerShape(11.dp),
                modifier = Modifier.weight(1f).height(158.dp)
            ) {
                Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("LAYERS", color = Color.White, fontSize = 10.sp, maxLines = 1, softWrap = false, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { assetsDialog = true }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("Assets", fontSize = 9.sp) }
                        TextButton(onClick = { addDialog = true }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("+ Add", fontSize = 9.sp) }
                    }
                    if (scene.layers.isEmpty()) {
                        Text("No layers yet", color = Color.Gray, fontSize = 10.sp)
                    } else {
                        val displayedLayers = scene.layers.asReversed()
                        LazyColumn(
                            modifier = Modifier.weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(displayedLayers, key = { it.id }) { item ->
                                var reorderDrag by remember(item.id) { mutableFloatStateOf(0f) }
                                Surface(
                                    color = if (item.id == selected) Color(0xFF35231D) else Color(0xFF20262B),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                        .animateItem()
                                        .pointerInput(item.id, scene.layers) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { selected = item.id; reorderDrag = 0f },
                                                onDragEnd = { reorderDrag = 0f },
                                                onDragCancel = { reorderDrag = 0f },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    reorderDrag += dragAmount.y
                                                    val threshold = 30.dp.toPx()
                                                    if (abs(reorderDrag) >= threshold) {
                                                        val currentDisplay = scenes[index].layers.asReversed().toMutableList()
                                                        val from = currentDisplay.indexOfFirst { it.id == item.id }
                                                        val to = (from + if (reorderDrag > 0f) 1 else -1).coerceIn(0, currentDisplay.lastIndex)
                                                        if (from >= 0 && from != to) {
                                                            val moving = currentDisplay.removeAt(from)
                                                            currentDisplay.add(to, moving)
                                                            scenes[index] = scenes[index].copy(layers = currentDisplay.asReversed())
                                                        }
                                                        reorderDrag = 0f
                                                    }
                                                }
                                            )
                                        },
                                    onClick = { selected = item.id }
                                ) {
                                    Row(Modifier.padding(horizontal = 5.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("⠿", color = Color(0xFFFFA27A), fontSize = 17.sp)
                                        Column(Modifier.weight(1f)) {
                                            Text(item.name, color = Color.White, fontSize = 9.sp, maxLines = 1, softWrap = false)
                                            Text(if (item.visible) "VISIBLE" else "HIDDEN", color = if (item.visible) Color(0xFF65D6A4) else Color.Gray, fontSize = 7.sp)
                                        }
                                        TextButton(onClick = { updateLayer(item.id) { it.copy(visible = !it.visible) } },
                                            contentPadding = PaddingValues(0.dp), modifier = Modifier.size(30.dp)) {
                                            Text(if (item.visible) "👁️" else "🙈", fontSize = 15.sp)
                                        }
                                        TextButton(onClick = {
                                            val locking = !item.locked
                                            updateLayer(item.id) { it.copy(locked = locking) }
                                            if (locking) selected = item.id
                                        }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(30.dp)) {
                                            Text(if (item.locked) "🔒" else "🔓", fontSize = 15.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Surface(
                color = Color(0xFF15191D), shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(158.dp)
            ) {
            Column(
                Modifier.fillMaxSize().padding(5.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text("LAYER SETTINGS", color = Color.White, fontSize = 10.sp, maxLines = 1, softWrap = false, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                if (layer == null) {
                    Text("Select a layer to edit its main controls.", color = Color(0xFFA1A8AF), fontSize = 9.sp)
                } else {
                    Text(layer.name, color = Color(0xFFFFA27A), fontSize = 9.sp, maxLines = 1)
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        OutlinedButton(
                            onClick = {
                                scenes[index] = scenes[index].copy(layers = scenes[index].layers.filterNot { it.id == layer.id })
                                selected = -1L
                            },
                            enabled = true,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) { Text("Delete layer", fontSize = 9.sp) }
                    }
                    if (layer.kind == "text" || layer.kind == "timer") {
                        OutlinedTextField(
                            value = layer.text, enabled = !layer.locked && layer.kind == "text",
                            onValueChange = { value -> updateLayer(layer.id) { it.copy(text = value) } },
                            label = { Text(if (layer.kind == "timer") "Timer label" else "Text", maxLines = 1) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                    }
                    if (layer.kind == "video") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Auto play", color = Color.White, fontSize = 9.sp, modifier = Modifier.weight(1f))
                            Switch(checked = layer.autoPlay, enabled = !layer.locked, onCheckedChange = { value -> updateLayer(layer.id) { it.copy(autoPlay = value) } })
                        }
                    }
                    if (layer.kind == "website") {
                        OutlinedButton(
                            onClick = { editingWebsiteLayerId = layer.id; websiteUrl = layer.text; websiteDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) { Text("Change website URL", fontSize = 9.sp) }
                        Text("Website runs inside the scene preview.", color = Color.Gray, fontSize = 8.sp)
                    }
                    if (layer.kind == "camera") {
                        Text("CAMERA", color = Color(0xFFA1A8AF), fontSize = 8.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            listOf("back" to "BACK", "front" to "FRONT").forEach { (facing, label) ->
                                FilterChip(
                                    selected = layer.cameraFacing == facing,
                                    onClick = { updateLayer(layer.id) { it.copy(cameraFacing = facing) } },
                                    enabled = !layer.locked,
                                    label = { Text(label, fontSize = 8.sp) }
                                )
                            }
                        }
                        Text("Live phone camera preview", color = Color.Gray, fontSize = 8.sp)
                    }

                    if (layer.kind == "image" || layer.kind == "gif" || layer.kind == "video") {
                        OutlinedButton(
                            onClick = { if (layer.kind == "video") videoPicker.launch(arrayOf("video/*")) else imagePicker.launch(arrayOf("image/*")) },
                            enabled = !layer.locked, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp)
                        ) { Text("Replace media", fontSize = 9.sp) }
                    }
                    if (layer.kind == "video") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Loop", color = Color.White, fontSize = 9.sp, modifier = Modifier.weight(1f))
                            Switch(checked = layer.loop, enabled = !layer.locked, onCheckedChange = { value -> updateLayer(layer.id) { it.copy(loop = value) } })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        OutlinedButton(onClick = { transformDialog = true }, enabled = !layer.locked, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 5.dp, vertical = 3.dp)) {
                            Text("Size & position", fontSize = 9.sp)
                        }
                        OutlinedButton(onClick = { updateLayer(layer.id) { it.copy(locked = !it.locked) } }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 5.dp, vertical = 3.dp)) {
                            Text(if (layer.locked) "Unlock" else "Lock", fontSize = 9.sp)
                        }
                    }
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
        }
    }

    if (transformDialog && layer != null) AlertDialog(
        onDismissRequest = { transformDialog = false },
        title = { Text("Size & position · ${layer.name}") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("WIDTH · ${(layer.width * 100).toInt()}%", color = Color.White, fontSize = 10.sp)
                Slider(value = layer.width, onValueChange = { v -> updateLayer(layer.id) { it.copy(width = v.coerceIn(.08f, 1f), x = it.x.coerceAtMost(1f - v)) } }, valueRange = .08f..1f)
                Text("HEIGHT · ${(layer.height * 100).toInt()}%", color = Color.White, fontSize = 10.sp)
                Slider(value = layer.height, onValueChange = { v -> updateLayer(layer.id) { it.copy(height = v.coerceIn(.05f, 1f), y = it.y.coerceAtMost(1f - v)) } }, valueRange = .05f..1f)
                Text("POSITION X · ${(layer.x * 100).toInt()}%", color = Color.White, fontSize = 10.sp)
                Slider(value = layer.x, onValueChange = { v -> updateLayer(layer.id) { it.copy(x = v.coerceIn(0f, 1f - it.width)) } }, valueRange = 0f..(1f - layer.width).coerceAtLeast(0f))
                Text("POSITION Y · ${(layer.y * 100).toInt()}%", color = Color.White, fontSize = 10.sp)
                Slider(value = layer.y, onValueChange = { v -> updateLayer(layer.id) { it.copy(y = v.coerceIn(0f, 1f - it.height)) } }, valueRange = 0f..(1f - layer.height).coerceAtLeast(0f))
                Text("ROTATION · ${layer.rotation.toInt()}°", color = Color.White, fontSize = 10.sp)
                Slider(value = layer.rotation, onValueChange = { v -> updateLayer(layer.id) { it.copy(rotation = v) } }, valueRange = -180f..180f)
                Text("OPACITY · ${(layer.opacity * 100).toInt()}%", color = Color.White, fontSize = 10.sp)
                Slider(value = layer.opacity, onValueChange = { v -> updateLayer(layer.id) { it.copy(opacity = v) } }, valueRange = 0f..1f)
                if (layer.kind == "video" || layer.kind == "image" || layer.kind == "gif") {
                    Text("CROP LEFT · ${(layer.cropLeft * 100).toInt()}%", color = Color.White, fontSize = 10.sp)
                    Slider(value = layer.cropLeft, onValueChange = { v -> updateLayer(layer.id) { it.copy(cropLeft = v.coerceIn(0f, it.cropRight - .02f)) } }, valueRange = 0f..0.95f)
                    Text("CROP TOP · ${(layer.cropTop * 100).toInt()}%", color = Color.White, fontSize = 10.sp)
                    Slider(value = layer.cropTop, onValueChange = { v -> updateLayer(layer.id) { it.copy(cropTop = v.coerceIn(0f, it.cropBottom - .02f)) } }, valueRange = 0f..0.95f)
                    Text("CROP RIGHT · ${(layer.cropRight * 100).toInt()}%", color = Color.White, fontSize = 10.sp)
                    Slider(value = layer.cropRight, onValueChange = { v -> updateLayer(layer.id) { it.copy(cropRight = v.coerceIn(it.cropLeft + .02f, 1f)) } }, valueRange = .05f..1f)
                    Text("CROP BOTTOM · ${(layer.cropBottom * 100).toInt()}%", color = Color.White, fontSize = 10.sp)
                    Slider(value = layer.cropBottom, onValueChange = { v -> updateLayer(layer.id) { it.copy(cropBottom = v.coerceIn(it.cropTop + .02f, 1f)) } }, valueRange = .05f..1f)
                }
            }
        },
        confirmButton = { TextButton(onClick = { transformDialog = false }) { Text("Done") } },
        containerColor = Color(0xFF15191D), titleContentColor = Color.White, textContentColor = Color.White
    )

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
            listOf("screen" to "Game capture / screen", "text" to "Text overlay", "timer" to "Live timer", "image" to "Image or animated GIF", "video" to "Video overlay", "camera" to "Phone camera (front)", "website" to "Website / browser").forEach { (kind, label) ->
                TextButton(onClick = {
                    addDialog = false
                    if (kind == "website") {
                        editingWebsiteLayerId = null
                        websiteUrl = "https://"
                        websiteDialog = true
                    } else addSource(kind)
                }, modifier = Modifier.fillMaxWidth()) { Text(label) }
            }
        } },
        confirmButton = { TextButton(onClick = { addDialog = false }) { Text("Close") } }
    )
    if (websiteDialog) AlertDialog(
        onDismissRequest = { websiteDialog = false },
        title = { Text("Open website source") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Enter the website URL to add it as a scene source.", color = Color.Gray, fontSize = 11.sp)
                OutlinedTextField(
                    value = websiteUrl,
                    onValueChange = { websiteUrl = it },
                    label = { Text("Website URL") },
                    placeholder = { Text("https://example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val address = websiteUrl.trim()
                    if (address.isNotBlank() && address != "https://") {
                        val normalized = if (address.startsWith("http://", true) || address.startsWith("https://", true)) address else "https://$address"
                        val editingId = editingWebsiteLayerId
                        if (editingId != null) updateLayer(editingId) { it.copy(text = normalized) }
                        else addSource("website", normalized)
                        editingWebsiteLayerId = null
                        websiteDialog = false
                    }
                },
                enabled = websiteUrl.trim().isNotBlank() && websiteUrl.trim() != "https://"
            ) { Text("Add website") }
        },
        dismissButton = { TextButton(onClick = { websiteDialog = false }) { Text("Cancel") } }
    )
    if (renameDialog) AlertDialog(
        onDismissRequest = { renameDialog = false }, title = { Text("Rename scene") },
        text = { OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (draft.isNotBlank()) scenes[index] = scene.copy(name = draft.trim()); renameDialog = false }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("Cancel") } }
    )
}
