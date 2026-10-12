package com.hongguo.adskip

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云端账号接口（可选功能，不注册不影响任何使用）。
 * 注册仅限 App 端（请求头 X-Client: app），且必须邮箱验证码；
 * 登录后反馈上报自动携带令牌，后台可看到报告来自哪个邮箱。
 */
object AccountApi {

    data class Result(val ok: Boolean, val token: String? = null, val error: String? = null)

    /** 从上报地址推导服务根地址（与更新检查同一规则） */
    private fun baseUrl(ctx: Context): String? {
        return try {
            val u = URL(Prefs.getFeedbackUrl(ctx))
            "${u.protocol}://${u.host}${if (u.port == -1) "" else ":${u.port}"}"
        } catch (_: Exception) {
            null
        }
    }

    /** 发送注册验证码 */
    fun sendCode(ctx: Context, email: String): Result = post(ctx, "/api/auth/code") {
        it.put("email", email)
    }

    fun login(ctx: Context, email: String, password: String): Result = post(ctx, "/api/auth/login") {
        it.put("email", email).put("password", password)
    }

    fun register(ctx: Context, email: String, password: String, code: String): Result =
        post(ctx, "/api/auth/register") {
            it.put("email", email).put("password", password).put("code", code)
        }

    private fun post(ctx: Context, path: String, build: (JSONObject) -> Unit): Result {
        val base = baseUrl(ctx) ?: return Result(false, error = "invalid_url")
        return try {
            val body = JSONObject().also(build).toString().toByteArray(Charsets.UTF_8)
            val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 6000
                readTimeout = 10000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("X-Client", "app")
                val tok = Prefs.getAuthToken(ctx)
                if (tok.isNotBlank()) setRequestProperty("Authorization", "Bearer $tok")
            }
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            val json = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
            if (json.optBoolean("ok")) Result(true, token = json.optString("token").ifBlank { null })
            else Result(false, error = json.optString("error").ifBlank { "http_$code" })
        } catch (e: Exception) {
            Result(false, error = e.message ?: "network_error")
        }
    }
}
