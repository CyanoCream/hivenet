package id.kampungnet.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import id.kampungnet.shared.chat.EncryptedChatRepository
import id.kampungnet.shared.db.createKampungNetDatabase

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chatRepository = EncryptedChatRepository(createKampungNetDatabase(applicationContext))
        val cryptoBridge = AndroidCryptoBridge(applicationContext)
        val meshBridge = AndroidUdpMeshBridge(applicationContext)
        setContent { KampungNetApp(cryptoBridge = cryptoBridge, meshBridge = meshBridge, chatRepository = chatRepository) }
    }
}
