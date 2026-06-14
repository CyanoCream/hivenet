package id.hivenet.app

interface CryptoBridge {
    fun getOrCreateIdentityPublicKey(): CryptoBridgeResult
    fun createPairingOffer(contactPeerId: String, senderPeerId: String, senderName: String): CryptoBridgeResult
    fun acceptPairingOffer(offerJson: String, responderPeerId: String, responderName: String): CryptoBridgeResult
    fun completePairing(acceptanceJson: String, localPeerId: String): CryptoBridgeResult
    fun encrypt(contactPeerId: String, plaintextBase64: String, associatedDataBase64: String): CryptoBridgeResult
    fun decrypt(contactPeerId: String, payloadJson: String, associatedDataBase64: String): CryptoBridgeResult
}

data class CryptoBridgeResult(
    val ok: Boolean,
    val value: String? = null,
    val error: String? = null
)
