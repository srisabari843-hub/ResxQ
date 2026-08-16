package com.yourname.mychatapp.sos

import org.json.JSONObject

enum class SosUrgency {
    CRITICAL, // Trapped / Medical Emergency
    HIGH,     // Need Food / Water / Evacuation
    MEDIUM    // General Assistance
}

enum class SosRescueStatus {
    PENDING,
    DISPATCHED,
    RESCUED
}

data class SosAlert(
    val sosId: String = java.util.UUID.randomUUID().toString().take(8),
    val victimName: String,
    val victimDeviceId: String,
    val urgency: SosUrgency = SosUrgency.CRITICAL,
    val emergencyType: String = "Medical Emergency",
    val message: String,
    val locationText: String = "Unknown Location",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val batteryLevel: Int = 100,
    val timestamp: Long = System.currentTimeMillis(),
    var gatewayDeviceId: String? = null,
    var status: SosRescueStatus = SosRescueStatus.PENDING,
    var isSyncedToRescueTeam: Boolean = false
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("sosId", sosId)
        json.put("victimName", victimName)
        json.put("victimDeviceId", victimDeviceId)
        json.put("urgency", urgency.name)
        json.put("emergencyType", emergencyType)
        json.put("message", message)
        json.put("locationText", locationText)
        json.put("latitude", latitude ?: JSONObject.NULL)
        json.put("longitude", longitude ?: JSONObject.NULL)
        json.put("batteryLevel", batteryLevel)
        json.put("timestamp", timestamp)
        json.put("gatewayDeviceId", gatewayDeviceId ?: JSONObject.NULL)
        json.put("status", status.name)
        json.put("isSyncedToRescueTeam", isSyncedToRescueTeam)
        return json.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): SosAlert? {
            return try {
                val json = JSONObject(jsonStr)
                val lat = if (json.isNull("latitude")) null else json.getDouble("latitude")
                val lng = if (json.isNull("longitude")) null else json.getDouble("longitude")
                val gateway = if (json.isNull("gatewayDeviceId")) null else json.getString("gatewayDeviceId")

                SosAlert(
                    sosId = json.getString("sosId"),
                    victimName = json.getString("victimName"),
                    victimDeviceId = json.getString("victimDeviceId"),
                    urgency = try { SosUrgency.valueOf(json.getString("urgency")) } catch (e: Exception) { SosUrgency.CRITICAL },
                    emergencyType = json.optString("emergencyType", "General Help"),
                    message = json.getString("message"),
                    locationText = json.optString("locationText", "Unknown Location"),
                    latitude = lat,
                    longitude = lng,
                    batteryLevel = json.optInt("batteryLevel", 100),
                    timestamp = json.getLong("timestamp"),
                    gatewayDeviceId = gateway,
                    status = try { SosRescueStatus.valueOf(json.optString("status", "PENDING")) } catch (e: Exception) { SosRescueStatus.PENDING },
                    isSyncedToRescueTeam = json.optBoolean("isSyncedToRescueTeam", false)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
