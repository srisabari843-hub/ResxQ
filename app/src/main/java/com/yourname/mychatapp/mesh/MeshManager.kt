package com.yourname.mychatapp.mesh

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MeshManager(private val context: Context) {
    @SuppressLint("HardwareIds")
    val myDeviceId: String = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: Build.MODEL

    private val router = MessageRouter(myDeviceId)
    private var transport: NearbyTransport? = null

    private val activeEndpoints = mutableMapOf<String, String>() // endpointId -> deviceId
    private val _connectedPeers = MutableStateFlow<Set<String>>(emptySet())
    val connectedPeers: StateFlow<Set<String>> = _connectedPeers.asStateFlow()

    private val _receivedMessages = MutableStateFlow<List<MeshMessage>>(emptyList())
    val receivedMessages: StateFlow<List<MeshMessage>> = _receivedMessages.asStateFlow()

    fun start() {
        if (transport != null) return

        transport = NearbyTransport(
            context = context,
            myDeviceId = myDeviceId,
            onMessageReceived = { senderEndpointId, messageStr ->
                router.processIncomingMessage(
                    messageStr = messageStr,
                    onDeliverToApp = { msg ->
                        _receivedMessages.update { currentList ->
                            currentList + msg
                        }
                    },
                    onForwardToNeighbors = { msgToForward ->
                        transport?.sendMessageToAllExcept(msgToForward.toJson(), senderEndpointId)
                    }
                )
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
    }

    fun stop() {
        transport?.stop()
        transport = null
        activeEndpoints.clear()
        _connectedPeers.value = emptySet()
    }

    fun sendMessage(payload: String, destinationId: String? = null) {
        val message = router.prepareOutgoingMessage(destinationId, payload)
        _receivedMessages.update { it + message }
        transport?.sendMessageToAll(message.toJson())
    }
}
