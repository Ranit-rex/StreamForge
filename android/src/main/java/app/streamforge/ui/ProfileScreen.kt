package app.streamforge.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

private val ProfileBg = Color(0xFF0B1018)
private val ProfileCard = Color(0xFF151F2B)
private val ProfileCardAlt = Color(0xFF1C2937)
private val ProfileBlue = Color(0xFF55D6FF)
private val ProfileGreen = Color(0xFF55D6A1)
private val ProfileMuted = Color(0xFF9BAABD)

@Composable
fun CastDooProfileScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val user = auth.currentUser
    var displayName by remember { mutableStateOf(user?.displayName.orEmpty()) }
    var publicId by remember { mutableStateOf("Loading…") }
    var accountStatus by remember { mutableStateOf("Active") }
    var createdAt by remember { mutableStateOf("—") }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf("") }
    var showSignOut by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    fun loadProfile() {
        val current = auth.currentUser
        if (current == null) {
            loading = false
            message = "No signed-in account found."
            return
        }
        loading = true
        db.collection("users").document(current.uid).get()
            .addOnSuccessListener { doc ->
                displayName = doc.getString("displayName") ?: current.displayName ?: "CastDoo User"
                publicId = doc.getString("publicId") ?: "Not assigned"
                accountStatus = doc.getString("accountStatus")?.replaceFirstChar { it.uppercase() } ?: "Active"
                createdAt = doc.getTimestamp("createdAt")?.toDate()?.let {
                    java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(it)
                } ?: "—"
                loading = false
                message = ""
            }
            .addOnFailureListener { error ->
                loading = false
                message = error.localizedMessage ?: "Could not load profile."
            }
    }

    LaunchedEffect(user?.uid) { loadProfile() }

    if (showEdit) AlertDialog(
        onDismissRequest = { if (!saving) showEdit = false },
        title = { Text("Edit profile name") },
        text = {
            OutlinedTextField(
                value = editName, onValueChange = { editName = it.take(60) },
                singleLine = true, label = { Text("Display name") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
            )
        },
        confirmButton = {
            TextButton(enabled = !saving && editName.trim().isNotEmpty(), onClick = {
                val current = auth.currentUser
                if (current == null) {
                    message = "Please sign in again."
                    showEdit = false
                } else {
                    saving = true
                    val clean = editName.trim().take(60)
                    db.collection("users").document(current.uid)
                        .update(mapOf("displayName" to clean, "updatedAt" to FieldValue.serverTimestamp()))
                        .addOnSuccessListener {
                            current.updateProfile(com.google.firebase.auth.UserProfileChangeRequest.Builder().setDisplayName(clean).build())
                            displayName = clean
                            saving = false
                            showEdit = false
                            message = "Profile updated successfully."
                        }
                        .addOnFailureListener { error ->
                            saving = false
                            message = error.localizedMessage ?: "Profile update failed."
                        }
                }
            }) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(onClick = { showEdit = false }) { Text("Cancel") } }
    )

    if (showSignOut) AlertDialog(
        onDismissRequest = { showSignOut = false },
        title = { Text("Sign out of CastDoo?") },
        text = { Text("Your profile and settings remain saved. You can sign in again with the same Google account.") },
        confirmButton = { TextButton(onClick = { showSignOut = false; auth.signOut() }) { Text("Sign out", color = Color(0xFFE77B7B)) } },
        dismissButton = { TextButton(onClick = { showSignOut = false }) { Text("Cancel") } }
    )

    Column(
        Modifier.fillMaxSize().background(ProfileBg).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("MY PROFILE", color = ProfileBlue, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.6.sp)
                Text("Your CastDoo account", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { loadProfile() }, enabled = !loading) { Text("↻ Refresh", color = ProfileBlue) }
        }

        Surface(color = ProfileCard, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(68.dp).clip(CircleShape).background(ProfileCardAlt), contentAlignment = Alignment.Center) {
                        Text(displayName.trim().firstOrNull()?.uppercase() ?: "C", color = ProfileBlue, fontSize = 30.sp, fontWeight = FontWeight.Black)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(displayName.ifBlank { "CastDoo User" }, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(user?.email ?: "Google account", color = ProfileMuted, fontSize = 12.sp)
                        Surface(color = Color(0xFF17372B), shape = RoundedCornerShape(30.dp)) {
                            Text("● $accountStatus", Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = ProfileGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                HorizontalDivider(color = Color(0xFF29384A))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("CASTDOO USER ID", color = ProfileMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(publicId, color = ProfileBlue, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                    }
                    OutlinedButton(onClick = {
                        if (publicId != "Loading…" && publicId != "Not assigned") {
                            clipboard.setText(AnnotatedString(publicId))
                            message = "User ID copied."
                        }
                    }, enabled = publicId != "Loading…" && publicId != "Not assigned") { Text("Copy ID") }
                }
                Button(
                    onClick = { editName = displayName; showEdit = true },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ProfileBlue, contentColor = Color(0xFF07131B))
                ) { Text("Edit profile", fontWeight = FontWeight.Bold) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProfileInfoTile("ACCOUNT CREATED", createdAt, Modifier.weight(1f))
            ProfileInfoTile("SIGN-IN", "Google", Modifier.weight(1f))
        }

        ProfileSectionTitle("ACCOUNT & SECURITY")
        ProfileActionCard("Account details", "Name, email and your unique CastDoo ID", "›") { editName = displayName; showEdit = true }
        ProfileActionCard("App permissions", "Manage notifications and Android permissions", "›") {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
        ProfileActionCard("Notification settings", "Choose whether CastDoo can notify you", "›") {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }

        ProfileSectionTitle("PREFERENCES")
        ProfileActionCard("Studio settings", "Video quality, audio, appearance and release channel", "›") {
            message = "Open Studio Settings from the Settings tab in bottom navigation."
        }
        ProfileActionCard("Privacy policy", "How CastDoo handles your account and data", "↗") {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Ranit-rex/StreamForge/blob/feature/castdoo-auth-foundation/CASTDOO_PRIVACY.md")))
        }
        ProfileActionCard("Terms & conditions", "Review the CastDoo service terms", "↗") {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Ranit-rex/StreamForge/blob/feature/castdoo-auth-foundation/CASTDOO_TERMS.md")))
        }

        ProfileSectionTitle("SESSION")
        Surface(color = ProfileCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Signed in as", color = ProfileMuted, fontSize = 11.sp)
                Text(user?.email ?: "Unknown account", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("Google sign-in secures your CastDoo profile and syncs your account identity.", color = ProfileMuted, fontSize = 11.sp)
                OutlinedButton(onClick = { showSignOut = true }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF8B8B))) { Text("Sign out") }
            }
        }

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = ProfileBlue)
        if (message.isNotBlank()) Text(message, color = if (message.contains("success") || message.contains("copied")) ProfileGreen else ProfileMuted, fontSize = 11.sp)
        Text("CastDoo · Account Center", color = ProfileMuted, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ProfileInfoTile(title: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = ProfileCard, shape = RoundedCornerShape(15.dp)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = ProfileMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ProfileSectionTitle(title: String) {
    Text(title, color = ProfileBlue, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
}

@Composable
private fun ProfileActionCard(title: String, subtitle: String, trailing: String, onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), color = ProfileCard, shape = RoundedCornerShape(15.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = ProfileMuted, fontSize = 10.sp)
            }
            Text(trailing, color = ProfileBlue, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}
