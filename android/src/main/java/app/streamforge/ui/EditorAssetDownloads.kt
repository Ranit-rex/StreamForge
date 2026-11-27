package app.streamforge.ui

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.Executors

/**
 * Optional editor packs are fetched after first launch, never bundled into the base APK.
 * Downloads are Wi-Fi only and stored in app-specific external files.
 */
object EditorAssetDownloads {
    private const val CATALOG_URL = "https://raw.githubusercontent.com/Ranit-rex/StreamForge/main/browser/editor-assets.json"
    private const val PREFS = "streamforge_editor_assets"
    private val executor = Executors.newSingleThreadExecutor()

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        executor.execute {
            runCatching {
                val connection = URL(CATALOG_URL).openConnection().apply {
                    connectTimeout = 8000
                    readTimeout = 10000
                    setRequestProperty("Accept", "application/json")
                }
                val body = connection.getInputStream().bufferedReader().use { it.readText() }
                val assets = JSONObject(body).optJSONArray("assets") ?: return@runCatching
                val manager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    val id = asset.optString("id").trim()
                    val url = asset.optString("url").trim()
                    val filename = asset.optString("filename").trim()
                    if (id.isBlank() || url.isBlank() || filename.isBlank()) continue
                    val key = "download_$id"
                    val previous = prefs.getLong(key, -1L)
                    if (previous > 0L) {
                        val query = DownloadManager.Query().setFilterById(previous)
                        val cursor = manager.query(query)
                        val complete = cursor?.use {
                            it.moveToFirst() && it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
                        } ?: false
                        if (complete) continue
                    }
                    val request = DownloadManager.Request(Uri.parse(url))
                        .setTitle("StreamForge editor pack")
                        .setDescription("Downloading $filename for offline editing")
                        .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, filename)
                        .setAllowedOverMetered(false)
                        .setAllowedOverRoaming(false)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    val downloadId = manager.enqueue(request)
                    prefs.edit().putLong(key, downloadId).apply()
                }
            }
        }
    }
}
