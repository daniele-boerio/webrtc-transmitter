package com.example.webrtcclient


import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.webrtcclient.databinding.ActivityMainBinding
import com.example.webrtcclient.observer.SimpleSdpObserver
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.IceConnectionState
import org.webrtc.PeerConnection.IceGatheringState
import org.webrtc.PeerConnection.IceServer
import org.webrtc.PeerConnection.SignalingState
import org.webrtc.PeerConnectionFactory
import org.webrtc.PeerConnectionFactory.InitializationOptions
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.net.URISyntaxException
import java.util.Arrays


class MainActivity : AppCompatActivity() {
    private val tag = "com.example.webrtcclient.webrtc." + this::class.simpleName

    private val requestCode = 111
    private val VIDEO_TRACK_ID = "ARDAMSv0"
    private val AUDIO_TRACK_ID = "ARDAMSa0"
    private val VIDEO_RESOLUTION_WIDTH = 1280
    private val VIDEO_RESOLUTION_HEIGHT = 720
    private val FPS = 30

    private lateinit var binding: ActivityMainBinding

    private val authCode = "tokenDiAuth"    //todo
    private val peerID = android.os.Build.MODEL //todo
    private val room = "STANZA" //todo

    private lateinit var socket: Socket
    private lateinit var options : IO.Options

    private var isInitiator = false
    private var isChannelReady : Boolean = false
    private var isStarted : Boolean = false


    private lateinit var audioConstraints: MediaConstraints
    private lateinit var videoSource: VideoSource
    private lateinit var audioSource: AudioSource
    private lateinit var localAudioTrack: AudioTrack
    private lateinit var surfaceTextureHelper: SurfaceTextureHelper

    private lateinit var peerConnection: PeerConnection
    private lateinit var rootEglBase: EglBase
    private lateinit var factory: PeerConnectionFactory
    private lateinit var videoTrackFromCamera: VideoTrack

    private var mState = SigState.Disconnected

    // For WebSocket connection retry
    private val MAX_WS_CONNECT_RETRY_CNT = 3
    private val WS_CONNECT_RETRY_WAITTIME: Long = 5000
    private var mNeedRetry = false
    private var mRetryCounter = 0
    private var mHandler: Handler? = null

    //enum for signaling state
    private enum class SigState {
        Disconnected,
        Connecting,
        Connected,
        Created,
        Joining,
        Joined,
        Full,
        Offering,
        ReceivedOffer,
        ReceivedAnswer,
        ReceivedCandidate,
        CreatingAnswer,
        Done
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        start()
    }

    override fun onDestroy() {
        super.onDestroy()

        /*
         * Connection with the signaling server terminated
         */
        mNeedRetry = false
        mRetryCounter = 0
        socket.disconnect()

        peerConnection.dispose()
        factory.dispose()
    }

    private fun start() {

        //if camera has mic and video permission start the process
        if(hasPermissions()){
            initializeSurfaceViews()
            initializePeerConnectionFactory()
            createVideoTrackFromCameraAndShowIt()
            initializePeerConnections()
            connectToSignallingServer()

            mNeedRetry = true
            mRetryCounter = 0
            mHandler = Handler()

        }else{
            requestPermissions()
        }
    }

    //restart all the process if someone disconnect from the call
    private fun restart(){
        initializeVariables()
        clearview()
        initializePeerConnectionFactory()
        createVideoTrackFromCameraAndShowIt()
        initializePeerConnections()
        connectToSignallingServer()
    }

    private fun initializeVariables(){
        mNeedRetry = false
        mRetryCounter = 0
        isInitiator = false
        isChannelReady = false
        isStarted = false
        socket.disconnect()
        peerConnection.dispose()
        factory.dispose()
    }
    private fun clearview(){
        binding.statusLabel.text = ""
    }

    private fun connectToSignallingServer() {
        try {
            val url = "https://develop.ewlab.di.unimi.it/"

            options = IO.Options.builder()
                .setPath("/telecyclette/socket.io/")
                .setReconnection(true)
                .setAuth(mapOf("token" to authCode))
                .setQuery("peerID=$peerID")
                .build()
            socket = IO.socket(url, options)

            Log.e(tag, "IO Socket: $url")
            Log.d(tag, "PeerID: $peerID")



            socket.on(Socket.EVENT_CONNECT) {

                Log.d(tag,"connectToSignallingServer: connect")
                socket.emit("create or join", room)
                mNeedRetry = false
                changeState(SigState.Connected)

            }.on("created") {

                Log.d(tag,"connectToSignallingServer: created")
                isInitiator = true
                changeState(SigState.Created)

            }.on("full") {

                Log.d(tag,"connectToSignallingServer: full")
                changeState(SigState.Full)

            }.on("join") {

                Log.d(tag,"connectToSignallingServer: join")
                isChannelReady = true
                changeState(SigState.Joining)

                //if the call isn't started yet, start the transmission
                if (!isStarted){
                    startStreamingVideo()
                }

            }.on("message") { args: Array<Any> ->

                try {
                    val message = args[0] as JSONObject
                    if (message.getString("type") == "offer") {
                        //received an offer, set the remote description and do an answer
                        Log.d(tag,"connectToSignallingServer: received an offer $message")
                        changeState(SigState.CreatingAnswer)
                        if (!isInitiator && !isStarted) {
                            maybeStart()
                        }
                        peerConnection.setRemoteDescription(
                            object : SimpleSdpObserver() {

                                override fun onSetFailure(s: String) {
                                    Log.d(tag, "onSetFailure: $s")
                                }

                                override fun onSetSuccess() {
                                    doAnswer()
                                }
                            },
                            SessionDescription(
                                SessionDescription.Type.OFFER,
                                message.getString("sdp")
                            )
                        )
                    } else if (message.getString("type") == "answer" && isStarted) {
                        //received an answer, set the remote description
                        changeState(SigState.ReceivedAnswer)
                        Log.d(tag,"connectToSignallingServer: received an answer $message")
                        peerConnection.setRemoteDescription(
                            SimpleSdpObserver(),
                            SessionDescription(
                                SessionDescription.Type.ANSWER,
                                message.getString("sdp")
                            )
                        )
                    } else if (message.getString("type") == "candidate" && isStarted) {
                        //received a candidate, add the candidate to the peerConnection
                        Log.d(tag,"connectToSignallingServer: receiving candidates")
                        changeState(SigState.ReceivedCandidate)
                        val candidate = IceCandidate(
                            message.getString("id"),
                            message.getInt("label"),
                            message.getString("candidate")
                        )
                        peerConnection.addIceCandidate(candidate)
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                    Log.e(tag, e.toString())
                }
            }.on(
                Socket.EVENT_DISCONNECT
            ) {
                Log.d(tag,"connectToSignallingServer: disconnect")
                disconnected()
            }
            socket.connect()
        } catch (e: URISyntaxException) {
            e.printStackTrace()
            Log.e(tag, e.toString())
        }
    }

    private fun disconnected(){
        // Retry at connection failure, after specified seconds
        if (mNeedRetry) {
            if (++mRetryCounter < MAX_WS_CONNECT_RETRY_CNT) {
                val retryConnection = Runnable {
                    connectToSignallingServer()
                }
                mHandler?.postDelayed(retryConnection, WS_CONNECT_RETRY_WAITTIME)
            }else{
                restart()
            }
        }else{
            restart()
        }
    }

    private fun doAnswer() {
        //set the local description and send the answer to the sender
        val sdpMediaConstraints = MediaConstraints()
        peerConnection.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                peerConnection.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        val message = JSONObject()
                        try {
                            message.put("type", "answer")
                            message.put("room", room)
                            message.put("sdp", sessionDescription.description)
                            Log.d(tag, "answer sent: $message")
                            sendMessage(message)
                        } catch (e: JSONException) {
                            e.printStackTrace()
                            Log.e(tag, e.toString())
                        }
                    }
                }, sessionDescription)
            }
        }, sdpMediaConstraints)
    }

    private fun maybeStart() {
        //if the channel is ready, it's not started and i'm the initiator, create an offer
        Log.d(tag, "maybeStart: $isStarted $isChannelReady")
        if (!isStarted && isChannelReady) {
            isStarted = true
            if (isInitiator) {
                doCall()
            }
        }
    }

    private fun doCall() {
        //create an offer and send it
        changeState(SigState.Offering)
        peerConnection.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                Log.d(tag, "onCreateSuccess: ")
                peerConnection.setLocalDescription(SimpleSdpObserver(), sessionDescription)
                val message = JSONObject()
                try {
                    message.put("type", "offer")
                    message.put("room", room)
                    message.put("sdp", sessionDescription.description)
                    Log.d(tag, "created an offer $message")
                    sendMessage(message)
                } catch (e: JSONException) {
                    e.printStackTrace()
                    Log.e(tag, e.toString())
                }
            }
        }, MediaConstraints())
    }

    private fun sendMessage(message: Any) {
        socket.emit("message", message)
    }

    private fun initializeSurfaceViews() {
        rootEglBase = EglBase.create()
        binding.surfaceView.init(rootEglBase.eglBaseContext, null)
        binding.surfaceView.setEnableHardwareScaler(true)
        binding.surfaceView.setMirror(true)
    }

    private fun initializePeerConnectionFactory() {

        val initializationOptions = InitializationOptions.builder(
            applicationContext
        ).createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)
        val options = PeerConnectionFactory.Options()
        val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(
            rootEglBase.eglBaseContext, true, true)
        val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)

        factory = PeerConnectionFactory.builder().setOptions(options)
            .setVideoEncoderFactory(defaultVideoEncoderFactory)
            .setVideoDecoderFactory(defaultVideoDecoderFactory)
            .createPeerConnectionFactory()
    }

    private fun createVideoTrackFromCameraAndShowIt() {
        audioConstraints = MediaConstraints()
        val videoCapturer: VideoCapturer? = createVideoCapturer()


        if (videoCapturer != null) {
            // Initialize the surfaceTextureHelper
            surfaceTextureHelper = SurfaceTextureHelper.create("VideoCapturerThread", rootEglBase.eglBaseContext)

            // Initialize the video source
            videoSource = factory.createVideoSource(false)

            // Initialize the video capturer
            videoCapturer.initialize(surfaceTextureHelper, applicationContext, videoSource.capturerObserver)

            videoCapturer.startCapture(VIDEO_RESOLUTION_WIDTH, VIDEO_RESOLUTION_HEIGHT, FPS)
            videoTrackFromCamera = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource)
            videoTrackFromCamera.setEnabled(true)
            videoTrackFromCamera.addSink(binding.surfaceView)

            // Create an AudioSource instance
            audioSource = factory.createAudioSource(audioConstraints)
            localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, audioSource)
        }
    }


    private fun initializePeerConnections() {
        peerConnection = createPeerConnection(factory)!!
    }

    private fun startStreamingVideo() {
        val mediaStream: MediaStream = factory.createLocalMediaStream("ARDAMS")
        mediaStream.addTrack(videoTrackFromCamera) // Assuming videoTrackFromCamera is a MediaStreamTrack
        mediaStream.addTrack(localAudioTrack) // Assuming localAudioTrack is a MediaStreamTrack
        peerConnection.addTrack(videoTrackFromCamera, listOf(mediaStream.id)) // Add the video track
        peerConnection.addTrack(localAudioTrack, listOf(mediaStream.id)) // Add the audio track

        //IMPORTANT -> set the transceiver for letting know the peerConnection that we are sending and we want audio and sending video but not expecting that

        val audioTransceiver = peerConnection.addTransceiver(localAudioTrack)

        val videoTransceiver  = peerConnection.addTransceiver(videoTrackFromCamera)

        audioTransceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)

        videoTransceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.INACTIVE)


        Log.d(tag, "mediaStreamTrack: ${mediaStream.audioTracks}")

        maybeStart()
    }


    private fun createPeerConnection(factory: PeerConnectionFactory?): PeerConnection? {
        val pcObserver: PeerConnection.Observer = object : PeerConnection.Observer {
            override fun onSignalingChange(signalingState: SignalingState) {
                Log.d(tag, "onSignalingChange: $signalingState")
            }

            override fun onIceConnectionChange(iceConnectionState: IceConnectionState) {
                Log.d(tag, "onIceConnectionChange: $iceConnectionState")
            }

            override fun onIceConnectionReceivingChange(b: Boolean) {
                Log.d(tag, "onIceConnectionReceivingChange: $b")
            }

            override fun onIceGatheringChange(iceGatheringState: IceGatheringState) {
                Log.d(tag, "onIceGatheringChange: $iceGatheringState")
            }

            override fun onIceCandidate(iceCandidate: IceCandidate) {
                val message = JSONObject()
                try {
                    message.put("type", "candidate")
                    message.put("room", room)
                    message.put("id", iceCandidate.sdpMid)
                    message.put("label", iceCandidate.sdpMLineIndex)
                    message.put("candidate", iceCandidate.sdp)
                    Log.d(tag, "onIceCandidate: sending candidate \$message")
                    socket.emit("message", message)
                } catch (e: JSONException) {
                    e.printStackTrace()
                    Log.e(tag, e.toString())
                }
            }

            override fun onIceCandidatesRemoved(iceCandidates: Array<IceCandidate>) {
                Log.d(tag, "onIceCandidatesRemoved: " + Arrays.toString(iceCandidates))
            }

            override fun onAddStream(mediaStream: MediaStream) {
                // Handle add stream
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                Log.d(tag, "onTrack: ")
                if (transceiver != null && transceiver.receiver != null && transceiver.receiver.track() != null) {
                    val track = transceiver.receiver.track()
                    if (track is AudioTrack) {
                        // Handle incoming audio track
                        Log.d(tag, "track audio ricevuta")
                        handleIncomingAudioTrack((track as AudioTrack?)!!)
                    }
                }
            }

            override fun onRemoveStream(mediaStream: MediaStream) {
                Log.d(tag, "onRemoveStream: ")
            }

            override fun onDataChannel(dataChannel: DataChannel) {
                Log.d(tag, "onDataChannel: ")
            }

            override fun onRenegotiationNeeded() {
                Log.d(tag, "onRenegotiationNeeded: ")
            }

            override fun onAddTrack(p0: RtpReceiver, mediaStreams: Array<MediaStream>) {}
        }
        val url = "stun:stun.l.google.com:19302"
        val iceServers = listOf(IceServer.builder(url).createIceServer())
        return factory!!.createPeerConnection(iceServers, pcObserver)
    }

    private fun handleIncomingAudioTrack(audioTrack: AudioTrack) {
        audioTrack.setEnabled(true)
    }
    private fun createVideoCapturer(): VideoCapturer? {
        val videoCapturer: VideoCapturer? = if (useCamera2()) {
            createCameraCapturer(Camera2Enumerator(this))
        } else {
            createCameraCapturer(Camera1Enumerator(true))
        }
        return videoCapturer
    }

    private fun createCameraCapturer(enumerator: CameraEnumerator): VideoCapturer? {
        val deviceNames: Array<String> = enumerator.deviceNames
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        for (deviceName in deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        return null
    }

    private fun useCamera2(): Boolean {
        return Camera2Enumerator.isSupported(this)
    }

    private fun hasPermissions(): Boolean {
        val cameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        )
        val audioPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        )

        // Return true only if both permissions are granted
        return cameraPermission == PackageManager.PERMISSION_GRANTED &&
                audioPermission == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ),
            requestCode
        )
    }
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == this.requestCode) {
            for (i in permissions.indices) {
                val permission = permissions[i]
                val grantResult = grantResults[i]

                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted
                    Log.d(tag, "Permission granted: $permission")

                } else {
                    // Permission denied
                    Log.d(tag, "Permission denied: $permission")
                    // Handle the denied permission (e.g., show a message to the user)

                }
            }
        }
        if(!grantResults.contains(PackageManager.PERMISSION_DENIED)){
            //permission granted
            start()
        }else{
            //permission denied
            Toast.makeText(this, "Permission are required" , Toast.LENGTH_SHORT).show()
        }
    }

    /*
     * Manage the connection status of WebRTC
     */
    private fun changeState(state: SigState) {
        mState = state
        runOnUiThread {
            var stat = ""
            when (state) {
                SigState.Connected -> stat = "Connected"
                SigState.Connecting -> stat = "Connecting..."
                SigState.Created -> stat = "Created"
                SigState.Joining -> stat = "joining..."
                SigState.Joined -> stat = "Joined"
                SigState.Full -> stat = "Full"
                SigState.Offering -> stat = "Offering..."
                SigState.CreatingAnswer -> stat = "CreatingAnswer..."
                SigState.ReceivedOffer -> stat = "ReceivedOffer"
                SigState.ReceivedAnswer -> stat = "ReceivedAnswer"
                SigState.ReceivedCandidate -> stat = "ReceivedCandidate"
                SigState.Done -> stat = "OK!"
                else -> {}
            }

            // Display status (for debug)
            setStatusText(stat)
        }
    }

    /*
     * Display status to application View (for debug)
     */
    private fun setStatusText(stateText: String) {
        val statusText = findViewById<View>(R.id.status_label) as TextView
        statusText.text = "${statusText.text} \n $stateText"
    }
}