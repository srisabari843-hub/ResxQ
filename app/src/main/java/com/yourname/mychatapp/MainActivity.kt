package com.yourname.mychatapp

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.yourname.mychatapp.ui.theme.MyApplicationTheme
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity() {

    private val serviceId = "com.yourname.mychatapp.SERVICE_ID"
    private val strategy = Strategy.P2P_STAR

    private lateinit var connectionsClient: ConnectionsClient
    private var messages = mutableStateListOf<String>()
    private var connectedEndpointId: String? = null
    private var statusText = mutableStateOf("Not connected")

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        connectionsClient = Nearby.getConnectionsClient(this)
        askForPermissions()

        setContent {
            MyApplicationTheme {
                ChatScreen(
                    messages = messages,
                    status = statusText.value,
                    onSend = { text -> sendMessage(text) },
                    onStart = { startAdvertisingAndDiscovery() }
                )
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

    private fun startAdvertisingAndDiscovery() {
        val deviceName = Build.MODEL

        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()

        connectionsClient.startAdvertising(
            deviceName,
            serviceId,
            connectionLifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(strategy).build()
        ).addOnSuccessListener {
            statusText.value = "Advertising..."
        }.addOnFailureListener {
            statusText.value = "Advertising failed"
        }

        connectionsClient.startDiscovery(
            serviceId,
            endpointDiscoveryCallback,
            DiscoveryOptions.Builder().setStrategy(strategy).build()
        ).addOnSuccessListener {
            statusText.value = "Discovering..."
        }.addOnFailureListener {
            statusText.value = "Discovery failed"
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            connectionsClient.requestConnection(
                Build.MODEL,
                endpointId,
                connectionLifecycleCallback
            )
        }

        override fun onEndpointLost(endpointId: String) {
            statusText.value = "Lost connection to peer"
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpointId = endpointId
                statusText.value = "Connected!"
            } else {
                statusText.value = "Connection failed"
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpointId = null
            statusText.value = "Disconnected"
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val bytes = payload.asBytes()
                val text = bytes?.let { String(it, StandardCharsets.UTF_8) }
                text?.let { messages.add("Them: $it") }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun sendMessage(text: String) {
        val endpointId = connectedEndpointId
        if (endpointId != null && text.isNotBlank()) {
            val payload = Payload.fromBytes(text.toByteArray(StandardCharsets.UTF_8))

            connectionsClient.sendPayload(endpointId, payload)
                .addOnSuccessListener {
                    messages.add("Me: $text (sent)")
                }
                .addOnFailureListener {
                    messages.add("Me: $text (FAILED TO SEND)")
                }
        } else {
            messages.add("(Cannot send - not connected)")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionsClient.stopAllEndpoints()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    messages: List<String>,
    status: String,
    onSend: (String) -> Unit,
    onStart: () -> Unit
) {
    var messageText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("My Chat App - $status") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Text("Start Connecting")
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                items(messages) { msg ->
                    Text(
                        text = msg,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message") }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = {
                    if (messageText.isNotBlank()) {
                        onSend(messageText)
                        messageText = ""
                    }
                }) {
                    Text("Send")
                }
            }
        }
    }
}