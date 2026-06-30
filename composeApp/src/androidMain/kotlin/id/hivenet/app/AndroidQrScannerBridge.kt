package id.hivenet.app

import androidx.activity.ComponentActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class AndroidQrScannerBridge(activity: ComponentActivity) : QrScannerBridge {
    private var pendingResult: ((CryptoBridgeResult) -> Unit)? = null

    private val launcher = activity.registerForActivityResult(ScanContract()) { result ->
        val callback = pendingResult
        pendingResult = null
        val token = result.contents
        callback?.invoke(
            if (token.isNullOrBlank()) CryptoBridgeResult(ok = false, error = "QR scan cancelled")
            else CryptoBridgeResult(ok = true, value = token)
        )
    }

    override fun scanPairingToken(onResult: (CryptoBridgeResult) -> Unit) {
        if (pendingResult != null) {
            onResult(CryptoBridgeResult(ok = false, error = "QR scan already running"))
            return
        }
        pendingResult = onResult
        launcher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Arahkan kamera ke QR kontak")
                .setBeepEnabled(false)
                .setCaptureActivity(PortraitQrCaptureActivity::class.java)
                .setOrientationLocked(true)
        )
    }
}
