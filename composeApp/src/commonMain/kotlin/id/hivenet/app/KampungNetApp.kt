package id.hivenet.app

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.hivenet.shared.chat.ChatMessageItem
import id.hivenet.shared.chat.ContactItem
import id.hivenet.shared.chat.EncryptedChatRepository
import id.hivenet.shared.identity.LocalIdentity
import id.hivenet.shared.identity.LocalIdentityRepository
import id.hivenet.shared.time.SystemClock
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlinx.coroutines.delay
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.cos
import kotlin.math.sin

private const val BROADCAST_ID = "kampung-broadcast"
private const val ME_ID = "me"
private const val MAX_MESH_ENVELOPE_BYTES = 48_000

// ── Design tokens ────────────────────────────────────────────────────────────

private val AppBg: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF080E1C) else Color(0xFFF1F5F9)
private val CardBg: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF111827) else Color.White
private val Primary: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF818CF8) else Color(0xFF4F46E5)
private val Ink: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFFF9FAFB) else Color(0xFF0F172A)
private val Muted: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF64748B) else Color(0xFF94A3B8)
private val Danger: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFFF87171) else Color(0xFFDC2626)
private val Amber: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFFFBBF24) else Color(0xFFD97706)
private val PrimarySubtle: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF1E2038) else Color(0xFFEEF2FF)

// ── Enums / models (unchanged) ───────────────────────────────────────────────

private enum class Screen { Welcome, Onboarding, Home, Settings, Profile, NewChoice, NewChat, NewGroup, Chat, GroupInfo, CryptoDebug, PairContact, EncryptedChatList, EncryptedChat, MeshDebug }
private enum class RoomTab { Chat, Sos, Ht }
private enum class ThreadFilter { All, Groups, Personal }

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

// ── Helpers (unchanged) ───────────────────────────────────────────────────────

@OptIn(ExperimentalEncodingApi::class)
private fun String.toBase64(): String = Base64.encode(encodeToByteArray())

@OptIn(ExperimentalEncodingApi::class)
private fun String.fromBase64OrNull(): String? = runCatching { Base64.decode(this).decodeToString() }.getOrNull()

private fun encodePairingEnvelope(type: String, json: String): String = json
private fun encodeChatEnvelope(json: String): String = "KNET1:CHAT:${json.toBase64()}"
private fun encodeReceiptEnvelope(json: String): String = "KNET1:RECEIPT:${json.toBase64()}"

private fun decodePairingEnvelope(input: String, expectedType: String): String? {
    val trimmed = input.trim()
    if (trimmed.startsWith("{") && trimmed.contains("\"sessionId\"", ignoreCase = true)) {
        val expectedKey = if (expectedType == "PAIRING_ACCEPT") "\"responderPeerId\"" else "\"senderPeerId\""
        return if (trimmed.contains(expectedKey, ignoreCase = true)) trimmed else null
    }
    val parts = trimmed.split(":", limit = 3)
    if (parts.size != 3 || parts[0] != "KNET1" || parts[1] != expectedType) return null
    return parts[2].fromBase64OrNull()
}
private fun detectPairingEnvelopeType(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.startsWith("{") && trimmed.contains("\"sessionId\"", ignoreCase = true)) {
        return when {
            trimmed.contains("\"responderPeerId\"", ignoreCase = true) -> "PAIRING_ACCEPT"
            trimmed.contains("\"senderPeerId\"", ignoreCase = true) -> "PAIRING_OFFER"
            else -> null
        }
    }
    val parts = trimmed.split(":", limit = 3)
    return if (parts.size == 3 && parts[0] == "KNET1" && parts[1].startsWith("PAIRING_")) parts[1] else null
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
            '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n")
            '\r' -> append("\\r"); '\t' -> append("\\t"); else -> append(char)
        }
    }
}

private fun chatPacketJson(packetId: String, encryptedPayloadJson: String): String =
    "{\"packet_id\":\"${jsonEscape(packetId)}\",\"payload\":$encryptedPayloadJson}"

private fun chatPacketJson(packetId: String, sourcePeerId: String, targetPeerId: String?, encryptedPayloadJson: String): String = buildString {
    append("{\"packet_id\":\""); append(jsonEscape(packetId)); append("\",\"source_peer_id\":\""); append(jsonEscape(sourcePeerId)); append("\"")
    if (!targetPeerId.isNullOrBlank()) { append(",\"target_peer_id\":\""); append(jsonEscape(targetPeerId)); append("\"") }
    append(",\"payload\":"); append(encryptedPayloadJson); append("}")
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
    else -> "${peerId.take(6)}…${peerId.takeLast(4)}"
}

private fun formatClockTime(epochMillis: Long): String {
    val totalMinutes = (epochMillis / 60_000L).mod(24L * 60L)
    val hour = totalMinutes / 60L
    val minute = totalMinutes % 60L
    return "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
}

private data class ReceivedChatPacket(val fromPeerId: String, val envelope: String)
private data class ReceivedReceiptPacket(val fromPeerId: String, val envelope: String)
private data class SecureThreadPreview(val contact: ContactItem, val lastMessage: ChatMessageItem?)

private fun stableEnvelopeId(value: String): String {
    var hash = 0xcbf29ce484222325UL
    value.encodeToByteArray().forEach { byte -> hash = hash xor byte.toUByte().toULong(); hash *= 0x100000001b3UL }
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
    if (clean.isBlank() || clean == "Belum ada peer connected" || clean == "Belum ada peer tersambung") return 0
    return clean.lines().map { it.trim() }.count { it.isNotBlank() }
}
private fun meshPeerSummary(peersText: String): String {
    val count = meshPeerCount(peersText)
    return when (count) {
        0 -> "No peers connected. Move closer, enable WiFi/Bluetooth, then start mesh on both devices."
        1 -> "1 peer connected\n${peersText.trim()}"
        else -> "$count peers connected\n${peersText.trim()}"
    }
}
private fun meshStatusValue(statusText: String, key: String): String? =
    statusText.lines().firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.trim()?.takeIf { it.isNotBlank() }
private fun meshReadyForBroadcast(bridge: MeshBridge): String? {
    val status = bridge.status()
    if (!status.ok) return "Mesh not ready: ${status.error.orEmpty()}"
    val value = status.value.orEmpty()
    val state = meshStatusValue(value, "state")
    if (state != null && state != "active") return "Mesh not active (state=$state). Open Local Mesh and press Start."
    if (state == null && (value.contains("Stopped", ignoreCase = true) || value.contains("local=-"))) return "Mesh not started. Open Local Mesh and press Start."
    return null
}
private fun meshEnvelopeSizeError(envelope: String): String? {
    val bytes = envelope.encodeToByteArray().size
    return if (bytes > MAX_MESH_ENVELOPE_BYTES) "Packet too large ($bytes bytes). Max $MAX_MESH_ENVELOPE_BYTES bytes." else null
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

// ── Root composable ───────────────────────────────────────────────────────────

@OptIn(ExperimentalEncodingApi::class)
@Composable
fun KampungNetApp(
    cryptoBridge: CryptoBridge? = null,
    meshBridge: MeshBridge? = null,
    qrScannerBridge: QrScannerBridge? = null,
    notificationBridge: NotificationBridge? = null,
    chatRepository: EncryptedChatRepository? = null,
    identityRepository: LocalIdentityRepository? = null,
) {
    val encryptedChatRepository = chatRepository
    var localIdentity by remember { mutableStateOf(identityRepository?.get()) }
    var contactVersion by remember { mutableStateOf(0) }
    val peers = remember(contactVersion, encryptedChatRepository) {
        encryptedChatRepository?.trustedContacts().orEmpty().map { contact ->
            Peer(contact.peerId, contact.displayName, contact.peerId)
        }
    }
    var screen by remember { mutableStateOf(if (localIdentity == null) Screen.Welcome else Screen.Home) }
    var selectedThreadId by remember { mutableStateOf<String?>(null) }
    var pendingThread by remember { mutableStateOf<ChatThread?>(null) }
    var selectedEncryptedPeerId by remember { mutableStateOf<String?>(null) }
    var animationsEnabled by remember { mutableStateOf(true) }
    var notificationsEnabled by remember { mutableStateOf(true) }
    var notificationPreviewEnabled by remember { mutableStateOf(true) }
    var sosNotificationsEnabled by remember { mutableStateOf(true) }
    var roomTab by remember { mutableStateOf(RoomTab.Chat) }
    var showSosSheet by remember { mutableStateOf(false) }
    var sosOverlay by remember { mutableStateOf<SOSOverlayState?>(null) }
    var nextThreadId by remember { mutableStateOf(1) }
    var nextMessageId by remember { mutableStateOf(100) }
    var nextHtId by remember { mutableStateOf(1) }
    val threads = remember { mutableStateListOf<ChatThread>() }
    val strings = remember { appStringsFor(deviceLanguageCode()) }

    fun updateThread(id: String, block: (ChatThread) -> ChatThread) {
        val index = threads.indexOfFirst { it.id == id }
        if (index >= 0) threads[index] = block(threads[index])
    }
    fun openThread(id: String, tab: RoomTab = RoomTab.Chat) { pendingThread = null; selectedThreadId = id; roomTab = tab; screen = Screen.Chat }
    fun openPendingThread(thread: ChatThread, tab: RoomTab = RoomTab.Chat) { pendingThread = thread; selectedThreadId = null; roomTab = tab; screen = Screen.Chat }
    fun backHome() { screen = Screen.Home; selectedThreadId = null; pendingThread = null; selectedEncryptedPeerId = null; roomTab = RoomTab.Chat }
    fun handleBack() {
        when {
            sosOverlay != null -> sosOverlay = null
            showSosSheet -> showSosSheet = false
            screen == Screen.GroupInfo -> screen = Screen.Chat
            screen == Screen.EncryptedChat -> screen = Screen.EncryptedChatList
            screen == Screen.Profile -> backHome()
            screen == Screen.Onboarding && localIdentity == null -> screen = Screen.Welcome
            screen == Screen.NewChat || screen == Screen.NewGroup || screen == Screen.NewChoice -> backHome()
            screen != Screen.Home -> backHome()
        }
    }
    fun selectedThread(): ChatThread? = pendingThread ?: selectedThreadId?.let { id -> threads.firstOrNull { it.id == id } }

    val darkMode = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (darkMode) darkColorScheme(primary = Primary, background = AppBg, surface = CardBg, onSurface = Ink, error = Danger)
                      else lightColorScheme(primary = Primary, background = AppBg, surface = CardBg, onSurface = Ink, error = Danger)
    ) {
        PlatformBackHandler(enabled = (screen != Screen.Home && screen != Screen.Welcome) || showSosSheet || sosOverlay != null) { handleBack() }
        Box(Modifier.fillMaxSize().background(AppBg).navigationBarsPadding()) {
            @Composable
            fun renderScreen(activeScreen: Screen) {
            when (activeScreen) {
                Screen.Welcome -> WelcomeScreen(onRegister = { screen = Screen.Onboarding })
                Screen.Onboarding -> OnboardingScreen(identityRepository, onBack = { screen = Screen.Welcome }, onCreated = { identity -> localIdentity = identity; screen = Screen.Home })
                Screen.Home -> HomeScreen(strings, threads, localIdentity, onOpen = ::openThread, onDelete = { threadId -> threads.removeAll { it.id == threadId } }, onNewChat = { screen = Screen.NewChat }, onNewGroup = { screen = Screen.NewGroup }, onGlobalSos = { showSosSheet = true }, onSettings = { screen = Screen.Settings }, onProfile = { screen = Screen.Profile }, onCrypto = { screen = Screen.CryptoDebug }, onPair = { screen = Screen.PairContact }, onEncryptedChat = { screen = Screen.EncryptedChatList }, onMesh = { screen = Screen.MeshDebug })
                Screen.Settings -> SettingsScreen(
                    notificationBridge = notificationBridge,
                    strings = strings,
                    animationsEnabled = animationsEnabled,
                    notificationsEnabled = notificationsEnabled,
                    notificationPreviewEnabled = notificationPreviewEnabled,
                    sosNotificationsEnabled = sosNotificationsEnabled,
                    onAnimationsChanged = { animationsEnabled = it },
                    onNotificationsChanged = { notificationsEnabled = it },
                    onNotificationPreviewChanged = { notificationPreviewEnabled = it },
                    onSosNotificationsChanged = { sosNotificationsEnabled = it },
                    onBack = ::backHome,
                    onProfile = { screen = Screen.Profile },
                    onPair = { screen = Screen.PairContact },
                    onMesh = { screen = Screen.MeshDebug },
                )
                Screen.Profile -> ProfileScreen(localIdentity, identityRepository, onBack = ::backHome, onSaved = { localIdentity = it })
                Screen.NewChoice -> NewChoiceScreen(onBack = ::backHome, onChat = { screen = Screen.NewChat }, onGroup = { screen = Screen.NewGroup })
                Screen.NewChat -> NewChatScreen(strings, peers, onBack = ::backHome, onAddContact = { screen = Screen.PairContact }) { peer, first ->
                    val id = "direct-${nextThreadId++}"
                    val thread = ChatThread(id, peer.name, peer.peerId, false, messages = if (first.isBlank()) emptyList() else listOf(ChatMessage(nextMessageId++, "Me", first.trim(), "Just now", true, "queued")))
                    if (thread.messages.isEmpty()) openPendingThread(thread) else { threads.add(0, thread); openThread(id) }
                }
                Screen.NewGroup -> NewGroupScreen(peers, onBack = ::backHome) { name, chosen ->
                    val id = "group-${nextThreadId++}"
                    val members = listOf(Member(ME_ID, "Me", "local-device", true)) + chosen.map { Member(it.id, it.name, it.peerId, it.id == "pak-rt") }
                    threads.add(0, ChatThread(id, name.trim().ifBlank { "New Group" }, "/kampungnet/v1/group/$id", true, members = members, messages = listOf(ChatMessage(nextMessageId++, "Me", "Group created. ${members.size} members joined.", "Just now", true, "created"))))
                    openThread(id)
                }
                Screen.Chat -> {
                    val thread = selectedThread()
                    if (thread == null) backHome() else ChatScreen(
                        thread = thread, tab = roomTab, onTab = { roomTab = it }, onBack = ::backHome, onInfo = { screen = Screen.GroupInfo },
                        onSend = { body ->
                            val message = ChatMessage(nextMessageId++, "Me", body, "Just now", true, if (thread.group) "sent" else "sent")
                            if (pendingThread?.id == thread.id) {
                                val saved = thread.copy(messages = thread.messages + message)
                                threads.add(0, saved)
                                pendingThread = null
                                selectedThreadId = saved.id
                            } else {
                                updateThread(thread.id) { it.copy(messages = it.messages + message) }
                            }
                        },
                        onDeleteMessage = { messageId -> updateThread(thread.id) { it.copy(messages = it.messages.filterNot { message -> message.id == messageId }) } },
                        onSos = { showSosSheet = true },
                        onPeerSos = { sosOverlay = SOSOverlayState(thread.name, thread.peerId, "Incoming SOS from ${thread.name}.") },
                        onHtSend = { updateThread(thread.id) { it.copy(htMessages = it.htMessages + HtMessage(nextHtId++, "Me", 3 + (nextHtId % 5), "Just now", true)) } },
                        onHtReply = { updateThread(thread.id) { it.copy(htMessages = it.htMessages + HtMessage(nextHtId++, it.members.firstOrNull { m -> !m.admin }?.name ?: "Peer", 2 + (nextHtId % 4), "Just now", false)) } }
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
                Screen.PairContact -> PairContactScreen(cryptoBridge, qrScannerBridge, encryptedChatRepository, identityRepository, onPaired = { contactVersion += 1 }, onBack = ::backHome)
                Screen.EncryptedChatList -> EncryptedChatListScreen(encryptedChatRepository, onBack = ::backHome, onPair = { screen = Screen.PairContact }, onOpen = { peerId -> selectedEncryptedPeerId = peerId; screen = Screen.EncryptedChat })
                Screen.EncryptedChat -> EncryptedChatScreen(cryptoBridge, meshBridge, encryptedChatRepository, identityRepository, initialContactPeerId = selectedEncryptedPeerId, onBack = { screen = Screen.EncryptedChatList })
                Screen.MeshDebug -> MeshDebugScreen(cryptoBridge, meshBridge, notificationBridge, notificationsEnabled, notificationPreviewEnabled, encryptedChatRepository, onBack = ::backHome)
            }
            }
            if (animationsEnabled) Crossfade(targetState = screen, animationSpec = tween(180), label = "screen") { renderScreen(it) } else renderScreen(screen)

            if (showSosSheet) {
                val target = selectedThread()
                SOSSheet(targetName = target?.name ?: "All Broadcasts", onCancel = { showSosSheet = false }) { body ->
                    showSosSheet = false
                    val targetId = target?.id ?: BROADCAST_ID
                    updateThread(targetId) { it.copy(messages = it.messages + ChatMessage(nextMessageId++, "Me", "SOS: $body", "Just now", true, "SOS ttl=5")) }
                    openThread(targetId)
                }
            }
            sosOverlay?.let { state -> SOSOverlay(state, onMute = { sosOverlay = null }, onBlacklist = { updateThread("pak-rt") { it.copy(blacklisted = true) }; sosOverlay = null }) }
        }
    }
}

// ── Onboarding / Profile ──────────────────────────────────────────────────────

@Composable
private fun WelcomeScreen(onRegister: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(AppBg).statusBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HiveLogo(Modifier.size(88.dp))
        Spacer(Modifier.height(20.dp))
        Text("Selamat datang di HiveNet", color = Ink, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Messenger mesh offline-first untuk komunikasi lokal saat jaringan terbatas.",
            color = Muted,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onRegister,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp)
        ) { Text("Daftar") }
        Spacer(Modifier.height(10.dp))
        Text("Identitas tersimpan lokal di perangkat ini.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun OnboardingScreen(repository: LocalIdentityRepository?, onBack: () -> Unit, onCreated: (LocalIdentity) -> Unit) {
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().background(AppBg).statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Buat Identitas HiveNet", color = Ink, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Identitas ini dipakai sebagai nama awal saat pairing. Email opsional untuk backup nanti.", color = Muted)
        OutlinedTextField(name, { name = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Nama *") }, singleLine = true)
        OutlinedTextField(role, { role = it }, Modifier.fillMaxWidth(), label = { Text("Jabatan / peran (opsional)") }, singleLine = true)
        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email backup (opsional)") }, singleLine = true)
        Text("Device label dibuat otomatis. Recovery code ditampilkan setelah identitas dibuat.", color = Muted, style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = Danger, fontWeight = FontWeight.SemiBold) }
        Button(
            onClick = {
                val repo = repository
                when {
                    repo == null -> error = "Database lokal belum siap."
                    name.trim().isBlank() -> error = "Nama wajib diisi."
                    else -> onCreated(repo.create(name, role, email))
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) { Text("Buat Identitas") }
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) { Text("Kembali") }
    }
}

@Composable
private fun ProfileScreen(identity: LocalIdentity?, repository: LocalIdentityRepository?, onBack: () -> Unit, onSaved: (LocalIdentity) -> Unit) {
    var name by remember(identity?.peerId) { mutableStateOf(identity?.displayName.orEmpty()) }
    var role by remember(identity?.peerId) { mutableStateOf(identity?.role.orEmpty()) }
    var email by remember(identity?.peerId) { mutableStateOf(identity?.email.orEmpty()) }
    var status by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(AppBg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().background(CardBg).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = Ink, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp))
            Text("Profile", color = Ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (identity == null) {
                Text("Identitas belum dibuat.", color = Danger)
                Button(onBack) { Text("Kembali") }
                return@Column
            }
            OutlinedTextField(name, { name = it; status = null }, Modifier.fillMaxWidth(), label = { Text("Nama") }, singleLine = true)
            OutlinedTextField(role, { role = it; status = null }, Modifier.fillMaxWidth(), label = { Text("Jabatan / peran") }, singleLine = true)
            OutlinedTextField(email, { email = it; status = null }, Modifier.fillMaxWidth(), label = { Text("Email backup") }, singleLine = true)
            ProfileValue("Peer ID", identity.peerId)
            ProfileValue("Device", identity.deviceLabel)
            ProfileValue("Recovery code", identity.recoveryCode)
            Text("Simpan recovery code. Versi ini baru menyimpan kode lokal; restore penuh identity/key belum aktif.", color = Amber, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            status?.let { Text(it, color = if (it.startsWith("Saved")) Primary else Danger, fontWeight = FontWeight.SemiBold) }
            Button(
                onClick = {
                    val repo = repository
                    when {
                        repo == null -> status = "Database lokal belum siap."
                        name.trim().isBlank() -> status = "Nama wajib diisi."
                        else -> repo.updateProfile(name, role, email)?.let { onSaved(it); status = "Saved." }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) { Text("Simpan Profile") }
        }
    }
}

@Composable
private fun ProfileValue(label: String, value: String) {
    Surface(Modifier.fillMaxWidth(), color = CardBg, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = Muted, style = MaterialTheme.typography.bodySmall)
            Text(value, color = Ink, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SettingsScreen(
    notificationBridge: NotificationBridge?,
    strings: AppStrings,
    animationsEnabled: Boolean,
    notificationsEnabled: Boolean,
    notificationPreviewEnabled: Boolean,
    sosNotificationsEnabled: Boolean,
    onAnimationsChanged: (Boolean) -> Unit,
    onNotificationsChanged: (Boolean) -> Unit,
    onNotificationPreviewChanged: (Boolean) -> Unit,
    onSosNotificationsChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
    onProfile: () -> Unit,
    onPair: () -> Unit,
    onMesh: () -> Unit,
) {
    var notificationStatus by remember { mutableStateOf(if (notificationBridge == null) "Notification bridge not available." else "Notification ready.") }
    Column(Modifier.fillMaxSize()) {
        Header(strings.settings, strings.settingsSubtitle, onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(Modifier.fillMaxWidth(), color = CardBg, shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(strings.pageAnimation, color = Ink, fontWeight = FontWeight.Bold)
                        Text(strings.pageAnimationHint, color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = animationsEnabled, onCheckedChange = onAnimationsChanged)
                }
            }
            Surface(Modifier.fillMaxWidth(), color = CardBg, shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(strings.notifications, color = Ink, fontWeight = FontWeight.Bold)
                            Text(strings.notificationsHint, color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = notificationsEnabled, onCheckedChange = onNotificationsChanged)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(strings.showMessagePreview, color = Ink, fontWeight = FontWeight.SemiBold)
                            Text(strings.showMessagePreviewHint, color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = notificationPreviewEnabled, onCheckedChange = onNotificationPreviewChanged, enabled = notificationsEnabled)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(strings.sosAlerts, color = Ink, fontWeight = FontWeight.SemiBold)
                            Text(strings.sosAlertsHint, color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = sosNotificationsEnabled, onCheckedChange = onSosNotificationsChanged, enabled = notificationsEnabled)
                    }
                    OutlinedButton(
                        onClick = {
                            val bridge = notificationBridge
                            if (bridge == null) notificationStatus = "Notification permission failed: bridge not available."
                            else bridge.requestPermissionIfNeeded { result -> notificationStatus = if (result.ok) result.value.orEmpty() else "Notification permission failed: ${result.error.orEmpty()}" }
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(999.dp),
                    ) { Text(strings.allowNotifications) }
                    Text(notificationStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(strings.shortcuts, color = Muted, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            BigAction(strings.profile, "Nama, jabatan, email backup, recovery code", onProfile)
            BigAction(strings.addContact, strings.addContactHint, onPair)
            BigAction(strings.localMesh, strings.localMeshHint, onMesh)
            InfoCard("Security", strings.securityHint)
        }
    }
}

// ── Home ──────────────────────────────────────────────────────────────────────

@Composable
private fun HomeScreen(
    strings: AppStrings,
    threads: List<ChatThread>,
    identity: LocalIdentity?,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNewChat: () -> Unit,
    onNewGroup: () -> Unit,
    onGlobalSos: () -> Unit,
    onSettings: () -> Unit,
    onProfile: () -> Unit,
    onCrypto: () -> Unit,
    onPair: () -> Unit,
    onEncryptedChat: () -> Unit,
    onMesh: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ThreadFilter.All) }
    val currentUserName = identity?.displayName ?: threads.firstOrNull { it.id == BROADCAST_ID }?.members?.firstOrNull { it.id == ME_ID }?.name ?: "Me"
    val filteredThreads = when (filter) {
        ThreadFilter.All -> threads
        ThreadFilter.Groups -> threads.filter { it.group }
        ThreadFilter.Personal -> threads.filterNot { it.group }
    }
    val query = searchQuery.trim()
    val visibleThreads = if (query.isBlank()) filteredThreads else filteredThreads.filter { thread ->
        thread.name.contains(query, ignoreCase = true) ||
            thread.peerId.contains(query, ignoreCase = true) ||
            thread.members.any { it.name.contains(query, ignoreCase = true) || it.peerId.contains(query, ignoreCase = true) } ||
            thread.messages.any { it.sender.contains(query, ignoreCase = true) || it.body.contains(query, ignoreCase = true) }
    }

    Box(Modifier.fillMaxSize().background(AppBg)) {
        Column(Modifier.fillMaxSize()) {
        // Header
        Column(
            Modifier.fillMaxWidth().background(CardBg).statusBarsPadding()
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("HiveNet", color = Ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(currentUserName, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onGlobalSos,
                    Modifier.height(40.dp),
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Danger),
                    elevation = ButtonDefaults.buttonElevation(0.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp)
                ) { Text("SOS", fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(4.dp))
                Box {
                    Surface(
                        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).clickable { menuOpen = true },
                        color = AppBg
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("⋮", color = Ink, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(strings.search) }, onClick = { menuOpen = false; searchOpen = true })
                        DropdownMenuItem(text = { Text(strings.settings) }, onClick = { menuOpen = false; onSettings() })
                        DropdownMenuItem(text = { Text(strings.profile) }, onClick = { menuOpen = false; onProfile() })
                        DropdownMenuItem(text = { Text(strings.newMessage) }, onClick = { menuOpen = false; onNewChat() })
                        DropdownMenuItem(text = { Text(strings.newGroup) }, onClick = { menuOpen = false; onNewGroup() })
                        DropdownMenuItem(text = { Text(strings.addContact) }, onClick = { menuOpen = false; onPair() })
                        DropdownMenuItem(text = { Text(strings.encryptedChat) }, onClick = { menuOpen = false; onEncryptedChat() })
                        DropdownMenuItem(text = { Text(strings.localMesh) }, onClick = { menuOpen = false; onMesh() })
                        DropdownMenuItem(text = { Text(strings.cryptoDebug) }, onClick = { menuOpen = false; onCrypto() })
                    }
                }
            }
            if (searchOpen) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(strings.searchPlaceholder) },
                    singleLine = true,
                    trailingIcon = {
                        Text(
                            "×",
                            color = Muted,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(CircleShape).clickable { searchQuery = ""; searchOpen = false }.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    },
                    shape = RoundedCornerShape(999.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(strings.all, filter == ThreadFilter.All, Modifier.weight(1f)) { filter = ThreadFilter.All }
                FilterChip(strings.groups, filter == ThreadFilter.Groups, Modifier.weight(1f)) { filter = ThreadFilter.Groups }
                FilterChip(strings.personal, filter == ThreadFilter.Personal, Modifier.weight(1f)) { filter = ThreadFilter.Personal }
            }
        }

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(visibleThreads, key = { it.id }) { ChatRow(it, onDelete = { onDelete(it.id) }) { onOpen(it.id) } }
            }
        }
        Surface(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp).size(56.dp).clip(CircleShape).clickable(onClick = onNewChat),
            color = Primary,
            shadowElevation = 8.dp
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("+", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
    }
}

// ── Mesh Debug ────────────────────────────────────────────────────────────────

@Composable
private fun MeshDebugScreen(
    cryptoBridge: CryptoBridge?,
    meshBridge: MeshBridge?,
    notificationBridge: NotificationBridge?,
    notificationsEnabled: Boolean,
    notificationPreviewEnabled: Boolean,
    repository: EncryptedChatRepository?,
    onBack: () -> Unit,
) {
    var localPeerId by remember { mutableStateOf("iphone-a") }
    var envelope by remember { mutableStateOf("KNET1:CHAT:paste-packet-here") }
    var received by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(if (meshBridge == null) "Mesh bridge not available on this platform." else "Ready. Enter peer ID then tap Start.") }
    var autoSync by remember { mutableStateOf(false) }
    var meshStatus by remember { mutableStateOf("Not started") }
    var peersText by remember { mutableStateOf("No peers connected") }
    var lastSyncSummary by remember { mutableStateOf("Not synced yet. Tap Start Mesh + Auto-Sync.") }

    fun run(label: String, block: () -> MeshBridgeResult) {
        val bridge = meshBridge
        if (bridge == null) { status = "$label failed: bridge not available"; return }
        val result = block()
        status = if (result.ok) "$label succeeded\n${result.value.orEmpty()}" else "$label failed\n${result.error.orEmpty()}"
    }

    fun forwardRelayCache(): String {
        val bridge = meshBridge ?: return "Forward failed: bridge not available"
        val repo = repository ?: return "Forward failed: database not available"
        meshReadyForBroadcast(bridge)?.let { return "Forward held: $it" }
        val relayPackets = repo.relayPackets(SystemClock.nowMillis())
        if (relayPackets.isEmpty()) return "No relay cache to forward."
        var forwarded = 0; var failed: String? = null
        relayPackets.forEach { packet ->
            if (failed == null) {
                val relayEnvelope = when (packet.topic) { "CHAT" -> encodeChatEnvelope(packet.payload); "RECEIPT" -> encodeReceiptEnvelope(packet.payload); else -> null }
                val sizeError = relayEnvelope?.let { meshEnvelopeSizeError(it) }
                val result = if (sizeError == null) relayEnvelope?.let { bridge.broadcast(it) } else null
                if (result?.ok == true) { forwarded += 1; repo.markRelayPacketForwarded(packet.packetId) }
                else failed = "${packet.packetId}: ${sizeError ?: result?.error ?: "topic ${packet.topic} not supported"}"
            }
        }
        return if (failed == null) "Relay forwarded. count=$forwarded." else "Relay partial. forwarded=$forwarded, failed=$failed"
    }

    fun sendPendingOutbox(): String {
        val bridge = meshBridge ?: return "Outbox failed: bridge not available"
        val repo = repository ?: return "Outbox failed: database not available"
        meshReadyForBroadcast(bridge)?.let { return "Outbox held: $it" }
        val pending = repo.retryableOutboxPackets(SystemClock.nowMillis())
        if (pending.isEmpty()) return "No pending outbox."
        var sent = 0; var failed: String? = null
        pending.forEach { packet ->
            if (failed == null) {
                val env = encodeChatEnvelope(chatPacketJson(packet.packetId, localPeerId.trim().ifBlank { "local-device" }, packet.targetPeerId, packet.payload))
                val sizeError = meshEnvelopeSizeError(env)
                val result = if (sizeError == null) bridge.broadcast(env) else null
                if (result?.ok == true) { repo.markOutboxPacketRelayed(packet.packetId, SystemClock.nowMillis()); sent += 1 }
                else failed = "${packet.packetId}: ${sizeError ?: result?.error.orEmpty()}"
            }
        }
        return if (failed == null) "Outbox broadcast. sent=$sent." else "Outbox partial. sent=$sent, failed=$failed"
    }

    fun refreshMeshSnapshot(): String {
        val bridge = meshBridge ?: return "Bridge not available"
        meshStatus = bridge.status().let { if (it.ok) it.value.orEmpty() else it.error.orEmpty() }
        peersText = bridge.peers().let { if (it.ok) it.value.orEmpty() else it.error.orEmpty() }
        return "Status refreshed."
    }

    fun drainIncomingPackets(): String {
        val bridge = meshBridge ?: return "Receive failed: bridge not available"
        val crypto = cryptoBridge; val repo = repository
        val result = bridge.drainReceived()
        if (!result.ok) return "Receive failed\n${result.error.orEmpty()}"
        received = result.value.orEmpty()
        val packets = parseReceivedChatPackets(received)
        val receipts = parseReceivedReceiptPackets(received)
        if (packets.isEmpty() && receipts.isEmpty()) return "Receive OK. No KNET1 packets."
        if (crypto == null && packets.isNotEmpty()) return "Received, but decrypt failed: crypto bridge not available. packets=${packets.size}"
        if (repo == null) return "Received, but save failed: database not available. packets=${packets.size}, receipts=${receipts.size}"
        val localId = localPeerId.trim().ifBlank { "local-device" }
        var decrypted = 0; var cachedForRelay = 0; var deliveryReceipts = 0; var receiptRelays = 0; var duplicates = 0; var failed: String? = null
        receipts.forEach { receipt ->
            if (failed == null) {
                val json = decodeReceiptEnvelope(receipt.envelope) ?: run { failed = "${receipt.fromPeerId}: invalid KNET1:RECEIPT"; return@forEach }
                val seenId = receiptSeenPacketId(json, receipt.envelope)
                if (repo.hasSeenMeshPacket(seenId, SystemClock.nowMillis())) { duplicates += 1; return@forEach }
                repo.markSeenMeshPacket(seenId, SystemClock.nowMillis())
                val packetId = extractJsonValue(json, "packet_id") ?: meshPacketId(receipt.envelope)
                val messageId = extractJsonValue(json, "message_id") ?: packetId
                val senderPeerId = extractJsonValue(json, "sender_peer_id") ?: receipt.fromPeerId
                val targetPeerId = extractJsonValue(json, "target_peer_id") ?: localId
                val receiptStatus = extractJsonValue(json, "status") ?: "DELIVERED"
                val receiptTimestamp = extractJsonLong(json, "timestamp") ?: SystemClock.nowMillis()
                if (senderPeerId == localId) { duplicates += 1; return@forEach }
                repo.saveDeliveryReceipt(messageId, packetId, senderPeerId, targetPeerId, receiptStatus, receiptTimestamp)
                if (targetPeerId == localId && receiptStatus == "DELIVERED") repo.markDeliveredFromReceipt(packetId, SystemClock.nowMillis())
                if (targetPeerId != localId) { repo.saveRelayReceiptPacket(relayReceiptPacketId(receipt.envelope), receipt.fromPeerId, targetPeerId, json, SystemClock.nowMillis()); receiptRelays += 1 }
                deliveryReceipts += 1
            }
        }
        packets.forEach { packet ->
            if (failed == null) {
                val payloadJson = decodeChatEnvelope(packet.envelope) ?: run { failed = "${packet.fromPeerId}: invalid KNET1:CHAT"; return@forEach }
                val decryptor = crypto ?: run { failed = "${packet.fromPeerId}: crypto bridge not available"; return@forEach }
                val seenId = chatSeenPacketId(payloadJson, packet.envelope)
                if (repo.hasSeenMeshPacket(seenId, SystemClock.nowMillis())) { duplicates += 1; return@forEach }
                val sourcePeerId = chatPacketSourcePeerId(payloadJson) ?: packet.fromPeerId
                val targetPeerId = chatPacketTargetPeerId(payloadJson)
                val encryptedPayloadJson = chatPacketPayloadJson(payloadJson)
                if (sourcePeerId == localId) { repo.markSeenMeshPacket(seenId, SystemClock.nowMillis()); duplicates += 1; return@forEach }
                if (!targetPeerId.isNullOrBlank() && targetPeerId != localId) {
                    repo.markSeenMeshPacket(seenId, SystemClock.nowMillis())
                    repo.saveRelayChatPacket(relayPacketId(packet.envelope), sourcePeerId, targetPeerId, payloadJson, SystemClock.nowMillis())
                    cachedForRelay += 1; return@forEach
                }
                val aad = "kampungnet-chat-v1:$sourcePeerId".toBase64()
                val decryptResult = decryptor.decrypt(sourcePeerId, encryptedPayloadJson, aad)
                if (decryptResult.ok) {
                    repo.markSeenMeshPacket(seenId, SystemClock.nowMillis())
                    val plaintext = decryptResult.value.orEmpty().fromBase64OrNull().orEmpty()
                    repo.saveIncomingDecryptedChat(localId, sourcePeerId, plaintext, SystemClock.nowMillis())
                    if (notificationsEnabled) {
                        notificationBridge?.showMessageNotification(
                            threadId = sourcePeerId,
                            senderName = sourcePeerId,
                            preview = plaintext,
                            showPreview = notificationPreviewEnabled,
                        )
                    }
                    decrypted += 1
                    val receiptJson = deliveryReceiptJson(chatPacketId(payloadJson) ?: meshPacketId(packet.envelope), localId, sourcePeerId, SystemClock.nowMillis())
                    bridge.broadcast(encodeReceiptEnvelope(receiptJson))
                } else {
                    repo.markSeenMeshPacket(seenId, SystemClock.nowMillis())
                    repo.saveRelayChatPacket(relayPacketId(packet.envelope), sourcePeerId, targetPeerId, payloadJson, SystemClock.nowMillis())
                    cachedForRelay += 1
                }
            }
        }
        val summary = if (failed == null) "OK. decrypted=$decrypted, relay=$cachedForRelay, receipts=$deliveryReceipts, rcpt-relay=$receiptRelays, dupes=$duplicates."
                      else "Partial. decrypted=$decrypted, relay=$cachedForRelay, receipts=$deliveryReceipts, rcpt-relay=$receiptRelays, dupes=$duplicates, failed=$failed"
        return if ((cachedForRelay > 0 || receiptRelays > 0) && failed == null) "$summary\nAuto-forward: ${forwardRelayCache()}" else summary
    }

    fun syncOnce(): String {
        val r = drainIncomingPackets(); val o = sendPendingOutbox(); val f = forwardRelayCache(); refreshMeshSnapshot()
        return "Sync done\n$r\n$o\n$f".also { lastSyncSummary = it }
    }

    LaunchedEffect(autoSync, localPeerId) {
        while (autoSync) {
            val r = drainIncomingPackets(); val o = sendPendingOutbox(); val f = forwardRelayCache()
            refreshMeshSnapshot()
            lastSyncSummary = "Last sync\n$r\n$o\n$f"
            status = "Auto-sync active. Peers: ${meshPeerCount(peersText)}. Polling every 3s."
            delay(3_000)
        }
    }

    FormScaffold("Local Mesh", "LAN / hotspot mesh relay", onBack) {
        InfoCard("Field Guide", "1. Enter a unique Peer ID on each device.\n2. Connect to the same WiFi or hotspot.\n3. Tap Start Mesh + Auto-Sync on all devices and keep this screen open.")
        OutlinedTextField(localPeerId, { localPeerId = it }, Modifier.fillMaxWidth(), label = { Text("My peer ID") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        PrimaryButton("Start Mesh + Auto-Sync", color = Color(0xFF0F766E)) {
            val bridge = meshBridge
            if (bridge == null) { status = "Start failed: bridge not available" }
            else {
                val result = bridge.start(localPeerId.trim().ifBlank { "device" })
                autoSync = result.ok; refreshMeshSnapshot()
                status = if (result.ok) "Mesh active. Auto-sync running.\n${result.value.orEmpty()}" else "Start failed\n${result.error.orEmpty()}"
            }
        }
        PrimaryButton("Sync Now", color = Color(0xFF0891B2)) { status = syncOnce() }
        PrimaryButton(if (autoSync) "Stop Auto-Sync" else "Start Auto-Sync", color = if (autoSync) Danger else Color(0xFF0369A1)) {
            autoSync = !autoSync
            status = if (autoSync) "Auto-sync enabled. Keep this screen active." else "Auto-sync stopped."
        }

        val pendingCount = repository?.pendingOutboxPackets(SystemClock.nowMillis())?.size
        val relayCount = repository?.relayPackets(SystemClock.nowMillis())?.size
        val recentOutbox = repository?.recentOutboxPackets()?.joinToString("\n") { "${it.packetId} → ${it.targetPeerId ?: "?"} [${it.status}]" }?.ifBlank { "No outbox yet." }
        val recentReceipts = repository?.recentDeliveryReceipts()?.joinToString("\n") { "${it.packetId} ${it.status} from ${it.senderPeerId}" }?.ifBlank { "No receipts yet." }

        InfoCard("Dashboard", "Mode: ${if (autoSync) "auto-sync active" else "manual"}\nPeers: ${meshPeerCount(peersText)} connected\nOutbox: ${pendingCount?.toString() ?: "DB off"}\nRelay cache: ${relayCount?.toString() ?: "DB off"}")
        InfoCard("Connection", meshStatus.ifBlank { "No status yet." })
        InfoCard("Peers", meshPeerSummary(peersText))
        InfoCard("Last Sync", lastSyncSummary)
        InfoCard("iOS Note", "Auto-relay runs while app is in foreground. iOS may pause mesh when screen is locked.")

        Text("Advanced", color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button({ run("Start") { meshBridge!!.start(localPeerId.trim().ifBlank { "device" }) }; refreshMeshSnapshot() }, Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F766E))) { Text("Start") }
            OutlinedButton({ autoSync = false; run("Stop") { meshBridge!!.stop() }; refreshMeshSnapshot() }, Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(12.dp)) { Text("Stop") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton({ status = refreshMeshSnapshot() }, Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(12.dp)) { Text("Refresh") }
            OutlinedButton({ run("Peers") { meshBridge!!.peers() }; refreshMeshSnapshot() }, Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(12.dp)) { Text("Peers") }
        }
        OutlinedTextField(envelope, { envelope = it }, Modifier.fillMaxWidth().height(100.dp), label = { Text("KNET1 envelope") }, shape = RoundedCornerShape(12.dp))
        PrimaryButton("Broadcast Packet", color = Color(0xFF7C3AED)) { meshEnvelopeSizeError(envelope)?.let { status = "Held: $it" } ?: run("Broadcast") { meshBridge!!.broadcast(envelope) } }
        PrimaryButton("Send Pending Outbox", color = Color(0xFF0F766E)) { status = sendPendingOutbox() }
        PrimaryButton("Receive Incoming", color = Primary) { status = drainIncomingPackets() }
        PrimaryButton("Forward Relay Cache", color = Color(0xFFEA580C)) { status = forwardRelayCache() }
        if (received.isNotBlank()) OutlinedTextField(received, { received = it }, Modifier.fillMaxWidth().height(100.dp), label = { Text("Received") }, shape = RoundedCornerShape(12.dp))
        InfoCard("Outbox", if (pendingCount == null) "Database off." else "$pendingCount pending packets.")
        InfoCard("Relay Cache", if (relayCount == null) "Database off." else "$relayCount packets ready to relay.")
        InfoCard("Recent Outbox", recentOutbox ?: "Database off.")
        InfoCard("Recent Receipts", recentReceipts ?: "Database off.")
        InfoCard("Status", status)
    }
}

// ── Encrypted chat list ───────────────────────────────────────────────────────

@Composable
private fun EncryptedChatListScreen(repository: EncryptedChatRepository?, onBack: () -> Unit, onPair: () -> Unit, onOpen: (String) -> Unit) {
    var previews by remember { mutableStateOf<List<SecureThreadPreview>>(emptyList()) }
    var status by remember { mutableStateOf(if (repository == null) "Database not available." else "Ready.") }
    var renameContact by remember { mutableStateOf<ContactItem?>(null) }
    var renameValue by remember { mutableStateOf("") }

    fun refresh() {
        val repo = repository ?: run { previews = emptyList(); status = "Database not available."; return }
        val contacts = repo.trustedContacts()
        previews = contacts.map { SecureThreadPreview(it, repo.chatMessagesForThread(it.peerId, 1).lastOrNull()) }
            .sortedWith(compareByDescending<SecureThreadPreview> { it.lastMessage?.createdAt ?: 0L }.thenBy { it.contact.displayName.lowercase() })
        status = if (contacts.isEmpty()) "No contacts yet. Pair a contact first." else "${contacts.size} contacts."
    }

    LaunchedEffect(repository) { refresh() }
    LaunchedEffect(repository) { while (repository != null) { refresh(); delay(5_000) } }

    Column(Modifier.fillMaxSize()) {
        Header("Chat", "End-to-end encrypted", onBack, action = "Add Contact", onAction = onPair)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = PrimarySubtle) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Inbox", color = Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Pick a paired contact to start chatting.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button({ refresh() }, Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(999.dp), colors = ButtonDefaults.buttonColors(containerColor = Primary), elevation = ButtonDefaults.buttonElevation(0.dp)) { Text("Refresh") }
                            OutlinedButton(onPair, Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(999.dp)) { Text("Add Contact") }
                        }
                    }
                }
            }
            if (previews.isEmpty()) item { InfoCard("No Chats", if (repository == null) "Database not available." else "Pair a contact first.") }
            else items(previews) { preview ->
                SecureThreadRow(
                    preview = preview,
                    onRename = { renameContact = preview.contact; renameValue = preview.contact.displayName },
                    onDelete = { repository?.deleteContact(preview.contact.peerId); status = "Kontak dihapus: ${preview.contact.displayName}."; refresh() },
                ) { onOpen(preview.contact.peerId) }
            }
            item { InfoCard("Status", status) }
        }
    }
    renameContact?.let { contact ->
        AlertDialog(
            onDismissRequest = { renameContact = null },
            title = { Text("Nama kontak") },
            text = { OutlinedTextField(renameValue, { renameValue = it }, Modifier.fillMaxWidth(), label = { Text("Nama") }, singleLine = true, shape = RoundedCornerShape(12.dp)) },
            confirmButton = { TextButton({ repository?.updateContactDisplayName(contact.peerId, renameValue.ifBlank { contact.displayName }); status = "Nama kontak disimpan."; renameContact = null; refresh() }) { Text("Save") } },
            dismissButton = { TextButton({ renameContact = null }) { Text("Cancel") } },
        )
    }
}

// ── Encrypted chat screen ─────────────────────────────────────────────────────

@Composable
private fun EncryptedChatScreen(cryptoBridge: CryptoBridge?, meshBridge: MeshBridge?, repository: EncryptedChatRepository?, identityRepository: LocalIdentityRepository?, initialContactPeerId: String?, onBack: () -> Unit) {
    val identity = remember(identityRepository) { identityRepository?.get() }
    var localPeerId by remember(identity?.peerId) { mutableStateOf(identity?.peerId.orEmpty()) }
    var contactPeerId by remember { mutableStateOf(initialContactPeerId ?: "iphone-b") }
    var outgoingMessage by remember { mutableStateOf("") }
    var outgoingEnvelope by remember { mutableStateOf("") }
    var incomingEnvelope by remember { mutableStateOf("") }
    var decryptedMessage by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(if (cryptoBridge == null) "Crypto not available on this platform." else "Ready.") }
    var messages by remember { mutableStateOf<List<ChatMessageItem>>(emptyList()) }
    var contacts by remember { mutableStateOf<List<ContactItem>>(emptyList()) }
    var lastRefreshText by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }

    fun associatedData(peerId: String): String = "kampungnet-chat-v1:$peerId".toBase64()

    fun refreshMessages() {
        contacts = repository?.trustedContacts().orEmpty()
        messages = repository?.chatMessagesForThread(contactPeerId.trim(), 50).orEmpty()
        lastRefreshText = "${messages.size} messages"
    }

    fun statusLabel(value: String): String = when (value) {
        "QUEUED" -> "Queued"; "BROADCASTED", "RELAYED" -> "Sent"; "DELIVERED" -> "Delivered"
        "READ" -> "Read"; "DECRYPTED" -> "Received"; else -> value
    }

    fun sendPendingOutboxFromChat(): String {
        val bridge = meshBridge ?: return "Mesh not started. Open Local Mesh to sync."
        val repo = repository ?: return "Database not available."
        meshReadyForBroadcast(bridge)?.let { return "Saved locally. $it" }
        val pending = repo.retryableOutboxPackets(SystemClock.nowMillis())
        if (pending.isEmpty()) return "No pending outbox."
        var sent = 0; var failed: String? = null
        pending.forEach { packet ->
            if (failed == null) {
                val env = encodeChatEnvelope(chatPacketJson(packet.packetId, localPeerId.trim().ifBlank { "local-device" }, packet.targetPeerId, packet.payload))
                val sizeError = meshEnvelopeSizeError(env)
                val result = if (sizeError == null) bridge.broadcast(env) else null
                if (result?.ok == true) { repo.markOutboxPacketRelayed(packet.packetId, SystemClock.nowMillis()); sent += 1 }
                else failed = "${packet.packetId}: ${sizeError ?: result?.error.orEmpty()}"
            }
        }
        return if (failed == null) "Sent $sent messages." else "Partial. sent=$sent, failed=$failed"
    }

    LaunchedEffect(contactPeerId) { refreshMessages() }
    LaunchedEffect(initialContactPeerId) { if (!initialContactPeerId.isNullOrBlank()) contactPeerId = initialContactPeerId }
    LaunchedEffect(contactPeerId, repository) { while (repository != null) { refreshMessages(); delay(5_000) } }

    FormScaffold("Encrypted Chat", "Offline E2EE via mesh", onBack) {
        val selectedContact = contacts.firstOrNull { it.peerId == contactPeerId }
        val pendingCount = repository?.pendingOutboxPackets(SystemClock.nowMillis())?.size
        val peerId = contactPeerId.trim()

        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = PrimarySubtle) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(selectedContact?.displayName?.take(1).orEmpty().ifBlank { "?" }, Primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(selectedContact?.displayName ?: "Choose a contact", color = Ink, fontWeight = FontWeight.Bold)
                    Text(if (selectedContact == null) "Pair a contact first." else lastRefreshText, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                if ((pendingCount ?: 0) > 0) {
                    Surface(shape = RoundedCornerShape(999.dp), color = Amber.copy(alpha = 0.15f)) {
                        Text("$pendingCount outbox", color = Amber, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }
        }

        if (contacts.isEmpty()) {
            InfoCard("No Contacts", if (repository == null) "Database not available." else "Use Add Contact to pair a device.")
        } else {
            Text("Contacts", color = Muted, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                contacts.take(3).forEach { contact ->
                    ContactPill(contact, selected = contact.peerId == contactPeerId, modifier = Modifier.weight(1f)) {
                        contactPeerId = contact.peerId; refreshMessages()
                        status = "Selected ${contact.displayName}."
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button({ refreshMessages() }, Modifier.weight(1f).height(46.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Primary), elevation = ButtonDefaults.buttonElevation(0.dp)) { Text("Refresh") }
            OutlinedButton({ sendPendingOutboxFromChat().also { status = it } }, Modifier.weight(1f).height(46.dp), shape = RoundedCornerShape(12.dp)) { Text("Sync Outbox") }
        }

        Surface(Modifier.fillMaxWidth().height(280.dp), shape = RoundedCornerShape(16.dp), color = AppBg) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp), contentPadding = PaddingValues(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (messages.isEmpty()) item { InfoCard("No messages", if (repository == null) "Database not available." else "Send the first message.") }
                else items(messages, key = { it.messageId }) { ChatBubble(it, statusLabel(it.status)) }
            }
        }

        Row(Modifier.fillMaxWidth().imePadding(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ComposerField(outgoingMessage, { outgoingMessage = it }, "Write a message…", Modifier.weight(1f))
            val bridge = cryptoBridge
            Button({
                if (bridge == null) { status = "Encrypt failed: crypto bridge not available" }
                else if (peerId.isBlank()) { status = "Encrypt failed: no contact selected" }
                else {
                    val result = bridge.encrypt(peerId, outgoingMessage.toBase64(), associatedData(peerId))
                    if (result.ok) {
                        val payloadJson = result.value.orEmpty()
                        val stored = repository?.saveOutgoingEncryptedChat(localPeerId.trim().ifBlank { "local-device" }, peerId, outgoingMessage, payloadJson, SystemClock.nowMillis())
                        outgoingEnvelope = if (stored == null) encodeChatEnvelope(payloadJson) else encodeChatEnvelope(chatPacketJson(stored.packetId, localPeerId.trim().ifBlank { "local-device" }, peerId, payloadJson))
                        status = if (stored == null) "Encrypted. No database." else "Sent to outbox. ${sendPendingOutboxFromChat()}"
                        outgoingMessage = ""
                        refreshMessages()
                    } else status = "Encrypt failed: ${result.error.orEmpty()}"
                }
            }, Modifier.height(50.dp), shape = RoundedCornerShape(999.dp), colors = ButtonDefaults.buttonColors(containerColor = Primary), elevation = ButtonDefaults.buttonElevation(0.dp), contentPadding = PaddingValues(horizontal = 20.dp)) { Text("Send") }
        }

        OutlinedButton({ showAdvanced = !showAdvanced }, Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(12.dp)) {
            Text(if (showAdvanced) "Hide Advanced" else "Advanced")
        }
        if (showAdvanced) {
            OutlinedTextField(localPeerId, { localPeerId = it }, Modifier.fillMaxWidth(), label = { Text("My peer ID") }, singleLine = true, shape = RoundedCornerShape(12.dp))
            OutlinedTextField(contactPeerId, { contactPeerId = it }, Modifier.fillMaxWidth(), label = { Text("Contact peer ID") }, singleLine = true, shape = RoundedCornerShape(12.dp))
            OutlinedTextField(outgoingEnvelope, { outgoingEnvelope = it }, Modifier.fillMaxWidth().height(100.dp), label = { Text("KNET1:CHAT packet") }, shape = RoundedCornerShape(12.dp))
            Text("Manual Receive", color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(incomingEnvelope, { incomingEnvelope = it }, Modifier.fillMaxWidth().height(100.dp), label = { Text("Paste KNET1:CHAT packet") }, shape = RoundedCornerShape(12.dp))
            PrimaryButton("Decrypt Message", color = Color(0xFF10B981)) {
                val b = cryptoBridge
                val payloadJson = decodeChatEnvelope(incomingEnvelope)
                when {
                    b == null -> status = "Decrypt failed: bridge not available"
                    peerId.isBlank() -> status = "Decrypt failed: no contact"
                    payloadJson == null -> status = "Decrypt failed: must be KNET1:CHAT:<base64>"
                    else -> {
                        val r = b.decrypt(peerId, chatPacketPayloadJson(payloadJson), associatedData(peerId))
                        if (r.ok) {
                            decryptedMessage = r.value.orEmpty().fromBase64OrNull().orEmpty()
                            val mid = repository?.saveIncomingDecryptedChat(localPeerId.trim().ifBlank { "local-device" }, peerId, decryptedMessage, SystemClock.nowMillis())
                            status = if (mid == null) "Decrypted. Database not available." else "Decrypted and saved."
                            refreshMessages()
                        } else status = "Decrypt failed: ${r.error.orEmpty()}"
                    }
                }
            }
            if (decryptedMessage.isNotBlank()) InfoCard("Decrypted", decryptedMessage)
        }
        InfoCard("Status", status)
    }
}

// ── Crypto debug ──────────────────────────────────────────────────────────────

@Composable
private fun CryptoDebugScreen(cryptoBridge: CryptoBridge?, onBack: () -> Unit) {
    var localPeerId by remember { mutableStateOf("iphone-a") }
    var contactPeerId by remember { mutableStateOf("iphone-b") }
    var displayName by remember { mutableStateOf("Me") }
    var offerJson by remember { mutableStateOf("") }
    var acceptanceJson by remember { mutableStateOf("") }
    var encryptedJson by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Test message from HiveNet") }
    var log by remember { mutableStateOf(if (cryptoBridge == null) "Crypto bridge not available." else "Ready.") }

    fun run(label: String, block: () -> CryptoBridgeResult) {
        if (cryptoBridge == null) { log = "$label failed: bridge not available"; return }
        val result = block()
        log = if (result.ok) "$label succeeded\n${result.value.orEmpty()}" else "$label failed\n${result.error.orEmpty()}"
    }

    FormScaffold("Crypto Debug", "X25519 + ChaCha20-Poly1305", onBack) {
        OutlinedTextField(localPeerId, { localPeerId = it }, Modifier.fillMaxWidth(), label = { Text("Local peer ID") }, singleLine = true, shape = RoundedCornerShape(12.dp))
        OutlinedTextField(contactPeerId, { contactPeerId = it }, Modifier.fillMaxWidth(), label = { Text("Contact peer ID") }, singleLine = true, shape = RoundedCornerShape(12.dp))
        OutlinedTextField(displayName, { displayName = it }, Modifier.fillMaxWidth(), label = { Text("Display name") }, singleLine = true, shape = RoundedCornerShape(12.dp))
        PrimaryButton("Generate Identity") { run("Identity") { cryptoBridge!!.getOrCreateIdentityPublicKey() } }
        PrimaryButton("Create Pairing Offer") { run("Create offer") { cryptoBridge!!.createPairingOffer(contactPeerId, localPeerId, displayName).also { if (it.ok) offerJson = it.value.orEmpty() } } }
        OutlinedTextField(offerJson, { offerJson = it }, Modifier.fillMaxWidth().height(88.dp), label = { Text("Offer JSON") }, shape = RoundedCornerShape(12.dp))
        PrimaryButton("Accept Offer") { run("Accept offer") { cryptoBridge!!.acceptPairingOffer(offerJson, contactPeerId, "Peer B").also { if (it.ok) acceptanceJson = it.value.orEmpty() } } }
        OutlinedTextField(acceptanceJson, { acceptanceJson = it }, Modifier.fillMaxWidth().height(88.dp), label = { Text("Acceptance JSON") }, shape = RoundedCornerShape(12.dp))
        PrimaryButton("Complete Pairing") { run("Complete pairing") { cryptoBridge!!.completePairing(acceptanceJson, localPeerId) } }
        OutlinedTextField(message, { message = it }, Modifier.fillMaxWidth(), label = { Text("Test message") }, shape = RoundedCornerShape(12.dp))
        PrimaryButton("Encrypt") { run("Encrypt") { cryptoBridge!!.encrypt(contactPeerId, message.toBase64(), "hive-debug-aad".toBase64()).also { if (it.ok) encryptedJson = it.value.orEmpty() } } }
        OutlinedTextField(encryptedJson, { encryptedJson = it }, Modifier.fillMaxWidth().height(88.dp), label = { Text("Encrypted payload") }, shape = RoundedCornerShape(12.dp))
        PrimaryButton("Decrypt") { run("Decrypt") { cryptoBridge!!.decrypt(contactPeerId, encryptedJson, "hive-debug-aad".toBase64()) } }
        InfoCard("Result", log)
    }
}

// ── Pair contact ──────────────────────────────────────────────────────────────

@Composable
private fun PairContactScreen(cryptoBridge: CryptoBridge?, qrScannerBridge: QrScannerBridge?, repository: EncryptedChatRepository?, identityRepository: LocalIdentityRepository?, onPaired: () -> Unit, onBack: () -> Unit) {
    val identity = remember { identityRepository?.get() }
    var localPeerId by remember { mutableStateOf(identity?.peerId.orEmpty()) }
    var localName by remember { mutableStateOf(identity?.displayName.orEmpty()) }
    var offerEnvelope by remember { mutableStateOf("") }
    var incomingOffer by remember { mutableStateOf("") }
    var acceptanceEnvelope by remember { mutableStateOf("") }
    var incomingAcceptance by remember { mutableStateOf("") }
    var verificationCode by remember { mutableStateOf("") }
    var pairingMode by remember { mutableStateOf("invite") }
    var showAdvanced by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf(if (cryptoBridge == null) "Pairing not available on this platform." else "Tampilkan QR kamu atau scan QR kontak. Kontak tersimpan otomatis setelah scan.") }
    var pairedPeerIdForName by remember { mutableStateOf<String?>(null) }
    var contactNameDraft by remember { mutableStateOf("") }

    fun setResult(label: String, cryptoResult: CryptoBridgeResult, onSuccess: (String) -> Unit = {}) {
        if (cryptoResult.ok) { result = "$label succeeded"; onSuccess(cryptoResult.value.orEmpty()) }
        else result = "$label failed: ${cryptoResult.error.orEmpty()}"
    }
    fun missingBridge(label: String): Boolean {
        if (cryptoBridge != null) return false
        result = "$label failed: bridge not available"; return true
    }
    fun scanQr(label: String, onToken: (String) -> Unit) {
        val scanner = qrScannerBridge ?: run { result = "$label failed: QR scanner not available"; return }
        result = "Camera open. Point at contact QR."
        scanner.scanPairingToken { scanResult ->
            if (scanResult.ok) onToken(scanResult.value.orEmpty().trim())
            else result = "$label failed: ${scanResult.error.orEmpty()}"
        }
    }
    fun savePairedContact(peerId: String, displayName: String, identityPublicKey: String?, keyId: String?, secretRef: String?) {
        val repo = repository ?: run { result = "Pairing succeeded, but database not available. Contact: $peerId."; return }
        repo.saveTrustedContact(peerId, displayName.ifBlank { peerId }, identityPublicKey, keyId, secretRef, SystemClock.nowMillis())
        onPaired()
        pairedPeerIdForName = peerId
        contactNameDraft = displayName.ifBlank { peerId }
        result = "Kontak tersimpan: ${displayName.ifBlank { peerId }}."
    }
    fun handleScannedPairingToken(token: String) {
        if (missingBridge("Scan QR")) return
        when (detectPairingEnvelopeType(token)) {
            "PAIRING_OFFER" -> {
                incomingOffer = token
                val oJson = decodePairingEnvelope(incomingOffer, "PAIRING_OFFER") ?: run { result = "QR kontak tidak valid."; return }
                setResult("Scan QR", cryptoBridge!!.acceptPairingOffer(oJson, localPeerId.trim(), localName.trim().ifBlank { localPeerId.trim() })) { aJson ->
                    verificationCode = extractJsonValue(aJson, "verificationCode").orEmpty()
                    acceptanceEnvelope = encodePairingEnvelope("PAIRING_ACCEPT", aJson)
                    savePairedContact(extractJsonValue(oJson, "senderPeerId").orEmpty(), extractJsonValue(oJson, "senderName").orEmpty(), extractJsonValue(oJson, "identityPublicKey"), null, null)
                }
            }
            "PAIRING_ACCEPT" -> {
                incomingAcceptance = token
                val aJson = decodePairingEnvelope(incomingAcceptance, "PAIRING_ACCEPT") ?: run { result = "QR balasan tidak valid."; return }
                verificationCode = extractJsonValue(aJson, "verificationCode").orEmpty()
                setResult("Scan QR", cryptoBridge!!.completePairing(aJson, localPeerId.trim())) { keyJson ->
                    savePairedContact(extractJsonValue(keyJson, "contactPeerId").orEmpty(), extractJsonValue(aJson, "responderName").orEmpty(), extractJsonValue(aJson, "identityPublicKey"), extractJsonValue(keyJson, "keyId").orEmpty(), extractJsonValue(keyJson, "keyId").orEmpty())
                }
            }
            else -> result = "QR bukan token pairing HiveNet."
        }
    }

    FormScaffold("Add Contact", "Pairing aman via QR · E2EE keys", onBack) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = CardBg, tonalElevation = 1.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(localName, { localName = it }, Modifier.fillMaxWidth(), label = { Text("Your name") }, placeholder = { Text("e.g. Andi") }, singleLine = true, shape = RoundedCornerShape(12.dp))
                Text("Peer ID: ${localPeerId.ifBlank { "belum ada identitas" }}", color = Muted, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ pairingMode = "invite" }, Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (pairingMode == "invite") Primary else AppBg, contentColor = if (pairingMode == "invite") Color.White else Ink),
                        elevation = ButtonDefaults.buttonElevation(0.dp)) { Text("Undang Kontak") }
                    Button({ pairingMode = "receive" }, Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (pairingMode == "receive") Primary else AppBg, contentColor = if (pairingMode == "receive") Color.White else Ink),
                        elevation = ButtonDefaults.buttonElevation(0.dp)) { Text("Scan Undangan") }
                }
            }
        }

        if (pairingMode == "invite") {
            InfoCard("1. Tampilkan QR saya", "Teman scan QR ini. Kontak kamu tersimpan di HP teman. Setelah itu, scan QR balasan dari HP teman agar kontak teman tersimpan di HP kamu.")
            PrimaryButton("Buat Invite QR") {
                if (localPeerId.isBlank()) result = "Buat identitas dulu dari onboarding/profile."
                else if (!missingBridge("Create invite")) setResult("Create invite", cryptoBridge!!.createPairingOffer("", localPeerId.trim(), localName.trim().ifBlank { localPeerId.trim() })) { json -> offerEnvelope = encodePairingEnvelope("PAIRING_OFFER", json); result = "QR siap. Minta teman scan." }
            }
            PairingQrCard("QR Saya", offerEnvelope, "Tampilkan ke kontak.")
            InfoCard("2. Scan QR", "Scan QR dari HP teman. Invite atau balasan terdeteksi otomatis. Kalau kontak belum muncul di HP ini, scan QR balasan teman.")
            OutlinedButton({ scanQr("Scan QR") { token -> handleScannedPairingToken(token) } }, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(999.dp)) { Text("Scan QR") }
        } else {
            InfoCard("1. Scan QR", "Scan QR dari HP teman. Invite atau balasan akan terdeteksi otomatis.")
            OutlinedButton({ scanQr("Scan QR") { token -> handleScannedPairingToken(token) } }, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(999.dp)) { Text("Scan QR") }
            PairingQrCard("QR Balasan", acceptanceEnvelope, "Tampilkan ke teman agar kontak kamu tersimpan di HP teman.")
        }

        if (verificationCode.isNotBlank()) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Color(0xFF10B981).copy(alpha = 0.12f)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Verification Code", color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    Text(verificationCode, color = Color(0xFF10B981), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text("Must match on both devices.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        OutlinedButton({ showAdvanced = !showAdvanced }, Modifier.fillMaxWidth().height(42.dp), shape = RoundedCornerShape(999.dp)) { Text(if (showAdvanced) "Hide Advanced" else "Advanced / Manual Token") }
        if (showAdvanced) {
            OutlinedTextField(localPeerId, { localPeerId = it }, Modifier.fillMaxWidth(), label = { Text("My peer ID") }, singleLine = true, shape = RoundedCornerShape(12.dp))
            Text("Manual peer ID add belum aktif. QR scan auto-add untuk pairing tatap muka.", color = Muted, style = MaterialTheme.typography.bodySmall)
            InfoCard("Manual token", "Dipakai kalau kamera/QR bermasalah. HP pengundang salin invite token. HP penerima tempel sebagai invite dari teman, lalu salin response token. HP pengundang tempel sebagai response dari teman.")
            OutlinedTextField(offerEnvelope, { offerEnvelope = it }, Modifier.fillMaxWidth().height(88.dp), label = { Text("Invite token dari HP ini") }, shape = RoundedCornerShape(12.dp))
            OutlinedTextField(incomingOffer, { incomingOffer = it }, Modifier.fillMaxWidth().height(88.dp), label = { Text("Invite token dari teman") }, shape = RoundedCornerShape(12.dp))
            OutlinedTextField(acceptanceEnvelope, { acceptanceEnvelope = it }, Modifier.fillMaxWidth().height(88.dp), label = { Text("Response token dari HP ini") }, shape = RoundedCornerShape(12.dp))
            OutlinedTextField(incomingAcceptance, { incomingAcceptance = it }, Modifier.fillMaxWidth().height(88.dp), label = { Text("Response token dari teman") }, shape = RoundedCornerShape(12.dp))
        }
        InfoCard("Status", result)
    }
    pairedPeerIdForName?.let { peerId ->
        AlertDialog(
            onDismissRequest = { pairedPeerIdForName = null },
            title = { Text("Nama kontak") },
            text = { OutlinedTextField(contactNameDraft, { contactNameDraft = it }, Modifier.fillMaxWidth(), label = { Text("Nama") }, singleLine = true, shape = RoundedCornerShape(12.dp)) },
            confirmButton = { TextButton({ repository?.updateContactDisplayName(peerId, contactNameDraft.ifBlank { peerId }); onPaired(); pairedPeerIdForName = null; result = "Nama kontak disimpan." }) { Text("Save") } },
            dismissButton = { TextButton({ pairedPeerIdForName = null }) { Text("Nanti") } },
        )
    }
}

// ── Pairing QR card ───────────────────────────────────────────────────────────

@Composable
private fun PairingQrCard(title: String, payload: String, helper: String) {
    if (payload.isBlank()) return
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = CardBg, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = Ink, fontWeight = FontWeight.Bold)
            Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
                Image(painter = rememberQrCodePainter(payload), contentDescription = title, modifier = Modifier.size(280.dp).padding(12.dp))
            }
            Text(helper, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ── New screens ───────────────────────────────────────────────────────────────

@Composable
private fun NewChoiceScreen(onBack: () -> Unit, onChat: () -> Unit, onGroup: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Header("New Conversation", "Choose a type", onBack)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BigAction("Personal Chat", "One-to-one message to a contact", onChat)
            BigAction("Group", "Room with members, SOS, and walkie-talkie", onGroup)
        }
    }
}

@Composable
private fun NewChatScreen(strings: AppStrings, peers: List<Peer>, onBack: () -> Unit, onAddContact: () -> Unit, onCreate: (Peer, String) -> Unit) {
    FormScaffold(strings.newMessage, strings.newMessageSubtitle, onBack) {
        OutlinedButton(onClick = onAddContact, modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(999.dp)) { Text(strings.addContact) }
        Text(if (strings.addContact == "Tambah Kontak") "Kontak" else "Contacts", color = Muted, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        if (peers.isEmpty()) {
            InfoCard(strings.noContactsTitle, strings.noContactsHint)
        }
        peers.forEach { peer ->
            Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onCreate(peer, "") },
                color = CardBg, tonalElevation = 1.dp) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(peer.name.take(1), Primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(peer.name, color = Ink, fontWeight = FontWeight.Bold)
                        Text(peer.peerId, color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("→", color = Muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun NewGroupScreen(peers: List<Peer>, onBack: () -> Unit, onCreate: (String, List<Peer>) -> Unit) {
    var name by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<Peer>() }
    FormScaffold("New Group", "You are the admin", onBack) {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Group name") }, placeholder = { Text("e.g. RT 05 Warga") }, singleLine = true, shape = RoundedCornerShape(14.dp))
        Text("Invite members", color = Muted, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        if (peers.isEmpty()) {
            InfoCard("No contacts yet", "Use Add Contact to pair a device first.")
        }
        peers.forEach { peer -> SelectablePeer(peer, selected.any { it.id == peer.id }) { checked -> if (checked) selected.add(peer) else selected.removeAll { it.id == peer.id } } }
        PrimaryButton("Create Group (${selected.size + 1} members)") { onCreate(name, selected.toList()) }
    }
}

// ── Chat screen ───────────────────────────────────────────────────────────────

@Composable
private fun ChatScreen(thread: ChatThread, tab: RoomTab, onTab: (RoomTab) -> Unit, onBack: () -> Unit, onInfo: () -> Unit, onSend: (String) -> Unit, onDeleteMessage: (Int) -> Unit, onSos: () -> Unit, onPeerSos: () -> Unit, onHtSend: () -> Unit, onHtReply: () -> Unit) {
    var draft by remember(thread.id) { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Header(thread.name, if (thread.group) "${thread.members.size} members" else shortPeerId(thread.peerId), onBack, if (thread.group) "Info" else null, onInfo)
        if (thread.group) TabBar(tab, onTab)
        when (if (thread.group) tab else RoomTab.Chat) {
            RoomTab.Chat -> Column(Modifier.weight(1f)) {
                LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (thread.messages.isEmpty()) item { InfoCard("No messages yet", "Send the first message below.") }
                    items(thread.messages, key = { it.id }) { MessageBubble(it, onDelete = { onDeleteMessage(it.id) }) }
                }
                Row(Modifier.fillMaxWidth().background(CardBg).imePadding().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    ComposerField(draft, { draft = it }, "Message…", Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(50.dp).clip(CircleShape).background(Primary).clickable { val b = draft.trim(); if (b.isNotEmpty()) { onSend(b); draft = "" } }, contentAlignment = Alignment.Center) {
                        Text("↑", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
            RoomTab.Sos -> SosRoom(thread, onSos, onPeerSos)
            RoomTab.Ht -> HtRoom(thread, onHtSend, onHtReply)
        }
    }
}

@Composable
private fun GroupInfoScreen(thread: ChatThread, peers: List<Peer>, onBack: () -> Unit, onInvite: (Peer) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Header("Group Info", thread.name, onBack)
        LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { InfoCard("Admins", thread.members.filter { it.admin }.joinToString { it.name }) }
            item { Text("Members", color = Muted, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp)) }
            items(thread.members) { MemberRow(it) }
            item { Text("Invite", color = Muted, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp)) }
            items(peers.filterNot { p -> thread.members.any { it.id == p.id } }) { peer -> BigAction("Invite ${peer.name}", peer.peerId) { onInvite(peer) } }
        }
    }
}

// ── Sub-screens / tab content ─────────────────────────────────────────────────

@Composable
private fun TabBar(tab: RoomTab, onTab: (RoomTab) -> Unit) {
    Row(Modifier.fillMaxWidth().background(CardBg).padding(horizontal = 16.dp)) {
        listOf(RoomTab.Chat to "Chat", RoomTab.Sos to "SOS", RoomTab.Ht to "HT").forEach { (t, label) ->
            Box(Modifier.weight(1f).clickable { onTab(t) }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, color = if (tab == t) Primary else Muted, fontWeight = if (tab == t) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodyMedium)
                    Box(Modifier.width(if (tab == t) 24.dp else 0.dp).height(3.dp).clip(CircleShape).background(Primary))
                }
            }
        }
    }
}

@Composable
private fun SosRoom(thread: ChatThread, onSos: () -> Unit, onPeerSos: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoCard("SOS Room", "Emergency alerts will be sent to ${thread.name} and relayed through the mesh.")
        Button(onSos, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Danger), elevation = ButtonDefaults.buttonElevation(0.dp)) {
            Text("Send SOS", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
        OutlinedButton(onPeerSos, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) { Text("Simulate Incoming SOS") }
    }
}

@Composable
private fun HtRoom(thread: ChatThread, onSend: () -> Unit, onReply: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoCard("Walkie-Talkie", "Push-to-talk for this group.")
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (thread.htMessages.isEmpty()) item { InfoCard("Channel idle", "No transmissions yet.") }
            items(thread.htMessages, key = { it.id }) { HtBubble(it) }
        }
        Button(onSend, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(999.dp), colors = ButtonDefaults.buttonColors(containerColor = Primary), elevation = ButtonDefaults.buttonElevation(0.dp)) {
            Text("● Hold to Talk", fontWeight = FontWeight.Bold)
        }
        OutlinedButton(onReply, Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(999.dp)) { Text("Simulate Reply") }
    }
}

// ── Reusable components ───────────────────────────────────────────────────────

@Composable
private fun FormScaffold(title: String, subtitle: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(AppBg)) {
        Header(title, subtitle, onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun Header(title: String, subtitle: String, onBack: () -> Unit, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().background(CardBg).statusBarsPadding().padding(start = 8.dp, top = 8.dp, end = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onBack), color = AppBg) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("←", color = Ink, style = MaterialTheme.typography.titleLarge)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (action != null) {
            Button(onAction, Modifier.height(38.dp), shape = RoundedCornerShape(999.dp), contentPadding = PaddingValues(horizontal = 16.dp), colors = ButtonDefaults.buttonColors(containerColor = Primary), elevation = ButtonDefaults.buttonElevation(0.dp)) {
                Text(action, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick, modifier.height(36.dp), shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (selected) Primary else CardBg, contentColor = if (selected) Color.White else Muted),
        elevation = ButtonDefaults.buttonElevation(0.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
    }
}

@Composable
private fun PrimaryButton(label: String, color: Color? = null, onClick: () -> Unit) {
    val bg = color ?: Primary
    Button(onClick, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = bg), elevation = ButtonDefaults.buttonElevation(0.dp)) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BigAction(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick), color = CardBg, tonalElevation = 1.dp) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = Ink, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("›", color = Primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun ChatRow(thread: ChatThread, onDelete: () -> Unit, onClick: () -> Unit) {
    val last = thread.messages.lastOrNull()
    var menuOpen by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick), color = if (thread.blacklisted) Color(0xFFFFF1F2) else CardBg, tonalElevation = 0.dp) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Avatar(if (thread.group) "#" else thread.name.take(1), if (thread.group) Amber else Primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(thread.name, color = Ink, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(last?.time.orEmpty(), color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Text(last?.body ?: "No messages yet", color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Box {
                Surface(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).clickable { menuOpen = true }, color = AppBg) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("⋮", color = Muted, fontWeight = FontWeight.Bold) }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Delete chat") }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun SecureThreadRow(preview: SecureThreadPreview, onRename: () -> Unit, onDelete: () -> Unit, onClick: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val last = preview.lastMessage
    Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick), color = CardBg, tonalElevation = 0.dp) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(preview.contact.displayName.take(1).ifBlank { "?" }, Primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(preview.contact.displayName, color = Ink, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(last?.let { formatClockTime(it.createdAt) }.orEmpty(), color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Text(last?.body ?: "Tap to start chatting.", color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Box {
                Text("⋮", color = Primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clickable { menuOpen = true }.padding(horizontal = 8.dp, vertical = 4.dp))
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename() })
                    DropdownMenuItem(text = { Text("Delete contact") }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun ContactRow(contact: ContactItem, selected: Boolean, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick), color = if (selected) PrimarySubtle else CardBg, tonalElevation = 1.dp) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(contact.displayName.take(1).ifBlank { "?" }, if (selected) Primary else Amber)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(contact.displayName, color = Ink, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(contact.peerId, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(if (selected) "Active" else "Chat", color = Primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ContactPill(contact: ContactItem, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(modifier.height(74.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick), color = if (selected) Primary else CardBg, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(contact.displayName.take(1).ifBlank { "?" }.uppercase(), color = if (selected) Color.White else Primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(contact.displayName, color = if (selected) Color.White else Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(shortPeerId(contact.peerId), color = if (selected) Color.White.copy(alpha = .7f) else Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SelectablePeer(peer: Peer, selected: Boolean, onChange: (Boolean) -> Unit) {
    Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onChange(!selected) }, color = if (selected) PrimarySubtle else CardBg, tonalElevation = 1.dp) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(peer.name.take(1), if (selected) Primary else Muted)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(peer.name, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1)
                Text(peer.peerId, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            Text(if (selected) "✓" else "+", color = Primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun MemberRow(member: Member) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = CardBg, tonalElevation = 1.dp) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(member.name.take(1), if (member.admin) Amber else Primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(member.name, color = Ink, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(member.peerId, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            if (member.admin) Surface(shape = RoundedCornerShape(999.dp), color = Amber.copy(alpha = 0.15f)) {
                Text("Admin", color = Amber, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start) {
        Box(Modifier.fillMaxWidth(.78f)) {
            Surface(
                color = if (message.outgoing) Primary else CardBg,
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (message.outgoing) 18.dp else 4.dp, bottomEnd = if (message.outgoing) 4.dp else 18.dp),
                modifier = Modifier.fillMaxWidth().clickable { menuOpen = true }
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (!message.outgoing) Text(message.sender, color = Primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    Text(message.body, color = if (message.outgoing) Color.White else Ink, style = MaterialTheme.typography.bodyMedium)
                    Text("${message.time}  ${message.status}", color = if (message.outgoing) Color.White.copy(alpha = .65f) else Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Delete message") }, onClick = { menuOpen = false; onDelete() })
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessageItem, statusText: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (message.outgoing) Primary else CardBg,
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (message.outgoing) 18.dp else 4.dp, bottomEnd = if (message.outgoing) 4.dp else 18.dp),
            modifier = Modifier.fillMaxWidth(.82f)
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    if (message.outgoing) "Me → ${shortPeerId(message.targetPeerId)}" else "${shortPeerId(message.senderPeerId)} → Me",
                    color = if (message.outgoing) Color.White.copy(alpha = .75f) else Primary,
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold
                )
                Text(message.body, color = if (message.outgoing) Color.White else Ink, style = MaterialTheme.typography.bodyMedium)
                Text("$statusText  ${formatClockTime(message.createdAt)}", color = if (message.outgoing) Color.White.copy(alpha = .65f) else Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun HtBubble(message: HtMessage) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = if (message.outgoing) PrimarySubtle else CardBg, tonalElevation = 1.dp) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = CircleShape, color = if (message.outgoing) Primary else Amber) {
                Text(if (message.outgoing) "TX" else "RX", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("${message.sender}  ·  00:0${message.duration}", color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text("${message.time}  ·  tap to play", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = CardBg, tonalElevation = 0.dp) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Muted, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            Text(body, color = Ink, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Avatar(text: String, color: Color) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(text.uppercase(), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ComposerField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Surface(modifier.height(50.dp), shape = RoundedCornerShape(999.dp), color = AppBg) {
        Box(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isBlank()) Text(placeholder, color = Muted, style = MaterialTheme.typography.bodyMedium)
            BasicTextField(value = value, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth(), maxLines = 3, textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink), cursorBrush = SolidColor(Primary))
        }
    }
}

// ── SOS ───────────────────────────────────────────────────────────────────────

@Composable
private fun SOSSheet(targetName: String, onCancel: () -> Unit, onSend: (String) -> Unit) {
    var body by remember { mutableStateOf("Need help now") }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f)), contentAlignment = Alignment.BottomCenter) {
        Surface(Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(24.dp), color = CardBg) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Danger))
                    Spacer(Modifier.width(8.dp))
                    Text("Send SOS", color = Danger, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Text("To: $targetName", color = Muted, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth(), label = { Text("Emergency message") }, shape = RoundedCornerShape(14.dp))
                Button({ onSend(body.ifBlank { "Need help" }) }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Danger), elevation = ButtonDefaults.buttonElevation(0.dp)) {
                    Text("Send SOS", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
                OutlinedButton(onCancel, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun SOSOverlay(state: SOSOverlayState, onMute: () -> Unit, onBlacklist: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF7F1D1D), Color(0xFFDC2626)))).statusBarsPadding().padding(28.dp),
        verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            HiveLogo(Modifier.size(80.dp))
            Text("EMERGENCY SOS", color = Color.White, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(state.sender, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(state.peerId, color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.bodyMedium)
            Surface(color = Color.White.copy(alpha = .12f), shape = RoundedCornerShape(18.dp)) {
                Text(state.message, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(18.dp))
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onMute, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Danger), elevation = ButtonDefaults.buttonElevation(0.dp)) {
                Text("Mute", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            Button(onBlacklist, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Black), elevation = ButtonDefaults.buttonElevation(0.dp)) {
                Text("Mute & Blacklist Peer", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

// ── Logo ──────────────────────────────────────────────────────────────────────

@Composable
private fun HiveLogo(modifier: Modifier = Modifier) {
    val primaryColor = Primary
    Canvas(modifier.clip(CircleShape)) {
        drawCircle(primaryColor)
        val w = size.width
        val h = size.height
        val leftX  = w * 0.306f
        val rightX = w * 0.694f
        val topY   = h * 0.241f
        val midY   = h * 0.500f
        val botY   = h * 0.759f
        val strokeW = w * 0.079f
        val nodeR   = w * 0.065f
        val white = Color.White

        drawLine(white, Offset(leftX,  topY), Offset(leftX,  botY), strokeW, StrokeCap.Round)
        drawLine(white, Offset(rightX, topY), Offset(rightX, botY), strokeW, StrokeCap.Round)
        drawLine(white, Offset(leftX,  midY), Offset(rightX, midY), strokeW, StrokeCap.Round)

        listOf(
            Offset(leftX,  topY), Offset(leftX,  botY), Offset(leftX,  midY),
            Offset(rightX, topY), Offset(rightX, botY), Offset(rightX, midY),
        ).forEach { drawCircle(white, nodeR, it) }
    }
}
