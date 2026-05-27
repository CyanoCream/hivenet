# Kampung-Net

Kampung-Net is an offline-first emergency mesh messenger prototype. The current implementation is iPhone-first, with Android foundations being prepared for Android-iPhone testing.

## Current Status

- Stage 1 is complete: iOS CryptoKit + Keychain foundation, Kotlin bridge, manual pairing, encrypted local chat, and SQLite persistence/outbox.
- Stage 2 is complete: iPhone-to-iPhone MultipeerConnectivity debug mesh, outbox sending, incoming packet persistence, relay cache/forwarding, delivery receipts, and foreground auto-sync UX.
- Chat UI is DB-backed, shows saved trusted contacts, and can auto-drain pending outbox packets from the chat screen when the foreground mesh bridge is active.
- Stage 3A is complete: Android database wiring uses `AndroidSqliteDriver` with `Context` injection.
- Stage 3B is in progress: Android crypto bridge exists, but Android compile/runtime verification is pending until an Android SDK is available.
- Stage 3C is implemented as a first pass: Android and iOS now have a UDP LAN/hotspot mesh bridge foundation on port `47777` for practical Android-iPhone testing on the same network.
- Stage 3D/3E are implemented as a first pass: successful pairing saves trusted contacts to SQLite, and the encrypted chat screen lists saved contacts before manual peer-id fallback.
- Stage 3F is implemented as a foreground first pass: sending from the encrypted chat screen immediately tries to broadcast pending outbox packets through the active mesh bridge.
- Stage 3G is blocked locally for Android compile verification because this machine does not currently have an Android SDK configured.
- Stage 4A is implemented as a first pass: chat envelopes now include `source_peer_id` and `target_peer_id` metadata, so relayed packets can be decrypted against the original sender instead of the last transport hop.
- Stage 4B/4C are implemented as a first pass: relay fallback packet IDs now use a stable FNV-1a 64-bit envelope ID instead of platform `hashCode()`, and receive processing is target-aware so packets for other devices are cached for relay without local decrypt attempts.
- Stage 4D is implemented as a first pass: the encrypted chat screen now prioritizes the normal user flow with a cleaner secure-chat layout, contact pills, larger message area, composer actions, and advanced/debug fields moved out of the main path.
- Stage 4E is implemented as a first pass: tapping Chat Aman now opens a secure inbox/contact-thread list before entering a specific encrypted chat room.
- Stage 4F is implemented as a first pass: outbox and relay broadcasts now check that Mesh Lokal has been started before marking packets as relayed, and chat send status distinguishes local outbox storage from actual mesh broadcast attempts.
- Stage 5A/5B are implemented as a first pass: mesh status now exposes a stable `state=active|stopped` field for broadcast guards, and UDP peers seen in the last 60 seconds are tracked on both iOS and Android for the Mesh Lokal dashboard.
- Stage 5C is implemented as a first pass: self-origin packets and recently seen transport envelopes are filtered before relay forwarding to reduce echo loops across UDP/Multipeer paths.
- Stage 5D is implemented as a first pass: outbox retries now use a simple backoff window, messages are marked as broadcasted after a mesh send attempt, and `DELIVERED` is reserved for valid delivery receipts.
- Stage 5E is implemented as a first pass: iOS and Android now advertise/discover `_kampungnet._tcp` peers and attempt TCP direct sends in addition to UDP broadcast, improving LAN/hotspot behavior when broadcast is filtered. Mesh envelope sends are capped at roughly 48 KB to avoid oversized UDP/TCP debug frames.

## Android Build Setup

Android builds require an installed Android SDK. Configure it with either environment variables:

```bash
export ANDROID_HOME=/path/to/Android/Sdk
export ANDROID_SDK_ROOT=/path/to/Android/Sdk
```

Or create `local.properties` in the project root:

```properties
sdk.dir=/path/to/Android/Sdk
```

Then compile Android:

```bash
JAVA_HOME="/var/folders/s5/j_gww7_j31ncwmywnfvp51600000gn/T/opencode/jdk-17.0.19+10/Contents/Home" ./gradlew :composeApp:compileDebugKotlinAndroid
```

If using Android Studio, opening the project should create `local.properties` automatically after the SDK is configured.

## Android Crypto Notes

The Android bridge currently targets compatibility with iOS `SecureCryptoService.swift`:

- X25519 key agreement through JCA `XDH` / `NamedParameterSpec("X25519")`.
- HKDF-SHA256 with salt `kampungnet-v1` and `sessionId` as shared info.
- ChaCha20-Poly1305 payload fields matching iOS: `version`, `algorithm`, `key_id`, `nonce`, `ciphertext`, `auth_tag`, and `aad`.
- Manual pairing JSON fields matching iOS offer/acceptance payloads.

Runtime verification is still required on Android devices because provider support for `XDH` and `ChaCha20-Poly1305` depends on the Android runtime/provider stack.

The latest local Android compile attempt is expected to fail until SDK setup is done: `SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable or by setting the sdk.dir path in local.properties`.

Current Android key storage is `SharedPreferences` for early interop testing. Move identity/contact keys to Android Keystore or encrypted storage before treating it as secure for real users.

## Manual Android-iPhone Test Checklist

Use this once Android compile/install works on another machine:

1. Launch iPhone app and Android app.
2. On one device, create a pairing offer from the Pair Contact screen.
3. Copy the `KNET1:PAIRING_OFFER:...` text to the other device.
4. Accept the offer and copy back the `KNET1:PAIRING_ACCEPT:...` text.
5. Complete pairing on the original device and confirm the verification code matches.
6. Confirm the paired peer appears in Chat Aman as a secure inbox/contact thread.
7. Open that thread and confirm the encrypted chat room is pre-filled with the selected peer.
8. Start Mesh Lokal / Auto-Sync on both devices while the apps stay foreground.
9. Send from the encrypted chat screen and confirm pending outbox packets are broadcast over the mesh bridge.
10. Confirm the other device decrypts and stores the message in the DB-backed chat UI.
11. Repeat in the opposite direction.

## Transport Notes

iPhone-to-iPhone debug transport currently uses MultipeerConnectivity. Android-iPhone testing now has an early UDP LAN/hotspot bridge foundation on port `47777`; both devices must be on the same WiFi or hotspot network, and the app must be foreground for reliable testing.

Stage 5E adds a LAN fallback on the same port: devices advertise and discover `_kampungnet._tcp` through Bonjour/mDNS/Android NSD, then try TCP direct sends to discovered peers alongside UDP broadcast. UDP remains the simplest broadcast path; TCP is a best-effort fallback for networks that filter `255.255.255.255`. TCP frames are newline-delimited and mesh envelope sends are capped at roughly 48 KB for the current debug transport. Android TCP/NSD code is still not compile/runtime verified locally because the Android SDK is unavailable on this machine.

Chat envelope metadata is now explicit: `packet_id`, `source_peer_id`, `target_peer_id`, and encrypted `payload`. Relays still cannot read the encrypted payload, but they can preserve sender/target metadata for forwarding and receipt handling.

Fallback relay/dedup packet IDs are derived from a stable envelope ID so the same packet maps consistently across platforms. This is not a cryptographic hash; it is only used as a deterministic fallback until a shared SHA helper is added.

Receive handling is target-aware: if `target_peer_id` is present and does not match the local peer id, the app marks the packet as seen and stores it in relay cache instead of trying to decrypt someone else's ciphertext.

The main encrypted-chat flow is now contact-first: Home -> Chat Aman -> secure inbox/contact thread -> chat room. Manual peer-id entry and raw envelope debug controls still exist, but they live in the advanced section for troubleshooting.

Outbox sync is guarded by mesh status. If Mesh Lokal has not been started, encrypted messages stay in the local outbox and the UI reports that the packet is stored locally instead of implying it was broadcast.

Outbox status is intentionally conservative. `QUEUED` means stored locally, `RELAYED`/`BROADCASTED` means the packet was handed to the mesh transport, and `DELIVERED` is only set after a delivery receipt for the local target is processed. Retryable outbox packets are rebroadcast after a short backoff instead of on every auto-sync tick.

Mesh status now includes structured lines such as `state`, `transport`, `local`, `connected`, `udp_seen`, and `tcp_seen`. The UI uses `state=active` for broadcast readiness instead of parsing human-readable event text. UDP peer tracking is best-effort and expires peers after roughly 60 seconds without packets.

Relay anti-echo is layered: transport bridges ignore self-origin and recently seen envelopes for a short window, and receive processing drops chat/receipt packets whose declared source is the local peer before they can be re-cached for relay.

Likely next transport options:

- WiFi LAN or hotspot with UDP broadcast plus mDNS/TCP direct fallback for practical Android-iPhone testing on the same network.
- BLE for direct offline discovery/transfer, with lower bandwidth and more platform-specific work.

iPhone relay remains foreground/best-effort because iOS limits background networking. Android relay should later use a foreground service for stronger background behavior.
