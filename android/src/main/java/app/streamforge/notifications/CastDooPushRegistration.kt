package app.streamforge.notifications

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val CASTDOO_ADMIN_API = "https://streamforge-admin-api-y6ie.onrender.com"

object CastDooPushRegistration {
    fun register(context: Context) {
        if (FirebaseAuth.getInstance().currentUser == null) return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            registerToken(context.applicationContext, token)
        }
    }

    fun registerToken(context: Context, token: String) {
        if (token.isBlank()) return
        Thread {
            var connection: HttpURLConnection? = null
            try {
                val user = FirebaseAuth.getInstance().currentUser ?: return@Thread
                val idToken = Tasks.await(user.getIdToken(true)).token ?: return@Thread
                connection = (URL("$CASTDOO_ADMIN_API/api/devices/fcm/register").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("Authorization", "Bearer $idToken")
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
                val payload = JSONObject().put("token", token)
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                connection.responseCode
            } catch (_: Exception) {
                // The next app launch or token refresh retries registration.
            } finally { connection?.disconnect() }
        }.start()
    }
}
