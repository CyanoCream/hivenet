package id.hivenet.app

interface NotificationBridge {
    fun requestPermissionIfNeeded(onResult: (NotificationBridgeResult) -> Unit)
    fun showMessageNotification(threadId: String, senderName: String, preview: String, showPreview: Boolean): NotificationBridgeResult
    fun showSosNotification(senderName: String, preview: String, showPreview: Boolean): NotificationBridgeResult
}

data class NotificationBridgeResult(
    val ok: Boolean,
    val value: String? = null,
    val error: String? = null,
)
