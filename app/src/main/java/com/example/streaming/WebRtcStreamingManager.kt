package com.example.streaming

import android.content.Context
import android.util.Log
import com.example.data.model.IceServerConfig
import com.example.data.model.StreamTelemetry
import com.example.data.model.VideoCodecPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class WebRtcStreamingManager(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val inputBus: ControllerInputBus
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val eglBase: EglBase = EglBase.create()

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var inputDataChannel: DataChannel? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var attachedRenderer: SurfaceViewRenderer? = null
    private var webSocket: WebSocket? = null

    private var statsJob: Job? = null
    private var inputForwardJob: Job? = null
    private var reconnectJob: Job? = null

    private var activeWsUrl: String = ""
    private var activeStreamToken: String = ""
    private var activeSessionId: String = ""
    private var activeIceServers: List<IceServerConfig> = emptyList()
    private var preferredCodec: VideoCodecPreference = VideoCodecPreference.H265
    private var manuallyClosed = false
    private var reconnectAttempt = 0
    private var lastBytesReceived = 0L
    private var lastStatsTsMs = 0L

    private val _telemetry = MutableStateFlow(StreamTelemetry())
    val telemetry: StateFlow<StreamTelemetry> = _telemetry.asStateFlow()

    val eglContext: EglBase.Context get() = eglBase.eglBaseContext

    fun initializeFactory() {
        if (peerConnectionFactory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(decoderFactory)
            .setVideoEncoderFactory(encoderFactory)
            .createPeerConnectionFactory()
    }

    fun attachRenderer(renderer: SurfaceViewRenderer) {
        attachedRenderer = renderer
        renderer.init(eglBase.eglBaseContext, null)
        renderer.setEnableHardwareScaler(true)
        renderer.setMirror(false)
        remoteVideoTrack?.addSink(renderer)
    }

    fun detachRenderer(renderer: SurfaceViewRenderer) {
        remoteVideoTrack?.removeSink(renderer)
        if (attachedRenderer == renderer) {
            attachedRenderer = null
        }
        runCatching { renderer.release() }
    }

    fun startStream(
        sessionId: String,
        signalingWsUrl: String,
        streamToken: String,
        iceServers: List<IceServerConfig>,
        codec: VideoCodecPreference,
        targetResolution: String,
        targetFps: Int
    ) {
        manuallyClosed = false
        reconnectAttempt = 0
        activeSessionId = sessionId
        activeWsUrl = signalingWsUrl
        activeStreamToken = streamToken
        activeIceServers = iceServers
        preferredCodec = codec

        _telemetry.update {
            it.copy(
                connectionState = "Initializing WebRTC...",
                activeCodec = codec.wireValue,
                activeResolution = "${targetResolution} @ ${targetFps}fps",
                isReconnecting = false
            )
        }

        initializeFactory()
        createPeerConnection()
        connectSignalingWebSocket()
        startInputForwarding()
    }

    fun requestQualityChange(resolution: String, fps: Int, maxBitrateKbps: Int) {
        val msg = JSONObject().apply {
            put("type", "quality_control")
            put("sessionId", activeSessionId)
            put("resolution", resolution)
            put("fps", fps)
            put("maxBitrateKbps", maxBitrateKbps)
            put("codec", preferredCodec.wireValue)
        }
        webSocket?.send(msg.toString())
        _telemetry.update { it.copy(activeResolution = "$resolution @ ${fps}fps") }
    }

    private fun createPeerConnection() {
        peerConnection?.close()
        val rtcIceServers = activeIceServers.map { cfg ->
            PeerConnection.IceServer.builder(cfg.urls).apply {
                if (!cfg.username.isNullOrBlank()) setUsername(cfg.username)
                if (!cfg.credential.isNullOrBlank()) setPassword(cfg.credential)
            }.createIceServer()
        }
        val rtcConfig = PeerConnection.RTCConfiguration(rtcIceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            reconnectAttempt = 0
                            _telemetry.update {
                                it.copy(connectionState = "Streaming Live", isReconnecting = false)
                            }
                            startStatsPolling()
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED,
                        PeerConnection.IceConnectionState.FAILED -> {
                            _telemetry.update {
                                it.copy(connectionState = "Reconnecting...", isReconnecting = true)
                            }
                            scheduleReconnect()
                        }
                        else -> Unit
                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate ?: return
                    val msg = JSONObject().apply {
                        put("type", "ice_candidate")
                        put("sessionId", activeSessionId)
                        put("candidate", JSONObject().apply {
                            put("sdpMid", candidate.sdpMid)
                            put("sdpMLineIndex", candidate.sdpMLineIndex)
                            put("candidate", candidate.sdp)
                        })
                    }
                    webSocket?.send(msg.toString())
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(dc: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    val track = receiver?.track()
                    if (track is VideoTrack) {
                        remoteVideoTrack = track
                        attachedRenderer?.let { renderer ->
                            scope.launch { track.addSink(renderer) }
                        }
                    }
                }
            }
        )

        val init = DataChannel.Init().apply {
            ordered = false
            maxRetransmits = 0
        }
        inputDataChannel = peerConnection?.createDataChannel("game_input", init)
    }

    private fun connectSignalingWebSocket() {
        webSocket?.close(1000, "reconnecting")
        val request = Request.Builder()
            .url(activeWsUrl)
            .addHeader("Authorization", "Bearer $activeStreamToken")
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _telemetry.update { it.copy(connectionState = "Negotiating GPU Host...") }
                val joinMsg = JSONObject().apply {
                    put("type", "join_session")
                    put("sessionId", activeSessionId)
                    put("token", activeStreamToken)
                    put("preferredCodec", preferredCodec.wireValue)
                }
                webSocket.send(joinMsg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "host_ready" -> createAndSendOffer()
                        "sdp_offer" -> {
                            val sdp = json.getString("sdp")
                            handleRemoteOffer(sdp)
                        }
                        "sdp_answer" -> {
                            val sdp = json.getString("sdp")
                            peerConnection?.setRemoteDescription(
                                SimpleSdpObserver(),
                                SessionDescription(SessionDescription.Type.ANSWER, sdp)
                            )
                        }
                        "ice_candidate" -> {
                            val c = json.getJSONObject("candidate")
                            peerConnection?.addIceCandidate(
                                IceCandidate(
                                    c.getString("sdpMid"),
                                    c.getInt("sdpMLineIndex"),
                                    c.getString("candidate")
                                )
                            )
                        }
                        "pong" -> {
                            val sentAt = json.optLong("clientTs", 0L)
                            if (sentAt > 0L) {
                                val rtt = (System.currentTimeMillis() - sentAt).toInt().coerceAtLeast(1)
                                _telemetry.update { it.copy(rttMs = rtt) }
                            }
                        }
                        "session_terminated" -> {
                            _telemetry.update { it.copy(connectionState = "Session Ended") }
                        }
                    }
                }.onFailure { err ->
                    Log.w("WebRtcManager", "Signaling parse error", err)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!manuallyClosed) {
                    _telemetry.update {
                        it.copy(connectionState = "Signal Lost • Reconnecting...", isReconnecting = true)
                    }
                    scheduleReconnect()
                }
            }
        })
    }

    private fun createAndSendOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc ?: return
                val prioritizedSdp = prioritizeCodecInSdp(desc.description, preferredCodec)
                val updated = SessionDescription(desc.type, prioritizedSdp)
                peerConnection?.setLocalDescription(SimpleSdpObserver(), updated)
                val msg = JSONObject().apply {
                    put("type", "sdp_offer")
                    put("sessionId", activeSessionId)
                    put("sdp", updated.description)
                }
                webSocket?.send(msg.toString())
            }
        }, constraints)
    }

    private fun handleRemoteOffer(sdp: String) {
        val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                peerConnection?.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(answer: SessionDescription?) {
                        answer ?: return
                        val prioritized = SessionDescription(
                            answer.type,
                            prioritizeCodecInSdp(answer.description, preferredCodec)
                        )
                        peerConnection?.setLocalDescription(SimpleSdpObserver(), prioritized)
                        val msg = JSONObject().apply {
                            put("type", "sdp_answer")
                            put("sessionId", activeSessionId)
                            put("sdp", prioritized.description)
                        }
                        webSocket?.send(msg.toString())
                    }
                }, MediaConstraints())
            }
        }, offer)
    }

    private fun prioritizeCodecInSdp(sdp: String, codec: VideoCodecPreference): String {
        val target = if (codec == VideoCodecPreference.H265) "H265" else "H264"
        val lines = sdp.split("\r\n").toMutableList()
        val mVideoIndex = lines.indexOfFirst { it.startsWith("m=video ") }
        if (mVideoIndex == -1) return sdp
        val payloadTypes = lines.mapNotNull { line ->
            if (line.startsWith("a=rtpmap:") && line.contains(target, ignoreCase = true)) {
                line.substringAfter("a=rtpmap:").substringBefore(" ")
            } else null
        }
        if (payloadTypes.isEmpty()) return sdp
        val parts = lines[mVideoIndex].split(" ").toMutableList()
        if (parts.size <= 3) return sdp
        val header = parts.take(3)
        val rest = parts.drop(3).filterNot { it in payloadTypes }
        lines[mVideoIndex] = (header + payloadTypes + rest).joinToString(" ")
        return lines.joinToString("\r\n")
    }

    private fun startInputForwarding() {
        inputForwardJob?.cancel()
        inputForwardJob = scope.launch(Dispatchers.IO) {
            inputBus.packets.collect { payload ->
                val dc = inputDataChannel
                if (dc != null && dc.state() == DataChannel.State.OPEN) {
                    val bytes = payload.toByteArray(StandardCharsets.UTF_8)
                    dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
                } else {
                    val fallback = JSONObject().apply {
                        put("type", "input_relay")
                        put("sessionId", activeSessionId)
                        put("payload", payload)
                    }
                    webSocket?.send(fallback.toString())
                }
            }
        }
    }

    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                val ping = JSONObject().apply {
                    put("type", "ping")
                    put("clientTs", System.currentTimeMillis())
                }
                webSocket?.send(ping.toString())

                peerConnection?.getStats { report ->
                    var fps = 0
                    var bytes = 0L
                    var packetsLost = 0
                    var packetsReceived = 0
                    report.statsMap.values.forEach { stat ->
                        if (stat.type == "inbound-rtp" && stat.members["kind"] == "video") {
                            fps = (stat.members["framesPerSecond"] as? Number)?.toInt() ?: fps
                            bytes = (stat.members["bytesReceived"] as? Number)?.toLong() ?: bytes
                            packetsLost = (stat.members["packetsLost"] as? Number)?.toInt() ?: packetsLost
                            packetsReceived = (stat.members["packetsReceived"] as? Number)?.toInt() ?: packetsReceived
                        }
                    }
                    val now = System.currentTimeMillis()
                    val kbps = if (lastStatsTsMs > 0 && now > lastStatsTsMs && bytes >= lastBytesReceived) {
                        (((bytes - lastBytesReceived) * 8) / (now - lastStatsTsMs)).toInt()
                    } else 0
                    lastBytesReceived = bytes
                    lastStatsTsMs = now
                    val totalPackets = packetsLost + packetsReceived
                    val lossPct = if (totalPackets > 0) (packetsLost * 100f) / totalPackets else 0f
                    _telemetry.update {
                        it.copy(
                            fps = fps,
                            bitrateKbps = kbps,
                            packetLossPct = lossPct,
                            activeGamepadName = inputBus.activeControllerName.value
                        )
                    }
                }
                delay(2000L)
            }
        }
    }

    private fun scheduleReconnect() {
        if (manuallyClosed || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            reconnectAttempt++
            val backoffMs = (1000L * reconnectAttempt).coerceAtMost(8000L)
            delay(backoffMs)
            if (!manuallyClosed) {
                createPeerConnection()
                connectSignalingWebSocket()
            }
        }
    }

    fun stopStream() {
        manuallyClosed = true
        statsJob?.cancel()
        inputForwardJob?.cancel()
        reconnectJob?.cancel()
        runCatching { webSocket?.close(1000, "user_exit") }
        webSocket = null
        runCatching { inputDataChannel?.close() }
        inputDataChannel = null
        runCatching { peerConnection?.close() }
        peerConnection = null
        remoteVideoTrack = null
        _telemetry.value = StreamTelemetry()
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}
