package app.streamforge.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val CASTDOO_ACCOUNT_API = "https://streamforge-admin-api-y6ie.onrender.com"

private data class CastDooFriend(val uid: String, val name: String, val email: String, val photoUrl: String)
private data class CastDooFriendRequest(val requestId: String, val name: String, val email: String)

private suspend fun castDooFriendsApi(
    token: String,
    path: String,
    method: String = "GET",
    payload: JSONObject = JSONObject()
): JSONObject = withContext(Dispatchers.IO) {
    val connection = (URL(CASTDOO_ACCOUNT_API + path).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 12000
        readTimeout = 15000
        setRequestProperty("Authorization", "Bearer $token")
        setRequestProperty("Accept", "application/json")
        if (method != "GET") {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
    }
    try {
        if (method != "GET") connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val responseText = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        val response = runCatching { JSONObject(responseText) }.getOrElse { JSONObject() }
        if (code !in 200..299) throw IllegalStateException(response.optString("error").ifBlank { "CastDoo account service is unavailable (HTTP $code)." })
        response
    } finally {
        connection.disconnect()
    }
}

private fun parseCastDooFriends(array: JSONArray?): List<CastDooFriend> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        CastDooFriend(item.optString("uid"), item.optString("name", "CastDoo user"), item.optString("email"), item.optString("photoUrl"))
    }
}

@Composable
fun CastDooAccountPanel(section: String, onSectionChange: (String) -> Unit) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val scope = rememberCoroutineScope()
    var accessToken by remember { mutableStateOf<String?>(null) }
    var profile by remember { mutableStateOf<JSONObject?>(null) }
    var friends by remember { mutableStateOf(emptyList<CastDooFriend>()) }
    var incoming by remember { mutableStateOf(emptyList<CastDooFriendRequest>()) }
    var outgoing by remember { mutableStateOf(emptyList<String>()) }
    var friendEmail by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var avatar by remember { mutableStateOf<Bitmap?>(null) }

    fun applySnapshot(data: JSONObject) {
        profile = data.optJSONObject("profile")
        friends = parseCastDooFriends(data.optJSONArray("friends"))
        val incomingJson = data.optJSONArray("incomingRequests")
        incoming = if (incomingJson == null) emptyList() else (0 until incomingJson.length()).mapNotNull { i ->
            val item = incomingJson.optJSONObject(i) ?: return@mapNotNull null
            val sender = item.optJSONObject("sender") ?: JSONObject()
            CastDooFriendRequest(item.optString("requestId"), sender.optString("name", "CastDoo user"), sender.optString("email"))
        }
        val outgoingJson = data.optJSONArray("outgoingRequests")
        outgoing = if (outgoingJson == null) emptyList() else (0 until outgoingJson.length()).mapNotNull { i ->
            outgoingJson.optJSONObject(i)?.optString("recipientEmail")
        }
    }

    fun refreshFriends() {
        val token = accessToken ?: return
        scope.launch {
            busy = true
            try { applySnapshot(castDooFriendsApi(token, "/api/friends")); message = "" }
            catch (error: Exception) { message = error.message ?: "Could not load your friends." }
            finally { busy = false }
        }
    }

    val authorizationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        try {
            val token = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data).accessToken
            if (token.isNullOrBlank()) {
                busy = false
                message = "Google did not return an account access token."
            } else {
                accessToken = token
                scope.launch {
                    try { applySnapshot(castDooFriendsApi(token, "/api/friends")); message = "Google account connected." }
                    catch (error: Exception) { message = error.message ?: "Could not connect your CastDoo profile." }
                    finally { busy = false }
                }
            }
        } catch (error: Exception) {
            busy = false
            message = error.message ?: "Google sign-in was cancelled."
        }
    }

    fun signInWithGoogle() {
        if (activity == null) { message = "Open CastDoo on your Android phone to connect Google."; return }
        busy = true
        message = "Opening Google's secure account screen…"
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope("openid"), Scope("email"), Scope("profile")))
            .build()
        Identity.getAuthorizationClient(activity).authorize(request)
            .addOnSuccessListener { authorization ->
                if (authorization.hasResolution()) {
                    val pendingIntent = authorization.pendingIntent
                    if (pendingIntent != null) authorizationLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                    else { busy = false; message = "Google could not open the account screen. Try again." }
                } else {
                    val token = authorization.accessToken
                    if (token.isNullOrBlank()) { busy = false; message = "Google did not return an account access token." }
                    else {
                        accessToken = token
                        scope.launch {
                            try { applySnapshot(castDooFriendsApi(token, "/api/friends")); message = "Google account connected." }
                            catch (error: Exception) { message = error.message ?: "Could not connect your CastDoo profile." }
                            finally { busy = false }
                        }
                    }
                }
            }
            .addOnFailureListener { error -> busy = false; message = error.message ?: "Could not connect to Google." }
    }

    val photoUrl = profile?.optString("photoUrl").orEmpty()
    LaunchedEffect(photoUrl) {
        avatar = if (photoUrl.startsWith("https://")) withContext(Dispatchers.IO) {
            runCatching {
                (URL(photoUrl).openConnection() as HttpURLConnection).run {
                    connectTimeout = 8000
                    readTimeout = 8000
                    inputStream.use { BitmapFactory.decodeStream(it) }
                }
            }.getOrNull()
        } else null
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("CASTDOO ACCOUNT", color = MaterialTheme.colorScheme.primary, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
            Text("Your space", color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("profile" to "Profile", "friends" to "Friends", "premium" to "Premium", "legal" to "Legal").forEach { (key, label) ->
                FilterChip(selected = section == key, onClick = { onSectionChange(key) }, label = { Text(label, fontSize = 10.sp) }, modifier = Modifier.weight(1f))
            }
        }

        when (section) {
            "profile" -> {
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        if (avatar != null) Image(avatar!!.asImageBitmap(), contentDescription = "Google profile photo", modifier = Modifier.size(78.dp).clip(CircleShape))
                        else Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape, modifier = Modifier.size(78.dp)) {
                            Box(contentAlignment = Alignment.Center) { Text(profile?.optString("name")?.take(1)?.uppercase().orEmpty().ifBlank { "C" }, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
                        }
                        Text(profile?.optString("name").orEmpty().ifBlank { "Welcome to CastDoo" }, color = MaterialTheme.colorScheme.onSurface, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(profile?.optString("email").orEmpty().ifBlank { "Connect your Google account to set up your profile." }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        if (profile == null) {
                            Button(onClick = { signInWithGoogle() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "CONNECTING…" else "CONTINUE WITH GOOGLE") }
                        } else {
                            Text("Google profile photo and verified account are synced with your CastDoo profile.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                            OutlinedButton(onClick = { signInWithGoogle() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("REFRESH GOOGLE PROFILE") }
                            OutlinedButton(onClick = {
                                accessToken = null; profile = null; friends = emptyList(); incoming = emptyList(); outgoing = emptyList(); avatar = null
                                message = "Signed out on this device. Your saved friend list remains on your account."
                            }, modifier = Modifier.fillMaxWidth()) { Text("SIGN OUT ON THIS DEVICE") }
                        }
                    }
                }
                AccountInfoCard("Account security", "CastDoo verifies your Google access token with Google before using your account. Your Google password is never collected. The access token stays in app memory and is not saved as a password or refresh token.")
            }
            "friends" -> {
                if (profile == null) {
                    AccountInfoCard("Sign in first", "Connect your Google account in Profile to add friends and sync your friend list across devices.")
                    Button(onClick = { onSectionChange("profile") }, modifier = Modifier.fillMaxWidth()) { Text("OPEN PROFILE") }
                } else {
                    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("YOUR FRIENDS", fontWeight = FontWeight.Black, fontSize = 12.sp)
                                    Text("Up to 6 friends · ${friends.size}/6 used", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                                }
                                TextButton(onClick = { refreshFriends() }, enabled = !busy) { Text("REFRESH") }
                            }
                            OutlinedTextField(value = friendEmail, onValueChange = { friendEmail = it }, label = { Text("Friend's Google email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Button(
                                onClick = {
                                    val token = accessToken ?: return@Button
                                    scope.launch {
                                        busy = true
                                        try {
                                            val response = castDooFriendsApi(token, "/api/friends/request", "POST", JSONObject().put("email", friendEmail.trim()))
                                            friendEmail = ""
                                            message = response.optString("message", "Friend request sent.")
                                            applySnapshot(castDooFriendsApi(token, "/api/friends"))
                                        } catch (error: Exception) { message = error.message ?: "Could not send friend request." }
                                        finally { busy = false }
                                    }
                                },
                                enabled = !busy && friends.size < 6 && friendEmail.contains("@"),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (busy) "PLEASE WAIT…" else "SEND FRIEND REQUEST") }
                        }
                    }
                    if (incoming.isNotEmpty()) {
                        Text("INCOMING REQUESTS", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black, fontSize = 11.sp)
                        incoming.forEach { request ->
                            AccountActionRow(request.name, request.email, "ACCEPT", "DECLINE",
                                onPrimary = {
                                    scope.launch {
                                        busy = true
                                        try {
                                            castDooFriendsApi(accessToken!!, "/api/friends/requests/accept", "POST", JSONObject().put("requestId", request.requestId))
                                            applySnapshot(castDooFriendsApi(accessToken!!, "/api/friends")); message = "Friend added."
                                        } catch (error: Exception) { message = error.message ?: "Could not accept request." }
                                        finally { busy = false }
                                    }
                                },
                                onSecondary = {
                                    scope.launch {
                                        busy = true
                                        try {
                                            castDooFriendsApi(accessToken!!, "/api/friends/requests/decline", "POST", JSONObject().put("requestId", request.requestId))
                                            applySnapshot(castDooFriendsApi(accessToken!!, "/api/friends"))
                                        } catch (error: Exception) { message = error.message ?: "Could not decline request." }
                                        finally { busy = false }
                                    }
                                }
                            )
                        }
                    }
                    Text("FRIEND LIST", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black, fontSize = 11.sp)
                    if (friends.isEmpty()) AccountInfoCard("No friends yet", "Send a friend request using their Google account email. Accepted friends will appear here.")
                    friends.forEach { friend ->
                        AccountActionRow(friend.name, friend.email, "REMOVE", null,
                            onPrimary = {
                                scope.launch {
                                    busy = true
                                    try {
                                        val encodedUid = java.net.URLEncoder.encode(friend.uid, "UTF-8")
                                        castDooFriendsApi(accessToken!!, "/api/friends/$encodedUid", "DELETE")
                                        applySnapshot(castDooFriendsApi(accessToken!!, "/api/friends")); message = "Friend removed."
                                    } catch (error: Exception) { message = error.message ?: "Could not remove friend." }
                                    finally { busy = false }
                                }
                            },
                            onSecondary = null
                        )
                    }
                    if (outgoing.isNotEmpty()) AccountInfoCard("Pending requests", outgoing.joinToString("\n"))
                }
            }
            "premium" -> SubscriptionPrototype()
            else -> LegalAndSupportPanel()
        }

        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
    }
}

@Composable
private fun AccountInfoCard(title: String, body: String) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun AccountActionRow(title: String, subtitle: String, primaryLabel: String, secondaryLabel: String?, onPrimary: () -> Unit, onSecondary: (() -> Unit)?) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            if (subtitle.isNotBlank()) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPrimary, modifier = Modifier.weight(1f)) { Text(primaryLabel, fontSize = 10.sp) }
                if (secondaryLabel != null && onSecondary != null) OutlinedButton(onClick = onSecondary, modifier = Modifier.weight(1f)) { Text(secondaryLabel, fontSize = 10.sp) }
            }
        }
    }
}

@Composable
private fun SubscriptionPrototype() {
    var selectedPlan by remember { mutableStateOf("Free") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AccountInfoCard("CastDoo plans", "Subscription preview only. No payment is collected and selecting a plan does not unlock or restrict any streaming feature yet.")
        listOf(
            Triple("Free", "₹0 / month", listOf("Standard streaming controls", "Local MP4 recording", "Basic creator tools")),
            Triple("Creator", "₹99 / month", listOf("1080p60 workflow presets", "Priority creator support", "Expanded scene presets")),
            Triple("Pro", "₹199 / month", listOf("4K workflow presets", "Advanced creator analytics", "Multi-destination roadmap access"))
        ).forEach { plan ->
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(if (selectedPlan == plan.first) 2.dp else 1.dp, if (selectedPlan == plan.first) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().clickable { selectedPlan = plan.first }
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(plan.first, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(plan.second, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black, fontSize = 13.sp)
                        }
                        if (selectedPlan == plan.first) Text("SELECTED", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black, fontSize = 9.sp)
                    }
                    plan.third.forEach { feature -> Text("•  $feature", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }
                    OutlinedButton(onClick = { selectedPlan = plan.first }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (selectedPlan == plan.first) "CURRENT PREVIEW" else "PREVIEW THIS PLAN")
                    }
                }
            }
        }
        Text("Prices are illustrative and can be changed before launch. Billing, receipts, renewals and cancellation are not connected.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    }
}

@Composable
private fun LegalAndSupportPanel() {
    var page by remember { mutableStateOf("privacy") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = page == "privacy", onClick = { page = "privacy" }, label = { Text("Privacy") })
            FilterChip(selected = page == "terms", onClick = { page = "terms" }, label = { Text("Terms") })
            FilterChip(selected = page == "support", onClick = { page = "support" }, label = { Text("Support") })
        }
        when (page) {
            "privacy" -> AccountInfoCard("Privacy policy", "CastDoo stores your streaming settings and YouTube channel connection on this device. When you connect a Google profile, CastDoo receives your verified account ID, email address, display name and profile photo URL. Friend requests and your friend list are stored by CastDoo so they can sync across devices. Google passwords and refresh tokens are not collected. The Google access token is held in app memory for the active session and sent to Google's user information service through CastDoo's account API for verification.\n\nYou can sign out on this device. To request account data deletion, contact CastDoo support from the Support tab. This in-app policy is a product summary and should be reviewed against the final deployed services before public release.")
            "terms" -> AccountInfoCard("Terms of use", "Use CastDoo only with streaming destinations and content you are authorized to broadcast. You are responsible for your stream keys, account access, music, video and other content. Do not harass other users, send unwanted friend requests, impersonate another person, or attempt to bypass the six-friend limit.\n\nSubscription plans shown here are previews only. No payment, renewal, refund or paid entitlement is active in this prototype. Features may change as CastDoo develops. Use of third-party services such as Google and YouTube is also subject to their own terms.")
            else -> AccountInfoCard("Support", "For streaming or account issues, open More → Problem Reporter and submit the diagnostic report from inside CastDoo. Do not include stream keys, passwords or private access tokens in a report. For privacy or account deletion requests, include the Google account email used with CastDoo. A dedicated support inbox will be added before public launch.")
        }
    }
}
