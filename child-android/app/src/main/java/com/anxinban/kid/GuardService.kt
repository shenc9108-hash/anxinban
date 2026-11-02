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

        /** 超过这个静默时长就认为用户中途离开了，下一段另起一条会话。 */
        private const val MAX_GAP = 2 * 60_000L
    }

    private val h = Handler(Looper.getMainLooper())
    private var cur: String? = null
    private var last = 0L
    private var sessionStart = 0L   // 当前应用本次会话起点（墙钟时间）
    private var flushedAt = 0L      // 当前会话已经写进时间线的截止时刻（墙钟）
    private var lastEventWall = 0L  // 最近一次收到无障碍事件的墙钟时刻
    // 时间线的落盘时机只有两个：
    //   1) 真的切换了应用（或被系统界面打断）—— 把上一段收尾
    //   2) tick 发现超过 MAX_GAP 没有任何无障碍事件 —— 判定人已离开，落盘并断开
    // 同一 App 反复上报（美团这类高频刷新）只累计时长，不再写条目，
    // 否则一小时的连续使用会刷出上百条，把别的记录挤掉。
    private var overlay: View? = null

    private val skip by lazy {
        setOf(packageName, "com.android.systemui",
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .let { packageManager.resolveActivity(it, 0)?.activityInfo?.packageName ?: "" },
            (getSystemService(TELECOM_SERVICE) as TelecomManager).defaultDialerPackage ?: "",
            Telephony.Sms.getDefaultSmsPackage(this) ?: "")
    }

    private val tick = object : Runnable {
        override fun run() {
            account(); flushGap(); cur?.let { check(it) }; h.postDelayed(this, 30_000)
        }
    }

    override fun onServiceConnected() {
        running = true
        Api.init(this); last = SystemClock.elapsedRealtime(); h.post(tick)
    }

    override fun onDestroy() { running = false; super.onDestroy() }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        // 每收到一次事件就盖个时间戳：家长端用它判断无障碍是否真的在工作，
        // 而不是只看系统开关是否"已开启"（vivo 等机型会出现假开启）。
        try { Store.markGuardAlive(this) } catch (_: Throwable) {}
        lastEventWall = System.currentTimeMillis()
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg in skip) {
            // 回到桌面/系统界面：把当前这段会话收尾
            account(); flushSession(); cur = null; sessionStart = 0L
            return
        }
        if (pkg != cur) {
            // 真正切换了应用：收尾上一段，再开始新的一段
            account(); flushSession()
            cur = pkg
            sessionStart = System.currentTimeMillis()
        } else {
            // 同一个应用重复上报（美团这类高频刷新的最常见），只累计时长
            account()
        }
        check(pkg)
    }

    /** 把上次到现在的时间记到当前应用上（息屏不计时）。 */
    /** 只累计使用时长，不写时间线；时间线交给 flushSession() 负责。 */
    private fun account() {
        val now = SystemClock.elapsedRealtime()
        val on = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        cur?.let {
            val dur = now - last
            if (on && dur in 0..120_000) Store.addMs(this, it, dur)
        }
        last = now
    }

    /**
     * tick 每 30 秒调用一次，检查当前会话是否已经"断档"。
     *
     * 这里刻意不按周期落盘：同一 App 连续用一小时就应该合成一条，
     * 而不是切成 120 条把时间线刷满。只有在最近的 tick 间隔里
     * 完全没收到无障碍事件（说明人已经离开或设备熄屏）时，
     * 才把这一段落盘并在之后另起一条，避免把空闲时间也算进去。
     */
    private fun flushGap() {
        val pkg = cur ?: return
        if (sessionStart == 0L) return
        val nowWall = System.currentTimeMillis()
        // 最近 MAX_GAP 内还有事件 => 用户仍在使用，继续累积，不落盘
        if (nowWall - lastEventWall <= MAX_GAP) return

        // 已经断档：把实际有事件的那一段落盘。
        // 终点用最后一次事件的时刻，而不是现在 —— 中间的空闲不能算进使用时长。
        val from = maxOf(sessionStart, flushedAt)
        val to = lastEventWall
        if (to > from) Store.addSession(this, pkg, from, to, to - from)

        // 断档之后由下一条事件重新开始计时
        cur = null
        sessionStart = 0L
        flushedAt = 0L
    }

    /** 会话结束时整体收尾写一条。 */
    private fun flushSession() {
        val pkg = cur ?: run { sessionStart = 0L; flushedAt = 0L; return }
        if (sessionStart == 0L) { flushedAt = 0L; return }
        val nowWall = System.currentTimeMillis()
        val from = maxOf(sessionStart, flushedAt)
        val dur = nowWall - from
        if (dur >= 5_000L && dur <= MAX_GAP) {
            Store.addSession(this, pkg, from, nowWall, dur)
        }
        sessionStart = 0L
        flushedAt = 0L
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
