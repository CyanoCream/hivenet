package id.kampungnet.app

interface QrScannerBridge {
    fun scanPairingToken(onResult: (CryptoBridgeResult) -> Unit)
}
