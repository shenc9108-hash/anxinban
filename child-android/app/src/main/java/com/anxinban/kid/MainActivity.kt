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
        col.addView(btn("无服务器测试：载入演示规则") { Store.saveRules(this, demo); startGuard(); toast("微信限 1 分钟，用后会被拦截"); refresh() })
        setContentView(ScrollView(this).apply { addView(col) })
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

    override fun onResume() { super.onResume(); refresh() }
    private fun startGuard() = startForegroundService(Intent(this, SyncService::class.java))
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun refresh() {
        val on = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(packageName) == true
        status.text = "绑定：" + (if (Store.token(this) != null) "已绑定" else "未绑定") + "\n无障碍：" + (if (on) "已开启" else "未开启") + "\n防卸载：" + (if (AdminReceiver.isActive(this)) "已启用" else "未启用")
    }
}
