package id.kampungnet.shared.sos

import id.kampungnet.shared.time.Clock

class SOSProcessor(
    private val repository: SOSRepository,
    private val clock: Clock,
    private val cooldownMillis: Long = 15 * 60 * 1000L
) {
    suspend fun processIncomingSOS(packet: SOSPacket): SOSProcessResult {
        if (repository.isBlacklisted(packet.senderPeerId)) {
            return SOSProcessResult.DroppedBlacklisted
        }

        if (repository.hasProcessed(packet.packetId)) {
            return SOSProcessResult.DroppedDuplicate
        }

        repository.markProcessed(
            packetId = packet.packetId,
            senderId = packet.senderPeerId,
            timestamp = packet.timestamp
        )

        val forwardPacket = packet.copy(ttl = (packet.ttl - 1).coerceAtLeast(0))
        val shouldForward = forwardPacket.ttl > 0
        val now = clock.nowMillis()
        val lastAlarmAt = repository.getLastAlarmAt(packet.senderPeerId)
        val inCooldown = lastAlarmAt != null && now - lastAlarmAt < cooldownMillis

        if (inCooldown) {
            return SOSProcessResult.ForwardOnly(forwardPacket)
        }

        repository.markAlarmTriggered(packet.senderPeerId, now)

        return if (shouldForward) {
            SOSProcessResult.TriggerAlarmAndForward(forwardPacket)
        } else {
            SOSProcessResult.TriggerAlarmNoForward(packet)
        }
    }
}
