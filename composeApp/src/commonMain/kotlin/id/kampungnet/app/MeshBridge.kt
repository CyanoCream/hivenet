package id.kampungnet.app

interface MeshBridge {
    fun start(localPeerId: String): MeshBridgeResult
    fun stop(): MeshBridgeResult
    fun status(): MeshBridgeResult
    fun peers(): MeshBridgeResult
    fun broadcast(envelope: String): MeshBridgeResult
    fun drainReceived(): MeshBridgeResult
}

data class MeshBridgeResult(
    val ok: Boolean,
    val value: String? = null,
    val error: String? = null,
)
