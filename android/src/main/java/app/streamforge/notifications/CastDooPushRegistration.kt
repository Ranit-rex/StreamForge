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
        val user = FirebaseAuth.getInstance().currentUser ?: return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            Thread {
                var connection: HttpURLConnection? = null
                try {
                    val idToken = Tasks.await(user.getIdToken(true)).token ?: return@Thread
                    connection = (URL("$CASTDOO_ADMIN_API/api/devices/register").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 10000
                        readTimeout = 10000
                        setRequestProperty("Authorization", "Bearer $idToken")
                        setRequestProperty("Content-Type", "application/json")
                        doOutput = true
                    }
                    val body = JSONObject().put("token", token).toString()
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    connection.responseCode
                } catch (_: Exception) {
                    // Push registration retries on the next authenticated app session.
                } finally {
                    connection?.disconnect()
                }
            }.start()
        }
    }
}
