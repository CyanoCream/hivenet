package id.hivenet.shared.network

data class PeerIdentity(
    val peerId: String,
    val publicKeyBase58: String
)

interface CryptoProvider {
    suspend fun getOrCreateIdentity(): PeerIdentity
    suspend fun sign(bytes: ByteArray): ByteArray
    suspend fun verify(publicKeyBase58: String, message: ByteArray, signature: ByteArray): Boolean
}
