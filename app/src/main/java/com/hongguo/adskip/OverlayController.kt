package com.hongguo.adskip

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * 播放界面上方的状态悬浮窗：让用户随时能看到
 * 「监测中 / 广告倒计时 / 已自动上滑」。
 * 位置按屏幕高度比例定位，手机与平板（含横屏）均适用。
 */
object OverlayController {

    private var tv: TextView? = null
    private var wm: WindowManager? = null
    private var lastText: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun update(ctx: Context, text: String?) {
        if (text == lastText) return
        lastText = text
        mainHandler.post {
            val app = ctx.applicationContext
            if (text.isNullOrEmpty() || !Prefs.isOverlayEnabled(app) || !Settings.canDrawOverlays(app)) {
                removeInternal()
                return@post
            }
            try {
                if (tv == null) attach(app)
                tv?.text = text
                tv?.visibility = View.VISIBLE
            } catch (e: Exception) {
                removeInternal()
            }
        }
    }

    private fun attach(app: Context) {
        val windowManager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // 刻意做得很小很透明：只在有广告时短暂出现，尽量不干扰画面
        val view = TextView(app).apply {
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 11f
            setPadding(dp(app, 9), dp(app, 4), dp(app, 9), dp(app, 4))
            background = GradientDrawable().apply {
                cornerRadius = dp(app, 12).toFloat()
                setColor(0x66222222)
            }
        }
        val params = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // 距顶部 6% 屏高，平板横竖屏都不会顶到状态栏/刘海
            y = (app.resources.displayMetrics.heightPixels * 0.06f).toInt()
        }
        windowManager.addView(view, params)
        tv = view
        wm = windowManager
    }

    private fun removeInternal() {
        val v = tv ?: return
        tv = null
        try {
            wm?.removeView(v)
        } catch (_: Exception) {
        }
        wm = null
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
}
