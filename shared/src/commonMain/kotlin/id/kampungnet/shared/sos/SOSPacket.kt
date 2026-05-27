package id.kampungnet.shared.sos

data class SOSPacket(
    val packetId: String,
    val senderPeerId: String,
    val senderName: String,
    val timestamp: Long,
    val ttl: Int,
    val message: String,
    val signature: String
)

sealed interface SOSProcessResult {
    data object DroppedBlacklisted : SOSProcessResult
    data object DroppedDuplicate : SOSProcessResult
    data class ForwardOnly(val packet: SOSPacket) : SOSProcessResult
    data class TriggerAlarmAndForward(val packet: SOSPacket) : SOSProcessResult
    data class TriggerAlarmNoForward(val packet: SOSPacket) : SOSProcessResult
}
