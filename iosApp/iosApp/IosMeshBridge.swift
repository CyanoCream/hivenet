import Foundation
import MultipeerConnectivity
import Network
import ComposeApp

final class IosMeshBridge: NSObject, MeshBridge {
    static let shared = IosMeshBridge()

    private let serviceType = "hivenet"
    private var peerID: MCPeerID?
    private var session: MCSession?
    private var advertiser: MCNearbyServiceAdvertiser?
    private var browser: MCNearbyServiceBrowser?
    private var udpListener: NWListener?
    private var udpConnection: NWConnection?
    private var tcpListener: NWListener?
    private var netService: NetService?
    private var serviceBrowser: NetServiceBrowser?
    private var resolvingServices: [String: NetService] = [:]
    private var connectedPeers: [MCPeerID] = []
    private var udpPeers: [String: Date] = [:]
    private var tcpPeers: [String: NWEndpoint] = [:]
    private var seenEnvelopeIds: [String: Date] = [:]
    private var inbox: [String] = []
    private var lastEvent = "Stopped"
    private var localPeerId = "iphone"
    private var isRunning = false

    private override init() {}

    func start(localPeerId: String) -> MeshBridgeResult {
        stopInternal()
        self.localPeerId = localPeerId.isEmpty ? "iphone" : localPeerId
        let cleanName = String(localPeerId.prefix(63))
        let peerID = MCPeerID(displayName: cleanName.isEmpty ? "iphone" : cleanName)
        let session = MCSession(peer: peerID, securityIdentity: nil, encryptionPreference: .required)
        let advertiser = MCNearbyServiceAdvertiser(peer: peerID, discoveryInfo: nil, serviceType: serviceType)
        let browser = MCNearbyServiceBrowser(peer: peerID, serviceType: serviceType)

        session.delegate = self
        advertiser.delegate = self
        browser.delegate = self

        self.peerID = peerID
        self.session = session
        self.advertiser = advertiser
        self.browser = browser
        self.connectedPeers = []
        self.udpPeers = [:]
        self.tcpPeers = [:]
        self.resolvingServices = [:]
        self.seenEnvelopeIds = [:]
        self.inbox = []
        self.isRunning = true
        self.lastEvent = "Started as \(peerID.displayName)"

        advertiser.startAdvertisingPeer()
        browser.startBrowsingForPeers()
        startUdpLan()
        startTcpFallback()
        return MeshBridgeResult(ok: true, value: statusText(), error: nil)
    }

    func stop() -> MeshBridgeResult {
        stopInternal()
        lastEvent = "Stopped"
        return MeshBridgeResult(ok: true, value: statusText(), error: nil)
    }

    func status() -> MeshBridgeResult {
        MeshBridgeResult(ok: true, value: statusText(), error: nil)
    }

    func peers() -> MeshBridgeResult {
        pruneUdpPeers()
        var names = connectedPeers.map { "\($0.displayName) [multipeer]" }
        names.append(contentsOf: udpPeers.keys.sorted().map { "\($0) [udp]" })
        names.append(contentsOf: tcpPeers.keys.sorted().map { "\($0) [tcp]" })
        let joined = names.joined(separator: "\n")
        return MeshBridgeResult(ok: true, value: joined.isEmpty ? "Belum ada peer tersambung" : joined, error: nil)
    }

    func broadcast(envelope: String) -> MeshBridgeResult {
        guard let data = envelope.data(using: .utf8) else {
            return MeshBridgeResult(ok: false, value: nil, error: "Envelope bukan UTF-8 valid")
        }
        guard data.count <= IosMeshBridge.maxEnvelopeBytes else {
            return MeshBridgeResult(ok: false, value: nil, error: "Envelope terlalu besar: \(data.count) bytes, max \(IosMeshBridge.maxEnvelopeBytes)")
        }
        var sentTransports: [String] = []
        if let session, !session.connectedPeers.isEmpty {
            do {
                try session.send(data, toPeers: session.connectedPeers, with: .reliable)
                sentTransports.append("multipeer=\(session.connectedPeers.count)")
            } catch {
                lastEvent = "Multipeer broadcast failed: \(error.localizedDescription)"
            }
        }
        if let udpData = udpFrame(peerId: localPeerId, envelope: envelope).data(using: .utf8) {
            let connection = NWConnection(host: "255.255.255.255", port: NWEndpoint.Port(rawValue: IosMeshBridge.udpPort)!, using: .udp)
            connection.start(queue: .main)
            connection.send(content: udpData, completion: .contentProcessed { _ in connection.cancel() })
            sentTransports.append("udp=1")
        }
        let tcpTargets = tcpPeers
        for (_, endpoint) in tcpTargets {
            sendTcp(envelope: envelope, to: endpoint)
        }
        if !tcpTargets.isEmpty {
            sentTransports.append("tcp=\(tcpTargets.count)")
        }
        guard !sentTransports.isEmpty else {
            return MeshBridgeResult(ok: false, value: nil, error: "Belum ada transport aktif")
        }
        lastEvent = "Broadcast \(data.count) bytes via \(sentTransports.joined(separator: ", "))"
        return MeshBridgeResult(ok: true, value: lastEvent, error: nil)
    }

    private func startUdpLan() {
        do {
            let listener = try NWListener(using: .udp, on: NWEndpoint.Port(rawValue: IosMeshBridge.udpPort)!)
            listener.newConnectionHandler = { [weak self] connection in
                connection.start(queue: .main)
                self?.receiveUdp(on: connection)
            }
            listener.start(queue: .main)
            udpListener = listener
        } catch {
            lastEvent = "UDP listener failed: \(error.localizedDescription)"
        }
    }

    private func startTcpFallback() {
        do {
            let listener = try NWListener(using: .tcp, on: NWEndpoint.Port(rawValue: IosMeshBridge.tcpPort)!)
            listener.newConnectionHandler = { [weak self] connection in
                connection.start(queue: .main)
                self?.receiveTcp(on: connection)
            }
            listener.start(queue: .main)
            tcpListener = listener

            let service = NetService(domain: "local.", type: "_hivenet._tcp.", name: localPeerId, port: Int32(IosMeshBridge.tcpPort))
            service.includesPeerToPeer = true
            service.publish()
            netService = service

            let browser = NetServiceBrowser()
            browser.includesPeerToPeer = true
            browser.delegate = self
            browser.searchForServices(ofType: "_hivenet._tcp.", inDomain: "local.")
            serviceBrowser = browser
        } catch {
            lastEvent = "TCP fallback failed: \(error.localizedDescription)"
        }
    }

    private func sendTcp(envelope: String, to endpoint: NWEndpoint) {
        guard let data = (udpFrame(peerId: localPeerId, envelope: envelope) + "\n").data(using: .utf8) else { return }
        let connection = NWConnection(to: endpoint, using: .tcp)
        connection.start(queue: .main)
        connection.send(content: data, completion: .contentProcessed { _ in connection.cancel() })
    }

    private func receiveTcp(on connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 60_000) { [weak self] data, _, _, _ in
            guard let self else { return }
            if let data, let rawFrame = String(data: data, encoding: .utf8) {
                let frame = rawFrame.trimmingCharacters(in: .whitespacesAndNewlines)
                let sender = jsonString(frame, key: "peer_id") ?? "tcp-peer"
                let envelope = jsonString(frame, key: "envelope") ?? frame
                if sender != self.localPeerId && envelope.hasPrefix("KNET1:") && self.markTransportSeen(sender: sender, envelope: envelope) {
                    self.tcpPeers[sender] = connection.endpoint
                    self.inbox.append("from=\(sender)\n\(envelope)")
                    self.lastEvent = "Received TCP \(data.count) bytes from \(sender)"
                }
            }
            connection.cancel()
        }
    }

    private func receiveUdp(on connection: NWConnection) {
        connection.receiveMessage { [weak self] data, _, _, _ in
            guard let self else { return }
            if let data, let frame = String(data: data, encoding: .utf8) {
                let sender = jsonString(frame, key: "peer_id") ?? "udp-peer"
                let envelope = jsonString(frame, key: "envelope") ?? frame
                if sender != self.localPeerId && envelope.hasPrefix("KNET1:") && self.markTransportSeen(sender: sender, envelope: envelope) {
                    self.udpPeers[sender] = Date()
                    self.inbox.append("from=\(sender)\n\(envelope)")
                    self.lastEvent = "Received UDP \(data.count) bytes from \(sender)"
                }
            }
            if self.udpListener != nil {
                self.receiveUdp(on: connection)
            }
        }
    }

    func drainReceived() -> MeshBridgeResult {
        let messages = inbox.joined(separator: "\n---\n")
        inbox.removeAll()
        return MeshBridgeResult(ok: true, value: messages.isEmpty ? "Belum ada paket masuk" : messages, error: nil)
    }

    private func stopInternal() {
        advertiser?.stopAdvertisingPeer()
        browser?.stopBrowsingForPeers()
        session?.disconnect()
        udpListener?.cancel()
        udpConnection?.cancel()
        tcpListener?.cancel()
        netService?.stop()
        serviceBrowser?.stop()
        isRunning = false
        advertiser = nil
        browser = nil
        session = nil
        udpListener = nil
        udpConnection = nil
        tcpListener = nil
        netService = nil
        serviceBrowser = nil
        resolvingServices = [:]
        peerID = nil
        connectedPeers = []
        udpPeers = [:]
        tcpPeers = [:]
        seenEnvelopeIds = [:]
    }

    private func statusText() -> String {
        pruneUdpPeers()
        let localName = peerID?.displayName ?? "-"
        let peers = connectedPeers.map(\.displayName).joined(separator: ", ")
        let state = isRunning ? "active" : "stopped"
        let udpPeerText = udpPeers.keys.sorted().joined(separator: ", ")
        let tcpPeerText = tcpPeers.keys.sorted().joined(separator: ", ")
        return "state=\(state)\ntransport=Multipeer + UDP LAN/hotspot + mDNS/TCP\nlocal=\(localName)\nconnected=\(connectedPeers.count + udpPeers.count + tcpPeers.count)\nmultipeer_connected=\(connectedPeers.count)\nudp_seen=\(udpPeers.count)\ntcp_seen=\(tcpPeers.count)\npeers=\(peers.isEmpty ? "-" : peers)\nudp_peers=\(udpPeerText.isEmpty ? "-" : udpPeerText)\ntcp_peers=\(tcpPeerText.isEmpty ? "-" : tcpPeerText)\nudp_port=\(IosMeshBridge.udpPort)\ntcp_port=\(IosMeshBridge.tcpPort)\nlast=\(lastEvent)"
    }

    private func pruneUdpPeers() {
        let cutoff = Date().addingTimeInterval(-60)
        udpPeers = udpPeers.filter { $0.value >= cutoff }
    }

    private func markTransportSeen(sender: String, envelope: String) -> Bool {
        let now = Date()
        let cutoff = now.addingTimeInterval(-300)
        seenEnvelopeIds = seenEnvelopeIds.filter { $0.value >= cutoff }
        let id = "\(sender)|\(stableEnvelopeId(envelope))"
        if seenEnvelopeIds[id] != nil { return false }
        seenEnvelopeIds[id] = now
        return true
    }

    fileprivate static let udpPort: UInt16 = 47777
    fileprivate static let tcpPort: UInt16 = 47777
    fileprivate static let maxEnvelopeBytes = 48_000
}

private func udpFrame(peerId: String, envelope: String) -> String {
    "{\"peer_id\":\"\(jsonEscape(peerId))\",\"envelope\":\"\(jsonEscape(envelope))\"}"
}

private func jsonString(_ json: String, key: String) -> String? {
    let pattern = "\\\"\(NSRegularExpression.escapedPattern(for: key))\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\""
    guard let regex = try? NSRegularExpression(pattern: pattern),
          let match = regex.firstMatch(in: json, range: NSRange(json.startIndex..., in: json)),
          let range = Range(match.range(at: 1), in: json) else { return nil }
    return jsonUnescape(String(json[range]))
}

private func jsonEscape(_ value: String) -> String {
    value
        .replacingOccurrences(of: "\\", with: "\\\\")
        .replacingOccurrences(of: "\"", with: "\\\"")
        .replacingOccurrences(of: "\n", with: "\\n")
        .replacingOccurrences(of: "\r", with: "\\r")
        .replacingOccurrences(of: "\t", with: "\\t")
}

private func jsonUnescape(_ value: String) -> String {
    var output = ""
    var escaping = false
    for character in value {
        if escaping {
            switch character {
            case "n": output.append("\n")
            case "r": output.append("\r")
            case "t": output.append("\t")
            default: output.append(character)
            }
            escaping = false
        } else if character == "\\" {
            escaping = true
        } else {
            output.append(character)
        }
    }
    return output
}

private func stableEnvelopeId(_ value: String) -> String {
    var hash: UInt64 = 0xcbf29ce484222325
    for byte in value.utf8 {
        hash ^= UInt64(byte)
        hash &*= 0x100000001b3
    }
    return String(format: "%016llx", hash)
}

extension IosMeshBridge: MCSessionDelegate {
    func session(_ session: MCSession, peer peerID: MCPeerID, didChange state: MCSessionState) {
        connectedPeers = session.connectedPeers
        let stateName: String
        switch state {
        case .notConnected: stateName = "notConnected"
        case .connecting: stateName = "connecting"
        case .connected: stateName = "connected"
        @unknown default: stateName = "unknown"
        }
        lastEvent = "Peer \(peerID.displayName) \(stateName)"
    }

    func session(_ session: MCSession, didReceive data: Data, fromPeer peerID: MCPeerID) {
        if let envelope = String(data: data, encoding: .utf8) {
            if peerID.displayName != localPeerId && envelope.hasPrefix("KNET1:") && markTransportSeen(sender: peerID.displayName, envelope: envelope) {
                inbox.append("from=\(peerID.displayName)\n\(envelope)")
                lastEvent = "Received \(data.count) bytes from \(peerID.displayName)"
            }
        }
    }

    func session(_ session: MCSession, didReceive stream: InputStream, withName streamName: String, fromPeer peerID: MCPeerID) {}
    func session(_ session: MCSession, didStartReceivingResourceWithName resourceName: String, fromPeer peerID: MCPeerID, with progress: Progress) {}
    func session(_ session: MCSession, didFinishReceivingResourceWithName resourceName: String, fromPeer peerID: MCPeerID, at localURL: URL?, withError error: Error?) {}
}

extension IosMeshBridge: MCNearbyServiceAdvertiserDelegate {
    func advertiser(_ advertiser: MCNearbyServiceAdvertiser, didReceiveInvitationFromPeer peerID: MCPeerID, withContext context: Data?, invitationHandler: @escaping (Bool, MCSession?) -> Void) {
        invitationHandler(true, session)
        lastEvent = "Accepted invite from \(peerID.displayName)"
    }
}

extension IosMeshBridge: MCNearbyServiceBrowserDelegate {
    func browser(_ browser: MCNearbyServiceBrowser, foundPeer peerID: MCPeerID, withDiscoveryInfo info: [String: String]?) {
        guard peerID != self.peerID else { return }
        guard let session else { return }
        browser.invitePeer(peerID, to: session, withContext: nil, timeout: 10)
        lastEvent = "Invited \(peerID.displayName)"
    }

    func browser(_ browser: MCNearbyServiceBrowser, lostPeer peerID: MCPeerID) {
        connectedPeers = session?.connectedPeers ?? []
        lastEvent = "Lost \(peerID.displayName)"
    }
}

extension IosMeshBridge: NetServiceBrowserDelegate, NetServiceDelegate {
    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        guard service.name != localPeerId else { return }
        service.delegate = self
        resolvingServices[service.name] = service
        service.resolve(withTimeout: 5)
        lastEvent = "Resolving TCP peer \(service.name)"
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        tcpPeers.removeValue(forKey: service.name)
        lastEvent = "Lost TCP peer \(service.name)"
    }

    func netServiceDidResolveAddress(_ sender: NetService) {
        guard sender.name != localPeerId else { return }
        let host = NWEndpoint.Host(sender.hostName ?? "\(sender.name).local")
        let port = NWEndpoint.Port(rawValue: UInt16(sender.port)) ?? NWEndpoint.Port(rawValue: IosMeshBridge.tcpPort)!
        tcpPeers[sender.name] = .hostPort(host: host, port: port)
        resolvingServices.removeValue(forKey: sender.name)
        lastEvent = "Resolved TCP peer \(sender.name)"
    }
}
