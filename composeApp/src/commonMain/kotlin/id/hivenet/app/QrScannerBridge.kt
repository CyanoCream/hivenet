package id.hivenet.app

interface QrScannerBridge {
    fun scanPairingToken(onResult: (CryptoBridgeResult) -> Unit)
}
