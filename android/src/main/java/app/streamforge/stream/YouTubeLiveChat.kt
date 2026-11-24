package app.streamforge.stream

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import app.streamforge.ui.YouTubeApiKeyStore
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** Reads YouTube Live Chat through StreamForge's server-side API proxy. */
object YouTubeLiveChat {
    private const val API_BASE = "https://streamforge-chat-api.onrender.com"
    val messages = MutableStateFlow<List<String>>(emptyList())
    val connectionState = MutableStateFlow("Not connected")
    @Volatile private var running = false
    @Volatile private var generation = 0
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "streamforge-chat").apply { isDaemon = true } }

    fun start(context: Context) {
        if (running) return
        val prefs = context.getSharedPreferences("streamforge_chat", Context.MODE_PRIVATE)
        val videoId = extractVideoId(prefs.getString("youtube_video_id", "").orEmpty())
        val userApiKey = YouTubeApiKeyStore.read(context)
        if (videoId.isBlank()) {
            connectionState.value = "Add your YouTube LIVE video link in Chat settings"
            return
        }
        running = true
        val active = ++generation
        connectionState.value = "Connecting to YouTube Live Chat…"
        executor.execute {
            var pageToken = ""
            val seen = LinkedHashSet<String>()
            while (running && active == generation) {
                var conn: HttpURLConnection? = null
                var delay = 7000L
                try {
                    val requestUrl: String
                    if (userApiKey.isNotBlank()) {
                        var chatId = prefs.getString("youtube_active_chat_id", "").orEmpty()
                        if (chatId.isBlank()) {
                            val videoConn = URL("https://www.googleapis.com/youtube/v3/videos?part=liveStreamingDetails&id=" + enc(videoId) + "&key=" + enc(userApiKey)).openConnection() as HttpURLConnection
                            videoConn.connectTimeout = 12000
                            videoConn.readTimeout = 18000
                            val videoCode = videoConn.responseCode
                            val videoBody = (if (videoCode in 200..299) videoConn.inputStream else videoConn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                            videoConn.disconnect()
                            if (videoCode !in 200..299) throw IllegalStateException(runCatching { JSONObject(videoBody).getJSONObject("error").optString("message") }.getOrNull().orEmpty().ifBlank { "YouTube API key error ($videoCode)" })
                            chatId = JSONObject(videoBody).optJSONArray("items")?.optJSONObject(0)?.optJSONObject("liveStreamingDetails")?.optString("activeLiveChatId").orEmpty()
                            if (chatId.isBlank()) throw IllegalStateException("No active Live Chat found. Check that the broadcast is live and chat is enabled.")
                            prefs.edit().putString("youtube_active_chat_id", chatId).apply()
                        }
                        requestUrl = "https://www.googleapis.com/youtube/v3/liveChat/messages?liveChatId=" + enc(chatId) + "&part=snippet%2CauthorDetails&maxResults=200&key=" + enc(userApiKey) + if (pageToken.isNotBlank()) "&pageToken=" + enc(pageToken) else ""
                    } else {
                        val query = "videoId=" + enc(videoId) + if (pageToken.isNotBlank()) "&pageToken=" + enc(pageToken) else ""
                        requestUrl = "$API_BASE/api/youtube/live-chat?$query"
                    }
                    conn = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 12000
                        readTimeout = 18000
                        setRequestProperty("Accept", "application/json")
                    }
                    val code = conn.responseCode
                    val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (code !in 200..299) {
                        val message = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
                        connectionState.value = message.ifBlank { "Chat service error ($code)" }
                        delay = 12000L
                    } else {
                        val json = JSONObject(body)
                        pageToken = json.optString("nextPageToken", pageToken)
                        delay = json.optLong("pollingIntervalMillis", 5000L).coerceIn(1500L, 30000L)
                        val items = json.optJSONArray("items")
                        val fresh = ArrayList<String>()
                        if (items != null) for (i in 0 until items.length()) {
                            val item = items.optJSONObject(i) ?: continue
                            val id = item.optString("id")
                            val snippet = item.optJSONObject("snippet") ?: continue
                            val author = item.optJSONObject("authorDetails")?.optString("displayName").orEmpty()
                            val message = snippet.optString("displayMessage").trim()
                            if (message.isNotBlank() && (id.isBlank() || seen.add(id))) fresh.add(if (author.isBlank()) message else "$author: $message")
                        }
                        if (fresh.isNotEmpty()) messages.value = (messages.value + fresh).takeLast(40)
                        connectionState.value = "YouTube chat connected"
                    }
                } catch (e: Exception) {
                    if (running) connectionState.value = "Chat connection: ${e.message ?: "network error"}"
                    delay = 12000L
                } finally { conn?.disconnect() }
                if (running && active == generation) try { Thread.sleep(delay) } catch (_: InterruptedException) { break }
            }
        }
    }

    fun stop() {
        running = false
        generation++
        if (connectionState.value == "YouTube chat connected") connectionState.value = "Disconnected"
    }
    fun clear() { messages.value = emptyList() }

    private fun extractVideoId(value: String): String {
        val raw = value.trim()
        if (raw.matches(Regex("[A-Za-z0-9_-]{11}"))) return raw
        return Regex("(?:v=|youtu\\.be/|/live/|/shorts/)([A-Za-z0-9_-]{11})").find(raw)?.groupValues?.getOrNull(1).orEmpty()
    }
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}
