package com.hongguo.adskip

import android.content.Context

/** 轻量配置存储 */
object Prefs {
    private const val FILE = "adskip_prefs"

    /** 红果短剧（红果免费短剧）官方包名 */
    const val DEFAULT_TARGET = "com.phoenix.read"

    private const val KEY_TARGETS = "target_packages"
    private const val KEY_AUTO = "auto_swipe"
    private const val KEY_OVERLAY = "overlay_status"
    private const val KEY_DELAY = "extra_delay_ms"
    private const val KEY_COUNT = "skip_count"

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

    fun getSkipCount(ctx: Context) = sp(ctx).getInt(KEY_COUNT, 0)

    fun setSkipCount(ctx: Context, v: Int) = sp(ctx).edit().putInt(KEY_COUNT, v).apply()
}
