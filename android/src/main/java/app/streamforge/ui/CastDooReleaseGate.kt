package app.streamforge.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.streamforge.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val CASTDOO_ADMIN_API = "https://castdoo-version-api.mranit66.workers.dev"
private const val RELEASE_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
private const val RELEASE_PREFS = "castdoo_release_policy"

private data class ReleaseInfo(
    val latestVersionCode: Long,
    val minimumVersionCode: Long,
    val versionName: String,
    val updateUrl: String,
    val releaseNotes: String,
    val forceUpdate: Boolean,
    val updateType: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("versionCode", latestVersionCode)
        .put("minimumVersionCode", minimumVersionCode)
        .put("versionName", versionName)
        .put("updateUrl", updateUrl)
        .put("releaseNotes", releaseNotes)
        .put("forceUpdate", forceUpdate)
        .put("updateType", updateType)
}

/** One installed CastDoo package can switch between the Live and Test release feeds. */
object CastDooReleaseChannel {
    val current = mutableStateOf("stable")

    fun initialize(context: Context) {
        val saved = context.getSharedPreferences(RELEASE_PREFS, Context.MODE_PRIVATE)
            .getString("selected_track", "stable")
        current.value = if (saved.equals("beta", ignoreCase = true)) "beta" else "stable"
    }

    fun setChannel(context: Context, track: String) {
        val normalized = if (track.equals("beta", ignoreCase = true)) "beta" else "stable"
        context.getSharedPreferences(RELEASE_PREFS, Context.MODE_PRIVATE)
            .edit().putString("selected_track", normalized).apply()
        current.value = normalized
    }
}

private fun parseRelease(json: JSONObject): ReleaseInfo? {
    val versionCode = json.optLong("versionCode", 0L)
    if (versionCode <= 0L) return null
    val force = json.optBoolean("forceUpdate", false)
    val type = if (force || json.optString("updateType").equals("major", true)) "major" else "mini"
    return ReleaseInfo(
        latestVersionCode = versionCode,
        minimumVersionCode = json.optLong("minimumVersionCode", 1L).coerceAtLeast(1L),
        versionName = json.optString("versionName", "CastDoo update"),
        updateUrl = json.optString("updateUrl", ""),
        releaseNotes = json.optString("releaseNotes", ""),
        forceUpdate = force,
        updateType = type
    )
}

private fun cachedRelease(context: Context, track: String): ReleaseInfo? {
    val raw = context.getSharedPreferences(RELEASE_PREFS, Context.MODE_PRIVATE)
        .getString("release_cache_$track", null) ?: return null
    return runCatching { parseRelease(JSONObject(raw)) }.getOrNull()
}

private suspend fun fetchRelease(track: String): ReleaseInfo? = withContext(Dispatchers.IO) {
    runCatching {
        val connection = URL("$CASTDOO_ADMIN_API/api/releases/$track").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in 200..299) return@runCatching null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            parseRelease(json)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

private val CastDooNavy = Color(0xFF0A101B)
private val CastDooPanel = Color(0xFF121E2D)
private val CastDooCyan = Color(0xFF53D7FF)
private val CastDooMuted = Color(0xFFA8B6C8)

@Composable
fun CastDooReleaseGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val track by CastDooReleaseChannel.current
    var release by remember(track) { mutableStateOf(cachedRelease(context, track)) }
    var continueWithoutUpdate by remember(track) { mutableStateOf(false) }

    LaunchedEffect(track) {
        val prefs = context.getSharedPreferences(RELEASE_PREFS, Context.MODE_PRIVATE)
        // Live and Test have independent daily checks. Switching channels must fetch
        // that channel immediately instead of inheriting the other channel's cooldown.
        val attemptKey = "last_attempt_$track"
        val lastAttempt = prefs.getLong(attemptKey, 0L)
        val now = System.currentTimeMillis()
        if (lastAttempt == 0L || now - lastAttempt >= RELEASE_CHECK_INTERVAL_MS) {
            prefs.edit().putLong(attemptKey, now).apply()
            val loaded = fetchRelease(track)
            if (loaded != null) {
                val checkedAt = System.currentTimeMillis()
                prefs.edit()
                    .putString("release_cache_$track", loaded.toJson().toString())
                    .putLong("last_success_$track", checkedAt)
                    .apply()
                release = loaded
            }
        }
    }

    val current = release
    val installedCode = BuildConfig.VERSION_CODE.toLong()
    // Compare numeric versionCode only; versionName is display-only.
    val updateAvailable = current != null && current.latestVersionCode > installedCode
    val mandatory = current != null && (
        current.minimumVersionCode > installedCode ||
            (updateAvailable && (current.updateType == "major" || current.forceUpdate))
        )
    val validUpdateUrl = current?.let {
        runCatching {
            val uri = Uri.parse(it.updateUrl)
            uri.scheme == "https" && !uri.host.isNullOrBlank()
        }.getOrDefault(false)
    } ?: false

    when {
        // Cached policy is enforced offline. If this is a first launch with no cache,
        // keep the app usable rather than locking the user out without a network check.
        mandatory -> CastDooUpdateScreen(
            release = current!!,
            installedCode = installedCode,
            mandatory = true,
            updateEnabled = validUpdateUrl,
            onUpdate = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(current!!.updateUrl))) }
        )
        updateAvailable && !continueWithoutUpdate -> CastDooUpdateScreen(
            release = current!!,
            installedCode = installedCode,
            mandatory = false,
            updateEnabled = validUpdateUrl,
            onUpdate = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(current!!.updateUrl))) },
            onContinue = { continueWithoutUpdate = true }
        )
        else -> content()
    }
}

@Composable
private fun ReleaseGateBackdrop() {
    // Check silently in the background; no "checking for updates" text or spinner is shown.
    Surface(Modifier.fillMaxSize(), color = CastDooNavy) {}
}

@Composable
private fun CastDooVerificationScreen(onRetry: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = CastDooNavy) {
        Column(Modifier.fillMaxSize().padding(26.dp), verticalArrangement = Arrangement.Center) {
            Text("CASTDOO", color = CastDooCyan, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
            Spacer(Modifier.height(18.dp))
            Text("Can't verify this app version", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("Connect to the internet and try again. CastDoo needs to verify the supported version before opening.", color = CastDooMuted, fontSize = 14.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(22.dp))
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Try again") }
        }
    }
}

@Composable
private fun CastDooUpdateScreen(
    release: ReleaseInfo,
    installedCode: Long,
    mandatory: Boolean,
    updateEnabled: Boolean,
    onUpdate: () -> Unit,
    onContinue: (() -> Unit)? = null
) {
    Surface(Modifier.fillMaxSize(), color = CastDooNavy) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Spacer(Modifier.height(24.dp))
            Text("CASTDOO", color = CastDooCyan, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
            Spacer(Modifier.height(22.dp))
            Surface(color = Color(0xFF163449), shape = RoundedCornerShape(18.dp)) {
                Text(
                    if (mandatory) "REQUIRED UPDATE" else "NEW VERSION AVAILABLE",
                    Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    color = CastDooCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                if (mandatory) "Update CastDoo to continue" else "A better CastDoo is ready",
                color = Color.White, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (mandatory) "This installed build is no longer supported. Install the latest version to keep using CastDoo."
                else "There’s a newer version with improvements waiting for you.",
                color = CastDooMuted, fontSize = 14.sp, lineHeight = 21.sp
            )
            Spacer(Modifier.height(24.dp))
            Surface(color = CastDooPanel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("UPDATE DETAILS", color = CastDooMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("Latest version", color = CastDooMuted, fontSize = 12.sp)
                            Text(release.versionName.ifBlank { "CastDoo" }, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Version code", color = CastDooMuted, fontSize = 12.sp)
                            Text(release.latestVersionCode.toString(), color = CastDooCyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    HorizontalDivider(color = Color(0xFF26384B))
                    Text("WHAT'S NEW", color = CastDooMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Text(
                        release.releaseNotes.ifBlank { "Performance improvements, stability updates and refinements." },
                        color = Color(0xFFE5EDF6), fontSize = 14.sp, lineHeight = 21.sp
                    )
                    Text("Installed version code: $installedCode", color = CastDooMuted, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onUpdate,
                enabled = updateEnabled,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CastDooCyan, contentColor = Color(0xFF06121C))
            ) {
                Text("Download update", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            if (!updateEnabled) {
                Spacer(Modifier.height(8.dp))
                Text("The update link is not available yet. Please contact support.", color = Color(0xFFFFB86B), fontSize = 12.sp)
            }
            if (!mandatory && onContinue != null) {
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue without updating", color = CastDooMuted)
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Text("Updating is required. You cannot skip this version.", color = CastDooMuted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}
