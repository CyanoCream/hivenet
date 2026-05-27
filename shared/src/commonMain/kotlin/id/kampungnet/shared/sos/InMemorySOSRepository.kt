package id.kampungnet.shared.sos

class InMemorySOSRepository : SOSRepository {
    private val blacklist = mutableMapOf<String, Long>()
    private val processed = mutableMapOf<String, Pair<String, Long>>()
    private val cooldowns = mutableMapOf<String, Long>()

    override suspend fun isBlacklisted(peerId: String): Boolean = blacklist.containsKey(peerId)

    override suspend fun blacklist(peerId: String, blockedAt: Long) {
        blacklist[peerId] = blockedAt
    }

    override suspend fun hasProcessed(packetId: String): Boolean = processed.containsKey(packetId)

    override suspend fun markProcessed(packetId: String, senderId: String, timestamp: Long) {
        processed[packetId] = senderId to timestamp
    }

    override suspend fun getLastAlarmAt(senderId: String): Long? = cooldowns[senderId]

    override suspend fun markAlarmTriggered(senderId: String, timestamp: Long) {
        cooldowns[senderId] = timestamp
    }
}
