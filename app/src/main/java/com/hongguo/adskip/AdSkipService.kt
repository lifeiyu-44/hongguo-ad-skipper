package com.hongguo.adskip

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 核心无障碍服务，工作流程：
 * 1. 定时检查前台窗口，判断是否为目标 App（红果短剧，包名 com.phoenix.read）
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
         * 倒计时提示特征，匹配如「3秒后可继续上滑观看短剧」。
         * 若红果短剧更新后文案变化导致识别失效，改这一行即可。
         */
        val COUNTDOWN_REGEX = Regex("""(\d+)\s*秒后[^0-9]{0,10}?(上滑|滑动|继续|观看)""")

        /** 「广告」角标上带单位的剩余秒数（备用特征），如「广告 15s」 */
        val CHIP_COUNTDOWN_REGEX = Regex("""广告\s*(\d{1,3})\s*[sS秒](?![a-zA-Z])""")

        /** 「跳过 5」这类带倒数数字的跳过按钮：数字未归零前不可用，不点击 */
        val SKIP_WITH_COUNTDOWN_REGEX = Regex("""跳过\s*\d+""")

        private const val AD_LABEL = "广告"

        // ---- 扫描节奏 ----
        private const val POLL_INTERVAL_MS = 350L
        private const val FULL_NODE_LIMIT = 900      // 目标应用内全量扫描的节点上限
        private const val PROBE_NODE_LIMIT = 260     // 非目标应用的轻量探测上限
        private const val PROBE_EVERY_N_TICKS = 3    // 非目标应用降低探测频率

        // ---- 跳过时机 ----
        private const val DEADLINE_BUFFER_MS = 350L       // 倒计时归零后等 UI 稳定
        private const val AFTER_SWIPE_COOLDOWN_MS = 2600L // 跳过尝试后的冷却
        private const val AD_GONE_GRACE_MS = 2000L        // 倒计时文字消失多久视为广告已不在
        private const val CHIP_CONFIRM_MS = 2000L         // 「广告」角标需持续出现才认定插播广告，防误判
        private const val MAX_ATTEMPTS = 4                // 同一波广告最多尝试次数（防反复滑动）
        private const val RETRY_GAP_MS = 2500L            // 重试间隔
        private const val FALLBACK_TOTAL_LIMIT_MS = 45000L // 一波广告自动跳过的总时长上限，超时放弃

        // ---- 上滑手势（按屏幕宽高比例计算，手机/平板/横竖屏通用）----
        // 距离拉到约 6 成屏高、速度加快，保证位移过半必翻页；翻不动由重试兜底
        private const val SWIPE_DURATION_MS = 220L
        private const val SWIPE_START_FRACTION_PORTRAIT = 0.80f
        private const val SWIPE_END_FRACTION_PORTRAIT = 0.16f
        private const val SWIPE_START_FRACTION_LANDSCAPE = 0.78f
        private const val SWIPE_END_FRACTION_LANDSCAPE = 0.18f

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

        fun clearLogs() {
            logs.value = emptyList()
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
    private var fallbackSinceAt = 0L       // 本波广告兜底流程的开始时间（总时长上限用）
    private var retryCount = 0             // 当前这波广告已尝试次数
    private var probeTick = 0
    private var offlineLogged = false      // 断网提示只记一次，避免日志刷屏

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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        running.value = true
        skipCount.value = Prefs.getSkipCount(this)
        appendLog("服务已启动，开始监测")
        mainHandler.post(ticker)
    }

    override fun onDestroy() {
        instance = null
        running.value = false
        state.value = State.IDLE
        countdownRemain.value = -1
        mode = Mode.IDLE
        mainHandler.removeCallbacks(ticker)
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

        if (!Prefs.isAutoSwipeEnabled(this)) {
            if (mode != Mode.IDLE) resetToIdle()
            publishState(State.IDLE, -1)
            return
        }

        val inTarget = pkg in Prefs.getTargetPackages(this)
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

        handleInTarget(findAdSignature(root, FULL_NODE_LIMIT), now)
    }

    private class ScanResult {
        var countdownSec: Int = -1
        var countdownText: String? = null   // 触发倒计时识别的原始文字（诊断用）
        var adChip: Boolean = false
        var adChipText: String? = null      // 触发角标识别的原始文字（诊断用）
        var skipNode: AccessibilityNodeInfo? = null
    }

    private fun findAdSignature(root: AccessibilityNodeInfo, nodeLimit: Int): ScanResult {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val result = ScanResult()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < nodeLimit) {
            val node = queue.removeFirst()
            visited++
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()

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
            if (!result.adChip && (isAdLabel(text) || isAdLabel(desc))) {
                // 只有屏幕右上角的「广告」角标才算插播广告；
                // 首页推荐卡片、活动页中间的「广告」字样一律忽略，防止误判
                if (isInAdChipCorner(node, screenW, screenH)) {
                    result.adChip = true
                    result.adChipText = (text ?: desc)?.take(16)
                }
            }
            if (result.skipNode == null && (isSkipText(text) || isSkipText(desc))) {
                result.skipNode = node.findClickableSelfOrParent()
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return result
    }

    /** 插播广告的「广告」角标固定在屏幕右上角区域（顶部 30%、右半屏） */
    private fun isInAdChipCorner(node: AccessibilityNodeInfo, screenW: Int, screenH: Int): Boolean {
        if (screenW <= 0 || screenH <= 0) return true
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        return r.top <= screenH * 0.30f && r.left >= screenW * 0.50f
    }

    // ------------------------------------------------------------------ 广告决策

    private fun handleInTarget(found: ScanResult, now: Long) {
        val hasCountdown = found.countdownSec >= 0
        val hasAd = hasCountdown || found.adChip

        // 屏幕上既无倒计时也无「广告」角标：广告已结束（或本来就没有），复位
        if (!hasAd) {
            chipFirstSeenAt = 0L
            if (mode != Mode.IDLE) resetToIdle("广告已结束，恢复正常监测")
            publishState(State.MONITORING, -1)
            return
        }

        // 这波广告尝试太多次仍未翻过去：放弃，等广告自然结束，避免反复滑动干扰
        if (retryCount >= MAX_ATTEMPTS) {
            publishState(if (mode == Mode.COUNTING) State.AD_COUNTDOWN else State.AD_FALLBACK, -1)
            return
        }

        // 「跳过」按钮出现即点击，不等倒计时（很多无倒计时广告带跳过按钮）
        if (found.skipNode != null) {
            attemptSkip(found.skipNode, "发现「跳过」按钮")
            publishAfterDecision(now)
            return
        }

        when (mode) {
            Mode.IDLE -> {
                if (hasCountdown) {
                    mode = Mode.COUNTING
                    deadlineAt = computeDeadline(now, found.countdownSec)
                    // 硬超时兜底：即使提示文字卡死不动，最多再多等 15 秒也强制执行
                    hardDeadlineAt = now + (found.countdownSec + 15) * 1000L +
                            Prefs.getExtraDelayMs(this)
                    lastCountdownSeenAt = now
                    appendLog("识别到广告，倒计时 ${found.countdownSec}s（触发文字：${found.countdownText ?: "?"}）")
                } else if (found.adChip) {
                    // 无倒计时的广告：角标需持续出现一小段时间才认定，防止瞬时文字误判
                    if (chipFirstSeenAt == 0L) chipFirstSeenAt = now
                    if (now - chipFirstSeenAt >= CHIP_CONFIRM_MS) {
                        mode = Mode.FALLBACK
                        if (fallbackSinceAt == 0L) fallbackSinceAt = now
                        val waitMs = Prefs.getFallbackWaitMs(this)
                        deadlineAt = now + waitMs
                        appendLog(
                            "检测到广告角标「${found.adChipText ?: "广告"}」（右上角，无倒计时），" +
                                    "${waitMs / 1000}s 后尝试上滑"
                        )
                    }
                }
            }

            Mode.COUNTING -> {
                if (hasCountdown) {
                    lastCountdownSeenAt = now
                    val candidate = computeDeadline(now, found.countdownSec)
                    if (now >= deadlineAt || candidate < deadlineAt) {
                        // 到点但文字仍在 = 计时偏早，允许推迟；
                        // 滑动前以屏幕上的最新倒计时为准，避免抢跑
                        deadlineAt = minOf(candidate, hardDeadlineAt)
                    }
                } else if (!found.adChip && now - lastCountdownSeenAt > AD_GONE_GRACE_MS) {
                    // 倒计时和角标都不在且已消失超过 2 秒：多半是用户自己划走了
                    resetToIdle("广告已消失，取消本次自动上滑")
                }
                if (mode == Mode.COUNTING && now >= deadlineAt) {
                    if (!hasCountdown) {
                        // 滑前校验通过：倒计时文字确实已从屏幕上消失
                        attemptSkip(found.skipNode, "倒计时结束")
                    } else if (now >= hardDeadlineAt) {
                        // 文字疑似卡死，硬超时兜底
                        attemptSkip(found.skipNode, "倒计时硬超时")
                    }
                    // 其余情况：deadline 已被推迟，继续等下一轮扫描
                }
            }

            Mode.FALLBACK -> {
                if (hasCountdown) {
                    // 兜底等待期间来了个带倒计时的新广告：切回倒计时模式
                    mode = Mode.COUNTING
                    deadlineAt = computeDeadline(now, found.countdownSec)
                    hardDeadlineAt = now + (found.countdownSec + 15) * 1000L +
                            Prefs.getExtraDelayMs(this)
                    lastCountdownSeenAt = now
                    appendLog("广告出现倒计时 ${found.countdownSec}s，改为倒计时结束后上滑")
                } else if (now - fallbackSinceAt > FALLBACK_TOTAL_LIMIT_MS) {
                    resetToIdle("本波广告自动跳过超时（45s），已暂停，等广告自然结束")
                } else if (now >= deadlineAt) {
                    // 本次扫描确认角标仍在，才执行重试滑动
                    attemptSkip(null, "广告等待超时，尝试上滑")
                }
            }
        }

        publishAfterDecision(now)
    }

    private fun publishAfterDecision(now: Long) {
        when (mode) {
            Mode.COUNTING -> {
                val remain = ((deadlineAt - now) / 1000L).toInt() + 1
                publishState(State.AD_COUNTDOWN, remain)
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
        val found = findAdSignature(root, PROBE_NODE_LIMIT)
        if (found.countdownSec >= 0 && pkg !in Prefs.getTargetPackages(this)) {
            Prefs.setTargetPackages(this, Prefs.getTargetPackages(this) + pkg)
            appendLog("在 $pkg 中发现同类广告样式，已自动加入监测")
        }
    }

    // ------------------------------------------------------------------ 执行跳过

    /**
     * 执行一次跳过尝试：优先点「跳过」按钮，否则上滑。
     * 成功与否下次扫描自见分晓：广告特征还在就由 FALLBACK 模式按 RETRY_GAP 重试。
     */
    private fun attemptSkip(skipNode: AccessibilityNodeInfo?, reason: String) {
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
        val startY = h * (if (portrait) SWIPE_START_FRACTION_PORTRAIT else SWIPE_START_FRACTION_LANDSCAPE)
        val endY = h * (if (portrait) SWIPE_END_FRACTION_PORTRAIT else SWIPE_END_FRACTION_LANDSCAPE)

        val path = Path().apply {
            moveTo(w / 2f, startY)
            lineTo(w / 2f, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, SWIPE_DURATION_MS))
            .build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                appendLog("$reason：已自动上滑 ✓（第 $retryCount 次尝试）")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                appendLog("上滑手势被系统取消（可能手指正触屏），稍后自动重试")
            }
        }, null)
        if (!dispatched) appendLog("上滑手势派发失败")
    }

    private fun resetToIdle(msg: String? = null) {
        if (msg != null) appendLog(msg)
        mode = Mode.IDLE
        deadlineAt = 0L
        hardDeadlineAt = 0L
        fallbackSinceAt = 0L
        retryCount = 0
        chipFirstSeenAt = 0L
    }

    // ------------------------------------------------------------------ 文字特征

    private fun matchCountdown(s: String): Int {
        COUNTDOWN_REGEX.find(s)?.let { return it.groupValues[1].toIntOrNull() ?: -1 }
        CHIP_COUNTDOWN_REGEX.find(s)?.let { return it.groupValues[1].toIntOrNull() ?: -1 }
        return -1
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
