package com.anxinban.kid

import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.text.SimpleDateFormat
import java.util.*

/**
 * 网络自检：记录每一次心跳/规则拉取的结果，供界面展示。
 *
 * 起因：家长端长期显示「离线」，但孩子端点「申请再用」却能送达，
 * 说明网络是通的、token 也有效，问题出在后台服务没在跑或者请求本身失败。
 * 没有日志就只能在服务端瞎猜，所以把最近一次结果留在本地给用户看。
 */
object Diag {

    private fun p(c: Context) = c.getSharedPreferences("diag", 0)

    /** 记录一次请求结果。ok=false 时 err 说明原因。 */
    fun record(c: Context, kind: String, ok: Boolean, detail: String) {
        try {
            p(c).edit()
                .putLong("${kind}_ts", System.currentTimeMillis())
                .putBoolean("${kind}_ok", ok)
                .putString("${kind}_detail", detail.take(200))
                .apply()
        } catch (_: Throwable) {}
    }

    fun lastOf(c: Context, kind: String): Triple<Long, Boolean, String>? {
        val sp = p(c)
        val ts = sp.getLong("${kind}_ts", 0L)
        if (ts == 0L) return null
        return Triple(ts, sp.getBoolean("${kind}_ok", false), sp.getString("${kind}_detail", "") ?: "")
    }

    private fun fmt(ts: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(ts))

    /** 生成一段给人看的诊断文本。 */
    fun report(c: Context): String {
        val sb = StringBuilder()

        val hb = lastOf(c, "heartbeat")
        val ru = lastOf(c, "rules")
        if (hb == null && ru == null) {
            sb.append("尚无网络记录\n（安装后还没有成功发出过请求）")
        } else {
            if (hb != null) {
                sb.append("心跳：").append(if (hb.second) "成功" else "失败")
                sb.append("  ").append(fmt(hb.first))
                if (hb.third.isNotEmpty()) sb.append("\n  原因：").append(hb.third)
                sb.append('\n')
            }
            if (ru != null) {
                sb.append("规则：").append(if (ru.second) "成功" else "失败")
                sb.append("  ").append(fmt(ru.first))
                if (ru.third.isNotEmpty()) sb.append("\n  原因：").append(ru.third)
            }
        }
        return sb.toString()
    }

    /** 服务状态：是否在跑、有没有电池优化、后台是否受限。 */
    fun serviceState(c: Context): String {
        val sb = StringBuilder()
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        sb.append("电池优化：")
        sb.append(if (pm.isIgnoringBatteryOptimizations(c.packageName)) "已忽略（好）" else "未忽略（可能被杀）")
        sb.append('\n')
        if (Build.VERSION.SDK_INT >= 23) {
            sb.append("后台受限：")
            sb.append(if (pm.isDeviceIdleMode) "系统处于 Doze 深度睡眠" else "未进入 Doze")
            sb.append('\n')
        }
        sb.append("无障碍：")
        sb.append(if (GuardService.running) "运行中" else "未运行（拦截不会生效）")
        return sb.toString()
    }
}
