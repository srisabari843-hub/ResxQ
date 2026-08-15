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
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.yourname.mychatapp.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

// Preferences DataStore for persistent key-value storage
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_settings")
private val KEY_DISPLAY_NAME = stringPreferencesKey("display_name")

/**
 * Data model for a connected peer device in the P2P cluster.
 */
data class PeerDevice(
    val endpointId: String,
    val displayName: String,
    val nodeId: String = "",
    val connectedAt: Long = System.currentTimeMillis()
)

/**
 * Delivery status for outgoing messages.
 */
enum class DeliveryStatus {
    SENDING,
    DELIVERED_PARTIAL,
    DELIVERED_ALL,
    FAILED
}

/**
 * Data model for chat messages and system status events.
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString().take(8),
    val senderName: String,
    val senderEndpointId: String = "",
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isMine: Boolean = false,
    val isSystem: Boolean = false,
    val targetEndpointIds: List<String> = emptyList(),
    val ackedSenders: MutableSet<String> = mutableSetOf(),
    var deliveryStatus: DeliveryStatus = DeliveryStatus.SENDING,
    var statusText: String = ""
)

class MainActivity : ComponentActivity() {

    private val serviceId = "com.yourname.mychatapp.SERVICE_ID"
    // Strategy.P2P_CLUSTER enables M-to-N decentralized mesh group connections
    private val strategy = Strategy.P2P_CLUSTER

    private lateinit var connectionsClient: ConnectionsClient

    // Deterministic unique Node ID generated for this app session to break discovery ties
    private val myNodeId = UUID.randomUUID().toString().replace("-", "").take(6)

    // Observable Compose state
    private val messages = mutableStateListOf<ChatMessage>()
    private val connectedEndpoints = mutableStateMapOf<String, PeerDevice>()
    private val pendingEndpoints = mutableSetOf<String>()
    private val receivedMessageIds = mutableSetOf<String>()
    private val retryAttempts = mutableMapOf<String, Int>()
    private val fallbackJobs = mutableMapOf<String, Job>()

    private var isSessionActive = mutableStateOf(false)
    private var statusText = mutableStateOf("Not connected")
    // Persistent display name loaded from DataStore (defaults to empty on first launch)
    private var myDisplayName = mutableStateOf("")

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (!allGranted) {
            Toast.makeText(
                this,
                "Nearby permissions are required for P2P chat",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        connectionsClient = Nearby.getConnectionsClient(this)
        askForPermissions()
        loadSavedDisplayName()

        setContent {
            MyApplicationTheme {
                ChatScreen(
                    messages = messages,
                    connectedPeers = connectedEndpoints.values.toList(),
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

    /**
     * Helper to encode node ID + display name for advertising.
     * Format: "$nodeId|$displayName" (e.g. "a8f3b2|Samsung SM-M366B")
     */
    private fun getAdvertisedName(): String {
        val cleanName = myDisplayName.value.trim().ifEmpty { Build.MODEL }
        return "$myNodeId|$cleanName"
    }

    /**
     * Parses the peer's advertised string into Node ID and Display Name.
     */
    private fun parsePeerInfo(rawEndpointName: String, endpointId: String): Pair<String, String> {
        val parts = rawEndpointName.split("|", limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank()) {
            Pair(parts[0], parts[1].ifEmpty { "Device-${endpointId.take(4)}" })
        } else {
            Pair("", rawEndpointName.ifEmpty { "Device-${endpointId.take(4)}" })
        }
    }

    /**
     * Loads the saved display name from Jetpack DataStore on startup.
     */
    private fun loadSavedDisplayName() {
        lifecycleScope.launch {
            try {
                val savedName = dataStore.data.map { preferences ->
                    preferences[KEY_DISPLAY_NAME] ?: ""
                }.first()
                myDisplayName.value = savedName
            } catch (_: Exception) {
                // If read fails, fallback cleanly
            }
        }
    }

    /**
     * Persists the updated display name to Jetpack DataStore immediately.
     */
    private fun saveDisplayName(name: String) {
        lifecycleScope.launch {
            try {
                dataStore.edit { preferences ->
                    preferences[KEY_DISPLAY_NAME] = name
                }
            } catch (_: Exception) {
                // Ignore write errors
            }
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

    /**
     * Starts continuous Advertising and Discovery in P2P_CLUSTER mode.
     * Keeps advertising and discovering active so newly arriving devices can join the mesh.
     */
    private fun startSession() {
        val advertisedName = getAdvertisedName()
        val displayName = myDisplayName.value.trim().ifEmpty { Build.MODEL }

        // Stop any previous state before starting
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()

        isSessionActive.value = true
        statusText.value = "Searching for nearby peers..."

        // 1. Start Advertising so other devices can discover and connect to us
        connectionsClient.startAdvertising(
            advertisedName,
            serviceId,
            connectionLifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(strategy).build()
        ).addOnSuccessListener {
            // Advertising active
        }.addOnFailureListener { e ->
            statusText.value = "Advertising failed: ${e.localizedMessage ?: "Unknown error"}"
        }

        // 2. Start Discovery so we can discover and connect to all other devices
        connectionsClient.startDiscovery(
            serviceId,
            endpointDiscoveryCallback,
            DiscoveryOptions.Builder().setStrategy(strategy).build()
        ).addOnSuccessListener {
            // Discovery active
        }.addOnFailureListener { e ->
            statusText.value = "Discovery failed: ${e.localizedMessage ?: "Unknown error"}"
        }

        addSystemMessage("Started P2P mesh session as \"$displayName\"")
    }

    /**
     * Stops the active session, disconnects from all endpoints, and cleans up state.
     */
    private fun stopSession() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()

        connectedEndpoints.clear()
        pendingEndpoints.clear()
        retryAttempts.clear()
        fallbackJobs.values.forEach { it.cancel() }
        fallbackJobs.clear()

        isSessionActive.value = false
        statusText.value = "Disconnected"

        addSystemMessage("Session stopped and disconnected from all peers")
    }

    /**
     * Discovery callback to detect new peers in the vicinity.
     * Implements DETERMINISTIC TIE-BREAKING to prevent simultaneous requestConnection collisions.
     */
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // If already connected or an outgoing connection is actively in-flight, ignore
            if (connectedEndpoints.containsKey(endpointId) || pendingEndpoints.contains(endpointId)) {
                return
            }

            val (peerNodeId, peerDisplayName) = parsePeerInfo(info.endpointName, endpointId)

            // Deterministic Tie-Breaker:
            // Compare node IDs lexicographically. The device with the SMALLER nodeId initiates requestConnection.
            // The device with the LARGER nodeId passively waits for the other to request, completely eliminating race conditions.
            val shouldInitiate = if (peerNodeId.isNotBlank() && myNodeId.isNotBlank()) {
                myNodeId < peerNodeId
            } else {
                // Fallback tiebreaker: compare display names or endpoint IDs
                endpointId.hashCode() % 2 == 0
            }

            if (shouldInitiate) {
                initiateConnectionWithJitter(endpointId, peerDisplayName)
            } else {
                // Passive side: Set up a fallback watchdog in case the initiator's packet was dropped
                scheduleFallbackWatchdog(endpointId, peerDisplayName)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            fallbackJobs.remove(endpointId)?.cancel()
            if (!connectedEndpoints.containsKey(endpointId)) {
                pendingEndpoints.remove(endpointId)
                retryAttempts.remove(endpointId)
            }
        }
    }

    /**
     * Initiates requestConnection with a small random jitter to avoid radio bursts,
     * and handles retries with backoff if needed.
     */
    private fun initiateConnectionWithJitter(endpointId: String, peerDisplayName: String) {
        if (connectedEndpoints.containsKey(endpointId) || pendingEndpoints.contains(endpointId)) return

        pendingEndpoints.add(endpointId)
        val advertisedName = getAdvertisedName()

        lifecycleScope.launch {
            // Small random jitter between 150ms and 450ms before requesting
            delay(Random.nextLong(150, 450))

            if (!isSessionActive.value || connectedEndpoints.containsKey(endpointId)) {
                pendingEndpoints.remove(endpointId)
                return@launch
            }

            connectionsClient.requestConnection(
                advertisedName,
                endpointId,
                connectionLifecycleCallback
            ).addOnFailureListener { e ->
                pendingEndpoints.remove(endpointId)
                handleConnectionFailureRetry(endpointId, peerDisplayName)
            }
        }
    }

    /**
     * Watchdog timer for passive devices: if after 4 seconds we are still not connected,
     * attempt a fallback connection in case the designated initiator failed or missed discovery.
     */
    private fun scheduleFallbackWatchdog(endpointId: String, peerDisplayName: String) {
        fallbackJobs[endpointId]?.cancel()
        fallbackJobs[endpointId] = lifecycleScope.launch {
            delay(4000)
            if (isSessionActive.value && !connectedEndpoints.containsKey(endpointId) && !pendingEndpoints.contains(endpointId)) {
                initiateConnectionWithJitter(endpointId, peerDisplayName)
            }
        }
    }

    /**
     * Retries failed connection attempts up to 3 times with randomized backoff.
     */
    private fun handleConnectionFailureRetry(endpointId: String, peerDisplayName: String) {
        val attempts = retryAttempts.getOrDefault(endpointId, 0)
        if (attempts < 3 && isSessionActive.value && !connectedEndpoints.containsKey(endpointId)) {
            retryAttempts[endpointId] = attempts + 1
            lifecycleScope.launch {
                delay(Random.nextLong(1500, 3000))
                if (isSessionActive.value && !connectedEndpoints.containsKey(endpointId)) {
                    initiateConnectionWithJitter(endpointId, peerDisplayName)
                }
            }
        }
    }

    /**
     * Connection lifecycle callback handling incoming connection requests, acceptance, and disconnections.
     */
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val (peerNodeId, peerDisplayName) = parsePeerInfo(info.endpointName, endpointId)
            
            // Cancel any fallback watchdog since connection handshake is now initiated
            fallbackJobs.remove(endpointId)?.cancel()
            pendingEndpoints.add(endpointId)

            // Cache peer display name
            connectedEndpoints[endpointId] = PeerDevice(endpointId, peerDisplayName, peerNodeId)

            // Auto-accept the incoming connection
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener {
                    pendingEndpoints.remove(endpointId)
                    connectedEndpoints.remove(endpointId)
                }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pendingEndpoints.remove(endpointId)
            fallbackJobs.remove(endpointId)?.cancel()

            if (result.status.isSuccess) {
                retryAttempts.remove(endpointId)
                val existing = connectedEndpoints[endpointId]
                val peerName = existing?.displayName ?: "Device-${endpointId.take(4)}"
                val peerNode = existing?.nodeId ?: ""
                connectedEndpoints[endpointId] = PeerDevice(endpointId, peerName, peerNode)

                updateConnectionStatus()
                addSystemMessage("\"$peerName\" joined the mesh")
            } else {
                val failedDevice = connectedEndpoints.remove(endpointId)
                updateConnectionStatus()
                // Retry if this was an accidental collision or transient failure
                val name = failedDevice?.displayName ?: "Peer"
                handleConnectionFailureRetry(endpointId, name)
            }
        }

        override fun onDisconnected(endpointId: String) {
            val disconnectedDevice = connectedEndpoints.remove(endpointId)
            pendingEndpoints.remove(endpointId)
            fallbackJobs.remove(endpointId)?.cancel()
            updateConnectionStatus()

            val peerName = disconnectedDevice?.displayName ?: "Device-${endpointId.take(4)}"
            addSystemMessage("\"$peerName\" left the mesh")
        }
    }

    private fun updateConnectionStatus() {
        val count = connectedEndpoints.size
        statusText.value = when {
            count == 0 && isSessionActive.value -> "Searching for mesh peers..."
            count == 1 -> "Connected to 1 peer"
            count > 1 -> "Connected to $count peers (Full Mesh Active)"
            else -> "Not connected"
        }
    }

    /**
     * Handles incoming data payloads (both Chat Messages and Delivery ACKs).
     */
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val bytes = payload.asBytes() ?: return
                val jsonString = String(bytes, StandardCharsets.UTF_8)
                handleIncomingJsonPayload(endpointId, jsonString)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Optional progress monitoring
        }
    }

    /**
     * Parses JSON packet to differentiate between CHAT messages and ACKs.
     */
    private fun handleIncomingJsonPayload(senderEndpointId: String, jsonString: String) {
        try {
            val json = JSONObject(jsonString)
            val type = json.optString("type", "")

            when (type) {
                "CHAT" -> {
                    val msgId = json.optString("id", UUID.randomUUID().toString())
                    val senderName = json.optString("senderName", "Unknown")
                    val text = json.optString("text", "")
                    val timestamp = json.optLong("timestamp", System.currentTimeMillis())

                    // Update known peer display name in case it changed
                    if (connectedEndpoints.containsKey(senderEndpointId)) {
                        val existing = connectedEndpoints[senderEndpointId]!!
                        connectedEndpoints[senderEndpointId] = existing.copy(displayName = senderName)
                    }

                    // Deduplicate messages in case of retransmission
                    if (!receivedMessageIds.contains(msgId)) {
                        receivedMessageIds.add(msgId)

                        // Add incoming message to UI
                        val incomingMsg = ChatMessage(
                            id = msgId,
                            senderName = senderName,
                            senderEndpointId = senderEndpointId,
                            text = text,
                            timestamp = timestamp,
                            isMine = false,
                            isSystem = false
                        )
                        messages.add(incomingMsg)
                    }

                    // Send immediate delivery ACK back to the direct sender
                    sendDeliveryAck(senderEndpointId, msgId)
                }

                "ACK" -> {
                    val msgId = json.optString("id", "")
                    val ackSenderName = json.optString("senderName", "Peer")

                    handleDeliveryAckReceived(msgId, ackSenderName)
                }
            }
        } catch (e: Exception) {
            // Fallback for raw legacy text if any
            messages.add(
                ChatMessage(
                    senderName = "Peer",
                    senderEndpointId = senderEndpointId,
                    text = jsonString,
                    isMine = false
                )
            )
        }
    }

    /**
     * Sends an ACK payload back to the specific message sender to confirm receipt.
     */
    private fun sendDeliveryAck(targetEndpointId: String, messageId: String) {
        try {
            val ackJson = JSONObject().apply {
                put("type", "ACK")
                put("id", messageId)
                put("senderName", myDisplayName.value.trim().ifEmpty { Build.MODEL })
            }
            val payload = Payload.fromBytes(ackJson.toString().toByteArray(StandardCharsets.UTF_8))
            connectionsClient.sendPayload(targetEndpointId, payload)
        } catch (_: Exception) {
            // Ignored
        }
    }

    /**
     * Handles received ACK from a peer and updates the message's delivery status badge.
     */
    private fun handleDeliveryAckReceived(messageId: String, ackSenderName: String) {
        val index = messages.indexOfFirst { it.id == messageId && it.isMine }
        if (index != -1) {
            val msg = messages[index]
            msg.ackedSenders.add(ackSenderName)

            val totalTarget = msg.targetEndpointIds.size
            val ackCount = msg.ackedSenders.size

            if (totalTarget == 0 || ackCount >= totalTarget) {
                msg.deliveryStatus = DeliveryStatus.DELIVERED_ALL
                msg.statusText = if (totalTarget > 1) "Delivered to all ($ackCount/$totalTarget)" else "Delivered"
            } else {
                msg.deliveryStatus = DeliveryStatus.DELIVERED_PARTIAL
                msg.statusText = "Delivered to $ackCount/$totalTarget (${msg.ackedSenders.joinToString(", ")})"
            }

            // Trigger Compose state recomposition by replacing the item
            messages[index] = msg.copy(
                deliveryStatus = msg.deliveryStatus,
                statusText = msg.statusText
            )
        }
    }

    /**
     * Broadcasts a chat message directly to ALL currently connected peers in the mesh.
     */
    private fun broadcastMessage(text: String) {
        if (text.isBlank()) return

        val activeEndpoints = connectedEndpoints.keys.toList()
        if (activeEndpoints.isEmpty()) {
            addSystemMessage("Cannot send: No peers connected yet. Please wait for peers to connect.")
            return
        }

        val myName = myDisplayName.value.trim().ifEmpty { Build.MODEL }
        val msgId = UUID.randomUUID().toString().take(8)
        val timestamp = System.currentTimeMillis()

        // Create structured JSON payload
        val chatJson = JSONObject().apply {
            put("type", "CHAT")
            put("id", msgId)
            put("senderName", myName)
            put("text", text)
            put("timestamp", timestamp)
        }

        val payload = Payload.fromBytes(chatJson.toString().toByteArray(StandardCharsets.UTF_8))

        // Create local outgoing message item
        val outgoingMsg = ChatMessage(
            id = msgId,
            senderName = "You",
            text = text,
            timestamp = timestamp,
            isMine = true,
            isSystem = false,
            targetEndpointIds = activeEndpoints,
            deliveryStatus = DeliveryStatus.SENDING,
            statusText = "Sending to ${activeEndpoints.size} peer(s)..."
        )
        messages.add(outgoingMsg)

        // Send payload directly to all connected endpoints in the mesh
        for (endpointId in activeEndpoints) {
            connectionsClient.sendPayload(endpointId, payload)
                .addOnFailureListener {
                    // Local enqueue failed for this specific endpoint
                    val index = messages.indexOfFirst { it.id == msgId }
                    if (index != -1) {
                        val m = messages[index]
                        if (m.deliveryStatus == DeliveryStatus.SENDING) {
                            m.deliveryStatus = DeliveryStatus.FAILED
                            m.statusText = "Failed local send"
                            messages[index] = m.copy(deliveryStatus = m.deliveryStatus, statusText = m.statusText)
                        }
                    }
                }
        }

        // ACK timeout coroutine (5 seconds) to finalize status if some peers didn't ACK
        lifecycleScope.launch {
            delay(5000)
            val index = messages.indexOfFirst { it.id == msgId && it.isMine }
            if (index != -1) {
                val current = messages[index]
                if (current.deliveryStatus == DeliveryStatus.SENDING) {
                    if (current.ackedSenders.isEmpty()) {
                        current.deliveryStatus = DeliveryStatus.FAILED
                        current.statusText = "No receipt ACK received (timeout)"
                    } else {
                        current.deliveryStatus = DeliveryStatus.DELIVERED_PARTIAL
                        current.statusText = "Delivered to ${current.ackedSenders.size}/${current.targetEndpointIds.size}"
                    }
                    messages[index] = current.copy(
                        deliveryStatus = current.deliveryStatus,
                        statusText = current.statusText
                    )
                }
            }
        }
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
        connectionsClient.stopAllEndpoints()
    }
}

/**
 * Formats timestamps into a clean, human-readable time string (e.g. 11:45 AM).
 */
fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

/**
 * Generates a deterministic, harmonious accent color for peer display names.
 */
fun getSenderColor(name: String): Color {
    val colors = listOf(
        Color(0xFF1E88E5), // Blue
        Color(0xFF43A047), // Green
        Color(0xFFE53935), // Red
        Color(0xFF8E24AA), // Purple
        Color(0xFFFB8C00), // Orange
        Color(0xFF00ACC1), // Teal
        Color(0xFFD81B60), // Pink
        Color(0xFF3949AB)  // Indigo
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

    // Auto-scroll to latest message
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
                            text = "Offline P2P Mesh Chat",
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
                    // Peer count badge / Settings toggle button
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
            // Expandable Connection & Nickname Setup Card
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

                        // Custom Display Name / Nickname Input with DataStore persistence & placeholder hint
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
                                Text(if (isSessionActive) "Stop & Disconnect" else "Start Connecting (P2P Cluster)")
                            }
                        }
                    }
                }
            }

            // Connected Peers Horizontal Chip List
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
                            text = "Online in Mesh (${connectedPeers.size}):",
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
                                    label = {
                                        Text(
                                            text = peer.displayName,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Message Stream
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

            // Message Input Bar
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
                        placeholder = {
                            Text(
                                if (connectedPeers.isEmpty()) "Connect to peers to chat..." else "Message group..."
                            )
                        },
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
                        enabled = messageText.isNotBlank() && connectedPeers.isNotEmpty(),
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

/**
 * System event pill (e.g. Device Joined / Left).
 */
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

/**
 * Bubble layout for messages sent by the current user (Right-aligned).
 */
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

                    // Delivery ACK status indicator
                    val ackIcon = when (message.deliveryStatus) {
                        DeliveryStatus.SENDING -> "⏳"
                        DeliveryStatus.DELIVERED_PARTIAL -> "✓"
                        DeliveryStatus.DELIVERED_ALL -> "✓✓"
                        DeliveryStatus.FAILED -> "⚠️"
                    }

                    Text(
                        text = ackIcon,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Delivery Status details (e.g., "Delivered to all (2/2)" or "Delivered to Samsung SM-M366B")
        if (message.statusText.isNotBlank()) {
            Text(
                text = message.statusText,
                style = MaterialTheme.typography.labelSmall,
                color = when (message.deliveryStatus) {
                    DeliveryStatus.FAILED -> MaterialTheme.colorScheme.error
                    DeliveryStatus.DELIVERED_ALL -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontSize = 10.sp,
                modifier = Modifier.padding(end = 4.dp, top = 2.dp)
            )
        }
    }
}

/**
 * Bubble layout for messages received from peers (Left-aligned).
 */
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
                // Sender Display Name Header with distinct color
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