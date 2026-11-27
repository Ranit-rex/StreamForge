package app.streamforge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

private data class CastDooFriendRow(
    val id: String,
    val name: String,
    val photoUrl: String,
    val publicId: String,
    val pending: Boolean
)

@Composable
fun CastDooFriendsSection() {
    val user = FirebaseAuth.getInstance().currentUser
    val uid = user?.uid
    val db = remember { FirebaseFirestore.getInstance() }
    var friends by remember(uid) { mutableStateOf(emptyList<CastDooFriendRow>()) }
    var incoming by remember(uid) { mutableStateOf(emptyList<CastDooFriendRow>()) }
    var publicId by remember(uid) { mutableStateOf("") }
    var addCode by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf("") }

    DisposableEffect(uid) {
        if (uid == null) return@DisposableEffect onDispose { }
        db.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                publicId = doc.getString("publicId").orEmpty()
                val profile = hashMapOf<String, Any>(
                    "uid" to uid,
                    "publicId" to publicId,
                    "displayName" to (doc.getString("displayName") ?: user?.displayName ?: "CastDoo User"),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                (doc.getString("photoUrl") ?: user?.photoUrl?.toString())?.let { profile["photoUrl"] = it }
                db.collection("publicProfiles").document(uid).set(profile, SetOptions.merge())
            }
        val registration = db.collection("friendships").whereArrayContains("members", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    feedback = error.localizedMessage ?: "Friends could not be loaded."
                    return@addSnapshotListener
                }
                val rows = snapshot?.documents.orEmpty()
                friends = rows.filter { it.getString("status") == "accepted" }.mapNotNull { doc ->
                    val from = doc.getString("fromUid").orEmpty()
                    val isSender = from == uid
                    CastDooFriendRow(
                        id = doc.id,
                        name = doc.getString(if (isSender) "toName" else "fromName") ?: "CastDoo User",
                        photoUrl = doc.getString(if (isSender) "toPhoto" else "fromPhoto").orEmpty(),
                        publicId = doc.getString(if (isSender) "toPublicId" else "fromPublicId").orEmpty(),
                        pending = false
                    )
                }
                incoming = rows.filter { it.getString("status") == "pending" && it.getString("toUid") == uid }.map { doc ->
                    CastDooFriendRow(
                        id = doc.id,
                        name = doc.getString("fromName") ?: "CastDoo User",
                        photoUrl = doc.getString("fromPhoto").orEmpty(),
                        publicId = doc.getString("fromPublicId").orEmpty(),
                        pending = true
                    )
                }
            }
        onDispose { registration.remove() }
    }

    if (showAdd) AlertDialog(
        onDismissRequest = { if (!adding) showAdd = false },
        title = { Text("Add a friend") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter their 5 character CastDoo ID. You can have up to 6 friends.", color = Color(0xFF9BAABD), fontSize = 12.sp)
                OutlinedTextField(
                    value = addCode,
                    onValueChange = { addCode = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(5) },
                    label = { Text("CastDoo ID") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !adding && addCode.length == 5, onClick = {
                val me = uid ?: return@TextButton
                adding = true
                feedback = ""
                db.collection("publicIds").document(addCode).get()
                    .addOnSuccessListener { idDoc ->
                        val friendUid = idDoc.getString("uid")
                        if (friendUid.isNullOrBlank()) {
                            adding = false
                            feedback = "No account found with that ID."
                            return@addOnSuccessListener
                        }
                        if (friendUid == me) {
                            adding = false
                            feedback = "You cannot add your own account."
                            return@addOnSuccessListener
                        }
                        db.collection("friendships").whereArrayContains("members", me).get()
                            .addOnSuccessListener { existing ->
                                val accepted = existing.documents.filter { it.getString("status") == "accepted" }
                                if (accepted.size >= 6) {
                                    adding = false
                                    feedback = "Friend limit reached. Remove a friend first."
                                    return@addOnSuccessListener
                                }
                                val pairId = listOf(me, friendUid).sorted().joinToString("_")
                                val existingPair = existing.documents.firstOrNull { it.id == pairId }
                                if (existingPair != null) {
                                    adding = false
                                    feedback = when (existingPair.getString("status")) {
                                        "accepted" -> "You are already friends."
                                        "pending" -> "A friend request already exists."
                                        else -> "This friend request was already handled."
                                    }
                                    return@addOnSuccessListener
                                }
                                db.collection("publicProfiles").document(friendUid).get()
                                    .addOnSuccessListener { profile ->
                                        val currentName = user?.displayName ?: "CastDoo User"
                                        val currentPhoto = user?.photoUrl?.toString().orEmpty()
                                        val targetName = profile.getString("displayName") ?: "CastDoo User"
                                        val targetPhoto = profile.getString("photoUrl").orEmpty()
                                        val targetPublicId = profile.getString("publicId") ?: addCode
                                        val data = hashMapOf<String, Any>(
                                            "members" to listOf(me, friendUid),
                                            "fromUid" to me, "toUid" to friendUid,
                                            "fromName" to currentName, "fromPhoto" to currentPhoto,
                                            "fromPublicId" to publicId,
                                            "toName" to targetName, "toPhoto" to targetPhoto,
                                            "toPublicId" to targetPublicId,
                                            "status" to "pending", "createdBy" to me,
                                            "createdAt" to FieldValue.serverTimestamp()
                                        )
                                        db.collection("friendships").document(pairId).set(data)
                                            .addOnSuccessListener {
                                                adding = false
                                                showAdd = false
                                                addCode = ""
                                                feedback = "Friend request sent."
                                            }.addOnFailureListener { error ->
                                                adding = false
                                                feedback = error.localizedMessage ?: "Could not send friend request."
                                            }
                                    }.addOnFailureListener { error ->
                                        adding = false
                                        feedback = error.localizedMessage ?: "Could not load that profile."
                                    }
                            }.addOnFailureListener { error ->
                                adding = false
                                feedback = error.localizedMessage ?: "Could not check your friend list."
                            }
                    }.addOnFailureListener { error ->
                        adding = false
                        feedback = error.localizedMessage ?: "Could not find that CastDoo ID."
                    }
            }) { Text(if (adding) "Sending…" else "Send request") }
        },
        dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } }
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("FRIENDS", color = Color(0xFF55D6FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text("${friends.size} / 6 friends", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Button(onClick = { showAdd = true }, enabled = friends.size < 6, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF55D6FF), contentColor = Color(0xFF07131B))) {
                Text("＋ Add friend")
            }
        }
        if (friends.isEmpty()) {
            Surface(color = Color(0xFF151F2B), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Text("Your friend list is empty. Add someone using their CastDoo ID.", Modifier.padding(14.dp), color = Color(0xFF9BAABD), fontSize = 12.sp)
            }
        }
        friends.forEach { friend ->
            FriendRowCard(friend, trailing = {
                TextButton(enabled = busyId != friend.id, onClick = {
                    busyId = friend.id
                    db.collection("friendships").document(friend.id).delete()
                        .addOnSuccessListener { busyId = ""; feedback = "Friend removed." }
                        .addOnFailureListener { busyId = ""; feedback = it.localizedMessage ?: "Could not remove friend." }
                }) { Text("Remove", color = Color(0xFFFF9292), fontSize = 11.sp) }
            })
        }
        if (incoming.isNotEmpty()) {
            Text("FRIEND REQUESTS", color = Color(0xFF9BAABD), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            incoming.forEach { friend ->
                FriendRowCard(friend, trailing = {
                    Row {
                        TextButton(enabled = busyId != friend.id, onClick = {
                            busyId = friend.id
                            db.collection("friendships").document(friend.id).update("status", "accepted")
                                .addOnSuccessListener { busyId = ""; feedback = "Friend added." }
                                .addOnFailureListener { busyId = ""; feedback = it.localizedMessage ?: "Could not accept request." }
                        }) { Text("Accept", color = Color(0xFF55D6A1), fontSize = 11.sp) }
                        TextButton(enabled = busyId != friend.id, onClick = {
                            busyId = friend.id
                            db.collection("friendships").document(friend.id).update("status", "declined")
                                .addOnSuccessListener { busyId = ""; feedback = "Request declined." }
                                .addOnFailureListener { busyId = ""; feedback = it.localizedMessage ?: "Could not decline request." }
                        }) { Text("Decline", color = Color(0xFFFF9292), fontSize = 11.sp) }
                    }
                })
            }
        }
        if (publicId.isNotBlank()) Text("Your CastDoo ID: $publicId", color = Color(0xFF55D6FF), fontSize = 11.sp)
        if (feedback.isNotBlank()) Text(feedback, color = Color(0xFF9BAABD), fontSize = 11.sp)
    }
}

@Composable
private fun FriendRowCard(friend: CastDooFriendRow, trailing: @Composable () -> Unit) {
    Surface(color = Color(0xFF151F2B), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0xFF1C2937)), contentAlignment = Alignment.Center) {
                if (friend.photoUrl.isNotBlank()) AsyncImage(friend.photoUrl, contentDescription = "Friend profile photo", modifier = Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                else Text(friend.name.firstOrNull()?.uppercase() ?: "C", color = Color(0xFF55D6FF), fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(friend.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(friend.publicId, color = Color(0xFF9BAABD), fontSize = 10.sp)
            }
            trailing()
        }
    }
}
