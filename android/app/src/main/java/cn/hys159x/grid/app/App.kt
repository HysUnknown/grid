package cn.hys159x.grid.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import cn.hys159x.grid.app.service.ServiceState
import cn.hys159x.grid.app.service.Settings
import cn.hys159x.grid.app.subscription.SubscriptionRepository

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        Settings.init(this)
        watchServiceState()
        SubscriptionRepository.get(this).start()
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "服务状态", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun watchServiceState() {
        var wasConnected = false
        ServiceState.serviceConnected.collectInBackground { connected ->
            if (wasConnected && !connected) notifyDisconnected()
            wasConnected = connected
        }
    }

    private fun notifyDisconnected() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("网格已停止")
            .setContentText("无障碍服务被系统关闭，点击重新开启（设置-无障碍-网格）")
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFY_ID, n)
    }

    private companion object {
        const val CHANNEL_STATUS = "status"
        const val NOTIFY_ID = 1
    }
}
