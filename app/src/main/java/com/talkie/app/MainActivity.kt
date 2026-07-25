@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.talkie.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.view.MotionEvent

private val DeepGreen = Color(0xFF0B4F4A)
private val MidGreen = Color(0xFF12766D)
private val BrightGreen = Color(0xFF1FA394)
private val LiveOrange = Color(0xFFE4572E)
private val BubbleOut = Color(0xFFDCF3EC)

data class ChannelEntry(val name: String, val code: String)

class MainActivity : ComponentActivity() {

    private var service: WalkieService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as WalkieService.LocalBinder).getService()
            bound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                var micGranted by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    )
                }
                val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    micGranted = granted
                }
                val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

                LaunchedEffect(Unit) {
                    if (!micGranted) permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }

                // Only start the foreground service once the microphone permission is
                // actually granted - Android refuses (and crashes) a microphone-type
                // foreground service started before that permission exists.
                LaunchedEffect(micGranted) {
                    if (micGranted && !bound) {
                        val intent = Intent(this@MainActivity, WalkieService::class.java)
                        ContextCompat.startForegroundService(this@MainActivity, intent)
                        bindService(intent, connection, Context.BIND_AUTO_CREATE)
                    }
                }

                if (!micGranted) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Talkie needs microphone access to work.\nPlease allow it to continue.", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                } else if (bound && service != null) {
                    TalkieApp(service!!)
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
        }
    }

    override fun onDestroy() {
        if (bound) { unbindService(connection); bound = false }
        super.onDestroy()
    }
}

@Composable
fun TalkieApp(service: WalkieService) {
    val state by service.state.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf("list") }
    var channels by remember { mutableStateOf(listOf<ChannelEntry>()) }
    var current by remember { mutableStateOf<ChannelEntry?>(null) }

    when (screen) {
        "list" -> ChannelListScreen(
            myName = state.myName,
            channels = channels,
            onRename = { service.setMyName(it) },
            onOpen = { ch -> current = ch; service.joinChannel(ch.name, ch.code); screen = "talk" },
            onAdd = { screen = "new" }
        )
        "new" -> NewChannelScreen(
            onBack = { screen = "list" },
            onJoin = { name, code ->
                val entry = ChannelEntry(name, code)
                if (channels.none { it.code == code }) channels = channels + entry
                current = entry
                service.joinChannel(name, code)
                screen = "talk"
            }
        )
        "talk" -> TalkScreen(
            channel = current,
            state = state,
            onBack = { service.leaveChannel(); screen = "list" },
            onTalkStart = { service.setTalking(true) },
            onTalkStop = { service.setTalking(false) }
        )
    }
}

@Composable
fun ChannelListScreen(
    myName: String,
    channels: List<ChannelEntry>,
    onRename: (String) -> Unit,
    onOpen: (ChannelEntry) -> Unit,
    onAdd: () -> Unit
) {
    var showRename by remember { mutableStateOf(myName.isEmpty()) }
    var nameInput by remember { mutableStateOf(myName) }

    Box(Modifier.fillMaxSize()) {
        Column {
            Row(
                Modifier.fillMaxWidth().background(DeepGreen).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Talkie", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (myName.isEmpty()) "Set your name to start" else "You: $myName",
                        color = Color(0xFFBFE6DF), fontSize = 12.sp
                    )
                }
                IconButton(onClick = { showRename = true }) {
                    Icon(Icons.Default.Mic, contentDescription = "Set name", tint = Color.White)
                }
            }

            if (channels.isEmpty()) {
                Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    Text("No channels yet.\nTap + to create or join one.", textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = Color.Gray)
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(channels) { ch ->
                        Row(
                            Modifier.fillMaxWidth()
                                .pointerInteropFilter {
                                    if (it.action == MotionEvent.ACTION_UP) onOpen(ch)
                                    true
                                }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(48.dp).clip(CircleShape).background(BrightGreen),
                                contentAlignment = Alignment.Center
                            ) { Text("\uD83D\uDCE1", fontSize = 20.sp) }
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(ch.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Text("Code: ${ch.code}", fontSize = 13.sp, color = Color.Gray)
                            }
                        }
                        Divider()
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onAdd,
            containerColor = BrightGreen,
            contentColor = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        ) { Icon(Icons.Default.Add, contentDescription = "New channel") }
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { if (myName.isNotEmpty()) showRename = false },
            title = { Text("Your display name") },
            text = {
                OutlinedTextField(value = nameInput, onValueChange = { nameInput = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    if (nameInput.isNotBlank()) { onRename(nameInput.trim()); showRename = false }
                }) { Text("Save") }
            }
        )
    }
}

@Composable
fun NewChannelScreen(onBack: () -> Unit, onJoin: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(DeepGreen).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White) }
            Text("Join a channel", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(20.dp)) {
            Text("Channel name", fontSize = 13.sp, color = Color.Gray)
            OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            Text("Channel code", fontSize = 13.sp, color = Color.Gray)
            OutlinedTextField(value = code, onValueChange = { code = it.uppercase() }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Text(
                "Everyone who enters the same code lands on the same channel, from anywhere. Leave it blank to create a brand new one.",
                fontSize = 12.5.sp, color = Color.Gray
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    val finalCode = code.ifBlank { (100000..999999).random().toString(36).uppercase() }
                    onJoin(name.ifBlank { "Channel" }, finalCode)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MidGreen)
            ) { Text("Join channel") }
        }
    }
}

@Composable
fun TalkScreen(
    channel: ChannelEntry?,
    state: WalkieState,
    onBack: () -> Unit,
    onTalkStart: () -> Unit,
    onTalkStop: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(DeepGreen).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White) }
            Column {
                Text(channel?.name ?: "Channel", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(if (state.connected) "Connected" else "Connecting…", color = Color(0xFFBFE6DF), fontSize = 12.sp)
            }
        }

        LazyColumn(Modifier.weight(1f).padding(16.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                        .background(BubbleOut).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) { Text("You (${state.myName})", fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(10.dp))
            }
            if (state.peers.isEmpty()) {
                item {
                    Text("Waiting for others to join this channel…", color = Color.Gray, modifier = Modifier.padding(vertical = 20.dp))
                }
            } else {
                items(state.peers) { p ->
                    Row(
                        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                            .background(if (p.talking) Color(0xFFC9F0E4) else BubbleOut).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold)
                            Text(if (p.talking) "Talking…" else "Listening", fontSize = 12.sp, color = Color.Gray)
                        }
                        Box(
                            Modifier.size(10.dp).clip(CircleShape)
                                .background(if (p.talking) LiveOrange else Color(0xFF99BBB8))
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(bottom = 30.dp, top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Code: ${channel?.code ?: "—"}", fontSize = 13.sp, color = Color.Gray)
            Spacer(Modifier.height(8.dp))
            Text(
                if (state.talking) "Talking… release to listen" else "Hold the button to talk",
                fontSize = 14.sp, color = Color.Gray
            )
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .size(128.dp)
                    .clip(CircleShape)
                    .background(if (state.talking) LiveOrange else MidGreen)
                    .pointerInteropFilter {
                        when (it.action) {
                            MotionEvent.ACTION_DOWN -> onTalkStart()
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onTalkStop()
                        }
                        true
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Mic, contentDescription = "Talk", tint = Color.White, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.height(4.dp))
                    Text("HOLD TO TALK", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}
