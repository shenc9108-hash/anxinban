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

    // ===== 使用记录时间线 =====
    // 会话记录格式：start|end|pkg|ms ，多条以 \n 分隔，只保留当天最近 300 条
    private fun sesKey() = "ses_" + day()

    fun sessions(c: Context): String = p(c).getString(sesKey(), "") ?: ""

    /** 记录一段使用会话（应用切换或息屏时调用）。 */
    fun addSession(c: Context, pkg: String, startMs: Long, endMs: Long, durMs: Long) {
        if (durMs < 5_000L) return
        val line = "$startMs|$endMs|$pkg|$durMs"
        val old = sessions(c).split("\n").filter { it.isNotBlank() }.toMutableList()
        old.add(line)
        while (old.size > 300) old.removeAt(0)
        p(c).edit().putString(sesKey(), old.joinToString("\n")).apply()
    }

    /** 取出当天会话，供心跳上报。 */
    fun sessionJson(c: Context): org.json.JSONArray {
        val arr = org.json.JSONArray()
        sessions(c).split("\n").filter { it.isNotBlank() }.forEach { ln ->
            val parts = ln.split("|")
            if (parts.size == 4) {
                arr.put(org.json.JSONObject()
                    .put("start", parts[0].toLongOrNull() ?: 0L)
                    .put("end", parts[1].toLongOrNull() ?: 0L)
                    .put("pkg", parts[2])
                    .put("ms", parts[3].toLongOrNull() ?: 0L))
            }
        }
        return arr
    }

    /** 上报成功后清掉已发送的记录，避免重复。 */
    fun clearSessions(c: Context) = p(c).edit().putString(sesKey(), "").apply()

    // ===== 防卸载状态 =====
    /** 记录设备管理器是否被取消激活（家长端可据此发现被绕过）。 */
    fun disableActive(c: Context, disabled: Boolean) {
        p(c).edit().putBoolean("admin_disabled", disabled).putLong("admin_ts", System.currentTimeMillis()).apply()
    }

    fun isAdminDisabled(c: Context): Boolean = p(c).getBoolean("admin_disabled", false)

    // ===== 位置轨迹 =====
    // 格式：ts|lat|lng|acc ，多条以 \n 分隔，只保留当天最多 500 条
    private fun trackKey() = "trk_" + day()

    fun track(c: Context): String = p(c).getString(trackKey(), "") ?: ""

    /** 位置变化时追加一个点（相距 30 米以上或超过 5 分钟才记，避免冗余）。 */
    fun addTrack(c: Context, lat: Double, lng: Double, acc: Float) {
        val now = System.currentTimeMillis()
        val list = track(c).split("\n").filter { it.isNotBlank() }.toMutableList()

        // 与上一个点比较
        if (list.isNotEmpty()) {
            val last = list.last().split("|")
            if (last.size == 4) {
                val lt = last[0].toLongOrNull() ?: 0L
                val llat = last[1].toDoubleOrNull() ?: 0.0
                val llng = last[2].toDoubleOrNull() ?: 0.0
                val dist = distance(lat, lng, llat, llng)
                if (dist < 30 && now - lt < 300_000L) return
            }
        }

        list.add("$now|$lat|$lng|$acc")
        while (list.size > 500) list.removeAt(0)
        p(c).edit().putString(trackKey(), list.joinToString("\n")).apply()
    }

    /** 取出当天轨迹点，供心跳上报。 */
    fun trackJson(c: Context): org.json.JSONArray {
        val arr = org.json.JSONArray()
        track(c).split("\n").filter { it.isNotBlank() }.forEach { ln ->
            val a = ln.split("|")
            if (a.size == 4) {
                arr.put(org.json.JSONObject()
                    .put("ts", a[0].toLongOrNull() ?: 0L)
                    .put("lat", a[1].toDoubleOrNull() ?: 0.0)
                    .put("lng", a[2].toDoubleOrNull() ?: 0.0)
                    .put("acc", a[3].toFloatOrNull() ?: 0f))
            }
        }
        return arr
    }

    /** 上报成功后清空已发送轨迹。 */
    fun clearTrack(c: Context) = p(c).edit().putString(trackKey(), "").apply()

    /** 两点间距离（米），Haversine。 */
    private fun distance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}
