package id.hivenet.shared.crypto

import id.hivenet.shared.network.GossipPacket
import id.hivenet.shared.network.PacketType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

class PairingPacketCodec(
    private val localPeerId: String,
    private val localName: String,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
) {
    fun offerPacket(packetId: String, offer: PairingOffer, ttl: Int = 3): GossipPacket {
        return pairingPacket(packetId, PacketType.PAIRING_OFFER, offer.senderPeerId, null, offer.timestamp, ttl, PairingOffer.serializer(), offer)
    }

    fun acceptancePacket(packetId: String, acceptance: PairingAcceptance, targetPeerId: String, ttl: Int = 3): GossipPacket {
        return pairingPacket(packetId, PacketType.PAIRING_ACCEPTANCE, acceptance.responderPeerId, targetPeerId, acceptance.timestamp, ttl, PairingAcceptance.serializer(), acceptance)
    }

    fun confirmationPacket(packetId: String, confirmation: PairingConfirmation, targetPeerId: String, ttl: Int = 3): GossipPacket {
        return pairingPacket(packetId, PacketType.PAIRING_CONFIRMATION, confirmation.peerId, targetPeerId, confirmation.timestamp, ttl, PairingConfirmation.serializer(), confirmation)
    }

    private fun <T> pairingPacket(
        packetId: String,
        type: PacketType,
        senderPeerId: String,
        targetPeerId: String?,
        timestamp: Long,
        ttl: Int,
        serializer: KSerializer<T>,
        payload: T
    ): GossipPacket {
        return GossipPacket(
            packetId = packetId,
            packetType = type,
            senderPeerId = senderPeerId.ifBlank { localPeerId },
            senderName = localName,
            targetPeerId = targetPeerId,
            timestamp = timestamp,
            ttl = ttl,
            payload = json.encodeToString(serializer, payload),
            signature = ""
        )
    }
}
