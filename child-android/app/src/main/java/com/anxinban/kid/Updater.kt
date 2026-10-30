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
 *
 * 签名固定后为覆盖安装，DeviceAdmin / 无障碍 / 绑定数据全部保留。
 */
object Updater {

    private const val PREFS = "guard"
    private const val KEY_LAST_CHECK = "update_last_check"
    private const val CHECK_INTERVAL = 6 * 60 * 60 * 1000L   // 6 小时检查一次

    private fun localCode(c: Context): Int = try {
        c.packageManager.getPackageInfo(c.packageName, 0).let {
            if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else @Suppress("DEPRECATION") it.versionCode
        }
    } catch (e: Exception) { 0 }

    private fun localName(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    /** 静默检查，仅在发现新版本时提示。 */
    fun check(c: Context, force: Boolean = false) {
        val app = c.applicationContext
        val sp = app.getSharedPreferences(PREFS, 0)
        val last = sp.getLong(KEY_LAST_CHECK, 0L)
        if (!force && System.currentTimeMillis() - last < CHECK_INTERVAL) return
        sp.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

        Thread {
            try {
                val conn = URL(Api.base + "/version").openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                if (conn.responseCode !in 200..299) return@Thread
                val o = JSONObject(conn.inputStream.bufferedReader().readText())
                val remoteCode = o.optInt("versionCode", 0)
                val remoteName = o.optString("versionName", "?")
                val apkUrl = o.optString("apkUrl", "")
                val notes = o.optString("notes", "")

                if (remoteCode > localCode(app) && apkUrl.isNotEmpty()) {
                    Handler(Looper.getMainLooper()).post {
                        showDialog(app, remoteName, remoteCode, apkUrl, notes)
                    }
                }
            } catch (_: Exception) {}
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
        } catch (_: Exception) {
            // 后台没有 Activity 时无法弹窗，静默跳过
        }
    }

    private fun download(c: Context, url: String, versionName: String) {
        val toast = { m: String ->
            Handler(Looper.getMainLooper()).post { Toast.makeText(c, m, Toast.LENGTH_SHORT).show() }
        }
        toast("开始下载 $versionName …")

        Thread {
            try {
                val dir = File(c.getExternalFilesDir(null), "update").apply { mkdirs() }
                val out = File(dir, "anxinban-$versionName.apk")
                if (out.exists()) out.delete()

                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.instanceFollowRedirects = true
                if (conn.responseCode !in 200..299) { toast("下载失败：HTTP ${conn.responseCode}"); return@Thread }

                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var n: Int
                        while (input.read(buf).also { n = it } > 0) output.write(buf, 0, n)
                    }
                }
                if (out.length() < 100_000) { toast("下载的安装包不完整"); return@Thread }

                Handler(Looper.getMainLooper()).post { install(c, out) }
            } catch (e: Exception) {
                toast("下载失败：" + (e.message ?: "未知错误"))
            }
        }.start()
    }

    private fun install(c: Context, apk: File) {
        try {
            // Android 8+ 需要「未知来源安装」权限
            if (Build.VERSION.SDK_INT >= 26 && !c.packageManager.canRequestPackageInstalls()) {
                AlertDialog.Builder(c)
                    .setTitle("需要安装权限")
                    .setMessage("请允许「安心伴孩子端」安装应用，然后重新点更新。")
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
            Toast.makeText(c, "无法启动安装：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
