package com.anxinban.kid

import android.content.Context
import android.os.BatteryManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 与你自己的后端通信。接口约定见 README。务必在后台线程调用。 */
object Api {
    @Volatile var base = "https://your-server.example.com/api"

    /** 启动时读取孩子端里保存的服务器地址，换域名或 IP 不用重新打包。 */
    fun init(c: Context) { Store.server(c)?.let { base = it } }

    /** 接受 guard.example.com、https://guard.example.com、192.168.1.20:3000 等写法。 */
    fun setServer(c: Context, raw: String) {
        var s = raw.trim().trimEnd('/')
        if (s.isEmpty()) return
        if (!s.startsWith("http")) s = "https://$s"
        if (!s.endsWith("/api")) s += "/api"
        Store.saveServer(c, s); base = s
    }

    private fun call(method: String, path: String, body: JSONObject?, token: String?): JSONObject? = try {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 8000; c.readTimeout = 8000
        token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        if (c.responseCode in 200..299) JSONObject(c.inputStream.bufferedReader().readText()) else null
    } catch (e: Exception) { null }

    fun bind(code: String, model: String) =
        call("POST", "/bind", JSONObject().put("code", code).put("model", model), null)

    fun sync(c: Context) {
        val t = Store.token(c) ?: return
        val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val rules = Store.rules(c)
        val used = JSONObject()
        rules.limits.keys.forEach { used.put(it, Store.usedMs(c, it) / 60000) }
        val hb = JSONObject()
            .put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("usedMinutes", used).put("ts", System.currentTimeMillis())
        Store.loc(c)?.let { hb.put("lat", it.first).put("lng", it.second).put("acc", it.third.toDouble()) }
        // 附带当天使用记录时间线
        val ses = Store.sessionJson(c)
        if (ses.length() > 0) hb.put("sessions", ses)
        val ok = call("POST", "/heartbeat", hb, t)
        if (ok != null) Store.clearSessions(c)   // 上报成功才清空，避免丢数据
        call("GET", "/rules", null, t)?.let { Store.saveRules(c, it.toString()) }
    }

    fun askMore(c: Context, pkg: String) {
        call("POST", "/requests", JSONObject().put("pkg", pkg).put("minutes", 15), Store.token(c))
    }
}
