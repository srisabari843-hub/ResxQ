package com.yourname.mychatapp.sos

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.yourname.mychatapp.mesh.CloudTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class GatewaySyncEngine(
    private val context: Context,
    private val myDeviceId: String,
    private val storageManager: SosStorageManager,
    private val cloudTransport: CloudTransport
) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _isInternetConnected = MutableStateFlow(false)
    val isInternetConnected: StateFlow<Boolean> = _isInternetConnected.asStateFlow()

    private val _syncStatusText = MutableStateFlow("Offline Mesh Node")
    val syncStatusText: StateFlow<String> = _syncStatusText.asStateFlow()

    private var serverBaseUrl: String = "http://10.0.2.2:3000"

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d("GatewaySyncEngine", "🌐 Internet Network Detected! Device is now a Gateway Node.")
            _isInternetConnected.value = true
            _syncStatusText.value = "Internet Gateway Active 🌐"
            triggerGatewaySync()
        }

        override fun onLost(network: Network) {
            Log.d("GatewaySyncEngine", "Internet Lost. Device back to offline mesh mode.")
            _isInternetConnected.value = false
            _syncStatusText.value = "Offline Mesh Node 📱"
        }
    }

    fun start(serverUrl: String = "http://10.0.2.2:3000") {
        this.serverBaseUrl = serverUrl
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)

            // Initial check
            val activeNet = connectivityManager.activeNetwork
            val caps = connectivityManager.getNetworkCapabilities(activeNet)
            val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            _isInternetConnected.value = hasInternet
            _syncStatusText.value = if (hasInternet) "Internet Gateway Active 🌐" else "Offline Mesh Node 📱"

            if (hasInternet) {
                triggerGatewaySync()
            }
        } catch (e: Exception) {
            Log.e("GatewaySyncEngine", "Failed to register network callback", e)
        }
    }

    fun stop() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
    }

    fun triggerGatewaySync() {
        scope.launch {
            val unsynced = storageManager.getUnsyncedSosAlerts()
            if (unsynced.isEmpty()) {
                Log.d("GatewaySyncEngine", "No unsynced SOS alerts to upload.")
                return@launch
            }

            Log.d("GatewaySyncEngine", "Syncing ${unsynced.size} offline SOS alerts to Rescue Team Server...")
            _syncStatusText.value = "Uploading ${unsynced.size} SOS Alerts..."

            // Send via HTTP REST Sync Endpoint
            val success = performHttpSync(unsynced)
            if (success) {
                val syncedIds = unsynced.map { it.sosId }
                storageManager.markAsSynced(syncedIds, myDeviceId)
                _syncStatusText.value = "Synced ${syncedIds.size} SOS Alerts to Rescue Team ⚡"
            } else {
                // Fallback: try sending via CloudTransport WebSocket if active
                if (cloudTransport.isConnected()) {
                    unsynced.forEach { alert ->
                        alert.gatewayDeviceId = myDeviceId
                        cloudTransport.sendMessage(alert.toJson())
                    }
                    val syncedIds = unsynced.map { it.sosId }
                    storageManager.markAsSynced(syncedIds, myDeviceId)
                    _syncStatusText.value = "Synced ${syncedIds.size} SOS Alerts via WebSocket ⚡"
                } else {
                    _syncStatusText.value = "Sync Retry Pending..."
                }
            }
        }
    }

    private fun performHttpSync(alerts: List<SosAlert>): Boolean {
        val candidateUrls = listOf(
            "https://85be265029a6ad.lhr.life",
            serverBaseUrl,
            "http://10.10.67.92:3000",
            "http://localhost:3000",
            "http://10.0.2.2:3000"
        ).distinct()

        for (baseUrl in candidateUrls) {
            try {
                val target = if (baseUrl.endsWith("/")) "${baseUrl}api/sync-sos" else "$baseUrl/api/sync-sos"
                Log.d("GatewaySyncEngine", "Trying sync connection to $target ...")
                val url = URL(target)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.doOutput = true

                val jsonArray = JSONArray()
                alerts.forEach { alert ->
                    val jsonObj = JSONObject(alert.toJson())
                    jsonObj.put("gatewayDeviceId", myDeviceId)
                    jsonArray.put(jsonObj)
                }

                val rootObj = JSONObject()
                rootObj.put("gatewayDeviceId", myDeviceId)
                rootObj.put("alerts", jsonArray)

                val writer = OutputStreamWriter(conn.outputStream)
                writer.write(rootObj.toString())
                writer.flush()
                writer.close()

                val responseCode = conn.responseCode
                Log.d("GatewaySyncEngine", "HTTP Sync Response Code: $responseCode from $target")
                conn.disconnect()
                if (responseCode in 200..299) {
                    serverBaseUrl = baseUrl
                    return true
                }
            } catch (e: Exception) {
                Log.w("GatewaySyncEngine", "Sync attempt to $baseUrl failed: ${e.message}")
            }
        }
        return false
    }
}
