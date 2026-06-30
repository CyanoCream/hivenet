package id.hivenet.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class AndroidNotificationBridge(private val activity: ComponentActivity) : NotificationBridge {
    private val appContext = activity.applicationContext
    private var permissionCallback: ((NotificationBridgeResult) -> Unit)? = null
    private val permissionLauncher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionCallback?.invoke(if (granted) NotificationBridgeResult(true, "Notifications allowed.") else NotificationBridgeResult(false, error = "Notification permission denied."))
        permissionCallback = null
    }

    init {
        ensureChannels(appContext)
    }

    override fun requestPermissionIfNeeded(onResult: (NotificationBridgeResult) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || hasNotificationPermission()) {
            onResult(NotificationBridgeResult(true, "Notifications allowed."))
            return
        }
        permissionCallback = onResult
        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun showMessageNotification(threadId: String, senderName: String, preview: String, showPreview: Boolean): NotificationBridgeResult = wrap {
        if (!hasNotificationPermission()) error("Notification permission not granted")
        notify(
            id = notificationId("msg:$threadId:${System.currentTimeMillis()}"),
            channelId = CHANNEL_MESSAGES,
            title = senderName.ifBlank { "HiveNet message" },
            text = if (showPreview) preview.ifBlank { "Pesan baru di HiveNet" } else "Pesan baru di HiveNet",
        )
        "Message notification shown."
    }

    override fun showSosNotification(senderName: String, preview: String, showPreview: Boolean): NotificationBridgeResult = wrap {
        if (!hasNotificationPermission()) error("Notification permission not granted")
        notify(
            id = notificationId("sos:$senderName:${System.currentTimeMillis()}"),
            channelId = CHANNEL_SOS,
            title = "SOS HiveNet dari ${senderName.ifBlank { "peer" }}",
            text = if (showPreview) preview.ifBlank { "SOS masuk" } else "SOS masuk di HiveNet",
        )
        "SOS notification shown."
    }

    private fun notify(id: Int, channelId: String, title: String, text: String) {
        val notification = builder(channelId)
            .setContentTitle(title)
            .setContentText(text.take(160))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        manager().notify(id, notification)
    }

    private fun builder(channelId: String): Notification.Builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(appContext, channelId) else Notification.Builder(appContext)

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP }
        return PendingIntent.getActivity(appContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun hasNotificationPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun manager() = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun wrap(block: () -> String): NotificationBridgeResult = try { NotificationBridgeResult(true, block()) } catch (error: Throwable) { NotificationBridgeResult(false, error = error.message ?: error::class.simpleName) }

    companion object {
        const val CHANNEL_MESH_STATUS = "mesh_status"
        private const val CHANNEL_MESSAGES = "messages"
        private const val CHANNEL_SOS = "sos_alerts"

        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL_MESH_STATUS, "HiveNet mesh status", NotificationManager.IMPORTANCE_LOW).apply { description = "Keeps offline mesh listener visible" })
            manager.createNotificationChannel(NotificationChannel(CHANNEL_MESSAGES, "HiveNet messages", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Offline messages received by this device" })
            manager.createNotificationChannel(NotificationChannel(CHANNEL_SOS, "HiveNet SOS alerts", NotificationManager.IMPORTANCE_HIGH).apply { description = "Emergency alerts received by this device" })
        }
    }
}

private fun notificationId(value: String): Int = value.hashCode() and 0x7fffffff
