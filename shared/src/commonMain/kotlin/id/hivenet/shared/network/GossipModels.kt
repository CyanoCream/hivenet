package id.hivenet.shared.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class GossipTopic(val path: String) {
    SOS("/kampungnet/v1/sos"),
    CHAT("/kampungnet/v1/chat"),
    PAIRING("/kampungnet/v1/pairing"),
    RECEIPT("/kampungnet/v1/receipt")
}

@Serializable
enum class PacketType {
    CHAT,
    SOS,
    PAIRING_OFFER,
    PAIRING_ACCEPTANCE,
    PAIRING_CONFIRMATION,
    DELIVERY_RECEIPT
}

@Serializable
data class GossipPacket(
    @SerialName("packet_id")
    val packetId: String,
    @SerialName("packet_type")
    val packetType: PacketType,
    @SerialName("sender_peer_id")
    val senderPeerId: String,
    @SerialName("sender_name")
    val senderName: String,
    @SerialName("target_peer_id")
    val targetPeerId: String? = null,
    val timestamp: Long,
    val ttl: Int,
    val payload: String,
    val signature: String
)

data class IncomingGossipPacket(
    val topic: GossipTopic,
    val bytes: ByteArray,
    val sourcePeerId: String?
)

data class DiscoveredPeer(
    val peerId: String,
    val displayName: String?,
    val addresses: List<String>,
    val transport: DiscoveryTransportType,
    val lastSeenAtMillis: Long
)

enum class DiscoveryTransportType {
    MDNS,
    BLE,
    LIBP2P
}
