# HiveNet

HiveNet is an offline-first encrypted mesh messenger for communities. Works over local WiFi, hotspot, and Bluetooth — no internet, no servers, no accounts. Built with Kotlin Multiplatform (Android + iOS) and Compose Multiplatform.

## Features

- **End-to-end encryption** — X25519 key agreement, HKDF-SHA256, ChaCha20-Poly1305
- **Mesh relay** — gossip protocol with TTL, outbox, relay cache, and delivery receipts
- **LAN/hotspot transport** — UDP broadcast on port `47777` + mDNS/TCP direct fallback (`_hivenet._tcp`)
- **iOS Multipeer** — MultipeerConnectivity for iPhone-to-iPhone mesh
- **QR pairing** — scan to exchange public keys; no central directory
- **Offline-first** — SQLite-backed outbox; messages queued until a mesh path exists
- **Cross-platform** — single Compose UI codebase for Android and iOS

## Package IDs

| Module | Package |
|--------|---------|
| App | `id.hivenet.app` |
| Shared | `id.hivenet.shared` |
| Database | `id.hivenet.db` (SQLDelight: `HiveNetDatabase`) |

## Current Status

- Stage 1 complete: iOS CryptoKit + Keychain, Kotlin bridge, manual pairing, encrypted local chat, SQLite persistence/outbox.
- Stage 2 complete: iPhone-to-iPhone MultipeerConnectivity mesh, outbox sending, incoming packet persistence, relay cache/forwarding, delivery receipts, foreground auto-sync.
- Stage 3A complete: Android database via `AndroidSqliteDriver` with `Context` injection.
- Stage 3B complete: Android crypto bridge (`AndroidCryptoBridge`) matching iOS `SecureCryptoService.swift` key agreement and payload format.
- Stage 3C complete: UDP LAN/hotspot mesh bridge on port `47777` for Android-iPhone cross-platform testing.
- Stage 3D/3E complete: successful pairing saves trusted contacts to SQLite; encrypted chat lists saved contacts before manual peer-id fallback.
- Stage 3F complete: sending from encrypted chat immediately broadcasts pending outbox packets through the active mesh bridge.
- Stage 4A complete: chat envelopes include `source_peer_id` / `target_peer_id` so relayed packets decrypt against the original sender.
- Stage 4B/4C complete: stable FNV-1a 64-bit envelope IDs; target-aware receive drops or relay-caches packets not addressed to the local peer.
- Stage 4D–4F complete: contact-first chat flow (Home → Secure Chat → contact thread → chat room); outbox/relay broadcasts guarded by mesh state.
- Stage 5A–5E complete: structured mesh status (`state`, `udp_seen`, `tcp_seen`); anti-echo filtering; backoff retries; mDNS/TCP direct sends alongside UDP broadcast; 48 KB envelope cap.

## Android Build Setup

Requires Android SDK. Set via environment variable:

```bash
export ANDROID_HOME=/path/to/Android/Sdk
```

Or add to `local.properties` at project root:

```properties
sdk.dir=/path/to/Android/Sdk
```

Build debug APK:

```bash
./gradlew :composeApp:assembleDebug
```

Android Studio creates `local.properties` automatically after SDK is configured.

## Crypto Notes

Android bridge targets compatibility with iOS `SecureCryptoService.swift`:

- X25519 key agreement via JCA `XDH` / `NamedParameterSpec("X25519")`
- HKDF-SHA256 with salt `kampungnet-v1` and `sessionId` as shared info (wire format preserved for cross-platform compatibility)
- ChaCha20-Poly1305 payload fields: `version`, `algorithm`, `key_id`, `nonce`, `ciphertext`, `auth_tag`, `aad`
- Manual pairing JSON fields match iOS offer/acceptance payloads
- Identity/contact keys stored in `SharedPreferences` (`hivenet_crypto`) for early testing — migrate to Android Keystore before production

## Manual Android-iPhone Test Checklist

1. Launch HiveNet on iPhone and Android.
2. On one device, create a pairing offer (Pair Contact screen).
3. Copy the `KNET1:PAIRING_OFFER:…` string to the other device.
4. Accept the offer; copy back the `KNET1:PAIRING_ACCEPT:…` string.
5. Complete pairing on the original device; confirm the verification code matches.
6. Confirm the paired peer appears in Secure Chat contact list.
7. Open the contact thread; confirm the chat room pre-fills the peer.
8. Start Mesh on both devices (apps must be foreground).
9. Send a message; confirm the outbox broadcasts over the mesh bridge.
10. Confirm the other device decrypts and stores the message.
11. Repeat in the opposite direction.

## Transport Notes

**UDP broadcast** on port `47777` is the primary Android-iPhone path. Both devices must be on the same WiFi or hotspot network and the app must be foreground for reliable transport.

**mDNS/TCP direct** (`_hivenet._tcp`) is a fallback for networks that filter broadcast. Devices advertise via Bonjour/NSD and attempt TCP sends alongside UDP. TCP frames are newline-delimited; mesh envelopes are capped at ~48 KB.

**MultipeerConnectivity** handles iPhone-to-iPhone mesh (foreground/best-effort only; iOS limits background networking).

**Android foreground service** (`MeshForegroundService`, channel `hivenet_mesh`) keeps the mesh relay alive when the app moves to background on Android.

### Envelope format

```
{ packet_id, source_peer_id, target_peer_id, payload (encrypted) }
```

Relays forward without decrypting. Envelope IDs use FNV-1a 64-bit for stable cross-platform dedup. `DELIVERED` status is set only after a valid delivery receipt for the local peer.

### Relay anti-echo

- Transport bridges discard self-origin and recently seen envelopes
- Receive processing drops chat/receipt packets whose `source_peer_id` matches the local peer before relay-caching

### Outbox status

| Status | Meaning |
|--------|---------|
| `QUEUED` | Stored locally; mesh not started |
| `BROADCASTED` | Handed to mesh transport |
| `DELIVERED` | Delivery receipt received |

## Roadmap

- BLE transport for true offline (no WiFi required)
- Group chat / broadcast channels
- Android Keystore for identity key storage
- Background mesh improvement on iOS
