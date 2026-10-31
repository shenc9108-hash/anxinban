package com.anxinban.kid

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*

/** 首次设置页：绑定家长 + 引导开启必要权限。 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private val demo = """{"locked":false,"timed":true,"sleepStart":21,"sleepEnd":7,"daily":60,
        "limits":{"com.tencent.mm":1,"tv.danmaku.bili":20},"whitelist":[]}"""

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Api.init(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        status = TextView(this).apply { textSize = 15f; setPadding(0, 0, 0, 32) }
        val code = EditText(this).apply { hint = "输入家长端的 6 位绑定码" }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }
        col.addView(TextView(this).apply { text = "家长守护 · 孩子端"; textSize = 22f })
        // 版本号单独一行、醒目显示，避免被状态区遮挡
        col.addView(TextView(this).apply {
            text = "版本 v" + (packageManager.getPackageInfo(packageName, 0).versionName ?: "?")
            textSize = 18f
            setTextColor(0xFF1976D2.toInt())
            setPadding(0, 8, 0, 12)
        })
        col.addView(status)
        val server = EditText(this).apply {
            hint = "服务器地址，如 guard.example.com 或 192.168.1.20:3000"
            setText(Store.server(this@MainActivity)?.removeSuffix("/api") ?: "")
        }
        col.addView(server)
        col.addView(code)
        col.addView(btn("绑定家长") {
            Api.setServer(this, server.text.toString())
            Thread {
                val r = Api.bind(code.text.toString(), Build.MODEL)
                runOnUiThread {
                    if (r == null) toast("绑定失败，请检查绑定码和网络") else {
                        Store.saveBind(this, r.getString("token")); startGuard(); refresh()
                    }
                }
            }.start()
        })
        col.addView(btn("1. 开启无障碍服务（必需）") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        col.addView(btn("2. 允许通知并关闭电池优化") {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })
        col.addView(btn("3. 开启位置权限（请选“始终允许”）") {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 2)
        })
        col.addView(btn("4. 防止卸载（激活设备管理器）") {
            if (AdminReceiver.isActive(this)) {
                toast("已启用。如需卸载，请先到「设置 → 安全 → 设备管理应用」取消激活")
            } else {
                try {
                    startActivityForResult(
                        Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                            .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, AdminReceiver.component(this))
                            .putExtra(
                                android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                "激活后可防止孩子误卸载守护应用。家长如需卸载，请先到「设置 → 安全 → 设备管理应用」取消激活。"
                            ),
                        4
                    )
                } catch (e: Exception) {
                    toast("无法打开设备管理器：" + e.message)
                }
            }
        })
        col.addView(btn("5. 允许家长截屏（可选）") {
            if (ScreenCapture.isReady()) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("截屏已授权")
                    .setMessage("家长可以查看孩子的屏幕内容。\n\n出于隐私考虑，建议仅在必要时使用。\n是否关闭截屏授权？")
                    .setPositiveButton("关闭授权") { _, _ -> ScreenCapture.release(); toast("已关闭截屏授权"); refresh() }
                    .setNegativeButton("保持开启", null)
                    .show()
            } else {
                try {
                    ScreenCapture.requestPermission(this, 9)
                } catch (e: Exception) {
                    toast("无法请求截屏权限：" + e.message)
                }
            }
        })
        col.addView(btn("检查更新") {
            toast("正在检查…（当前 v${packageManager.getPackageInfo(packageName, 0).versionName}）")
            Updater.check(this, force = true, silent = false)
        })
        col.addView(btn("无服务器测试：载入演示规则") { Store.saveRules(this, demo); startGuard(); toast("微信限 1 分钟，用后会被拦截"); refresh() })

        // ---- 自检面板 ----
        col.addView(TextView(this).apply {
            text = "—— 自检 ——"; textSize = 18f; setPadding(0, 32, 0, 12)
        })
        val diagText = TextView(this).apply {
            textSize = 13f
            setPadding(0, 8, 0, 8)
            setTextIsSelectable(true)
        }
        fun updateDiag() {
            diagText.text = Diag.report(this) + "\n\n" + Diag.serviceState(this)
        }
        updateDiag()
        col.addView(diagText)
        val self = this
        col.addView(btn("立即上报一次（测试连接）") {
            Thread {
                try { Api.sync(self) } catch (_: Throwable) {}
                runOnUiThread { updateDiag(); toast("已尝试上报，看上面的结果") }
            }.start()
        })
        col.addView(btn("服务状态 / 电池优化设置") {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:$packageName")))
            } catch (_: Throwable) {}
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(reqCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(reqCode, resultCode, data)
        if (reqCode == 9) {
            if (ScreenCapture.onPermissionResult(this, resultCode, data)) {
                toast("截屏已授权，家长可以请求查看屏幕")
            } else {
                toast("已取消截屏授权")
            }
            refresh()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 2 && res.isNotEmpty() && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= 29) requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 3)
            if (Store.token(this) != null) startGuard()
        }
        if (code == 3 && Store.token(this) != null) startGuard()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // 每次回到前台检查一次更新（内部有 6 小时节流）
        try { Updater.check(this) } catch (_: Exception) {}
    }
    private fun startGuard() = startForegroundService(Intent(this, SyncService::class.java))
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun refresh() {
        val on = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(packageName) == true
        status.text = "绑定：" + (if (Store.token(this) != null) "已绑定" else "未绑定") + "\n无障碍：" + (if (on) "已开启" else "未开启") + "\n防卸载：" + (if (AdminReceiver.isActive(this)) "已启用" else "未启用") + "\n截屏授权：" + (if (ScreenCapture.isReady()) "已开启" else "未开启") + "\n当前版本：v" + (packageManager.getPackageInfo(packageName, 0).versionName ?: "?")
    }
}
