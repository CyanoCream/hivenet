package id.hivenet.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import kotlin.concurrent.thread

class AndroidWifiDirectBridge(
    context: Context,
    private val onGroupReady: (broadcastIp: String) -> Unit,
    private val onGroupLost: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel = manager.initialize(appContext, Looper.getMainLooper(), null)

    @Volatile private var running = false
    @Volatile private var inGroup = false

    val intentFilter: IntentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        inGroup = false
                        onGroupLost()
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (running && !inGroup) connectToBestPeer()
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (networkInfo?.isConnected == true) {
                        requestGroupInfo()
                    } else {
                        if (inGroup) { inGroup = false; onGroupLost() }
                        if (running) retryDiscovery(delayMs = 30_000)
                    }
                }
            }
        }
    }

    fun start() {
        running = true
        startDiscovery()
    }

    fun stop() {
        running = false
        inGroup = false
        runCatching { manager.stopPeerDiscovery(channel, null) }
        runCatching { manager.removeGroup(channel, null) }
        onGroupLost()
    }

    private fun startDiscovery() {
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit
            override fun onFailure(reason: Int) {
                if (running) retryDiscovery(delayMs = 30_000)
            }
        })
    }

    private fun retryDiscovery(delayMs: Long) {
        thread(isDaemon = true, name = "hivenet-p2p-retry") {
            Thread.sleep(delayMs)
            if (running && !inGroup) startDiscovery()
        }
    }

    private fun connectToBestPeer() {
        manager.requestPeers(channel) { peerList ->
            val peer = peerList.deviceList.firstOrNull() ?: return@requestPeers
            val config = WifiP2pConfig().apply { deviceAddress = peer.deviceAddress }
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = Unit
                override fun onFailure(reason: Int) = Unit
            })
        }
    }

    private fun requestGroupInfo() {
        manager.requestGroupInfo(channel) { group ->
            if (group == null) { retryDiscovery(delayMs = 5_000); return@requestGroupInfo }
            inGroup = true
            // Android P2P group owner is always 192.168.49.1/24
            onGroupReady(P2P_BROADCAST)
        }
    }

    private companion object {
        const val P2P_BROADCAST = "192.168.49.255"
    }
}
