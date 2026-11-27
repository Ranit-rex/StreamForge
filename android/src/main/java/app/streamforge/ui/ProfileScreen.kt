package app.streamforge.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
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
    val auth = remember { FirebaseAuth.getInstance() }
    val user = auth.currentUser
    var displayName by remember(user?.uid) { mutableStateOf(user?.displayName ?: "CastDoo User") }
    var publicId by remember(user?.uid) { mutableStateOf(user?.uid?.take(10)?.uppercase() ?: "Unavailable") }
    var accountStatus by remember(user?.uid) { mutableStateOf("Active") }
    var createdAt by remember(user?.uid) {
        mutableStateOf(user?.metadata?.creationTimestamp?.let {
            java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(it))
        } ?: "—")
    }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf("") }
    var showSignOut by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var legalPage by remember { mutableStateOf("") }
    var showPlans by remember { mutableStateOf(false) }

    LaunchedEffect(user?.uid) {
        val uid = user?.uid ?: return@LaunchedEffect
        loading = true
        runCatching { FirebaseFirestore.getInstance().collection("users").document(uid).get() }
            .onSuccess { task ->
                task.addOnSuccessListener { doc ->
                    displayName = doc.getString("displayName")?.takeIf { it.isNotBlank() } ?: auth.currentUser?.displayName ?: "CastDoo User"
                    publicId = doc.getString("publicId")?.takeIf { it.isNotBlank() } ?: uid.take(10).uppercase()
                    accountStatus = doc.getString("accountStatus")?.replaceFirstChar { it.uppercase() } ?: "Active"
                    createdAt = doc.getTimestamp("createdAt")?.toDate()?.let {
                        java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(it)
                    } ?: createdAt
                    loading = false
                }.addOnFailureListener { error ->
                    loading = false
                    message = error.localizedMessage ?: "Showing basic account details."
                }
            }
            .onFailure { error ->
                loading = false
                message = error.localizedMessage ?: "Showing basic account details."
            }
    }

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
                    runCatching {
                        FirebaseFirestore.getInstance().collection("users").document(current.uid)
                            .update(mapOf("displayName" to clean, "updatedAt" to FieldValue.serverTimestamp()))
                    }.onSuccess { task ->
                        task.addOnSuccessListener {
                            runCatching {
                                current.updateProfile(com.google.firebase.auth.UserProfileChangeRequest.Builder().setDisplayName(clean).build())
                            }
                            displayName = clean
                            saving = false
                            showEdit = false
                            message = "Profile updated successfully."
                        }.addOnFailureListener { error ->
                            saving = false
                            message = error.localizedMessage ?: "Profile update failed."
                        }
                    }.onFailure { error ->
                        saving = false
                        message = error.localizedMessage ?: "Profile update failed."
                    }
                }
            }) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(onClick = { showEdit = false }) { Text("Cancel") } }
    )

    if (legalPage.isNotBlank()) {
        val isPrivacy = legalPage == "privacy"
        AlertDialog(
            onDismissRequest = { legalPage = "" },
            title = { Text(if (isPrivacy) "Privacy policy" else "Terms & conditions") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        if (isPrivacy) CASTDOO_PRIVACY_TEXT else CASTDOO_TERMS_TEXT,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            },
            confirmButton = { TextButton(onClick = { legalPage = "" }) { Text("Close") } }
        )
    }
    if (showPlans) AlertDialog(
        onDismissRequest = { showPlans = false },
        title = { Text("CastDoo plans") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Free · ₹0", fontWeight = FontWeight.Bold)
                Text("Core streaming, basic profile and friends.")
                Divider()
                Text("Plus · Demo ₹99/month", fontWeight = FontWeight.Bold)
                Text("Higher quality presets, extended analytics and custom profile themes.")
                Divider()
                Text("Studio · Demo ₹199/month", fontWeight = FontWeight.Bold)
                Text("Advanced creator tools and expanded stream controls.")
                Text("Prototype only. These are sample prices and no payment or auto renewal is active.", color = ProfileMuted, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = { showPlans = false }) { Text("Got it") } }
    )

    if (showSignOut) AlertDialog(
        onDismissRequest = { showSignOut = false },
        title = { Text("Sign out of CastDoo?") },
        text = { Text("Your profile and settings remain saved. You can sign in again with the same Google account.") },
        confirmButton = { TextButton(onClick = { showSignOut = false; auth.signOut() }) { Text("Sign out", color = Color(0xFFE77B7B)) } },
        dismissButton = { TextButton(onClick = { showSignOut = false }) { Text("Cancel") } }
    )

    // The parent app already owns the page scroll. Avoid a second nested verticalScroll here.
    Column(
        Modifier.fillMaxWidth().background(ProfileBg).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("MY PROFILE", color = ProfileBlue, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.6.sp)
                Text("Your CastDoo account", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = {
                val uid = auth.currentUser?.uid
                if (uid != null) {
                    loading = true
                    runCatching { FirebaseFirestore.getInstance().collection("users").document(uid).get() }
                        .onSuccess { it.addOnSuccessListener { doc ->
                            displayName = doc.getString("displayName")?.takeIf { name -> name.isNotBlank() } ?: auth.currentUser?.displayName ?: "CastDoo User"
                            publicId = doc.getString("publicId")?.takeIf { id -> id.isNotBlank() } ?: uid.take(10).uppercase()
                            accountStatus = doc.getString("accountStatus")?.replaceFirstChar { ch -> ch.uppercase() } ?: "Active"
                            loading = false
                        }.addOnFailureListener { loading = false; message = it.localizedMessage ?: "Refresh failed." } }
                        .onFailure { loading = false; message = it.localizedMessage ?: "Refresh failed." }
                }
            }, enabled = !loading) { Text("↻ Refresh", color = ProfileBlue) }
        }

        Surface(color = ProfileCard, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(68.dp).clip(CircleShape).background(ProfileCardAlt), contentAlignment = Alignment.Center) {
                        if (user?.photoUrl != null) {
                            AsyncImage(model = user.photoUrl, contentDescription = "Google profile photo",
                                modifier = Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                        } else {
                            Text(displayName.trim().firstOrNull()?.uppercase() ?: "C", color = ProfileBlue, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(displayName.ifBlank { "CastDoo User" }, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(user?.email ?: "Signed-in account", color = ProfileMuted, fontSize = 12.sp)
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
                        val manager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        manager?.setPrimaryClip(android.content.ClipData.newPlainText("CastDoo ID", publicId))
                        message = "User ID copied."
                    }) { Text("Copy ID") }
                }
                Button(onClick = { editName = displayName; showEdit = true }, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(13.dp),
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
            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
        }
        ProfileActionCard("Notification settings", "Choose whether CastDoo can notify you", "›") {
            runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
        }
        ProfileSectionTitle("PREFERENCES")
        ProfileActionCard("Studio settings", "Video quality, audio, appearance and release channel", "›") {
            message = "Open Studio Settings from the Settings tab in bottom navigation."
        }
        ProfileActionCard("Privacy policy", "Read how CastDoo uses account and app data", "›") { legalPage = "privacy" }
        ProfileActionCard("Terms & conditions", "Read the CastDoo service terms", "›") { legalPage = "terms" }
        ProfileSectionTitle("YOUR PLAN")
        Surface(color = ProfileCard, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().clickable { showPlans = true }) {
            Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("CastDoo Free", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("Current prototype plan · no payment is collected", color = ProfileMuted, fontSize = 10.sp)
                }
                Text("View plans  ›", color = ProfileBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        CastDooFriendsSection()
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
        if (message.isNotBlank()) Text(message, color = if (message.contains("success", true) || message.contains("copied", true)) ProfileGreen else ProfileMuted, fontSize = 11.sp)
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

private const val CASTDOO_PRIVACY_TEXT = """CASTDOO PRIVACY POLICY

CastDoo processes your Google account ID, name, email and profile photo to create and secure your account. Depending on the features you use, it may also process your CastDoo ID, friends and invitations, settings, notification device tokens, streaming sessions and technical diagnostics.

We use this information to sign you in, provide app features, deliver notifications, prevent abuse, maintain security and improve reliability. Google and Firebase provide authentication and selected app data services. MongoDB Atlas stores service records and notification device registrations. Firebase Cloud Messaging and UnifiedPush may be used to deliver notifications.

We do not intend to sell personal information. Data may be processed by infrastructure providers or disclosed when legally required. Records are retained for as long as needed to operate the service, meet legal obligations and protect security. Account deletion and data export controls are being added; until available, contact the support channel published inside the app for account requests.

No online service can guarantee absolute security. This notice should be reviewed against the final operating entity, local laws, retention settings and actual service configuration before public release.
"""

private const val CASTDOO_TERMS_TEXT = """CASTDOO TERMS AND CONDITIONS

By creating or using a CastDoo account, you agree to use the service lawfully and respectfully.

1. Keep your Google account secure and provide accurate profile information.
2. Do not use CastDoo to break laws, infringe rights, harass people, distribute harmful content or disrupt the service.
3. You are responsible for content you stream, record, upload, share or send and must have the rights and permissions needed to use it.
4. Features may change or be interrupted. Unlimited storage, bandwidth or uninterrupted service is not guaranteed.
5. Sign in and some features rely on Google, Firebase and other providers whose terms may also apply.
6. Access may be restricted when reasonably necessary to protect users, the service or comply with law.
7. Account deletion and subscription cancellation controls will be provided in app before paid subscriptions are enabled.
8. These terms may be updated as the service changes.

This is a prototype service notice, not legal advice. Review it for the final operating entity and applicable jurisdictions.
"""
