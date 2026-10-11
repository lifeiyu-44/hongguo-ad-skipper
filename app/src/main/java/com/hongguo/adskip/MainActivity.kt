package com.hongguo.adskip

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    // Android 11 以下的屏幕截图授权（MediaProjection，授权一次即可常驻）
    private val captureAuthLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                // Android 10+ 系统强制要求：先启动 mediaProjection 前台服务，再建立捕捉
                try {
                    ContextCompat.startForegroundService(
                        this, Intent(this, CaptureService::class.java)
                    )
                } catch (_: Exception) {
                }
                val data = result.data!!
                val resultCode = result.resultCode
                Handler(Looper.getMainLooper()).postDelayed({
                    val ok = ScreenCapture.start(this, resultCode, data)
                    AdSkipService.appendLogExternal(
                        "屏幕截图授权结果: " +
                                if (ok) "成功，已就绪" else "失败：" + (ScreenCapture.lastError ?: "未知原因")
                    )
                    Toast.makeText(
                        this,
                        if (ok) {
                            getString(R.string.capture_auth_ok)
                        } else {
                            getString(
                                R.string.capture_start_fail_fmt,
                                ScreenCapture.lastError ?: "未知原因"
                            )
                        },
                        Toast.LENGTH_LONG
                    ).show()
                    refreshCaptureButton(findViewById(R.id.btnCaptureAuth))
                }, 500)
            } else {
                AdSkipService.appendLogExternal("屏幕截图授权被取消（未确认系统弹窗）")
                Toast.makeText(this, R.string.capture_auth_cancel, Toast.LENGTH_LONG).show()
                refreshCaptureButton(findViewById(R.id.btnCaptureAuth))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusPill = findViewById<TextView>(R.id.statusPill)
        val stateText = findViewById<TextView>(R.id.stateText)
        val countText = findViewById<TextView>(R.id.countText)
        val btnAccessibility = findViewById<Button>(R.id.btnAccessibility)
        val btnOpenTarget = findViewById<Button>(R.id.btnOpenTarget)
        val btnTestSwipe = findViewById<Button>(R.id.btnTestSwipe)
        val btnResetTargets = findViewById<Button>(R.id.btnResetTargets)
        val switchAuto = findViewById<MaterialSwitch>(R.id.switchAuto)
        val switchFeedback = findViewById<MaterialSwitch>(R.id.switchFeedback)
        val switchVisualFallback = findViewById<MaterialSwitch>(R.id.switchVisualFallback)
        val editFeedbackUrl = findViewById<EditText>(R.id.editFeedbackUrl)
        val btnSaveFeedbackUrl = findViewById<Button>(R.id.btnSaveFeedbackUrl)
        val btnDiscover = findViewById<Button>(R.id.btnDiscover)
        val btnCaptureAuth = findViewById<Button>(R.id.btnCaptureAuth)
        val btnCheckUpdate = findViewById<Button>(R.id.btnCheckUpdate)
        btnCaptureAuth.setOnClickListener {
            if (ScreenCapture.isReady) {
                // 已授权：当场测试截图
                Toast.makeText(this, "正在测试截图…", Toast.LENGTH_SHORT).show()
                Thread {
                    val bmp = ScreenCapture.captureLatest()
                    val err = ScreenCapture.lastError
                    runOnUiThread {
                        if (bmp != null) {
                            bmp.recycle()
                            Toast.makeText(this, R.string.capture_test_ok, Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(
                                this,
                                getString(R.string.capture_test_fail_fmt, err ?: "未知原因"),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }.start()
            } else {
                launchCaptureAuth()
            }
        }
        refreshCaptureButton(btnCaptureAuth)

        // Android 11 以下且未授权时，打开 App 主动引导一次
        if (ScreenCapture.needed() && !ScreenCapture.isReady) {
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.capture_guide_title)
                .setMessage(R.string.capture_guide_msg)
                .setPositiveButton(R.string.capture_guide_ok) { _, _ -> launchCaptureAuth() }
                .setNegativeButton(R.string.capture_guide_later, null)
                .show()
        }

        btnCheckUpdate.setOnClickListener { UpdateChecker.check(this, silent = false) }
        val delayBar = findViewById<SeekBar>(R.id.delayBar)
        val delayLabel = findViewById<TextView>(R.id.delayLabel)
        val fallbackBar = findViewById<SeekBar>(R.id.fallbackBar)
        val fallbackLabel = findViewById<TextView>(R.id.fallbackLabel)
        val targetsText = findViewById<TextView>(R.id.targetsText)
        val logView = findViewById<TextView>(R.id.logView)
        val btnClearLog = findViewById<Button>(R.id.btnClearLog)
        val btnShareLog = findViewById<Button>(R.id.btnShareLog)

        switchAuto.isChecked = Prefs.isAutoSwipeEnabled(this)
        switchAuto.setOnCheckedChangeListener { _, checked ->
            Prefs.setAutoSwipeEnabled(this, checked)
        }

        switchFeedback.isChecked = Prefs.isFeedbackButtonEnabled(this)
        switchFeedback.setOnCheckedChangeListener { _, checked ->
            Prefs.setFeedbackButtonEnabled(this, checked)
        }

        switchVisualFallback.isChecked = Prefs.isVisualAdFallbackEnabled(this)
        switchVisualFallback.setOnCheckedChangeListener { _, checked ->
            Prefs.setVisualAdFallbackEnabled(this, checked)
        }

        editFeedbackUrl.setText(Prefs.getFeedbackUrl(this))
        btnSaveFeedbackUrl.setOnClickListener {
            Prefs.setFeedbackUrl(this, editFeedbackUrl.text.toString().trim())
            Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
        }
        btnDiscover.setOnClickListener {
            btnDiscover.isEnabled = false
            Toast.makeText(this, R.string.discover_scanning, Toast.LENGTH_SHORT).show()
            LanDiscovery.discover { url ->
                btnDiscover.isEnabled = true
                if (url != null) {
                    Prefs.setFeedbackUrl(this, url)
                    editFeedbackUrl.setText(url)
                    Toast.makeText(
                        this,
                        getString(R.string.discover_found_fmt, url),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this, R.string.discover_notfound, Toast.LENGTH_LONG).show()
                }
            }
        }

        delayBar.progress = Prefs.getExtraDelayMs(this)
        delayLabel.text = getString(R.string.delay_value, Prefs.getExtraDelayMs(this))
        delayBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                delayLabel.text = getString(R.string.delay_value, snapDelay(value))
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                val v = snapDelay(sb?.progress ?: 0)
                Prefs.setExtraDelayMs(this@MainActivity, v)
                delayLabel.text = getString(R.string.delay_value, v)
            }
        })

        fallbackBar.max = 27000 // 1s ~ 28s
        fallbackBar.progress = (Prefs.getFallbackWaitMs(this) - 1000).coerceIn(0, 27000)
        fallbackLabel.text = getString(R.string.fallback_value, Prefs.getFallbackWaitMs(this) / 1000)
        fallbackBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                fallbackLabel.text = getString(R.string.fallback_value, snapFallbackSec(value))
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                val v = snapFallbackSec(sb?.progress ?: 0) * 1000
                Prefs.setFallbackWaitMs(this@MainActivity, v)
                fallbackLabel.text = getString(R.string.fallback_value, v / 1000)
            }
        })

        refreshTargetsText(targetsText)
        btnResetTargets.setOnClickListener {
            Prefs.setTargetPackages(this, setOf(Prefs.DEFAULT_TARGET))
            refreshTargetsText(targetsText)
            Toast.makeText(this, R.string.targets_literal, Toast.LENGTH_SHORT).show()
        }

        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnOpenTarget.setOnClickListener { openTargetApp() }
        btnTestSwipe.setOnClickListener {
            if (!AdSkipService.requestTestSwipe()) {
                Toast.makeText(this, R.string.toast_service_not_running, Toast.LENGTH_SHORT).show()
            }
        }
        btnClearLog.setOnClickListener { AdSkipService.clearLogs() }
        btnShareLog.setOnClickListener {
            val text = AdSkipService.logs.value.joinToString("\n")
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "红果广告跳过-运行日志")
                putExtra(Intent.EXTRA_TEXT, text.ifEmpty { "（暂无日志）" })
            }
            startActivity(Intent.createChooser(send, getString(R.string.btn_share_log)))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AdSkipService.running.collect { up ->
                        statusPill.text = getString(if (up) R.string.status_on else R.string.status_off)
                        statusPill.setBackgroundResource(
                            if (up) R.drawable.bg_pill_on else R.drawable.bg_pill_off
                        )
                        btnTestSwipe.isEnabled = up
                        btnAccessibility.text = getString(
                            if (up) R.string.btn_accessibility_on else R.string.btn_accessibility_off
                        )
                    }
                }
                launch {
                    AdSkipService.state.collect { st ->
                        when (st) {
                            AdSkipService.State.MONITORING -> stateText.setText(R.string.state_monitoring)
                            AdSkipService.State.AD_FALLBACK -> stateText.setText(R.string.state_fallback)
                            AdSkipService.State.IDLE -> stateText.setText(R.string.state_idle)
                            // 倒计时秒数由 countdownRemain 渲染
                            AdSkipService.State.AD_COUNTDOWN -> Unit
                        }
                    }
                }
                launch {
                    AdSkipService.countdownRemain.collect { remain ->
                        if (remain >= 0) {
                            stateText.text = getString(R.string.state_countdown_fmt, remain)
                        }
                    }
                }
                launch {
                    AdSkipService.skipCount.collect {
                        countText.text = getString(R.string.count_text, it)
                    }
                }
                launch {
                    AdSkipService.logs.collect { list ->
                        logView.text = list.takeLast(14).reversed().joinToString("\n")
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTargetsText(findViewById(R.id.targetsText))
        refreshCaptureButton(findViewById(R.id.btnCaptureAuth))
        // 启动/回到前台时静默检查一次更新（发现新版本才弹提示）
        UpdateChecker.check(this, silent = true)
    }

    private fun launchCaptureAuth() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureAuthLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun refreshCaptureButton(btn: Button) {
        btn.visibility = View.VISIBLE
        if (!ScreenCapture.needed()) {
            // Android 11+：系统免授权截图，无需任何设置
            btn.text = getString(R.string.capture_not_needed)
            btn.isEnabled = false
            return
        }
        btn.isEnabled = true
        btn.text = getString(
            if (ScreenCapture.isReady) R.string.capture_ready_test else R.string.btn_capture_auth
        )
    }

    private fun snapDelay(value: Int): Int = (value / 50) * 50

    private fun snapFallbackSec(progress: Int): Int = ((progress + 1000) / 1000).coerceIn(1, 28)

    private fun refreshTargetsText(tv: TextView) {
        val pkgs = Prefs.getTargetPackages(this).sorted().joinToString("、")
        tv.text = getString(R.string.targets_value, pkgs)
    }

    private fun openTargetApp() {
        // 永远优先打开红果官方包（监测列表可能含历史遗留项，绝不能取"第一个"）
        val targets = linkedSetOf(Prefs.DEFAULT_TARGET).apply {
            addAll(Prefs.getTargetPackages(this@MainActivity))
        }
        for (pkg in targets) {
            if (launchPackage(pkg)) return
        }
        Toast.makeText(
            this,
            getString(R.string.toast_target_missing, Prefs.DEFAULT_TARGET),
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * 双保险拉起：
     * ① 标准入口（getLaunchIntentForPackage）；
     * ② 失败则用"显式包名直启"（不依赖包可见性查询，绕过部分新系统的限制）。
     */
    private fun launchPackage(pkg: String): Boolean {
        try {
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                startActivity(it)
                return true
            }
        } catch (_: Exception) {
        }
        return try {
            startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        } catch (_: Exception) {
            false
        }
    }
}
