package app.streamforge.notifications

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import org.unifiedpush.android.connector.UnifiedPush

private const val CASTDOO_PUSH_MESSAGE = "CastDoo notifications"

object CastDooPushRegistration {
    fun register(context: Context) {
        if (FirebaseAuth.getInstance().currentUser == null) return
        runCatching {
            UnifiedPush.tryUseCurrentOrDefaultDistributor(context) { available ->
                if (available) runCatching { UnifiedPush.register(context, CASTDOO_PUSH_MESSAGE, null) }
            }
        }
    }
}
