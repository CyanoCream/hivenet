package id.kampungnet.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class AndroidUdpMeshBridge(context: Context) : MeshBridge {
    private val appContext = context.applicationContext
    private val inbox = CopyOnWriteArrayList<String>()
    private val seenPeers = ConcurrentHashMap<String, Long>()
    private val tcpPeers = ConcurrentHashMap<String, InetSocketAddress>()
    private val seenEnvelopeIds = ConcurrentHashMap<String, Long>()
    private var socket: DatagramSocket? = null
    private var tcpServerSocket: ServerSocket? = null
    private var receiveThread: Thread? = null
    private var tcpReceiveThread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var localPeerId: String = "android"
    @Volatile private var resolvingNsd = false
    @Volatile private var running = false
    @Volatile private var lastEvent = "Stopped"

    override fun start(localPeerId: String): MeshBridgeResult = wrap {
        stopInternal()
        this.localPeerId = localPeerId.ifBlank { "android" }
        seenPeers.clear()
        tcpPeers.clear()
        seenEnvelopeIds.clear()
        acquireMulticastLock()
        val newSocket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(PORT))
        }
        socket = newSocket
        running = true
        receiveThread = thread(name = "kampungnet-udp-receiver", isDaemon = true) {
            receiveLoop(newSocket)
        }
        startTcpFallback()
        lastEvent = "UDP/TCP LAN started as ${this.localPeerId} on port $PORT"
        statusText()
    }

    override fun stop(): MeshBridgeResult = wrap {
        stopInternal()
        lastEvent = "Stopped"
        statusText()
    }

    override fun status(): MeshBridgeResult = wrap { statusText() }

    override fun peers(): MeshBridgeResult = wrap {
        prunePeers()
        val peers = buildList {
            addAll(seenPeers.keys().toList().sorted().map { "$it [udp]" })
            addAll(tcpPeers.keys().toList().sorted().map { "$it [tcp]" })
        }
        peers.joinToString("\n").ifBlank { "Belum ada peer tersambung" }
    }

    override fun broadcast(envelope: String): MeshBridgeResult = wrap {
        val activeSocket = socket ?: error("Mesh UDP belum start")
        val frame = udpFrame(localPeerId, envelope).encodeToByteArray()
        require(frame.size <= MAX_PACKET_BYTES) { "Envelope terlalu besar: ${frame.size} bytes, max $MAX_PACKET_BYTES" }
        val packet = DatagramPacket(frame, frame.size, InetAddress.getByName(BROADCAST_ADDRESS), PORT)
        activeSocket.send(packet)
        val tcpTargets = tcpPeers.values.toList()
        tcpTargets.forEach { address -> sendTcp(envelope, address) }
        lastEvent = "Broadcast UDP ${frame.size} bytes + TCP ${tcpTargets.size} peers as $localPeerId"
        lastEvent
    }

    override fun drainReceived(): MeshBridgeResult = wrap {
        val messages = inbox.joinToString("\n---\n")
        inbox.clear()
        messages.ifBlank { "Belum ada paket masuk" }
    }

    private fun receiveLoop(activeSocket: DatagramSocket) {
        val buffer = ByteArray(MAX_PACKET_BYTES)
        while (running) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                activeSocket.receive(packet)
                val frame = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                val sender = requireJsonStringOrNull(frame, "peer_id") ?: packet.address.hostAddress.orEmpty()
                val envelope = requireJsonStringOrNull(frame, "envelope") ?: frame
                if (sender != localPeerId && envelope.startsWith("KNET1:") && markTransportSeen(sender, envelope)) {
                    seenPeers[sender] = System.currentTimeMillis()
                    inbox += "from=$sender\n$envelope"
                    lastEvent = "Received UDP ${packet.length} bytes from $sender"
                }
            } catch (error: SocketException) {
                if (running) lastEvent = "UDP socket error: ${error.message.orEmpty()}"
            } catch (error: Throwable) {
                lastEvent = "UDP receive error: ${error.message.orEmpty()}"
            }
        }
    }

    private fun stopInternal() {
        running = false
        socket?.close()
        tcpServerSocket?.close()
        socket = null
        tcpServerSocket = null
        multicastLock?.release()
        multicastLock = null
        stopNsd()
        receiveThread = null
        tcpReceiveThread = null
        seenPeers.clear()
        tcpPeers.clear()
        seenEnvelopeIds.clear()
    }

    private fun startTcpFallback() {
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(PORT))
        }
        tcpServerSocket = server
        tcpReceiveThread = thread(name = "kampungnet-tcp-receiver", isDaemon = true) {
            while (running) {
                try {
                    server.accept().use { socket -> receiveTcp(socket) }
                } catch (error: SocketException) {
                    if (running) lastEvent = "TCP socket error: ${error.message.orEmpty()}"
                } catch (error: Throwable) {
                    lastEvent = "TCP receive error: ${error.message.orEmpty()}"
                }
            }
        }
        startNsd()
    }

    private fun sendTcp(envelope: String, address: InetSocketAddress) {
        try {
            Socket().use { socket ->
                socket.connect(address, 2_000)
                socket.getOutputStream().write((udpFrame(localPeerId, envelope) + "\n").encodeToByteArray())
                socket.getOutputStream().flush()
            }
        } catch (error: Throwable) {
            lastEvent = "TCP send failed to ${address.hostString}: ${error.message.orEmpty()}"
        }
    }

    private fun receiveTcp(socket: Socket) {
        val frame = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).readLine().orEmpty()
        val sender = requireJsonStringOrNull(frame, "peer_id") ?: socket.inetAddress.hostAddress.orEmpty()
        val envelope = requireJsonStringOrNull(frame, "envelope") ?: frame
        if (sender != localPeerId && envelope.startsWith("KNET1:") && markTransportSeen(sender, envelope)) {
            tcpPeers[sender] = InetSocketAddress(socket.inetAddress, PORT)
            inbox += "from=$sender\n$envelope"
            lastEvent = "Received TCP ${frame.length} chars from $sender"
        }
    }

    private fun startNsd() {
        val manager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsdManager = manager
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                lastEvent = "mDNS registered ${serviceInfo.serviceName}"
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                lastEvent = "mDNS registration failed: $errorCode"
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = registration
        manager.registerService(
            NsdServiceInfo().apply {
                serviceName = localPeerId
                serviceType = SERVICE_TYPE
                port = PORT
            },
            NsdManager.PROTOCOL_DNS_SD,
            registration,
        )

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                lastEvent = "mDNS discovery failed: $errorCode"
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                tcpPeers.remove(serviceInfo.serviceName)
                lastEvent = "Lost TCP peer ${serviceInfo.serviceName}"
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName == localPeerId || serviceInfo.serviceType != SERVICE_TYPE) return
                if (resolvingNsd) return
                resolvingNsd = true
                manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        resolvingNsd = false
                        lastEvent = "mDNS resolve failed: $errorCode"
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        resolvingNsd = false
                        val host = serviceInfo.host ?: return
                        tcpPeers[serviceInfo.serviceName] = InetSocketAddress(host, serviceInfo.port)
                        lastEvent = "Resolved TCP peer ${serviceInfo.serviceName}"
                    }
                })
            }
        }
        discoveryListener = discovery
        manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
    }

    private fun stopNsd() {
        val manager = nsdManager
        registrationListener?.let { runCatching { manager?.unregisterService(it) } }
        discoveryListener?.let { runCatching { manager?.stopServiceDiscovery(it) } }
        registrationListener = null
        discoveryListener = null
        nsdManager = null
        resolvingNsd = false
    }

    private fun acquireMulticastLock() {
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifiManager.createMulticastLock("kampungnet-udp").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun statusText(): String {
        prunePeers()
        val state = if (running) "active" else "stopped"
        val peers = seenPeers.keys().toList().sorted().joinToString(", ").ifBlank { "-" }
        val tcpPeerText = tcpPeers.keys().toList().sorted().joinToString(", ").ifBlank { "-" }
        return "state=$state\ntransport=UDP LAN/hotspot + mDNS/TCP\nlocal=$localPeerId\nconnected=${seenPeers.size + tcpPeers.size}\nudp_seen=${seenPeers.size}\ntcp_seen=${tcpPeers.size}\nudp_peers=$peers\ntcp_peers=$tcpPeerText\nport=$PORT\nlast=$lastEvent"
    }

    private fun prunePeers() {
        val cutoff = System.currentTimeMillis() - PEER_TTL_MILLIS
        seenPeers.entries.removeIf { it.value < cutoff }
    }

    private fun markTransportSeen(sender: String, envelope: String): Boolean {
        val now = System.currentTimeMillis()
        val cutoff = now - SEEN_TTL_MILLIS
        seenEnvelopeIds.entries.removeIf { it.value < cutoff }
        val id = "$sender|${stableEnvelopeIdUdp(envelope)}"
        return seenEnvelopeIds.putIfAbsent(id, now) == null
    }

    private fun wrap(action: () -> String): MeshBridgeResult = try {
        MeshBridgeResult(ok = true, value = action())
    } catch (throwable: Throwable) {
        MeshBridgeResult(ok = false, error = throwable.message ?: throwable::class.java.simpleName)
    }

    private companion object {
        const val PORT = 47777
        const val BROADCAST_ADDRESS = "255.255.255.255"
        const val SERVICE_TYPE = "_kampungnet._tcp."
        const val MAX_PACKET_BYTES = 60_000
        const val PEER_TTL_MILLIS = 60_000L
        const val SEEN_TTL_MILLIS = 300_000L
    }
}

private fun udpFrame(peerId: String, envelope: String): String = "{" +
    "\"peer_id\":\"${jsonEscapeUdp(peerId)}\"," +
    "\"envelope\":\"${jsonEscapeUdp(envelope)}\"" +
    "}"

private fun requireJsonStringOrNull(json: String, key: String): String? =
    Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"")
        .find(json)
        ?.groupValues
        ?.get(1)
        ?.jsonUnescapeUdp()

private fun jsonEscapeUdp(value: String): String = buildString {
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

private fun String.jsonUnescapeUdp(): String = buildString {
    var index = 0
    while (index < this@jsonUnescapeUdp.length) {
        val char = this@jsonUnescapeUdp[index]
        if (char == '\\' && index + 1 < this@jsonUnescapeUdp.length) {
            when (val next = this@jsonUnescapeUdp[index + 1]) {
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

private fun stableEnvelopeIdUdp(value: String): String {
    var hash = 0xcbf29ce484222325UL
    value.encodeToByteArray().forEach { byte ->
        hash = hash xor byte.toUByte().toULong()
        hash *= 0x100000001b3UL
    }
    return hash.toString(16).padStart(16, '0')
}
