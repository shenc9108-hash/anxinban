package com.anxinban.kid

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.*
import android.os.*

/**
 * 前台服务：每 30 秒上报心跳（电量、用时、位置）并拉取规则；同时持续获取位置。
 *
 * 保活增强（针对国产 ROM / Doze）：
 *  1. PARTIAL_WAKE_LOCK —— 让 CPU 在息屏后仍能按时醒来看点，避免深度睡眠挂起线程
 *  2. AlarmManager.setExactAndAllowWhileIdle —— Doze 下也能被系统唤醒，兜底重启服务
 *  3. 前台通知 ongoing，降低被系统/用户清掉的概率
 */
class SyncService : Service() {
    @Volatile private var run = false
    private var listening = false
    private var wakeLock: PowerManager.WakeLock? = null

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
        val n = Notification.Builder(this, "guard")
            .setContentTitle("家长守护已开启")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)                 // 常驻，不可被左右滑掉
            .build()
        val hasLoc = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (hasLoc) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(1, n, type)
        } else startForeground(1, n)

        acquireWakeLock()
        scheduleAlarm()                       // 兜底：即使被杀也会被唤醒重启

        if (hasLoc && !listening) startLocation()
        // 被家长解绑时自我清理（取消设备管理器、清数据、停服务）
        Api.onUnbound = {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                UnbindHandler.handle(this)
            }
        }
        // 后台每隔 6 小时静默检查一次更新（有新版会在下次打开界面时提示）
        try { Updater.check(this) } catch (_: Exception) {}
        if (!run) {
            run = true
            Thread {
                while (run) {
                    try {
                        // 每次循环确保 wakelock 仍在，防止被系统释放
                        if (wakeLock?.isHeld != true) acquireWakeLock()
                        Api.sync(this)
                    } catch (_: Throwable) {
                        // 单次失败不能让线程退出
                    }
                    try { Thread.sleep(30_000) } catch (_: InterruptedException) { }
                }
            }.start()
        }
        return START_STICKY
    }

    /** 持有不超时的 partial wakelock，保证息屏后线程仍能被调度。 */
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "anxinban:sync")
                wakeLock?.setReferenceCounted(false)
            }
            if (wakeLock?.isHeld != true) wakeLock?.acquire()
        } catch (_: Throwable) {}
    }

    /** 用 AlarmManager 每 15 分钟唤醒一次自己，Doze 下也生效。 */
    private fun scheduleAlarm() {
        try {
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                this, 1001, Intent(this, AlarmReceiver::class.java).setAction(ACTION_ALARM),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val at = System.currentTimeMillis() + 15 * 60_000L
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (_: Throwable) {}
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

    override fun onDestroy() {
        run = false
        try { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(listener) } catch (_: Throwable) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Throwable) {}
        wakeLock = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户从最近任务里划掉时，重新拉起自己
        scheduleAlarm()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(i: Intent?): IBinder? = null
}

const val ACTION_ALARM = "com.anxinban.kid.ALARM_WAKE"

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (Store.token(c) != null) c.startForegroundService(Intent(c, SyncService::class.java))
    }
}

/** 定时唤醒：Doze 下被系统拉起后重新启动前台服务，并安排下一次唤醒。 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (Store.token(c) == null) return
        try { c.startForegroundService(Intent(c, SyncService::class.java)) } catch (_: Throwable) {}
    }
}
