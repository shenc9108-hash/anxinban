package com.anxinban.kid

import android.content.Context
import android.os.BatteryManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 与你自己的后端通信。接口约定见 README。务必在后台线程调用。 */
object Api {
    @Volatile var base = "https://your-server.example.com/api"

    /** 发现被家长解绑时的回调，由 Api.init 注册。 */
    var onUnbound: (() -> Unit)? = null

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
        c.requestMethod = method; c.connectTimeout = 20_000; c.readTimeout = 20_000
        token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = c.responseCode
        if (code in 200..299) {
            JSONObject(c.inputStream.bufferedReader().readText())
        } else {
            // 401 时读取响应体，判断是否被家长解绑
            if (code == 401) {
                try {
                    val txt = c.errorStream?.bufferedReader()?.readText() ?: ""
                    if (txt.contains("unbound")) onUnbound?.invoke()
                } catch (_: Exception) {}
            }
            null
        }
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
        // 附带当天使用记录时间线（分批：一次最多 100 条，
        // 原来把 500 条全塞进心跳，包体可达 50KB，弱网下 8 秒超时会导致心跳整个失败）
        val ses = Store.sessionJson(c)
        if (ses.length() > 0) {
            val part = org.json.JSONArray()
            val n = minOf(ses.length(), 40)
            for (i in 0 until n) part.put(ses.opt(i))
            hb.put("sessions", part)
        }
        // 上报防卸载状态（设备管理器是否被取消）
        hb.put("adminDisabled", !AdminReceiver.isActive(c))
        // 设备基础信息（型号/系统/存储/内存）
        try { hb.put("device", DeviceInfo.collect(c)) } catch (_: Exception) {}
        // 位置轨迹（同样分批，一次最多 100 个点）
        val trk = Store.trackJson(c)
        if (trk.length() > 0) {
            val part = org.json.JSONArray()
            val n = minOf(trk.length(), 40)
            for (i in 0 until n) part.put(trk.opt(i))
            hb.put("track", part)
        }
        // 心跳失败不能影响拉规则：之前两者串在一条链上，
        // 心跳因包体过大超时后，新规则就永远下发不下去。
        var ok: JSONObject? = null
        try { ok = call("POST", "/heartbeat", hb, t) } catch (_: Throwable) {}
        if (ok != null) {                         // 上报成功才清空，避免丢数据
            Store.clearSessions(c)
            Store.clearTrack(c)
        }

        // 家长请求截屏：已授权则截一张上传
        if (ok != null && ok.optBoolean("wantShot") && ScreenCapture.isReady()) {
            val img = ScreenCapture.capture(c)
            if (img != null) {
                call("POST", "/heartbeat", JSONObject()
                    .put("battery", hb.optInt("battery"))
                    .put("usedMinutes", JSONObject())
                    .put("ts", System.currentTimeMillis())
                    .put("shot", img), t)
            }
        }

        // 拉规则单独 try：这是远程修复的唯一通道，绝不能被上面的心跳拖垮
        try { call("GET", "/rules", null, t)?.let { Store.saveRules(c, it.toString()) } } catch (_: Throwable) {}
    }

    fun askMore(c: Context, pkg: String) {
        call("POST", "/requests", JSONObject().put("pkg", pkg).put("minutes", 15), Store.token(c))
    }
}
