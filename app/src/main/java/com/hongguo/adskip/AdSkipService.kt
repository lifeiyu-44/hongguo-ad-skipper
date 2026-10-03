package com.hongguo.adskip

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
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
 * 2. 遍历视图树，查找广告特征文字：「广告」角标 + 「N秒后可继续上滑观看短剧」倒计时
 * 3. 记录倒计时截止时间；倒计时归零后模拟一次上滑手势，进入下一集
 *
 * 所有识别基于无障碍节点文字，在本机完成，不联网。
 */
class AdSkipService : AccessibilityService() {

    enum class State { IDLE, MONITORING, AD_COUNTDOWN }

    companion object {
        /**
         * 倒计时提示特征，匹配如「3秒后可继续上滑观看短剧」。
         * 若红果短剧更新后文案变化导致识别失效，改这一行即可。
         */
        val COUNTDOWN_REGEX = Regex("""(\d+)\s*秒后[^0-9]{0,10}?(上滑|滑动|继续|观看)""")

        /** 「广告」角标上带单位的剩余秒数（备用特征），如「广告 15s」 */
        val CHIP_COUNTDOWN_REGEX = Regex("""广告\s*(\d{1,3})\s*[sS秒](?![a-zA-Z])""")

        private const val AD_LABEL = "广告"

        // ---- 扫描节奏 ----
        private const val POLL_INTERVAL_MS = 350L
        private const val FULL_NODE_LIMIT = 900      // 目标应用内全量扫描的节点上限
        private const val PROBE_NODE_LIMIT = 260     // 非目标应用的轻量探测上限
        private const val PROBE_EVERY_N_TICKS = 3    // 非目标应用降低探测频率

        // ---- 跳过时机 ----
        private const val DEADLINE_BUFFER_MS = 350L       // 倒计时归零后等 UI 稳定
        private const val AFTER_SWIPE_COOLDOWN_MS = 2600L // 上滑后的冷却，防止连续误触
        private const val AD_GONE_GRACE_MS = 2000L        // 倒计时文字消失多久后视为广告已不在

        // ---- 上滑手势（按屏幕宽高比例计算，手机/平板/横竖屏通用） ----
        private const val SWIPE_DURATION_MS = 300L
        private const val SWIPE_START_FRACTION_PORTRAIT = 0.74f
        private const val SWIPE_END_FRACTION_PORTRAIT = 0.30f
        private const val SWIPE_START_FRACTION_LANDSCAPE = 0.76f
        private const val SWIPE_END_FRACTION_LANDSCAPE = 0.38f

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

    private enum class Mode { IDLE, COUNTING }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val tsFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private var mode = Mode.IDLE
    private var deadlineAt = 0L            // elapsedRealtime 时刻的上滑截止点
    private var lastCountdownSeenAt = 0L
    private var cooldownUntil = 0L
    private var probeTick = 0

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
        OverlayController.update(this, null)
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

    private fun scanOnce() {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        val now = SystemClock.elapsedRealtime()

        if (!Prefs.isAutoSwipeEnabled(this)) {
            if (mode != Mode.IDLE) resetToIdle()
            publishState(State.IDLE, -1)
            OverlayController.update(this, null)
            return
        }

        val inTarget = pkg in Prefs.getTargetPackages(this)
        if (!inTarget) {
            // 非目标应用：低频轻量探测；若发现同样的倒计时文案，自动把该包名加入监测
            probeTick++
            if (probeTick % PROBE_EVERY_N_TICKS == 0) probeUnknownApp(root, pkg)
            if (mode != Mode.IDLE) resetToIdle("已离开播放页，取消本次自动上滑")
            publishState(State.IDLE, -1)
            OverlayController.update(this, null)
            return
        }

        if (now < cooldownUntil) {
            // 刚执行过上滑，给界面切换留时间
            publishState(State.MONITORING, -1)
            return
        }

        handleInTarget(findAdSignature(root, FULL_NODE_LIMIT), now)
    }

    private class ScanResult {
        var countdownSec: Int = -1
        var adChip: Boolean = false
        var skipNode: AccessibilityNodeInfo? = null
    }

    private fun findAdSignature(root: AccessibilityNodeInfo, nodeLimit: Int): ScanResult {
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
                if (text != null) result.countdownSec = matchCountdown(text)
                if (result.countdownSec < 0 && desc != null) result.countdownSec = matchCountdown(desc)
            }
            if (!result.adChip && (isAdLabel(text) || isAdLabel(desc))) {
                result.adChip = true
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

    private fun handleInTarget(found: ScanResult, now: Long) {
        if (mode == Mode.IDLE) {
            if (found.countdownSec >= 0) {
                mode = Mode.COUNTING
                deadlineAt = now + found.countdownSec * 1000L +
                        Prefs.getExtraDelayMs(this) + DEADLINE_BUFFER_MS
                lastCountdownSeenAt = now
                appendLog("识别到广告，倒计时 ${found.countdownSec}s，结束后自动上滑")
            }
        } else {
            if (found.countdownSec >= 0) {
                lastCountdownSeenAt = now
                // 按屏幕上的最新秒数不断校正截止时间，防止计时漂移
                val candidate = now + found.countdownSec * 1000L +
                        Prefs.getExtraDelayMs(this) + DEADLINE_BUFFER_MS
                if (candidate < deadlineAt) deadlineAt = candidate
            } else if (now - lastCountdownSeenAt > AD_GONE_GRACE_MS && now < deadlineAt) {
                // 倒计时文字消失且广告角标也不在，多半是用户自己划走了
                resetToIdle("广告已消失，取消本次自动上滑")
            }

            if (mode == Mode.COUNTING && now >= deadlineAt) {
                performSkip(found.skipNode)
            }
        }

        if (mode == Mode.COUNTING) {
            val remain = ((deadlineAt - now) / 1000L).toInt() + 1
            publishState(State.AD_COUNTDOWN, remain)
            OverlayController.update(this, "广告倒计时 ${remain}s，将自动上滑")
        } else {
            publishState(State.MONITORING, -1)
            OverlayController.update(this, null)
        }
    }

    private fun probeUnknownApp(root: AccessibilityNodeInfo, pkg: String) {
        val found = findAdSignature(root, PROBE_NODE_LIMIT)
        if (found.countdownSec >= 0 && pkg !in Prefs.getTargetPackages(this)) {
            Prefs.setTargetPackages(this, Prefs.getTargetPackages(this) + pkg)
            appendLog("在 $pkg 中发现同类广告样式，已自动加入监测")
        }
    }

    // ------------------------------------------------------------------ 执行跳过

    private fun performSkip(skipNode: AccessibilityNodeInfo?) {
        mode = Mode.IDLE
        deadlineAt = 0L
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
            appendLog("倒计时结束：已点击「跳过」按钮 ✓")
            OverlayController.update(this, "已点击跳过 ✓")
        } else {
            swipeUp("倒计时结束")
        }

        val n = skipCount.value + 1
        skipCount.value = n
        Prefs.setSkipCount(this, n)
        mainHandler.postDelayed({
            OverlayController.update(this@AdSkipService, null)
        }, 1800L)
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
                appendLog("$reason：已自动上滑 ✓")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                appendLog("上滑手势被系统取消（可能手指正触屏），下个周期重试")
            }
        }, null)
        if (!dispatched) appendLog("上滑手势派发失败")
        OverlayController.update(this, "已自动上滑 ✓")
    }

    private fun resetToIdle(msg: String? = null) {
        if (msg != null) appendLog(msg)
        mode = Mode.IDLE
        deadlineAt = 0L
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

    private fun isSkipText(s: String?): Boolean =
        s != null && s.contains("跳过") && s.length <= 12

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
