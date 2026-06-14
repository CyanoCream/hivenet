package id.hivenet.shared.network

import id.hivenet.shared.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class GossipRouter(
    private val localPeerId: String,
    private val transport: GossipTransport,
    private val packetStore: PacketStore,
    private val packetVerifier: PacketVerifier,
    private val clock: Clock,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
) {
    private var job: Job? = null

    suspend fun start(scope: CoroutineScope) {
        transport.start()
        transport.subscribe(GossipTopic.SOS)
        transport.subscribe(GossipTopic.CHAT)
        transport.subscribe(GossipTopic.PAIRING)
        transport.subscribe(GossipTopic.RECEIPT)

        job = scope.launch {
            transport.incomingPackets.collect { incoming ->
                processIncoming(incoming)
            }
        }
    }

    suspend fun stop() {
        job?.cancel()
        job = null
        transport.stop()
    }

    suspend fun publish(packet: GossipPacket) {
        transport.publish(topicFor(packet), encode(packet))
    }

    private suspend fun processIncoming(incoming: IncomingGossipPacket) {
        val packet = decode(incoming.bytes) ?: return
        if (packet.ttl <= 0) return
        if (packetStore.hasProcessed(packet.packetId)) return
        if (!packetVerifier.verify(packet)) return

        packetStore.markProcessed(packet.packetId, clock.nowMillis())

        if (packet.targetPeerId == null || packet.targetPeerId == localPeerId) {
            packetStore.saveForLocalDelivery(packet)
        } else {
            packetStore.cacheForForwarding(packet)
        }

        val forwarded = packet.copy(ttl = packet.ttl - 1)
        if (forwarded.ttl > 0) {
            transport.publish(topicFor(forwarded), encode(forwarded))
        }
    }

    private fun encode(packet: GossipPacket): ByteArray {
        return json.encodeToString(GossipPacket.serializer(), packet).encodeToByteArray()
    }

    private fun decode(bytes: ByteArray): GossipPacket? {
        return try {
            json.decodeFromString(GossipPacket.serializer(), bytes.decodeToString())
        } catch (_: Throwable) {
            null
        }
    }

    private fun topicFor(packet: GossipPacket): GossipTopic {
        return when (packet.packetType) {
            PacketType.SOS -> GossipTopic.SOS
            PacketType.CHAT -> GossipTopic.CHAT
            PacketType.PAIRING_OFFER,
            PacketType.PAIRING_ACCEPTANCE,
            PacketType.PAIRING_CONFIRMATION -> GossipTopic.PAIRING
            PacketType.DELIVERY_RECEIPT -> GossipTopic.RECEIPT
        }
    }
}
