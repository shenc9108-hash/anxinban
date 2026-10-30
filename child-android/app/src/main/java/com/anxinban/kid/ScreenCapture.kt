package com.anxinban.kid

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * 屏幕截图。
 *
 * 设计约束（出于合规与体验考虑）：
 *  - 必须由孩子在手机上主动点击「允许截屏」，获得 MediaProjection 授权
 *  - 已授权后，家长端每次请求截屏，孩子端最多每 60 秒执行 1 次
 *  - Android 14+ 系统会在状态栏显示投屏图标，孩子可见
 *  - 授权在进程被杀后失效，需要重新授权
 */
object ScreenCapture {

    private var projection: MediaProjection? = null
    private var resultCode = 0
    private var resultData: Intent? = null
    private var lastShot = 0L
    private const val MIN_INTERVAL = 60_000L

    /** 是否已获得授权。 */
    fun isReady(): Boolean = projection != null

    /** 在 Activity 中请求授权，用 registerForActivityResult 或 startActivityForResult 接收结果。 */
    @Suppress("DEPRECATION")
    fun requestPermission(activity: Activity, reqCode: Int) {
        val mpm = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        activity.startActivityForResult(mpm.createScreenCaptureIntent(), reqCode)
    }

    /** 在 onActivityResult 中回调。 */
    fun onPermissionResult(c: Context, code: Int, data: Intent?): Boolean {
        if (code != Activity.RESULT_OK || data == null) return false
        val mpm = c.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection?.stop()
        projection = mpm.getMediaProjection(code, data)
        resultCode = code
        resultData = data
        return true
    }

    /** 释放授权（孩子可在主界面关闭）。 */
    fun release() {
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        resultCode = 0
        resultData = null
    }

    /** 截一张图，返回 JPEG 的 Base64（无则 null）。会做频率限制。 */
    fun capture(c: Context, width: Int = 720): String? {
        val proj = projection ?: return null
        val now = System.currentTimeMillis()
        if (now - lastShot < MIN_INTERVAL) return null   // 频率限制

        val dm = c.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val metrics = c.resources.displayMetrics
        val w = minOf(metrics.widthPixels, width)
        val h = (metrics.heightPixels * (w.toFloat() / metrics.widthPixels)).toInt()
        val density = metrics.densityDpi

        var bmp: Bitmap? = null
        val lock = Object()

        val reader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 1)
        val handler = Handler(Looper.getMainLooper())
        val virtualDisplay = proj.createVirtualDisplay(
            "anxinban-shot", w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )

        reader.setOnImageAvailableListener({ rd ->
            try {
                val img = rd.acquireLatestImage()
                if (img != null) {
                    val plane = img.planes[0]
                    val rowStride = plane.rowStride
                    val pixelStride = plane.pixelStride
                    val paddedWidth = rowStride / pixelStride
                    val buf = plane.buffer
                    val bt = Bitmap.createBitmap(paddedWidth, h, Bitmap.Config.ARGB_8888)
                    bt.copyPixelsFromBuffer(buf)
                    bmp = Bitmap.createBitmap(bt, 0, 0, w, h)
                    img.close()
                }
            } catch (_: Exception) {
            } finally {
                synchronized(lock) { lock.notifyAll() }
            }
        }, handler)

        synchronized(lock) {
            try { lock.wait(2500) } catch (_: Exception) {}
        }
        try { virtualDisplay.release() } catch (_: Exception) {}
        try { reader.close() } catch (_: Exception) {}

        val b = bmp ?: return null
        lastShot = System.currentTimeMillis()
        return try {
            val bos = ByteArrayOutputStream()
            b.compress(Bitmap.CompressFormat.JPEG, 55, bos)
            Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) { null }
    }
}
