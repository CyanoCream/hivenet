package id.hivenet.app

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import id.hivenet.shared.chat.EncryptedChatRepository
import id.hivenet.shared.db.createHiveNetDatabase
import id.hivenet.shared.identity.LocalIdentityRepository

fun MainViewController(cryptoBridge: CryptoBridge? = null, meshBridge: MeshBridge? = null, qrScannerBridge: QrScannerBridge? = null) = ComposeUIViewController(
    configure = { enforceStrictPlistSanityCheck = false },
) {
    val repositories = remember {
        val database = createHiveNetDatabase()
        EncryptedChatRepository(database) to LocalIdentityRepository(database)
    }
    KampungNetApp(
        cryptoBridge = cryptoBridge,
        meshBridge = meshBridge,
        qrScannerBridge = qrScannerBridge,
        notificationBridge = remember { IosNotificationBridge() },
        chatRepository = repositories.first,
        identityRepository = repositories.second,
    )
}
