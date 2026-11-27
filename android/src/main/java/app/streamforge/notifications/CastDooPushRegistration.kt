package app.streamforge.notifications

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import org.json.JSONObject
import org.unifiedpush.android.connector.UnifiedPush
import java.net.HttpURLConnection
import java.net.URL

private const val CASTDOO_ADMIN_API = "https://streamforge-admin-api-y6ie.onrender.com"
private const val PUSH_INSTANCE_MESSAGE = "CastDoo notifications"

object CastDooPushRegistration {
    fun register(context: Context) {
        val appContext = context.applicationContext
        val user = FirebaseAuth.getInstance().currentUser ?: return

        // Record a signed-in app session even if the user has not enabled push.
        Thread { registerAppSession(user) }.start()

        try {
            if (UnifiedPush.getDistributors(appContext).isEmpty()) {
                Log.w("CastDooPush", "No UnifiedPush distributor is installed; install a distributor to receive background push notifications")
                return
            }
            if (UnifiedPush.getAckDistributor(appContext) != null) {
                UnifiedPush.register(appContext, messageForDistributor = PUSH_INSTANCE_MESSAGE)
            } else {
                UnifiedPush.tryUseCurrentOrDefaultDistributor(appContext) { selected ->
                    if (selected) {
                        runCatching {
                            UnifiedPush.register(appContext, messageForDistributor = PUSH_INSTANCE_MESSAGE)
                        }.onFailure { Log.e("CastDooPush", "UnifiedPush registration request failed", it) }
                    } else {
                        Log.w("CastDooPush", "No UnifiedPush distributor selected or available")
                    }
                }
            }
        } catch (error: Exception) {
            Log.e("CastDooPush", "UnifiedPush setup failed", error)
        }
    }

    private fun registerAppSession(user: FirebaseUser) {
        var connection: HttpURLConnection? = null
        try {
            val idToken = Tasks.await(user.getIdToken(true)).token ?: return
            connection = (URL("$CASTDOO_ADMIN_API/api/usage/session").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Authorization", "Bearer $idToken")
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            val payload = JSONObject().put("platform", "android")
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val response = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Log.e("CastDooPush", "App session registration rejected ($status): $response")
            } else {
                Log.i("CastDooPush", "App session recorded")
            }
        } catch (error: Exception) {
            Log.e("CastDooPush", "App session registration failed", error)
        } finally {
            connection?.disconnect()
        }
    }
}
