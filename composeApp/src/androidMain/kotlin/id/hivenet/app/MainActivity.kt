package id.hivenet.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import id.hivenet.shared.chat.EncryptedChatRepository
import id.hivenet.shared.db.createHiveNetDatabase

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val chatRepository = EncryptedChatRepository(createHiveNetDatabase(applicationContext))
        val cryptoBridge = AndroidCryptoBridge(applicationContext)
        val meshBridge = AndroidUdpMeshBridge(applicationContext)
        setContent { KampungNetApp(cryptoBridge = cryptoBridge, meshBridge = meshBridge, qrScannerBridge = null, chatRepository = chatRepository) }
    }
}
