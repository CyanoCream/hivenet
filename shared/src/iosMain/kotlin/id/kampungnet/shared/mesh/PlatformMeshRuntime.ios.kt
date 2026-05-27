package id.kampungnet.shared.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual fun createPlatformMeshRuntime(): MeshRuntime = IosMeshRuntime()

class IosMeshRuntime : MeshRuntime {
    private val mutableState = MutableStateFlow(MeshRuntimeState.STOPPED)

    override val state: StateFlow<MeshRuntimeState> = mutableState

    override val capabilities: MeshCapabilities = MeshCapabilities(
        platformName = "iPhone",
        backgroundRelay = BackgroundRelayMode.LIMITED_BY_IOS,
        secureStorage = SecureStorageMode.IOS_KEYCHAIN,
        transports = listOf(MeshTransport.MULTIPEER_CONNECTIVITY, MeshTransport.BLE, MeshTransport.MDNS),
        platformWarning = "iOS membatasi relay background; pesan tetap disimpan dan diteruskan saat app aktif atau iOS memberi jatah background."
    )

    override suspend fun start() {
        mutableState.value = MeshRuntimeState.RUNNING
    }

    override suspend fun stop() {
        mutableState.value = MeshRuntimeState.STOPPED
    }
}
