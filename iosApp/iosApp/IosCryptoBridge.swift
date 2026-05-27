import ComposeApp
import Foundation

final class IosCryptoBridge: CryptoBridge {
    private let service: SecureCryptoService
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(service: SecureCryptoService = .shared) {
        self.service = service
    }

    func getOrCreateIdentityPublicKey() -> CryptoBridgeResult {
        wrap { try service.getOrCreateIdentityPublicKey() }
    }

    func createPairingOffer(contactPeerId: String, senderPeerId: String, senderName: String) -> CryptoBridgeResult {
        wrap {
            let offer = try service.createPairingOffer(
                contactPeerId: contactPeerId,
                senderPeerId: senderPeerId,
                senderName: senderName
            )
            return try encode(offer)
        }
    }

    func acceptPairingOffer(offerJson: String, responderPeerId: String, responderName: String) -> CryptoBridgeResult {
        wrap {
            let offer = try decode(PairingOfferPayload.self, from: offerJson)
            let acceptance = try service.acceptPairingOffer(
                offer,
                responderPeerId: responderPeerId,
                responderName: responderName
            )
            return try encode(acceptance)
        }
    }

    func completePairing(acceptanceJson: String, localPeerId: String) -> CryptoBridgeResult {
        wrap {
            let acceptance = try decode(PairingAcceptancePayload.self, from: acceptanceJson)
            let contactKey = try service.completePairing(acceptance: acceptance, localPeerId: localPeerId)
            return try encode(contactKey)
        }
    }

    func encrypt(contactPeerId: String, plaintextBase64: String, associatedDataBase64: String) -> CryptoBridgeResult {
        wrap {
            guard let plaintext = Data(base64Encoded: plaintextBase64),
                  let associatedData = Data(base64Encoded: associatedDataBase64) else {
                throw SecureCryptoError.invalidCiphertext
            }
            let payload = try service.encrypt(
                contactPeerId: contactPeerId,
                plaintext: plaintext,
                associatedData: associatedData
            )
            return try encode(payload)
        }
    }

    func decrypt(contactPeerId: String, payloadJson: String, associatedDataBase64: String) -> CryptoBridgeResult {
        wrap {
            guard let associatedData = Data(base64Encoded: associatedDataBase64) else {
                throw SecureCryptoError.invalidCiphertext
            }
            let payload = try decode(IosEncryptedPayload.self, from: payloadJson)
            let plaintext = try service.decrypt(
                contactPeerId: contactPeerId,
                payload: payload,
                associatedData: associatedData
            )
            return plaintext.base64EncodedString()
        }
    }

    private func wrap(_ action: () throws -> String) -> CryptoBridgeResult {
        do {
            return CryptoBridgeResult(ok: true, value: try action(), error: nil)
        } catch {
            return CryptoBridgeResult(ok: false, value: nil, error: String(describing: error))
        }
    }

    private func encode<T: Encodable>(_ value: T) throws -> String {
        let data = try encoder.encode(value)
        return String(data: data, encoding: .utf8) ?? ""
    }

    private func decode<T: Decodable>(_ type: T.Type, from json: String) throws -> T {
        guard let data = json.data(using: .utf8) else {
            throw SecureCryptoError.invalidCiphertext
        }
        return try decoder.decode(type, from: data)
    }
}
