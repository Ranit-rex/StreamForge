package app.streamforge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

@Composable
internal fun CastDooNotificationsPanel() {
    val uid = FirebaseAuth.getInstance().currentUser?.uid
    var items by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    DisposableEffect(uid) {
        if (uid == null) {
            loading = false
            error = "Sign in to view your CastDoo notifications."
            onDispose { }
        } else {
            val registration = FirebaseFirestore.getInstance()
                .collection("users").document(uid).collection("notifications")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(100)
                .addSnapshotListener { snapshot, exception ->
                    loading = false
                    if (exception != null) {
                        error = "Notifications could not be loaded. Please try again."
                    } else {
                        error = ""
                        items = snapshot?.documents?.map { doc ->
                            mapOf(
                                "id" to doc.id,
                                "title" to (doc.getString("title") ?: "CastDoo update"),
                                "body" to (doc.getString("body") ?: ""),
                                "read" to (doc.getBoolean("read") ?: false),
                                "createdAt" to (doc.getTimestamp("createdAt")?.toDate()?.time ?: 0L)
                            )
                        } ?: emptyList()
                    }
                }
            onDispose { registration.remove() }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("NOTIFICATIONS", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp)
        Text("Your updates", style = MaterialTheme.typography.headlineSmall)
        when {
            loading -> CircularProgressIndicator()
            error.isNotBlank() -> Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant)
            items.isEmpty() -> Text("You're all caught up. New announcements will appear here.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 620.dp)) {
                items(items, key = { it["id"].toString() }) { item ->
                    val id = item["id"].toString()
                    val read = item["read"] as Boolean
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (read) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (!read && uid != null) {
                                FirebaseFirestore.getInstance().collection("users").document(uid)
                                    .collection("notifications").document(id)
                                    .update(mapOf("read" to true, "readAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()))
                            }
                        }
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(item["title"].toString(), style = MaterialTheme.typography.titleMedium)
                            Text(item["body"].toString(), style = MaterialTheme.typography.bodyMedium)
                            Text(if (read) "READ" else "NEW · TAP TO MARK READ", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
