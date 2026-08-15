package com.yourname.mychatapp.mesh

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy

class NearbyTransport(
    private val context: Context,
    private val myDeviceId: String,
    private val onMessageReceived: (String, String) -> Unit, // senderEndpointId, messageStr
    private val onEndpointConnected: (String, String) -> Unit, // endpointId, deviceId
    private val onEndpointDisconnected: (String, String) -> Unit // endpointId, deviceId
) {
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val STRATEGY = Strategy.P2P_CLUSTER
    private val SERVICE_ID = "com.yourname.mychatapp.mesh.SERVICE"

    private val connectedEndpoints = mutableSetOf<String>()
    private val endpointToDeviceId = mutableMapOf<String, String>()

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val bytes = payload.asBytes()
                if (bytes != null) {
                    val messageStr = String(bytes, Charsets.UTF_8)
                    onMessageReceived(endpointId, messageStr)
                }
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Log.d("NearbyTransport", "Connection initiated with: " + endpointId + " (" + connectionInfo.endpointName + ")")
            endpointToDeviceId[endpointId] = connectionInfo.endpointName
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                Log.d("NearbyTransport", "Connected to: " + endpointId)
                connectedEndpoints.add(endpointId)
                val deviceId = endpointToDeviceId[endpointId] ?: endpointId
                onEndpointConnected(endpointId, deviceId)
            } else {
                Log.d("NearbyTransport", "Connection failed with " + endpointId)
                endpointToDeviceId.remove(endpointId)
            }
        }
        override fun onDisconnected(endpointId: String) {
            Log.d("NearbyTransport", "Disconnected from: " + endpointId)
            connectedEndpoints.remove(endpointId)
            val deviceId = endpointToDeviceId.remove(endpointId) ?: endpointId
            onEndpointDisconnected(endpointId, deviceId)
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d("NearbyTransport", "Endpoint found: " + endpointId + " (" + info.endpointName + "). Requesting connection.")
            connectionsClient.requestConnection(myDeviceId, endpointId, connectionLifecycleCallback)
                .addOnSuccessListener { Log.d("NearbyTransport", "Connection requested to " + endpointId) }
                .addOnFailureListener { Log.e("NearbyTransport", "Failed to request connection to " + endpointId, it) }
        }
        override fun onEndpointLost(endpointId: String) {
            Log.d("NearbyTransport", "Endpoint lost: " + endpointId)
        }
    }

    fun start() {
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(
            myDeviceId, SERVICE_ID, connectionLifecycleCallback, advertisingOptions
        ).addOnSuccessListener { Log.d("NearbyTransport", "Advertising started") }
         .addOnFailureListener { Log.e("NearbyTransport", "Advertising failed", it) }

        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(
            SERVICE_ID, endpointDiscoveryCallback, discoveryOptions
        ).addOnSuccessListener { Log.d("NearbyTransport", "Discovery started") }
         .addOnFailureListener { Log.e("NearbyTransport", "Discovery failed", it) }
    }

    fun stop() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
        endpointToDeviceId.clear()
    }

    fun sendMessageToAll(messageStr: String) {
        if (connectedEndpoints.isEmpty()) return
        val payload = Payload.fromBytes(messageStr.toByteArray(Charsets.UTF_8))
        connectionsClient.sendPayload(connectedEndpoints.toList(), payload)
    }

    fun sendMessageToAllExcept(messageStr: String, excludeEndpointId: String) {
        val targets = connectedEndpoints.filter { it != excludeEndpointId }
        if (targets.isEmpty()) return
        val payload = Payload.fromBytes(messageStr.toByteArray(Charsets.UTF_8))
        connectionsClient.sendPayload(targets, payload)
    }
}
