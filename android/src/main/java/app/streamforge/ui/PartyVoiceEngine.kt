package app.streamforge.ui

import android.content.Context
import android.media.AudioManager
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
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
import org.webrtc.JavaAudioDeviceModule
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal data class VoiceEngineState(
    val connected: Boolean = false,
    val micEnabled: Boolean = true,
    val speakerEnabled: Boolean = true,
    val speaking: Set<String> = emptySet(),
    val status: String = "Connecting to voice…"
)

/**
 * Ephemeral mesh voice for rooms of up to six participants.
 * Media travels peer-to-peer; the backend only relays SDP/ICE and presence.
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
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val peers = ConcurrentHashMap<String, PeerConnection>()
    private val remoteTracks = ConcurrentHashMap<String, AudioTrack>()
    private val speaking = ConcurrentHashMap.newKeySet<String>()
    private var factory: PeerConnectionFactory? = null
    private var audioSource: AudioSource? = null
    private var localTrack: AudioTrack? = null
    private var socket: WebSocket? = null
    @Volatile private var micEnabled = true
    @Volatile private var speakerEnabled = true
    @Volatile private var closed = false

    fun connect() {
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions()
            )
            val audioModule = JavaAudioDeviceModule.builder(appContext)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()
            factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioModule)
                .createPeerConnectionFactory()
            audioSource = factory?.createAudioSource(MediaConstraints())
            localTrack = factory?.createAudioTrack("streamforge-mic", audioSource).also { it?.setEnabled(true) }

            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            run { audioManager.isSpeakerphoneOn = true }

            val wsBase = endpoint.trimEnd('/')
                .replaceFirst("https://", "wss://")
                .replaceFirst("http://", "ws://")
            val url = "$wsBase/api/voice/signaling?roomId=$roomId&memberId=$memberId&token=$token"
            socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    publish("Voice connected · joining peers")
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleSignal(runCatching { JSONObject(text) }.getOrNull() ?: return)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    publish("Voice connection lost: ${t.message ?: "network error"}")
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!closed) publish("Voice disconnected")
                }
            })
        } catch (t: Throwable) {
            publish("Voice setup failed: ${t.message ?: "audio initialization error"}")
        }
    }

    fun setMicEnabled(enabled: Boolean) {
        micEnabled = enabled
        localTrack?.setEnabled(enabled)
        send(JSONObject().put("type", "mute").put("active", !enabled))
        publish(if (enabled) "Microphone on" else "Microphone muted")
    }

    fun setSpeakerEnabled(enabled: Boolean) {
        speakerEnabled = enabled
        remoteTracks.values.forEach { it.setEnabled(enabled) }
        @Suppress("DEPRECATION")
        run { audioManager.isSpeakerphoneOn = enabled }
        publish(if (enabled) "Speaker on" else "Speaker off")
    }

    private fun handleSignal(message: JSONObject) {
        when (message.optString("type")) {
            "ready" -> {
                val list = message.optJSONArray("peers") ?: JSONArray()
                for (i in 0 until list.length()) {
                    val peer = list.optJSONObject(i) ?: continue
                    val id = peer.optString("memberId")
                    if (id.isNotBlank()) ensurePeer(id, initiate = memberId < id)
                }
                publish("Live voice connected")
            }
            "peer-joined" -> {
                val id = message.optString("memberId")
                if (id.isNotBlank()) ensurePeer(id, initiate = memberId < id)
            }
            "peer-left" -> {
                val id = message.optString("memberId")
                peers.remove(id)?.close()
                remoteTracks.remove(id)
                speaking.remove(id)
                publish("Participant left")
            }
            "offer" -> {
                val id = message.optString("from")
                val desc = message.optJSONObject("description") ?: return
                val pc = ensurePeer(id, initiate = false) ?: return
                val remote = SessionDescription(SessionDescription.Type.OFFER, desc.optString("sdp"))
                pc.setRemoteDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        pc.createAnswer(object : SdpObserverAdapter() {
                            override fun onCreateSuccess(answer: SessionDescription) {
                                pc.setLocalDescription(object : SdpObserverAdapter() {
                                    override fun onSetSuccess() {
                                        send(JSONObject().put("type", "answer").put("to", id)
                                            .put("description", JSONObject().put("type", "answer").put("sdp", answer.description)))
                                    }
                                }, answer)
                            }
                        }, MediaConstraints())
                    }
                }, remote)
            }
            "answer" -> {
                val id = message.optString("from")
                val desc = message.optJSONObject("description") ?: return
                peers[id]?.setRemoteDescription(SdpObserverAdapter(),
                    SessionDescription(SessionDescription.Type.ANSWER, desc.optString("sdp")))
            }
            "ice" -> {
                val id = message.optString("from")
                val c = message.optJSONObject("candidate") ?: return
                peers[id]?.addIceCandidate(IceCandidate(
                    c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.optString("candidate")
                ))
            }
            "speaking" -> {
                val id = message.optString("memberId")
                val name = message.optString("displayName", id)
                if (message.optBoolean("active")) speaking.add(name) else speaking.remove(name)
                publish("Live voice connected")
            }
            "mute" -> {
                val name = message.optString("displayName", message.optString("memberId"))
                if (message.optBoolean("active")) speaking.remove(name)
            }
        }
    }

    private fun ensurePeer(id: String, initiate: Boolean): PeerConnection? {
        peers[id]?.let { return it }
        val f = factory ?: return null
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = f.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                if (state == PeerConnection.IceConnectionState.FAILED ||
                    state == PeerConnection.IceConnectionState.DISCONNECTED) publish("Reconnecting voice…")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                send(JSONObject().put("type", "ice").put("to", id).put("candidate",
                    JSONObject().put("sdpMid", candidate.sdpMid)
                        .put("sdpMLineIndex", candidate.sdpMLineIndex)
                        .put("candidate", candidate.sdp)))
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: org.webrtc.MediaStream?) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out org.webrtc.MediaStream>?) {
                val track = receiver?.track() as? AudioTrack
                if (track != null) {
                    track.setEnabled(speakerEnabled)
                    remoteTracks[id] = track
                }
            }
            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track() as? AudioTrack
                if (track != null) {
                    track.setEnabled(speakerEnabled)
                    remoteTracks[id] = track
                }
            }
        }) ?: return null
        peers[id] = pc
        localTrack?.let { pc.addTrack(it, listOf("streamforge-party-audio")) }
        if (initiate) {
            pc.createOffer(object : SdpObserverAdapter() {
                override fun onCreateSuccess(offer: SessionDescription) {
                    pc.setLocalDescription(object : SdpObserverAdapter() {
                        override fun onSetSuccess() {
                            send(JSONObject().put("type", "offer").put("to", id)
                                .put("description", JSONObject().put("type", "offer").put("sdp", offer.description)))
                        }
                    }, offer)
                }
            }, MediaConstraints())
        }
        return pc
    }

    private fun send(message: JSONObject) {
        socket?.send(message.toString())
    }

    private fun publish(status: String) {
        onState(VoiceEngineState(
            connected = socket != null && !closed,
            micEnabled = micEnabled,
            speakerEnabled = speakerEnabled,
            speaking = speaking.toSet(),
            status = status
        ))
    }

    fun close() {
        closed = true
        socket?.close(1000, "Leaving voice room")
        socket = null
        peers.values.forEach { it.close() }
        peers.clear()
        remoteTracks.clear()
        localTrack?.dispose()
        audioSource?.dispose()
        factory?.dispose()
        localTrack = null
        audioSource = null
        factory = null
        audioManager.mode = AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION")
        run { audioManager.isSpeakerphoneOn = false }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private abstract class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}
