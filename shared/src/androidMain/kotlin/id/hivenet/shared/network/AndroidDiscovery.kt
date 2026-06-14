package id.hivenet.shared.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

actual class MdnsDiscovery actual constructor(
    localPeerId: String,
    serviceName: String
) : PeerDiscovery {
    override val peers: Flow<DiscoveredPeer> = emptyFlow()

    override suspend fun start() = Unit

    override suspend fun stop() = Unit
}

actual class BleDiscovery actual constructor(
    localPeerId: String,
    serviceUuid: String
) : PeerDiscovery {
    override val peers: Flow<DiscoveredPeer> = emptyFlow()

    override suspend fun start() = Unit

    override suspend fun stop() = Unit
}
