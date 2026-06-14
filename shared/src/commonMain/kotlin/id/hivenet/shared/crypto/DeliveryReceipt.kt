package id.hivenet.shared.crypto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeliveryReceipt(
    @SerialName("message_id")
    val messageId: String,
    @SerialName("packet_id")
    val packetId: String,
    @SerialName("sender_peer_id")
    val senderPeerId: String,
    @SerialName("target_peer_id")
    val targetPeerId: String,
    val status: MessageStatus,
    val timestamp: Long
)
