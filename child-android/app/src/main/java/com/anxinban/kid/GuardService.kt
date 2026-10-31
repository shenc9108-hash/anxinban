package com.anxinban.kid

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.*
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** 核心：识别前台应用 -> 累计用时 -> 按规则拦截。 */
class GuardService : AccessibilityService() {
    companion object {
        /** 无障碍服务是否在运行：自检面板要显示 */
        @Volatile var running = false
    }

    private val h = Handler(Looper.getMainLooper())
    private var cur: String? = null
    private var last = 0L
    private var sessionStart = 0L   // 当前应用本次会话起点（墙钟时间）
    private var overlay: View? = null

    private val skip by lazy {
        setOf(packageName, "com.android.systemui",
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .let { packageManager.resolveActivity(it, 0)?.activityInfo?.packageName ?: "" },
            (getSystemService(TELECOM_SERVICE) as TelecomManager).defaultDialerPackage ?: "",
            Telephony.Sms.getDefaultSmsPackage(this) ?: "")
    }

    private val tick = object : Runnable {
        override fun run() { account(); cur?.let { check(it) }; h.postDelayed(this, 30_000) }
    }

    override fun onServiceConnected() {
        running = true
        Api.init(this); last = SystemClock.elapsedRealtime(); h.post(tick)
    }

    override fun onDestroy() { running = false; super.onDestroy() }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg in skip) { account(); cur = null; sessionStart = 0L; return }
        account(); cur = pkg; sessionStart = System.currentTimeMillis(); check(pkg)
    }

    /** 把上次到现在的时间记到当前应用上（息屏不计时）。 */
    private fun account() {
        val now = SystemClock.elapsedRealtime()
        val on = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        cur?.let {
            val dur = now - last
            if (on && dur in 0..120_000) {
                Store.addMs(this, it, dur)
                // 会话时间线：起点用墙钟时间推算，保证与家长端时区一致
                val endWall = System.currentTimeMillis()
                if (sessionStart == 0L) sessionStart = endWall - dur
                Store.addSession(this, it, sessionStart, endWall, dur)
                sessionStart = endWall
            }
        }
        last = now
    }

    private fun check(pkg: String) {
        val why = Store.rules(this).blockReason(this, pkg) ?: return
        performGlobalAction(GLOBAL_ACTION_HOME)
        showOverlay(pkg, why)
    }

    private fun showOverlay(pkg: String, why: String) {
        if (overlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        fun close() { overlay?.let { wm.removeView(it) }; overlay = null }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F2121620")); setPadding(60, 60, 60, 60)
            addView(TextView(context).apply { text = "🔒"; textSize = 56f; gravity = Gravity.CENTER })
            addView(TextView(context).apply { text = "现在不能使用这个应用\n$why"; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, 24, 0, 40) })
            addView(Button(context).apply { text = "回到桌面"; setOnClickListener { close() } })
            addView(Button(context).apply {
                text = "申请再用 15 分钟"
                setOnClickListener { Thread { Api.askMore(this@GuardService, pkg) }.start(); text = "已发送给家长"; isEnabled = false }
            })
        }
        // 关键修正：原实现是全屏 OPAQUE 且只盖在最上层，会连电源菜单一起挡住，
        // 用户拉不下通知栏、按不出关机，误锁后只能进 Recovery 清数据。
        // 现在：让出顶部状态栏高度 + 半透明，通知栏始终可下拉；长按可强制关闭。
        val lp = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT)
        // 从状态栏下沿开始铺，留出下拉通知栏的空间
        lp.gravity = Gravity.TOP or Gravity.START
        lp.y = statusBarHeight()
        wm.addView(box, lp); overlay = box

        // 长按遮罩 3 秒强制关闭遮罩：给用户留一条自救的路
        box.setOnLongClickListener {
            close()
            try { Toast.makeText(this, "已临时关闭拦截，请尽快处理", Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
            true
        }
    }

    /** 取状态栏高度，让遮罩从状态栏下沿开始，保证通知栏可下拉。 */
    private fun statusBarHeight(): Int {
        return try {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else 0
        } catch (_: Throwable) { 0 }
    }

    override fun onInterrupt() {}
}
