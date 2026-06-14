package id.hivenet.shared.crypto

import id.hivenet.shared.network.GossipPacket
import id.hivenet.shared.network.PacketType
import kotlinx.serialization.json.Json

class EncryptedChatCodec(
    private val localPeerId: String,
    private val localName: String,
    private val crypto: EndToEndCrypto,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
) {
    suspend fun createDirectMessagePacket(
        packetId: String,
        messageId: String,
        targetPeerId: String,
        body: String,
        timestamp: Long,
        ttl: Int = 5
    ): GossipPacket {
        val plain = PlainChatPayload(
            messageId = messageId,
            body = body,
            timestamp = timestamp
        )
        val associatedData = associatedData(packetId, localPeerId, targetPeerId, timestamp)
        val encrypted = crypto.encryptForContact(
            contactPeerId = targetPeerId,
            plaintext = json.encodeToString(PlainChatPayload.serializer(), plain).encodeToByteArray(),
            associatedData = associatedData
        )

        return GossipPacket(
            packetId = packetId,
            packetType = PacketType.CHAT,
            senderPeerId = localPeerId,
            senderName = localName,
            targetPeerId = targetPeerId,
            timestamp = timestamp,
            ttl = ttl,
            payload = json.encodeToString(EncryptedPayload.serializer(), encrypted),
            signature = ""
        )
    }

    suspend fun decryptDirectMessagePacket(packet: GossipPacket): PlainChatPayload? {
        if (packet.packetType != PacketType.CHAT || packet.targetPeerId != localPeerId) return null
        val encrypted = runCatching {
            json.decodeFromString(EncryptedPayload.serializer(), packet.payload)
        }.getOrNull() ?: return null
        val plaintext = crypto.decryptFromContact(
            contactPeerId = packet.senderPeerId,
            payload = encrypted,
            associatedData = associatedData(packet.packetId, packet.senderPeerId, localPeerId, packet.timestamp)
        )
        return runCatching {
            json.decodeFromString(PlainChatPayload.serializer(), plaintext.decodeToString())
        }.getOrNull()
    }

    private fun associatedData(packetId: String, senderPeerId: String, targetPeerId: String, timestamp: Long): ByteArray {
        return "$packetId|$senderPeerId|$targetPeerId|$timestamp".encodeToByteArray()
    }
}
