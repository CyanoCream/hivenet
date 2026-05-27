package id.kampungnet.shared.crypto

import id.kampungnet.shared.network.PeerIdentity

class UnavailableEndToEndCrypto : EndToEndCrypto {
    override suspend fun getOrCreateIdentity(): PeerIdentity = unavailable()

    override suspend fun createPairingOffer(contactPeerId: String, contactName: String): PairingOffer = unavailable()

    override suspend fun acceptPairingOffer(offer: PairingOffer): PairingAcceptance = unavailable()

    override suspend fun confirmPairing(confirmation: PairingConfirmation): ContactKey = unavailable()

    override suspend fun encryptForContact(
        contactPeerId: String,
        plaintext: ByteArray,
        associatedData: ByteArray
    ): EncryptedPayload = unavailable()

    override suspend fun decryptFromContact(
        contactPeerId: String,
        payload: EncryptedPayload,
        associatedData: ByteArray
    ): ByteArray = unavailable()

    private fun unavailable(): Nothing {
        error("End-to-end crypto provider is not installed for this platform yet")
    }
}
