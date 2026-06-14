package id.hivenet.shared.sos

interface SOSRepository {
    suspend fun isBlacklisted(peerId: String): Boolean
    suspend fun blacklist(peerId: String, blockedAt: Long)
    suspend fun hasProcessed(packetId: String): Boolean
    suspend fun markProcessed(packetId: String, senderId: String, timestamp: Long)
    suspend fun getLastAlarmAt(senderId: String): Long?
    suspend fun markAlarmTriggered(senderId: String, timestamp: Long)
}
