package com.hongguo.adskip

import android.content.Context

/** 轻量配置存储 */
object Prefs {
    private const val FILE = "adskip_prefs"

    /** 红果短剧（红果免费短剧）官方包名 */
    const val DEFAULT_TARGET = "com.phoenix.read"

    /** 默认反馈上报地址：云端服务（HTTPS）；局域网电脑接收服务可用 LanDiscovery 发现或手动填 */
    const val DEFAULT_FEEDBACK_URL = "https://123.57.163.149:8443/report"

    private const val KEY_TARGETS = "target_packages"
    private const val KEY_AUTO = "auto_swipe"
    private const val KEY_OVERLAY = "overlay_status"
    private const val KEY_DELAY = "extra_delay_ms"
    private const val KEY_FALLBACK = "fallback_wait_ms"
    private const val KEY_COUNT = "skip_count"
    private const val KEY_FB_BTN = "feedback_button"
    private const val KEY_FB_URL = "feedback_url"
    private const val KEY_URL_MIGRATED = "feedback_url_migrated_v1"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_AUTH_EMAIL = "auth_email"
    private const val KEY_FB_Y = "feedback_btn_y"
    private const val KEY_VISUAL_FALLBACK = "visual_ad_fallback"

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
     * 反馈上报服务器地址：默认指向云端服务。
     * 任何「未设置 / 空值」都一律回落到默认地址，确保一键反馈永远走静默上传，
     * 不会因为地址栏为空而弹出分享面板。
     */
    fun getFeedbackUrl(ctx: Context): String {
        migrateLegacyUrl(ctx)
        val v = sp(ctx).getString(KEY_FB_URL, null)
        return if (v.isNullOrBlank()) DEFAULT_FEEDBACK_URL else v
    }

    /** 旧版默认的局域网地址（仅用于一次性迁移判断） */
    private val LEGACY_LAN_URLS = setOf(
        "http://192.168.5.2:8765/report",
        "http://192.168.5.10:8765/report",
    )

    /**
     * 一次性迁移：老版本把局域网电脑地址保存进了设置，升级后仍会覆盖云端默认地址，
     * 导致外网（手机流量）下上报/更新全部连不上。这里把「恰好等于旧默认值」的
     * 保存项清掉回落到云端默认；迁移只做一次，之后用户手动保存/自动发现的地址不再动。
     */
    private fun migrateLegacyUrl(ctx: Context) {
        val sp = sp(ctx)
        if (sp.getBoolean(KEY_URL_MIGRATED, false)) return
        val saved = sp.getString(KEY_FB_URL, null)
        if (saved != null && saved in LEGACY_LAN_URLS) {
            sp.edit().remove(KEY_FB_URL).apply()
        }
        sp.edit().putBoolean(KEY_URL_MIGRATED, true).apply()
    }

    fun setFeedbackUrl(ctx: Context, v: String) = sp(ctx).edit().putString(KEY_FB_URL, v).apply()

    /** 反馈按钮纵向位置（屏高百分比） */
    fun getFeedbackBtnYPercent(ctx: Context) = sp(ctx).getInt(KEY_FB_Y, 42)

    fun setFeedbackBtnYPercent(ctx: Context, v: Int) =
        sp(ctx).edit().putInt(KEY_FB_Y, v.coerceIn(2, 96)).apply()

    /** 画面型广告兜底：屏幕几乎读不到文字时自动尝试上滑（默认开启，可在设置中关闭） */
    fun isVisualAdFallbackEnabled(ctx: Context) =
        sp(ctx).getBoolean(KEY_VISUAL_FALLBACK, true)

    fun setVisualAdFallbackEnabled(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_VISUAL_FALLBACK, v).apply()

    /** 云端账号（可选）：登录后反馈自动关联邮箱；不登录完全不影响使用 */
    fun getAuthToken(ctx: Context): String =
        sp(ctx).getString(KEY_AUTH_TOKEN, null) ?: ""

    fun getAuthEmail(ctx: Context): String =
        sp(ctx).getString(KEY_AUTH_EMAIL, null) ?: ""

    fun setAuth(ctx: Context, email: String, token: String) =
        sp(ctx).edit().putString(KEY_AUTH_EMAIL, email).putString(KEY_AUTH_TOKEN, token).apply()

    fun clearAuth(ctx: Context) =
        sp(ctx).edit().remove(KEY_AUTH_EMAIL).remove(KEY_AUTH_TOKEN).apply()
}
