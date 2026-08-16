package com.yourname.mychatapp.sos

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.io.File

class SosStorageManager(private val context: Context) {

    private val storageFile = File(context.filesDir, "offline_sos_alerts.json")
    private val sosAlertsMap = mutableMapOf<String, SosAlert>()

    init {
        loadFromDisk()
    }

    @Synchronized
    fun saveSosAlert(alert: SosAlert): Boolean {
        val isNew = !sosAlertsMap.containsKey(alert.sosId)
        sosAlertsMap[alert.sosId] = alert
        saveToDisk()
        Log.d("SosStorageManager", "Saved SOS Alert: ${alert.sosId} (New: $isNew)")
        return isNew
    }

    @Synchronized
    fun getAllSosAlerts(): List<SosAlert> {
        return sosAlertsMap.values.toList().sortedByDescending { it.timestamp }
    }

    @Synchronized
    fun getUnsyncedSosAlerts(): List<SosAlert> {
        return sosAlertsMap.values.filter { !it.isSyncedToRescueTeam }.sortedByDescending { it.timestamp }
    }

    @Synchronized
    fun markAsSynced(sosIds: List<String>, gatewayDeviceId: String) {
        for (id in sosIds) {
            sosAlertsMap[id]?.let { alert ->
                alert.isSyncedToRescueTeam = true
                alert.gatewayDeviceId = gatewayDeviceId
            }
        }
        saveToDisk()
        Log.d("SosStorageManager", "Marked ${sosIds.size} SOS alerts as synced via gateway $gatewayDeviceId")
    }

    private fun loadFromDisk() {
        if (!storageFile.exists()) return
        try {
            val content = storageFile.readText()
            if (content.isBlank()) return
            val jsonArray = JSONArray(content)
            sosAlertsMap.clear()
            for (i in 0 until jsonArray.length()) {
                val jsonObjStr = jsonArray.getJSONObject(i).toString()
                SosAlert.fromJson(jsonObjStr)?.let { alert ->
                    sosAlertsMap[alert.sosId] = alert
                }
            }
            Log.d("SosStorageManager", "Loaded ${sosAlertsMap.size} SOS alerts from disk")
        } catch (e: Exception) {
            Log.e("SosStorageManager", "Failed to load SOS alerts from disk", e)
        }
    }

    private fun saveToDisk() {
        try {
            val jsonArray = JSONArray()
            sosAlertsMap.values.forEach { alert ->
                jsonArray.put(org.json.JSONObject(alert.toJson()))
            }
            storageFile.writeText(jsonArray.toString())
        } catch (e: Exception) {
            Log.e("SosStorageManager", "Failed to save SOS alerts to disk", e)
        }
    }
}
