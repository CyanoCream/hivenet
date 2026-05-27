package id.kampungnet.shared.chat

import id.kampungnet.db.KampungNetDatabase
import kotlin.random.Random

data class StoredEncryptedPacket(
    val messageId: String,
    val packetId: String,
    val status: String,
)

data class PendingOutboxPacket(
    val packetId: String,
    val targetPeerId: String?,
    val payload: String,
    val status: String,
)

data class OutboxPacketStatus(
    val packetId: String,
    val messageId: String?,
    val targetPeerId: String?,
    val status: String,
    val createdAt: Long,
)

data class RelayCachePacket(
    val packetId: String,
    val sourcePeerId: String?,
    val targetPeerId: String?,
    val topic: String,
    val payload: String,
    val ttl: Long,
)

data class StoredDeliveryReceipt(
    val messageId: String,
    val packetId: String,
    val status: String,
)

data class DeliveryReceiptStatus(
    val messageId: String,
    val packetId: String,
    val senderPeerId: String,
    val targetPeerId: String,
    val status: String,
    val timestamp: Long,
)

data class ChatMessageItem(
    val messageId: String,
    val threadPeerId: String,
    val senderPeerId: String,
    val targetPeerId: String,
    val body: String,
    val outgoing: Boolean,
    val status: String,
    val createdAt: Long,
)

data class ContactItem(
    val peerId: String,
    val displayName: String,
    val trusted: Boolean,
    val createdAt: Long,
    val lastSeenAt: Long?,
)

class EncryptedChatRepository(
    private val database: KampungNetDatabase,
) {
    fun saveTrustedContact(
        peerId: String,
        displayName: String,
        identityPublicKey: String?,
        keyId: String?,
        secretRef: String?,
        nowMillis: Long,
    ) {
        if (peerId.isBlank()) return
        database.meshQueries.transaction {
            database.meshQueries.upsertContact(
                peer_id = peerId,
                display_name = displayName.ifBlank { peerId },
                identity_public_key = identityPublicKey,
                trusted = 1,
                created_at = nowMillis,
                last_seen_at = nowMillis,
            )
            if (!keyId.isNullOrBlank()) {
                database.meshQueries.upsertContactKey(
                    key_id = keyId,
                    contact_peer_id = peerId,
                    algorithm = "X25519-HKDF-SHA256-ChaCha20-Poly1305",
                    secret_ref = secretRef?.ifBlank { keyId } ?: keyId,
                    created_at = nowMillis,
                    verified_at = nowMillis,
                )
            }
        }
    }

    fun trustedContacts(): List<ContactItem> =
        database.meshQueries.getTrustedContacts().executeAsList().map {
            ContactItem(
                peerId = it.peer_id,
                displayName = it.display_name,
                trusted = it.trusted == 1L,
                createdAt = it.created_at,
                lastSeenAt = it.last_seen_at,
            )
        }

    fun saveOutgoingEncryptedChat(
        localPeerId: String,
        contactPeerId: String,
        plaintext: String,
        encryptedPayloadJson: String,
        nowMillis: Long,
        ttlMillis: Long = 86_400_000L,
    ): StoredEncryptedPacket {
        val messageId = newId("msg")
        val packetId = newId("pkt")
        database.meshQueries.transaction {
            database.meshQueries.insertChatMessage(
                message_id = messageId,
                thread_peer_id = contactPeerId,
                sender_peer_id = localPeerId,
                target_peer_id = contactPeerId,
                body = plaintext,
                outgoing = 1,
                status = "QUEUED",
                created_at = nowMillis,
                delivered_at = null,
                read_at = null,
            )
            database.meshQueries.insertOutboxPacket(
                packet_id = packetId,
                message_id = messageId,
                target_peer_id = contactPeerId,
                packet_type = "ENCRYPTED_CHAT",
                topic = "CHAT",
                payload = encryptedPayloadJson,
                ttl = 5,
                status = "QUEUED",
                created_at = nowMillis,
                last_attempt_at = null,
                expires_at = nowMillis + ttlMillis,
            )
        }
        return StoredEncryptedPacket(messageId, packetId, "QUEUED")
    }

    fun saveIncomingDecryptedChat(
        localPeerId: String,
        contactPeerId: String,
        plaintext: String,
        nowMillis: Long,
    ): String {
        val messageId = newId("msg")
        database.meshQueries.insertChatMessage(
            message_id = messageId,
            thread_peer_id = contactPeerId,
            sender_peer_id = contactPeerId,
            target_peer_id = localPeerId,
            body = plaintext,
            outgoing = 0,
            status = "DECRYPTED",
            created_at = nowMillis,
            delivered_at = nowMillis,
            read_at = null,
        )
        return messageId
    }

    fun chatMessagesForThread(threadPeerId: String, limit: Long = 50): List<ChatMessageItem> =
        database.meshQueries.getChatMessagesForThread(threadPeerId, limit).executeAsList().map {
            ChatMessageItem(
                messageId = it.message_id,
                threadPeerId = it.thread_peer_id,
                senderPeerId = it.sender_peer_id,
                targetPeerId = it.target_peer_id,
                body = it.body.orEmpty(),
                outgoing = it.outgoing == 1L,
                status = it.status,
                createdAt = it.created_at,
            )
        }

    fun pendingOutboxPackets(nowMillis: Long): List<PendingOutboxPacket> {
        deleteExpiredPackets(nowMillis)
        return database.meshQueries.getPendingOutboxPackets(nowMillis).executeAsList().map {
            PendingOutboxPacket(
                packetId = it.packet_id,
                targetPeerId = it.target_peer_id,
                payload = it.payload,
                status = it.status,
            )
        }
    }

    fun retryableOutboxPackets(nowMillis: Long, retryAfterMillis: Long = 30_000L): List<PendingOutboxPacket> {
        deleteExpiredPackets(nowMillis)
        return database.meshQueries.getRetryableOutboxPackets(
            expires_at = nowMillis,
            last_attempt_at = nowMillis - retryAfterMillis,
        ).executeAsList().map {
            PendingOutboxPacket(
                packetId = it.packet_id,
                targetPeerId = it.target_peer_id,
                payload = it.payload,
                status = it.status,
            )
        }
    }

    fun recentOutboxPackets(limit: Long = 5): List<OutboxPacketStatus> =
        database.meshQueries.getRecentOutboxPackets(limit).executeAsList().map {
            OutboxPacketStatus(
                packetId = it.packet_id,
                messageId = it.message_id,
                targetPeerId = it.target_peer_id,
                status = it.status,
                createdAt = it.created_at,
            )
        }

    fun markOutboxPacketRelayed(packetId: String, nowMillis: Long) {
        database.meshQueries.transaction {
            database.meshQueries.updateOutboxPacketStatus(
                status = "RELAYED",
                last_attempt_at = nowMillis,
                packet_id = packetId,
            )
            database.meshQueries.markChatMessageBroadcastedForOutbox(packetId)
        }
    }

    fun saveRelayChatPacket(
        packetId: String,
        sourcePeerId: String,
        targetPeerId: String?,
        encryptedPayloadJson: String,
        nowMillis: Long,
        ttl: Long = 4,
        ttlMillis: Long = 86_400_000L,
    ) {
        if (ttl <= 0) return
        database.meshQueries.insertRelayCache(
            packet_id = packetId,
            source_peer_id = sourcePeerId,
            target_peer_id = targetPeerId,
            packet_type = "ENCRYPTED_CHAT",
            topic = "CHAT",
            payload = encryptedPayloadJson,
            ttl = ttl,
            received_at = nowMillis,
            expires_at = nowMillis + ttlMillis,
        )
    }

    fun saveRelayReceiptPacket(
        packetId: String,
        sourcePeerId: String,
        targetPeerId: String?,
        receiptJson: String,
        nowMillis: Long,
        ttl: Long = 4,
        ttlMillis: Long = 86_400_000L,
    ) {
        if (ttl <= 0) return
        database.meshQueries.insertRelayCache(
            packet_id = packetId,
            source_peer_id = sourcePeerId,
            target_peer_id = targetPeerId,
            packet_type = "DELIVERY_RECEIPT",
            topic = "RECEIPT",
            payload = receiptJson,
            ttl = ttl,
            received_at = nowMillis,
            expires_at = nowMillis + ttlMillis,
        )
    }

    fun relayPackets(nowMillis: Long): List<RelayCachePacket> {
        deleteExpiredPackets(nowMillis)
        return database.meshQueries.getRelayPackets(nowMillis).executeAsList().map {
            RelayCachePacket(
                packetId = it.packet_id,
                sourcePeerId = it.source_peer_id,
                targetPeerId = it.target_peer_id,
                topic = it.topic,
                payload = it.payload,
                ttl = it.ttl,
            )
        }
    }

    fun markRelayPacketForwarded(packetId: String) {
        database.meshQueries.transaction {
            database.meshQueries.decrementRelayPacketTtl(packetId)
            database.meshQueries.deleteDeadRelayCache()
        }
    }

    fun deleteRelayPacket(packetId: String) {
        database.meshQueries.deleteRelayPacket(packetId)
    }

    fun saveDeliveryReceipt(
        messageId: String,
        packetId: String,
        senderPeerId: String,
        targetPeerId: String,
        status: String,
        timestamp: Long,
    ): StoredDeliveryReceipt {
        database.meshQueries.insertDeliveryReceipt(
            message_id = messageId,
            packet_id = packetId,
            sender_peer_id = senderPeerId,
            target_peer_id = targetPeerId,
            status = status,
            timestamp = timestamp,
        )
        return StoredDeliveryReceipt(messageId, packetId, status)
    }

    fun recentDeliveryReceipts(limit: Long = 5): List<DeliveryReceiptStatus> =
        database.meshQueries.getRecentDeliveryReceipts(limit).executeAsList().map {
            DeliveryReceiptStatus(
                messageId = it.message_id,
                packetId = it.packet_id,
                senderPeerId = it.sender_peer_id,
                targetPeerId = it.target_peer_id,
                status = it.status,
                timestamp = it.timestamp,
            )
        }

    fun markDeliveredFromReceipt(packetId: String, nowMillis: Long) {
        database.meshQueries.transaction {
            database.meshQueries.markOutboxPacketDelivered(
                last_attempt_at = nowMillis,
                packet_id = packetId,
            )
            database.meshQueries.markChatMessageDeliveredForOutbox(
                delivered_at = nowMillis,
                packet_id = packetId,
            )
        }
    }

    fun hasSeenMeshPacket(packetId: String, nowMillis: Long): Boolean =
        database.meshQueries.hasSeenMeshPacket(packetId, nowMillis).executeAsOneOrNull() != null

    fun markSeenMeshPacket(
        packetId: String,
        nowMillis: Long,
        ttlMillis: Long = 86_400_000L,
    ) {
        database.meshQueries.markSeenMeshPacket(
            packet_id = packetId,
            seen_at = nowMillis,
            expires_at = nowMillis + ttlMillis,
        )
    }

    fun deleteExpiredPackets(nowMillis: Long) {
        database.meshQueries.transaction {
            database.meshQueries.deleteExpiredSeenPackets(nowMillis)
            database.meshQueries.deleteExpiredOutboxPackets(nowMillis)
            database.meshQueries.deleteExpiredRelayCache(nowMillis)
            database.meshQueries.deleteDeadRelayCache()
        }
    }

    private fun newId(prefix: String): String = "$prefix-${Random.nextLong().toULong().toString(16)}"
}
