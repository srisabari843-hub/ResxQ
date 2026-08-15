package com.yourname.mychatapp.mesh

import android.util.Log

class MessageRouter(private val myDeviceId: String) {

    private val seenMessageIds = LinkedHashSet<String>()
    private val MAX_CACHE_SIZE = 1000

    fun processIncomingMessage(
        messageStr: String,
        onDeliverToApp: (MeshMessage) -> Unit,
        onForwardToNeighbors: (MeshMessage) -> Unit
    ) {
        val message = MeshMessage.fromJson(messageStr)
        if (message == null) {
            Log.e("MessageRouter", "Failed to parse message")
            return
        }

        // Duplicate Detection
        if (seenMessageIds.contains(message.messageId)) {
            Log.d("MessageRouter", "Duplicate message received, dropping: ${message.messageId}")
            return
        }

        // Add to seen
        seenMessageIds.add(message.messageId)
        if (seenMessageIds.size > MAX_CACHE_SIZE) {
            val iterator = seenMessageIds.iterator()
            iterator.next()
            iterator.remove()
        }

        // Is this message for me?
        if (message.destinationDeviceId == myDeviceId || message.destinationDeviceId == null) {
            Log.d("MessageRouter", "Message delivered to this device")
            onDeliverToApp(message)
        }

        // TTL and Forwarding
        if (message.destinationDeviceId != myDeviceId && message.ttl > 0) {
            val forwardedMessage = message.copy(ttl = message.ttl - 1)
            Log.d("MessageRouter", "Forwarding message: ${message.messageId}, TTL remaining: ${forwardedMessage.ttl}")
            onForwardToNeighbors(forwardedMessage)
        }
    }

    fun prepareOutgoingMessage(
        destinationId: String?,
        payload: String
    ): MeshMessage {
        val messageId = java.util.UUID.randomUUID().toString()
        val message = MeshMessage(
            messageId = messageId,
            sourceDeviceId = myDeviceId,
            destinationDeviceId = destinationId,
            payload = payload,
            timestamp = System.currentTimeMillis(),
            ttl = 10
        )
        // Add to our seen cache so we don't reflect our own message if it comes back
        seenMessageIds.add(messageId)
        return message
    }
}
