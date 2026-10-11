package com.hongguo.adskip

import android.content.Context

/** 轻量配置存储 */
object Prefs {
    private const val FILE = "adskip_prefs"

    /** 红果短剧（红果免费短剧）官方包名 */
    const val DEFAULT_TARGET = "com.phoenix.read"

    /** 默认反馈上报地址：指向电脑端接收服务（有线网IP；无线 192.168.5.10 作为备用自动回退） */
    const val DEFAULT_FEEDBACK_URL = "http://192.168.5.2:8765/report"

    private const val KEY_TARGETS = "target_packages"
    private const val KEY_AUTO = "auto_swipe"
    private const val KEY_OVERLAY = "overlay_status"
    private const val KEY_DELAY = "extra_delay_ms"
    private const val KEY_FALLBACK = "fallback_wait_ms"
    private const val KEY_COUNT = "skip_count"
    private const val KEY_FB_BTN = "feedback_button"
    private const val KEY_FB_URL = "feedback_url"
    private const val KEY_FB_Y = "feedback_btn_y"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getTargetPackages(ctx: Context): Set<String> =
        sp(ctx).getStringSet(KEY_TARGETS, setOf(DEFAULT_TARGET))?.toSet() ?: setOf(DEFAULT_TARGET)

    fun setTargetPackages(ctx: Context, v: Set<String>) =
        sp(ctx).edit().putStringSet(KEY_TARGETS, v).apply()

    fun isAutoSwipeEnabled(ctx: Context) = sp(ctx).getBoolean(KEY_AUTO, true)

    fun setAutoSwipeEnabled(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_AUTO, v).apply()

    fun isOverlayEnabled(ctx: Context) = sp(ctx).getBoolean(KEY_OVERLAY, true)

    fun setOverlayEnabled(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_OVERLAY, v).apply()

    fun getExtraDelayMs(ctx: Context) = sp(ctx).getInt(KEY_DELAY, 250)

    fun setExtraDelayMs(ctx: Context, v: Int) = sp(ctx).edit().putInt(KEY_DELAY, v).apply()

    /** 无倒计时广告的兜底等待时长（毫秒），到点后开始尝试上滑 */
    fun getFallbackWaitMs(ctx: Context) = sp(ctx).getInt(KEY_FALLBACK, 10000)

    fun setFallbackWaitMs(ctx: Context, v: Int) = sp(ctx).edit().putInt(KEY_FALLBACK, v).apply()

    fun getSkipCount(ctx: Context) = sp(ctx).getInt(KEY_COUNT, 0)

    fun setSkipCount(ctx: Context, v: Int) = sp(ctx).edit().putInt(KEY_COUNT, v).apply()

    /** 一键反馈悬浮按钮开关（仅在红果前台时显示） */
    fun isFeedbackButtonEnabled(ctx: Context) = sp(ctx).getBoolean(KEY_FB_BTN, true)

    fun setFeedbackButtonEnabled(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_FB_BTN, v).apply()

    /**
     * 反馈上报服务器地址：默认指向电脑端接收服务。
     * 任何「未设置 / 空值」都一律回落到默认地址，确保一键反馈永远走静默上传，
     * 不会因为地址栏为空而弹出分享面板。
     */
    fun getFeedbackUrl(ctx: Context): String {
        val v = sp(ctx).getString(KEY_FB_URL, null)
        return if (v.isNullOrBlank()) DEFAULT_FEEDBACK_URL else v
    }

    fun setFeedbackUrl(ctx: Context, v: String) = sp(ctx).edit().putString(KEY_FB_URL, v).apply()

    /** 反馈按钮纵向位置（屏高百分比） */
    fun getFeedbackBtnYPercent(ctx: Context) = sp(ctx).getInt(KEY_FB_Y, 42)

    fun setFeedbackBtnYPercent(ctx: Context, v: Int) =
        sp(ctx).edit().putInt(KEY_FB_Y, v.coerceIn(2, 96)).apply()
}
