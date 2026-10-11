package com.hongguo.adskip

import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * 局域网自动发现：扫描本机所在网段（/24）中开放 8765 端口的主机，
 * 并用 /ping 接口确认是否为「红果广告跳过」接收服务，
 * 从而在电脑 IP 变化时自动找到正确的反馈上报地址。
 */
object LanDiscovery {

    private const val PORT = 8765
    private const val CONNECT_TIMEOUT_MS = 350
    private const val READ_TIMEOUT_MS = 1200
    private const val MAX_THREADS = 48
    private const val PING_MARK = "hongguo-ad-skipper"

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 异步扫描，回调在主线程；找到返回形如 http://192.168.x.x:8765/report 的地址 */
    fun discover(onResult: (String?) -> Unit) {
        Thread {
            val found = scanOnce()
            mainHandler.post { onResult(found) }
        }.start()
    }

    private fun scanOnce(): String? {
        val subnets = localSubnets()
        if (subnets.isEmpty()) return null
        val result = AtomicReference<String?>(null)
        val pool = Executors.newFixedThreadPool(MAX_THREADS)
        try {
            for (prefix in subnets) {
                for (host in 1..254) {
                    if (result.get() != null) break
                    val ip = "$prefix$host"
                    pool.execute {
                        if (result.get() != null) return@execute
                        if (verify(ip)) {
                            result.compareAndSet(null, "http://$ip:$PORT/report")
                        }
                    }
                }
            }
            // 等待扫描收敛（最多约 6 秒）
            val deadline = System.currentTimeMillis() + 6000
            while (result.get() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(120)
            }
        } catch (_: Exception) {
        } finally {
            pool.shutdownNow()
        }
        return result.get()
    }

    /** 本机所在的 /24 网段前缀列表（如 192.168.5.） */
    private fun localSubnets(): List<String> {
        val out = LinkedHashSet<String>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    val ip = addr.hostAddress ?: continue
                    if (addr.isLoopbackAddress || !addr.isSiteLocalAddress) continue
                    // 仅 IPv4
                    if (ip.count { it == '.' } != 3) continue
                    val parts = ip.split(".")
                    out.add("${parts[0]}.${parts[1]}.${parts[2]}.")
                }
            }
        } catch (_: Exception) {
        }
        return out.toList()
    }

    /** 端口 + /ping 身份双重确认，避免误认其他服务的 8765 端口 */
    private fun verify(ip: String): Boolean {
        // 第一步：端口是否开放
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, PORT), CONNECT_TIMEOUT_MS)
            }
        } catch (_: Exception) {
            return false
        }
        // 第二步：确认是本项目服务
        return try {
            val conn = (URL("http://$ip:$PORT/ping").openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            body.contains(PING_MARK)
        } catch (_: Exception) {
            false
        }
    }
}
