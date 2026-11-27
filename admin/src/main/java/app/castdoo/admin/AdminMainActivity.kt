package app.castdoo.admin

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.functions.FirebaseFunctions

private const val WEB_CLIENT_ID = "464964505379-adhjuamik8b98s9fhmg0ggpmbm6q12d9.apps.googleusercontent.com"
private const val PROJECT_ID = "castdoo"
private const val APP_ID = "1:464964505379:android:9673e87c44c1588e088696"
private const val API_KEY = "AIzaSyBuNI9MYuqU341vJRiZ0liOihad_N3XkEY"
private const val SENDER_ID = "464964505379"

class AdminMainActivity : ComponentActivity() {
    private lateinit var adminAuth: FirebaseAuth
    private lateinit var adminFunctions: FirebaseFunctions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val options = FirebaseOptions.Builder()
            .setApplicationId(APP_ID)
            .setApiKey(API_KEY)
            .setProjectId(PROJECT_ID)
            .setGcmSenderId(SENDER_ID)
            .build()
        val app = FirebaseApp.getApps(this).firstOrNull { it.name == "CastDooAdmin" }
            ?: FirebaseApp.initializeApp(this, options, "CastDooAdmin")
            ?: error("Could not initialize CastDoo services")
        adminAuth = FirebaseAuth.getInstance(app)
        adminFunctions = FirebaseFunctions.getInstance(app, "asia-south1")
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF55D6FF),
                background = Color(0xFF0B1018),
                surface = Color(0xFF151F2B)
            )) {
                AdminRoot(
                    auth = adminAuth,
                    functions = adminFunctions,
                    onGoogleSignIn = { launchGoogleSignIn() }
                )
            }
        }
    }

    private val googleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java)
            val token = account.idToken
            if (token.isNullOrBlank()) {
                AdminUiState.error.value = "Google did not return an ID token. Check the Admin app OAuth registration."
                return@registerForActivityResult
            }
            adminAuth.signInWithCredential(GoogleAuthProvider.getCredential(token, null))
                .addOnSuccessListener { authResult ->
                    authResult.user?.getIdToken(true)?.addOnSuccessListener { tokenResult ->
                        if (tokenResult.claims["admin"] == true) {
                            AdminUiState.error.value = ""
                            AdminUiState.authorized.value = true
                        } else {
                            adminAuth.signOut()
                            AdminUiState.error.value = "This Google account is not authorized as a CastDoo admin."
                        }
                    }?.addOnFailureListener {
                        adminAuth.signOut()
                        AdminUiState.error.value = "Could not verify admin permissions."
                    }
                }
                .addOnFailureListener { error ->
                    AdminUiState.error.value = error.localizedMessage ?: "Admin sign-in failed."
                }
        } catch (error: ApiException) {
            AdminUiState.error.value = "Google sign-in failed (code ${error.statusCode})."
        } catch (error: Exception) {
            AdminUiState.error.value = error.localizedMessage ?: "Google sign-in failed."
        }
    }

    private fun launchGoogleSignIn() {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestIdToken(WEB_CLIENT_ID)
            .build()
        googleLauncher.launch(GoogleSignIn.getClient(this, options).signInIntent)
    }
}

private object AdminUiState {
    val authorized = mutableStateOf(false)
    val error = mutableStateOf("")
}

@Composable
private fun AdminRoot(
    auth: FirebaseAuth,
    functions: FirebaseFunctions,
    onGoogleSignIn: () -> Unit
) {
    val authorized by AdminUiState.authorized
    val error by AdminUiState.error
    LaunchedEffect(auth.currentUser?.uid) {
        val signedInUser = auth.currentUser
        if (signedInUser != null) {
            signedInUser.getIdToken(true)
                .addOnSuccessListener { token ->
                    if (token.claims["admin"] == true) {
                        AdminUiState.authorized.value = true
                        AdminUiState.error.value = ""
                    } else {
                        auth.signOut()
                        AdminUiState.authorized.value = false
                        AdminUiState.error.value = "This Google account is not authorized as a CastDoo admin."
                    }
                }
                .addOnFailureListener {
                    auth.signOut()
                    AdminUiState.authorized.value = false
                    AdminUiState.error.value = "Could not verify admin permissions."
                }
        }
    }
    var section by remember { mutableStateOf("overview") }
    Surface(Modifier.fillMaxSize(), color = Color(0xFF0B1018)) {
        if (!authorized || auth.currentUser == null) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("CASTDOO", color = Color(0xFF55D6FF), fontSize = 14.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(10.dp))
                Text("Admin Console", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Private control center for CastDoo releases and user notifications.", color = Color(0xFF9BAABD))
                Spacer(Modifier.height(24.dp))
                Button(onClick = onGoogleSignIn, modifier = Modifier.fillMaxWidth()) { Text("Continue with Google") }
                if (error.isNotBlank()) Text(error, color = Color(0xFFFF8585), modifier = Modifier.padding(top = 12.dp))
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("CASTDOO ADMIN", color = Color(0xFF55D6FF), fontSize = 12.sp, fontWeight = FontWeight.Black)
                        Text("Control Center", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { auth.signOut(); AdminUiState.authorized.value = false }) { Text("Sign out") }
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("overview" to "Overview", "push" to "Push", "versions" to "Versions").forEach { (key, label) ->
                        FilterChip(selected = section == key, onClick = { section = key }, label = { Text(label) })
                    }
                }
                Spacer(Modifier.height(18.dp))
                when (section) {
                    "push" -> PushComposer(functions)
                    "versions" -> ReleaseManager(functions)
                    else -> {
                        AdminCard("Push notifications", "Send announcements to all users or selected accounts.")
                        AdminCard("Beta / Stable", "Manage beta testing releases separately from stable releases.")
                        AdminCard("Access control", "Every action is verified by the server-side admin claim.")
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth().padding(bottom = 10.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF151F2B)), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(5.dp))
            Text(body, color = Color(0xFF9BAABD), fontSize = 13.sp)
        }
    }
}

@Composable
private fun PushComposer(functions: FirebaseFunctions) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("all") }
    var userIds by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("normal") }
    var actionUrl by remember { mutableStateOf("") }
    var resultText by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Send push notification", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(title, { title = it }, label = { Text("Notification title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(body, { body = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(target == "all", { target = "all" }, label = { Text("All users") })
            FilterChip(target == "selected", { target = "selected" }, label = { Text("Selected users") })
        }
        if (target == "selected") OutlinedTextField(userIds, { userIds = it }, label = { Text("User UIDs (comma separated)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        OutlinedTextField(actionUrl, { actionUrl = it }, label = { Text("Optional HTTPS action URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(priority == "normal", { priority = "normal" }, label = { Text("Normal") })
            FilterChip(priority == "important", { priority = "important" }, label = { Text("Important") })
        }
        Button(
            enabled = !sending && title.isNotBlank() && body.isNotBlank() && (target == "all" || userIds.isNotBlank()),
            onClick = {
                sending = true
                resultText = ""
                val ids = userIds.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                val payload = hashMapOf<String, Any>(
                    "title" to title.trim(), "body" to body.trim(), "target" to target, "priority" to priority
                )
                if (target == "selected") payload["targetUids"] = ids
                if (actionUrl.isNotBlank()) payload["actionUrl"] = actionUrl.trim()
                functions.getHttpsCallable("sendAdminNotification").call(payload)
                    .addOnSuccessListener { task ->
                        val data = task.data as? Map<*, *>
                        resultText = "Sent to ${data?.get("recipientCount") ?: 0} users · Push delivered: ${data?.get("pushSuccessCount") ?: 0}"
                        sending = false
                    }
                    .addOnFailureListener { e -> resultText = e.localizedMessage ?: "Send failed"; sending = false }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (sending) "Sending…" else "Send notification") }
        if (resultText.isNotBlank()) Text(resultText, color = Color(0xFF55D6A1), fontSize = 13.sp)
    }
}

@Composable
private fun ReleaseManager(functions: FirebaseFunctions) {
    var track by remember { mutableStateOf("beta") }
    var versionCode by remember { mutableStateOf("1") }
    var minimumVersionCode by remember { mutableStateOf("1") }
    var versionName by remember { mutableStateOf("1.0.0-beta.1") }
    var updateUrl by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var resultText by remember { mutableStateOf("") }
    var publishing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Release management", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(track == "beta", { track = "beta" }, label = { Text("Beta") })
            FilterChip(track == "stable", { track = "stable" }, label = { Text("Stable") })
        }
        OutlinedTextField(versionName, { versionName = it }, label = { Text("Version name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(versionCode, { versionCode = it }, label = { Text("Version code") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(minimumVersionCode, { minimumVersionCode = it }, label = { Text("Minimum supported build") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(updateUrl, { updateUrl = it }, label = { Text("APK / Play Store update URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(notes, { notes = it }, label = { Text("Release notes") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        Button(
            enabled = !publishing && versionCode.toIntOrNull() != null && minimumVersionCode.toIntOrNull() != null && updateUrl.startsWith("https://"),
            onClick = {
                publishing = true
                val payload = mapOf(
                    "track" to track,
                    "versionCode" to (versionCode.toIntOrNull() ?: 1),
                    "minimumVersionCode" to (minimumVersionCode.toIntOrNull() ?: 1),
                    "versionName" to versionName.trim(),
                    "updateUrl" to updateUrl.trim(),
                    "releaseNotes" to notes.trim()
                )
                functions.getHttpsCallable("publishAppRelease").call(payload)
                    .addOnSuccessListener { resultText = "${track.replaceFirstChar { it.uppercase() }} release published"; publishing = false }
                    .addOnFailureListener { e -> resultText = e.localizedMessage ?: "Publish failed"; publishing = false }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (publishing) "Publishing…" else "Publish release") }
        if (resultText.isNotBlank()) Text(resultText, color = Color(0xFF55D6A1), fontSize = 13.sp)
    }
}
