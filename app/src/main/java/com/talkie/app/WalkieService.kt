package com.talkie.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.UUID

data class Peer(val id: String, val name: String, val talking: Boolean = false)

data class WalkieState(
    val connected: Boolean = false,
    val channelName: String = "",
    val channelCode: String = "",
    val myName: String = "",
    val peers: List<Peer> = emptyList(),
    val talking: Boolean = false
)

// Change this if you deploy your own signaling server.
private const val SIGNALING_URL = "wss://talkie-1-hb0s.onrender.com"

class WalkieService : Service() {

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): WalkieService = this@WalkieService
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(WalkieState())
    val state: StateFlow<WalkieState> = _state

    private var webSocket: WebSocket? = null
    private var myPeerId: String = UUID.randomUUID().toString()

    private lateinit var factory: PeerConnectionFactory
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private val peerConnections = mutableMapOf<String, PeerConnection>()

    override fun onCreate() {
        super.onCreate()
        initWebRtc()
    }

    private fun initWebRtc() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                .createInitializationOptions()
        )
        // Audio-only app - no video encoder/decoder factories or EGL context needed.
        // Setting those up from a headless background service is a common crash source.
        factory = PeerConnectionFactory.builder()
            .createPeerConnectionFactory()

        val audioConstraints = MediaConstraints()
        localAudioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack("talkie-audio", localAudioSource)
        localAudioTrack?.setEnabled(false) // muted until push-to-talk is held
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "talkie_channel"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Talkie", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("Talkie")
            .setContentText("Connected — hold the button in the app to talk")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
    }

    fun setMyName(name: String) {
        _state.update { it.copy(myName = name) }
    }

    fun joinChannel(channelName: String, channelCode: String) {
        _state.update { it.copy(channelName = channelName, channelCode = channelCode) }
        val client = OkHttpClient()
        val request = Request.Builder().url(SIGNALING_URL).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch {
                    _state.update { it.copy(connected = true) }
                }
                val join = JSONObject().apply {
                    put("type", "join")
                    put("channel", channelCode)
                    put("peerId", myPeerId)
                    put("name", _state.value.myName)
                }
                webSocket.send(join.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { handleSignal(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch { _state.update { it.copy(connected = false) } }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch { _state.update { it.copy(connected = false) } }
            }
        })
    }

    private fun handleSignal(text: String) {
        val msg = JSONObject(text)
        when (msg.optString("type")) {
            "channel-state" -> {
                val arr: JSONArray = msg.optJSONArray("peers") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    val id = p.getString("peerId")
                    val name = p.optString("name", "Someone")
                    addPeer(id, name)
                    makeOffer(id)
                }
            }
            "peer-joined" -> addPeer(msg.getString("peerId"), msg.optString("name", "Someone"))
            "peer-left" -> removePeer(msg.getString("peerId"))
            "talk-state" -> {
                val id = msg.getString("peerId")
                val talking = msg.optBoolean("talking", false)
                _state.update { s ->
                    s.copy(peers = s.peers.map { if (it.id == id) it.copy(talking = talking) else it })
                }
            }
            "signal" -> {
                val from = msg.getString("from")
                val data = msg.getJSONObject("data")
                handlePeerSignal(from, data)
            }
        }
    }

    private fun addPeer(id: String, name: String) {
        _state.update { s ->
            if (s.peers.any { it.id == id }) s
            else s.copy(peers = s.peers + Peer(id, name))
        }
    }

    private fun removePeer(id: String) {
        peerConnections.remove(id)?.close()
        _state.update { s -> s.copy(peers = s.peers.filter { it.id != id }) }
    }

    private fun sendSignal(to: String, data: JSONObject) {
        val msg = JSONObject().apply {
            put("type", "signal")
            put("to", to)
            put("data", data)
        }
        webSocket?.send(msg.toString())
    }

    private fun newPeerConnection(peerId: String): PeerConnection {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                val data = JSONObject().apply {
                    put("candidate", JSONObject().apply {
                        put("candidate", candidate.sdp)
                        put("sdpMid", candidate.sdpMid)
                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                    })
                }
                sendSignal(peerId, data)
            }
            override fun onAddStream(stream: MediaStream) { /* audio plays automatically via WebRTC's audio device module */ }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {}
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {}
        })!!
        localAudioTrack?.let { pc.addTrack(it, listOf("talkie-stream")) }
        peerConnections[peerId] = pc
        return pc
    }

    private fun makeOffer(peerId: String) {
        val pc = peerConnections[peerId] ?: newPeerConnection(peerId)
        val constraints = MediaConstraints()
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        val data = JSONObject().apply {
                            put("sdp", JSONObject().apply {
                                put("type", "offer")
                                put("sdp", desc.description)
                            })
                        }
                        sendSignal(peerId, data)
                    }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, desc)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    private fun handlePeerSignal(fromId: String, data: JSONObject) {
        addPeer(fromId, _state.value.peers.find { it.id == fromId }?.name ?: "Someone")
        val pc = peerConnections[fromId] ?: newPeerConnection(fromId)

        if (data.has("sdp")) {
            val sdpObj = data.getJSONObject("sdp")
            val type = sdpObj.getString("type")
            val sdp = sdpObj.getString("sdp")
            val sessionDescription = SessionDescription(
                if (type == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER,
                sdp
            )
            pc.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onSetSuccess() {
                    if (type == "offer") {
                        pc.createAnswer(object : SdpObserver {
                            override fun onCreateSuccess(answerDesc: SessionDescription) {
                                pc.setLocalDescription(object : SdpObserver {
                                    override fun onCreateSuccess(p0: SessionDescription?) {}
                                    override fun onSetSuccess() {
                                        val outData = JSONObject().apply {
                                            put("sdp", JSONObject().apply {
                                                put("type", "answer")
                                                put("sdp", answerDesc.description)
                                            })
                                        }
                                        sendSignal(fromId, outData)
                                    }
                                    override fun onCreateFailure(p0: String?) {}
                                    override fun onSetFailure(p0: String?) {}
                                }, answerDesc)
                            }
                            override fun onSetSuccess() {}
                            override fun onCreateFailure(p0: String?) {}
                            override fun onSetFailure(p0: String?) {}
                        }, MediaConstraints())
                    }
                }
                override fun onCreateFailure(p0: String?) {}
                override fun onSetFailure(p0: String?) {}
            }, sessionDescription)
        } else if (data.has("candidate")) {
            val c = data.getJSONObject("candidate")
            pc.addIceCandidate(
                IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.getString("candidate"))
            )
        }
    }

    fun setTalking(on: Boolean) {
        localAudioTrack?.setEnabled(on)
        _state.update { it.copy(talking = on) }
        val msg = JSONObject().apply {
            put("type", "talk-state")
            put("talking", on)
        }
        webSocket?.send(msg.toString())
    }

    fun leaveChannel() {
        webSocket?.close(1000, "leaving")
        webSocket = null
        peerConnections.values.forEach { it.close() }
        peerConnections.clear()
        _state.update { WalkieState(myName = it.myName) }
    }

    override fun onDestroy() {
        leaveChannel()
        localAudioTrack?.dispose()
        localAudioSource?.dispose()
        super.onDestroy()
    }
}
