package id.kampungnet.shared.crypto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PairingOffer(
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("sender_peer_id")
    val senderPeerId: String,
    @SerialName("sender_name")
    val senderName: String,
    @SerialName("identity_public_key")
    val identityPublicKey: String,
    @SerialName("agreement_public_key")
    val agreementPublicKey: String,
    val timestamp: Long
)

@Serializable
data class PairingAcceptance(
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("responder_peer_id")
    val responderPeerId: String,
    @SerialName("responder_name")
    val responderName: String,
    @SerialName("identity_public_key")
    val identityPublicKey: String,
    @SerialName("agreement_public_key")
    val agreementPublicKey: String,
    @SerialName("verification_code")
    val verificationCode: String,
    val timestamp: Long
)

@Serializable
data class PairingConfirmation(
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("peer_id")
    val peerId: String,
    @SerialName("key_id")
    val keyId: String,
    @SerialName("verification_code")
    val verificationCode: String,
    val timestamp: Long
)

enum class PairingState {
    OFFER_SENT,
    OFFER_RECEIVED,
    ACCEPTED,
    VERIFIED,
    REJECTED,
    EXPIRED
}
