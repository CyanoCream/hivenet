import CryptoKit
import Foundation
import Security

enum SecureCryptoError: Error {
    case invalidPeerKey
    case missingIdentity
    case missingContactKey
    case keychain(OSStatus)
    case invalidCiphertext
}

struct PairingOfferPayload: Codable {
    let sessionId: String
    let senderPeerId: String
    let senderName: String
    let identityPublicKey: String
    let agreementPublicKey: String
    let timestamp: Int64
}

struct PairingAcceptancePayload: Codable {
    let sessionId: String
    let responderPeerId: String
    let responderName: String
    let identityPublicKey: String
    let agreementPublicKey: String
    let verificationCode: String
    let timestamp: Int64
}

struct ContactKeyRecord: Codable {
    let contactPeerId: String
    let keyId: String
    let algorithm: String
    let createdAt: Int64
    let verifiedAt: Int64?
}

struct IosEncryptedPayload: Codable {
    let version: Int
    let algorithm: String
    let keyId: String
    let nonce: String
    let ciphertext: String
    let authTag: String?
    let associatedData: String?

    enum CodingKeys: String, CodingKey {
        case version
        case algorithm
        case keyId = "key_id"
        case nonce
        case ciphertext
        case authTag = "auth_tag"
        case associatedData = "aad"
    }
}

final class SecureCryptoService {
    static let shared = SecureCryptoService()

    private let keychain = KeychainStore(service: "id.kampungnet.app.crypto")
    private let identityKeyName = "identity.curve25519.private"
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    private init() {}

    func getOrCreateIdentityPublicKey() throws -> String {
        let key = try getOrCreateIdentityKey()
        return key.publicKey.rawRepresentation.base64EncodedString()
    }

    func createPairingOffer(contactPeerId: String, senderPeerId: String, senderName: String) throws -> PairingOfferPayload {
        let identityKey = try getOrCreateIdentityKey()
        let agreementKey = Curve25519.KeyAgreement.PrivateKey()
        let sessionId = UUID().uuidString

        try keychain.set(
            agreementKey.rawRepresentation,
            account: pairingPrivateKeyAccount(sessionId: sessionId)
        )

        return PairingOfferPayload(
            sessionId: sessionId,
            senderPeerId: senderPeerId,
            senderName: senderName,
            identityPublicKey: identityKey.publicKey.rawRepresentation.base64EncodedString(),
            agreementPublicKey: agreementKey.publicKey.rawRepresentation.base64EncodedString(),
            timestamp: nowMillis()
        )
    }

    func acceptPairingOffer(_ offer: PairingOfferPayload, responderPeerId: String, responderName: String) throws -> PairingAcceptancePayload {
        let identityKey = try getOrCreateIdentityKey()
        let responderAgreementKey = Curve25519.KeyAgreement.PrivateKey()
        let offerPublicKey = try agreementPublicKey(from: offer.agreementPublicKey)
        let sharedKey = try deriveSymmetricKey(privateKey: responderAgreementKey, remotePublicKey: offerPublicKey, context: offer.sessionId)
        let keyId = contactKeyId(localPeerId: responderPeerId, contactPeerId: offer.senderPeerId, sessionId: offer.sessionId)
        let verificationCode = verificationCode(for: sharedKey, sessionId: offer.sessionId)

        try storeContactKey(sharedKey, keyId: keyId, contactPeerId: offer.senderPeerId)

        return PairingAcceptancePayload(
            sessionId: offer.sessionId,
            responderPeerId: responderPeerId,
            responderName: responderName,
            identityPublicKey: identityKey.publicKey.rawRepresentation.base64EncodedString(),
            agreementPublicKey: responderAgreementKey.publicKey.rawRepresentation.base64EncodedString(),
            verificationCode: verificationCode,
            timestamp: nowMillis()
        )
    }

    func completePairing(acceptance: PairingAcceptancePayload, localPeerId: String) throws -> ContactKeyRecord {
        guard let privateKeyData = try keychain.get(account: pairingPrivateKeyAccount(sessionId: acceptance.sessionId)) else {
            throw SecureCryptoError.missingIdentity
        }

        let privateKey = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privateKeyData)
        let remotePublicKey = try agreementPublicKey(from: acceptance.agreementPublicKey)
        let sharedKey = try deriveSymmetricKey(privateKey: privateKey, remotePublicKey: remotePublicKey, context: acceptance.sessionId)
        let expectedCode = verificationCode(for: sharedKey, sessionId: acceptance.sessionId)

        guard expectedCode == acceptance.verificationCode else {
            throw SecureCryptoError.invalidPeerKey
        }

        let keyId = contactKeyId(localPeerId: localPeerId, contactPeerId: acceptance.responderPeerId, sessionId: acceptance.sessionId)
        try storeContactKey(sharedKey, keyId: keyId, contactPeerId: acceptance.responderPeerId)
        try keychain.delete(account: pairingPrivateKeyAccount(sessionId: acceptance.sessionId))

        return ContactKeyRecord(
            contactPeerId: acceptance.responderPeerId,
            keyId: keyId,
            algorithm: "ChaCha20-Poly1305",
            createdAt: nowMillis(),
            verifiedAt: nowMillis()
        )
    }

    func encrypt(contactPeerId: String, plaintext: Data, associatedData: Data) throws -> IosEncryptedPayload {
        let keyRecord = try latestContactKey(contactPeerId: contactPeerId)
        let sealedBox = try ChaChaPoly.seal(plaintext, using: keyRecord.key, authenticating: associatedData)

        return IosEncryptedPayload(
            version: 1,
            algorithm: "ChaCha20-Poly1305",
            keyId: keyRecord.keyId,
            nonce: sealedBox.nonce.withUnsafeBytes { Data($0).base64EncodedString() },
            ciphertext: sealedBox.ciphertext.base64EncodedString(),
            authTag: sealedBox.tag.base64EncodedString(),
            associatedData: associatedData.base64EncodedString()
        )
    }

    func decrypt(contactPeerId: String, payload: IosEncryptedPayload, associatedData: Data) throws -> Data {
        let keyRecord = try contactKey(contactPeerId: contactPeerId, keyId: payload.keyId)
        let tagData = payload.authTag.flatMap { Data(base64Encoded: $0) }
        guard
            let nonceData = Data(base64Encoded: payload.nonce),
            let ciphertext = Data(base64Encoded: payload.ciphertext),
            let tag = tagData
        else {
            throw SecureCryptoError.invalidCiphertext
        }

        let nonce = try ChaChaPoly.Nonce(data: nonceData)
        let sealedBox = try ChaChaPoly.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag)
        return try ChaChaPoly.open(sealedBox, using: keyRecord.key, authenticating: associatedData)
    }

    private func getOrCreateIdentityKey() throws -> Curve25519.KeyAgreement.PrivateKey {
        if let existing = try keychain.get(account: identityKeyName) {
            return try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: existing)
        }

        let key = Curve25519.KeyAgreement.PrivateKey()
        try keychain.set(key.rawRepresentation, account: identityKeyName)
        return key
    }

    private func agreementPublicKey(from base64: String) throws -> Curve25519.KeyAgreement.PublicKey {
        guard let data = Data(base64Encoded: base64) else {
            throw SecureCryptoError.invalidPeerKey
        }
        return try Curve25519.KeyAgreement.PublicKey(rawRepresentation: data)
    }

    private func deriveSymmetricKey(
        privateKey: Curve25519.KeyAgreement.PrivateKey,
        remotePublicKey: Curve25519.KeyAgreement.PublicKey,
        context: String
    ) throws -> SymmetricKey {
        let secret = try privateKey.sharedSecretFromKeyAgreement(with: remotePublicKey)
        return secret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data("kampungnet-v1".utf8),
            sharedInfo: Data(context.utf8),
            outputByteCount: 32
        )
    }

    private func verificationCode(for key: SymmetricKey, sessionId: String) -> String {
        let mac = HMAC<SHA256>.authenticationCode(for: Data(sessionId.utf8), using: key)
        let value = mac.prefix(4).reduce(UInt32(0)) { ($0 << 8) | UInt32($1) } % 1_000_000
        return String(format: "%06d", value)
    }

    private func contactKeyId(localPeerId: String, contactPeerId: String, sessionId: String) -> String {
        let digest = SHA256.hash(data: Data("\(localPeerId)|\(contactPeerId)|\(sessionId)".utf8))
        return digest.prefix(16).map { String(format: "%02x", $0) }.joined()
    }

    private func storeContactKey(_ key: SymmetricKey, keyId: String, contactPeerId: String) throws {
        let keyData = key.withUnsafeBytes { Data($0) }
        try keychain.set(keyData, account: contactKeyAccount(contactPeerId: contactPeerId, keyId: keyId))
        try keychain.set(Data(keyId.utf8), account: latestContactKeyAccount(contactPeerId: contactPeerId))
    }

    private func latestContactKey(contactPeerId: String) throws -> (keyId: String, key: SymmetricKey) {
        guard let keyIdData = try keychain.get(account: latestContactKeyAccount(contactPeerId: contactPeerId)),
              let keyId = String(data: keyIdData, encoding: .utf8) else {
            throw SecureCryptoError.missingContactKey
        }
        return try contactKey(contactPeerId: contactPeerId, keyId: keyId)
    }

    private func contactKey(contactPeerId: String, keyId: String) throws -> (keyId: String, key: SymmetricKey) {
        guard let keyData = try keychain.get(account: contactKeyAccount(contactPeerId: contactPeerId, keyId: keyId)) else {
            throw SecureCryptoError.missingContactKey
        }
        return (keyId, SymmetricKey(data: keyData))
    }

    private func pairingPrivateKeyAccount(sessionId: String) -> String {
        "pairing.\(sessionId).private"
    }

    private func latestContactKeyAccount(contactPeerId: String) -> String {
        "contact.\(contactPeerId).latest"
    }

    private func contactKeyAccount(contactPeerId: String, keyId: String) -> String {
        "contact.\(contactPeerId).key.\(keyId)"
    }

    private func nowMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}

private final class KeychainStore {
    private let service: String

    init(service: String) {
        self.service = service
    }

    func set(_ data: Data, account: String) throws {
        let query = baseQuery(account: account)
        let attributes: [String: Any] = [kSecValueData as String: data]
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)

        if status == errSecSuccess {
            return
        }

        if status != errSecItemNotFound {
            throw SecureCryptoError.keychain(status)
        }

        var addQuery = query
        addQuery[kSecValueData as String] = data
        addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let addStatus = SecItemAdd(addQuery as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            throw SecureCryptoError.keychain(addStatus)
        }
    }

    func get(account: String) throws -> Data? {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound {
            return nil
        }
        guard status == errSecSuccess else {
            throw SecureCryptoError.keychain(status)
        }
        return item as? Data
    }

    func delete(account: String) throws {
        let status = SecItemDelete(baseQuery(account: account) as CFDictionary)
        if status != errSecSuccess && status != errSecItemNotFound {
            throw SecureCryptoError.keychain(status)
        }
    }

    private func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
    }
}
