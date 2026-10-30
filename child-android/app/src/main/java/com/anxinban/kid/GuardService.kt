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

/** 核心：识别前台应用 -> 累计用时 -> 按规则拦截。 */
class GuardService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private var cur: String? = null
    private var last = 0L
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

    override fun onServiceConnected() { Api.init(this); last = SystemClock.elapsedRealtime(); h.post(tick) }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg in skip) { account(); cur = null; return }
        account(); cur = pkg; check(pkg)
    }

    /** 把上次到现在的时间记到当前应用上（息屏不计时）。 */
    private fun account() {
        val now = SystemClock.elapsedRealtime()
        val on = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        cur?.let { if (on && now - last in 0..120_000) Store.addMs(this, it, now - last) }
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
        val lp = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.OPAQUE)
        wm.addView(box, lp); overlay = box
    }

    override fun onInterrupt() {}
}
