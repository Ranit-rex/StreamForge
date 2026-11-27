package app.streamforge.notifications

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import java.security.MessageDigest

object CastDooPushRegistration {
    fun register(context: Context) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> saveToken(user.uid, token) }
    }

    fun saveToken(uid: String, token: String) {
        if (uid.isBlank() || token.isBlank()) return
        val tokenId = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }
        FirebaseFirestore.getInstance()
            .collection("users").document(uid)
            .collection("deviceTokens").document(tokenId)
            .set(
                mapOf(
                    "token" to token,
                    "platform" to "android",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
    }
}
