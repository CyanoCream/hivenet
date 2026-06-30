package id.hivenet.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import id.hivenet.shared.chat.EncryptedChatRepository
import id.hivenet.shared.db.createHiveNetDatabase
import id.hivenet.shared.identity.LocalIdentityRepository

class MainActivity : ComponentActivity() {

    private lateinit var meshBridge: AndroidUdpMeshBridge
    private lateinit var wifiDirectBridge: AndroidWifiDirectBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val database = createHiveNetDatabase(applicationContext)
        val chatRepository = EncryptedChatRepository(database)
        val identityRepository = LocalIdentityRepository(database)
        val cryptoBridge = AndroidCryptoBridge(applicationContext)
        val qrScannerBridge = AndroidQrScannerBridge(this)
        meshBridge = AndroidUdpMeshBridge(applicationContext)

        wifiDirectBridge = AndroidWifiDirectBridge(
            context = applicationContext,
            onGroupReady = { broadcastIp -> meshBridge.setP2pBroadcastAddress(broadcastIp) },
            onGroupLost = { meshBridge.setP2pBroadcastAddress(null) },
        )

        requestWifiDirectPermissions()

        setContent {
            KampungNetApp(
                cryptoBridge = cryptoBridge,
                meshBridge = meshBridge,
                qrScannerBridge = qrScannerBridge,
                chatRepository = chatRepository,
                identityRepository = identityRepository,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(wifiDirectBridge.receiver, wifiDirectBridge.intentFilter)
        wifiDirectBridge.start()
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(wifiDirectBridge.receiver) }
        wifiDirectBridge.stop()
    }

    private fun requestWifiDirectPermissions() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_WIFI_DIRECT)
        }
    }

    companion object {
        private const val REQ_WIFI_DIRECT = 1001
    }
}
