package com.hongguo.adskip

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 核心无障碍服务，工作流程：
 * 1. 定时检查前台窗口，判断是否为目标 App（红果短剧 com.phoenix.read / 红果免费漫剧 com.kylin.read）
 * 2. 遍历视图树读取屏幕文字，识别三类广告特征：
 *    - 「N秒后可继续上滑观看短剧」倒计时提示（主特征）
 *    - 右上角「广告」角标（含无倒计时的广告，走兜底等待后上滑）
 *    - 「跳过」按钮（出现即点击）
 * 3. 倒计时归零 / 兜底等待到点后，模拟上滑手势进入下一集；翻页失败自动重试
 *
 * 识别基于无障碍节点文字，在本机完成，不联网。
 * 屏幕上不显示任何悬浮内容；断网时自动暂停，避免把断网页面误判成广告。
 */
class AdSkipService : AccessibilityService() {

    enum class State { IDLE, MONITORING, AD_COUNTDOWN, AD_FALLBACK }

    companion object {
        /**
         * 倒计时提示特征，两种文案都要认：
         *   平板端「3秒后可继续上滑观看短剧」、手机端「5s 后可继续上滑观看」（拉丁字母 s）。
         * 若红果短剧更新后文案变化导致识别失效，改这一行即可。
         */
        val COUNTDOWN_REGEX = Regex("""(\d+)\s*[秒sS]\s*后[^0-9]{0,12}?(上滑|滑动|继续|观看|跳过|关闭|自动|播放|进入|下一集|解锁|可滑)""")

        /**
         * 「广告」角标上的剩余秒数（备用特征）。两种写法都要认：
         * 带单位「广告 15s」、不带单位「广告 15」（部分横屏/竖屏视频广告只显示纯数字）。
         * 严格限定整段文字形态与数值上限（≤90），避免把「广告 100 元」等误判成秒数。
         */
        val CHIP_COUNTDOWN_REGEX = Regex("""广告\s*(\d{1,3})\s*[sS秒]?(?![a-zA-Z0-9])\s*$""")

        /** 「跳过 5」「跳过广告 5」这类带倒数数字的跳过按钮：数字未归零前不可用，不点击 */
        val SKIP_WITH_COUNTDOWN_REGEX = Regex("""跳过[^0-9]{0,4}\d+""")

        /**
         * 倒计时尾部文案（不含数字），用于「数字与文案被渲染成两个独立节点」的广告布局。
         * 例：数字节点「5s」+ 文案节点「后可继续上滑观看」/「秒后可继续上滑观看短剧」。
         * 与数字节点就近配对成功后即可得到真实剩余秒数。
         */
        val COUNTDOWN_TAIL_REGEX = Regex("""^(?:[秒sS]\s*)?后[^0-9]{0,12}?(上滑|滑动|继续|观看|跳过|关闭|自动|播放|进入|下一集|解锁|可滑)""")

        /** 纯数字节点（拆分布局中倒计时秒数单独成节点，如「5」「5s」） */
        val COUNTDOWN_NUMBER_REGEX = Regex("""^\s*(\d{1,3})\s*[sS秒]?\s*$""")

        /**
         * 「上滑继续观看短剧」「上滑观看下一集」等无数字的解锁提示：
         * 红果在广告可跳过时显示，出现即说明现在就能上滑（无倒计时广告的可靠信号）。
         * 必须以「上滑」开头：锁定期文案「N秒后可继续上滑观看短剧」也含「上滑观看」，
         * 但以「N秒后」开头，用锚定开头来区分；「上滑查看详情」（进商城）不匹配。
         */
        val UNLOCK_HINT_REGEX = Regex("""^[^0-9]{0,4}上滑[^0-9]{0,4}?(继续|观看|进入|下一集)""")

        /** 红果拒绝滑动时的提示「倒计时结束后即可滑动」：出现说明仍在锁定期，应顺延重试 */
        val LOCKED_TOAST_REGEX = Regex("""倒计时结束后""")

        /** 直播购物类广告的特征文字（无「广告」角标、无倒计时）：直播中/讲解中/已售N件/点击进入直播间 */
        val LIVE_AD_REGEX = Regex("""直播中|讲解中|已售\s*\d+|点击进入直播间""")

        /** 游戏类广告的特征按钮：点击进入游戏/点击试玩 等（仅作识别信号，绝不点击） */
        val GAME_AD_REGEX = Regex("""点击进入游戏|点击试玩|立即试玩|点击游玩|点击开玩""")

        private const val AD_LABEL = "广告"

        // ---- 扫描节奏 ----
        private const val POLL_INTERVAL_MS = 350L
        private const val WINDOW_NODE_LIMIT = 600   // 每个窗口扫描的节点上限（多窗口合并）
        private const val PROBE_NODE_LIMIT = 260     // 非目标应用的轻量探测上限
        private const val PROBE_EVERY_N_TICKS = 3    // 非目标应用降低探测频率

        // ---- 跳过时机 ----
        private const val DEADLINE_BUFFER_MS = 350L       // 倒计时归零后等 UI 稳定
        private const val AFTER_SWIPE_COOLDOWN_MS = 4000L // 跳过尝试后的冷却（等翻页动画和加载完成）
        private const val AD_GONE_GRACE_MS = 2000L        // 倒计时文字消失多久视为广告已不在
        private const val CHIP_CONFIRM_MS = 700L          // 角标需连续出现约2个扫描周期才认定，防单帧误判
        private const val HINT_ONLY_CONFIRM_MS = 1200L    // 「上滑继续看短剧」提示持续出现多久即视为广告（各类型通用信号）
        private const val UNLOCK_BLINK_GRACE_MS = 900L    // 提示闪断宽限：间隔多久内重新出现仍算连续
        private const val MAX_ATTEMPTS = 8                // 同一波广告最多尝试次数（覆盖被锁定期拒绝后的重试）
        private const val RETRY_GAP_MS = 3500L            // 重试间隔（放宽以减少滑动打扰）
        private const val LOCKED_WAVE_LIMIT_MS = 25000L   // 锁定期广告等待正向信号的上限，超时复位重新判定
        private const val AD_BLINK_GRACE_MS = 800L        // 广告特征闪断（UI重绘瞬间）的宽限，不重新计时
        // ---- 画面型广告兜底（可选功能，默认开启）----
        private const val EMPTY_TREE_CONFIRM_MS = 5000L   // 屏幕几乎读不到文字持续多久判定为画面型广告
        private const val EMPTY_BLIP_GRACE_MS = 3000L     // 闪断宽限：短暂读到文字（界面重绘）不清零计时
        private const val EMPTY_FLIP_MAX = 2              // 窗口内最多尝试次数（防误翻连环）
        private const val EMPTY_FLIP_WINDOW_MS = 300000L  // 次数统计窗口
        private const val FALLBACK_TOTAL_LIMIT_MS = 45000L // 一波广告自动跳过的总时长上限，超时放弃

        // ---- 上滑手势（按屏幕宽高比例计算，手机/平板/横竖屏通用）----
        // 常规起手：从屏幕下部起手，长距离快速滑动
        private const val SWIPE_DURATION_MS = 220L
        private const val SWIPE_START_FRACTION_PORTRAIT = 0.80f
        private const val SWIPE_END_FRACTION_PORTRAIT = 0.16f
        private const val SWIPE_START_FRACTION_LANDSCAPE = 0.78f
        private const val SWIPE_END_FRACTION_LANDSCAPE = 0.18f
        // 上部起手：直播购物类广告底部是可滚动商品面板，从下部起手手势会被
        // 面板拦截导致翻页失败；改为从画面上部起手（避开面板），速度更快
        private const val SWIPE_UPPER_DURATION_MS = 200L
        private const val SWIPE_UPPER_START_PORTRAIT = 0.50f
        private const val SWIPE_UPPER_END_PORTRAIT = 0.04f
        private const val SWIPE_UPPER_START_LANDSCAPE = 0.50f
        private const val SWIPE_UPPER_END_LANDSCAPE = 0.05f

        // ---- 供 UI 订阅的状态 ----
        val running = MutableStateFlow(false)
        val state = MutableStateFlow(State.IDLE)
        val countdownRemain = MutableStateFlow(-1)
        val skipCount = MutableStateFlow(0)
        val logs = MutableStateFlow(listOf("等待服务启动…"))

        @Volatile
        private var instance: AdSkipService? = null

        /** 从主界面手动触发一次上滑，用于验证手势在当前设备上是否有效 */
        fun requestTestSwipe(): Boolean {
            val svc = instance ?: return false
            svc.mainHandler.post { svc.swipeUp("手动测试") }
            return true
        }

        /**
         * 从主界面一键触发「截图 + 缺陷上报」：与红果内悬浮反馈点是同一流程，
         * 供用户在 App 界面里随时提交问题（截图为触发瞬间的当前屏幕）。
         */
        fun requestFeedback(): Boolean {
            val svc = instance ?: return false
            return svc.onFeedbackTapped(FeedbackSource.IN_APP)
        }

        fun clearLogs() {
            logs.value = emptyList()
        }

        /** 供界面层写入运行日志（如截图授权结果），会一并出现在反馈报告中 */
        fun appendLogExternal(msg: String) {
            val line = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()) + "  " + msg
            logs.value = (logs.value + line).takeLast(120)
        }
    }

    /** IDLE=无广告 COUNTING=倒计时中 FALLBACK=有「广告」角标但无倒计时（兜底等待/重试） */
    private enum class Mode { IDLE, COUNTING, FALLBACK }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val tsFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private var mode = Mode.IDLE
    private var deadlineAt = 0L            // elapsedRealtime 时刻的下次动作时间
    private var hardDeadlineAt = 0L        // 倒计时的硬超时点：文字卡死不动时兜底强制滑动
    private var lastCountdownSeenAt = 0L
    private var cooldownUntil = 0L
    private var chipFirstSeenAt = 0L       // 「广告」角标首次出现时间（连续出现判定用）
    private var unlockSinceAt = 0L         // 「上滑继续看短剧」提示首次出现时间（通用信号判定用）
    private var lastUnlockSeenAt = 0L      // 最近一次见到该提示的时间（闪断宽限用）
    private var lastSheetLikely = false    // 最近一轮扫描是否发现底部商品面板（决定上滑起手位置）
    private var lastAdSeenAt = 0L          // 最近一次见到广告特征的时间（闪断宽限判定用）
    private var fallbackSinceAt = 0L       // 本波广告兜底流程的开始时间（总时长上限用）
    private var retryCount = 0             // 当前这波广告已尝试次数
    private var lockToastSeenAt = 0L       // 本波广告见到「倒计时结束后才可滑动」的时间（0=未见）
    private var countingUnknownSec = false // 当前倒计时为「拆分布局且秒数未知」：只能等文案消失后上滑
    private var probeTick = 0
    private var offlineLogged = false      // 断网提示只记一次，避免日志刷屏
    private var lastScan: ScanResult? = null          // 最近一轮扫描结果（反馈用）
    private var lastForegroundPackage: String? = null // 最近一次前台包名（反馈用）
    private var feedbackBusy = false                  // 一键反馈进行中保护
    private var lastReportAt = 0L                     // 上次反馈时间（冷却用，防误触连点）
    private var lastProbeNoticePkg: String? = null    // 同类广告样式提示去重（防日志刷屏）
    private var emptySinceAt = 0L                     // 屏幕读不到任何文字的起始时间（画面型广告检测）
    private var lastEmptySeenAt = 0L                  // 最近一次见到"空屏"的时间（闪断宽限用）
    private val emptyFlipTimes = ArrayDeque<Long>()   // 画面型广告兜底上滑的时间记录（限额用）

    private val ticker = object : Runnable {
        override fun run() {
            try {
                scanOnce()
            } catch (e: Exception) {
                appendLog("扫描出错: ${e.message}")
            }
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val kicker = Runnable {
        try {
            scanOnce()
        } catch (_: Exception) {
        }
    }

    /** 到点即时触发一次扫描，消除轮询相位带来的额外等待 */
    private fun scheduleDeadlineKick(delayMs: Long) {
        if (delayMs > 50) mainHandler.postDelayed(kicker, delayMs)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        running.value = true
        skipCount.value = Prefs.getSkipCount(this)
        // 监测列表固定为两款红果官方应用（短剧 + 免费漫剧）：清理历史遗留（曾因"自动学习"
        // 误加其他应用，导致"打开红果短剧"提示未安装、并误监测无关应用）；
        // 老版本升级后这一步会自动把「红果免费漫剧」补进监测列表
        if (Prefs.getTargetPackages(this) != Prefs.DEFAULT_TARGETS) {
            Prefs.setTargetPackages(this, Prefs.DEFAULT_TARGETS)
            appendLog("已重置监测列表为：红果短剧 + 红果免费漫剧")
        }
        val dm = resources.displayMetrics
        val orient = if (dm.heightPixels >= dm.widthPixels) "竖屏" else "横屏"
        appendLog("服务已启动 v${BuildConfig.VERSION_NAME}（屏幕 ${dm.widthPixels}×${dm.heightPixels}，$orient）")
        mainHandler.post(ticker)
        startUpdateWatch()
    }

    /* ---------- 在线版本巡检：服务运行期间每 6 小时静默检查，新版发通知栏提醒 ---------- */

    private var updateWatchThread: Thread? = null

    private fun startUpdateWatch() {
        if (updateWatchThread?.isAlive == true) return
        updateWatchThread = Thread {
            Thread.sleep(60_000L) // 启动 1 分钟后首查，避开开机繁忙
            while (true) {
                try {
                    checkCloudUpdate()
                } catch (_: Exception) {
                }
                Thread.sleep(6 * 3600_000L)
            }
        }.apply {
            isDaemon = true
            name = "update-watch"
            start()
        }
    }

    private fun checkCloudUpdate() {
        val base = UpdateChecker.baseUrl(this) ?: return
        val conn = (java.net.URL("$base/ping").openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
        }
        val body = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
        val remote = try {
            org.json.JSONObject(body ?: return).optString("version").ifBlank { null }
        } catch (_: Exception) {
            null
        } ?: return
        if (UpdateChecker.isNewer(remote, BuildConfig.VERSION_NAME) &&
            Prefs.getLastNotifyVer(this) != remote
        ) {
            Prefs.setLastNotifyVer(this, remote)
            mainHandler.post { notifyUpdate(remote) }
        }
    }

    /** 通知栏提醒新版本；点击通知直接下载并拉起安装 */
    private fun notifyUpdate(version: String) {
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                appendLog("发现新版本 v$version（无通知权限，打开 App 即可更新）")
                return
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel("update", "版本更新", android.app.NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val pi = android.app.PendingIntent.getActivity(
                this, 1001,
                Intent(this, MainActivity::class.java)
                    .putExtra("auto_update", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = if (Build.VERSION.SDK_INT >= 26) {
                android.app.Notification.Builder(this, "update")
            } else {
                @Suppress("DEPRECATION")
                android.app.Notification.Builder(this)
            }
                .setContentTitle("发现新版本 v$version")
                .setContentText("点此立即更新红果广告跳过")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(2001, n)
            appendLog("已发送更新提醒通知：v$version")
        } catch (e: Exception) {
            appendLog("更新通知发送失败：${e.message}")
        }
    }

    override fun onDestroy() {
        instance = null
        running.value = false
        state.value = State.IDLE
        countdownRemain.value = -1
        mode = Mode.IDLE
        mainHandler.removeCallbacks(ticker)
        FeedbackOverlay.release()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 以轮询为主；窗口切换事件用来加快响应
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            mainHandler.post(kicker)
        }
    }

    // ------------------------------------------------------------------ 扫描

    /** 设备当前是否能上网（断网时暂停一切识别与滑动，避免误翻页） */
    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return true
        val nw = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(nw) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun scanOnce() {
        if (!isOnline()) {
            if (mode != Mode.IDLE) {
                resetToIdle("网络已断开，取消本次自动跳过")
            } else if (!offlineLogged) {
                offlineLogged = true
                appendLog("网络已断开，自动跳过暂停")
            }
            publishState(State.IDLE, -1)
            return
        }
        if (offlineLogged) {
            offlineLogged = false
            appendLog("网络已恢复，继续监测")
        }

        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        val now = SystemClock.elapsedRealtime()

        // 一键反馈按钮：仅在目标应用前台时显示（开关可在设置中关闭）
        val inTarget = pkg in Prefs.getTargetPackages(this)
        lastForegroundPackage = pkg
        FeedbackOverlay.setVisible(this, inTarget)

        if (!Prefs.isAutoSwipeEnabled(this)) {
            if (mode != Mode.IDLE) resetToIdle()
            publishState(State.IDLE, -1)
            return
        }

        if (!inTarget) {
            // 非目标应用：低频轻量探测；若发现同样的倒计时文案，自动把该包名加入监测
            probeTick++
            if (probeTick % PROBE_EVERY_N_TICKS == 0) probeUnknownApp(root, pkg)
            if (mode != Mode.IDLE) resetToIdle("已离开播放页，取消本次自动上滑")
            publishState(State.IDLE, -1)
            return
        }

        if (now < cooldownUntil) {
            // 刚执行过跳过动作，给界面切换留时间
            publishState(if (mode == Mode.COUNTING) State.AD_COUNTDOWN else State.MONITORING, -1)
            return
        }

        // 广告可能渲染在独立窗口（浮层）里，遍历目标应用的所有窗口一起扫描
        handleInTarget(scanAllWindows(), now)
    }

    /** 合并扫描目标应用的所有窗口：rootInActiveWindow 不一定包含广告浮层所在的窗口 */
    private fun scanAllWindows(): ScanResult {
        val merged = ScanResult()
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                val r = w.root ?: continue
                val rp = r.packageName?.toString() ?: continue
                if (rp != packageName && rp in Prefs.getTargetPackages(this)) roots.add(r)
            }
        } catch (_: Exception) {
        }
        if (roots.isEmpty()) rootInActiveWindow?.let { roots.add(it) }
        for (r in roots) {
            val f = findAdSignature(r, WINDOW_NODE_LIMIT)
            if (merged.countdownSec < 0) {
                merged.countdownSec = f.countdownSec
                merged.countdownText = f.countdownText
            }
            if (!merged.adChip && f.adChip) {
                merged.adChip = true
                merged.adChipText = f.adChipText
            }
            if (!merged.unlocked && f.unlocked) {
                merged.unlocked = true
                merged.unlockText = f.unlockText
            }
            if (!merged.promoAd && f.promoAd) {
                merged.promoAd = true
                merged.promoAdText = f.promoAdText
            }
            if (!merged.sheet && f.sheet) merged.sheet = true
            if (!merged.lockedToast && f.lockedToast) merged.lockedToast = true
            if (!merged.mallPromoAd && f.mallPromoAd) merged.mallPromoAd = true
            merged.textNodeCount += f.textNodeCount
            if (merged.skipNode == null) merged.skipNode = f.skipNode
        }
        return merged
    }

    private class ScanResult {
        var countdownSec: Int = -1
        var countdownText: String? = null   // 触发倒计时识别的原始文字（诊断用）
        var countdownUnknown: Boolean = false // 只匹配到倒计时尾部文案（数字拆到别的节点且未配对到）：锁定但剩余秒数未知
        var adChip: Boolean = false
        var adChipText: String? = null      // 触发角标识别的原始文字（诊断用）
        var unlocked: Boolean = false
        var unlockText: String? = null      // 「上滑继续观看」类提示原文（诊断用）
        var promoAd: Boolean = false
        var promoAdText: String? = null     // 推广类广告特征原文（直播中/讲解中/点击进入游戏等）
        var sheet: Boolean = false          // 底部商品面板特征（查看详情等，决定上滑起手位置）
        var lockedToast: Boolean = false    // 红果提示「倒计时结束后即可滑动」（锁定期信号）
        var textNodeCount: Int = 0          // 屏幕内可读文字节点数（0=画面型广告特征）
        var mallPromoAd: Boolean = false    // 商城推广卡片广告特征（可读文字极少，含"商城好物省心选"）
        var skipNode: AccessibilityNodeInfo? = null
    }

    private fun findAdSignature(root: AccessibilityNodeInfo, nodeLimit: Int): ScanResult {
        // 可视范围以「窗口根节点的实际边界」为准：横屏旋转或视频窗口模式下
        // displayMetrics 可能滞后/与真实窗口不一致，导致倒计时文字被误判为
        // 屏幕外而漏检——漏检就会走兜底盲翻，触发红果锁定期提示
        val frame = Rect()
        try {
            root.getBoundsInScreen(frame)
        } catch (_: Exception) {
        }
        if (frame.isEmpty || frame.width() <= 0 || frame.height() <= 0) {
            val dm = resources.displayMetrics
            frame.set(0, 0, dm.widthPixels, dm.heightPixels)
        }
        val result = ScanResult()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        // 拆分布局配对用：倒计时剩余秒数节点 + 尾部文案节点（见下方组装逻辑）
        val numberCandidates = ArrayList<Pair<Int, Rect>>()
        var tailBounds: Rect? = null
        while (queue.isNotEmpty() && visited < nodeLimit) {
            val node = queue.removeFirst()
            visited++
            // 归一化特殊空白/全角字符，避免"显示一样却匹配不上"
            val text = normalizeText(node.text?.toString())
            val desc = normalizeText(node.contentDescription?.toString())

            if (text == null && desc == null) {
                collectChildren(node, queue)
                continue
            }
            // 铁律：只认屏幕上真实可见的节点。
            // 翻页容器里刚滑过去的页面仍留在视图树中（屏幕外），
            // 不过滤会把上一页的广告当成当前页的广告，造成连翻多页
            if (!isVisibleOnScreen(node, frame)) {
                collectChildren(node, queue)
                continue
            }
            result.textNodeCount++
            if (!result.mallPromoAd && (text?.contains("商城好物省心选") == true ||
                        desc?.contains("商城好物省心选") == true)
            ) {
                result.mallPromoAd = true
            }
            if (result.countdownSec < 0) {
                if (text != null) {
                    result.countdownSec = matchCountdown(text)
                    if (result.countdownSec >= 0) result.countdownText = text.take(24)
                }
                if (result.countdownSec < 0 && desc != null) {
                    result.countdownSec = matchCountdown(desc)
                    if (result.countdownSec >= 0) result.countdownText = desc.take(24)
                }
            }
            // 拆分布局采集：纯数字节点（「5」「5s」）与倒计时尾部文案节点（「后可继续上滑观看」）
            if (numberCandidates.size < 4 && text != null) {
                val nm = COUNTDOWN_NUMBER_REGEX.find(text)
                if (nm != null) {
                    val v = nm.groupValues[1].toIntOrNull()
                    if (v != null && v in 0..90) {
                        val r = Rect()
                        node.getBoundsInScreen(r)
                        numberCandidates.add(v to r)
                    }
                }
            }
            if (tailBounds == null) {
                val cand = text ?: desc
                if (cand != null && cand.length <= 16 && COUNTDOWN_TAIL_REGEX.containsMatchIn(cand)) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    tailBounds = r
                }
            }
            if (!result.adChip && (isAdLabel(text) || isAdLabel(desc))) {
                // 只有屏幕右上角的「广告」角标才算插播广告；
                // 首页推荐卡片、活动页中间的「广告」字样一律忽略，防止误判
                if (isInAdChipCorner(node, frame)) {
                    result.adChip = true
                    result.adChipText = (text ?: desc)?.take(16)
                }
            }
            if (!result.unlocked && (isUnlockHint(text) || isUnlockHint(desc))) {
                result.unlocked = true
                result.unlockText = (text ?: desc)?.take(16)
            }
            if (!result.promoAd && (isPromoAd(text) || isPromoAd(desc))) {
                result.promoAd = true
                result.promoAdText = (text ?: desc)?.take(16)
            }
            if (!result.sheet && (text?.contains("查看详情") == true || desc?.contains("查看详情") == true)) {
                result.sheet = true
            }
            if (!result.lockedToast && (text?.contains(LOCKED_TOAST_REGEX) == true ||
                        desc?.contains(LOCKED_TOAST_REGEX) == true)
            ) {
                result.lockedToast = true
            }
            if (result.skipNode == null && (isSkipText(text) || isSkipText(desc))) {
                result.skipNode = node.findClickableSelfOrParent()
            }
            collectChildren(node, queue)
        }
        // 组装：单节点都匹配不到时，把「数字节点 + 尾部文案节点」就近配对还原倒计时。
        // 仅在广告上下文（右上角角标/推广特征）内启用，避免剧集列表等页面的
        // 「数字 + 后…继续」文字被误组装成倒计时
        if (result.countdownSec < 0 && tailBounds != null &&
            (result.adChip || result.promoAd || result.mallPromoAd)
        ) {
            val tb = tailBounds!!
            var bestVal = -1
            var bestDist = Int.MAX_VALUE
            for ((v, r) in numberCandidates) {
                // 同一行的相邻控件：纵向几乎对齐、横向距离有限
                val dy = kotlin.math.abs(r.centerY() - tb.centerY())
                val dx = kotlin.math.abs(r.centerX() - tb.centerX())
                if (dy <= 90 && dx <= 900 && dx + dy < bestDist) {
                    bestDist = dx + dy
                    bestVal = v
                }
            }
            if (bestVal >= 0) {
                result.countdownSec = bestVal
                result.countdownText = "组装:$bestVal"
            } else {
                // 只读到尾部文案、配不到数字：仍然是在锁定期，但剩余秒数未知
                result.countdownUnknown = true
                result.countdownText = "尾部文案(秒数未知)"
            }
        }
        return result
    }

    private fun collectChildren(node: AccessibilityNodeInfo, queue: ArrayDeque<AccessibilityNodeInfo>) {
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { queue.add(it) }
        }
    }

    /** 节点中心点是否落在窗口可视范围内（屏幕外的翻页残留节点一律无视） */
    private fun isVisibleOnScreen(node: AccessibilityNodeInfo, frame: Rect): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        return frame.contains(r.centerX(), r.centerY())
    }

    /** 插播广告的「广告」角标固定在窗口右上角区域（顶部 30%、右半屏） */
    private fun isInAdChipCorner(node: AccessibilityNodeInfo, frame: Rect): Boolean {
        if (frame.width() <= 0 || frame.height() <= 0) return true
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        return r.top <= frame.top + frame.height() * 0.30f && r.left >= frame.left + frame.width() * 0.50f
    }

    // ------------------------------------------------------------------ 广告决策

    private fun handleInTarget(found: ScanResult, now: Long) {
        lastScan = found

        // 画面型广告兜底（可选功能，默认关闭）：
        // 屏幕连续读不到任何文字节点，且无任何已知广告特征时，判定为画面型广告并尝试上滑。
        // 限额：8 秒确认 + 5 分钟内最多 2 次，防止误翻连环。
        if (Prefs.isVisualAdFallbackEnabled(this)) {
            val emptyish = found.textNodeCount == 0 || found.mallPromoAd
            if (emptyish) {
                lastEmptySeenAt = now
                if (emptySinceAt == 0L) {
                    emptySinceAt = now
                    appendLog("屏幕几乎读不到文字，开始计时（5 秒后自动尝试上滑）")
                }
            } else if (now - lastEmptySeenAt > EMPTY_BLIP_GRACE_MS) {
                // 读到文字且持续超过宽限期，计时清零（避免界面重绘的瞬时节点打断判定）
                emptySinceAt = 0L
            }
            if (emptySinceAt != 0L) {
                val windowStart = now - EMPTY_FLIP_WINDOW_MS
                while (emptyFlipTimes.isNotEmpty() && emptyFlipTimes.first() < windowStart) {
                    emptyFlipTimes.removeFirst()
                }
                if (now - emptySinceAt >= EMPTY_TREE_CONFIRM_MS &&
                    emptyFlipTimes.size < EMPTY_FLIP_MAX &&
                    mode == Mode.IDLE
                ) {
                    emptyFlipTimes.addLast(now)
                    emptySinceAt = now
                    appendLog(
                        "屏幕几乎读不到文字，疑似画面型广告（" +
                                (if (found.mallPromoAd) "商城推广卡片" else "空屏") + "），尝试上滑"
                    )
                    attemptSkip(null, "画面型广告兜底", countdownVisible = false)
                    publishAfterDecision(now)
                    return
                }
            }
        }

        val hasCountdown = found.countdownSec >= 0 || found.countdownUnknown
        // 广告上下文：右上角「广告」角标，或推广类广告特征
        // （直播购物：直播中/讲解中/已售N件；游戏广告：点击进入游戏 等）
        val adContext = found.adChip || found.promoAd
        val adContextText = found.adChipText ?: found.promoAdText ?: "广告"
        val hasAd = hasCountdown || adContext || found.unlocked || found.lockedToast

        // 屏幕上既无倒计时也无「广告」角标：广告已结束（或本来就没有），复位
        if (!hasAd) {
            chipFirstSeenAt = 0L
            if (mode != Mode.IDLE) {
                if (now - lastAdSeenAt <= AD_BLINK_GRACE_MS) {
                    // 特征刚消失不足 0.8 秒：多半是界面重绘导致的闪断，
                    // 维持原计时宽限等待，避免重新确认 + 重新等待多花近 2 秒
                    publishState(if (mode == Mode.COUNTING) State.AD_COUNTDOWN else State.AD_FALLBACK, -1)
                    return
                }
                resetToIdle("广告已结束，恢复正常监测")
            }
            publishState(State.MONITORING, -1)
            return
        }
        lastAdSeenAt = now
        if (found.unlocked) lastUnlockSeenAt = now
        // 进入翻页流程后（FALLBACK）一律用「上部起手」：直播购物类整屏广告的
        // 下半屏常是商品面板/直播手势区，从下部起手容易被拦截；倒计时结束
        // 的翻页走 COUNTING 路径不受影响，保持原有的下部起手
        lastSheetLikely = found.sheet || found.promoAd || mode == Mode.FALLBACK

        // 红果提示「倒计时结束后即可滑动」= 仍在锁定期。
        // 屏幕上还有倒计时文字时：说明倒计时正在走，忽略该提示、按倒计时正常等待；
        // 没有倒计时文字时才把它当作「本波广告锁定」的证据：
        // 一旦被红果明确拒绝过，本波广告就不再做任何盲翻（否则会反复触发红果提示），
        // 只等正向信号——「上滑继续观看短剧」提示出现（可滑动）/「跳过」按钮可用/广告自然结束
        if (found.lockedToast && !hasCountdown) {
            if (mode == Mode.IDLE) {
                mode = Mode.FALLBACK
                if (fallbackSinceAt == 0L) fallbackSinceAt = now
                appendLog("红果提示仍在锁定期（倒计时结束后才可滑动），停止盲翻，等待可滑动信号")
            }
            lockToastSeenAt = now
            // 锁定期内不动作；唯一例外是同时出现了明确的可滑动信号（解锁提示），继续走后续判定
            if (!found.unlocked) {
                publishAfterDecision(now)
                return
            }
        }

        // 这波广告尝试太多次仍未翻过去：放弃，等广告自然结束，避免反复滑动干扰
        if (retryCount >= MAX_ATTEMPTS) {
            publishState(if (mode == Mode.COUNTING) State.AD_COUNTDOWN else State.AD_FALLBACK, -1)
            return
        }

        // 「跳过」按钮：屏幕上没有倒计时文字时才点（COUNTING 统一走倒计时结束路径）
        if (mode != Mode.COUNTING && found.skipNode != null && !hasCountdown) {
            attemptSkip(found.skipNode, "发现「跳过」按钮", countdownVisible = false)
            publishAfterDecision(now)
            return
        }

        when (mode) {
            Mode.IDLE -> {
                if (hasCountdown) {
                    enterCounting(now, found)
                } else if (adContext) {
                    // 无倒计时广告：右上角「广告」角标，或直播类购物广告（直播中/讲解中/已售N件）
                    if (chipFirstSeenAt == 0L) chipFirstSeenAt = now
                    if (found.unlocked) {
                        // 广告特征 + 「上滑继续看短剧」提示同时在场：最快路径，
                        // 下一轮扫描（约350ms）确认后立即上滑
                        mode = Mode.FALLBACK
                        if (fallbackSinceAt == 0L) fallbackSinceAt = now
                        deadlineAt = now
                        appendLog("检测到广告「$adContextText」+「${found.unlockText ?: "上滑继续观看"}」提示，准备立即上滑")
                    } else if (now - chipFirstSeenAt >= CHIP_CONFIRM_MS) {
                        mode = Mode.FALLBACK
                        if (fallbackSinceAt == 0L) fallbackSinceAt = now
                        // 从特征首次出现时刻起算等待：设置 1 秒 ≈ 广告出现 1 秒后翻页
                        val waitMs = Prefs.getFallbackWaitMs(this)
                        deadlineAt = chipFirstSeenAt + waitMs
                        if (deadlineAt <= now) deadlineAt = now + 200L
                        scheduleDeadlineKick(deadlineAt - now)
                        appendLog(
                            "检测到广告「$adContextText」（无倒计时），" +
                                    "${waitMs / 1000}s 后尝试上滑"
                        )
                    }
                } else if (found.unlocked) {
                    // 通用兜底信号：底部「上滑继续看短剧」提示持续出现即判定为广告。
                    // 部分广告的创意文字（直播中/点击进入游戏等）由广告SDK绘制、
                    // 读不到文字节点，这行提示往往是唯一可靠的信号；
                    // 闪断宽限：短暂读不到不清零，避免界面重绘打断连续计时
                    if (now - lastUnlockSeenAt > UNLOCK_BLINK_GRACE_MS) unlockSinceAt = now
                    if (now - unlockSinceAt >= HINT_ONLY_CONFIRM_MS) {
                        mode = Mode.FALLBACK
                        if (fallbackSinceAt == 0L) fallbackSinceAt = now
                        deadlineAt = now
                        appendLog("「${found.unlockText ?: "上滑继续看短剧"}」提示持续出现，判定为广告，准备上滑")
                    }
                }
                // 无任何广告特征时不动作：普通剧集页的滑动提示不是广告信号
            }

            Mode.COUNTING -> {
                if (hasCountdown) {
                    // 铁律：倒计时文字在屏幕上 = 只等待，绝不做任何动作
                    lastCountdownSeenAt = now
                    if (!countingUnknownSec) {
                        val candidate = computeDeadline(now, found.countdownSec)
                        if (now >= deadlineAt || candidate < deadlineAt) {
                            // 到点但文字仍在 = 计时偏早，以屏幕上的最新秒数顺延
                            deadlineAt = minOf(candidate, hardDeadlineAt)
                            scheduleDeadlineKick(deadlineAt - now)
                        }
                    }
                    if (now >= hardDeadlineAt) {
                        // 文字疑似卡死不动：硬超时兜底（唯一允许文字仍在时动作的情况）
                        attemptSkip(found.skipNode, "倒计时硬超时", countdownVisible = true)
                    }
                } else if (found.unlocked) {
                    // 倒计时文字已消失 + 红果给出「上滑继续观看」提示：立即上滑
                    attemptSkip(found.skipNode, "检测到「${found.unlockText ?: "上滑继续观看"}」提示", countdownVisible = false)
                } else if (adContext || now - lastCountdownSeenAt <= AD_GONE_GRACE_MS) {
                    // 倒计时文字已从屏幕消失 = 倒计时结束，按自身计时尽快上滑。
                    // 秒数未知的拆分布局：以文字消失为准立即上滑（延后 0.8s 防闪断误判）
                    val effectiveDeadline =
                        if (countingUnknownSec) maxOf(now, lastCountdownSeenAt + 800L) else deadlineAt
                    if (now >= effectiveDeadline) {
                        if (lockToastSeenAt == 0L) {
                            attemptSkip(found.skipNode, "倒计时结束", countdownVisible = false)
                        }
                        // 本波曾被红果拒绝过：不再盲翻，等「上滑继续观看」提示或广告结束
                    } else {
                        scheduleDeadlineKick(effectiveDeadline - now)
                    }
                } else {
                    // 倒计时和角标都不在且已超过宽限期：多半是用户自己划走了
                    resetToIdle("广告已消失，取消本次自动上滑")
                }
            }

            Mode.FALLBACK -> {
                if (hasCountdown) {
                    // 兜底等待期间屏幕上出现了倒计时：切回倒计时模式，结束前不动作
                    enterCounting(now, found)
                } else if (now - fallbackSinceAt >
                    (if (lockToastSeenAt != 0L) LOCKED_WAVE_LIMIT_MS else FALLBACK_TOTAL_LIMIT_MS)
                ) {
                    resetToIdle(
                        if (lockToastSeenAt != 0L) "锁定期广告等待可滑动信号超时（25s），已暂停，等广告自然结束"
                        else "本波广告自动跳过超时（45s），已暂停，等广告自然结束"
                    )
                } else if (found.unlocked) {
                    // 无倒计时广告的解锁提示：立即上滑
                    attemptSkip(found.skipNode, "检测到「${found.unlockText ?: "上滑继续观看"}」提示", countdownVisible = false)
                } else if (lockToastSeenAt == 0L && now >= deadlineAt) {
                    // 本次扫描已确认无倒计时文字，才执行兜底上滑。
                    // 若本波广告曾被红果明确拒绝（锁定期），一律不盲滑，避免反复顶撞提示
                    attemptSkip(found.skipNode, "广告等待超时，尝试上滑", countdownVisible = false)
                }
            }
        }

        publishAfterDecision(now)
    }

    /** 进入倒计时等待；倒计时期间的任何翻页动作都被 attemptSkip 的总闸拒绝 */
    private fun enterCounting(now: Long, found: ScanResult) {
        val first = mode != Mode.COUNTING
        mode = Mode.COUNTING
        countingUnknownSec = found.countdownUnknown && found.countdownSec < 0
        // 秒数未知时用 30s 作为调度估值；实际翻页以「文案从屏幕消失」为准（见 COUNTING 分支）
        val sec = if (countingUnknownSec) 30 else found.countdownSec
        deadlineAt = computeDeadline(now, sec)
        // 硬超时兜底：即使提示文字卡死不动，最多再多等 15 秒也强制执行
        hardDeadlineAt = now + (sec + 15) * 1000L + Prefs.getExtraDelayMs(this)
        lastCountdownSeenAt = now
        unlockSinceAt = 0L
        scheduleDeadlineKick(deadlineAt - now)
        if (first) {
            if (countingUnknownSec) {
                appendLog("识别到广告倒计时（拆分布局，剩余秒数未知），等提示文字消失后上滑")
            } else {
                appendLog("识别到广告，倒计时 ${found.countdownSec}s（触发文字：${found.countdownText ?: "?"}）")
            }
        }
    }

    private fun publishAfterDecision(now: Long) {
        when (mode) {
            Mode.COUNTING -> {
                if (countingUnknownSec) {
                    // 秒数未知：不显示具体倒计时数字，按"稍后自动上滑"提示
                    publishState(State.AD_FALLBACK, -1)
                } else {
                    val remain = ((deadlineAt - now) / 1000L).toInt() + 1
                    publishState(State.AD_COUNTDOWN, remain)
                }
            }
            Mode.FALLBACK -> {
                publishState(State.AD_FALLBACK, -1)
            }
            Mode.IDLE -> {
                publishState(State.MONITORING, -1)
            }
        }
    }

    private fun computeDeadline(now: Long, sec: Int): Long =
        now + sec * 1000L + Prefs.getExtraDelayMs(this) + DEADLINE_BUFFER_MS

    private fun probeUnknownApp(root: AccessibilityNodeInfo, pkg: String) {
        // 仅在日志中提示发现同类广告样式的应用；不再自动加入监测列表
        // （曾因误加其他应用导致"打开红果短剧"提示未安装、并误监测无关应用）
        if (pkg == packageName) return
        val found = findAdSignature(root, PROBE_NODE_LIMIT)
        if (found.countdownSec >= 0 && lastProbeNoticePkg != pkg) {
            lastProbeNoticePkg = pkg
            appendLog("提示：在 $pkg 中发现同类广告样式（未加入监测）")
        }
    }

    // ------------------------------------------------------------------ 执行跳过

    /**
     * 执行一次跳过尝试：优先点「跳过」按钮，否则上滑。
     * 成功与否下次扫描自见分晓：广告特征还在就由 FALLBACK 模式按 RETRY_GAP 重试。
     */
    private fun attemptSkip(skipNode: AccessibilityNodeInfo?, reason: String, countdownVisible: Boolean) {
        // 总闸（铁律）：本轮扫描屏幕上还有倒计时文字时，除倒计时自己的
        // 结束/硬超时外一律拒绝。任何路径都无法在倒计时结束前翻页或点击
        if (countdownVisible && !reason.startsWith("倒计时")) {
            appendLog("倒计时文字仍在屏幕上，已拒绝动作（$reason）")
            return
        }
        retryCount++
        cooldownUntil = SystemClock.elapsedRealtime() + AFTER_SWIPE_COOLDOWN_MS

        var clicked = false
        if (skipNode != null) {
            clicked = try {
                skipNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (e: Exception) {
                false
            }
        }
        if (clicked) {
            appendLog("$reason：已点击「跳过」（第 $retryCount 次尝试）✓")
        } else {
            swipeUp(reason)
        }

        if (retryCount >= MAX_ATTEMPTS) {
            appendLog("连续 ${MAX_ATTEMPTS} 次仍未翻过广告，暂停尝试，等广告自然结束")
        }

        // 无论点还是滑，之后都进入 FALLBACK 节奏：广告特征若还在，隔 RETRY_GAP 再试
        mode = Mode.FALLBACK
        if (fallbackSinceAt == 0L) {
            fallbackSinceAt = SystemClock.elapsedRealtime()
        }
        deadlineAt = SystemClock.elapsedRealtime() + RETRY_GAP_MS
        scheduleDeadlineKick(RETRY_GAP_MS)
        val n = skipCount.value + 1
        skipCount.value = n
        Prefs.setSkipCount(this, n)
    }

    private fun swipeUp(reason: String) {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        if (w <= 0f || h <= 0f) return
        val portrait = h >= w
        // 有底部商品面板（直播购物广告等）或已重试过的：改从画面上部起手，
        // 避免手势被商品面板拦截；首次尝试保持下部起手（常规广告最稳）
        val useUpper = lastSheetLikely || retryCount >= 2
        val startY: Float
        val endY: Float
        val durationMs: Long
        if (portrait) {
            if (useUpper) {
                startY = h * SWIPE_UPPER_START_PORTRAIT
                endY = h * SWIPE_UPPER_END_PORTRAIT
                durationMs = SWIPE_UPPER_DURATION_MS
            } else {
                startY = h * SWIPE_START_FRACTION_PORTRAIT
                endY = h * SWIPE_END_FRACTION_PORTRAIT
                durationMs = SWIPE_DURATION_MS
            }
        } else {
            if (useUpper) {
                startY = h * SWIPE_UPPER_START_LANDSCAPE
                endY = h * SWIPE_UPPER_END_LANDSCAPE
                durationMs = SWIPE_UPPER_DURATION_MS
            } else {
                startY = h * SWIPE_START_FRACTION_LANDSCAPE
                endY = h * SWIPE_END_FRACTION_LANDSCAPE
                durationMs = SWIPE_DURATION_MS
            }
        }

        val path = Path().apply {
            moveTo(w / 2f, startY)
            lineTo(w / 2f, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                appendLog("$reason：已自动上滑 ✓（第 $retryCount 次尝试·${if (useUpper) "上部起手" else "下部起手"}）")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                appendLog("上滑手势被系统取消（可能手指正触屏），稍后自动重试")
            }
        }, null)
        if (!dispatched) appendLog("上滑手势派发失败")
    }

    // ------------------------------------------------------------------ 一键反馈

    /** 反馈触发来源：决定提交结果提示的措辞（应用内按钮 vs 红果内悬浮按钮） */
    enum class FeedbackSource { OVERLAY, IN_APP }

    private var feedbackSource = FeedbackSource.OVERLAY

    /** 悬浮按钮轻点回调：抓取当前页面截图 + 诊断信息，生成反馈并提交 */
    fun onFeedbackTapped(source: FeedbackSource = FeedbackSource.OVERLAY): Boolean {
        val now = SystemClock.elapsedRealtime()
        // 冷却 8 秒 + 进行中保护：防止误触导致反复提交、反复弹窗
        if (feedbackBusy || now - lastReportAt < 8000L) return false
        lastReportAt = now
        feedbackBusy = true
        feedbackSource = source
        mainHandler.post { captureReport() }
        return true
    }

    private fun captureReport() {
        FeedbackOverlay.setAlpha(0f)
        appendLog("收到一键反馈，正在抓取当前页面…")
        // 兜底：无论截图回调是否返回，6 秒后解除占用并恢复按钮
        mainHandler.postDelayed({
            if (feedbackBusy) {
                feedbackBusy = false
                FeedbackOverlay.setAlpha(1f)
                appendLog("反馈抓取超时，已取消")
            }
        }, 6000L)
        mainHandler.postDelayed({
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        ContextCompat.getMainExecutor(this),
                        object : AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                                var soft: Bitmap? = null
                                try {
                                    soft = Bitmap.wrapHardwareBuffer(
                                        screenshot.hardwareBuffer,
                                        screenshot.colorSpace
                                    )?.copy(Bitmap.Config.ARGB_8888, false)
                                } catch (_: Exception) {
                                } finally {
                                    try {
                                        screenshot.hardwareBuffer.close()
                                    } catch (_: Exception) {
                                    }
                                }
                                finishReport(soft)
                            }

                            override fun onFailure(errorCode: Int) {
                                appendLog("截图失败（错误码 $errorCode），改为仅提交日志")
                                finishReport(null)
                            }
                        }
                    )
                } catch (e: Exception) {
                    appendLog("截图异常：${e.message}")
                    finishReport(null)
                }
            } else {
                // Android 11 以下：用 MediaProjection 常驻捕捉取最新一帧
                //（需用户在设置页授权一次，之后全程静默）
                Thread {
                    val bmp = ScreenCapture.captureLatest()
                    mainHandler.post {
                        if (bmp == null) {
                            appendLog(
                                if (!ScreenCapture.isReady)
                                    "屏幕截图未授权：请在 App 设置里点「开启屏幕截图」（本次反馈不带截图）"
                                else
                                    "屏幕截图已授权但取帧失败：${ScreenCapture.lastError ?: "未知原因"}（本次反馈不带截图）"
                            )
                        }
                        finishReport(bmp)
                    }
                }.start()
            }
        }, 200L)
    }

    private fun finishReport(bmp: Bitmap?) {
        FeedbackOverlay.setAlpha(1f)
        feedbackBusy = false
        try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val dir = File(getExternalFilesDir(null), "feedback").apply { mkdirs() }
            val txtFile = File(dir, "反馈_$ts.txt")
            txtFile.writeText(buildReportText())
            var imgFile: File? = null
            if (bmp != null) {
                val f = File(dir, "反馈_$ts.png")
                try {
                    FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 92, it) }
                    imgFile = f
                } catch (_: Exception) {
                }
            }
            appendLog("反馈已生成：${txtFile.name}${if (imgFile != null) "（含截图）" else "（无截图）"}")
            // 静默上传（云端服务器，任意网络可用）；
            // 本 App 不含任何分享面板代码，永远不会弹出分享窗口。
            // 提示措辞按触发来源区分：应用内按钮 / 红果内悬浮按钮（普通用户日志已隐藏，
            // 提示必须自解释，不能再引导用户"看日志"）
            val fromApp = feedbackSource == FeedbackSource.IN_APP
            uploadWithFallback(imgFile, txtFile) { ok ->
                FeedbackOverlay.flashResult(ok)
                if (ok) {
                    val msg = when {
                        fromApp && imgFile != null -> getString(R.string.report_ok_app)
                        fromApp -> getString(R.string.report_ok_app_noshot)
                        imgFile != null -> getString(R.string.report_ok_overlay)
                        else -> getString(R.string.report_ok_overlay_noshot)
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                } else {
                    appendLog("上传失败，10 秒后自动重试")
                    mainHandler.postDelayed({
                        uploadWithFallback(imgFile, txtFile) { ok2 ->
                            Toast.makeText(
                                this,
                                if (ok2) {
                                    getString(if (fromApp) R.string.report_ok_app else R.string.report_ok_overlay)
                                } else {
                                    getString(if (fromApp) R.string.report_fail_app else R.string.report_fail_overlay)
                                },
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }, 10000L)
                }
            }
        } catch (e: Exception) {
            appendLog("反馈生成失败：${e.message}")
        }
    }

    /**
     * 上传优先级：配置地址（默认云端）→ 电脑有线/无线局域网地址 → 局域网扫描。
     * 云端(https)地址具有「粘性」：局域网兜底成功也不会覆盖它，
     * 避免在家连一次电脑、出门后地址失效的问题。
     */
    private fun uploadWithFallback(img: File?, txt: File, onResult: (Boolean) -> Unit) {
        val configured = Prefs.getFeedbackUrl(this)
        val cloudSticky = configured.startsWith("https://")
        val candidates = linkedSetOf(
            configured,
            "http://192.168.5.2:8765/report",   // 电脑有线（家里局域网备用）
            "http://192.168.5.10:8765/report"   // 电脑无线（备用）
        ).filter { it.isNotBlank() }
        fun tryNext(list: List<String>) {
            if (list.isEmpty()) {
                // 已知地址全部不通：扫描局域网自动寻找接收服务（应对电脑 IP 变化）
                appendLog("已知地址均不通，开始扫描局域网寻找接收服务…")
                LanDiscovery.discover { found ->
                    if (found != null) {
                        if (cloudSticky) {
                            appendLog("局域网发现接收服务：$found（仅本次使用，不覆盖云端地址）")
                        } else {
                            appendLog("局域网发现接收服务：$found（已保存）")
                            Prefs.setFeedbackUrl(this, found)
                        }
                        uploadReport(found, img, txt) { ok -> onResult(ok) }
                    } else {
                        appendLog("局域网未发现接收服务")
                        onResult(false)
                    }
                }
                return
            }
            uploadReport(list.first(), img, txt) { ok ->
                if (ok) {
                    onResult(true)
                } else {
                    appendLog("地址 ${list.first()} 不通，自动尝试下一个")
                    tryNext(list.drop(1))
                }
            }
        }
        tryNext(candidates)
    }

    /** 生成反馈文本：设备信息 + 识别快照 + 屏幕可见文字 + 最近日志 */
    private fun buildReportText(): String {
        val sb = StringBuilder()
        sb.appendLine("== 红果广告跳过 · 问题反馈 ==")
        sb.appendLine("来源: " + if (feedbackSource == FeedbackSource.IN_APP) "应用内按钮" else "红果内悬浮按钮")
        sb.appendLine("时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
        sb.appendLine("版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        val dm = resources.displayMetrics
        sb.appendLine("屏幕: ${dm.widthPixels}x${dm.heightPixels} ${if (dm.heightPixels >= dm.widthPixels) "竖屏" else "横屏"}")
        sb.appendLine("前台应用: ${lastForegroundPackage ?: "?"}")
        sb.appendLine("监测列表: ${Prefs.getTargetPackages(this).joinToString("、")}")
        sb.appendLine("画面兜底: ${if (Prefs.isVisualAdFallbackEnabled(this)) "开启" else "关闭"}")
        sb.appendLine("当前模式: $mode ｜ 本波尝试次数: $retryCount ｜ 累计跳过: ${skipCount.value}")
        val s = lastScan
        if (s != null) {
            sb.appendLine(
                "识别快照: 倒计时=${if (s.countdownSec >= 0) "${s.countdownSec}秒(${s.countdownText})" else "无"}" +
                        " ｜ 右上角角标=${if (s.adChip) "有(${s.adChipText})" else "无"}" +
                        " ｜ 解锁提示=${if (s.unlocked) "有(${s.unlockText})" else "无"}"
            )
            sb.appendLine(
                "          推广特征=${if (s.promoAd) "有(${s.promoAdText})" else "无"}" +
                        " ｜ 商品面板=${if (s.sheet) "有" else "无"}" +
                        " ｜ 锁定提示=${if (s.lockedToast) "有" else "无"}"
            )
        } else {
            sb.appendLine("识别快照: （尚未产生扫描数据）")
        }
        sb.appendLine("-- 屏幕可见文字（供分析识别特征）--")
        val texts = dumpVisibleTexts(40)
        if (texts.isEmpty()) sb.appendLine("（读不到任何文字节点）") else texts.forEach { sb.appendLine(it) }
        sb.appendLine("-- 最近运行日志 --")
        logs.value.takeLast(60).reversed().forEach { sb.appendLine(it) }
        return sb.toString()
    }

    /** 抓取当前屏幕上所有可见的短文字（诊断广告界面到底有哪些文字可读） */
    private fun dumpVisibleTexts(limit: Int): List<String> {
        val out = LinkedHashSet<String>()
        try {
            val dm = resources.displayMetrics
            val w = dm.widthPixels
            val h = dm.heightPixels
            val roots = ArrayList<AccessibilityNodeInfo>()
            try {
                for (win in windows) {
                    val r = win.root ?: continue
                    if (r.packageName?.toString() in Prefs.getTargetPackages(this)) roots.add(r)
                }
            } catch (_: Exception) {
            }
            if (roots.isEmpty()) rootInActiveWindow?.let { roots.add(it) }
            for (r in roots) {
                // 可视范围同样以窗口根节点边界为准（与广告扫描保持一致）
                val frame = android.graphics.Rect()
                try {
                    r.getBoundsInScreen(frame)
                } catch (_: Exception) {
                }
                if (frame.isEmpty || frame.width() <= 0 || frame.height() <= 0) {
                    frame.set(0, 0, w, h)
                }
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.add(r)
                var visited = 0
                while (queue.isNotEmpty() && visited < 900 && out.size < limit) {
                    val n = queue.removeFirst()
                    visited++
                    val t = n.text?.toString() ?: n.contentDescription?.toString()
                    if (!t.isNullOrBlank() && t.length <= 24 && isVisibleOnScreen(n, frame)) {
                        out.add(t)
                    }
                    for (i in 0 until n.childCount) {
                        n.getChild(i)?.let { queue.add(it) }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out.toList()
    }

    /** 可选：把反馈 POST 到自定义服务器（multipart：report 文本 + screenshot 图片） */
    private fun uploadReport(url: String, img: File?, txt: File, onResult: (Boolean) -> Unit) {
        Thread {
            val ok = try {
                val boundary = "----hongguo" + System.currentTimeMillis()
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 4000
                    readTimeout = 30000
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    // 已登录账号时关联报告来源（未登录则匿名，同样可用）
                    val tok = Prefs.getAuthToken(this@AdSkipService)
                    if (tok.isNotBlank()) setRequestProperty("X-Auth-Token", tok)
                }
                DataOutputStream(conn.outputStream).use { out ->
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"report\"\r\n\r\n")
                    out.write(txt.readText().toByteArray(Charsets.UTF_8))
                    out.writeBytes("\r\n")
                    if (img != null && img.exists()) {
                        out.writeBytes("--$boundary\r\n")
                        out.writeBytes("Content-Disposition: form-data; name=\"screenshot\"; filename=\"${img.name}\"\r\n")
                        out.writeBytes("Content-Type: image/png\r\n\r\n")
                        img.inputStream().use { it.copyTo(out) }
                        out.writeBytes("\r\n")
                    }
                    out.writeBytes("--$boundary--\r\n")
                }
                val code = conn.responseCode
                conn.disconnect()
                mainHandler.post { appendLog("反馈上传完成（HTTP $code）") }
                code in 200..299
            } catch (e: Exception) {
                mainHandler.post { appendLog("反馈上传失败：${e.message}") }
                false
            }
            mainHandler.post { onResult(ok) }
        }.start()
    }

    private fun resetToIdle(msg: String? = null) {
        if (msg != null) appendLog(msg)
        mode = Mode.IDLE
        deadlineAt = 0L
        hardDeadlineAt = 0L
        fallbackSinceAt = 0L
        retryCount = 0
        chipFirstSeenAt = 0L
        unlockSinceAt = 0L
        emptySinceAt = 0L
        lockToastSeenAt = 0L
        countingUnknownSec = false
    }

    // ------------------------------------------------------------------ 文字特征

    private fun matchCountdown(s: String): Int {
        COUNTDOWN_REGEX.find(s)?.let {
            val sec = it.groupValues[1].toIntOrNull() ?: -1
            if (sec in 0..600) return sec
        }
        CHIP_COUNTDOWN_REGEX.find(s)?.let {
            val sec = it.groupValues[1].toIntOrNull() ?: -1
            if (sec in 0..90) return sec
        }
        return -1
    }

    /**
     * 文案归一化：全角数字/字母、各类 Unicode 空格（含不换行空格 U+00A0）、繁体「後」
     * 统一成常规字符后再匹配。广告 SDK 的排版常混用这些字符，正则里的 \s 与 ASCII
     * 数字无法覆盖，会导致倒计时文案"看起来一样却匹配不上"。
     */
    private fun normalizeText(s: String?): String? {
        if (s == null) return null
        var changed = false
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch in '０'..'９' -> { sb.append('0' + (ch - '０')); changed = true }
                ch == 'ｓ' -> { sb.append('s'); changed = true }
                ch == 'Ｓ' -> { sb.append('S'); changed = true }
                ch == '後' -> { sb.append('后'); changed = true }
                ch == '\u00A0' || ch == '\u3000' || ch.code == 0x202F || ch.code == 0x205F ||
                        ch.code in 0x2000..0x200A -> { sb.append(' '); changed = true }
                else -> sb.append(ch)
            }
        }
        return if (changed) sb.toString() else s
    }

    private fun isAdLabel(s: String?): Boolean {
        if (s == null) return false
        return s == AD_LABEL || (s.startsWith(AD_LABEL) && s.length <= AD_LABEL.length + 6)
    }

    private fun isSkipText(s: String?): Boolean {
        if (s == null || !s.contains("跳过") || s.length > 12) return false
        // 「跳过 5」这类带倒数数字的按钮尚未可用：不点，等数字归零变成纯「跳过」再点
        return !SKIP_WITH_COUNTDOWN_REGEX.containsMatchIn(s)
    }

    private fun isUnlockHint(s: String?): Boolean {
        if (s == null || s.length > 20) return false
        return UNLOCK_HINT_REGEX.containsMatchIn(s)
    }

    private fun isPromoAd(s: String?): Boolean {
        if (s == null || s.length > 40) return false
        return LIVE_AD_REGEX.containsMatchIn(s) || GAME_AD_REGEX.containsMatchIn(s)
    }

    private fun AccessibilityNodeInfo.findClickableSelfOrParent(): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = this
        var depth = 0
        while (n != null && depth < 5) {
            if (n.isClickable) return n
            n = n.parent
            depth++
        }
        return null
    }

    // ------------------------------------------------------------------ 状态与日志

    private fun publishState(s: State, remain: Int) {
        if (state.value != s) state.value = s
        if (countdownRemain.value != remain) countdownRemain.value = remain
    }

    private fun appendLog(msg: String) {
        val line = tsFormat.format(Date()) + "  " + msg
        logs.value = (logs.value + line).takeLast(120)
    }
}
