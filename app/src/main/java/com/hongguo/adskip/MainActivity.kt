package com.hongguo.adskip

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusPill = findViewById<TextView>(R.id.statusPill)
        val stateText = findViewById<TextView>(R.id.stateText)
        val countText = findViewById<TextView>(R.id.countText)
        val btnAccessibility = findViewById<Button>(R.id.btnAccessibility)
        val btnOpenTarget = findViewById<Button>(R.id.btnOpenTarget)
        val btnTestSwipe = findViewById<Button>(R.id.btnTestSwipe)
        val btnOverlayPermission = findViewById<Button>(R.id.btnOverlayPermission)
        val btnResetTargets = findViewById<Button>(R.id.btnResetTargets)
        val switchAuto = findViewById<MaterialSwitch>(R.id.switchAuto)
        val switchOverlay = findViewById<MaterialSwitch>(R.id.switchOverlay)
        val delayBar = findViewById<SeekBar>(R.id.delayBar)
        val delayLabel = findViewById<TextView>(R.id.delayLabel)
        val targetsText = findViewById<TextView>(R.id.targetsText)
        val logView = findViewById<TextView>(R.id.logView)
        val btnClearLog = findViewById<Button>(R.id.btnClearLog)

        switchAuto.isChecked = Prefs.isAutoSwipeEnabled(this)
        switchAuto.setOnCheckedChangeListener { _, checked ->
            Prefs.setAutoSwipeEnabled(this, checked)
        }

        switchOverlay.isChecked = Prefs.isOverlayEnabled(this)
        switchOverlay.setOnCheckedChangeListener { _, checked ->
            Prefs.setOverlayEnabled(this, checked)
            refreshOverlayPermissionButton(btnOverlayPermission)
            if (checked && !Settings.canDrawOverlays(this)) requestOverlayPermission()
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
        btnOverlayPermission.setOnClickListener { requestOverlayPermission() }
        btnClearLog.setOnClickListener { AdSkipService.clearLogs() }

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
        refreshOverlayPermissionButton(findViewById(R.id.btnOverlayPermission))
        refreshTargetsText(findViewById(R.id.targetsText))
    }

    private fun snapDelay(value: Int): Int = (value / 50) * 50

    private fun refreshOverlayPermissionButton(btn: Button) {
        btn.visibility = if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
    }

    private fun refreshTargetsText(tv: TextView) {
        val pkgs = Prefs.getTargetPackages(this).sorted().joinToString("、")
        tv.text = getString(R.string.targets_value, pkgs)
    }

    private fun requestOverlayPermission() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun openTargetApp() {
        val pkg = Prefs.getTargetPackages(this).firstOrNull() ?: Prefs.DEFAULT_TARGET
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, getString(R.string.toast_target_missing, pkg), Toast.LENGTH_LONG).show()
        }
    }
}
