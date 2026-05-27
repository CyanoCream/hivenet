package id.kampungnet.app

import androidx.compose.ui.window.ComposeUIViewController

fun MainViewController(cryptoBridge: CryptoBridge? = null, meshBridge: MeshBridge? = null) = ComposeUIViewController(
    configure = { enforceStrictPlistSanityCheck = false },
) { KampungNetApp(cryptoBridge, meshBridge) }
