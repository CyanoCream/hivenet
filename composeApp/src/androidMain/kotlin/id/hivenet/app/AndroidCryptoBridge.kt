package id.hivenet.app

import android.content.Context
import android.util.Base64
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Provider
import java.security.Security
import java.security.SecureRandom
import java.security.interfaces.XECPublicKey
import java.security.spec.NamedParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.XECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

class AndroidCryptoBridge(context: Context) : CryptoBridge {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("hivenet_crypto", Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()

    init {
        installBouncyCastleProvider()
    }

    override fun getOrCreateIdentityPublicKey(): CryptoBridgeResult = wrap {
        getOrCreateIdentity().rawPublicKey.toBase64()
    }

    override fun createPairingOffer(contactPeerId: String, senderPeerId: String, senderName: String): CryptoBridgeResult = wrap {
        val identity = getOrCreateIdentity()
        val agreement = generateX25519KeyPair()
        val sessionId = java.util.UUID.randomUUID().toString()
        prefs.edit()
            .putString(pairingPrivateKeyAccount(sessionId), agreement.private.encoded.toBase64())
            .apply()

        "{" +
            "\"sessionId\":\"${jsonEscape(sessionId)}\"," +
            "\"senderPeerId\":\"${jsonEscape(senderPeerId)}\"," +
            "\"senderName\":\"${jsonEscape(senderName)}\"," +
            "\"identityPublicKey\":\"${identity.rawPublicKey.toBase64()}\"," +
            "\"agreementPublicKey\":\"${rawPublicKey(agreement).toBase64()}\"," +
            "\"timestamp\":${nowMillis()}" +
            "}"
    }

    override fun acceptPairingOffer(offerJson: String, responderPeerId: String, responderName: String): CryptoBridgeResult = wrap {
        val offer = PairingOffer(
            sessionId = requireJsonString(offerJson, "sessionId"),
            senderPeerId = requireJsonString(offerJson, "senderPeerId"),
            agreementPublicKey = requireJsonString(offerJson, "agreementPublicKey"),
        )
        val identity = getOrCreateIdentity()
        val agreement = generateX25519KeyPair()
        val sharedKey = deriveSharedKey(agreement.private, offer.agreementPublicKey.fromBase64(), offer.sessionId)
        val keyId = contactKeyId(responderPeerId, offer.senderPeerId, offer.sessionId)
        val verificationCode = verificationCode(sharedKey, offer.sessionId)
        storeContactKey(offer.senderPeerId, keyId, sharedKey)

        "{" +
            "\"sessionId\":\"${jsonEscape(offer.sessionId)}\"," +
            "\"responderPeerId\":\"${jsonEscape(responderPeerId)}\"," +
            "\"responderName\":\"${jsonEscape(responderName)}\"," +
            "\"identityPublicKey\":\"${identity.rawPublicKey.toBase64()}\"," +
            "\"agreementPublicKey\":\"${rawPublicKey(agreement).toBase64()}\"," +
            "\"verificationCode\":\"$verificationCode\"," +
            "\"timestamp\":${nowMillis()}" +
            "}"
    }

    override fun completePairing(acceptanceJson: String, localPeerId: String): CryptoBridgeResult = wrap {
        val acceptance = PairingAcceptance(
            sessionId = requireJsonString(acceptanceJson, "sessionId"),
            responderPeerId = requireJsonString(acceptanceJson, "responderPeerId"),
            agreementPublicKey = requireJsonString(acceptanceJson, "agreementPublicKey"),
            verificationCode = requireJsonString(acceptanceJson, "verificationCode"),
        )
        val privateKeyBase64 = prefs.getString(pairingPrivateKeyAccount(acceptance.sessionId), null)
            ?: error("Missing pairing private key")
        val privateKey = decodePrivateKey(privateKeyBase64.fromBase64())
        val sharedKey = deriveSharedKey(privateKey, acceptance.agreementPublicKey.fromBase64(), acceptance.sessionId)
        val expectedCode = verificationCode(sharedKey, acceptance.sessionId)
        check(expectedCode == acceptance.verificationCode) { "Invalid verification code" }

        val keyId = contactKeyId(localPeerId, acceptance.responderPeerId, acceptance.sessionId)
        storeContactKey(acceptance.responderPeerId, keyId, sharedKey)
        prefs.edit().remove(pairingPrivateKeyAccount(acceptance.sessionId)).apply()

        "{" +
            "\"contactPeerId\":\"${jsonEscape(acceptance.responderPeerId)}\"," +
            "\"keyId\":\"$keyId\"," +
            "\"algorithm\":\"ChaCha20-Poly1305\"," +
            "\"createdAt\":${nowMillis()}," +
            "\"verifiedAt\":${nowMillis()}" +
            "}"
    }

    override fun encrypt(contactPeerId: String, plaintextBase64: String, associatedDataBase64: String): CryptoBridgeResult = wrap {
        val keyRecord = latestContactKey(contactPeerId)
        val nonce = ByteArray(12).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyRecord.key, "ChaCha20"), IvParameterSpec(nonce))
        cipher.updateAAD(associatedDataBase64.fromBase64())
        val sealed = cipher.doFinal(plaintextBase64.fromBase64())
        val ciphertext = sealed.copyOfRange(0, sealed.size - 16)
        val tag = sealed.copyOfRange(sealed.size - 16, sealed.size)

        "{" +
            "\"version\":1," +
            "\"algorithm\":\"ChaCha20-Poly1305\"," +
            "\"key_id\":\"${keyRecord.keyId}\"," +
            "\"nonce\":\"${nonce.toBase64()}\"," +
            "\"ciphertext\":\"${ciphertext.toBase64()}\"," +
            "\"auth_tag\":\"${tag.toBase64()}\"," +
            "\"aad\":\"${jsonEscape(associatedDataBase64)}\"" +
            "}"
    }

    override fun decrypt(contactPeerId: String, payloadJson: String, associatedDataBase64: String): CryptoBridgeResult = wrap {
        val keyId = requireJsonString(payloadJson, "key_id")
        val nonce = requireJsonString(payloadJson, "nonce").fromBase64()
        val ciphertext = requireJsonString(payloadJson, "ciphertext").fromBase64()
        val tag = requireJsonString(payloadJson, "auth_tag").fromBase64()
        require(nonce.size == NONCE_SIZE_BYTES) { "Invalid nonce length" }
        require(tag.size == AUTH_TAG_SIZE_BYTES) { "Invalid auth tag length" }
        val key = contactKey(contactPeerId, keyId)
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        cipher.updateAAD(associatedDataBase64.fromBase64())
        cipher.doFinal(ciphertext + tag).toBase64()
    }

    private fun wrap(action: () -> String): CryptoBridgeResult = try {
        CryptoBridgeResult(ok = true, value = action())
    } catch (throwable: Throwable) {
        CryptoBridgeResult(ok = false, error = throwable.message ?: throwable::class.java.simpleName)
    }

    private fun getOrCreateIdentity(): IdentityKey {
        val existingPrivate = prefs.getString(IDENTITY_PRIVATE, null)
        val existingPublic = prefs.getString(IDENTITY_PUBLIC, null)
        if (existingPrivate != null && existingPublic != null) {
            return IdentityKey(decodePrivateKey(existingPrivate.fromBase64()), existingPublic.fromBase64())
        }

        val keyPair = generateX25519KeyPair()
        val rawPublicKey = rawPublicKey(keyPair)
        prefs.edit()
            .putString(IDENTITY_PRIVATE, keyPair.private.encoded.toBase64())
            .putString(IDENTITY_PUBLIC, rawPublicKey.toBase64())
            .apply()
        return IdentityKey(keyPair.private, rawPublicKey)
    }

    private fun generateX25519KeyPair(): KeyPair {
        return runCatching {
            KeyPairGenerator.getInstance("XDH").apply { initialize(NamedParameterSpec("X25519"), secureRandom) }.generateKeyPair()
        }.getOrElse {
            val privateKey = X25519PrivateKeyParameters(secureRandom)
            val publicKey = privateKey.generatePublicKey()
            KeyPair(RawX25519PublicKey(publicKey.encoded), RawX25519PrivateKey(privateKey.encoded))
        }
    }

    private fun decodePrivateKey(encoded: ByteArray): PrivateKey {
        if (encoded.size == X25519_PRIVATE_KEY_SIZE_BYTES) return RawX25519PrivateKey(encoded)
        return runCatching { KeyFactory.getInstance("XDH").generatePrivate(PKCS8EncodedKeySpec(encoded)) }
            .getOrElse { KeyFactory.getInstance("X25519", BOUNCY_CASTLE_PROVIDER).generatePrivate(PKCS8EncodedKeySpec(encoded)) }
    }

    private fun rawPublicKey(keyPair: KeyPair): ByteArray {
        (keyPair.public as? RawX25519PublicKey)?.let { return it.raw.copyOf() }
        rawPublicKeyFromBouncyCastle(keyPair.public)?.let { return it }
        val u = (keyPair.public as XECPublicKey).u
        return u.toLittleEndian32()
    }

    private fun deriveSharedKey(privateKey: PrivateKey, remoteRawPublicKey: ByteArray, sessionId: String): ByteArray {
        require(remoteRawPublicKey.size == X25519_PUBLIC_KEY_SIZE_BYTES) { "Invalid X25519 public key length" }
        val secret = runCatching { generateSharedSecret(privateKey, remoteRawPublicKey, provider = null) }
            .getOrElse { generateSharedSecret(privateKey, remoteRawPublicKey, provider = BOUNCY_CASTLE_PROVIDER) }
        return hkdfSha256(secret, "kampungnet-v1".encodeToByteArray(), sessionId.encodeToByteArray(), SYMMETRIC_KEY_SIZE_BYTES)
    }

    private fun generateSharedSecret(privateKey: PrivateKey, remoteRawPublicKey: ByteArray, provider: String?): ByteArray {
        if (privateKey is RawX25519PrivateKey) {
            val secret = ByteArray(X25519_PUBLIC_KEY_SIZE_BYTES)
            X25519PrivateKeyParameters(privateKey.raw, 0)
                .generateSecret(X25519PublicKeyParameters(remoteRawPublicKey, 0), secret, 0)
            return secret
        }
        val keyFactory = if (provider == null) KeyFactory.getInstance("XDH") else KeyFactory.getInstance("X25519", provider)
        val agreement = if (provider == null) KeyAgreement.getInstance("XDH") else KeyAgreement.getInstance("X25519", provider)
        val publicKey = keyFactory.generatePublic(XECPublicKeySpec(NamedParameterSpec("X25519"), remoteRawPublicKey.fromLittleEndian()))
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        return agreement.generateSecret()
    }

    private fun rawPublicKeyFromBouncyCastle(publicKey: java.security.PublicKey): ByteArray? = runCatching {
        val method = publicKey.javaClass.methods.firstOrNull { it.name == "getUEncoding" && it.parameterTypes.isEmpty() } ?: return null
        (method.invoke(publicKey) as ByteArray).also { require(it.size == X25519_PUBLIC_KEY_SIZE_BYTES) }
    }.getOrNull()

    private fun hkdfSha256(inputKeyMaterial: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(salt, inputKeyMaterial)
        var previous = ByteArray(0)
        val output = mutableListOf<Byte>()
        var counter = 1
        while (output.size < length) {
            previous = hmacSha256(prk, previous + info + byteArrayOf(counter.toByte()))
            output.addAll(previous.toList())
            counter += 1
        }
        return output.take(length).toByteArray()
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun verificationCode(key: ByteArray, sessionId: String): String {
        val mac = hmacSha256(key, sessionId.encodeToByteArray())
        val value = ByteBuffer.wrap(mac.copyOfRange(0, 4)).int.toLong() and 0xffffffffL
        return (value % 1_000_000L).toString().padStart(6, '0')
    }

    private fun contactKeyId(localPeerId: String, contactPeerId: String, sessionId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$localPeerId|$contactPeerId|$sessionId".encodeToByteArray())
        return digest.take(16).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    }

    private fun storeContactKey(contactPeerId: String, keyId: String, key: ByteArray) {
        require(key.size == SYMMETRIC_KEY_SIZE_BYTES) { "Invalid contact key length" }
        prefs.edit()
            .putString(contactKeyAccount(contactPeerId, keyId), key.toBase64())
            .putString(latestContactKeyAccount(contactPeerId), keyId)
            .apply()
    }

    private fun latestContactKey(contactPeerId: String): ContactKey {
        val keyId = prefs.getString(latestContactKeyAccount(contactPeerId), null) ?: error("Missing contact key")
        return ContactKey(keyId, contactKey(contactPeerId, keyId))
    }

    private fun contactKey(contactPeerId: String, keyId: String): ByteArray {
        return prefs.getString(contactKeyAccount(contactPeerId, keyId), null)?.fromBase64() ?: error("Missing contact key")
    }

    private fun pairingPrivateKeyAccount(sessionId: String) = "pairing.$sessionId.private"
    private fun latestContactKeyAccount(contactPeerId: String) = "contact.$contactPeerId.latest"
    private fun contactKeyAccount(contactPeerId: String, keyId: String) = "contact.$contactPeerId.key.$keyId"
    private fun nowMillis(): Long = System.currentTimeMillis()

    private data class IdentityKey(val privateKey: PrivateKey, val rawPublicKey: ByteArray)
    private data class PairingOffer(val sessionId: String, val senderPeerId: String, val agreementPublicKey: String)
    private data class PairingAcceptance(val sessionId: String, val responderPeerId: String, val agreementPublicKey: String, val verificationCode: String)
    private data class ContactKey(val keyId: String, val key: ByteArray)

    private companion object {
        const val IDENTITY_PRIVATE = "identity.x25519.private"
        const val IDENTITY_PUBLIC = "identity.x25519.public"
        const val X25519_PUBLIC_KEY_SIZE_BYTES = 32
        const val X25519_PRIVATE_KEY_SIZE_BYTES = 32
        const val SYMMETRIC_KEY_SIZE_BYTES = 32
        const val NONCE_SIZE_BYTES = 12
        const val AUTH_TAG_SIZE_BYTES = 16
        const val BOUNCY_CASTLE_PROVIDER = "BC"

        fun installBouncyCastleProvider(): Provider {
            return Security.getProvider(BOUNCY_CASTLE_PROVIDER) ?: BouncyCastleProvider().also { Security.addProvider(it) }
        }
    }
}

private class RawX25519PrivateKey(val raw: ByteArray) : PrivateKey {
    override fun getAlgorithm(): String = "X25519"
    override fun getFormat(): String = "RAW"
    override fun getEncoded(): ByteArray = raw.copyOf()
}

private class RawX25519PublicKey(val raw: ByteArray) : java.security.PublicKey {
    override fun getAlgorithm(): String = "X25519"
    override fun getFormat(): String = "RAW"
    override fun getEncoded(): ByteArray = raw.copyOf()
}

private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

private fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.DEFAULT)

private fun BigInteger.toLittleEndian32(): ByteArray {
    val bytes = toByteArray()
    val unsignedBigEndian = if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    return unsignedBigEndian.reversedArray().copyOf(32)
}

private fun ByteArray.fromLittleEndian(): BigInteger = BigInteger(1, copyOf(32).reversedArray())

private fun requireJsonString(json: String, key: String): String {
    return Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"")
        .find(json)
        ?.groupValues
        ?.get(1)
        ?.jsonUnescape()
        ?: error("Missing JSON field: $key")
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

private fun String.jsonUnescape(): String = buildString {
    var index = 0
    while (index < this@jsonUnescape.length) {
        val char = this@jsonUnescape[index]
        if (char == '\\' && index + 1 < this@jsonUnescape.length) {
            when (val next = this@jsonUnescape[index + 1]) {
                'n' -> append('\n')
                'r' -> append('\r')
                't' -> append('\t')
                else -> append(next)
            }
            index += 2
        } else {
            append(char)
            index += 1
        }
    }
}
