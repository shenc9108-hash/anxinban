package com.anxinban.kid

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.*
import android.os.*

/** 前台服务：每 30 秒上报心跳（电量、用时、位置）并拉取规则；同时持续获取位置。 */
class SyncService : Service() {
    @Volatile private var run = false
    private var listening = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            Store.saveLoc(this@SyncService, l.latitude, l.longitude, l.accuracy)
            Store.addTrack(this@SyncService, l.latitude, l.longitude, l.accuracy)
        }
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        Api.init(this)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("guard", "守护运行中", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "guard").setContentTitle("家长守护已开启").setSmallIcon(android.R.drawable.ic_lock_idle_lock).build()
        val hasLoc = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (hasLoc) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(1, n, type)
        } else startForeground(1, n)
        if (hasLoc && !listening) startLocation()
        // 被家长解绑时自我清理（取消设备管理器、清数据、停服务）
        Api.onUnbound = {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                UnbindHandler.handle(this)
            }
        }
        // 后台每隔 6 小时静默检查一次更新（有新版会在下次打开界面时提示）
        try { Updater.check(this) } catch (_: Exception) {}
        if (!run) { run = true; Thread { while (run) { Api.sync(this); Thread.sleep(30_000) } }.start() }
        return START_STICKY
    }

    private fun startLocation() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER))
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 30_000L, 10f, listener, Looper.getMainLooper())
                    lm.getLastKnownLocation(p)?.let {
                        Store.saveLoc(this, it.latitude, it.longitude, it.accuracy)
                        Store.addTrack(this, it.latitude, it.longitude, it.accuracy)
                    }
                }
            listening = true
        } catch (e: SecurityException) {}
    }

    override fun onDestroy() { run = false; (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(listener) }
    override fun onBind(i: Intent?): IBinder? = null
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (Store.token(c) != null) c.startForegroundService(Intent(c, SyncService::class.java))
    }
}
