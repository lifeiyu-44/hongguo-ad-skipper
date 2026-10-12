package com.hongguo.adskip

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * App 内升级：
 * 从电脑端接收服务（局域网，速度快）读取最新版本号与安装包，
 * 有新版本时提示一键更新——自动下载 + 拉起系统安装界面，
 * 无需再手动传安装包。
 */
object UpdateChecker {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var checking = false

    /** 从上报地址推导服务根地址：https://ip:8443/report -> https://ip:8443（保留原协议） */
    fun baseUrl(ctx: Context): String? {
        return try {
            val u = URL(Prefs.getFeedbackUrl(ctx))
            "${u.protocol}://${u.host}${if (u.port == -1) "" else ":${u.port}"}"
        } catch (_: Exception) {
            null
        }
    }

    /** 版本号比较：a(1.22) > b(1.21) 返回 true */
    fun isNewer(a: String, b: String): Boolean {
        val pa = a.trim().removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        val pb = b.trim().removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * 检查更新。
     * showDialogWhenFound=false 且提供 onUpdateAvailable 时：发现新版本只回调（不弹窗），
     * 由界面自行以非弹窗方式提示（如按钮文案）。
     */
    fun check(ctx: Context, silent: Boolean, onUpdateAvailable: ((String) -> Unit)? = null) {
        if (checking) return
        checking = true
        val base = baseUrl(ctx)
        if (base == null) {
            checking = false
            if (!silent && onUpdateAvailable == null) toast(ctx, "上报地址无效，无法检查更新")
            return
        }
        Thread {
            var remote: String? = null
            try {
                val conn = (URL("$base/ping").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                remote = JSONObject(body).optString("version").ifBlank { null }
            } catch (_: Exception) {
            }
            val current = BuildConfig.VERSION_NAME
            val found = remote != null && isNewer(remote, current)
            mainHandler.post {
                checking = false
                when {
                    found && onUpdateAvailable != null -> onUpdateAvailable(remote!!)
                    found -> showUpdateDialog(ctx, remote!!, base)
                    !silent -> toast(ctx, "已是最新版本（$current）")
                }
            }
        }.start()
    }

    /** 供通知栏等入口直接下载最新版（点击通知时调用） */
    fun downloadLatest(ctx: Context) {
        val base = baseUrl(ctx) ?: run {
            toast(ctx, "上报地址无效，无法下载更新")
            return
        }
        downloadAndInstall(ctx, "$base/update/apk")
    }

    private fun showUpdateDialog(ctx: Context, version: String, base: String) {
        try {
            val dlg = android.app.AlertDialog.Builder(ctx)
                .setTitle("发现新版本 v$version")
                .setMessage("从云端服务器直接下载安装，确认更新？")
                .setPositiveButton("立即更新") { _, _ -> downloadAndInstall(ctx, "$base/update/apk") }
                .setNegativeButton("稍后", null)
                .create()
            dlg.show()
        } catch (_: Exception) {
            // 服务上下文弹窗失败时忽略（下次打开 App 时会在界面里提示）
        }
    }

    private fun downloadAndInstall(ctx: Context, apkUrl: String) {
        toast(ctx, "正在下载新版本…")
        Thread {
            try {
                val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
                val apk = File(dir, "HongguoAdSkipper.apk")
                val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 60000
                }
                conn.inputStream.use { input ->
                    FileOutputStream(apk).use { output -> input.copyTo(output) }
                }
                conn.disconnect()
                mainHandler.post {
                    toast(ctx, "下载完成，请在系统界面点「安装」")
                    installApk(ctx, apk)
                }
            } catch (e: Exception) {
                mainHandler.post { toast(ctx, "下载失败：${e.message}") }
            }
        }.start()
    }

    private fun installApk(ctx: Context, apk: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(ctx, "com.hongguo.adskip.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            toast(ctx, "拉起安装失败：${e.message}（可在文件管理器中手动安装）")
        }
    }

    private fun toast(ctx: Context, msg: String) {
        Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_LONG).show()
    }
}
