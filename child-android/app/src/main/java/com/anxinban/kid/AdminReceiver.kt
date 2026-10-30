package com.anxinban.kid

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * 设备管理器：激活后孩子无法直接卸载本应用
 * （系统会要求先到「设备管理应用」里取消激活，而孩子端可拦截该设置页）。
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Store.disableActive(context, false)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Store.disableActive(context, true)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "本应用正在守护孩子手机使用，取消后将无法限制使用时间。确定要取消吗？"
    }

    companion object {
        fun component(c: Context) = ComponentName(c, AdminReceiver::class.java)

        fun isActive(c: Context): Boolean {
            val dpm = c.getSystemService(Context.DEVICE_ADMIN_SERVICE) as android.app.admin.DevicePolicyManager
            return dpm.isAdminActive(component(c))
        }
    }
}
