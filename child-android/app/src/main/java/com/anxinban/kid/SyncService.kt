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

    // 心跳线程的等待锁：网络恢复时用它把线程立刻叫醒，不用干等到下一次 30 秒
    private val lock = Object()
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * 监听网络变化。代理开关、WiFi 与移动数据互切时立刻触发一次上报。
     * 这是「离线后必须手动点上报才能恢复」的关键修复之一：
     * 以前网络恢复了 App 完全不知道，只能等下一个 30 秒周期，
     * 而那个周期往往又在用失效的连接。
     */
    private fun registerNetWatch() {
        if (netCallback != null) return
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { wakeUp() }
                override fun onLost(network: android.net.Network) { wakeUp() }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (_: Throwable) {
            // 没有权限或系统太旧就放弃，不影响主流程
        }
    }

    private fun unregisterNetWatch() {
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            netCallback?.let { cm.unregisterNetworkCallback(it) }
        } catch (_: Throwable) {}
        netCallback = null
    }

    /** 唤醒心跳线程，立即重试一次。 */
    private fun wakeUp() {
        try { synchronized(lock) { lock.notifyAll() } } catch (_: Throwable) {}
    }

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
            try { startForeground(1, n, type) } catch (_: Throwable) { startForeground(1, n) }
        } else try { startForeground(1, n) } catch (_: Throwable) {}

        // 先启动心跳线程：这是最关键的功能，必须最先保证跑起来。
        // 后面的唤醒锁/闹钟/定位即使失败，也不能影响上报。
        if (!run) {
            run = true
            registerNetWatch()
            Thread {
                var backoff = 0          // 连续失败次数，用于退避
                while (run) {
                    var ok = false
                    try {
                        // 每次循环确保 wakelock 仍在，防止被系统释放
                        if (wakeLock?.isHeld != true) acquireWakeLock()
                        ok = Api.sync(this)
                    } catch (_: Throwable) {
                        // 单次失败不能让线程退出
                    }
                    if (ok) {
                        backoff = 0
                        // 成功：正常 30 秒一轮，但可以被网络变化提前唤醒
                        synchronized(lock) { try { lock.wait(30_000L) } catch (_: InterruptedException) {} }
                    } else {
                        // 失败：指数退避 30s -> 60s -> 120s，最多 3 分钟。
                        // 曾经固定 30 秒死磕，代理切换后网络栈一直是坏的，
                        // 加退避能减少无谓重试，同时配合网络回调立刻恢复。
                        backoff = (backoff + 1).coerceAtMost(3)
                        val delay = 30_000L * (1L shl (backoff - 1))
                        synchronized(lock) { try { lock.wait(delay) } catch (_: InterruptedException) {} }
                    }
                }
            }.start()
        }
        // 以下是保活与附加功能，任何一步失败都不应让服务崩溃
        try { acquireWakeLock() } catch (_: Throwable) {}
        try { scheduleAlarm() } catch (_: Throwable) {}
        try { if (hasLoc && !listening) startLocation() } catch (_: Throwable) {}
        // 被家长解绑时自我清理（取消设备管理器、清数据、停服务）
        try {
            Api.onUnbound = {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    UnbindHandler.handle(this)
                }
            }
        } catch (_: Throwable) {}
        // 后台静默检查一次更新（有新版会在下次打开界面时提示）
        try { Updater.check(this) } catch (_: Throwable) {}
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
            // 必须带超时：无超时的 wakelock 在 onDestroy 未执行时会永远挂着，
            // 系统判定异常耗电会直接强杀应用。心跳每 30 秒续期一次。
            if (wakeLock?.isHeld != true) wakeLock?.acquire(10 * 60_000L)
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
        try { unregisterNetWatch() } catch (_: Throwable) {}
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

/**
 * 开机、以及「应用被覆盖安装完成」时重启守护服务。
 *
 * ACTION_MY_PACKAGE_REPLACED 很关键：升级 APK 后系统会杀掉本应用的所有进程和服务，
 * 若没人监听该广播，服务就再也不会自己起来，只能等用户手动打开 App——
 * 表现就是「升级完就离线」。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (Store.token(c) == null) return
        try {
            c.startForegroundService(Intent(c, SyncService::class.java))
        } catch (_: Throwable) {}
    }
}

/** 定时唤醒：Doze 下被系统拉起后重新启动前台服务，并安排下一次唤醒。 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (Store.token(c) == null) return
        try { c.startForegroundService(Intent(c, SyncService::class.java)) } catch (_: Throwable) {}
    }
}
