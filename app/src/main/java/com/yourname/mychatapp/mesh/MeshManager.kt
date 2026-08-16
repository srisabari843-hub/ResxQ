package com.yourname.mychatapp.mesh

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.yourname.mychatapp.sos.GatewaySyncEngine
import com.yourname.mychatapp.sos.SosAlert
import com.yourname.mychatapp.sos.SosStorageManager
import com.yourname.mychatapp.sos.SosUrgency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

class MeshManager(private val context: Context) {
    @SuppressLint("HardwareIds")
    val myDeviceId: String = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: Build.MODEL

    private val router = MessageRouter(myDeviceId)
    private var transport: NearbyTransport? = null
    var cloudTransport: CloudTransport? = null
        private set

    val storageManager = SosStorageManager(context)
    var gatewaySyncEngine: GatewaySyncEngine? = null
        private set

    private val activeEndpoints = mutableMapOf<String, String>() // endpointId -> deviceId
    private val _connectedPeers = MutableStateFlow<Set<String>>(emptySet())
    val connectedPeers: StateFlow<Set<String>> = _connectedPeers.asStateFlow()

    private val _receivedMessages = MutableStateFlow<List<MeshMessage>>(emptyList())
    val receivedMessages: StateFlow<List<MeshMessage>> = _receivedMessages.asStateFlow()

    private val _sosAlerts = MutableStateFlow<List<SosAlert>>(emptyList())
    val sosAlerts: StateFlow<List<SosAlert>> = _sosAlerts.asStateFlow()

    private val _relayStatus = MutableStateFlow("Disconnected")
    val relayStatus: StateFlow<String> = _relayStatus.asStateFlow()

    fun start() {
        if (transport != null) return

        _sosAlerts.value = storageManager.getAllSosAlerts()

        transport = NearbyTransport(
            context = context,
            myDeviceId = myDeviceId,
            onMessageReceived = { senderEndpointId, messageStr ->
                processIncomingMessage(messageStr, senderEndpointId)
            },
            onEndpointConnected = { endpointId, deviceId ->
                activeEndpoints[endpointId] = deviceId
                _connectedPeers.update { activeEndpoints.values.toSet() }
            },
            onEndpointDisconnected = { endpointId, _ ->
                activeEndpoints.remove(endpointId)
                _connectedPeers.update { activeEndpoints.values.toSet() }
            }
        )
        transport?.start()

        // Cloud Relay Transport
        if (cloudTransport == null) {
            cloudTransport = CloudTransport(
                myDeviceId = myDeviceId,
                onMessageReceived = { messageStr ->
                    processIncomingMessage(messageStr, null)
                },
                onStatusChanged = { isConnected, statusText ->
                    _relayStatus.value = statusText
                }
            )
        }

        // Initialize Gateway Sync Engine
        if (gatewaySyncEngine == null) {
            gatewaySyncEngine = GatewaySyncEngine(
                context = context,
                myDeviceId = myDeviceId,
                storageManager = storageManager,
                cloudTransport = cloudTransport!!
            )
            gatewaySyncEngine?.start()
        }
    }

    fun startCloudRelay(serverUrl: String = "http://10.0.2.2:3000", displayName: String = "Android Device") {
        cloudTransport?.start(serverUrl, displayName)
        gatewaySyncEngine?.start(serverUrl)
    }

    fun stopCloudRelay() {
        cloudTransport?.stop()
        gatewaySyncEngine?.stop()
    }

    private fun processIncomingMessage(messageStr: String, sourceEndpointId: String?) {
        // Direct parse attempt
        val directSos = SosAlert.fromJson(messageStr)
        if (directSos != null) {
            val isNew = storageManager.saveSosAlert(directSos)
            _sosAlerts.value = storageManager.getAllSosAlerts()
            if (isNew) {
                gatewaySyncEngine?.triggerGatewaySync()
            }
        }

        router.processIncomingMessage(
            messageStr = messageStr,
            onDeliverToApp = { msg ->
                // Check if payload inside MeshMessage is an SosAlert
                val embeddedSos = SosAlert.fromJson(msg.payload)
                if (embeddedSos != null) {
                    val isNew = storageManager.saveSosAlert(embeddedSos)
                    _sosAlerts.value = storageManager.getAllSosAlerts()
                    if (isNew) {
                        gatewaySyncEngine?.triggerGatewaySync()
                    }
                }

                _receivedMessages.update { currentList ->
                    if (currentList.any { it.messageId == msg.messageId }) {
                        currentList
                    } else {
                        currentList + msg
                    }
                }
            },
            onForwardToNeighbors = { msgToForward ->
                // Check if forwarded payload is an SosAlert and ensure it's saved locally before forwarding
                val forwardedSos = SosAlert.fromJson(msgToForward.payload)
                if (forwardedSos != null) {
                    storageManager.saveSosAlert(forwardedSos)
                    _sosAlerts.value = storageManager.getAllSosAlerts()
                }
                transport?.sendMessageToAllExcept(msgToForward.toJson(), sourceEndpointId ?: "")
            }
        )
    }

    fun broadcastSos(
        victimName: String,
        urgency: SosUrgency = SosUrgency.CRITICAL,
        emergencyType: String = "Medical Emergency",
        messageText: String,
        locationText: String = "Disaster Zone Sector B",
        lat: Double? = null,
        lng: Double? = null
    ): SosAlert {
        val sos = SosAlert(
            victimName = victimName,
            victimDeviceId = myDeviceId,
            urgency = urgency,
            emergencyType = emergencyType,
            message = messageText,
            locationText = locationText,
            latitude = lat,
            longitude = lng
        )

        storageManager.saveSosAlert(sos)
        _sosAlerts.value = storageManager.getAllSosAlerts()

        val sosJson = sos.toJson()
        sendMessage(sosJson)

        // Trigger immediate sync if gateway has internet
        gatewaySyncEngine?.triggerGatewaySync()

        return sos
    }

    fun stop() {
        transport?.stop()
        transport = null
        cloudTransport?.stop()
        cloudTransport = null
        gatewaySyncEngine?.stop()
        gatewaySyncEngine = null
        activeEndpoints.clear()
        _connectedPeers.value = emptySet()
        _relayStatus.value = "Disconnected"
    }

    fun sendMessage(payload: String, destinationId: String? = null) {
        val message = router.prepareOutgoingMessage(destinationId, payload)
        _receivedMessages.update { it + message }

        val jsonPayload = message.toJson()
        transport?.sendMessageToAll(jsonPayload)
        cloudTransport?.sendMessage(jsonPayload)
    }
}
