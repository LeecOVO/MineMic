package com.aiaixyz.minemic

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat

class MicForegroundService : Service() {
    companion object { var disconnect: (() -> Unit)? = null }
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("microphone", "MineMic 麦克风", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, MicForegroundService::class.java).setAction("disconnect"), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "microphone").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("MineMic 正在使用麦克风").setContentText("点击返回应用，或断开与 PC 的连接")
            .setContentIntent(open).setOngoing(true).addAction(0, "断开", stop).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "disconnect") { disconnect?.invoke(); stopSelf() }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?) = null
}
