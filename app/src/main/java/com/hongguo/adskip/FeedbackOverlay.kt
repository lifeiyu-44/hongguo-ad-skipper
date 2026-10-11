package com.hongguo.adskip

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * 一键反馈悬浮按钮。
 * 仅在红果短剧（被监测应用）前台时显示的一个很小的半透明圆点：
 *  - 可上下拖动调整位置（位置记忆）
 *  - 轻点一下 = 抓取当前页面截图 + 识别快照 + 屏幕文字清单 + 运行日志，
 *    生成反馈并打开分享面板（可发微信文件传输助手）
 *  - 使用无障碍服务专属悬浮层（TYPE_ACCESSIBILITY_OVERLAY），无需任何额外权限
 *  - 可在设置里整体关闭
 */
object FeedbackOverlay {

    private var view: TextView? = null
    private var wm: WindowManager? = null
    private var shown = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    /** 由服务每轮扫描调用：目标应用在前台且开关开启时显示，否则隐藏 */
    fun setVisible(svc: AccessibilityService, show: Boolean) {
        val want = show && Prefs.isFeedbackButtonEnabled(svc)
        if (want == shown) return
        shown = want
        mainHandler.post {
            if (want) attach(svc) else detach()
        }
    }

    /** 抓取截图前把按钮临时隐藏，避免按钮出现在反馈截图里 */
    fun setAlpha(alpha: Float) {
        mainHandler.post { view?.alpha = alpha }
    }

    fun release() {
        shown = false
        mainHandler.post { detach() }
    }

    private fun attach(svc: AccessibilityService) {
        if (view != null) return
        try {
            val windowManager = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val size = dp(svc, 30)
            val v = TextView(svc).apply {
                text = "反"
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 11f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x7A6E6E78.toInt())
                }
                alpha = 0.85f
            }
            val dm = svc.resources.displayMetrics
            val p = WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dm.widthPixels - size - dp(svc, 4)
                y = (dm.heightPixels * (Prefs.getFeedbackBtnYPercent(svc) / 100f)).toInt()
            }
            v.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX
                        downY = ev.rawY
                        dragging = false
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - downX
                        val dy = ev.rawY - downY
                        if (!dragging && (abs(dx) > dp(svc, 8) || abs(dy) > dp(svc, 8))) {
                            dragging = true
                        }
                        if (dragging) {
                            p.x = (p.x + dx.toInt()).coerceIn(0, dm.widthPixels - size)
                            p.y = (p.y + dy.toInt()).coerceIn(0, dm.heightPixels - size)
                            downX = ev.rawX
                            downY = ev.rawY
                            try {
                                wm?.updateViewLayout(v, p)
                            } catch (_: Exception) {
                            }
                        }
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!dragging) {
                            try {
                                (svc as? AdSkipService)?.onFeedbackTapped()
                            } catch (_: Exception) {
                            }
                        } else {
                            Prefs.setFeedbackBtnYPercent(svc, (p.y * 100f / dm.heightPixels).toInt())
                        }
                        true
                    }

                    else -> false
                }
            }
            windowManager.addView(v, p)
            view = v
            wm = windowManager
        } catch (_: Exception) {
            view = null
            wm = null
        }
    }

    private fun detach() {
        val v = view ?: return
        view = null
        try {
            wm?.removeView(v)
        } catch (_: Exception) {
        }
        wm = null
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
}
