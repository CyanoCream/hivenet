package id.hivenet.shared.crypto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EncryptedPayload(
    val version: Int = 1,
    val algorithm: String,
    @SerialName("key_id")
    val keyId: String,
    val nonce: String,
    val ciphertext: String,
    @SerialName("auth_tag")
    val authTag: String? = null,
    @SerialName("aad")
    val associatedData: String? = null
)

@Serializable
data class PlainChatPayload(
    @SerialName("message_id")
    val messageId: String,
    val body: String,
    val timestamp: Long
)

@Serializable
data class ContactKey(
    @SerialName("contact_peer_id")
    val contactPeerId: String,
    @SerialName("key_id")
    val keyId: String,
    val algorithm: String,
    @SerialName("created_at")
    val createdAt: Long,
    @SerialName("verified_at")
    val verifiedAt: Long? = null
)

enum class MessageStatus {
    QUEUED,
    BROADCASTED,
    RELAYED,
    DELIVERED,
    READ,
    EXPIRED,
    FAILED
}
