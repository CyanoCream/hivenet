package id.kampungnet.shared.mesh

import kotlinx.coroutines.flow.StateFlow

enum class MeshRuntimeState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    FAILED
}

enum class BackgroundRelayMode {
    FULL_WITH_FOREGROUND_SERVICE,
    LIMITED_BY_IOS,
    FOREGROUND_ONLY
}

enum class SecureStorageMode {
    ANDROID_KEYSTORE,
    IOS_KEYCHAIN,
    UNAVAILABLE
}

enum class MeshTransport {
    BLE,
    WIFI_LAN,
    MDNS,
    MULTIPEER_CONNECTIVITY
}

data class MeshCapabilities(
    val platformName: String,
    val backgroundRelay: BackgroundRelayMode,
    val secureStorage: SecureStorageMode,
    val transports: List<MeshTransport>,
    val platformWarning: String? = null
)

interface MeshRuntime {
    val state: StateFlow<MeshRuntimeState>
    val capabilities: MeshCapabilities
    suspend fun start()
    suspend fun stop()
}

expect fun createPlatformMeshRuntime(): MeshRuntime
