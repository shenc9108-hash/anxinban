package com.anxinban.kid

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * 家长在家长端解除绑定后，孩子端收到 unbound 标记：
 *  1. 取消设备管理器激活（否则无法卸载）
 *  2. 清除本地 token / 规则 / 使用记录
 *  3. 停止后台服务
 *  4. 提示可以卸载
 *
 * 注意：取消设备管理器是系统限制操作，必须在主线程调 dpm.removeActiveAdmin()。
 */
object UnbindHandler {

    fun handle(c: Context) {
        val app = c.applicationContext

        // 1. 停止服务
        try { app.stopService(Intent(app, SyncService::class.java)) } catch (_: Exception) {}

        // 2. 取消设备管理器（释放防卸载）
        try {
            val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val comp = AdminReceiver.component(app)
            if (dpm.isAdminActive(comp)) dpm.removeActiveAdmin(comp)
        } catch (_: Exception) {}

        // 3. 清除本地数据
        try { Store.clearAll(app) } catch (_: Exception) {}
        try { ScreenCapture.release() } catch (_: Exception) {}

        // 4. 提示
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(app, "已被家长解除绑定，现在可以卸载本应用", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }
    }
}
