package app.streamforge.ui

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal data class VoiceEngineState(
    val connected: Boolean = false,
    val mediaConnected: Boolean = false,
    val micEnabled: Boolean = true,
    val speakerEnabled: Boolean = true,
    val speaking: Set<String> = emptySet(),
    val status: String = "Connecting to voice…"
)

/**
 * Party Voice v2.
 *
 * One WebRTC audio device module owns microphone capture for the whole room.
 * The backend only handles presence and SDP/ICE signaling. Each peer pair has
 * exactly one offerer (stable memberId ordering) to avoid offer glare.
 */
internal class PartyVoiceEngine(
    context: Context,
    private val endpoint: String,
    private val roomId: String,
    private val memberId: String,
    private val token: String,
    private val displayName: String,
    private val onState: (VoiceEngineState) -> Unit
) {
    private val app = context.applicationContext
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val peers = ConcurrentHashMap<String, PeerConnection>()
    private val remoteTracks = ConcurrentHashMap<String, AudioTrack>()
    private val pendingIce = ConcurrentHashMap<String, ConcurrentLinkedQueue<IceCandidate>>()
    private val remoteSdpReady = ConcurrentHashMap.newKeySet<String>()
    private val peerNames = ConcurrentHashMap<String, String>()
    private val speaking = ConcurrentHashMap.newKeySet<String>()
    private val client = sharedClient

    @Volatile private var closed = false
    @Volatile private var signalingConnected = false
    @Volatile private var micEnabled = true
    @Volatile private var speakerEnabled = true
    @Volatile private var mediaConnected = false
    @Volatile private var socket: WebSocket? = null
    @Volatile private var factory: PeerConnectionFactory? = null
    @Volatile private var audioModule: JavaAudioDeviceModule? = null
    @Volatile private var audioSource: org.webrtc.AudioSource? = null
    @Volatile private var localTrack: AudioTrack? = null
    @Volatile private var iceServers: List<PeerConnection.IceServer> = emptyList()

    private val lock = Any()

    fun connect() {
        thread(name = "sf-party-voice-start") {
            synchronized(lock) {
                if (closed || factory != null) return@synchronized
                try {
                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions()
                    )
                    iceServers = fetchIceServers()
                    audioModule = JavaAudioDeviceModule.builder(app)
                        .setUseHardwareAcousticEchoCanceler(true)
                        .setUseHardwareNoiseSuppressor(true)
                        .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                            override fun onWebRtcAudioRecordStart() {
                                if (!closed) publish("Microphone active · voice ready")
                            }
                            override fun onWebRtcAudioRecordStop() {
                                if (!closed) publish("Microphone capture stopped")
                            }
                        })
                        .createAudioDeviceModule()

                    factory = PeerConnectionFactory.builder()
                        .setAudioDeviceModule(audioModule)
                        .createPeerConnectionFactory()

                    val audioConstraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                        mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                        mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                    }
                    audioSource = factory?.createAudioSource(audioConstraints)
                    localTrack = audioSource?.let { factory?.createAudioTrack("sf-party-mic", it) }
                    localTrack?.setEnabled(micEnabled)
                    if (factory == null || audioModule == null || localTrack == null) {
                        throw IllegalStateException("WebRTC audio engine could not be created")
                    }
                    applyAudioRoute(speakerEnabled)
                    openSignalingSocket()
                } catch (error: Throwable) {
                    Log.e(TAG, "Voice initialization failed", error)
                    publish("Voice setup failed: " + (error.message ?: "audio initialization error"))
                    releaseMedia()
                }
            }
        }
    }

    private fun openSignalingSocket() {
        val wsBase = endpoint.trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        val url = wsBase + "/api/voice/signaling?roomId=" + roomId + "&memberId=" + memberId
        socket = client.newWebSocket(
            Request.Builder().url(url).header("Authorization", "Bearer " + token).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (closed) {
                        webSocket.close(1000, "Voice session closed")
                        return
                    }
                    signalingConnected = true
                    publish("Connected to voice server · finding participants")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (closed) return
                    val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                    handleSignal(message)
                }

                override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                    signalingConnected = false
                    Log.e(TAG, "Signaling socket failed: HTTP " + (response?.code ?: -1), error)
                    if (!closed) publish("Voice server connection failed: " + (error.message ?: "network error"))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    signalingConnected = false
                    if (!closed) publish("Voice server disconnected")
                }
            }
        )
    }

    fun setMicEnabled(enabled: Boolean) {
        micEnabled = enabled
        localTrack?.setEnabled(enabled)
        send(JSONObject().put("type", "mute").put("active", !enabled))
        if (!closed) publish(if (enabled) "Microphone on" else "Microphone muted")
    }

    fun setSpeakerEnabled(enabled: Boolean) {
        speakerEnabled = enabled
        remoteTracks.values.forEach { track -> runCatching { track.setEnabled(enabled) } }
        applyAudioRoute(enabled)
        if (!closed) publish(if (enabled) "Speaker on" else "Speaker off")
    }

    private fun handleSignal(message: JSONObject) {
        when (message.optString("type")) {
            "ready" -> {
                val list = message.optJSONArray("peers") ?: JSONArray()
                for (index in 0 until list.length()) {
                    val peer = list.optJSONObject(index) ?: continue
                    val id = peer.optString("memberId")
                    if (id.isNotBlank() && id != memberId) {
                        peerNames[id] = peer.optString("displayName", id)
                        ensurePeer(id, shouldOffer(id))
                    }
                }
                publish(if (list.length() == 0) "Room ready · waiting for friends" else "Connecting party audio…")
            }
            "peer-joined" -> {
                val id = message.optString("memberId")
                if (id.isNotBlank() && id != memberId) {
                    peerNames[id] = message.optString("displayName", id)
                    ensurePeer(id, shouldOffer(id))
                }
            }
            "peer-left" -> removePeer(message.optString("memberId"))
            "offer" -> receiveOffer(message)
            "answer" -> receiveAnswer(message)
            "ice" -> receiveIce(message)
            "speaking" -> {
                val id = message.optString("memberId")
                val name = message.optString("displayName", id)
                if (message.optBoolean("active")) speaking.add(name) else speaking.remove(name)
                publish("Party voice active")
            }
            "mute" -> {
                val id = message.optString("memberId")
                speaking.remove(message.optString("displayName", id))
                publish("Party voice active")
            }
        }
    }

    private fun shouldOffer(peerId: String): Boolean = memberId < peerId

    private fun ensurePeer(peerId: String, offer: Boolean): PeerConnection? {
        if (closed || peerId.isBlank() || peerId == memberId) return null
        peers[peerId]?.let { return it }
        synchronized(lock) {
            peers[peerId]?.let { return it }
            val currentFactory = factory ?: return null
            if (closed) return null
            val config = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                iceTransportsType = PeerConnection.IceTransportsType.ALL
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            }
            val connection = currentFactory.createPeerConnection(config, observer(peerId))
            if (connection == null) {
                publish("Could not create audio connection")
                return null
            }
            peers[peerId] = connection
            val track = localTrack
            if (track == null || connection.addTrack(track, listOf("streamforge-party-audio")) == null) {
                Log.e(TAG, "Local microphone track attach failed for " + peerId)
                publish("Microphone track could not be attached")
            }
            if (offer) createOffer(peerId, connection)
            return connection
        }
    }

    private fun observer(peerId: String) = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {
            Log.d(TAG, "peer=" + peerId + " signaling=" + state)
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.i(TAG, "peer=" + peerId + " ICE=" + state)
            updateMediaState()
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> publish("Party audio connected")
                PeerConnection.IceConnectionState.FAILED -> publish("Audio route failed · retrying connection")
                PeerConnection.IceConnectionState.DISCONNECTED -> publish("Audio reconnecting…")
                else -> if (!mediaConnected) publish("Connecting party audio…")
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            Log.d(TAG, "peer=" + peerId + " receiving=" + receiving)
        }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            Log.d(TAG, "peer=" + peerId + " gathering=" + state)
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            send(JSONObject().put("type", "ice").put("to", peerId).put(
                "candidate",
                JSONObject().put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex)
                    .put("candidate", candidate.sdp)
            ))
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: org.webrtc.MediaStream?) {}
        override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out org.webrtc.MediaStream>?) {
            attachRemoteTrack(peerId, receiver?.track() as? AudioTrack)
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            attachRemoteTrack(peerId, transceiver?.receiver?.track() as? AudioTrack)
        }
    }

    private fun attachRemoteTrack(peerId: String, track: AudioTrack?) {
        if (track == null || closed) return
        track.setEnabled(speakerEnabled)
        remoteTracks[peerId] = track
        Log.i(TAG, "Remote audio track attached for " + peerId)
        publish("Remote microphone connected")
    }

    private fun createOffer(peerId: String, pc: PeerConnection) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        pc.createOffer(object : SdpObserverAdapter("create offer") { 
            override fun onCreateSuccess(description: SessionDescription?) {
                if (description == null || closed) return
                pc.setLocalDescription(object : SdpObserverAdapter("set local offer") {
                    override fun onSetSuccess() {
                        sendDescription("offer", peerId, description)
                    }
                }, description)
            }
        }, constraints)
    }

    private fun receiveOffer(message: JSONObject) {
        val peerId = message.optString("from")
        val sdp = message.optJSONObject("description")?.optString("sdp").orEmpty()
        if (peerId.isBlank() || sdp.isBlank()) return
        val pc = ensurePeer(peerId, false) ?: return
        pc.setRemoteDescription(object : SdpObserverAdapter("set remote offer") {
            override fun onSetSuccess() {
                markRemoteSdpReady(peerId, pc)
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                }
                pc.createAnswer(object : SdpObserverAdapter("create answer") {
                    override fun onCreateSuccess(description: SessionDescription?) {
                        if (description == null || closed) return
                        pc.setLocalDescription(object : SdpObserverAdapter("set local answer") {
                            override fun onSetSuccess() {
                                sendDescription("answer", peerId, description)
                            }
                        }, description)
                    }
                }, constraints)
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun receiveAnswer(message: JSONObject) {
        val peerId = message.optString("from")
        val sdp = message.optJSONObject("description")?.optString("sdp").orEmpty()
        val pc = peers[peerId] ?: return
        if (sdp.isBlank()) return
        pc.setRemoteDescription(object : SdpObserverAdapter("set remote answer") {
            override fun onSetSuccess() {
                markRemoteSdpReady(peerId, pc)
            }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    private fun receiveIce(message: JSONObject) {
        val peerId = message.optString("from")
        val json = message.optJSONObject("candidate") ?: return
        val candidate = IceCandidate(
            json.optString("sdpMid"),
            json.optInt("sdpMLineIndex"),
            json.optString("candidate")
        )
        val pc = peers[peerId]
        if (pc != null && remoteSdpReady.contains(peerId)) {
            if (!pc.addIceCandidate(candidate)) Log.w(TAG, "ICE candidate rejected for " + peerId)
        } else {
            pendingIce.computeIfAbsent(peerId) { ConcurrentLinkedQueue() }.add(candidate)
        }
    }

    private fun markRemoteSdpReady(peerId: String, pc: PeerConnection) {
        remoteSdpReady.add(peerId)
        val queued = pendingIce.remove(peerId) ?: return
        while (true) {
            val candidate = queued.poll() ?: break
            if (!pc.addIceCandidate(candidate)) Log.w(TAG, "Queued ICE candidate rejected for " + peerId)
        }
    }

    private fun sendDescription(type: String, peerId: String, description: SessionDescription) {
        send(JSONObject().put("type", type).put("to", peerId).put(
            "description",
            JSONObject().put("type", type).put("sdp", description.description)
        ))
    }

    private fun updateMediaState() {
        mediaConnected = peers.values.any { pc ->
            pc.iceConnectionState() == PeerConnection.IceConnectionState.CONNECTED ||
                pc.iceConnectionState() == PeerConnection.IceConnectionState.COMPLETED
        }
    }

    private fun removePeer(peerId: String) {
        if (peerId.isBlank()) return
        synchronized(lock) {
            peers.remove(peerId)?.let { runCatching { it.close() } }
            remoteTracks.remove(peerId)
            pendingIce.remove(peerId)
            remoteSdpReady.remove(peerId)
            peerNames.remove(peerId)?.let { speaking.remove(it) }
            updateMediaState()
        }
        publish(if (peers.isEmpty()) "Waiting for party members" else "A participant left")
    }

    private fun fetchIceServers(): List<PeerConnection.IceServer> {
        val fallback = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(endpoint.trimEnd('/') + "/api/voice/ice-servers").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 7000
            connection.readTimeout = 7000
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("ICE service returned " + code)
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val array = JSONObject(body).optJSONArray("iceServers")
                ?: throw IllegalStateException("ICE server list missing")
            val result = mutableListOf<PeerConnection.IceServer>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val rawUrls = item.opt("urls")
                val urls = when (rawUrls) {
                    is JSONArray -> (0 until rawUrls.length()).mapNotNull { rawUrls.optString(it).takeIf(String::isNotBlank) }
                    is String -> listOf(rawUrls).filter(String::isNotBlank)
                    else -> emptyList()
                }
                if (urls.isEmpty()) continue
                val builder = PeerConnection.IceServer.builder(urls)
                item.optString("username").takeIf(String::isNotBlank)?.let(builder::setUsername)
                item.optString("credential").takeIf(String::isNotBlank)?.let(builder::setPassword)
                result.add(builder.createIceServer())
            }
            if (result.isEmpty()) throw IllegalStateException("No usable ICE servers")
            result
        } catch (error: Throwable) {
            Log.w(TAG, "TURN configuration unavailable; using STUN fallback", error)
            publish("Using direct voice route · relay unavailable")
            fallback
        } finally {
            connection?.disconnect()
        }
    }

    private fun applyAudioRoute(useSpeaker: Boolean) {
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val wanted = if (useSpeaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                val device = audioManager.availableCommunicationDevices.firstOrNull { it.type == wanted }
                    ?: audioManager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (device != null) audioManager.setCommunicationDevice(device)
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = useSpeaker
            }
        }.onFailure { Log.w(TAG, "Audio route selection failed", it) }
    }

    private fun send(message: JSONObject) {
        if (closed) return
        val current = socket ?: return
        if (current.send(message.toString()).not()) {
            Log.w(TAG, "Signaling socket rejected message: " + message.optString("type"))
        }
    }

    private fun publish(status: String) {
        if (closed) return
        onState(
            VoiceEngineState(
                connected = signalingConnected,
                mediaConnected = mediaConnected,
                micEnabled = micEnabled,
                speakerEnabled = speakerEnabled,
                speaking = speaking.toSet(),
                status = status
            )
        )
    }

    private fun releaseMedia() {
        runCatching { localTrack?.dispose() }
        runCatching { audioSource?.dispose() }
        runCatching { factory?.dispose() }
        runCatching { audioModule?.release() }
        localTrack = null
        audioSource = null
        factory = null
        audioModule = null
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            signalingConnected = false
            mediaConnected = false
            runCatching { socket?.close(1000, "Leaving voice room") }
            socket = null
            peers.values.forEach { pc -> runCatching { pc.close() } }
            peers.clear()
            remoteTracks.clear()
            pendingIce.clear()
            remoteSdpReady.clear()
            speaking.clear()
            releaseMedia()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { audioManager.clearCommunicationDevice() }
            } else {
                @Suppress("DEPRECATION")
                runCatching { audioManager.isSpeakerphoneOn = false }
            }
            runCatching { audioManager.mode = AudioManager.MODE_NORMAL }
        }
    }

    private open class SdpObserverAdapter(private val operation: String) : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {
            Log.e(TAG, operation + " failed: " + error)
        }
        override fun onSetFailure(error: String?) {
            Log.e(TAG, operation + " failed: " + error)
        }
    }

    private companion object {
        const val TAG = "StreamForgeVoice"
        val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
