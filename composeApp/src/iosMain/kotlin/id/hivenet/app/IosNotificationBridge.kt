package id.hivenet.app

import platform.Foundation.NSUUID
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter

class IosNotificationBridge : NotificationBridge {
    override fun requestPermissionIfNeeded(onResult: (NotificationBridgeResult) -> Unit) {
        UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
            UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
        ) { granted, error ->
            onResult(
                when {
                    error != null -> NotificationBridgeResult(false, error = error.localizedDescription)
                    granted -> NotificationBridgeResult(true, "Notifications allowed.")
                    else -> NotificationBridgeResult(false, error = "Notification permission denied.")
                }
            )
        }
    }

    override fun showMessageNotification(threadId: String, senderName: String, preview: String, showPreview: Boolean): NotificationBridgeResult = show(
        title = senderName.ifBlank { "HiveNet message" },
        body = if (showPreview) preview.ifBlank { "Pesan baru di HiveNet" } else "Pesan baru di HiveNet",
    )

    override fun showSosNotification(senderName: String, preview: String, showPreview: Boolean): NotificationBridgeResult = show(
        title = "SOS HiveNet dari ${senderName.ifBlank { "peer" }}",
        body = if (showPreview) preview.ifBlank { "SOS masuk" } else "SOS masuk di HiveNet",
    )

    private fun show(title: String, body: String): NotificationBridgeResult = try {
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(body.take(160))
            setSound(platform.UserNotifications.UNNotificationSound.defaultSound())
        }
        val request = UNNotificationRequest.requestWithIdentifier(NSUUID().UUIDString, content, null)
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request) { _ -> }
        NotificationBridgeResult(true, "Notification scheduled.")
    } catch (error: Throwable) {
        NotificationBridgeResult(false, error = error.message ?: error::class.simpleName)
    }
}
