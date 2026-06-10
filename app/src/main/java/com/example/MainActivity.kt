package com.example

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MyApplicationTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.delay
import java.io.File
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color(0xFF0B0F19) // Custom premium dark slate background
                ) { innerPadding ->
                    AudioStreamApp(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun AudioStreamApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    
    // Binding directly to the service's companion object mutableStateOf properties!
    var isStreaming by remember { mutableStateOf(AudioCaptureService.isServiceRunningState.value) }
    var activeClients by remember { mutableStateOf(AudioCaptureService.activeClientCount.value) }
    var liveVolume by remember { mutableStateOf(AudioCaptureService.liveVolumeLevel.value) }
    var recordedSize by remember { mutableStateOf(AudioCaptureService.recordedBytes.value) }
    var currentRecName by remember { mutableStateOf(AudioCaptureService.currentRecordingName.value) }
    
    var ipAddress by remember { mutableStateOf(getLocalIpAddress()) }
    var selectedTab by remember { mutableStateOf(0) } // 0: Broadcast, 1: Saved Files
    var recordingsList by remember { mutableStateOf(getLocalRecordings(context)) }

    // Update state from background thread occasionally
    LaunchedEffect(Unit) {
        while (true) {
            ipAddress = getLocalIpAddress()
            isStreaming = AudioCaptureService.isServiceRunningState.value
            activeClients = AudioCaptureService.activeClientCount.value
            liveVolume = AudioCaptureService.liveVolumeLevel.value
            recordedSize = AudioCaptureService.recordedBytes.value
            currentRecName = AudioCaptureService.currentRecordingName.value
            
            // Periodically refresh list of recordings if not actively playing
            if (selectedTab == 1) {
                recordingsList = getLocalRecordings(context)
            }
            delay(250)
        }
    }

    // Refresh recordings list when switching tabs
    LaunchedEffect(selectedTab) {
        if (selectedTab == 1) {
            recordingsList = getLocalRecordings(context)
        }
    }

    val permissions = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.MODIFY_AUDIO_SETTINGS
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    
    val permissionState = rememberMultiplePermissionsState(permissions = permissions)
    val allGranted = permissionState.allPermissionsGranted

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF0F172A), Color(0xFF020617))
                )
            )
    ) {
        // App header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "ROOM BROADCASTER",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF3B82F6),
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Audio Stream & Recorder",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.7f)
            )
        }

        // Tab selection
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            contentColor = Color(0xFF3B82F6),
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Wifi, 
                            contentDescription = null, 
                            modifier = Modifier.size(18.dp),
                            tint = if (selectedTab == 0) Color(0xFF3B82F6) else Color.White.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Live Broadcast",
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == 0) Color.White else Color.White.copy(alpha = 0.5f)
                        )
                    }
                }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Audiotrack, 
                            contentDescription = null, 
                            modifier = Modifier.size(18.dp),
                            tint = if (selectedTab == 1) Color(0xFF3B82F6) else Color.White.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Recordings (${recordingsList.size})",
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == 1) Color.White else Color.White.copy(alpha = 0.5f)
                        )
                    }
                }
            )
        }

        // Main content body
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (selectedTab == 0) {
                // Live broadcast view
                BroadcastTab(
                    isStreaming = isStreaming,
                    activeClients = activeClients,
                    liveVolume = liveVolume,
                    recordedSize = recordedSize,
                    currentRecName = currentRecName,
                    ipAddress = ipAddress,
                    allGranted = allGranted,
                    onRequestPermissions = { permissionState.launchMultiplePermissionRequest() }
                )
            } else {
                // Saved files view
                RecordingsTab(
                    recordingsList = recordingsList,
                    onDeleted = { recordingsList = getLocalRecordings(context) }
                )
            }
        }
    }
}

@Composable
fun BroadcastTab(
    isStreaming: Boolean,
    activeClients: Int,
    liveVolume: Float,
    recordedSize: Long,
    currentRecName: String?,
    ipAddress: String?,
    allGranted: Boolean,
    onRequestPermissions: () -> Unit
) {
    val context = LocalContext.current
    var sliderValue by remember { mutableStateOf(AudioCaptureService.gainMultiplier) }
    
    var isRemoteMicConnected by remember { mutableStateOf(AudioCaptureService.isRemoteMicConnected.value) }
    var remoteMicLevel by remember { mutableStateOf(AudioCaptureService.remoteMicLevel.value) }

    LaunchedEffect(Unit) {
        while (true) {
            isRemoteMicConnected = AudioCaptureService.isRemoteMicConnected.value
            remoteMicLevel = AudioCaptureService.remoteMicLevel.value
            delay(150)
        }
    }
    
    // Dynamic soft pulsing light color representing microphone capture
    val pulseScale by animateFloatAsState(
        targetValue = 1f + (liveVolume * 0.45f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "PulseScale"
    )

    val signalColor by animateColorAsState(
        targetValue = if (isStreaming) Color(0xFF10B981) else Color(0xFF3B82F6),
        animationSpec = tween(durationMillis = 300),
        label = "StatusColor"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp)
    ) {
        // Visual Microphone Pulse Center
        item {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(200.dp)
                    .fillMaxWidth()
            ) {
                // Pulsing outer halo
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .graphicsLayer(scaleX = pulseScale, scaleY = pulseScale)
                        .background(
                            color = signalColor.copy(alpha = 0.08f),
                            shape = CircleShape
                        )
                )
                // Second halo
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .graphicsLayer(scaleX = (pulseScale + 1f) / 2f, scaleY = (pulseScale + 1f) / 2f)
                        .background(
                            color = signalColor.copy(alpha = 0.12f),
                            shape = CircleShape
                        )
                )

                // Interactive Circle Button
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(signalColor, signalColor.copy(alpha = 0.8f))
                            )
                        )
                        .clickable {
                            if (!allGranted) {
                                onRequestPermissions()
                            } else {
                                val intent = Intent(context, AudioCaptureService::class.java)
                                if (isStreaming) {
                                    intent.action = "STOP"
                                    context.startService(intent)
                                } else {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(intent)
                                    } else {
                                        context.startService(intent)
                                    }
                                }
                            }
                        }
                ) {
                    Icon(
                        imageVector = if (isStreaming) Icons.Default.Mic else Icons.Default.MicOff,
                        contentDescription = "Microphone Toggle",
                        modifier = Modifier.size(36.dp),
                        tint = Color.White
                    )
                }
            }
        }

        // Live Audio Enhancement Multiplier Level Slider
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VolumeUp, contentDescription = null, tint = Color(0xFF3B82F6), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Room Audio Booster",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        Text(
                            text = String.format(Locale.getDefault(), "%.1fx Boost", sliderValue),
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF3B82F6),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Automatically boosts quiet voices across the room and dampens explosive noise dynamically.",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Slider(
                        value = sliderValue,
                        onValueChange = {
                            sliderValue = it
                            AudioCaptureService.gainMultiplier = it
                        },
                        valueRange = 1.0f..5.0f,
                        steps = 8,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF3B82F6),
                            activeTrackColor = Color(0xFF3B82F6),
                            inactiveTrackColor = Color.White.copy(alpha = 0.1f)
                        )
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("1.0x (Off)", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.4f))
                        Text("2.5x (Classroom)", style = MaterialTheme.typography.bodySmall, color = Color(0xFF3B82F6), fontWeight = FontWeight.SemiBold)
                        Text("5.0x (Spy/Max)", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.4f))
                    }
                }
            }
        }

        // Connection Web Link Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = "Wifi",
                        tint = if (isStreaming) Color(0xFF10B981) else Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Local Network Stream Link",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    if (isStreaming && ipAddress != null) {
                        val streamUrl = "http://$ipAddress:8080"
                        
                        Text(
                            text = streamUrl,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF3B82F6),
                            letterSpacing = 0.5.sp
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .clickable {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Streaming Link", streamUrl)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Link copied to clipboard!", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Link", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Copy Streaming Address",
                                color = Color.White.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        Divider(color = Color.White.copy(alpha = 0.05f))
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Listeners", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.5f))
                                Text("$activeClients online", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Recorded", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.5f))
                                Text(formatFileSize(recordedSize), style = MaterialTheme.typography.bodyMedium, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else if (isStreaming && ipAddress == null) {
                        Text(
                            "No Wi-Fi hotspot or local connection detected. Connect to Wi-Fi to allow other devices to stream your microphone.",
                            color = Color(0xFFEF4444),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text(
                            "Broadcaster Offline",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Tap the microphone circle above to start capturing and broadcasting live room audio.",
                            color = Color.White.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // Remote Speaker Talk back Card
        if (isStreaming && isRemoteMicConnected) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A).copy(alpha = 0.9f)),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF10B981))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Remote Intercom Active",
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            Text(
                                "Live Speaker",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10B981),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Text(
                            "A browser listener is currently transmitting their microphone audio back to your phone speaker in real-time.",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Output audio level indicator
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Voice Level",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.width(72.dp)
                            )
                            
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.05f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(remoteMicLevel.coerceIn(0.01f, 1f))
                                        .background(
                                            brush = Brush.horizontalGradient(
                                                colors = listOf(Color(0xFF10B981), Color(0xFF34D399))
                                            )
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }

        // Persistent Power & Stability Guideline (Battery Optimization)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B).copy(alpha = 0.6f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                    openBatteryOptimizations(context)
                }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.BatteryAlert,
                        contentDescription = "Battery Alert",
                        tint = Color(0xFFFBBF24),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Avoid Audio Stream Interruptions",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "Tap here to bypass battery restrictions for standard uninterrupted multi-hour streaming.",
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Info",
                        tint = Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun RecordingsTab(
    recordingsList: List<File>,
    onDeleted: () -> Unit
) {
    val context = LocalContext.current
    
    // In-app Playback states
    var currentPlayingFile by remember { mutableStateOf<File?>(null) }
    var isPlaybackPlaying by remember { mutableStateOf(false) }
    var playbackProgress by remember { mutableStateOf(0f) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    fun playFile(file: File) {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    isPlaybackPlaying = false
                    currentPlayingFile = null
                }
            }
            currentPlayingFile = file
            isPlaybackPlaying = true
        } catch (e: Exception) {
            Toast.makeText(context, "Playback error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun togglePlayPause() {
        val mp = mediaPlayer ?: return
        try {
            if (mp.isPlaying) {
                mp.pause()
                isPlaybackPlaying = false
            } else {
                mp.start()
                isPlaybackPlaying = true
            }
        } catch (e: Exception) {}
    }

    fun stopPlayback() {
        try {
            mediaPlayer?.let {
                it.stop()
                it.release()
            }
        } catch (e: Exception) {}
        mediaPlayer = null
        currentPlayingFile = null
        isPlaybackPlaying = false
        playbackProgress = 0f
    }

    // Progress updates runner
    LaunchedEffect(currentPlayingFile, isPlaybackPlaying) {
        if (currentPlayingFile != null && isPlaybackPlaying) {
            while (true) {
                mediaPlayer?.let {
                    if (it.duration > 0) {
                        playbackProgress = it.currentPosition.toFloat() / it.duration.toFloat()
                    }
                }
                delay(200)
            }
        }
    }

    // Release player on exit/dispose
    DisposableEffect(Unit) {
        onDispose {
            try {
                mediaPlayer?.release()
            } catch (e: Exception) {}
        }
    }

    if (recordingsList.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Icon(
                    Icons.Default.Audiotrack, 
                    contentDescription = null, 
                    tint = Color.White.copy(alpha = 0.2f),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "No Stored Audio Yet",
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Start broadcasting live to record automatically. High-quality room audio captures will populate here.",
                    color = Color.White.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            
            // Audio list
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(recordingsList, key = { it.name }) { file ->
                    val isCurrent = currentPlayingFile?.absolutePath == file.absolutePath
                    val estimatedDurationMs = estimateWavDurationMs(file)
                    
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCurrent) Color(0xFF1E293B) else Color(0xFF0F172A).copy(alpha = 0.5f)
                        ),
                        border = if (isCurrent) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3B82F6)) else null,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Mini play icon
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isCurrent) Color(0xFF3B82F6).copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f)
                                        )
                                        .clickable {
                                            if (isCurrent) {
                                                togglePlayPause()
                                            } else {
                                                playFile(file)
                                            }
                                        }
                                ) {
                                    Icon(
                                        imageVector = if (isCurrent && isPlaybackPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "Play/Pause File",
                                        tint = if (isCurrent) Color(0xFF3B82F6) else Color.White.copy(alpha = 0.8f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                
                                Spacer(modifier = Modifier.width(12.dp))
                                
                                // Details
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = file.name.removeSuffix(".wav").replace("_", " "),
                                        fontWeight = FontWeight.Bold,
                                        color = if (isCurrent) Color(0xFF3B82F6) else Color.White,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = formatTimestamp(file.lastModified()),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White.copy(alpha = 0.4f)
                                        )
                                        Text(
                                            text = formatDuration(estimatedDurationMs),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF3B82F6),
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = formatFileSize(file.length()),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White.copy(alpha = 0.4f)
                                        )
                                    }
                                }
                                
                                // Delete individual option
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .clickable {
                                            if (isCurrent) {
                                                stopPlayback()
                                            }
                                            file.delete()
                                            onDeleted()
                                            Toast.makeText(context, "Recording deleted", Toast.LENGTH_SHORT).show()
                                        }
                                ) {
                                    Icon(
                                        Icons.Default.Delete, 
                                        contentDescription = "Delete File", 
                                        tint = Color.White.copy(alpha = 0.3f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            
                            // Audio play progress slider
                            if (isCurrent) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = formatDuration((playbackProgress * estimatedDurationMs).toLong()),
                                        color = Color.White.copy(alpha = 0.6f),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Slider(
                                        value = playbackProgress,
                                        onValueChange = {
                                            playbackProgress = it
                                            mediaPlayer?.let { mp ->
                                                val target = (it * mp.duration).toInt()
                                                mp.seekTo(target)
                                            }
                                        },
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color(0xFF3B82F6),
                                            activeTrackColor = Color(0xFF3B82F6),
                                            inactiveTrackColor = Color.White.copy(alpha = 0.1f)
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 8.dp)
                                    )
                                    Text(
                                        text = formatDuration(estimatedDurationMs),
                                        color = Color.White.copy(alpha = 0.6f),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            // Bottom mini playback bar
            if (currentPlayingFile != null) {
                Surface(
                    color = Color(0xFF1E293B),
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Audiotrack, 
                            contentDescription = null, 
                            tint = Color(0xFF3B82F6),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Playing: ${currentPlayingFile?.name?.removeSuffix(".wav")?.replace("_", " ")}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        
                        IconButton(onClick = { togglePlayPause() }) {
                            Icon(
                                imageVector = if (isPlaybackPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = Color.White
                            )
                        }
                        IconButton(onClick = { stopPlayback() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Player",
                                tint = Color.White.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}

// Estimates the duration based on wave formats
fun estimateWavDurationMs(file: File): Long {
    val len = file.length()
    if (len <= 44) return 0L
    val dataSize = len - 44
    // 44.1 kHz, 16 bits mono = 88,200 bytes per second
    return (dataSize * 1000) / 88200
}

fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format(Locale.getDefault(), "%02d:%02d", min, sec)
}

fun formatTimestamp(lastModified: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy - hh:mm a", Locale.getDefault())
    return sdf.format(Date(lastModified))
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return String.format(Locale.getDefault(), "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

fun getLocalRecordings(context: Context): List<File> {
    val dir = File(context.filesDir, "recordings")
    if (!dir.exists()) return emptyList()
    return dir.listFiles { file -> file.extension.lowercase() == "wav" }?.toList()?.sortedByDescending { it.lastModified() } ?: emptyList()
}

fun openBatteryOptimizations(context: Context) {
    try {
        val intent = Intent().apply {
            action = android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        try {
            val intent = Intent().apply {
                action = android.provider.Settings.ACTION_SETTINGS
            }
            context.startActivity(intent)
        } catch (ex: Exception) {
            Toast.makeText(context, "Could not open settings", Toast.LENGTH_SHORT).show()
        }
    }
}

fun getLocalIpAddress(): String? {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        for (intf in interfaces) {
            val addrs = intf.inetAddresses
            for (addr in addrs) {
                if (!addr.isLoopbackAddress && addr.hostAddress?.contains(":") == false) {
                    return addr.hostAddress
                }
            }
        }
    } catch (e: Exception) {
    }
    return null
}
