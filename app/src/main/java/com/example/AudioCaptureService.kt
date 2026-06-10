package com.example

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

class AudioCaptureService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    private val activeClients = CopyOnWriteArrayList<Socket>()
    private val activeWsClients = CopyOnWriteArrayList<Socket>()
    private var serverSocket: ServerSocket? = null
    private var remoteAudioTrack: AudioTrack? = null

    // For local audio file storage
    private var currentFile: File? = null
    private var fileOut: FileOutputStream? = null
    private var totalBytesSaved = 0

    companion object {
        const val CHANNEL_ID = "AudioStreamChannel"
        const val NOTIFICATION_ID = 1
        const val PORT = 8080
        
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        
        var isServiceRunning = false
        val isServiceRunningState = mutableStateOf(false)
        val activeClientCount = mutableStateOf(0)
        val recordedBytes = mutableStateOf(0L)
        val liveVolumeLevel = mutableStateOf(0f)
        val currentRecordingName = mutableStateOf<String?>(null)
        val isRemoteMicConnected = mutableStateOf(false)
        val remoteMicLevel = mutableStateOf(0f)
        
        @Volatile
        var gainMultiplier = 2.5f
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Stream Active")
            .setContentText("Capturing and streaming room audio")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        if (!isServiceRunning) {
            isServiceRunning = true
            isServiceRunningState.value = true
            startAudioCapture()
            startServer()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        isServiceRunningState.value = false
        isRecording = false
        
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        activeClients.forEach { 
            try { it.close() } catch (e: Exception) {} 
        }
        activeClients.clear()

        activeWsClients.forEach { 
            try { it.close() } catch (e: Exception) {} 
        }
        activeWsClients.clear()
        activeClientCount.value = 0
        liveVolumeLevel.value = 0f

        isRemoteMicConnected.value = false
        remoteMicLevel.value = 0f

        try {
            remoteAudioTrack?.stop()
            remoteAudioTrack?.release()
        } catch (e: Exception) {}
        remoteAudioTrack = null
        
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        
        serviceJob.cancel()
        
        // Stop saving and backfill the WAV file size fields
        stopSavingAndFinalizeWav()
        recordedBytes.value = 0L
        currentRecordingName.value = null

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private fun startAudioCapture() {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e("AudioCaptureService", "Invalid audio configuration")
            return
        }
        val bufferSize = minBufferSize * 4

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
        } catch (e: IllegalArgumentException) {
            Log.e("AudioCaptureService", "Failed to initialize AudioRecord", e)
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("AudioCaptureService", "AudioRecord not initialized")
            audioRecord?.release()
            audioRecord = null
            return
        }

        val audioSessionId = audioRecord?.audioSessionId ?: return

        // Apply hardware level corrections
        if (NoiseSuppressor.isAvailable()) {
            try { NoiseSuppressor.create(audioSessionId)?.enabled = true } catch (e: Exception) {}
        }
        if (AcousticEchoCanceler.isAvailable()) {
            try { AcousticEchoCanceler.create(audioSessionId)?.enabled = true } catch (e: Exception) {}
        }
        if (AutomaticGainControl.isAvailable()) {
            try { AutomaticGainControl.create(audioSessionId)?.enabled = true } catch (e: Exception) {}
        }

        // Initialize local recording saving
        startSavingWav()

        audioRecord?.startRecording()
        isRecording = true

        serviceScope.launch {
            val buffer = ByteArray(bufferSize)
            while (isActive && isRecording) {
                val readResult = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (readResult > 0) {
                    // 1. Amplification filter (Software sound enhancer)
                    applyGainFilter(buffer, readResult, gainMultiplier)
                    
                    // 2. Compute amplitude for meter UI
                    calculateAmplitude(buffer, readResult)

                    // 3. Save to local WAV file
                    saveAudioBytes(buffer, readResult)

                    // 4. Stream to browser/client sockets
                    val dataToSend = buffer.copyOf(readResult)
                    
                    // Send to standard HTTP clients
                    val clientsToRemove = mutableListOf<Socket>()
                    for (client in activeClients) {
                        try {
                            client.getOutputStream().write(dataToSend)
                            client.getOutputStream().flush()
                        } catch (e: Exception) {
                            clientsToRemove.add(client)
                        }
                    }
                    if (clientsToRemove.isNotEmpty()) {
                        activeClients.removeAll(clientsToRemove)
                        clientsToRemove.forEach { 
                            try { it.close() } catch (ex: Exception) {} 
                        }
                    }

                    // Send to custom WebSocket connections
                    val wsClientsToRemove = mutableListOf<Socket>()
                    for (client in activeWsClients) {
                        try {
                            sendWebSocketBinaryFrame(client.getOutputStream(), dataToSend, dataToSend.size)
                        } catch (e: Exception) {
                            wsClientsToRemove.add(client)
                        }
                    }
                    if (wsClientsToRemove.isNotEmpty()) {
                        activeWsClients.removeAll(wsClientsToRemove)
                        wsClientsToRemove.forEach { 
                            try { it.close() } catch (ex: Exception) {} 
                        }
                    }

                    // Synchronize combined listeners
                    activeClientCount.value = activeClients.size + activeWsClients.size
                }
            }
        }
    }

    private fun startSavingWav() {
        try {
            val dir = File(filesDir, "recordings")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val name = "Room_Capture_$dateStr.wav"
            val file = File(dir, name)
            currentFile = file
            fileOut = FileOutputStream(file)
            totalBytesSaved = 0
            
            // Write initial empty header
            writeWavHeader(fileOut!!, 0)
            currentRecordingName.value = name
            Log.d("AudioCaptureService", "Started saving to $name")
        } catch (e: Exception) {
            Log.e("AudioCaptureService", "Failed to start saving file", e)
        }
    }

    private fun saveAudioBytes(buffer: ByteArray, len: Int) {
        val out = fileOut ?: return
        try {
            out.write(buffer, 0, len)
            totalBytesSaved += len
            recordedBytes.value = totalBytesSaved.toLong()
        } catch (e: Exception) {
            Log.e("AudioCaptureService", "Error writing audio to storage", e)
        }
    }

    private fun stopSavingAndFinalizeWav() {
        val out = fileOut
        val file = currentFile
        val totalBytes = totalBytesSaved
        
        fileOut = null
        currentFile = null
        totalBytesSaved = 0
        
        if (out != null) {
            try {
                out.flush()
                out.close()
                if (file != null && totalBytes > 0) {
                    backfillWavHeader(file, totalBytes)
                }
            } catch (e: Exception) {
                Log.e("AudioCaptureService", "Error finalizing WAV file", e)
            }
        }
    }

    private fun backfillWavHeader(file: File, totalDataBytes: Int) {
        try {
            RandomAccessFile(file, "rw").use { raf ->
                // Offset 4: ChunkSize = 36 + totalDataBytes
                raf.seek(4)
                val chunkSize = 36 + totalDataBytes
                raf.write(byteArrayOf(
                    (chunkSize and 0xFF).toByte(),
                    ((chunkSize shr 8) and 0xFF).toByte(),
                    ((chunkSize shr 16) and 0xFF).toByte(),
                    ((chunkSize shr 24) and 0xFF).toByte()
                ))
                
                // Offset 40: Subchunk2Size = totalDataBytes
                raf.seek(40)
                raf.write(byteArrayOf(
                    (totalDataBytes and 0xFF).toByte(),
                    ((totalDataBytes shr 8) and 0xFF).toByte(),
                    ((totalDataBytes shr 16) and 0xFF).toByte(),
                    ((totalDataBytes shr 24) and 0xFF).toByte()
                ))
            }
            Log.d("AudioCaptureService", "WAV header updated for ${file.name} size=$totalDataBytes")
        } catch (e: Exception) {
            Log.e("AudioCaptureService", "Failed to update WAV header on stop", e)
        }
    }

    private fun applyGainFilter(buffer: ByteArray, bytesRead: Int, gain: Float) {
        if (gain == 1.0f) return
        for (i in 0 until bytesRead step 2) {
            if (i + 1 < bytesRead) {
                val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                val amplified = sample * gain
                val limited = amplified.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
                buffer[i] = (limited.toInt() and 0xFF).toByte()
                buffer[i + 1] = ((limited.toInt() shr 8) and 0xFF).toByte()
            }
        }
    }

    private fun calculateAmplitude(buffer: ByteArray, bytesRead: Int) {
        var sum = 0.0
        val count = bytesRead / 2
        if (count <= 0) return
        
        for (i in 0 until bytesRead step 2) {
            if (i + 1 < bytesRead) {
                val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                sum += sample * sample
            }
        }
        val rms = Math.sqrt(sum / count)
        // Normalize 0.0 - 1.0
        val level = (rms / 32767.0).toFloat().coerceIn(0f, 1f)
        liveVolumeLevel.value = level
    }

    private fun startServer() {
        serviceScope.launch {
            try {
                serverSocket = ServerSocket(PORT)
                Log.d("AudioCaptureService", "Server started on port $PORT")
                while (isActive) {
                    val socket = serverSocket?.accept() ?: break
                    handleClient(socket)
                }
            } catch (e: Exception) {
                Log.e("AudioCaptureService", "Server error", e)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        serviceScope.launch {
            try {
                val input: InputStream = socket.getInputStream()
                val reader = input.bufferedReader()
                val requestLine = reader.readLine()
                
                if (requestLine != null && requestLine.startsWith("GET")) {
                    var line = reader.readLine()
                    var isUpgradeWs = false
                    var secKey = ""
                    while (!line.isNullOrEmpty()) {
                        if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) {
                            secKey = line.substringAfter(":").trim()
                        }
                        if (line.startsWith("Upgrade:", ignoreCase = true) && line.contains("websocket", ignoreCase = true)) {
                            isUpgradeWs = true
                        }
                        line = reader.readLine()
                    }
                    
                    val path = requestLine.split(" ").getOrNull(1)?.substringBefore("?") ?: "/"
                    val output: OutputStream = socket.getOutputStream()
                    
                    if (isUpgradeWs && secKey.isNotEmpty()) {
                        val acceptKey = getWebSocketAcceptKey(secKey)
                        val handshake = "HTTP/1.1 101 Switching Protocols\r\n" +
                                "Upgrade: websocket\r\n" +
                                "Connection: Upgrade\r\n" +
                                "Sec-WebSocket-Accept: $acceptKey\r\n\r\n"
                        output.write(handshake.toByteArray(Charsets.US_ASCII))
                        output.flush()
                        
                        if (path == "/ws") {
                            activeWsClients.add(socket)
                            activeClientCount.value = activeClients.size + activeWsClients.size
                        } else if (path == "/ws-mic") {
                            isRemoteMicConnected.value = true
                            handleIncomingMicWs(socket)
                        } else {
                            socket.close()
                        }
                    } else {
                        if (path == "/") {
                            val html = """
                                <!DOCTYPE html>
                                <html lang="en">
                                <head>
                                    <meta charset="UTF-8">
                                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                                    <title>Room Audio Portal</title>
                                    <style>
                                        :root {
                                            --bg: #0b0f19;
                                            --surface: #151d30;
                                            --primary: #3b82f6;
                                            --primary-glow: rgba(59, 130, 246, 0.2);
                                            --accent-green: #10b981;
                                            --accent-green-glow: rgba(16, 185, 129, 0.2);
                                            --text: #f3f4f6;
                                            --text-muted: #9ca3af;
                                        }
                                        body {
                                            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                                            background-color: var(--bg);
                                            color: var(--text);
                                            display: flex;
                                            align-items: center;
                                            justify-content: center;
                                            min-height: 100vh;
                                            margin: 0;
                                            padding: 16px;
                                            box-sizing: border-box;
                                        }
                                        .card {
                                            background: var(--surface);
                                            padding: 2.2rem 2rem;
                                            border-radius: 20px;
                                            box-shadow: 0 10px 30px rgba(0, 0, 0, 0.4);
                                            max-width: 480px;
                                            width: 100%;
                                            border: 1px solid rgba(255, 255, 255, 0.05);
                                            text-align: center;
                                            box-sizing: border-box;
                                        }
                                        h1 {
                                            font-size: 1.8rem;
                                            margin: 4px 0 12px 0;
                                            background: linear-gradient(135deg, #3b82f6 0%, #0ea5e9 100%);
                                            -webkit-background-clip: text;
                                            -webkit-text-fill-color: transparent;
                                            font-weight: 800;
                                            letter-spacing: 0.5px;
                                        }
                                        .subtitle {
                                            color: var(--text-muted);
                                            font-size: 0.9rem;
                                            line-height: 1.5;
                                            margin-bottom: 1.8rem;
                                        }
                                        .tabs {
                                            display: flex;
                                            background: rgba(0, 0, 0, 0.2);
                                            padding: 4px;
                                            border-radius: 12px;
                                            margin-bottom: 1.5rem;
                                        }
                                        .tab-btn {
                                            flex: 1;
                                            background: transparent;
                                            border: none;
                                            color: var(--text-muted);
                                            padding: 10px;
                                            font-weight: 600;
                                            font-size: 0.85rem;
                                            border-radius: 8px;
                                            cursor: pointer;
                                            transition: all 0.2s ease;
                                        }
                                        .tab-btn.active {
                                            background: var(--primary);
                                            color: white;
                                            box-shadow: 0 4px 10px var(--primary-glow);
                                        }
                                        .tab-btn.active.talk-tab {
                                            background: var(--accent-green);
                                            box-shadow: 0 4px 10px var(--accent-green-glow);
                                        }
                                        .visualizer-container {
                                            position: relative;
                                            height: 120px;
                                            background: rgba(0, 0, 0, 0.25);
                                            border-radius: 14px;
                                            margin-bottom: 1.5rem;
                                            overflow: hidden;
                                            display: flex;
                                            align-items: center;
                                            justify-content: center;
                                            border: 1px solid rgba(255, 255, 255, 0.02);
                                        }
                                        canvas {
                                            display: block;
                                            width: 100%;
                                            height: 100%;
                                        }
                                        .indicator {
                                            position: absolute;
                                            top: 10px;
                                            right: 12px;
                                            font-size: 0.65rem;
                                            font-weight: 700;
                                            text-transform: uppercase;
                                            padding: 3px 8px;
                                            border-radius: 60px;
                                            background: rgba(255, 255, 255, 0.1);
                                            color: var(--text-muted);
                                        }
                                        .indicator.active {
                                            background: rgba(16, 185, 129, 0.15);
                                            color: var(--accent-green);
                                        }
                                        .action-container {
                                            margin-bottom: 1.8rem;
                                        }
                                        .main-btn {
                                            background: linear-gradient(135deg, var(--primary) 0%, #0ea5e9 100%);
                                            color: white;
                                            border: none;
                                            padding: 14px 40px;
                                            font-size: 1rem;
                                            font-weight: 700;
                                            border-radius: 50px;
                                            cursor: pointer;
                                            width: 100%;
                                            transition: all 0.2s ease;
                                            box-shadow: 0 4px 15px var(--primary-glow);
                                            letter-spacing: 0.5px;
                                        }
                                        .main-btn:active {
                                            transform: scale(0.98);
                                        }
                                        .main-btn.active-listen {
                                            background: #ef4444;
                                            box-shadow: 0 4px 15px rgba(239, 68, 68, 0.2);
                                        }
                                        .main-btn.active-talk {
                                            background: var(--accent-green);
                                            box-shadow: 0 4px 15px var(--accent-green-glow);
                                        }
                                        .filters-panel {
                                            background: rgba(255, 255, 255, 0.02);
                                            border-radius: 12px;
                                            padding: 14px;
                                            text-align: left;
                                            margin-top: 1.5rem;
                                        }
                                        .filter-row {
                                            display: flex;
                                            align-items: center;
                                            justify-content: space-between;
                                            margin-bottom: 8px;
                                        }
                                        .filter-row:last-child {
                                            margin-bottom: 0;
                                        }
                                        .filter-label {
                                            font-size: 0.8rem;
                                            color: var(--text-muted);
                                            display: flex;
                                            align-items: center;
                                            gap: 6px;
                                        }
                                        .filter-value {
                                            font-size: 0.8rem;
                                            font-weight: 600;
                                            color: var(--text);
                                        }
                                        .switch {
                                            position: relative;
                                            display: inline-block;
                                            width: 40px;
                                            height: 20px;
                                        }
                                        .switch input {
                                            opacity: 0;
                                            width: 0;
                                            height: 0;
                                        }
                                        .slider {
                                            position: absolute;
                                            cursor: pointer;
                                            top: 0;
                                            left: 0;
                                            right: 0;
                                            bottom: 0;
                                            background-color: rgba(21, 29, 48, 0.8);
                                            transition: .3s;
                                            border-radius: 20px;
                                            border: 1px solid rgba(255, 255, 255, 0.1);
                                        }
                                        .slider:before {
                                            position: absolute;
                                            content: "";
                                            height: 14px;
                                            width: 14px;
                                            left: 2px;
                                            bottom: 2px;
                                            background-color: white;
                                            transition: .3s;
                                            border-radius: 50%;
                                        }
                                        input:checked + .slider {
                                            background-color: var(--primary);
                                        }
                                        input:checked + .slider:before {
                                            transform: translateX(20px);
                                        }
                                        .volume-control {
                                            margin-top: 12px;
                                        }
                                        .volume-slider {
                                            width: 100%;
                                            background: rgba(255, 255, 255, 0.1);
                                            height: 4px;
                                            border-radius: 2px;
                                            outline: none;
                                            -webkit-appearance: none;
                                        }
                                        .volume-slider::-webkit-slider-thumb {
                                            -webkit-appearance: none;
                                            width: 14px;
                                            height: 14px;
                                            border-radius: 50%;
                                            background: var(--primary);
                                            cursor: pointer;
                                        }
                                    </style>
                                </head>
                                <body>
                                    <div class="card">
                                        <div id="connectionStatus" class="indicator">Offline</div>
                                        <h1>Broadcaster Portal</h1>
                                        <p class="subtitle">Access dual-mode studio audio over high-speed custom WebSockets with real-time noise suppressors.</p>
                                        
                                        <div class="tabs">
                                            <button id="btnListen" class="tab-btn active">LISTEN FROM PHONE</button>
                                            <button id="btnTalk" class="tab-btn">TALK TO PHONE</button>
                                        </div>

                                        <div class="visualizer-container">
                                            <span id="visualizerActive" class="indicator">Visualizer Off</span>
                                            <canvas id="canvas"></canvas>
                                        </div>

                                        <div class="action-container">
                                            <button id="actionBtn" class="main-btn">CONNECT & LISTEN</button>
                                        </div>

                                        <!-- Real-time Web Audio API Controls -->
                                        <div class="filters-panel">
                                            <div class="filter-row">
                                                <span class="filter-label">
                                                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 12h-4l-3 9L9 3l-3 9H2"/></svg>
                                                    High-Pass Noise Filter (120Hz)
                                                </span>
                                                <label class="switch">
                                                    <input type="checkbox" id="toggleHighPass" checked>
                                                    <span class="slider"></span>
                                                </label>
                                            </div>
                                            <div class="filter-row">
                                                <span class="filter-label">
                                                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2v20M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/></svg>
                                                    Dynamics Level Compressor
                                                </span>
                                                <label class="switch">
                                                    <input type="checkbox" id="toggleCompressor" checked>
                                                    <span class="slider"></span>
                                                </label>
                                            </div>
                                            <div class="filter-row volume-control">
                                                <span class="filter-label">Output Gain Volume</span>
                                                <input type="range" id="volumeSlider" class="volume-slider" min="0" max="2" step="0.1" value="1">
                                            </div>
                                        </div>
                                    </div>

                                    <script>
                                        const canvas = document.getElementById('canvas');
                                        const ctx = canvas.getContext('2d');
                                        const connectionStatus = document.getElementById('connectionStatus');
                                        const visualizerActive = document.getElementById('visualizerActive');
                                        const actionBtn = document.getElementById('actionBtn');
                                        const btnListen = document.getElementById('btnListen');
                                        const btnTalk = document.getElementById('btnTalk');
                                        
                                        const toggleHighPass = document.getElementById('toggleHighPass');
                                        const toggleCompressor = document.getElementById('toggleCompressor');
                                        const volumeSlider = document.getElementById('volumeSlider');

                                        let isListeningMode = true;
                                        let isRunning = false;

                                        // Web Audio Context variables
                                        let audioCtx = null;
                                        let pcmSub = null; // WebSocket for listening
                                        let nextPlayTime = 0;
                                        
                                        // Microphone recording variables
                                        let micStream = null;
                                        let micProcessor = null;
                                        let micSub = null; // WebSocket for talking

                                        // Web Audio Nodes
                                        let highPassFilter = null;
                                        let compressor = null;
                                        let outputGain = null;
                                        let analyser = null;
                                        let analyserBuffer = null;
                                        let drawId = null;

                                        function resizeCanvas() {
                                            canvas.width = canvas.parentElement.clientWidth;
                                            canvas.height = canvas.parentElement.clientHeight;
                                        }
                                        window.addEventListener('resize', resizeCanvas);
                                        resizeCanvas();

                                        function updateTabsColors() {
                                            if (isListeningMode) {
                                                btnListen.classList.add('active');
                                                btnListen.classList.remove('talk-tab');
                                                btnTalk.classList.remove('active');
                                                actionBtn.innerText = isRunning ? 'STOP LISTENING' : 'CONNECT & LISTEN';
                                                if (isRunning) {
                                                    actionBtn.className = 'main-btn active-listen';
                                                } else {
                                                    actionBtn.className = 'main-btn';
                                                }
                                            } else {
                                                btnListen.classList.remove('active');
                                                btnTalk.classList.add('active');
                                                btnTalk.classList.add('talk-tab');
                                                actionBtn.innerText = isRunning ? 'STOP BROADCASTING' : 'CONNECT & TRANSMIT';
                                                if (isRunning) {
                                                    actionBtn.className = 'main-btn active-talk';
                                                } else {
                                                    actionBtn.className = 'main-btn';
                                                }
                                            }
                                        }

                                        btnListen.onclick = () => {
                                            if (isRunning) return; // Prevent switching while active
                                            isListeningMode = true;
                                            updateTabsColors();
                                        };

                                        btnTalk.onclick = () => {
                                            if (isRunning) return; // Prevent switching while active
                                            isListeningMode = false;
                                            updateTabsColors();
                                        };

                                        // Standard animated idle wave when visualizer is inactive
                                        function drawIdleWaves() {
                                            ctx.clearRect(0, 0, canvas.width, canvas.height);
                                            ctx.strokeStyle = isListeningMode ? '#3b82f6' : '#10b981';
                                            ctx.lineWidth = 2;
                                            ctx.beginPath();
                                            const columns = 60;
                                            const step = canvas.width / columns;
                                            let x = 0;
                                            for (let i = 0; i <= columns; i++) {
                                                const heightAmp = isRunning ? 25 : 4;
                                                const rawSpeed = isRunning ? 0.02 : 0.005;
                                                const waveY = (canvas.height / 2) + Math.sin(i * 0.25 + Date.now() * rawSpeed) * heightAmp;
                                                if (i === 0) ctx.moveTo(x, waveY);
                                                else ctx.lineTo(x, waveY);
                                                x += step;
                                            }
                                            ctx.stroke();
                                            drawId = requestAnimationFrame(drawIdleWaves);
                                        }
                                        drawIdleWaves();

                                        function initAudioContext() {
                                            if (!audioCtx) {
                                                audioCtx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 44100 });
                                                analyser = audioCtx.createAnalyser();
                                                analyser.fftSize = 64;
                                                analyserBuffer = new Uint8Array(analyser.frequencyBinCount);

                                                highPassFilter = audioCtx.createBiquadFilter();
                                                highPassFilter.type = 'highpass';
                                                highPassFilter.frequency.value = 120; // Cuts off low background rumbles

                                                compressor = audioCtx.createDynamicsCompressor();
                                                compressor.threshold.value = -24;
                                                compressor.knee.value = 30;
                                                compressor.ratio.value = 12;
                                                compressor.attack.value = 0.003;
                                                compressor.release.value = 0.25;

                                                outputGain = audioCtx.createGain();
                                                outputGain.gain.value = parseFloat(volumeSlider.value);
                                            }
                                            // Resume if suspended
                                            if (audioCtx.state === 'suspended') {
                                                audioCtx.resume();
                                            }
                                        }

                                        toggleHighPass.onchange = () => {
                                            if (!highPassFilter) return;
                                            highPassFilter.frequency.value = toggleHighPass.checked ? 120 : 10; // essentially bypass
                                        };

                                        toggleCompressor.onchange = () => {
                                            if (!compressor) return;
                                            compressor.threshold.value = toggleCompressor.checked ? -24 : 0; // essentially bypass
                                        };

                                        volumeSlider.oninput = () => {
                                            if (!outputGain) return;
                                            outputGain.gain.value = parseFloat(volumeSlider.value);
                                        };

                                        function drawLiveVisualizer() {
                                            if (!isRunning || !analyser) return;
                                            requestAnimationFrame(drawLiveVisualizer);
                                            analyser.getByteFrequencyData(analyserBuffer);

                                            ctx.clearRect(0, 0, canvas.width, canvas.height);
                                            const numBars = analyser.frequencyBinCount;
                                            const barWidth = (canvas.width / numBars) * 2.5;
                                            let currentX = 0;

                                            for (let i = 0; i < numBars; i++) {
                                                const scaledHeight = (analyserBuffer[i] / 255) * canvas.height * 0.85;
                                                const g = ctx.createLinearGradient(0, canvas.height, 0, canvas.height - scaledHeight);
                                                if (isListeningMode) {
                                                    g.addColorStop(0, '#3b82f6');
                                                    g.addColorStop(1, '#00d2ff');
                                                } else {
                                                    g.addColorStop(0, '#10b981');
                                                    g.addColorStop(1, '#059669');
                                                }
                                                ctx.fillStyle = g;
                                                ctx.fillRect(currentX, canvas.height - scaledHeight, barWidth - 2, scaledHeight);
                                                currentX += barWidth;
                                            }
                                        }

                                        // --- Dual-Mode Connect/Disconnect Controller ---
                                        actionBtn.onclick = () => {
                                            if (isRunning) {
                                                stopAllTracks();
                                            } else {
                                                startSelectedMode();
                                            }
                                        };

                                        function startSelectedMode() {
                                            isRunning = true;
                                            initAudioContext();
                                            updateTabsColors();
                                            
                                            if (isListeningMode) {
                                                startListening();
                                            } else {
                                                startBroadcasting();
                                            }
                                        }

                                        function stopAllTracks() {
                                            isRunning = false;
                                            
                                            // Terminate Listening Mode
                                            if (pcmSub) {
                                                pcmSub.close();
                                                pcmSub = null;
                                            }

                                            // Terminate Talking Mode
                                            if (micProcessor) {
                                                micProcessor.disconnect();
                                                micProcessor = null;
                                            }
                                            if (micStream) {
                                                micStream.getTracks().forEach(track => track.stop());
                                                micStream = null;
                                            }
                                            if (micSub) {
                                                micSub.close();
                                                micSub = null;
                                            }

                                            connectionStatus.innerText = 'Offline';
                                            connectionStatus.className = 'indicator';
                                            visualizerActive.innerText = 'Visualizer Off';
                                            visualizerActive.className = 'indicator';

                                            updateTabsColors();
                                            cancelAnimationFrame(drawId);
                                            drawIdleWaves();
                                        }

                                        // --- WebSocket Streaming Listener (Receiver) ---
                                        function startListening() {
                                            const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
                                            const streamUrl = proto + '//' + window.location.host + '/ws';
                                            
                                            connectionStatus.innerText = 'Connecting...';
                                            connectionStatus.className = 'indicator active';

                                            pcmSub = new WebSocket(streamUrl);
                                            pcmSub.binaryType = 'arraybuffer';

                                            pcmSub.onopen = () => {
                                                connectionStatus.innerText = 'Streaming Active';
                                                visualizerActive.innerText = 'Live Visualizer';
                                                visualizerActive.className = 'indicator active';
                                                
                                                // Pipeline: incoming chunks -> highPassFilter -> compressor -> outputGain -> analyser -> destination
                                                highPassFilter.connect(compressor);
                                                compressor.connect(outputGain);
                                                outputGain.connect(analyser); // analyser connected to feed visualizer
                                                analyser.connect(audioCtx.destination);
                                                
                                                nextPlayTime = audioCtx.currentTime;
                                                drawLiveVisualizer();
                                            };

                                            pcmSub.onmessage = (event) => {
                                                const int16Array = new Int16Array(event.data);
                                                const float32Array = new Float32Array(int16Array.length);
                                                for (let i = 0; i < int16Array.length; i++) {
                                                    const sample = int16Array[i];
                                                    float32Array[i] = sample < 0 ? sample / 32768.0 : sample / 32767.0;
                                                }
                                                playChunk(float32Array);
                                            };

                                            pcmSub.onclose = () => {
                                                if (isRunning) stopAllTracks();
                                            };

                                            pcmSub.onerror = (e) => {
                                                console.error("WS error:", e);
                                                if (isRunning) stopAllTracks();
                                            };
                                        }

                                        function playChunk(float32Data) {
                                            if (!audioCtx || !isRunning) return;
                                            const buffer = audioCtx.createBuffer(1, float32Data.length, 44100);
                                            buffer.getChannelData(0).set(float32Data);

                                            const source = audioCtx.createBufferSource();
                                            source.buffer = buffer;
                                            source.connect(highPassFilter);

                                            const now = audioCtx.currentTime;
                                            if (nextPlayTime < now) {
                                                nextPlayTime = now + 0.05; // Quick stabilization buffer
                                            }
                                            source.start(nextPlayTime);
                                            nextPlayTime += buffer.duration;
                                        }

                                        // --- WebSocket Microphone Sender (Transmitter) ---
                                        function startBroadcasting() {
                                            connectionStatus.innerText = 'Accessing Mic...';
                                            connectionStatus.className = 'indicator active';

                                            navigator.mediaDevices.getUserMedia({ 
                                                audio: { 
                                                    echoCancellation: true, 
                                                    noiseSuppression: true,
                                                    autoGainControl: true
                                                } 
            }).then(stream => {
                micStream = stream;
                
                const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
                const uploadUrl = proto + '//' + window.location.host + '/ws-mic';

                micSub = new WebSocket(uploadUrl);
                micSub.onopen = () => {
                    connectionStatus.innerText = 'Transmission Active';
                    visualizerActive.innerText = 'Outgoing Meter';
                    visualizerActive.className = 'indicator active';

                    // Web Audio Node connection for incoming local browser audio to apply real-time filtering
                    const sourceNode = audioCtx.createMediaStreamSource(micStream);
                    
                    // Filter chain for outgoing microphone stream to clean low rumble and hiss
                    sourceNode.connect(highPassFilter);
                    highPassFilter.connect(compressor);
                    compressor.connect(analyser); // analyser connected to inspect local mic 

                    // ScriptProcessor to packetize and transmit bytes
                    micProcessor = audioCtx.createScriptProcessor(4096, 1, 1);
                    analyser.connect(micProcessor);
                    micProcessor.connect(audioCtx.destination); // Required to trigger onprocess events

                    micProcessor.onaudioprocess = (e) => {
                        const inputFloat = e.inputBuffer.getChannelData(0);
                        const pcmSamples = new Int16Array(inputFloat.length);
                        for (let i = 0; i < inputFloat.length; i++) {
                            const sample = Math.max(-1, Math.min(1, inputFloat[i]));
                            pcmSamples[i] = sample < 0 ? sample * 32768.0 : sample * 32767.0;
                        }
                        if (micSub && micSub.readyState === WebSocket.OPEN) {
                            micSub.send(pcmSamples.buffer);
                        }
                    };

                    drawLiveVisualizer();
                };

                micSub.onclose = () => {
                    if (isRunning) stopAllTracks();
                };

                micSub.onerror = () => {
                    if (isRunning) stopAllTracks();
                };

            }).catch(err => {
                console.error("Mic access denied:", err);
                alert("Microphone permission was denied or is blocked by your browser. Please allow permission to transmit sound.");
                stopAllTracks();
            });
                                        }
                                    </script>
                                </body>
                                </html>
                            """.trimIndent()
                            
                            val htmlBytes = html.toByteArray()
                            val response = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: text/html\r\n" +
                                    "Content-Length: ${htmlBytes.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                            output.write(response.toByteArray())
                            output.write(htmlBytes)
                            output.flush()
                            socket.close()
                        } else if (path == "/stream") {
                            val headers = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: audio/wav\r\n" +
                                    "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                                    "Pragma: no-cache\r\n" +
                                    "Expires: 0\r\n" +
                                    "Connection: keep-alive\r\n\r\n"
                            output.write(headers.toByteArray())
                            writeWavHeader(output, 0xFFFFFFFF.toInt())
                            
                            activeClients.add(socket)
                            activeClientCount.value = activeClients.size + activeWsClients.size
                        } else {
                            val response = "HTTP/1.1 404 Not Found\r\n\r\n"
                            output.write(response.toByteArray())
                            output.flush()
                            socket.close()
                        }
                    }
                } else {
                    socket.close()
                }
            } catch (e: Exception) {
                try { socket.close() } catch (ex: Exception) {}
            }
        }
    }

    private fun getWebSocketAcceptKey(clientKey: String): String {
        val magicString = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val combinedValue = clientKey + magicString
        val messageDigest = MessageDigest.getInstance("SHA-1")
        val hashedBytes = messageDigest.digest(combinedValue.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(hashedBytes, Base64.NO_WRAP)
    }

    private fun sendWebSocketBinaryFrame(out: OutputStream, data: ByteArray, len: Int) {
        synchronized(out) {
            out.write(0x82) // FIN bit set, Opcode 2 (Binary frame)
            if (len < 126) {
                out.write(len)
            } else if (len <= 65535) {
                out.write(126)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            } else {
                out.write(127)
                out.write(0)
                out.write(0)
                out.write(0)
                out.write(0)
                out.write((len shr 24) and 0xFF)
                out.write((len shr 16) and 0xFF)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            out.write(data, 0, len)
            out.flush()
        }
    }

    private fun handleIncomingMicWs(socket: Socket) {
        serviceScope.launch {
            try {
                val input = socket.getInputStream()
                while (isActive && !socket.isClosed) {
                    val b0 = input.read()
                    if (b0 == -1) break
                    val opcode = b0 and 0x0F
                    
                    val b1 = input.read()
                    if (b1 == -1) break
                    val hasMask = (b1 and 0x80) != 0
                    val payloadLen = b1 and 0x7F
                    
                    var actualLen = payloadLen.toLong()
                    if (payloadLen == 126) {
                        val pml1 = input.read()
                        val pml2 = input.read()
                        if (pml1 == -1 || pml2 == -1) break
                        actualLen = ((pml1 shl 8) or pml2).toLong()
                    } else if (payloadLen == 127) {
                        var lenSum = 0L
                        for (i in 0 until 8) {
                            val b = input.read()
                            if (b == -1) break
                            lenSum = (lenSum shl 8) or b.toLong()
                        }
                        actualLen = lenSum
                    }
                    
                    val maskKey = ByteArray(4)
                    if (hasMask) {
                        val readBytes = input.read(maskKey, 0, 4)
                        if (readBytes != 4) break
                    }
                    
                    val payload = ByteArray(actualLen.toInt())
                    var bytesRead = 0
                    while (bytesRead < actualLen) {
                        val r = input.read(payload, bytesRead, payload.size - bytesRead)
                        if (r <= 0) break
                        bytesRead += r
                    }
                    if (bytesRead != actualLen.toInt()) break
                    
                    if (hasMask) {
                        for (i in 0 until bytesRead) {
                            payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                        }
                    }
                    
                    if (opcode == 8) { // CLOSE
                        break
                    } else if (opcode == 2) { // BINARY PCM raw frames
                        processRemoteMicAudio(payload, bytesRead)
                    }
                }
            } catch (e: Exception) {
                Log.e("AudioCaptureService", "Error reading mic websocket frame", e)
            } finally {
                isRemoteMicConnected.value = false
                remoteMicLevel.value = 0f
                try { socket.close() } catch (ex: Exception) {}
            }
        }
    }

    private fun processRemoteMicAudio(buffer: ByteArray, len: Int) {
        if (remoteAudioTrack == null) {
            initRemoteAudioTrack()
        }
        try {
            remoteAudioTrack?.write(buffer, 0, len)
            calculateRemoteAmplitude(buffer, len)
        } catch (e: Exception) {
            Log.e("AudioCaptureService", "Failed to play remote audio", e)
        }
    }

    @SuppressLint("NewApi")
    private fun initRemoteAudioTrack() {
        try {
            val minBufSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AUDIO_FORMAT
            )
            remoteAudioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(minBufSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            remoteAudioTrack?.play()
        } catch (e: Exception) {
            Log.e("AudioCaptureService", "Failed to init AudioTrack", e)
        }
    }

    private fun calculateRemoteAmplitude(buffer: ByteArray, bytesRead: Int) {
        var sum = 0.0
        val count = bytesRead / 2
        if (count <= 0) return
        
        for (i in 0 until bytesRead step 2) {
            if (i + 1 < bytesRead) {
                val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                sum += sample * sample
            }
        }
        val rms = Math.sqrt(sum / count)
        val level = (rms / 32767.0).toFloat().coerceIn(0f, 1f)
        remoteMicLevel.value = level
    }

    private fun writeWavHeader(out: OutputStream, dataSize: Int) {
        val channels = 1
        val sampleRate = SAMPLE_RATE
        val byteRate = sampleRate * channels * 2
        val chunkSize = 36 + dataSize
        
        val header = ByteBuffer.allocate(44)
        header.order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(chunkSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16) // Subchunk1Size
        header.putShort(1.toShort()) // AudioFormat (PCM)
        header.putShort(channels.toShort()) // NumChannels
        header.putInt(sampleRate) // SampleRate
        header.putInt(byteRate) // ByteRate
        header.putShort((channels * 2).toShort()) // BlockAlign
        header.putShort(16.toShort()) // BitsPerSample
        header.put("data".toByteArray())
        header.putInt(dataSize) // Subchunk2Size
        
        out.write(header.array())
        out.flush()
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Stream Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
