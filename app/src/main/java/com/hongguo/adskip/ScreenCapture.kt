package com.hongguo.adskip

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log

/**
 * 屏幕捕捉（Android 10 及以下的截图方案）。
 * Android 11+ 使用无障碍服务自带的 takeScreenshot（无需授权）；
 * Android 10 及以下只能走 MediaProjection：由用户在设置页点一次系统授权弹窗，
 * 之后建立常驻虚拟屏幕持续镜像画面，点击反馈时直接取最新一帧，
 * 全程静默、不再弹任何窗口、不产生相册文件。
 */
object ScreenCapture {

    private const val TAG = "ScreenCapture"

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    /** 是否已授权并建立捕捉（仅 Android 11 以下需要） */
    val isReady: Boolean
        get() = imageReader != null

    /** 最近一次失败原因（供诊断日志用） */
    @Volatile
    var lastError: String? = null
        private set

    /** 在设置页由用户完成系统授权后调用 */
    fun start(ctx: Context, resultCode: Int, data: Intent): Boolean {
        stop()
        return try {
            val mgr = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mgr.getMediaProjection(resultCode, data) ?: return false
            val dm = ctx.resources.displayMetrics
            val w = dm.widthPixels
            val h = dm.heightPixels
            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
            val vd = p.createVirtualDisplay(
                "adskip-capture",
                w,
                h,
                dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )
            projection = p
            imageReader = reader
            virtualDisplay = vd
            lastError = null
            true
        } catch (e: Exception) {
            Log.w(TAG, "建立屏幕捕捉失败: ${e.message}")
            lastError = e.message ?: "建立捕捉失败"
            stop()
            false
        }
    }

    /** 取当前屏幕最新一帧（无授权时返回 null）；需在后台线程调用 */
    fun captureLatest(): Bitmap? {
        val reader = imageReader ?: run {
            lastError = "未授权"
            return null
        }
        // 画面帧可能刚建立还未产出，短暂重试几次
        for (attempt in 0 until 8) {
            try {
                val image = reader.acquireLatestImage()
                if (image != null) {
                    return try {
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - pixelStride * image.width
                        val bmp = Bitmap.createBitmap(
                            image.width + rowPadding / pixelStride,
                            image.height,
                            Bitmap.Config.ARGB_8888
                        )
                        bmp.copyPixelsFromBuffer(buffer)
                        if (rowPadding == 0) {
                            bmp
                        } else {
                            val cropped = Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
                            if (cropped !== bmp) bmp.recycle()
                            cropped
                        }
                    } finally {
                        image.close()
                    }
                }
            } catch (e: Exception) {
                lastError = e.message ?: "取帧异常"
            }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
            }
        }
        lastError = lastError ?: "无画面帧产出"
        return null
    }

    fun stop() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        imageReader = null
        projection = null
    }

    /** Android 11 以下才需要这种授权方式 */
    fun needed(): Boolean = Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q
}
