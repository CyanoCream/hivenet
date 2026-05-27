package id.kampungnet.shared.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual fun createPlatformMeshRuntime(): MeshRuntime = AndroidMeshRuntime()

class AndroidMeshRuntime : MeshRuntime {
    private val mutableState = MutableStateFlow(MeshRuntimeState.STOPPED)

    override val state: StateFlow<MeshRuntimeState> = mutableState

    override val capabilities: MeshCapabilities = MeshCapabilities(
        platformName = "Android",
        backgroundRelay = BackgroundRelayMode.FULL_WITH_FOREGROUND_SERVICE,
        secureStorage = SecureStorageMode.ANDROID_KEYSTORE,
        transports = listOf(MeshTransport.BLE, MeshTransport.WIFI_LAN, MeshTransport.MDNS),
        platformWarning = "Foreground service aktif diperlukan agar relay tetap hidup saat app di background."
    )

    override suspend fun start() {
        mutableState.value = MeshRuntimeState.RUNNING
    }

    override suspend fun stop() {
        mutableState.value = MeshRuntimeState.STOPPED
    }
}
