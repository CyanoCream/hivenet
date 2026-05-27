package id.kampungnet.shared.crypto

import id.kampungnet.shared.network.PeerIdentity

interface EndToEndCrypto {
    suspend fun getOrCreateIdentity(): PeerIdentity
    suspend fun createPairingOffer(contactPeerId: String, contactName: String): PairingOffer
    suspend fun acceptPairingOffer(offer: PairingOffer): PairingAcceptance
    suspend fun confirmPairing(confirmation: PairingConfirmation): ContactKey
    suspend fun encryptForContact(contactPeerId: String, plaintext: ByteArray, associatedData: ByteArray): EncryptedPayload
    suspend fun decryptFromContact(contactPeerId: String, payload: EncryptedPayload, associatedData: ByteArray): ByteArray
}
