package app.streamforge.ui

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.EglBase
import org.webrtc.MediaStreamTrack
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.VideoSink
import org.webrtc.VideoFrame
import java.util.concurrent.atomic.AtomicLong
import android.content.Intent
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
import kotlin.math.sqrt
import kotlin.concurrent.thread

internal data class VoiceEngineState(
    val connected: Boolean = false,
    val mediaConnected: Boolean = false,
    val micEnabled: Boolean = true,
    val speakerEnabled: Boolean = true,
    val speaking: Set<String> = emptySet(),
    val mutedMembers: Set<String> = emptySet(),
    val localSpeaking: Boolean = false,
    val localLevel: Float = 0f,
    val micCapturing: Boolean = false,
    val remoteScreenTrack: VideoTrack? = null,
    val localScreenTrack: VideoTrack? = null,
    val remoteScreenOwner: String? = null,
    val remoteScreenActive: Boolean = false,
    val screenSharing: Boolean = false,
    val screenShareStatus: String = "",
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
    private val remoteVideoTracks = ConcurrentHashMap<String, VideoTrack>()
    private val remoteVideoFrameSinks = ConcurrentHashMap<String, VideoSink>()
    private val videoTransceivers = ConcurrentHashMap<String, RtpTransceiver>()
    private val pendingIce = ConcurrentHashMap<String, ConcurrentLinkedQueue<IceCandidate>>()
    private val remoteSdpReady = ConcurrentHashMap.newKeySet<String>()
    private val peerNames = ConcurrentHashMap<String, String>()
    private val speaking = ConcurrentHashMap.newKeySet<String>()
    private val mutedMembers = ConcurrentHashMap.newKeySet<String>()
    private val screenSharingMembers = ConcurrentHashMap.newKeySet<String>()
    private val locallyMutedPeers = ConcurrentHashMap.newKeySet<String>()
    private val remoteVolumes = ConcurrentHashMap<String, Double>()
    private val client = sharedClient

    @Volatile private var closed = false
    @Volatile private var signalingConnected = false
    @Volatile private var micEnabled = true
    @Volatile private var speakerEnabled = true
    @Volatile private var mediaConnected = false
    @Volatile private var micCapturing = false
    @Volatile private var localSpeaking = false
    @Volatile private var localLevel = 0f
    @Volatile private var currentStatus = "Connecting to voice…"
    @Volatile private var lastMicUiPublishAt = 0L
    @Volatile private var lastSpeakingSignalAt = 0L
    @Volatile private var socket: WebSocket? = null
    @Volatile private var reconnecting = false
    @Volatile private var reconnectAttempts = 0
    @Volatile private var factory: PeerConnectionFactory? = null
    @Volatile private var audioModule: JavaAudioDeviceModule? = null
    @Volatile private var eglBase: EglBase? = null
    @Volatile private var screenCapturer: ScreenCapturerAndroid? = null
    @Volatile private var screenTextureHelper: SurfaceTextureHelper? = null
    @Volatile private var screenVideoSource: VideoSource? = null
    @Volatile private var screenVideoTrack: VideoTrack? = null
    @Volatile private var localScreenFrameSink: VideoSink? = null
    @Volatile private var screenSharing = false
    @Volatile private var screenShareStatus = ""
    @Volatile private var audioSource: org.webrtc.AudioSource? = null
    @Volatile private var localTrack: AudioTrack? = null
    @Volatile private var localMicMonitor: PeerConnection? = null
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
                                micCapturing = true
                                if (!closed) publish(if (peers.isEmpty()) "Microphone live · invite friends" else "Microphone active · voice ready")
                            }
                            override fun onWebRtcAudioRecordStop() {
                                micCapturing = false
                                localSpeaking = false
                                localLevel = 0f
                                speaking.remove(displayName)
                                if (!closed) publish("Microphone capture stopped · reconnecting")
                            }
                        })
                        .setSamplesReadyCallback(object : JavaAudioDeviceModule.SamplesReadyCallback {
                            override fun onWebRtcAudioRecordSamplesReady(samples: JavaAudioDeviceModule.AudioSamples) {
                                updateLocalAudioLevel(samples.data)
                            }
                        })
                        .createAudioDeviceModule()

                    eglBase = EglBase.create()
                    factory = PeerConnectionFactory.builder()
                        .setAudioDeviceModule(audioModule)
                        .setVideoEncoderFactory(org.webrtc.DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
                        .setVideoDecoderFactory(org.webrtc.DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
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
                    startLocalMicMonitor()
                    openSignalingSocket()
                    // A user may confirm Android's capture dialog before WebRTC finishes
                    // booting. Drain that queued request only after the factory/EGL are ready.
                    startPendingScreenShare()
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
                    reconnectAttempts = 0
                    reconnecting = false
                    publish("Voice room ready · microphone armed")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (closed) return
                    val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                    handleSignal(message)
                }

                override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                    signalingConnected = false
                    val statusCode = response?.code ?: -1
                    Log.e(TAG, "Signaling socket failed: HTTP " + statusCode)
                    if (!closed) {
                        if (statusCode == 401 || statusCode == 404) {
                            publish("This voice room has ended. Create a new room.")
                        } else {
                            publish("Connection interrupted · reconnecting…")
                            scheduleSignalingReconnect()
                        }
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    signalingConnected = false
                    if (!closed && code != 1000 && code != 1008 && code != 4001) {
                        publish("Connection interrupted · reconnecting…")
                        scheduleSignalingReconnect()
                    }
                }
            }
        )
    }

    private fun scheduleSignalingReconnect() {
        synchronized(lock) {
            if (closed || reconnecting) return
            reconnecting = true
        }
        thread(name = "sf-party-voice-reconnect") {
            val delays = longArrayOf(1000L, 2000L, 4000L, 8000L, 15000L)
            val waitMs = delays[reconnectAttempts.coerceIn(0, delays.lastIndex)]
            reconnectAttempts = (reconnectAttempts + 1).coerceAtMost(delays.lastIndex)
            try {
                Thread.sleep(waitMs)
            } catch (_: InterruptedException) {
                synchronized(lock) { reconnecting = false }
                return@thread
            }
            synchronized(lock) {
                if (closed) {
                    reconnecting = false
                    return@thread
                }
                reconnecting = false
                openSignalingSocket()
            }
        }
    }

    fun eglContext(): org.webrtc.EglBase.Context? = eglBase?.eglBaseContext

    private data class PendingScreenShare(val permissionData: Intent, val width: Int, val height: Int, val fps: Int)

    @Volatile private var pendingScreenShare: PendingScreenShare? = null

    fun startScreenShare(permissionData: Intent, width: Int, height: Int, fps: Int = 30) {
        synchronized(lock) {
            if (closed || screenSharing) return
            // Preserve the one-time MediaProjection grant while WebRTC initializes.
            pendingScreenShare = PendingScreenShare(permissionData, width, height, fps)
            screenShareStatus = "Preparing screen capture…"
            publish("Preparing screen capture…")
        }
        startPendingScreenShare()
    }

    private fun startPendingScreenShare() {
        thread(name = "sf-party-screen-start") {
            synchronized(lock) {
                if (closed || screenSharing) return@thread
                val request = pendingScreenShare ?: return@thread
                val currentFactory = factory
                val currentEgl = eglBase
                if (currentFactory == null || currentEgl == null) {
                    screenShareStatus = "Waiting for voice engine…"
                    publish("Waiting for voice engine…")
                    return@thread
                }
                pendingScreenShare = null
                var source: VideoSource? = null
                var helper: SurfaceTextureHelper? = null
                var capturer: ScreenCapturerAndroid? = null
                var track: VideoTrack? = null
                try {
                    val transceivers = videoTransceivers.values.toList()
                    Log.i(TAG, "Screen share starting: peers=${peers.size} videoTransceivers=${transceivers.size} requested=${request.width}x${request.height}@${request.fps}")
                    val createdSource = currentFactory.createVideoSource(true)
                    source = createdSource
                    val createdHelper = SurfaceTextureHelper.create("sf-party-screen-capture", currentEgl.eglBaseContext)
                    helper = createdHelper
                    val createdCapturer = ScreenCapturerAndroid(request.permissionData, object : android.media.projection.MediaProjection.Callback() {
                        override fun onStop() { thread(name = "sf-party-screen-stop") { stopScreenShare() } }
                    })
                    capturer = createdCapturer
                    createdCapturer.initialize(createdHelper, app, createdSource.capturerObserver)
                    val createdTrack = currentFactory.createVideoTrack("sf-party-screen", createdSource)
                    track = createdTrack
                    createdTrack.setEnabled(true)
                    var attached = 0
                    transceivers.forEach { transceiver ->
                        val ok = transceiver.sender.setTrack(createdTrack, false)
                        Log.i(TAG, "Screen sender attach peerTransceiver=${transceiver.mid ?: "pending"} result=${ok} direction=${transceiver.direction}")
                        if (ok) attached++
                    }
                    if (transceivers.isNotEmpty() && attached != transceivers.size) {
                        throw IllegalStateException("Could not attach screen video to every participant")
                    }

                    screenVideoSource = createdSource
                    screenTextureHelper = createdHelper
                    screenCapturer = createdCapturer
                    screenVideoTrack = createdTrack
                    val lastFrameLogAt = AtomicLong(0L)
                    val frameCount = AtomicLong(0L)
                    val frameSink = object : VideoSink {
                        override fun onFrame(frame: VideoFrame) {
                            val count = frameCount.incrementAndGet()
                            if (count == 1L) {
                                screenShareStatus = "Live · ${frame.buffer.width}×${frame.buffer.height}"
                                publish("Screen capture frames received")
                            }
                            val now = SystemClock.elapsedRealtime()
                            val previous = lastFrameLogAt.get()
                            if (previous == 0L || now - previous >= 5000L) {
                                if (lastFrameLogAt.compareAndSet(previous, now)) {
                                    Log.i(TAG, "LOCAL_SCREEN_FRAME count=${count} ${frame.buffer.width}x${frame.buffer.height} rotation=${frame.rotation}")
                                }
                            }
                        }
                    }
                    localScreenFrameSink = frameSink
                    createdTrack.addSink(frameSink)

                    // Scale the longest source edge to the requested quality while preserving
                    // portrait/landscape ratio. Never clamp width and height independently.
                    val maxEdge = request.width.coerceAtLeast(request.height).coerceIn(2, 1920)
                    val sourceLongEdge = request.width.coerceAtLeast(request.height).coerceAtLeast(2)
                    val scale = maxEdge.toFloat() / sourceLongEdge.toFloat()
                    val captureWidth = ((request.width * scale).toInt().coerceAtLeast(2) / 2) * 2
                    val captureHeight = ((request.height * scale).toInt().coerceAtLeast(2) / 2) * 2
                    screenSharing = true
                    screenShareStatus = "Starting Android screen capture…"
                    createdCapturer.startCapture(captureWidth, captureHeight, request.fps.coerceIn(15, 30))
                    Log.i(TAG, "ScreenCapturerAndroid startCapture returned: ${captureWidth}x${captureHeight}@${request.fps}; attachedSenders=${attached}")
                    send(JSONObject().put("type", "screen-share").put("active", true))
                    videoTransceivers.keys.forEach { peerId -> renegotiateVideoPeer(peerId) }
                    publish("Screen share starting · waiting for first frame")

                    // Keep the capturer untouched after start. Reconfiguring a live
                    // MediaProjection VirtualDisplay on a watchdog timer caused regressions.
                    thread(name = "sf-party-screen-watchdog") {
                        Thread.sleep(6000L)
                        synchronized(lock) {
                            if (screenSharing && screenCapturer === createdCapturer && frameCount.get() == 0L) {
                                screenShareStatus = "Capture started, but Android has not delivered video frames. Stop and retry screen share."
                                Log.e(TAG, "SCREEN_CAPTURE_NO_FRAMES after 6 seconds")
                                publish("Screen capture produced no frames")
                            }
                        }
                    }
                } catch (error: Throwable) {
                    Log.e(TAG, "Screen share start failed", error)
                    runCatching { capturer?.stopCapture() }
                    runCatching { capturer?.dispose() }
                    runCatching { track?.dispose() }
                    runCatching { source?.dispose() }
                    runCatching { helper?.dispose() }
                    stopScreenShareInternal()
                    screenShareStatus = "Screen share failed: ${error.message ?: "unknown error"}"
                    publish(currentStatus)
                }
            }
        }
    }

    fun stopScreenShare() {
        synchronized(lock) {
            pendingScreenShare = null
            stopScreenShareInternal()
        }
        publish(currentStatus)
    }

    private fun stopScreenShareInternal() {
        pendingScreenShare = null
        val wasSharing = screenSharing
        runCatching { videoTransceivers.values.forEach { it.sender.setTrack(null, false) } }
        if (wasSharing) send(JSONObject().put("type", "screen-share").put("active", false))
        val localTrack = screenVideoTrack
        val localSink = localScreenFrameSink
        if (localTrack != null && localSink != null) runCatching { localTrack.removeSink(localSink) }
        localScreenFrameSink = null
        runCatching { screenCapturer?.stopCapture() }
        runCatching { screenCapturer?.dispose() }
        runCatching { localTrack?.dispose() }
        runCatching { screenVideoSource?.dispose() }
        runCatching { screenTextureHelper?.dispose() }
        screenCapturer = null
        screenVideoTrack = null
        screenVideoSource = null
        screenTextureHelper = null
        screenSharing = false
        screenShareStatus = ""
    }

    fun setMicEnabled(enabled: Boolean) {
        micEnabled = enabled
        localTrack?.setEnabled(enabled)
        if (!enabled) {
            localSpeaking = false
            localLevel = 0f
            speaking.remove(memberId)
            send(JSONObject().put("type", "speaking").put("active", false))
        }
        send(JSONObject().put("type", "mute").put("active", !enabled))
        if (!closed) publish(if (enabled) "Microphone on" else "Microphone muted")
    }

    fun setRemoteVolume(peerId: String, volume: Float) {
        if (peerId.isBlank()) return
        val normalized = volume.coerceIn(0f, 1f).toDouble()
        remoteVolumes[peerId] = normalized
        remoteTracks[peerId]?.let { runCatching { it.setVolume(normalized) } }
    }

    fun setRemoteMuted(peerId: String, muted: Boolean) {
        if (peerId.isBlank()) return
        if (muted) locallyMutedPeers.add(peerId) else locallyMutedPeers.remove(peerId)
        remoteTracks[peerId]?.let { runCatching { it.setEnabled(speakerEnabled && !muted) } }
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
                        if (peer.optBoolean("muted")) mutedMembers.add(id) else mutedMembers.remove(id)
                        if (peer.optBoolean("screenSharing")) screenSharingMembers.add(id) else screenSharingMembers.remove(id)
                        ensurePeer(id, shouldOffer(id))
                    }
                }
                publish(if (list.length() == 0) "Microphone armed · invite friends" else "Connecting party audio…")
            }
            "peer-joined" -> {
                val id = message.optString("memberId")
                if (id.isNotBlank() && id != memberId) {
                    peerNames[id] = message.optString("displayName", id)
                    if (message.optBoolean("muted")) mutedMembers.add(id) else mutedMembers.remove(id)
                    if (message.optBoolean("screenSharing")) screenSharingMembers.add(id) else screenSharingMembers.remove(id)
                    ensurePeer(id, shouldOffer(id))
                }
            }
            "peer-left" -> removePeer(message.optString("memberId"))
            "offer" -> receiveOffer(message)
            "answer" -> receiveAnswer(message)
            "ice" -> receiveIce(message)
            "speaking" -> {
                val id = message.optString("memberId")
                if (message.optBoolean("active")) speaking.add(id) else speaking.remove(id)
                publish("Party voice active")
            }
            "mute" -> {
                val id = message.optString("memberId")
                if (message.optBoolean("active")) mutedMembers.add(id) else mutedMembers.remove(id)
                if (message.optBoolean("active")) speaking.remove(id)
                publish("Party voice active")
            }
            "screen-share" -> {
                val id = message.optString("memberId")
                if (message.optBoolean("active")) {
                    screenSharingMembers.add(id)
                    // Only the deterministic offerer creates offers; this avoids glare
                    // while ensuring a newly-started screen sender is negotiated.
                    if (shouldOffer(id)) renegotiateVideoPeer(id)
                } else {
                    screenSharingMembers.remove(id)
                }
                publish("Party screen share updated")
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
            val video = connection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV))
            if (video != null) {
                videoTransceivers[peerId] = video
                if (screenSharing) screenVideoTrack?.let { video.sender.setTrack(it, false) }
            }
            if (offer) createOffer(peerId, connection)
            return connection
        }
    }

    /**
     * Keep WebRTC's own AudioRecord capture alive even in a one-person room.
     * This local-only sender has no remote description and never sends media to
     * the network; it exists so real microphone samples and the speaking meter
     * are active before the first friend joins.
     */
    private fun startLocalMicMonitor() {
        val currentFactory = factory ?: return
        val track = localTrack ?: return
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
        val monitor = currentFactory.createPeerConnection(config, observer(LOCAL_MIC_MONITOR_ID)) ?: run {
            publish("Microphone meter unavailable · retrying capture")
            return
        }
        if (monitor.addTrack(track, listOf("streamforge-local-mic-monitor")) == null) {
            monitor.close()
            publish("Microphone monitor could not attach the local track")
            return
        }
        localMicMonitor = monitor
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        monitor.createOffer(object : SdpObserverAdapter("create local mic monitor offer") {
            override fun onCreateSuccess(description: SessionDescription?) {
                if (description == null || closed) return
                monitor.setLocalDescription(object : SdpObserverAdapter("set local mic monitor offer") {
                    override fun onSetSuccess() {
                        if (!closed) publish("Microphone track armed · opening capture")
                    }
                }, description)
            }
        }, constraints)
    }

    private fun observer(peerId: String) = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {
            Log.d(TAG, "peer=" + peerId + " signaling=" + state)
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.i(TAG, "peer=" + peerId + " ICE=" + state)
            if (peerId == LOCAL_MIC_MONITOR_ID) return
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
            if (peerId == LOCAL_MIC_MONITOR_ID) return
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
            attachRemoteVideoTrack(peerId, receiver?.track() as? VideoTrack)
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            attachRemoteTrack(peerId, transceiver?.receiver?.track() as? AudioTrack)
            attachRemoteVideoTrack(peerId, transceiver?.receiver?.track() as? VideoTrack)
        }
    }

    private fun attachRemoteVideoTrack(peerId: String, track: VideoTrack?) {
        if (track == null || closed) return
        track.setEnabled(true)
        remoteVideoTracks.put(peerId, track)?.let { previous ->
            remoteVideoFrameSinks.remove(peerId)?.let { sink -> runCatching { previous.removeSink(sink) } }
        }
        val lastFrameLogAt = AtomicLong(0L)
        val frameSink = object : VideoSink {
            override fun onFrame(frame: VideoFrame) {
                val now = SystemClock.elapsedRealtime()
                val previous = lastFrameLogAt.get()
                if (previous == 0L || now - previous >= 5000L) {
                    if (lastFrameLogAt.compareAndSet(previous, now)) {
                        Log.i(TAG, "REMOTE_SCREEN_FRAME peer=${peerId} ${frame.buffer.width}x${frame.buffer.height} rotation=${frame.rotation}")
                    }
                }
            }
        }
        remoteVideoFrameSinks[peerId] = frameSink
        track.addSink(frameSink)
        Log.i(TAG, "Remote screen video track attached for " + peerId + " state=" + track.state())
        publish("Screen share received")
    }

    private fun attachRemoteTrack(peerId: String, track: AudioTrack?) {
        if (track == null || closed) return
        track.setEnabled(speakerEnabled && peerId !in locallyMutedPeers)
        track.setVolume(remoteVolumes[peerId] ?: 1.0)
        remoteTracks[peerId] = track
        Log.i(TAG, "Remote audio track attached for " + peerId)
        publish("Remote microphone connected")
    }

    private fun renegotiateVideoPeer(peerId: String) {
        val pc = peers[peerId] ?: return
        if (!shouldOffer(peerId)) return
        if (pc.signalingState() != PeerConnection.SignalingState.STABLE) {
            Log.w(TAG, "Screen renegotiation deferred for $peerId; signaling=${pc.signalingState()}")
            return
        }
        Log.i(TAG, "Renegotiating screen video sender for peer=$peerId")
        createOffer(peerId, pc)
    }

    private fun createOffer(peerId: String, pc: PeerConnection) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
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
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
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
            remoteVideoTracks.remove(peerId)?.let { track ->
                remoteVideoFrameSinks.remove(peerId)?.let { sink -> runCatching { track.removeSink(sink) } }
            }
            screenSharingMembers.remove(peerId)
            videoTransceivers.remove(peerId)
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

    private fun updateLocalAudioLevel(data: ByteArray) {
        if (closed || !micEnabled || data.size < 2) return
        var sum = 0.0
        var count = 0
        var index = 0
        while (index + 1 < data.size) {
            val raw = ((data[index + 1].toInt() shl 8) or (data[index].toInt() and 0xff)).toShort().toInt()
            val normalized = raw / 32768.0
            sum += normalized * normalized
            count++
            index += 2
        }
        if (count == 0) return
        val level = sqrt(sum / count).toFloat().coerceIn(0f, 1f)
        // A small noise gate prevents microphone hiss from lighting the member tile.
        val active = level >= 0.018f
        val now = SystemClock.elapsedRealtime()
        val changed = active != localSpeaking
        localLevel = level
        if (changed) {
            localSpeaking = active
            if (active) speaking.add(memberId) else speaking.remove(memberId)
            send(JSONObject().put("type", "speaking").put("active", active))
            lastSpeakingSignalAt = now
        } else if (active && now - lastSpeakingSignalAt >= 1800L) {
            send(JSONObject().put("type", "speaking").put("active", true))
            lastSpeakingSignalAt = now
        }
        if (changed || now - lastMicUiPublishAt >= 120L) {
            lastMicUiPublishAt = now
            publish(currentStatus)
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
        currentStatus = status
        onState(
            VoiceEngineState(
                connected = signalingConnected,
                mediaConnected = mediaConnected,
                micEnabled = micEnabled,
                speakerEnabled = speakerEnabled,
                speaking = speaking.toSet(),
                mutedMembers = mutedMembers.toSet(),
                localSpeaking = localSpeaking,
                localLevel = localLevel,
                micCapturing = micCapturing,
                remoteScreenTrack = remoteVideoTracks.entries.firstOrNull { it.key in screenSharingMembers }?.value,
                localScreenTrack = if (screenSharing) screenVideoTrack else null,
                remoteScreenOwner = screenSharingMembers.firstOrNull()?.let { peerNames[it] ?: it },
                remoteScreenActive = screenSharingMembers.isNotEmpty(),
                screenSharing = screenSharing,
                screenShareStatus = screenShareStatus,
                status = status
            )
        )
    }

    private fun releaseMedia() {
        stopScreenShareInternal()
        runCatching { audioModule?.setMicrophoneMute(true) }
        runCatching { localTrack?.setEnabled(false) }
        runCatching { localMicMonitor?.close() }
        localMicMonitor = null
        runCatching { localTrack?.dispose() }
        runCatching { audioSource?.dispose() }
        runCatching { factory?.dispose() }
        runCatching { eglBase?.release() }
        runCatching { audioModule?.release() }
        localTrack = null
        audioSource = null
        factory = null
        eglBase = null
        audioModule = null
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            // Hard mute first, then release the audio device module below.
            micEnabled = false
            runCatching { localTrack?.setEnabled(false) }
            runCatching { audioModule?.setMicrophoneMute(true) }
            micCapturing = false
            localSpeaking = false
            localLevel = 0f
            closed = true
            signalingConnected = false
            mediaConnected = false
            runCatching { socket?.close(1000, "Leaving voice room") }
            socket = null
            peers.values.forEach { pc -> runCatching { pc.close() } }
            peers.clear()
            runCatching { localMicMonitor?.close() }
            localMicMonitor = null
            remoteTracks.clear()
            remoteVideoTracks.forEach { (peerId, track) ->
                remoteVideoFrameSinks.remove(peerId)?.let { sink -> runCatching { track.removeSink(sink) } }
            }
            remoteVideoTracks.clear()
            remoteVideoFrameSinks.clear()
            videoTransceivers.clear()
            pendingIce.clear()
            remoteSdpReady.clear()
            speaking.clear()
            mutedMembers.clear()
            screenSharingMembers.clear()
            locallyMutedPeers.clear()
            remoteVolumes.clear()
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
        const val LOCAL_MIC_MONITOR_ID = "__local_mic_monitor__"
        val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
