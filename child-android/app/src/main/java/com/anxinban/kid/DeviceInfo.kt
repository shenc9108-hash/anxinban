package com.anxinban.kid

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import org.json.JSONObject

/** 采集设备基础信息，随心跳上报。 */
object DeviceInfo {

    fun collect(c: Context): JSONObject {
        val o = JSONObject()
        o.put("model", Build.MODEL ?: "未知")
        o.put("brand", Build.BRAND ?: "")
        o.put("manufacturer", Build.MANUFACTURER ?: "")
        o.put("android", Build.VERSION.RELEASE ?: "")
        o.put("sdk", Build.VERSION.SDK_INT)

        // 存储
        try {
            val st = StatFs(Environment.getDataDirectory().path)
            val total = st.blockCountLong * st.blockSizeLong
            val free = st.availableBlocksLong * st.blockSizeLong
            o.put("storageTotal", total)
            o.put("storageFree", free)
        } catch (_: Exception) {}

        // 内存
        try {
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            o.put("memTotal", mi.totalMem)
            o.put("memAvail", mi.availMem)
        } catch (_: Exception) {}

        // 屏幕亮屏时长（开机以来）
        try {
            // 不需要额外权限的近似值：开机时长
            o.put("uptime", android.os.SystemClock.elapsedRealtime())
        } catch (_: Exception) {}

        return o
    }
}
