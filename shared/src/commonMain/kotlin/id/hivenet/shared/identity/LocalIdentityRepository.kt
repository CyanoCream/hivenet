package id.hivenet.shared.identity

import id.hivenet.db.HiveNetDatabase
import id.hivenet.shared.time.SystemClock
import kotlin.random.Random

data class LocalIdentity(
    val peerId: String,
    val displayName: String,
    val role: String?,
    val deviceLabel: String,
    val email: String?,
    val recoveryCode: String,
    val createdAt: Long,
    val updatedAt: Long,
)

class LocalIdentityRepository(
    private val database: HiveNetDatabase,
    private val clock: SystemClock = SystemClock,
) {
    fun get(): LocalIdentity? = database.meshQueries.getLocalIdentity().executeAsOneOrNull()?.let {
        LocalIdentity(
            peerId = it.peer_id,
            displayName = it.display_name,
            role = it.role,
            deviceLabel = it.device_label,
            email = it.email,
            recoveryCode = it.recovery_code,
            createdAt = it.created_at,
            updatedAt = it.updated_at,
        )
    }

    fun create(displayName: String, role: String?, email: String?): LocalIdentity {
        val now = clock.nowMillis()
        val identity = LocalIdentity(
            peerId = "peer-${token(10)}",
            displayName = displayName.trim(),
            role = role?.trim()?.takeIf { it.isNotBlank() },
            deviceLabel = "HiveNet Device ${token(4)}",
            email = email?.trim()?.takeIf { it.isNotBlank() },
            recoveryCode = "HN-${token(4)}-${token(4)}-${token(4)}-${token(4)}",
            createdAt = now,
            updatedAt = now,
        )
        database.meshQueries.upsertLocalIdentity(
            peer_id = identity.peerId,
            display_name = identity.displayName,
            role = identity.role,
            device_label = identity.deviceLabel,
            email = identity.email,
            recovery_code = identity.recoveryCode,
            created_at = identity.createdAt,
            updated_at = identity.updatedAt,
        )
        return identity
    }

    fun updateProfile(displayName: String, role: String?, email: String?): LocalIdentity? {
        val current = get() ?: return null
        val updated = current.copy(
            displayName = displayName.trim(),
            role = role?.trim()?.takeIf { it.isNotBlank() },
            email = email?.trim()?.takeIf { it.isNotBlank() },
            updatedAt = clock.nowMillis(),
        )
        database.meshQueries.updateLocalProfile(
            display_name = updated.displayName,
            role = updated.role,
            email = updated.email,
            updated_at = updated.updatedAt,
        )
        return updated
    }

    private fun token(length: Int): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return buildString(length) { repeat(length) { append(alphabet[Random.nextInt(alphabet.length)]) } }
    }
}
