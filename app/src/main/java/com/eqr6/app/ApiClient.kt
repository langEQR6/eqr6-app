package com.eqr6.app

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors

/**
 * Talks to the EQR6 toolbox API.
 *
 * ADDRESS SELECTION
 * -----------------
 * The server is reachable two ways depending on where the phone is:
 *   at home        -> 192.168.3.11:8080   (LAN, fastest)
 *   anywhere else  -> 100.77.117.76:8080  (Tailscale)
 *
 * A user override wins over both. Each candidate is probed with a short TCP
 * connect so an unreachable address costs ~2.5 s instead of a full timeout.
 *
 * AUTHENTICATION
 * --------------
 * Every call carries X-API-Key. The key is generated on the server
 * (api-key.txt) and entered by the user in Settings. The firewall additionally
 * restricts port 8080 to the local subnet and the Tailscale range.
 */
object ApiClient {

    const val DEFAULT_BASE = "http://100.77.117.76:8080"

    /**
     * Tailscale first: it works both at home (direct LAN path over the tunnel)
     * and anywhere else. The plain LAN address only helps at home, so trying it
     * first made every request from outside wait for a failed probe.
     */
    private val CANDIDATES = listOf(
        "http://100.77.117.76:8080",
        "http://192.168.3.11:8080"
    )

    private const val PROBE_TIMEOUT_MS = 2500
    private const val CONNECT_TIMEOUT_MS = 10000
    private const val READ_TIMEOUT_MS = 30000

    private val io = Executors.newCachedThreadPool()

    sealed class Result {
        data class Ok(val json: JSONObject, val base: String) : Result()
        data class Err(val message: String) : Result()
    }

    /** Fire a callback on the UI thread. Kept tiny so no AndroidX is needed. */
    private fun onUi(action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(action)
    }

    // ------------------------------------------------------------------
    // address resolution
    // ------------------------------------------------------------------

    private fun probe(base: String): Boolean {
        return try {
            val u = URL(base)
            val port = if (u.port > 0) u.port else if (u.protocol == "https") 443 else 80
            Socket().use { s ->
                s.connect(InetSocketAddress(u.host, port), PROBE_TIMEOUT_MS)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Bases to try, in order. An override replaces the list entirely. */
    fun candidateBases(context: Context): List<String> {
        val saved = context.getSharedPreferences("eqr6", Context.MODE_PRIVATE)
            .getString("api_base_override", null)
        val fromPrefs = Prefs.apiBase(context)
        // Prefs.apiBase already falls back to DEFAULT_BASE; treat that as "auto"
        // unless the user explicitly typed something.
        return if (fromPrefs != DEFAULT_BASE) listOf(fromPrefs) else CANDIDATES
    }

    // ------------------------------------------------------------------
    // calls
    // ------------------------------------------------------------------

    /**
     * GET a toolbox endpoint. `path` starts with /api/.
     * Tries each candidate base until one answers.
     */
    fun get(context: Context, path: String, callback: (Result) -> Unit) {
        val key = Prefs.apiKey(context)
        if (key.isBlank()) {
            callback(Result.Err("尚未设置 API 密钥，请到「设置」填写"))
            return
        }
        val bases = candidateBases(context)
        io.execute {
            val notes = StringBuilder()
            for (base in bases) {
                if (!probe(base)) {
                    notes.append(hostOf(base)).append(": 无法连接\n")
                    continue
                }
                try {
                    val body = request("GET", base + path, key, null)
                    onUi { callback(Result.Ok(JSONObject(body), base)) }
                    return@execute
                } catch (e: Exception) {
                    notes.append(hostOf(base)).append(": ").append(e.message ?: "错误").append('\n')
                }
            }
            val msg = notes.toString().trim().ifBlank { "没有可用的服务器地址" }
            onUi { callback(Result.Err(msg)) }
        }
    }

    /** POST with an optional JSON body. */
    fun post(context: Context, path: String, jsonBody: JSONObject?, callback: (Result) -> Unit) {
        val key = Prefs.apiKey(context)
        if (key.isBlank()) {
            callback(Result.Err("尚未设置 API 密钥"))
            return
        }
        val bases = candidateBases(context)
        io.execute {
            val notes = StringBuilder()
            for (base in bases) {
                if (!probe(base)) {
                    notes.append(hostOf(base)).append(": 无法连接\n")
                    continue
                }
                try {
                    val body = request("POST", base + path, key, jsonBody?.toString() ?: "{}")
                    onUi { callback(Result.Ok(JSONObject(body), base)) }
                    return@execute
                } catch (e: Exception) {
                    notes.append(hostOf(base)).append(": ").append(e.message ?: "错误").append('\n')
                }
            }
            val msg = notes.toString().trim().ifBlank { "没有可用的服务器地址" }
            onUi { callback(Result.Err(msg)) }
        }
    }

    private fun request(method: String, url: String, apiKey: String, body: String?): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("X-API-Key", apiKey)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "EQR6-App")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            connect()
        }
        try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = if (stream == null) "" else
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) {
                // the API answers with JSON even on errors
                if (text.contains("\"error\"")) throw IllegalStateException("HTTP $code")
                throw IllegalStateException("HTTP $code")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun hostOf(base: String): String = try {
        URL(base).host
    } catch (e: Exception) {
        base
    }

    /** Quick reachability test used by the Settings "test connection" button. */
    fun testConnection(context: Context, callback: (Boolean, String) -> Unit) {
        val key = Prefs.apiKey(context)
        if (key.isBlank()) {
            callback(false, "尚未填写 API 密钥")
            return
        }
        val bases = candidateBases(context)
        io.execute {
            for (base in bases) {
                if (!probe(base)) continue
                try {
                    val started = System.currentTimeMillis()
                    val body = request("GET", "$base/api/ping", key, null)
                    val ms = System.currentTimeMillis() - started
                    if (body.contains("\"ok\"")) {
                        onUi { callback(true, "连接成功 · 延迟 ${ms}ms · ${hostOf(base)}") }
                        return@execute
                    }
                } catch (e: Exception) {
                    // try the next candidate
                }
            }
            onUi { callback(false, "连接失败：请检查 Tailscale 是否开启、地址与密钥是否正确") }
        }
    }
}
