package id.hivenet.shared.network

import kotlinx.coroutines.flow.Flow

interface PeerDiscovery {
    val peers: Flow<DiscoveredPeer>
    suspend fun start()
    suspend fun stop()
}

expect class MdnsDiscovery(
    localPeerId: String,
    serviceName: String = "_hivenet._tcp."
) : PeerDiscovery

expect class BleDiscovery(
    localPeerId: String,
    serviceUuid: String
) : PeerDiscovery

interface GossipTransport {
    val incomingPackets: Flow<IncomingGossipPacket>
    suspend fun start()
    suspend fun stop()
    suspend fun subscribe(topic: GossipTopic)
    suspend fun publish(topic: GossipTopic, bytes: ByteArray)
}

interface PacketStore {
    suspend fun hasProcessed(packetId: String): Boolean
    suspend fun markProcessed(packetId: String, timestampMillis: Long)
    suspend fun saveForLocalDelivery(packet: GossipPacket)
    suspend fun cacheForForwarding(packet: GossipPacket)
}

interface PacketVerifier {
    suspend fun verify(packet: GossipPacket): Boolean
}
