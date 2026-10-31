package com.anxinban.kid

import android.content.Context
import android.os.BatteryManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 与你自己的后端通信。接口约定见 README。务必在后台线程调用。 */
object Api {
    /**
     * 强制清空 JVM 的 DNS 缓存。
     *
     * 这是「关掉代理后仍然离线、必须手动点上报」的最后一层兜底。
     * 代理/VPN 切换时，路由器和系统给域名解析出的地址会变，
     * 但 JVM 会把旧结果缓存住（成功几分钟、失败甚至更久），
     * 于是后台一直往已经不通的地址发请求，重试多少次都没用。
     * 手动点「立即上报」之所以能好，是因为那次请求恰好走了新的解析路径。
     */
    fun resetNetworkState() {
        try {
            val cl = Class.forName("java.net.InetAddress")
            // 清 fqdn 缓存表（不同 JDK 字段名不同，逐一尝试）
            for (name in listOf("addressCache", "cachedLookup", "lookupTable")) {
                try {
                    val fld = cl.getDeclaredField(name)
                    fld.isAccessible = true
                    val cache = fld.get(null)
                    if (cache != null) {
                        val clr = cache.javaClass.getDeclaredMethod("clear")
                        clr.isAccessible = true
                        clr.invoke(cache)
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        try {
            // 允许重新解析，配合 call() 里的 no-cache
            java.security.Security.setProperty("networkaddress.cache.ttl", "0")
            java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0")
        } catch (_: Throwable) {}
    }


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

    private fun call(method: String, path: String, body: JSONObject?, token: String?): JSONObject? {
        // 曾经的坑：代理/VPN 切换、WiFi 换移动数据之后，后台每 30 秒都在重试，
        // 但一直失败，必须手动点「立即上报」才能恢复。原因是 HttpURLConnection
        // 默认复用 keep-alive 长连接，旧连接已随网络切换失效却仍被拿来用；
        // 同时 JVM 会缓存 DNS 结果，代理改了解析也刷不掉。
        // 这里强制每次关闭连接、禁用缓存，并在 finally 主动 disconnect。
        var c: HttpURLConnection? = null
        return try {
            c = URL(base + path).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 20_000
            c.useCaches = false
            c.setRequestProperty("Connection", "close")
            c.setRequestProperty("Cache-Control", "no-cache")
            token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
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
        } catch (e: Exception) {
            null
        } finally {
            // 主动断开，别把坏连接留在池子里
            try { c?.disconnect() } catch (_: Throwable) {}
        }
    }

    fun bind(code: String, model: String) =
        call("POST", "/bind", JSONObject().put("code", code).put("model", model), null)

    fun sync(c: Context): Boolean {
        val t = Store.token(c) ?: return false
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
        try {
            ok = call("POST", "/heartbeat", hb, t)
            Diag.record(c, "heartbeat", ok != null,
                if (ok != null) "" else "网络请求未返回 200（超时或被拒绝）")
        } catch (e: Throwable) {
            Diag.record(c, "heartbeat", false, e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
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
        try {
            val r = call("GET", "/rules", null, t)
            if (r != null) { Store.saveRules(c, r.toString()); Diag.record(c, "rules", true, "") }
            else Diag.record(c, "rules", false, "规则拉取未返回 200")
        } catch (e: Throwable) {
            Diag.record(c, "rules", false, e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
        return ok != null
    }

    fun askMore(c: Context, pkg: String) {
        call("POST", "/requests", JSONObject().put("pkg", pkg).put("minutes", 15), Store.token(c))
    }
}
