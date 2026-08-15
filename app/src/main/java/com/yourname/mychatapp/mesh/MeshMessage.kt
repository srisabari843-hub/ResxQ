package com.yourname.mychatapp.mesh

import org.json.JSONObject

data class MeshMessage(
    val messageId: String,
    val sourceDeviceId: String,
    val destinationDeviceId: String?,
    val payload: String,
    val timestamp: Long,
    val ttl: Int
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("messageId", messageId)
        json.put("sourceDeviceId", sourceDeviceId)
        json.put("destinationDeviceId", destinationDeviceId ?: JSONObject.NULL)
        json.put("payload", payload)
        json.put("timestamp", timestamp)
        json.put("ttl", ttl)
        return json.toString()
    }

    companion object {
        fun fromJson(jsonString: String): MeshMessage? {
            return try {
                val json = JSONObject(jsonString)
                val dest = if (json.isNull("destinationDeviceId")) null else json.getString("destinationDeviceId")
                MeshMessage(
                    messageId = json.getString("messageId"),
                    sourceDeviceId = json.getString("sourceDeviceId"),
                    destinationDeviceId = dest,
                    payload = json.getString("payload"),
                    timestamp = json.getLong("timestamp"),
                    ttl = json.getInt("ttl")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
