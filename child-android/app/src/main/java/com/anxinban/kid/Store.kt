package com.anxinban.kid

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/** 家长下发的管控规则，字段与家长端原型一致。 */
data class Rules(
    val locked: Boolean = false, val timed: Boolean = true,
    val sleepStart: Int = 21, val sleepEnd: Int = 7, val daily: Int = 60,
    // 多时段锁定：起止均为「当日第几分钟」(0..1439)，精确到分。
    // 老版本只存小时，解析时会自动乘以 60。
    val sleepWindows: List<Pair<Int, Int>> = emptyList(),
    val limits: Map<String, Int> = emptyMap(),   // 包名 -> 每日限时（分钟）
    val whitelist: Set<String> = emptySet()      // 学习类白名单，始终可用
) {
    /** 返回拦截原因；返回 null 表示允许使用。 */
    fun blockReason(c: Context, pkg: String): String? {
        if (pkg in whitelist) return null
        if (locked) return "家长已临时锁定手机"
        val cal = Calendar.getInstance()
        val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (timed && inSleepWindow(m)) return "睡眠时段自动锁定"
        val lim = limits[pkg] ?: return null
        if (Store.usedMs(c, pkg) / 60000 >= lim) return "这个应用今天的时间用完了"
        if (limits.keys.sumOf { Store.usedMs(c, it) } / 60000 >= daily) return "今日娱乐总时长已用完"
        return null
    }

    /** 命中任一锁定时段即返回 true。参数为当日第几分钟。 */
    private fun inSleepWindow(now: Int): Boolean {
        if (sleepWindows.isEmpty()) {
            // 老数据只有小时：按整点区间处理
            val h = now / 60
            return h >= sleepStart || h < sleepEnd
        }
        return sleepWindows.any { (st, en) ->
            if (st == en) false                       // 起止相同视为无效
            else if (st < en) now >= st && now < en   // 同一天内，如 12:30 -> 14:00
            else now >= st || now < en                // 跨夜，如 22:32 -> 07:15
        }
    }

    companion object {
        /** 把家长端下发的值解析成「当日第几分钟」。支持 22:32 字符串、1352、或旧的 22。 */
        private fun toMinute(v: Any?, def: Int = -1): Int = when (v) {
            is Number -> { val n = v.toInt(); if (n in 0..23) n * 60 else if (n in 0..1439) n else def }
            is String -> {
                val t = v.trim()
                when {
                    t.contains(':') -> {
                        // "22:32" 或 "22:32:00"
                        val parts = t.split(':')
                        val h = parts.getOrNull(0)?.toIntOrNull()
                        val m = parts.getOrNull(1)?.toIntOrNull()
                        if (h != null && m != null && h in 0..23 && m in 0..59) h * 60 + m else def
                    }
                    t.isNotEmpty() && t.all { it.isDigit() } -> {
                        val n = t.toInt()
                        if (n in 0..23) n * 60 else if (n in 0..1439) n else def
                    }
                    else -> def
                }
            }
            else -> def
        }

        fun parse(s: String): Rules {
            val o = JSONObject(s)
            val l = mutableMapOf<String, Int>()
            o.optJSONObject("limits")?.let { j -> j.keys().forEach { k -> l[k] = j.getInt(k) } }
            val w = mutableSetOf<String>()
            o.optJSONArray("whitelist")?.let { a -> for (i in 0 until a.length()) w += a.getString(i) }

            // 多时段：优先解析 sleepWindows；老数据没有该字段时留空，自动回退 sleepStart/sleepEnd
            val wins = mutableListOf<Pair<Int, Int>>()
            o.optJSONArray("sleepWindows")?.let { a ->
                for (i in 0 until a.length()) {
                    val x = a.optJSONObject(i) ?: continue
                    val st = toMinute(x.opt("s"))
                    val en = toMinute(x.opt("e"))
                    if (st in 0..1439 && en in 0..1439) wins += st to en
                }
            }
            return Rules(o.optBoolean("locked"), o.optBoolean("timed", true), o.optInt("sleepStart", 21),
                o.optInt("sleepEnd", 7), o.optInt("daily", 60), wins, l, w)
        }
    }
}
