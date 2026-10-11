package com.example.minimaluvc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 仅在存在 USB 会话时保持进程为前台服务，防止 Home 后鸿蒙回收连接。
 * USB / GPU 仍由控制器串行管理；不录制、不保存任何媒体。
 * START_NOT_STICKY：系统杀进程后不在后台自行重开相机。
 */
class PreviewConnectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "采集卡连接", NotificationManager.IMPORTANCE_LOW).apply {
                description = "保持采集卡连接，返回应用继续预览"
                setShowBadge(false)
            })
        }
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder.setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("采集卡保持连接")
            .setContentText("返回应用继续预览；退出应用后断开连接")
            .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        else if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIFICATION, notification)
        UvcDiagnostics.event("connection foreground service started")
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        PreviewRuntime.closeCurrent()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        UvcDiagnostics.event("connection foreground service stopped")
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "uvc_connection"
        private const val NOTIFICATION = 1
        fun update(context: Context, held: Boolean) {
            val intent = Intent(context, PreviewConnectionService::class.java)
            if (!held) context.stopService(intent)
            else if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
