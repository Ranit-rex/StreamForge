package app.streamforge.stream

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * YouTube Live Chat reader. Uses the official YouTube Data API v3 liveChatMessages.list
 * endpoint. The user supplies an API key and the active broadcast's liveChatId.
 */
object YouTubeLiveChat {
    val messages = MutableStateFlow<List<String>>(emptyList())
    val connectionState = MutableStateFlow("Not connected")

    @Volatile private var running = false
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "streamforge-youtube-chat").apply { isDaemon = true }
    }

    fun start(context: Context) {
        if (running) return
        val prefs = context.getSharedPreferences("streamforge_chat", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("youtube_api_key", "").orEmpty().trim()
        val liveChatId = prefs.getString("youtube_live_chat_id", "").orEmpty().trim()
        if (apiKey.isBlank() || liveChatId.isBlank()) {
            connectionState.value = "Add YouTube API key and live chat ID in Chat settings"
            return
        }
        running = true
        connectionState.value = "Connecting to YouTube Live Chat…"
        executor.execute {
            var pageToken = ""
            val seen = LinkedHashSet<String>()
            while (running) {
                var delayMs = 5000L
                var connection: HttpURLConnection? = null
                try {
                    val query = buildString {
                        append("liveChatId=").append(enc(liveChatId))
                        append("&part=snippet,authorDetails")
                        append("&key=").append(enc(apiKey))
                        append("&maxResults=200")
                        if (pageToken.isNotBlank()) append("&pageToken=").append(enc(pageToken))
                    }
                    connection = (URL("https://www.googleapis.com/youtube/v3/liveChat/messages?$query").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 10000
                        readTimeout = 15000
                        setRequestProperty("Accept", "application/json")
                    }
                    val code = connection.responseCode
                    val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (code !in 200..299) {
                        val message = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
                        connectionState.value = "YouTube API error ($code): ${message ?: "request failed"}"
                        delayMs = 10000L
                    } else {
                        val json = JSONObject(body)
                        pageToken = json.optString("nextPageToken", pageToken)
                        delayMs = json.optLong("pollingIntervalMillis", 5000L).coerceIn(1000L, 30000L)
                        val items = json.optJSONArray("items")
                        val fresh = ArrayList<String>()
                        if (items != null) {
                            for (i in 0 until items.length()) {
                                val item = items.optJSONObject(i) ?: continue
                                val id = item.optString("id")
                                val snippet = item.optJSONObject("snippet") ?: continue
                                val author = item.optJSONObject("authorDetails")?.optString("displayName").orEmpty()
                                val message = snippet.optString("displayMessage").trim()
                                if (message.isNotBlank() && (id.isBlank() || seen.add(id))) {
                                    fresh.add(if (author.isBlank()) message else "$author: $message")
                                }
                            }
                        }
                        if (fresh.isNotEmpty()) {
                            val merged = (messages.value + fresh).takeLast(40)
                            messages.value = merged
                        }
                        connectionState.value = "YouTube chat connected"
                    }
                } catch (error: Exception) {
                    if (running) connectionState.value = "YouTube chat: ${error.message ?: error.javaClass.simpleName}"
                    delayMs = 10000L
                } finally {
                    connection?.disconnect()
                }
                if (running) try { Thread.sleep(delayMs) } catch (_: InterruptedException) { break }
            }
        }
    }

    fun stop() {
        running = false
        if (connectionState.value == "YouTube chat connected") connectionState.value = "Disconnected"
    }

    fun clear() { messages.value = emptyList() }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
