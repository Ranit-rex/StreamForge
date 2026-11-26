package app.streamforge.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

private const val CASTDOO_WEB_CLIENT_ID =
    "464964505379-adhjuamik8b98s9fhmg0ggpmbm6q12d9.apps.googleusercontent.com"
private const val CASTDOO_TERMS_VERSION = "1.0"
private const val CASTDOO_TERMS_URL =
    "https://github.com/Ranit-rex/StreamForge/blob/feature/castdoo-auth-foundation/CASTDOO_TERMS.md"
private const val CASTDOO_PRIVACY_URL =
    "https://github.com/Ranit-rex/StreamForge/blob/feature/castdoo-auth-foundation/CASTDOO_PRIVACY.md"

@Composable
fun CastDooAuthGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    var checking by remember { mutableStateOf(true) }
    var authenticated by remember { mutableStateOf(false) }
    var acceptedTerms by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }

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
        if (result.resultCode != Activity.RESULT_OK) {
            busy = false
            if (result.data != null) errorText = "Google sign-in was cancelled or could not start."
        } else {
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
            } catch (error: Exception) {
                busy = false
                errorText = error.localizedMessage ?: "Google sign-in failed."
            }
        }
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
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CASTDOO_TERMS_URL)))
                                }
                            )
                            Text("and", color = Color(0xFFADB8C6), fontSize = 13.sp)
                            Text(
                                "Privacy Policy",
                                color = Color(0xFF56D8FF),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CASTDOO_PRIVACY_URL)))
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
