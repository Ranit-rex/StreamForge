package app.streamforge.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlin.random.Random
import app.streamforge.notifications.CastDooPushRegistration

private const val CASTDOO_WEB_CLIENT_ID =
    "464964505379-adhjuamik8b98s9fhmg0ggpmbm6q12d9.apps.googleusercontent.com"
private const val CASTDOO_TERMS_VERSION = "1.0"

@Composable
fun CastDooAuthGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    var checking by remember { mutableStateOf(true) }
    var authenticated by remember { mutableStateOf(false) }
    var acceptedTerms by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }
    var legalPage by remember { mutableStateOf("") }

    LaunchedEffect(authenticated) {
        if (authenticated) {
            CastDooPushRegistration.register(context)
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            if (user == null) {
                authenticated = false
                checking = false
            } else {
                checking = true
                db.collection("users").document(user.uid).get()
                    .addOnSuccessListener { snapshot ->
                        authenticated = snapshot.exists()
                        checking = false
                    }
                    .addOnFailureListener { error ->
                        authenticated = false
                        checking = false
                        errorText = "Could not verify your account. Check your internet and try again."
                    }
            }
        }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }

    val googleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            val account = task.getResult(ApiException::class.java)
                val idToken = account.idToken
                if (idToken.isNullOrBlank()) {
                    busy = false
                    errorText = "Google did not return an ID token. Verify the Web OAuth client in Firebase."
                } else {
                    val credential = GoogleAuthProvider.getCredential(idToken, null)
                    auth.signInWithCredential(credential)
                        .addOnSuccessListener { resultAuth ->
                            val user = resultAuth.user
                            if (user == null) {
                                busy = false
                                errorText = "Google account could not be loaded."
                            } else if (!acceptedTerms) {
                                busy = false
                                errorText = "Please accept the Terms & Conditions first."
                                auth.signOut()
                            } else {
                                createCastDooProfile(db, user) { success, message ->
                                    busy = false
                                    if (success) {
                                        authenticated = true
                                        errorText = ""
                                    } else {
                                        errorText = message
                                    }
                                }
                            }
                        }
                        .addOnFailureListener { error ->
                            busy = false
                            errorText = error.localizedMessage ?: "Google authentication failed."
                        }
                }
        } catch (error: ApiException) {
            busy = false
            errorText = when (error.statusCode) {
                10 -> "Google sign-in configuration error (code 10). The installed APK signing SHA-1 must match Firebase."
                12501 -> "Google sign-in was cancelled."
                else -> "Google sign-in failed (code ${error.statusCode})."
            }
        } catch (error: Exception) {
            busy = false
            errorText = error.localizedMessage ?: "Google sign-in failed."
        }
    }

    if (legalPage.isNotBlank()) {
        val privacy = legalPage == "privacy"
        AlertDialog(
            onDismissRequest = { legalPage = "" },
            title = { Text(if (privacy) "Privacy policy" else "Terms & conditions") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(if (privacy) AUTH_PRIVACY_TEXT else AUTH_TERMS_TEXT, fontSize = 12.sp, lineHeight = 18.sp)
                }
            },
            confirmButton = { TextButton(onClick = { legalPage = "" }) { Text("Close") } }
        )
    }

    if (checking) {
        Box(
            Modifier.fillMaxSize().background(Color(0xFF0B1018)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("CASTDOO", color = Color(0xFF56D8FF), fontSize = 30.sp, fontWeight = FontWeight.Black)
                CircularProgressIndicator(color = Color(0xFF56D8FF))
                Text("Preparing your studio…", color = Color(0xFFADB8C6), fontSize = 13.sp)
            }
        }
    } else if (authenticated) {
        content()
    } else {
        Box(
            Modifier.fillMaxSize().background(Color(0xFF0B1018)).padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().widthIn(max = 440.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Box(
                    Modifier.size(78.dp).background(Color(0xFF142C3C), RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("C", color = Color(0xFF56D8FF), fontSize = 48.sp, fontWeight = FontWeight.Black)
                }
                Text("Welcome to CastDoo", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Sign in to access your streaming studio, save your account and connect with your community.",
                    color = Color(0xFFADB8C6), fontSize = 14.sp, lineHeight = 21.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF151F2B), RoundedCornerShape(14.dp))
                        .clickable { acceptedTerms = !acceptedTerms }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = acceptedTerms,
                        onCheckedChange = { acceptedTerms = it },
                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFF20BCEB))
                    )
                    Column(Modifier.weight(1f)) {
                        Text("I agree to the", color = Color.White, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(
                                "Terms & Conditions",
                                color = Color(0xFF56D8FF),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable {
                                    legalPage = "terms"
                                }
                            )
                            Text("and", color = Color(0xFFADB8C6), fontSize = 13.sp)
                            Text(
                                "Privacy Policy",
                                color = Color(0xFF56D8FF),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable {
                                    legalPage = "privacy"
                                }
                            )
                        }
                    }
                }
                Button(
                    onClick = {
                        if (activity == null) {
                            errorText = "Unable to open Google sign-in from this screen."
                        } else if (!acceptedTerms) {
                            errorText = "Accept the Terms & Conditions to continue."
                        } else {
                            busy = true
                            errorText = ""
                            val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                                .requestEmail()
                                .requestIdToken(CASTDOO_WEB_CLIENT_ID)
                                .build()
                            googleLauncher.launch(GoogleSignIn.getClient(activity, options).signInIntent)
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(15.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF20BCEB), contentColor = Color(0xFF06131B))
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp, color = Color(0xFF06131B))
                    else Text("Continue with Google", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                if (errorText.isNotBlank()) {
                    Text(errorText, color = Color(0xFFFF8D8D), fontSize = 12.sp)
                }
                Text("Secure sign-in powered by Google", color = Color(0xFF77889A), fontSize = 11.sp)
            }
        }
    }
}

private fun createCastDooProfile(
    db: FirebaseFirestore,
    user: FirebaseUser,
    onComplete: (Boolean, String) -> Unit
) {
    val userRef = db.collection("users").document(user.uid)
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    db.runTransaction { transaction ->
        val existing = transaction.get(userRef)
        if (existing.exists()) {
            return@runTransaction true
        }
        val publicId = buildString {
            repeat(5) { append(alphabet[Random.nextInt(alphabet.length)]) }
        }
        val publicIdRef = db.collection("publicIds").document(publicId)
        val claimed = transaction.get(publicIdRef)
        if (claimed.exists()) {
            throw IllegalStateException("A user ID collision occurred. Please try signing in again.")
        }
        val now = FieldValue.serverTimestamp()
        val profile = hashMapOf<String, Any?>(
            "uid" to user.uid,
            "publicId" to publicId,
            "displayName" to (user.displayName?.take(60) ?: "CastDoo User"),
            "email" to user.email,
            "photoUrl" to user.photoUrl?.toString(),
            "createdAt" to now,
            "updatedAt" to now,
            "termsAcceptedAt" to FieldValue.serverTimestamp(),
            "termsVersion" to CASTDOO_TERMS_VERSION,
            "provider" to "google.com",
            "accountStatus" to "active"
        )
        transaction.set(publicIdRef, mapOf("uid" to user.uid, "createdAt" to FieldValue.serverTimestamp()))
        transaction.set(userRef, profile, SetOptions.merge())
        true
    }.addOnSuccessListener {
        onComplete(true, "")
    }.addOnFailureListener { error ->
        onComplete(false, error.localizedMessage ?: "Could not create your CastDoo profile.")
    }
}


private const val AUTH_PRIVACY_TEXT = """CASTDOO PRIVACY POLICY

CastDoo processes your Google account ID, name, email and profile photo to create and secure your account. Depending on features used, it may also process your CastDoo ID, friends and invitations, settings, notification device tokens, streaming sessions and technical diagnostics.

We use this information to authenticate you, provide requested features, deliver notifications, prevent abuse, maintain security and improve reliability. Google and Firebase provide authentication and selected app data services. MongoDB Atlas stores service records and notification device registrations. Firebase Cloud Messaging and UnifiedPush may be used to deliver notifications.

We do not intend to sell personal information. Data may be processed by infrastructure providers or disclosed when legally required. Records are retained as needed to operate the service, meet legal obligations and protect security. Contact the support channel inside the app for account requests.

No online service can guarantee absolute security. This notice should be reviewed against the final operating entity, local laws, retention practices and actual service configuration before public release.
"""

private const val AUTH_TERMS_TEXT = """CASTDOO TERMS AND CONDITIONS

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
