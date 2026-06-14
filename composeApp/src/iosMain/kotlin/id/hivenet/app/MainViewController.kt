package id.hivenet.app

import androidx.compose.ui.window.ComposeUIViewController

fun MainViewController(cryptoBridge: CryptoBridge? = null, meshBridge: MeshBridge? = null, qrScannerBridge: QrScannerBridge? = null) = ComposeUIViewController(
    configure = { enforceStrictPlistSanityCheck = false },
) { KampungNetApp(cryptoBridge = cryptoBridge, meshBridge = meshBridge, qrScannerBridge = qrScannerBridge) }
