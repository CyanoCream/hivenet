package id.kampungnet.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.kampungnet.shared.chat.ChatMessageItem
import id.kampungnet.shared.chat.ContactItem
import id.kampungnet.shared.chat.EncryptedChatRepository
import id.kampungnet.shared.db.createKampungNetDatabase
import id.kampungnet.shared.time.SystemClock
import kotlinx.coroutines.delay
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val BROADCAST_ID = "kampung-broadcast"
private const val ME_ID = "me"
private const val MAX_MESH_ENVELOPE_BYTES = 48_000

private val AppBg = Color(0xFFF4F6FB)
private val CardBg = Color.White
private val Primary = Color(0xFF2563EB)
private val Ink = Color(0xFF111827)
private val Muted = Color(0xFF6B7280)
private val Danger = Color(0xFFE11D48)
private val Village = Color(0xFF8A5A22)

private enum class Screen { Home, NewChoice, NewChat, NewGroup, Chat, GroupInfo, CryptoDebug, PairContact, EncryptedChatList, EncryptedChat, MeshDebug }
private enum class RoomTab { Chat, Sos, Ht }

private data class Peer(val id: String, val name: String, val peerId: String)
private data class Member(val id: String, val name: String, val peerId: String, val admin: Boolean = false)
private data class ChatThread(
    val id: String,
    val name: String,
    val peerId: String,
    val group: Boolean,
    val blacklisted: Boolean = false,
    val members: List<Member> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val htMessages: List<HtMessage> = emptyList()
)
private data class ChatMessage(val id: Int, val sender: String, val body: String, val time: String, val outgoing: Boolean, val status: String)
private data class HtMessage(val id: Int, val sender: String, val duration: Int, val time: String, val outgoing: Boolean)
private data class SOSOverlayState(val sender: String, val peerId: String, val message: String)

@OptIn(ExperimentalEncodingApi::class)
private fun String.toBase64(): String = Base64.encode(encodeToByteArray())

@OptIn(ExperimentalEncodingApi::class)
private fun String.fromBase64OrNull(): String? = runCatching { Base64.decode(this).decodeToString() }.getOrNull()

private fun encodePairingEnvelope(type: String, json: String): String = "KNET1:$type:${json.toBase64()}"

private fun encodeChatEnvelope(json: String): String = "KNET1:CHAT:${json.toBase64()}"

private fun encodeReceiptEnvelope(json: String): String = "KNET1:RECEIPT:${json.toBase64()}"

private fun decodePairingEnvelope(input: String, expectedType: String): String? {
    val parts = input.trim().split(":", limit = 3)
    if (parts.size != 3 || parts[0] != "KNET1" || parts[1] != expectedType) return null
    return parts[2].fromBase64OrNull()
}

private fun decodeChatEnvelope(input: String): String? = decodePairingEnvelope(input, "CHAT")

private fun decodeReceiptEnvelope(input: String): String? = decodePairingEnvelope(input, "RECEIPT")

private fun extractJsonValue(json: String, key: String): String? {
    val pattern = Regex("\\\"$key\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
    return pattern.find(json)?.groupValues?.getOrNull(1)
}

private fun extractJsonLong(json: String, key: String): Long? {
    val pattern = Regex("\\\"$key\\\"\\s*:\\s*(-?\\d+)")
    return pattern.find(json)?.groupValues?.getOrNull(1)?.toLongOrNull()
}

private fun jsonEscape(value: String): String = buildString {
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(char)
        }
    }
}

private fun chatPacketJson(packetId: String, encryptedPayloadJson: String): String =
    "{\"packet_id\":\"${jsonEscape(packetId)}\",\"payload\":$encryptedPayloadJson}"

private fun chatPacketJson(packetId: String, sourcePeerId: String, targetPeerId: String?, encryptedPayloadJson: String): String = buildString {
    append("{\"packet_id\":\"")
    append(jsonEscape(packetId))
    append("\",\"source_peer_id\":\"")
    append(jsonEscape(sourcePeerId))
    append("\"")
    if (!targetPeerId.isNullOrBlank()) {
        append(",\"target_peer_id\":\"")
        append(jsonEscape(targetPeerId))
        append("\"")
    }
    append(",\"payload\":")
    append(encryptedPayloadJson)
    append("}")
}

private fun chatPacketId(json: String): String? = extractJsonValue(json, "packet_id")

private fun chatPacketSourcePeerId(json: String): String? = extractJsonValue(json, "source_peer_id")

private fun chatPacketTargetPeerId(json: String): String? = extractJsonValue(json, "target_peer_id")

private fun chatPacketPayloadJson(json: String): String {
    val marker = "\"payload\":"
    val start = json.indexOf(marker)
    if (start < 0 || chatPacketId(json) == null) return json
    val payloadStart = start + marker.length
    val payloadEnd = json.lastIndexOf('}')
    return if (payloadEnd > payloadStart) json.substring(payloadStart, payloadEnd).trim() else json
}

private fun shortPeerId(peerId: String): String = when {
    peerId.length <= 12 -> peerId
    else -> "${peerId.take(6)}...${peerId.takeLast(4)}"
}

private fun formatClockTime(epochMillis: Long): String {
    val totalMinutes = (epochMillis / 60_000L).mod(24L * 60L)
    val hour = totalMinutes / 60L
    val minute = totalMinutes % 60L
    return "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
}

@Composable
fun KampungNetApp(
    cryptoBridge: CryptoBridge? = null,
    meshBridge: MeshBridge? = null,
    chatRepository: EncryptedChatRepository? = null,
) {
    val encryptedChatRepository = chatRepository ?: remember {
        runCatching { EncryptedChatRepository(createKampungNetDatabase()) }.getOrNull()
    }
    val peers = remember {
        listOf(
            Peer("pak-rt", "Pak RT", "12D3KooWRt"),
            Peer("pos-ronda", "Pos Ronda", "9xQmNeru72"),
            Peer("ibu-sari", "Ibu Sari", "7aBLeuP44k"),
            Peer("karang-taruna", "Karang Taruna", "8mQTaruna22")
        )
    }
    var screen by remember { mutableStateOf(Screen.Home) }
    var selectedThreadId by remember { mutableStateOf<String?>(null) }
    var selectedEncryptedPeerId by remember { mutableStateOf<String?>(null) }
    var roomTab by remember { mutableStateOf(RoomTab.Chat) }
    var showSosSheet by remember { mutableStateOf(false) }
    var sosOverlay by remember { mutableStateOf<SOSOverlayState?>(null) }
    var nextThreadId by remember { mutableStateOf(1) }
    var nextMessageId by remember { mutableStateOf(100) }
    var nextHtId by remember { mutableStateOf(1) }
    val threads = remember {
        mutableStateListOf(
            ChatThread(
                id = BROADCAST_ID,
                name = "Kampung Broadcast",
                peerId = "/kampungnet/v1/chat",
                group = true,
                members = listOf(
                    Member(ME_ID, "Saya", "local-device", true),
                    Member("pak-rt", "Pak RT", "12D3KooWRt", true),
                    Member("pos-ronda", "Pos Ronda", "9xQmNeru72")
                ),
                messages = listOf(
                    ChatMessage(1, "Pak RT", "Router mesh aktif di balai warga.", "09:41", false, "relayed"),
                    ChatMessage(2, "Saya", "Siap, saya monitor dari rumah.", "09:43", true, "sent")
                ),
                htMessages = listOf(HtMessage(1, "Pak RT", 4, "09:45", false))
            ),
            ChatThread("pak-rt", "Pak RT", "12D3KooWRt", false, messages = listOf(ChatMessage(3, "Pak RT", "Kalau internet mati, kabari dari sini.", "09:38", false, "verified"))),
            ChatThread("pos-ronda", "Pos Ronda", "9xQmNeru72", false, messages = listOf(ChatMessage(4, "Pos Ronda", "Ada 3 perangkat dekat mushola.", "09:28", false, "nearby")))
        )
    }

    fun updateThread(id: String, block: (ChatThread) -> ChatThread) {
        val index = threads.indexOfFirst { it.id == id }
        if (index >= 0) threads[index] = block(threads[index])
    }
    fun openThread(id: String, tab: RoomTab = RoomTab.Chat) {
        selectedThreadId = id
        roomTab = tab
        screen = Screen.Chat
    }
    fun backHome() {
        screen = Screen.Home
        selectedThreadId = null
        selectedEncryptedPeerId = null
        roomTab = RoomTab.Chat
    }
    fun selectedThread(): ChatThread? = selectedThreadId?.let { id -> threads.firstOrNull { it.id == id } }

    MaterialTheme {
        Box(Modifier.fillMaxSize().background(AppBg)) {
            when (screen) {
                Screen.Home -> HomeScreen(threads, onOpen = ::openThread, onNew = { screen = Screen.NewChoice }, onGlobalSos = { showSosSheet = true }, onCrypto = { screen = Screen.CryptoDebug }, onPair = { screen = Screen.PairContact }, onEncryptedChat = { screen = Screen.EncryptedChatList }, onMesh = { screen = Screen.MeshDebug })
                Screen.NewChoice -> NewChoiceScreen(onBack = ::backHome, onChat = { screen = Screen.NewChat }, onGroup = { screen = Screen.NewGroup })
                Screen.NewChat -> NewChatScreen(onBack = { screen = Screen.NewChoice }) { name, first ->
                    val cleanName = name.trim().ifBlank { "Tetangga Baru" }
                    val id = "direct-${nextThreadId++}"
                    threads.add(0, ChatThread(id, cleanName, "local-$id", false, messages = if (first.isBlank()) emptyList() else listOf(ChatMessage(nextMessageId++, "Saya", first.trim(), "Baru saja", true, "queued"))))
                    openThread(id)
                }
                Screen.NewGroup -> NewGroupScreen(peers, onBack = { screen = Screen.NewChoice }) { name, chosen ->
                    val id = "group-${nextThreadId++}"
                    val members = listOf(Member(ME_ID, "Saya", "local-device", true)) + chosen.map { Member(it.id, it.name, it.peerId, it.id == "pak-rt") }
                    threads.add(0, ChatThread(id, name.trim().ifBlank { "Group Baru" }, "/kampungnet/v1/group/$id", true, members = members, messages = listOf(ChatMessage(nextMessageId++, "Saya", "Group dibuat. ${members.size} anggota bergabung.", "Baru saja", true, "created"))))
                    openThread(id)
                }
                Screen.Chat -> {
                    val thread = selectedThread()
                    if (thread == null) backHome() else ChatScreen(
                        thread = thread,
                        tab = roomTab,
                        onTab = { roomTab = it },
                        onBack = ::backHome,
                        onInfo = { screen = Screen.GroupInfo },
                        onSend = { body -> updateThread(thread.id) { it.copy(messages = it.messages + ChatMessage(nextMessageId++, "Saya", body, "Baru saja", true, if (it.group) "sent to group" else "sent")) } },
                        onSos = { showSosSheet = true },
                        onPeerSos = { sosOverlay = SOSOverlayState(thread.name, thread.peerId, "SOS masuk dari ${thread.name} untuk room ini.") },
                        onHtSend = { updateThread(thread.id) { it.copy(htMessages = it.htMessages + HtMessage(nextHtId++, "Saya", 3 + (nextHtId % 5), "Baru saja", true)) } },
                        onHtReply = { updateThread(thread.id) { it.copy(htMessages = it.htMessages + HtMessage(nextHtId++, it.members.firstOrNull { m -> !m.admin }?.name ?: "Pak RT", 2 + (nextHtId % 4), "Baru saja", false)) } }
                    )
                }
                Screen.GroupInfo -> {
                    val thread = selectedThread()
                    if (thread == null) backHome() else GroupInfoScreen(thread, peers, onBack = { screen = Screen.Chat }) { peer ->
                        updateThread(thread.id) { current ->
                            if (current.members.any { it.id == peer.id }) current else current.copy(members = current.members + Member(peer.id, peer.name, peer.peerId))
                        }
                    }
                }
                Screen.CryptoDebug -> CryptoDebugScreen(cryptoBridge, onBack = ::backHome)
                Screen.PairContact -> PairContactScreen(cryptoBridge, encryptedChatRepository, onBack = ::backHome)
                Screen.EncryptedChatList -> EncryptedChatListScreen(encryptedChatRepository, onBack = ::backHome, onPair = { screen = Screen.PairContact }, onOpen = { peerId -> selectedEncryptedPeerId = peerId; screen = Screen.EncryptedChat })
                Screen.EncryptedChat -> EncryptedChatScreen(cryptoBridge, meshBridge, encryptedChatRepository, initialContactPeerId = selectedEncryptedPeerId, onBack = { screen = Screen.EncryptedChatList })
                Screen.MeshDebug -> MeshDebugScreen(cryptoBridge, meshBridge, encryptedChatRepository, onBack = ::backHome)
            }

            if (showSosSheet) {
                val target = selectedThread()
                SOSSheet(targetName = target?.name ?: "Semua Broadcast", onCancel = { showSosSheet = false }) { body ->
                    showSosSheet = false
                    val targetId = target?.id ?: BROADCAST_ID
                    updateThread(targetId) { it.copy(messages = it.messages + ChatMessage(nextMessageId++, "Saya", "SOS: $body", "Baru saja", true, if (target?.group == true) "SOS group ttl=5" else "SOS direct ttl=5")) }
                    openThread(targetId)
                }
            }

            sosOverlay?.let { state -> SOSOverlay(state, onMute = { sosOverlay = null }, onBlacklist = { updateThread("pak-rt") { it.copy(blacklisted = true) }; sosOverlay = null }) }
        }
    }
}

@Composable
private fun HomeScreen(threads: List<ChatThread>, onOpen: (String) -> Unit, onNew: () -> Unit, onGlobalSos: () -> Unit, onCrypto: () -> Unit, onPair: () -> Unit, onEncryptedChat: () -> Unit, onMesh: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF111827), Color(0xFF1D4ED8)))).padding(start = 18.dp, top = 48.dp, end = 18.dp, bottom = 18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KampungNetLogo(Modifier.size(56.dp)); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text("KampungNet", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Chat, Group, SOS, dan HT lokal", color = Color.White.copy(alpha = .78f)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onNew, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Primary)) { Text("+ Pesan / Group", fontWeight = FontWeight.Bold) }
                    Button(onGlobalSos, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Danger)) { Text("SOS Global") }
                }
                Button(onPair, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))) { Text("Tambah Kontak Aman") }
                Button(onEncryptedChat, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))) { Text("Chat Terenkripsi") }
                Button(onMesh, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E))) { Text("Mesh Lokal") }
                OutlinedButton(onCrypto, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp)) { Text("CryptoKit / Pairing Debug") }
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { items(threads) { ChatRow(it) { onOpen(it.id) } } }
    }
}

private data class ReceivedChatPacket(val fromPeerId: String, val envelope: String)

private data class ReceivedReceiptPacket(val fromPeerId: String, val envelope: String)

private data class SecureThreadPreview(
    val contact: ContactItem,
    val lastMessage: ChatMessageItem?,
)

private fun stableEnvelopeId(value: String): String {
    var hash = 0xcbf29ce484222325UL
    value.encodeToByteArray().forEach { byte ->
        hash = hash xor byte.toUByte().toULong()
        hash *= 0x100000001b3UL
    }
    return hash.toString(16).padStart(16, '0')
}

private fun relayPacketId(envelope: String): String = "relay-${stableEnvelopeId(envelope)}"

private fun relayReceiptPacketId(envelope: String): String = "relay-receipt-${stableEnvelopeId(envelope)}"

private fun meshPacketId(envelope: String): String = "mesh-${stableEnvelopeId(envelope)}"

private fun chatSeenPacketId(payloadJson: String, envelope: String): String = chatPacketId(payloadJson) ?: meshPacketId(envelope)

private fun receiptSeenPacketId(json: String, envelope: String): String = "receipt-${extractJsonValue(json, "packet_id") ?: meshPacketId(envelope)}-${extractJsonValue(json, "status") ?: "DELIVERED"}"

private fun deliveryReceiptJson(packetId: String, senderPeerId: String, targetPeerId: String, timestamp: Long): String =
    "{\"message_id\":\"${jsonEscape(packetId)}\",\"packet_id\":\"${jsonEscape(packetId)}\",\"sender_peer_id\":\"${jsonEscape(senderPeerId)}\",\"target_peer_id\":\"${jsonEscape(targetPeerId)}\",\"status\":\"DELIVERED\",\"timestamp\":$timestamp}"

private fun meshPeerCount(peersText: String): Int {
    val clean = peersText.trim()
    if (clean.isBlank() || clean == "Belum ada peer tersambung") return 0
    return clean.lines().map { it.trim() }.count { it.isNotBlank() }
}

private fun meshPeerSummary(peersText: String): String {
    val count = meshPeerCount(peersText)
    return when (count) {
        0 -> "Belum ada iPhone lain tersambung. Dekatkan device, nyalakan WiFi/Bluetooth, lalu start mesh di kedua HP."
        1 -> "1 peer tersambung\n${peersText.trim()}"
        else -> "$count peer tersambung\n${peersText.trim()}"
    }
}

private fun meshStatusValue(statusText: String, key: String): String? =
    statusText.lines()
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter('=')
        ?.trim()
        ?.takeIf { it.isNotBlank() }

private fun meshReadyForBroadcast(bridge: MeshBridge): String? {
    val status = bridge.status()
    if (!status.ok) return "Mesh Lokal belum siap: ${status.error.orEmpty()}"
    val value = status.value.orEmpty()
    val state = meshStatusValue(value, "state")
    if (state != null && state != "active") {
        return "Mesh Lokal belum aktif (state=$state). Buka Mesh Lokal lalu tekan Start Mesh + Auto-Sync."
    }
    if (state == null && (value.contains("Stopped", ignoreCase = true) || value.contains("local=-"))) {
        return "Mesh Lokal belum start. Buka Mesh Lokal lalu tekan Start Mesh + Auto-Sync."
    }
    return null
}

private fun meshEnvelopeSizeError(envelope: String): String? {
    val bytes = envelope.encodeToByteArray().size
    return if (bytes > MAX_MESH_ENVELOPE_BYTES) "Paket terlalu besar ($bytes bytes). Maks $MAX_MESH_ENVELOPE_BYTES bytes untuk mesh LAN." else null
}

private fun parseReceivedChatPackets(raw: String): List<ReceivedChatPacket> =
    raw.split("\n---\n").mapNotNull { block ->
        val lines = block.lines()
        val from = lines.firstOrNull()?.removePrefix("from=")?.takeIf { it != lines.firstOrNull() }?.trim().orEmpty()
        val envelope = lines.drop(1).joinToString("\n").trim()
        if (from.isBlank() || !envelope.startsWith("KNET1:CHAT:")) null else ReceivedChatPacket(from, envelope)
    }

private fun parseReceivedReceiptPackets(raw: String): List<ReceivedReceiptPacket> =
    raw.split("\n---\n").mapNotNull { block ->
        val lines = block.lines()
        val from = lines.firstOrNull()?.removePrefix("from=")?.takeIf { it != lines.firstOrNull() }?.trim().orEmpty()
        val envelope = lines.drop(1).joinToString("\n").trim()
        if (from.isBlank() || !envelope.startsWith("KNET1:RECEIPT:")) null else ReceivedReceiptPacket(from, envelope)
    }

@Composable
private fun MeshDebugScreen(cryptoBridge: CryptoBridge?, meshBridge: MeshBridge?, repository: EncryptedChatRepository?, onBack: () -> Unit) {
    var localPeerId by remember { mutableStateOf("iphone-a") }
    var envelope by remember { mutableStateOf("KNET1:CHAT:paste-packet-di-sini") }
    var received by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(if (meshBridge == null) "Mesh bridge belum tersedia di platform ini." else "Transport mesh siap distart.") }
    var autoSync by remember { mutableStateOf(false) }
    var meshStatus by remember { mutableStateOf("Belum start") }
    var peersText by remember { mutableStateOf("Belum ada peer tersambung") }
    var lastSyncSummary by remember { mutableStateOf("Belum pernah sync. Tekan Start Mesh + Auto-Sync untuk mulai.") }

    fun run(label: String, block: () -> MeshBridgeResult) {
        val bridge = meshBridge
        if (bridge == null) {
            status = "$label gagal: bridge iOS tidak tersedia"
            return
        }
        val result = block()
        status = if (result.ok) "$label sukses\n${result.value.orEmpty()}" else "$label gagal\n${result.error.orEmpty()}"
    }

    fun forwardRelayCache(): String {
        val bridge = meshBridge ?: return "Forward relay gagal: bridge iOS tidak tersedia"
        val repo = repository ?: return "Forward relay gagal: database belum tersedia"
        meshReadyForBroadcast(bridge)?.let { return "Forward relay ditahan: $it" }
        val relayPackets = repo.relayPackets(SystemClock.nowMillis())
        if (relayPackets.isEmpty()) return "Tidak ada relay cache aktif untuk diteruskan."

        var forwarded = 0
        var failed: String? = null
        relayPackets.forEach { packet ->
            if (failed == null) {
                val relayEnvelope = when (packet.topic) {
                    "CHAT" -> encodeChatEnvelope(packet.payload)
                    "RECEIPT" -> encodeReceiptEnvelope(packet.payload)
                    else -> null
                }
                val sizeError = relayEnvelope?.let { meshEnvelopeSizeError(it) }
                val result = if (sizeError == null) relayEnvelope?.let { bridge.broadcast(it) } else null
                if (result?.ok == true) {
                    forwarded += 1
                    repo.markRelayPacketForwarded(packet.packetId)
                } else {
                    failed = "${packet.packetId}: ${sizeError ?: result?.error ?: "topic ${packet.topic} tidak didukung"}"
                }
            }
        }
        return if (failed == null) {
            "Relay cache diteruskan. forwarded=$forwarded."
        } else {
            "Relay cache sebagian diteruskan. forwarded=$forwarded, gagal=$failed"
        }
    }

    fun sendPendingOutbox(): String {
        val bridge = meshBridge ?: return "Kirim outbox gagal: bridge iOS tidak tersedia"
        val repo = repository ?: return "Kirim outbox gagal: database belum tersedia"
        meshReadyForBroadcast(bridge)?.let { return "Kirim outbox ditahan: $it" }
        val pending = repo.retryableOutboxPackets(SystemClock.nowMillis())
        if (pending.isEmpty()) return "Tidak ada pending outbox untuk dikirim."

        var sent = 0
        var failed: String? = null
        pending.forEach { packet ->
            if (failed == null) {
                val envelope = encodeChatEnvelope(chatPacketJson(packet.packetId, localPeerId.trim().ifBlank { "local-device" }, packet.targetPeerId, packet.payload))
                val sizeError = meshEnvelopeSizeError(envelope)
                val result = if (sizeError == null) bridge.broadcast(envelope) else null
                if (result?.ok == true) {
                    repo.markOutboxPacketRelayed(packet.packetId, SystemClock.nowMillis())
                    sent += 1
                } else {
                    failed = "${packet.packetId}: ${sizeError ?: result?.error.orEmpty()}"
                }
            }
        }
        return if (failed == null) {
            "Outbox dibroadcast ke mesh. sent=$sent, status=RELAYED; menunggu receipt untuk DELIVERED."
        } else {
            "Outbox sebagian terkirim. sent=$sent, gagal=$failed"
        }
    }

    fun refreshMeshSnapshot(): String {
        val bridge = meshBridge ?: return "Bridge iOS tidak tersedia"
        val statusResult = bridge.status()
        val peersResult = bridge.peers()
        meshStatus = if (statusResult.ok) statusResult.value.orEmpty() else statusResult.error.orEmpty()
        peersText = if (peersResult.ok) peersResult.value.orEmpty() else peersResult.error.orEmpty()
        return "Status mesh diperbarui."
    }

    fun drainIncomingPackets(): String {
        val bridge = meshBridge ?: return "Receive gagal: bridge iOS tidak tersedia"
        val crypto = cryptoBridge
        val repo = repository
        val result = bridge.drainReceived()
        if (!result.ok) return "Receive gagal\n${result.error.orEmpty()}"

        received = result.value.orEmpty()
        val packets = parseReceivedChatPackets(received)
        val receipts = parseReceivedReceiptPackets(received)
        if (packets.isEmpty() && receipts.isEmpty()) return "Receive sukses. Tidak ada KNET1:CHAT/RECEIPT yang bisa diproses."
        if (crypto == null && packets.isNotEmpty()) return "Receive sukses, tapi decrypt gagal: crypto bridge tidak tersedia. packets=${packets.size}"
        if (repo == null) return "Receive sukses, tapi simpan gagal: database belum tersedia. packets=${packets.size}, receipts=${receipts.size}"

        val decryptor = crypto
        val localId = localPeerId.trim().ifBlank { "local-device" }
        var decrypted = 0
        var cachedForRelay = 0
        var deliveryReceipts = 0
        var receiptRelays = 0
        var duplicates = 0
        var failed: String? = null
        receipts.forEach { receipt ->
            if (failed == null) {
                val json = decodeReceiptEnvelope(receipt.envelope)
                if (json == null) {
                    failed = "${receipt.fromPeerId}: format KNET1:RECEIPT invalid"
                } else {
                    val seenId = receiptSeenPacketId(json, receipt.envelope)
                    if (repo.hasSeenMeshPacket(seenId, SystemClock.nowMillis())) {
                        duplicates += 1
                        return@forEach
                    }
                    repo.markSeenMeshPacket(seenId, SystemClock.nowMillis())
                    val packetId = extractJsonValue(json, "packet_id") ?: meshPacketId(receipt.envelope)
                    val messageId = extractJsonValue(json, "message_id") ?: packetId
                    val senderPeerId = extractJsonValue(json, "sender_peer_id") ?: receipt.fromPeerId
                    val targetPeerId = extractJsonValue(json, "target_peer_id") ?: localId
                    val receiptStatus = extractJsonValue(json, "status") ?: "DELIVERED"
                    val receiptTimestamp = extractJsonLong(json, "timestamp") ?: SystemClock.nowMillis()
                    if (senderPeerId == localId) {
                        duplicates += 1
                        return@forEach
                    }
                    repo.saveDeliveryReceipt(
                        messageId = messageId,
                        packetId = packetId,
                        senderPeerId = senderPeerId,
                        targetPeerId = targetPeerId,
                        status = receiptStatus,
                        timestamp = receiptTimestamp,
                    )
                    if (targetPeerId == localId && receiptStatus == "DELIVERED") {
                        repo.markDeliveredFromReceipt(packetId, SystemClock.nowMillis())
                    }
                    if (targetPeerId != localId) {
                        repo.saveRelayReceiptPacket(
                            packetId = relayReceiptPacketId(receipt.envelope),
                            sourcePeerId = receipt.fromPeerId,
                            targetPeerId = targetPeerId,
                            receiptJson = json,
                            nowMillis = SystemClock.nowMillis(),
                        )
                        receiptRelays += 1
                    }
                    deliveryReceipts += 1
                }
            }
        }
        packets.forEach { packet ->
            if (failed == null) {
                val payloadJson = decodeChatEnvelope(packet.envelope)
                if (payloadJson == null) {
                    failed = "${packet.fromPeerId}: format KNET1:CHAT invalid"
                } else if (decryptor == null) {
                    failed = "${packet.fromPeerId}: crypto bridge tidak tersedia"
                } else {
                    val seenId = chatSeenPacketId(payloadJson, packet.envelope)
                    if (repo.hasSeenMeshPacket(seenId, SystemClock.nowMillis())) {
                        duplicates += 1
                        return@forEach
                    }
                    val sourcePeerId = chatPacketSourcePeerId(payloadJson) ?: packet.fromPeerId
                    val targetPeerId = chatPacketTargetPeerId(payloadJson)
                    val encryptedPayloadJson = chatPacketPayloadJson(payloadJson)
                    if (sourcePeerId == localId) {
                        repo.markSeenMeshPacket(seenId, SystemClock.nowMillis())
                        duplicates += 1
                        return@forEach
                    }
                    if (!targetPeerId.isNullOrBlank() && targetPeerId != localId) {
                        repo.markSeenMeshPacket(seenId, SystemClock.nowMillis())
                        repo.saveRelayChatPacket(
                            packetId = relayPacketId(packet.envelope),
                            sourcePeerId = sourcePeerId,
                            targetPeerId = targetPeerId,
                            encryptedPayloadJson = payloadJson,
                            nowMillis = SystemClock.nowMillis(),
                        )
                        cachedForRelay += 1
                        return@forEach
                    }
                    val aad = "kampungnet-chat-v1:$sourcePeerId".toBase64()
                    val decryptResult = decryptor.decrypt(sourcePeerId, encryptedPayloadJson, aad)
                    if (decryptResult.ok) {
                        repo.markSeenMeshPacket(seenId, SystemClock.nowMillis())
                        val plaintext = decryptResult.value.orEmpty().fromBase64OrNull().orEmpty()
                        repo.saveIncomingDecryptedChat(
                            localPeerId = localId,
                            contactPeerId = sourcePeerId,
                            plaintext = plaintext,
                            nowMillis = SystemClock.nowMillis(),
                        )
                        decrypted += 1
                        val receiptJson = deliveryReceiptJson(
                            packetId = chatPacketId(payloadJson) ?: meshPacketId(packet.envelope),
                            senderPeerId = localId,
                            targetPeerId = sourcePeerId,
                            timestamp = SystemClock.nowMillis(),
                        )
                        bridge.broadcast(encodeReceiptEnvelope(receiptJson))
                    } else {
                        repo.markSeenMeshPacket(seenId, SystemClock.nowMillis())
                        repo.saveRelayChatPacket(
                            packetId = relayPacketId(packet.envelope),
                            sourcePeerId = sourcePeerId,
                            targetPeerId = targetPeerId,
                            encryptedPayloadJson = payloadJson,
                            nowMillis = SystemClock.nowMillis(),
                        )
                        cachedForRelay += 1
                    }
                }
            }
        }
        val receiveStatus = if (failed == null) {
            "Receive sukses. decrypted=$decrypted, cached relay=$cachedForRelay, receipts=$deliveryReceipts, receipt relay=$receiptRelays, duplicates=$duplicates."
        } else {
            "Receive sebagian diproses. decrypted=$decrypted, cached relay=$cachedForRelay, receipts=$deliveryReceipts, receipt relay=$receiptRelays, duplicates=$duplicates, gagal=$failed"
        }
        return if ((cachedForRelay > 0 || receiptRelays > 0) && failed == null) {
            "$receiveStatus\nAuto-forward: ${forwardRelayCache()}"
        } else {
            receiveStatus
        }
    }

    fun syncOnce(): String {
        val receiveStatus = drainIncomingPackets()
        val outboxStatus = sendPendingOutbox()
        val relayStatus = forwardRelayCache()
        val snapshotStatus = refreshMeshSnapshot()
        val summary = "Sync mesh sekali\n$receiveStatus\n$outboxStatus\n$relayStatus\n$snapshotStatus"
        lastSyncSummary = summary
        return summary
    }

    LaunchedEffect(autoSync, localPeerId) {
        while (autoSync) {
            val receiveStatus = drainIncomingPackets()
            val outboxStatus = sendPendingOutbox()
            val relayStatus = forwardRelayCache()
            refreshMeshSnapshot()
            lastSyncSummary = "Auto-sync terakhir\n$receiveStatus\n$outboxStatus\n$relayStatus"
            status = "Auto-sync foreground aktif. Peer=${meshPeerCount(peersText)}. Outbox dan relay dicek tiap 3 detik."
            delay(3_000)
        }
    }

        FormScaffold("Mesh Lokal", "iPhone Multipeer + LAN/hotspot", onBack) {
        EmptyCard("Cara Pakai Lapangan", "1. Isi Peer ID unik di tiap device. 2. Untuk Android-iPhone, hubungkan ke WiFi/hotspot yang sama. 3. Tekan Start Mesh + Auto-Sync di semua device dan biarkan layar ini terbuka.")
        OutlinedTextField(localPeerId, { localPeerId = it }, Modifier.fillMaxWidth(), label = { Text("Peer ID saya") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        Button({
            val bridge = meshBridge
            if (bridge == null) {
                status = "Start gagal: bridge iOS tidak tersedia"
            } else {
                val result = bridge.start(localPeerId.trim().ifBlank { "iphone" })
                autoSync = result.ok
                refreshMeshSnapshot()
                status = if (result.ok) "Mesh aktif dan auto-sync foreground berjalan.\n${result.value.orEmpty()}" else "Start gagal\n${result.error.orEmpty()}"
            }
        }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E))) { Text("Start Mesh + Auto-Sync") }
        Button({
            status = syncOnce()
        }, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0891B2))) { Text("Sync Sekarang") }
        Button({
            autoSync = !autoSync
            status = if (autoSync) "Auto-sync foreground dinyalakan. App harus tetap aktif di layar ini." else "Auto-sync foreground dimatikan."
        }, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = if (autoSync) Danger else Color(0xFF0369A1))) { Text(if (autoSync) "Stop Auto-Sync" else "Start Auto-Sync") }
        val pendingCount = repository?.pendingOutboxPackets(SystemClock.nowMillis())?.size
        val relayCount = repository?.relayPackets(SystemClock.nowMillis())?.size
        val recentOutbox = repository?.recentOutboxPackets()?.joinToString("\n") {
            "${it.packetId} -> ${it.targetPeerId ?: "?"} [${it.status}]"
        }?.ifBlank { "Belum ada outbox." }
        val recentReceipts = repository?.recentDeliveryReceipts()?.joinToString("\n") {
            "${it.packetId} ${it.status} dari ${it.senderPeerId}"
        }?.ifBlank { "Belum ada receipt." }
        EmptyCard("Dashboard Mesh", "Mode: ${if (autoSync) "auto-sync foreground aktif" else "manual"}\nPeer: ${meshPeerCount(peersText)} tersambung\nOutbox: ${pendingCount?.toString() ?: "DB belum aktif"}\nRelay cache: ${relayCount?.toString() ?: "DB belum aktif"}")
        EmptyCard("Koneksi", meshStatus.ifBlank { "Belum ada status mesh." })
        EmptyCard("Peer Tersambung", meshPeerSummary(peersText))
        EmptyCard("Sync Terakhir", lastSyncSummary)
        EmptyCard("Batasan iOS", "Relay otomatis di iPhone berjalan saat app aktif/foreground. Kalau layar terkunci lama atau app ditutup, iOS bisa menghentikan aktivitas mesh.")
        Text("Advanced / Troubleshooting", color = Ink, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button({
                run("Start mesh") { meshBridge!!.start(localPeerId.trim().ifBlank { "iphone" }) }
                refreshMeshSnapshot()
            }, Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E))) { Text("Start") }
            OutlinedButton({
                autoSync = false
                run("Stop mesh") { meshBridge!!.stop() }
                refreshMeshSnapshot()
            }, Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(16.dp)) { Text("Stop") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton({ status = refreshMeshSnapshot() }, Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(16.dp)) { Text("Refresh Status") }
            OutlinedButton({ run("Peers") { meshBridge!!.peers() }; refreshMeshSnapshot() }, Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(16.dp)) { Text("Peers") }
        }
        OutlinedTextField(envelope, { envelope = it }, Modifier.fillMaxWidth().height(112.dp), label = { Text("Envelope KNET1 untuk broadcast") }, shape = RoundedCornerShape(16.dp))
        Button({
            meshEnvelopeSizeError(envelope)?.let { status = "Broadcast ditahan: $it" } ?: run("Broadcast") { meshBridge!!.broadcast(envelope) }
        }, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))) { Text("Broadcast Paket") }
        Button({
            status = sendPendingOutbox()
        }, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E))) { Text("Kirim Pending Outbox") }
        Button({
            status = drainIncomingPackets()
        }, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Primary)) { Text("Ambil Paket Masuk") }
        Button({
            status = forwardRelayCache()
        }, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA580C))) { Text("Forward Relay Cache") }
        if (received.isNotBlank()) OutlinedTextField(received, { received = it }, Modifier.fillMaxWidth().height(112.dp), label = { Text("Received envelopes") }, shape = RoundedCornerShape(16.dp))
        EmptyCard("Outbox", if (pendingCount == null) "Database belum aktif di platform ini." else "$pendingCount paket pending/relayed belum delivered.")
        EmptyCard("Relay Cache", if (relayCount == null) "Database belum aktif di platform ini." else "$relayCount paket ciphertext siap diteruskan.")
        EmptyCard("Outbox Terakhir", recentOutbox ?: "Database belum aktif di platform ini.")
        EmptyCard("Receipt Terakhir", recentReceipts ?: "Database belum aktif di platform ini.")
        EmptyCard("Status", status)
    }
}

@Composable
private fun EncryptedChatListScreen(repository: EncryptedChatRepository?, onBack: () -> Unit, onPair: () -> Unit, onOpen: (String) -> Unit) {
    var previews by remember { mutableStateOf<List<SecureThreadPreview>>(emptyList()) }
    var status by remember { mutableStateOf(if (repository == null) "Database belum aktif di platform ini." else "Kontak aman siap dipakai untuk chat offline.") }

    fun refresh() {
        val repo = repository
        if (repo == null) {
            previews = emptyList()
            status = "Database belum aktif di platform ini."
            return
        }
        val contacts = repo.trustedContacts()
        previews = contacts.map { contact ->
            val messages = repo.chatMessagesForThread(contact.peerId, 1)
            SecureThreadPreview(contact, messages.lastOrNull())
        }.sortedWith(compareByDescending<SecureThreadPreview> { it.lastMessage?.createdAt ?: 0L }.thenBy { it.contact.displayName.lowercase() })
        status = if (contacts.isEmpty()) "Belum ada kontak aman. Pairing dulu supaya chat punya key per kontak." else "${contacts.size} kontak aman. Pilih satu untuk mulai chat terenkripsi."
    }

    LaunchedEffect(repository) { refresh() }
    LaunchedEffect(repository) {
        while (repository != null) {
            refresh()
            delay(2_000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header("Chat Aman", "Percakapan E2EE offline", onBack, action = "Pairing", onAction = onPair)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = Color(0xFFEEF2FF)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Secure inbox", color = Ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Pilih kontak yang sudah dipairing. Pesan tetap end-to-end encrypted; relay cuma bawa ciphertext.", color = Muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button({ refresh() }, Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Primary)) { Text("Refresh") }
                            OutlinedButton(onPair, Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(16.dp)) { Text("Tambah Kontak") }
                        }
                    }
                }
            }
            if (previews.isEmpty()) {
                item { EmptyCard("Belum Ada Chat Aman", if (repository == null) "Database belum aktif di platform ini." else "Pairing kontak dulu. Setelah ada key, chat akan muncul di sini.") }
            } else {
                items(previews) { preview ->
                    SecureThreadRow(preview) { onOpen(preview.contact.peerId) }
                }
            }
            item { EmptyCard("Status", status) }
        }
    }
}

@Composable
private fun EncryptedChatScreen(cryptoBridge: CryptoBridge?, meshBridge: MeshBridge?, repository: EncryptedChatRepository?, initialContactPeerId: String?, onBack: () -> Unit) {
    var localPeerId by remember { mutableStateOf("iphone-a") }
    var contactPeerId by remember { mutableStateOf(initialContactPeerId ?: "iphone-b") }
    var outgoingMessage by remember { mutableStateOf("Pesan rahasia") }
    var outgoingEnvelope by remember { mutableStateOf("") }
    var incomingEnvelope by remember { mutableStateOf("") }
    var decryptedMessage by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(if (cryptoBridge == null) "Chat crypto belum tersedia di platform ini." else "Siap kirim pesan terenkripsi ke kontak yang sudah dipairing.") }
    var messages by remember { mutableStateOf<List<ChatMessageItem>>(emptyList()) }
    var contacts by remember { mutableStateOf<List<ContactItem>>(emptyList()) }
    var lastRefreshText by remember { mutableStateOf("Belum refresh") }
    var showAdvanced by remember { mutableStateOf(false) }

    fun associatedData(peerId: String): String = "kampungnet-chat-v1:$peerId".toBase64()

    fun refreshMessages() {
        contacts = repository?.trustedContacts().orEmpty()
        messages = repository?.chatMessagesForThread(contactPeerId.trim(), 50).orEmpty()
        lastRefreshText = "Update lokal: ${messages.size} pesan"
    }

    fun selectContact(contact: ContactItem) {
        contactPeerId = contact.peerId
        refreshMessages()
        status = "Kontak ${contact.displayName} dipilih. Peer=${contact.peerId}."
    }

    fun statusLabel(value: String): String = when (value) {
        "QUEUED" -> "Menunggu mesh"
        "BROADCASTED" -> "Sudah dibroadcast"
        "RELAYED" -> "Sudah dibroadcast"
        "DELIVERED" -> "Terkirim"
        "READ" -> "Dibaca"
        "DECRYPTED" -> "Diterima"
        else -> value
    }

    fun sendPendingOutboxFromChat(): String {
        val bridge = meshBridge ?: return "Outbox siap, tapi Mesh Lokal belum tersedia/start. Buka Mesh Lokal untuk Start Mesh + Auto-Sync."
        val repo = repository ?: return "Outbox siap, tapi database belum tersedia."
        meshReadyForBroadcast(bridge)?.let { return "Outbox disimpan lokal. $it" }
        val pending = repo.retryableOutboxPackets(SystemClock.nowMillis())
        if (pending.isEmpty()) return "Tidak ada pending outbox untuk dikirim."

        var sent = 0
        var failed: String? = null
        pending.forEach { packet ->
            if (failed == null) {
                val envelope = encodeChatEnvelope(chatPacketJson(packet.packetId, localPeerId.trim().ifBlank { "local-device" }, packet.targetPeerId, packet.payload))
                val sizeError = meshEnvelopeSizeError(envelope)
                val result = if (sizeError == null) bridge.broadcast(envelope) else null
                if (result?.ok == true) {
                    repo.markOutboxPacketRelayed(packet.packetId, SystemClock.nowMillis())
                    sent += 1
                } else {
                    failed = "${packet.packetId}: ${sizeError ?: result?.error.orEmpty()}"
                }
            }
        }
        return if (failed == null) "Outbox dilempar ke mesh dari layar chat. sent=$sent." else "Outbox sebagian terkirim. sent=$sent, gagal=$failed"
    }

    LaunchedEffect(contactPeerId) {
        refreshMessages()
    }

    LaunchedEffect(initialContactPeerId) {
        if (!initialContactPeerId.isNullOrBlank()) contactPeerId = initialContactPeerId
    }

    LaunchedEffect(contactPeerId, repository) {
        while (repository != null) {
            refreshMessages()
            delay(2_000)
        }
    }

    FormScaffold("Chat Aman", "Offline E2EE + outbox mesh", onBack) {
        val selectedContact = contacts.firstOrNull { it.peerId == contactPeerId }
        val pendingCount = repository?.pendingOutboxPackets(SystemClock.nowMillis())?.size

        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), color = Color(0xFFEEF2FF)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(selectedContact?.displayName?.take(1).orEmpty().ifBlank { "?" }, Primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(selectedContact?.displayName ?: "Pilih kontak aman", color = Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(if (selectedContact == null) "Pairing dulu, lalu pilih kontak untuk mulai chat." else "${shortPeerId(contactPeerId)} · $lastRefreshText", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Surface(shape = RoundedCornerShape(999.dp), color = if ((pendingCount ?: 0) > 0) Color(0xFFFFFBEB) else Color.White) {
                    Text(if (pendingCount == null) "DB off" else "$pendingCount outbox", color = if ((pendingCount ?: 0) > 0) Village else Muted, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                }
            }
        }

        Text("Kontak Aman", color = Ink, fontWeight = FontWeight.Bold)
        if (contacts.isEmpty()) {
            EmptyCard("Belum Ada Kontak", if (repository == null) "Database belum aktif di platform ini." else "Pairing lewat Tambah Kontak Aman, lalu kontak akan muncul otomatis.")
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                contacts.take(3).forEach { contact ->
                    ContactPill(contact, selected = contact.peerId == contactPeerId, modifier = Modifier.weight(1f), onClick = { selectContact(contact) })
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button({ refreshMessages() }, Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Primary)) { Text("Refresh") }
            OutlinedButton({ sendPendingOutboxFromChat().also { status = it } }, Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(16.dp)) { Text("Sync Outbox") }
        }

        Surface(Modifier.fillMaxWidth().height(390.dp), shape = RoundedCornerShape(28.dp), color = Color(0xFFF8FAFC)) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                if (messages.isEmpty()) {
                    item { EmptyCard("Belum Ada Pesan", if (repository == null) "Database belum aktif di platform ini." else "Kirim pesan pertama atau refresh setelah menerima paket dari Mesh Lokal.") }
                } else {
                    items(messages) { message ->
                        ChatBubble(message, statusLabel(message.status))
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().imePadding(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(outgoingMessage, { outgoingMessage = it }, Modifier.weight(1f), placeholder = { Text("Tulis pesan aman...") }, shape = RoundedCornerShape(22.dp))
            Button({
            val bridge = cryptoBridge
            val peerId = contactPeerId.trim()
            if (bridge == null) {
                status = "Encrypt gagal: bridge iOS tidak tersedia"
            } else if (peerId.isBlank()) {
                status = "Encrypt gagal: peer ID kontak kosong"
            } else {
                val result = bridge.encrypt(peerId, outgoingMessage.toBase64(), associatedData(peerId))
                if (result.ok) {
                    val payloadJson = result.value.orEmpty()
                    val stored = repository?.saveOutgoingEncryptedChat(
                        localPeerId = localPeerId.trim().ifBlank { "local-device" },
                        contactPeerId = peerId,
                        plaintext = outgoingMessage,
                        encryptedPayloadJson = payloadJson,
                        nowMillis = SystemClock.nowMillis(),
                    )
                    outgoingEnvelope = if (stored == null) {
                        encodeChatEnvelope(payloadJson)
                    } else {
                        encodeChatEnvelope(chatPacketJson(stored.packetId, localPeerId.trim().ifBlank { "local-device" }, peerId, payloadJson))
                    }
                    status = if (stored == null) {
                        "Pesan terenkripsi. Database belum tersedia, paket hanya ada di layar."
                    } else {
                        "Pesan terenkripsi dan masuk outbox. message=${stored.messageId}, packet=${stored.packetId}, status=${stored.status}."
                    }
                    if (stored != null) {
                        status += "\n${sendPendingOutboxFromChat()}"
                    }
                    outgoingMessage = ""
                    refreshMessages()
                } else {
                    status = "Encrypt gagal: ${result.error.orEmpty()}"
                }
            }
            }, Modifier.height(56.dp), shape = RoundedCornerShape(20.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))) { Text("Kirim") }
        }

        OutlinedButton({ showAdvanced = !showAdvanced }, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp)) { Text(if (showAdvanced) "Sembunyikan Advanced" else "Advanced") }
        if (showAdvanced) {
            OutlinedTextField(localPeerId, { localPeerId = it }, Modifier.fillMaxWidth(), label = { Text("Peer ID saya") }, singleLine = true, shape = RoundedCornerShape(16.dp))
            OutlinedTextField(contactPeerId, { contactPeerId = it }, Modifier.fillMaxWidth(), label = { Text("Peer ID kontak manual / fallback") }, singleLine = true, shape = RoundedCornerShape(16.dp))
            OutlinedTextField(outgoingEnvelope, { outgoingEnvelope = it }, Modifier.fillMaxWidth().height(124.dp), label = { Text("Paket KNET1:CHAT") }, shape = RoundedCornerShape(16.dp))

            Text("Terima Manual", color = Ink, fontWeight = FontWeight.Bold)
            OutlinedTextField(incomingEnvelope, { incomingEnvelope = it }, Modifier.fillMaxWidth().height(124.dp), label = { Text("Paste paket KNET1:CHAT") }, shape = RoundedCornerShape(16.dp))
            Button({
                val bridge = cryptoBridge
                val peerId = contactPeerId.trim()
                val payloadJson = decodeChatEnvelope(incomingEnvelope)
                if (bridge == null) {
                    status = "Decrypt gagal: bridge iOS tidak tersedia"
                } else if (peerId.isBlank()) {
                    status = "Decrypt gagal: peer ID kontak kosong"
                } else if (payloadJson == null) {
                    status = "Decrypt gagal: format harus KNET1:CHAT:<base64-json>"
                } else {
                    val result = bridge.decrypt(peerId, chatPacketPayloadJson(payloadJson), associatedData(peerId))
                    if (result.ok) {
                        decryptedMessage = result.value.orEmpty().fromBase64OrNull().orEmpty()
                        val messageId = repository?.saveIncomingDecryptedChat(
                            localPeerId = localPeerId.trim().ifBlank { "local-device" },
                            contactPeerId = peerId,
                            plaintext = decryptedMessage,
                            nowMillis = SystemClock.nowMillis(),
                        )
                        status = if (messageId == null) "Pesan berhasil didecrypt. Database belum tersedia." else "Pesan berhasil didecrypt dan disimpan. message=$messageId."
                        refreshMessages()
                    } else {
                        status = "Decrypt gagal: ${result.error.orEmpty()}"
                    }
                }
            }, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))) { Text("Decrypt Pesan") }
            if (decryptedMessage.isNotBlank()) EmptyCard("Isi Pesan", decryptedMessage)
        }
        EmptyCard("Status", status)
    }
}

@Composable
private fun CryptoDebugScreen(cryptoBridge: CryptoBridge?, onBack: () -> Unit) {
    var localPeerId by remember { mutableStateOf("iphone-a") }
    var contactPeerId by remember { mutableStateOf("iphone-b") }
    var displayName by remember { mutableStateOf("Saya") }
    var offerJson by remember { mutableStateOf("") }
    var acceptanceJson by remember { mutableStateOf("") }
    var encryptedJson by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Pesan rahasia dari KampungNet") }
    var log by remember { mutableStateOf(if (cryptoBridge == null) "Crypto bridge belum tersedia di platform ini." else "Crypto bridge siap.") }

    fun run(label: String, block: () -> CryptoBridgeResult) {
        if (cryptoBridge == null) {
            log = "$label gagal: bridge tidak tersedia"
            return
        }
        val result = block()
        log = if (result.ok) "$label sukses\n${result.value.orEmpty()}" else "$label gagal\n${result.error.orEmpty()}"
    }

    FormScaffold("Crypto Debug", "CryptoKit + Keychain bridge iOS", onBack) {
        OutlinedTextField(localPeerId, { localPeerId = it }, Modifier.fillMaxWidth(), label = { Text("Local peer ID") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        OutlinedTextField(contactPeerId, { contactPeerId = it }, Modifier.fillMaxWidth(), label = { Text("Contact peer ID") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        OutlinedTextField(displayName, { displayName = it }, Modifier.fillMaxWidth(), label = { Text("Display name") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        Button({
            run("Identity") { cryptoBridge!!.getOrCreateIdentityPublicKey() }
        }, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Primary)) { Text("Generate Identity") }
        Button({
            run("Create offer") {
                cryptoBridge!!.createPairingOffer(contactPeerId, localPeerId, displayName).also { if (it.ok) offerJson = it.value.orEmpty() }
            }
        }, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Primary)) { Text("Create Pairing Offer") }
        OutlinedTextField(offerJson, { offerJson = it }, Modifier.fillMaxWidth().height(92.dp), label = { Text("Offer JSON") }, shape = RoundedCornerShape(16.dp))
        Button({
            run("Accept offer") {
                cryptoBridge!!.acceptPairingOffer(offerJson, contactPeerId, "Peer B").also { if (it.ok) acceptanceJson = it.value.orEmpty() }
            }
        }, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Primary)) { Text("Accept Offer") }
        OutlinedTextField(acceptanceJson, { acceptanceJson = it }, Modifier.fillMaxWidth().height(92.dp), label = { Text("Acceptance JSON") }, shape = RoundedCornerShape(16.dp))
        Button({
            run("Complete pairing") { cryptoBridge!!.completePairing(acceptanceJson, localPeerId) }
        }, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Primary)) { Text("Complete Pairing") }
        OutlinedTextField(message, { message = it }, Modifier.fillMaxWidth(), label = { Text("Test message") }, shape = RoundedCornerShape(16.dp))
        Button({
            run("Encrypt") {
                cryptoBridge!!.encrypt(contactPeerId, message.toBase64(), "kampungnet-debug-aad".toBase64()).also { if (it.ok) encryptedJson = it.value.orEmpty() }
            }
        }, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Primary)) { Text("Encrypt Test") }
        OutlinedTextField(encryptedJson, { encryptedJson = it }, Modifier.fillMaxWidth().height(92.dp), label = { Text("Encrypted payload JSON") }, shape = RoundedCornerShape(16.dp))
        Button({
            run("Decrypt") { cryptoBridge!!.decrypt(contactPeerId, encryptedJson, "kampungnet-debug-aad".toBase64()) }
        }, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Primary)) { Text("Decrypt Test") }
        EmptyCard("Result", log)
    }
}

@Composable
private fun PairContactScreen(cryptoBridge: CryptoBridge?, repository: EncryptedChatRepository?, onBack: () -> Unit) {
    var localPeerId by remember { mutableStateOf("iphone-a") }
    var localName by remember { mutableStateOf("Saya") }
    var contactPeerId by remember { mutableStateOf("iphone-b") }
    var offerEnvelope by remember { mutableStateOf("") }
    var incomingOffer by remember { mutableStateOf("") }
    var acceptanceEnvelope by remember { mutableStateOf("") }
    var incomingAcceptance by remember { mutableStateOf("") }
    var verificationCode by remember { mutableStateOf("") }
    var result by remember { mutableStateOf(if (cryptoBridge == null) "Pairing crypto belum tersedia di platform ini." else "Siap pairing aman via copy-paste.") }

    fun setResult(label: String, cryptoResult: CryptoBridgeResult, onSuccess: (String) -> Unit = {}) {
        if (cryptoResult.ok) {
            val value = cryptoResult.value.orEmpty()
            onSuccess(value)
            result = "$label sukses"
        } else {
            result = "$label gagal: ${cryptoResult.error.orEmpty()}"
        }
    }

    fun missingBridge(label: String): Boolean {
        if (cryptoBridge != null) return false
        result = "$label gagal: bridge iOS tidak tersedia"
        return true
    }

    fun savePairedContact(peerId: String, displayName: String, identityPublicKey: String?, keyId: String?, secretRef: String?) {
        val repo = repository
        if (repo == null) {
            result = "Pairing berhasil, tapi database belum tersedia. Contact: $peerId, key: ${keyId.orEmpty()}."
            return
        }
        repo.saveTrustedContact(
            peerId = peerId,
            displayName = displayName.ifBlank { peerId },
            identityPublicKey = identityPublicKey,
            keyId = keyId,
            secretRef = secretRef,
            nowMillis = SystemClock.nowMillis(),
        )
        result = "Pairing berhasil dan kontak tersimpan. Contact: $peerId, key: ${keyId.orEmpty()}."
    }

    FormScaffold("Tambah Kontak Aman", "Pairing manual tanpa internet", onBack) {
        EmptyCard("Cara pakai", "A buat invite, B paste invite dan buat acceptance, lalu A paste acceptance untuk menyelesaikan pairing. Cocokkan kode verifikasi di kedua HP.")
        OutlinedTextField(localPeerId, { localPeerId = it }, Modifier.fillMaxWidth(), label = { Text("Peer ID saya") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        OutlinedTextField(localName, { localName = it }, Modifier.fillMaxWidth(), label = { Text("Nama saya") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        OutlinedTextField(contactPeerId, { contactPeerId = it }, Modifier.fillMaxWidth(), label = { Text("Peer ID teman") }, singleLine = true, shape = RoundedCornerShape(16.dp))

        Text("Sebagai Pengundang", color = Ink, fontWeight = FontWeight.Bold)
        Button({
            if (!missingBridge("Buat invite")) {
                val bridge = cryptoBridge!!
                setResult("Buat invite", bridge.createPairingOffer(contactPeerId.trim(), localPeerId.trim(), localName.trim().ifBlank { "Saya" })) { json ->
                    offerEnvelope = encodePairingEnvelope("PAIRING_OFFER", json)
                }
            }
        }, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Primary)) { Text("Buat Invite") }
        OutlinedTextField(offerEnvelope, { offerEnvelope = it }, Modifier.fillMaxWidth().height(104.dp), label = { Text("Invite untuk dikirim ke teman") }, shape = RoundedCornerShape(16.dp))

        Text("Sebagai Penerima", color = Ink, fontWeight = FontWeight.Bold)
        OutlinedTextField(incomingOffer, { incomingOffer = it }, Modifier.fillMaxWidth().height(104.dp), label = { Text("Paste invite dari teman") }, shape = RoundedCornerShape(16.dp))
        Button({
            if (!missingBridge("Terima invite")) {
                val offerJson = decodePairingEnvelope(incomingOffer, "PAIRING_OFFER")
                if (offerJson == null) {
                    result = "Terima invite gagal: format harus KNET1:PAIRING_OFFER:<base64-json>"
                } else {
                    val bridge = cryptoBridge!!
                    setResult("Terima invite", bridge.acceptPairingOffer(offerJson, localPeerId.trim(), localName.trim().ifBlank { "Saya" })) { acceptanceJson ->
                        verificationCode = extractJsonValue(acceptanceJson, "verificationCode").orEmpty()
                        acceptanceEnvelope = encodePairingEnvelope("PAIRING_ACCEPT", acceptanceJson)
                        val peerId = extractJsonValue(offerJson, "senderPeerId").orEmpty()
                        val displayName = extractJsonValue(offerJson, "senderName").orEmpty()
                        val identityPublicKey = extractJsonValue(offerJson, "identityPublicKey")
                        savePairedContact(peerId, displayName, identityPublicKey, null, null)
                    }
                }
            }
        }, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Primary)) { Text("Terima Invite") }
        OutlinedTextField(acceptanceEnvelope, { acceptanceEnvelope = it }, Modifier.fillMaxWidth().height(104.dp), label = { Text("Acceptance untuk dikirim balik") }, shape = RoundedCornerShape(16.dp))
        if (verificationCode.isNotBlank()) EmptyCard("Kode Verifikasi", "$verificationCode - cocokkan dengan teman sebelum lanjut chat.")

        Text("Finalisasi Pengundang", color = Ink, fontWeight = FontWeight.Bold)
        OutlinedTextField(incomingAcceptance, { incomingAcceptance = it }, Modifier.fillMaxWidth().height(104.dp), label = { Text("Paste acceptance dari teman") }, shape = RoundedCornerShape(16.dp))
        Button({
            if (!missingBridge("Selesaikan pairing")) {
                val acceptanceJson = decodePairingEnvelope(incomingAcceptance, "PAIRING_ACCEPT")
                if (acceptanceJson == null) {
                    result = "Selesaikan pairing gagal: format harus KNET1:PAIRING_ACCEPT:<base64-json>"
                } else {
                    verificationCode = extractJsonValue(acceptanceJson, "verificationCode").orEmpty()
                    val bridge = cryptoBridge!!
                    setResult("Selesaikan pairing", bridge.completePairing(acceptanceJson, localPeerId.trim())) { keyJson ->
                        val keyId = extractJsonValue(keyJson, "keyId").orEmpty()
                        val peerId = extractJsonValue(keyJson, "contactPeerId").orEmpty()
                        val displayName = extractJsonValue(acceptanceJson, "responderName").orEmpty()
                        val identityPublicKey = extractJsonValue(acceptanceJson, "identityPublicKey")
                        savePairedContact(peerId, displayName, identityPublicKey, keyId, keyId)
                    }
                }
            }
        }, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))) { Text("Selesaikan Pairing") }
        if (verificationCode.isNotBlank()) EmptyCard("Kode Yang Harus Cocok", verificationCode)
        EmptyCard("Status", result)
    }
}

@Composable
private fun NewChoiceScreen(onBack: () -> Unit, onChat: () -> Unit, onGroup: () -> Unit) {
    Column(Modifier.fillMaxSize()) { Header("Buat Baru", "Pilih jenis percakapan", onBack)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BigAction("Chat Personal", "Kirim pesan ke satu peer", onChat)
            BigAction("Group", "Buat room dengan admin, member, SOS group, dan HT", onGroup)
        }
    }
}

@Composable
private fun NewChatScreen(onBack: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }; var first by remember { mutableStateOf("") }
    FormScaffold("Chat Baru", "Mulai percakapan personal", onBack) {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nama kontak") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        OutlinedTextField(first, { first = it }, Modifier.fillMaxWidth().height(120.dp), label = { Text("Pesan pertama") }, shape = RoundedCornerShape(16.dp))
        Button({ onCreate(name, first) }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Primary)) { Text("Buat Chat") }
    }
}

@Composable
private fun NewGroupScreen(peers: List<Peer>, onBack: () -> Unit, onCreate: (String, List<Peer>) -> Unit) {
    var name by remember { mutableStateOf("Group Ronda") }
    val selected = remember { mutableStateListOf(peers[0], peers[1]) }
    FormScaffold("Group Baru", "Saya otomatis menjadi admin", onBack) {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nama group") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        Text("Invite member", color = Muted, fontWeight = FontWeight.Bold)
        peers.forEach { peer -> SelectablePeer(peer, selected.any { it.id == peer.id }) { checked -> if (checked) selected.add(peer) else selected.removeAll { it.id == peer.id } } }
        Button({ onCreate(name, selected.toList()) }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Primary)) { Text("Buat Group (${selected.size + 1} anggota)") }
    }
}

@Composable
private fun ChatScreen(thread: ChatThread, tab: RoomTab, onTab: (RoomTab) -> Unit, onBack: () -> Unit, onInfo: () -> Unit, onSend: (String) -> Unit, onSos: () -> Unit, onPeerSos: () -> Unit, onHtSend: () -> Unit, onHtReply: () -> Unit) {
    var draft by remember(thread.id) { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Header(thread.name, if (thread.group) "${thread.members.size} member · ${thread.members.count { it.admin }} admin" else thread.peerId, onBack, if (thread.group) "Info" else null, onInfo)
        if (thread.group) TabBar(tab, onTab)
        when (if (thread.group) tab else RoomTab.Chat) {
            RoomTab.Chat -> Column(Modifier.weight(1f)) {
                LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { if (thread.messages.isEmpty()) item { EmptyCard("Belum ada pesan", "Kirim pesan pertama dari kolom bawah.") }; items(thread.messages) { MessageBubble(it) } }
                Row(Modifier.fillMaxWidth().background(Color.White).imePadding().padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), placeholder = { Text("Tulis pesan...") }, shape = RoundedCornerShape(20.dp))
                    Spacer(Modifier.width(8.dp)); Button({ val body = draft.trim(); if (body.isNotEmpty()) { onSend(body); draft = "" } }, Modifier.height(56.dp), shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = Primary)) { Text("Kirim") }
                }
            }
            RoomTab.Sos -> SosRoom(thread, onSos, onPeerSos)
            RoomTab.Ht -> HtRoom(thread, onHtSend, onHtReply)
        }
    }
}

@Composable
private fun GroupInfoScreen(thread: ChatThread, peers: List<Peer>, onBack: () -> Unit, onInvite: (Peer) -> Unit) {
    Column(Modifier.fillMaxSize()) { Header("Info Group", thread.name, onBack)
        LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { EmptyCard("Admin", thread.members.filter { it.admin }.joinToString { it.name }) }
            item { Text("Member", color = Muted, fontWeight = FontWeight.Bold, modifier = Modifier.padding(6.dp)) }
            items(thread.members) { MemberRow(it) }
            item { Text("Invite peer", color = Muted, fontWeight = FontWeight.Bold, modifier = Modifier.padding(6.dp)) }
            items(peers.filterNot { p -> thread.members.any { it.id == p.id } }) { peer -> BigAction("Invite ${peer.name}", peer.peerId) { onInvite(peer) } }
        }
    }
}

@Composable private fun TabBar(tab: RoomTab, onTab: (RoomTab) -> Unit) { Row(Modifier.fillMaxWidth().background(Color.White).padding(start = 10.dp, top = 8.dp, end = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(RoomTab.Chat to "Chat", RoomTab.Sos to "SOS", RoomTab.Ht to "HT").forEach { (t, label) -> Button({ onTab(t) }, Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = if (tab == t) Primary else Color(0xFFEFF6FF), contentColor = if (tab == t) Color.White else Primary)) { Text(label) } } } }

@Composable private fun SosRoom(thread: ChatThread, onSos: () -> Unit, onPeerSos: () -> Unit) { Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { EmptyCard("SOS Room", "SOS di sini hanya masuk ke ${thread.name}, bukan ke semua group."); Button(onSos, Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = Danger)) { Text("Kirim SOS ke ${thread.name}") }; OutlinedButton(onPeerSos, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) { Text("Simulasi SOS masuk di room ini") } } }

@Composable private fun HtRoom(thread: ChatThread, onSend: () -> Unit, onReply: () -> Unit) { Column(Modifier.fillMaxSize().imePadding().padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { EmptyCard("HT / Walkie Talkie", "Mode MVP: voice simulasi push-to-talk satu arah bergantian di group ini."); LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { if (thread.htMessages.isEmpty()) item { EmptyCard("Channel idle", "Belum ada transmisi HT.") }; items(thread.htMessages) { HtBubble(it) } }; Button(onSend, Modifier.fillMaxWidth().height(68.dp), shape = RoundedCornerShape(24.dp), colors = ButtonDefaults.buttonColors(containerColor = Primary)) { Text("Tekan untuk Kirim Voice 1 Arah") }; OutlinedButton(onReply, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) { Text("Simulasi Balasan HT") } } }

@Composable private fun FormScaffold(title: String, subtitle: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) { Column(Modifier.fillMaxSize()) { Header(title, subtitle, onBack); Card(Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(CardBg)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content) } } }

@Composable private fun Header(title: String, subtitle: String, onBack: () -> Unit, action: String? = null, onAction: () -> Unit = {}) { Row(Modifier.fillMaxWidth().background(Color.White).padding(start = 12.dp, top = 44.dp, end = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) { OutlinedButton(onBack, Modifier.height(52.dp), shape = RoundedCornerShape(18.dp)) { Text("‹ Kembali", fontWeight = FontWeight.Bold) }; Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(title, color = Ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall) }; if (action != null) Button(onAction, Modifier.height(48.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Primary)) { Text(action) } } }

@Composable private fun BigAction(title: String, subtitle: String, onClick: () -> Unit) { Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(CardBg), elevation = CardDefaults.cardElevation(1.dp)) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, color = Ink, fontWeight = FontWeight.Bold); Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall) }; Text("›", color = Primary, style = MaterialTheme.typography.headlineSmall) } } }

@Composable private fun ChatRow(thread: ChatThread, onClick: () -> Unit) { val last = thread.messages.lastOrNull(); Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(if (thread.blacklisted) Color(0xFFFFF1F2) else CardBg), elevation = CardDefaults.cardElevation(1.dp)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Avatar(if (thread.group) "#" else thread.name.take(1), if (thread.group) Village else Primary); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(thread.name, color = Ink, fontWeight = FontWeight.Bold); Text(last?.time.orEmpty(), color = Muted, style = MaterialTheme.typography.bodySmall) }; Text(last?.body ?: "Belum ada pesan", color = Color(0xFF374151)); Text(if (thread.group) "Group · ${thread.members.size} member" else thread.peerId, color = Muted, style = MaterialTheme.typography.bodySmall) } } } }

@Composable private fun ContactRow(contact: ContactItem, selected: Boolean, onClick: () -> Unit) { Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(if (selected) Color(0xFFEFF6FF) else CardBg), elevation = CardDefaults.cardElevation(1.dp)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Avatar(contact.displayName.take(1).ifBlank { "?" }, if (selected) Primary else Village); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(contact.displayName, color = Ink, fontWeight = FontWeight.Bold); Text(contact.peerId, color = Muted, style = MaterialTheme.typography.bodySmall) }; Text(if (selected) "Aktif" else "Chat", color = Primary, fontWeight = FontWeight.Bold) } } }

@Composable private fun ContactPill(contact: ContactItem, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) { Card(modifier.height(92.dp).clickable(onClick = onClick), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(if (selected) Primary else CardBg), elevation = CardDefaults.cardElevation(if (selected) 3.dp else 1.dp)) { Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) { Avatar(contact.displayName.take(1).ifBlank { "?" }, if (selected) Color.White.copy(alpha = .22f) else Village); Column { Text(contact.displayName, color = if (selected) Color.White else Ink, fontWeight = FontWeight.Bold, maxLines = 1); Text(shortPeerId(contact.peerId), color = if (selected) Color.White.copy(alpha = .78f) else Muted, style = MaterialTheme.typography.bodySmall) } } } }

@Composable private fun SecureThreadRow(preview: SecureThreadPreview, onClick: () -> Unit) { val last = preview.lastMessage; Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(CardBg), elevation = CardDefaults.cardElevation(1.dp)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Avatar(preview.contact.displayName.take(1).ifBlank { "?" }, Primary); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(preview.contact.displayName, color = Ink, fontWeight = FontWeight.Bold); Text(last?.let { formatClockTime(it.createdAt) }.orEmpty(), color = Muted, style = MaterialTheme.typography.bodySmall) }; Text(last?.body ?: "Belum ada pesan. Tap untuk mulai chat aman.", color = Color(0xFF374151), maxLines = 1); Text("${shortPeerId(preview.contact.peerId)} · ${last?.status ?: "trusted"}", color = Muted, style = MaterialTheme.typography.bodySmall) }; Text("›", color = Primary, style = MaterialTheme.typography.headlineSmall) } } }

@Composable private fun SelectablePeer(peer: Peer, selected: Boolean, onChange: (Boolean) -> Unit) { Card(Modifier.fillMaxWidth().clickable { onChange(!selected) }, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(if (selected) Color(0xFFEFF6FF) else CardBg)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Avatar(peer.name.take(1), if (selected) Primary else Muted); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(peer.name, fontWeight = FontWeight.Bold, color = Ink); Text(peer.peerId, color = Muted, style = MaterialTheme.typography.bodySmall) }; Text(if (selected) "Dipilih" else "Invite", color = Primary, fontWeight = FontWeight.Bold) } } }

@Composable private fun MemberRow(member: Member) { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(CardBg)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Avatar(member.name.take(1), if (member.admin) Village else Primary); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(member.name, color = Ink, fontWeight = FontWeight.Bold); Text(member.peerId, color = Muted, style = MaterialTheme.typography.bodySmall) }; if (member.admin) Text("Admin", color = Village, fontWeight = FontWeight.Bold) } } }

@Composable private fun MessageBubble(message: ChatMessage) { Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start) { Surface(color = if (message.outgoing) Primary else CardBg, shape = RoundedCornerShape(22.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth(.82f)) { Column(Modifier.padding(13.dp)) { if (!message.outgoing) Text(message.sender, color = Primary, fontWeight = FontWeight.Bold); Text(message.body, color = if (message.outgoing) Color.White else Ink); Text("${message.time} · ${message.status}", color = if (message.outgoing) Color.White.copy(alpha = .75f) else Muted, style = MaterialTheme.typography.bodySmall) } } } }

@Composable private fun ChatBubble(message: ChatMessageItem, statusText: String) { Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start) { Surface(color = if (message.outgoing) Primary else Color(0xFFF8FAFC), shape = RoundedCornerShape(22.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth(.86f)) { Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(if (message.outgoing) "Saya -> ${shortPeerId(message.targetPeerId)}" else "${shortPeerId(message.senderPeerId)} -> Saya", color = if (message.outgoing) Color.White.copy(alpha = .82f) else Primary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold); Text(message.body, color = if (message.outgoing) Color.White else Ink); Text("$statusText · ${formatClockTime(message.createdAt)}", color = if (message.outgoing) Color.White.copy(alpha = .75f) else Muted, style = MaterialTheme.typography.bodySmall) } } } }

@Composable private fun HtBubble(message: HtMessage) { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(if (message.outgoing) Color(0xFFEFF6FF) else CardBg)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Text(if (message.outgoing) "TX" else "RX", color = if (message.outgoing) Primary else Village, fontWeight = FontWeight.Bold); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("${message.sender} · Voice 00:0${message.duration}", color = Ink, fontWeight = FontWeight.Bold); Text("${message.time} · tap untuk playback simulasi", color = Muted, style = MaterialTheme.typography.bodySmall) } } } }

@Composable private fun EmptyCard(title: String, subtitle: String) { Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(CardBg)) { Column(Modifier.padding(16.dp)) { Text(title, color = Ink, fontWeight = FontWeight.Bold); Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall) } } }
@Composable private fun Avatar(text: String, color: Color) { Box(Modifier.size(50.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) { Text(text.uppercase(), color = Color.White, fontWeight = FontWeight.Bold) } }

@Composable private fun SOSSheet(targetName: String, onCancel: () -> Unit, onSend: (String) -> Unit) { var body by remember { mutableStateOf("Butuh bantuan sekarang") }; Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f)), contentAlignment = Alignment.BottomCenter) { Surface(Modifier.fillMaxWidth().padding(14.dp), shape = RoundedCornerShape(28.dp), color = CardBg) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("Kirim SOS", color = Danger, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("Target: $targetName", color = Muted); OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth(), label = { Text("Pesan darurat") }, shape = RoundedCornerShape(16.dp)); Button({ onSend(body.ifBlank { "Butuh bantuan" }) }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Danger)) { Text("Kirim SOS") }; OutlinedButton(onCancel, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) { Text("Batal") } } } } }

@Composable private fun SOSOverlay(state: SOSOverlayState, onMute: () -> Unit, onBlacklist: () -> Unit) { Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF7F1D1D), Danger))).padding(24.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) { Spacer(Modifier.height(12.dp)); Column(horizontalAlignment = Alignment.CenterHorizontally) { KampungNetLogo(Modifier.size(90.dp)); Spacer(Modifier.height(18.dp)); Text("SOS DARURAT", color = Color.White, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold); Text(state.sender, color = Color.White, style = MaterialTheme.typography.headlineMedium); Text(state.peerId, color = Color.White.copy(alpha = .75f)); Spacer(Modifier.height(20.dp)); Surface(color = Color.White.copy(alpha = .14f), shape = RoundedCornerShape(22.dp)) { Text(state.message, color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(18.dp)) } }; Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) { Button(onMute, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Danger)) { Text("Mute") }; Button(onBlacklist, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Text("Mute & Blacklist Peer") } } } }

@Composable
private fun KampungNetLogo(modifier: Modifier = Modifier) { Canvas(modifier.clip(CircleShape).background(Color(0xFFFFF7D6))) { val green = Color(0xFF16A34A); val bamboo = Color(0xFF8B5A22); val dark = Color(0xFF3E2A12); drawCircle(Color(0xFFDBEAFE), size.minDimension * .48f, center); drawCircle(Color(0xFFBBF7D0), size.minDimension * .34f, center, alpha = .6f); drawLine(bamboo, Offset(size.width * .30f, size.height * .74f), Offset(size.width * .70f, size.height * .30f), strokeWidth = size.width * .08f, cap = StrokeCap.Round); drawLine(bamboo, Offset(size.width * .40f, size.height * .80f), Offset(size.width * .78f, size.height * .38f), strokeWidth = size.width * .08f, cap = StrokeCap.Round); drawOval(bamboo, Offset(size.width * .24f, size.height * .38f), Size(size.width * .46f, size.height * .24f)); drawOval(dark, Offset(size.width * .34f, size.height * .43f), Size(size.width * .25f, size.height * .12f)); drawArc(green, -70f, 65f, false, Offset(size.width * .49f, size.height * .18f), Size(size.width * .36f, size.height * .36f), style = Stroke(size.width * .035f, cap = StrokeCap.Round)); drawArc(green, -70f, 65f, false, Offset(size.width * .57f, size.height * .10f), Size(size.width * .46f, size.height * .46f), style = Stroke(size.width * .03f, cap = StrokeCap.Round)) } }
