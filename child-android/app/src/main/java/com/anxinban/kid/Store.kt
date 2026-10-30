package com.anxinban.kid

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/** 家长下发的管控规则，字段与家长端原型一致。 */
data class Rules(
    val locked: Boolean = false, val timed: Boolean = true,
    val sleepStart: Int = 21, val sleepEnd: Int = 7, val daily: Int = 60,
    val limits: Map<String, Int> = emptyMap(),   // 包名 -> 每日限时（分钟）
    val whitelist: Set<String> = emptySet()      // 学习类白名单，始终可用
) {
    /** 返回拦截原因；返回 null 表示允许使用。 */
    fun blockReason(c: Context, pkg: String): String? {
        if (pkg in whitelist) return null
        if (locked) return "家长已临时锁定手机"
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (timed && (h >= sleepStart || h < sleepEnd)) return "睡眠时段自动锁定"
        val lim = limits[pkg] ?: return null
        if (Store.usedMs(c, pkg) / 60000 >= lim) return "这个应用今天的时间用完了"
        if (limits.keys.sumOf { Store.usedMs(c, it) } / 60000 >= daily) return "今日娱乐总时长已用完"
        return null
    }

    companion object {
        fun parse(s: String): Rules {
            val o = JSONObject(s)
            val l = mutableMapOf<String, Int>()
            o.optJSONObject("limits")?.let { j -> j.keys().forEach { k -> l[k] = j.getInt(k) } }
            val w = mutableSetOf<String>()
            o.optJSONArray("whitelist")?.let { a -> for (i in 0 until a.length()) w += a.getString(i) }
            return Rules(o.optBoolean("locked"), o.optBoolean("timed", true), o.optInt("sleepStart", 21),
                o.optInt("sleepEnd", 7), o.optInt("daily", 60), l, w)
        }
    }
}

object Store {
    fun server(c: Context): String? = c.getSharedPreferences("guard", 0).getString("server", null)
    fun saveServer(c: Context, s: String) = c.getSharedPreferences("guard", 0).edit().putString("server", s).apply()
    fun saveLoc(c: Context, lat: Double, lng: Double, acc: Float) =
        c.getSharedPreferences("guard", 0).edit().putString("loc", "$lat,$lng,$acc").apply()
    fun loc(c: Context): Triple<Double, Double, Float>? =
        c.getSharedPreferences("guard", 0).getString("loc", null)?.split(",")?.let { Triple(it[0].toDouble(), it[1].toDouble(), it[2].toFloat()) }
    private fun p(c: Context) = c.getSharedPreferences("guard", 0)
    private fun day() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    fun rules(c: Context) = Rules.parse(p(c).getString("rules", "{}")!!)
    fun saveRules(c: Context, json: String) = p(c).edit().putString("rules", json).apply()
    fun token(c: Context): String? = p(c).getString("token", null)
    fun saveBind(c: Context, token: String) = p(c).edit().putString("token", token).apply()
    fun usedMs(c: Context, pkg: String) = p(c).getLong("u_${day()}_$pkg", 0)
    fun addMs(c: Context, pkg: String, ms: Long) = p(c).edit().putLong("u_${day()}_$pkg", usedMs(c, pkg) + ms).apply()
}
