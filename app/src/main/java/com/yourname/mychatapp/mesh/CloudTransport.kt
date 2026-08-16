package com.yourname.mychatapp.mesh

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URISyntaxException

class CloudTransport(
    private val myDeviceId: String,
    private val onMessageReceived: (messageStr: String) -> Unit,
    private val onStatusChanged: (isConnected: Boolean, statusText: String) -> Unit
) {
    private var socket: Socket? = null
    private var isConnected = false

    fun start(serverUrl: String = "http://10.0.2.2:3000", displayName: String = "Android Device") {
        try {
            if (socket != null && socket!!.connected()) {
                return
            }

            val opts = IO.Options().apply {
                forceNew = true
                reconnection = true
                timeout = 10000
            }

            socket = IO.socket(serverUrl, opts).apply {
                on(Socket.EVENT_CONNECT) {
                    isConnected = true
                    Log.d("CloudTransport", "Connected to relay server: $serverUrl")
                    onStatusChanged(true, "Relay Connected")

                    // Register with relay server
                    val registerObj = JSONObject().apply {
                        put("deviceId", myDeviceId)
                        put("displayName", displayName)
                        put("clientType", "android")
                    }
                    emit("register_peer", registerObj)
                }

                on(Socket.EVENT_DISCONNECT) {
                    isConnected = false
                    Log.d("CloudTransport", "Disconnected from relay server")
                    onStatusChanged(false, "Relay Disconnected")
                }

                on(Socket.EVENT_CONNECT_ERROR) { args ->
                    val errorMsg = args.firstOrNull()?.toString() ?: "Connection error"
                    Log.e("CloudTransport", "Relay Connect Error: $errorMsg")
                    onStatusChanged(false, "Relay Error: $errorMsg")
                }

                on("receive_mesh_message") { args ->
                    if (args.isNotEmpty()) {
                        val payload = args[0].toString()
                        Log.d("CloudTransport", "Received message via Cloud Relay: $payload")
                        onMessageReceived(payload)
                    }
                }

                on("mesh_payload") { args ->
                    if (args.isNotEmpty()) {
                        val payload = args[0].toString()
                        onMessageReceived(payload)
                    }
                }

                connect()
            }
        } catch (e: URISyntaxException) {
            Log.e("CloudTransport", "Invalid server URL: $serverUrl", e)
            onStatusChanged(false, "Invalid URL")
        } catch (e: Exception) {
            Log.e("CloudTransport", "Error starting CloudTransport", e)
            onStatusChanged(false, "Connection Failed")
        }
    }

    fun stop() {
        socket?.disconnect()
        socket?.off()
        socket = null
        isConnected = false
        onStatusChanged(false, "Relay Stopped")
    }

    fun sendMessage(jsonPayload: String) {
        if (socket != null && isConnected) {
            try {
                // Try parsing as JSON object to send structured socket event
                val jsonObj = JSONObject(jsonPayload)
                socket?.emit("send_mesh_message", jsonObj)
            } catch (e: Exception) {
                // Fallback to sending raw string
                socket?.emit("mesh_payload", jsonPayload)
            }
        }
    }

    fun isConnected(): Boolean = isConnected
}
