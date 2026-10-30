package com.anxinban.kid

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新：
 *  1. 从服务器 /api/version 获取最新版本信息
 *  2. 版本号高于本地时弹窗提示
 *  3. 用户确认后下载 APK 并调起系统安装器
 */
object Updater {

    private const val PREFS = "guard"
    private const val KEY_LAST_CHECK = "update_last_check"
    private const val CHECK_INTERVAL = 6 * 60 * 60 * 1000L

    private fun ui(r: Runnable) = Handler(Looper.getMainLooper()).post(r)

    private fun toast(c: Context, m: String) = ui { Toast.makeText(c, m, Toast.LENGTH_LONG).show() }

    private fun localCode(c: Context): Int = try {
        c.packageManager.getPackageInfo(c.packageName, 0).let {
            if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt()
            else @Suppress("DEPRECATION") it.versionCode
        }
    } catch (e: Exception) { 0 }

    private fun localName(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    /**
     * 检查更新。
     * @param silent true=静默（失败不提示，仅成功弹窗）；false=用户主动点击，失败会提示原因
     */
    fun check(c: Context, force: Boolean = false, silent: Boolean = true) {
        val app = c.applicationContext
        val sp = app.getSharedPreferences(PREFS, 0)
        val last = sp.getLong(KEY_LAST_CHECK, 0L)
        if (!force && System.currentTimeMillis() - last < CHECK_INTERVAL) return
        sp.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

        Thread {
            var conn: HttpURLConnection? = null
            try {
                val url = Api.base + "/version"
                conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                if (code !in 200..299) {
                    if (!silent) toast(app, "检查失败：服务器返回 $code\n$url")
                    return@Thread
                }
                val text = conn.inputStream.bufferedReader().readText()
                val o = JSONObject(text)
                val remoteCode = o.optInt("versionCode", 0)
                val remoteName = o.optString("versionName", "?")
                val apkUrl = o.optString("apkUrl", "")
                val notes = o.optString("notes", "")

                val mine = localCode(app)

                if (remoteCode <= mine) {
                    if (!silent) toast(app, "已是最新版本 v${localName(app)}")
                    return@Thread
                }
                if (apkUrl.isEmpty()) {
                    toast(app, "发现新版本 $remoteName，但服务器没有下载地址")
                    return@Thread
                }
                ui { showDialog(app, remoteName, remoteCode, apkUrl, notes) }

            } catch (e: Exception) {
                if (!silent) {
                    toast(app, "检查失败：${e.javaClass.simpleName}: ${e.message}\n地址：${Api.base}")
                }
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }.start()
    }

    private fun showDialog(c: Context, versionName: String, versionCode: Int, apkUrl: String, notes: String) {
        try {
            val msg = buildString {
                append("新版本 "); append(versionName)
                append("（当前 "); append(localName(c)); append("）")
                if (notes.isNotBlank()) { append("\n\n"); append(notes) }
                append("\n\n更新不会丢失绑定和权限设置。")
            }
            AlertDialog.Builder(c)
                .setTitle("发现新版本")
                .setMessage(msg)
                .setPositiveButton("立即更新") { _, _ -> download(c, apkUrl, versionName) }
                .setNegativeButton("稍后", null)
                .setCancelable(true)
                .show()
        } catch (e: Exception) {
            toast(c, "无法显示更新弹窗：${e.message}")
        }
    }

    private fun download(c: Context, url: String, versionName: String) {
        toast(c, "开始下载 $versionName …")

        Thread {
            try {
                val dir = File(c.getExternalFilesDir(null), "update").apply { mkdirs() }
                val out = File(dir, "anxinban-$versionName.apk")
                if (out.exists()) out.delete()

                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.instanceFollowRedirects = true
                val code = conn.responseCode
                if (code !in 200..299) {
                    toast(c, "下载失败：HTTP $code")
                    return@Thread
                }

                var total = 0L
                val expected = conn.contentLengthLong
                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var n: Int
                        var lastPct = -10
                        while (input.read(buf).also { n = it } > 0) {
                            output.write(buf, 0, n)
                            total += n
                            if (expected > 0) {
                                val pct = ((total * 100) / expected).toInt()
                                if (pct - lastPct >= 25) {
                                    lastPct = pct
                                    toast(c, "下载中 $pct%")
                                }
                            }
                        }
                    }
                }

                val sz = out.length()
                if (sz < 100_000) {
                    toast(c, "下载的安装包不完整（$sz 字节）")
                    return@Thread
                }

                toast(c, "下载完成，准备安装…")
                ui { install(c, out) }
            } catch (e: Exception) {
                toast(c, "下载失败：${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    private fun install(c: Context, apk: File) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !c.packageManager.canRequestPackageInstalls()) {
                AlertDialog.Builder(c)
                    .setTitle("需要安装权限")
                    .setMessage("请允许「安心伴孩子端」安装应用，然后重新点更新。\n\n路径：设置 → 应用 → 安心伴孩子端 → 安装未知应用 → 允许")
                    .setPositiveButton("去设置") { _, _ ->
                        try {
                            c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + c.packageName))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) {}
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return
            }

            val uri = androidx.core.content.FileProvider.getUriForFile(
                c, c.packageName + ".update", apk)
            c.startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast(c, "无法启动安装：${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
