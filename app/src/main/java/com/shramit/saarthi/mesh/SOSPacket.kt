package com.shramit.saarthi.mesh

import java.nio.ByteBuffer
import java.util.*

/**
 * SOS Packet for BLE mesh transmission.
 * Aligned with BitChat's 13-byte fixed header protocol.
 *
 * Binary Format:
 * [Header - 13 Bytes]
 *   [0]      : Type (1B)
 *   [1]      : TTL (1B)
 *   [2..5]   : Message Hash (4B)
 *   [6..12]  : Sender ID Fragment (7B)
 * [Payload - Variable]
 *   [13..20] : Latitude (8B)
 *   [21..28] : Longitude (8B)
 *   [29..36] : Timestamp (8B)
 *   [37]     : isAcknowledgement (1B)
 *   [38..]   : Hop Path (Variable)
 */
data class SOSPacket(
    val messageId: String = UUID.randomUUID().toString(),
    val type: Byte = TYPE_SOS_ALERT,
    val senderId: String,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val ttl: Int = DEFAULT_TTL,
    val hopPath: MutableList<String> = mutableListOf(),
    val isAcknowledgement: Boolean = false
) {
    companion object {
        const val TYPE_SOS_ALERT: Byte = 0x01
        const val TYPE_SOS_ACK: Byte = 0x02
        const val TYPE_HEARTBEAT: Byte = 0x03
        const val TYPE_POLICE_BEACON: Byte = 0x04
        
        const val HEADER_SIZE = 13
        const val SENDER_FRAGMENT_SIZE = 7
        const val DEFAULT_TTL = 5
        const val MAX_TTL = 7

        /**
         * Deserialize an SOSPacket from bytes received over BLE.
         */
        fun fromBytes(data: ByteArray): SOSPacket? {
            if (data.size < HEADER_SIZE) return null
            
            return try {
                val buffer = ByteBuffer.wrap(data)

                // 1. Read Header (13 bytes)
                val type = buffer.get()
                val ttl = buffer.get().toInt()
                val msgHash = buffer.getInt()
                val senderFrag = ByteArray(SENDER_FRAGMENT_SIZE)
                buffer.get(senderFrag)

                // 2. Read Payload
                if (buffer.remaining() < 25) return null // lat(8) + lon(8) + time(8) + ack(1)
                
                val latitude = buffer.getDouble()
                val longitude = buffer.getDouble()
                val timestamp = buffer.getLong()
                val isAck = buffer.get() == 1.toByte()

                // 3. Read extra paths if they exist
                val hopPath = mutableListOf<String>()
                while (buffer.remaining() >= 4) {
                    val hopLen = buffer.getInt()
                    if (buffer.remaining() < hopLen) break
                    val hopBytes = ByteArray(hopLen)
                    buffer.get(hopBytes)
                    hopPath.add(String(hopBytes, Charsets.UTF_8))
                }

                // Since we used hashes for headers, we use a placeholder or partial ID 
                // In a real BitChat app, the full mapping is discovered or implied.
                // For Saarthi, we'll prefix our fragments so we can still identify types.
                val derivedSenderId = String(senderFrag).trim()

                SOSPacket(
                    messageId = msgHash.toString(),
                    type = type,
                    senderId = derivedSenderId,
                    latitude = latitude,
                    longitude = longitude,
                    timestamp = timestamp,
                    ttl = ttl,
                    hopPath = hopPath,
                    isAcknowledgement = isAck
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Serialize this SOSPacket to bytes for BLE transmission.
     */
    fun toBytes(): ByteArray {
        val senderBytes = senderId.toByteArray(Charsets.UTF_8)
        val senderFrag = ByteArray(SENDER_FRAGMENT_SIZE)
        System.arraycopy(senderBytes, 0, senderFrag, 0, Math.min(senderBytes.size, SENDER_FRAGMENT_SIZE))

        val pathData = hopPath.map { it.toByteArray(Charsets.UTF_8) }
        val pathSize = pathData.sumOf { 4 + it.size }

        val totalSize = HEADER_SIZE + 8 + 8 + 8 + 1 + pathSize
        val buffer = ByteBuffer.allocate(totalSize)

        // 1. Header (13 Bytes)
        buffer.put(type)
        buffer.put(ttl.toByte())
        buffer.putInt(messageId.hashCode())
        buffer.put(senderFrag)

        // 2. Payload
        buffer.putDouble(latitude)
        buffer.putDouble(longitude)
        buffer.putLong(timestamp)
        buffer.put(if (isAcknowledgement) 1.toByte() else 0.toByte())

        // 3. Hop Path
        for (hop in pathData) {
            buffer.putInt(hop.size)
            buffer.put(hop)
        }

        return buffer.array()
    }

    fun createRelayPacket(relayNodeId: String): SOSPacket? {
        if (ttl <= 0) return null

        val newPath = hopPath.toMutableList()
        newPath.add(relayNodeId)

        return copy(
            ttl = ttl - 1,
            hopPath = newPath
        )
    }

    val hopCount: Int get() = hopPath.size
}
