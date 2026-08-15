package com.yourname.mychatapp

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.lifecycleScope
import com.yourname.mychatapp.ui.theme.MyApplicationTheme
import com.yourname.mychatapp.mesh.MeshManager
import com.yourname.mychatapp.mesh.MeshMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*

// Preferences DataStore for persistent key-value storage
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_settings")
private val KEY_DISPLAY_NAME = stringPreferencesKey("display_name")

data class PeerDevice(
    val endpointId: String,
    val displayName: String
)

enum class DeliveryStatus {
    SENDING,
    DELIVERED_PARTIAL,
    DELIVERED_ALL,
    FAILED
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString().take(8),
    val senderName: String,
    val senderEndpointId: String = "",
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isMine: Boolean = false,
    val isSystem: Boolean = false,
    var deliveryStatus: DeliveryStatus = DeliveryStatus.DELIVERED_ALL,
    var statusText: String = ""
)

class MainActivity : ComponentActivity() {

    private lateinit var meshManager: MeshManager

    private val messages = mutableStateListOf<ChatMessage>()
    private var isSessionActive = mutableStateOf(false)
    private var statusText = mutableStateOf("Not connected")
    private var myDisplayName = mutableStateOf("")
    private val connectedPeersList = mutableStateListOf<PeerDevice>()

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (!allGranted) {
            Toast.makeText(
                this,
                "Nearby permissions are required for P2P mesh chat",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        meshManager = MeshManager(this)
        askForPermissions()
        loadSavedDisplayName()

        // Observe Mesh Manager state
        lifecycleScope.launch {
            meshManager.connectedPeers.collect { peers ->
                connectedPeersList.clear()
                connectedPeersList.addAll(peers.map { PeerDevice(it, it) })
                statusText.value = if (peers.isEmpty()) {
                    if (isSessionActive.value) "Searching for mesh peers..." else "Disconnected"
                } else {
                    "Connected to ${peers.size} peer(s)"
                }
            }
        }

        lifecycleScope.launch {
            meshManager.receivedMessages.collect { meshMsgs ->
                // Map MeshMessage to ChatMessage and update UI
                // Only take new messages
                val currentIds = messages.map { it.id }.toSet()
                for (meshMsg in meshMsgs) {
                    if (!currentIds.contains(meshMsg.messageId)) {
                        val isMine = meshMsg.sourceDeviceId == meshManager.myDeviceId
                        val senderName = if (isMine) "You" else "Peer (${meshMsg.sourceDeviceId.take(4)})"
                        
                        // Parse payload which we formatted as JSON earlier (or simple text)
                        var text = meshMsg.payload
                        var extractedName = senderName
                        try {
                            val json = JSONObject(meshMsg.payload)
                            if (json.has("text")) text = json.getString("text")
                            if (json.has("senderName")) extractedName = json.getString("senderName")
                        } catch (e: Exception) {
                            // ignore, treat as simple string
                        }
                        
                        messages.add(
                            ChatMessage(
                                id = meshMsg.messageId,
                                senderName = if (isMine) "You" else extractedName,
                                text = text,
                                timestamp = meshMsg.timestamp,
                                isMine = isMine,
                                isSystem = false
                            )
                        )
                    }
                }
            }
        }

        setContent {
            MyApplicationTheme {
                ChatScreen(
                    messages = messages,
                    connectedPeers = connectedPeersList,
                    status = statusText.value,
                    isSessionActive = isSessionActive.value,
                    myDisplayName = myDisplayName.value,
                    onDisplayNameChange = { newName ->
                        myDisplayName.value = newName
                        saveDisplayName(newName)
                    },
                    onSend = { text -> broadcastMessage(text) },
                    onToggleSession = {
                        if (isSessionActive.value) {
                            stopSession()
                        } else {
                            startSession()
                        }
                    }
                )
            }
        }
    }

    private fun loadSavedDisplayName() {
        lifecycleScope.launch {
            try {
                val savedName = dataStore.data.map { preferences ->
                    preferences[KEY_DISPLAY_NAME] ?: ""
                }.first()
                myDisplayName.value = savedName
            } catch (_: Exception) { }
        }
    }

    private fun saveDisplayName(name: String) {
        lifecycleScope.launch {
            try {
                dataStore.edit { preferences ->
                    preferences[KEY_DISPLAY_NAME] = name
                }
            } catch (_: Exception) { }
        }
    }

    private fun askForPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        requestPermissions.launch(permissions.toTypedArray())
    }

    private fun startSession() {
        isSessionActive.value = true
        statusText.value = "Searching for nearby peers..."
        meshManager.start()
        addSystemMessage("Started Multi-Hop Mesh Session")
    }

    private fun stopSession() {
        meshManager.stop()
        isSessionActive.value = false
        statusText.value = "Disconnected"
        addSystemMessage("Session stopped and disconnected from all peers")
    }

    private fun broadcastMessage(text: String) {
        if (text.isBlank()) return
        
        val myName = myDisplayName.value.trim().ifEmpty { Build.MODEL }
        
        // Wrap our text in a small JSON to include senderName
        val payloadObj = JSONObject().apply {
            put("text", text)
            put("senderName", myName)
        }
        meshManager.sendMessage(payloadObj.toString(), null)
    }

    private fun addSystemMessage(text: String) {
        messages.add(
            ChatMessage(
                senderName = "System",
                text = text,
                isMine = false,
                isSystem = true
            )
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        meshManager.stop()
    }
}

fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

fun getSenderColor(name: String): Color {
    val colors = listOf(
        Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFE53935), Color(0xFF8E24AA),
        Color(0xFFFB8C00), Color(0xFF00ACC1), Color(0xFFD81B60), Color(0xFF3949AB)
    )
    val hash = kotlin.math.abs(name.hashCode())
    return colors[hash % colors.size]
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    connectedPeers: List<PeerDevice>,
    status: String,
    isSessionActive: Boolean,
    myDisplayName: String,
    onDisplayNameChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onToggleSession: () -> Unit
) {
    var messageText by remember { mutableStateOf("") }
    var isConfigExpanded by remember { mutableStateOf(!isSessionActive) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Multi-Hop Mesh Chat",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (connectedPeers.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    FilledTonalButton(
                        onClick = { isConfigExpanded = !isConfigExpanded },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(
                            text = if (connectedPeers.isEmpty()) "Peers: 0" else "Peers (${connectedPeers.size})",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AnimatedVisibility(visible = isConfigExpanded) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Connection & Profile",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = myDisplayName,
                            onValueChange = onDisplayNameChange,
                            label = { Text("Your Display Name (Nickname)") },
                            placeholder = { Text(Build.MODEL) },
                            singleLine = true,
                            enabled = !isSessionActive,
                            trailingIcon = {
                                if (!isSessionActive) {
                                    TextButton(onClick = { onDisplayNameChange(Build.MODEL) }) {
                                        Text("Default", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = onToggleSession,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSessionActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (isSessionActive) "Stop Mesh Session" else "Start Mesh Session")
                            }
                        }
                    }
                }
            }

            if (connectedPeers.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF4CAF50))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Online (${connectedPeers.size}):",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(connectedPeers, key = { it.endpointId }) { peer ->
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text(text = peer.displayName, style = MaterialTheme.typography.labelSmall) },
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                        }
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No messages yet.\nConnect devices and start chatting!",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
                items(messages, key = { it.id }) { msg ->
                    if (msg.isSystem) {
                        SystemEventBubble(text = msg.text)
                    } else if (msg.isMine) {
                        MyMessageBubble(message = msg)
                    } else {
                        TheirMessageBubble(message = msg)
                    }
                }
            }

            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message group...") },
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 4
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (messageText.isNotBlank()) {
                                onSend(messageText)
                                messageText = ""
                            }
                        },
                        enabled = messageText.isNotBlank(),
                        shape = RoundedCornerShape(24.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text("Send")
                    }
                }
            }
        }
    }
}

@Composable
fun SystemEventBubble(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
fun MyMessageBubble(message: ChatMessage) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 4.dp
            ),
            tonalElevation = 2.dp,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = formatTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
fun TheirMessageBubble(message: ChatMessage) {
    val senderColor = getSenderColor(message.senderName)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(
                topStart = 4.dp,
                topEnd = 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp
            ),
            tonalElevation = 1.dp,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = message.senderName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = senderColor
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatTime(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}
