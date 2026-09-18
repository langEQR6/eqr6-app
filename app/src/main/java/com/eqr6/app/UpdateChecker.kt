package com.eqr6.app

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Over-the-air update logic, split into three deliberate steps:
 *
 *   check()     - read the manifest and report what is available
 *   download()  - fetch the APK (only when the user asks)
 *   (install)   - hand the file to the system installer
 *
 * The split exists because the original version started downloading the moment
 * it saw a new version, which left no room for the user to decide and gave no
 * visible progress.
 *
 * MULTIPLE CANDIDATE ADDRESSES
 * ----------------------------
 *   1. LAN       192.168.3.11:8080     fastest, at home
 *   2. Tailscale 100.77.117.76:8080    works anywhere, if Tailscale is on
 *   3. GitHub    releases/latest       any network, github.com permitting
 *
 * A user override in Settings replaces all three.
 *
 * Each candidate is probed with a short TCP connect first, and every failure is
 * recorded so the UI can explain which addresses were tried and why they
 * failed rather than showing a single opaque error.
 */
object UpdateChecker {

    private val io = Executors.newCachedThreadPool()

    private const val PROBE_TIMEOUT_MS = 4000
    private const val CONNECT_TIMEOUT_MS = 12000
    private const val READ_TIMEOUT_MS = 20000

    private val DEFAULT_CANDIDATES = listOf(
        "http://192.168.3.11:8080/version.json",
        "http://100.77.117.76:8080/version.json",
        "https://github.com/langEQR6/eqr6-app/releases/latest/download/version.json"
    )

    sealed class Result {
        /** Already on the newest build. */
        data class UpToDate(val version: String, val source: String) : Result()

        /** A newer build exists. Nothing is downloaded yet. */
        data class Available(
            val versionCode: Int,
            val version: String,
            val apkUrl: String,
            val notes: String,
            val source: String
        ) : Result()

        /**
         * Nothing could be read.
         * `detail` lists every address tried, and `hint` is the environment
         * based advice the UI should show.
         */
        data class Failed(val detail: String, val hint: String) : Result()
    }

    data class DownloadProgress(
        val downloaded: Long,
        val total: Long,
        val percent: Int
    )

    private fun onUi(action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(action)
    }

    // ------------------------------------------------------------------
    // candidate selection
    // ------------------------------------------------------------------

    fun candidateUrls(context: Context): List<String> {
        val saved = Prefs.manifestUrl(context)
        return if (saved.isNullOrBlank()) DEFAULT_CANDIDATES else listOf(saved)
    }

    /** Which channel a URL belongs to, for display. */
    fun channelOf(url: String): String = when {
        url.contains("github.com") -> "GitHub"
        url.contains("192.168.") -> "局域网"
        url.contains("100.") -> "Tailscale"
        else -> "自定义"
    }

    // ------------------------------------------------------------------
    // step 1: check
    // ------------------------------------------------------------------

    fun check(context: Context, callback: (Result) -> Unit) {
        io.execute {
            val candidates = candidateUrls(context)
            val notes = StringBuilder()
            var reachableCount = 0

            for (url in candidates) {
                val channel = channelOf(url)
                if (!isReachable(url)) {
                    notes.append("• ").append(channel).append("：无法连接（")
                        .append(hostOf(url)).append("）\n")
                    continue
                }
                reachableCount++
                try {
                    val manifest = fetchManifest(url, null)
                    val json = JSONObject(manifest)
                    val code = json.optInt("versionCode", 0)
                    val name = json.optString("versionName", "?")
                    val changeNotes = json.optString("notes", "")

                    if (code <= BuildConfig.VERSION_CODE) {
                        onUi { callback(Result.UpToDate(BuildConfig.VERSION_NAME, channel)) }
                        return@execute
                    }

                    var raw = json.optString("apkUrl", "")
                    if (raw.isBlank()) {
                        notes.append("• ").append(channel).append("：清单缺少 apkUrl\n")
                        continue
                    }
                    if (url.contains("/releases/") && url.contains("github.com")) {
                        try {
                            val base = url.substringBeforeLast('/')
                            raw = resolveGithubRelease("$base/$raw", null)
                        } catch (e: Exception) {
                            // keep the literal relative path as a fallback
                        }
                    }
                    val apkUrl = if (raw.startsWith("http")) raw else resolveRelative(url, raw)
                    onUi { callback(Result.Available(code, name, apkUrl, changeNotes, channel)) }
                    return@execute
                } catch (e: Exception) {
                    notes.append("• ").append(channel).append("：")
                        .append(e.message ?: "读取失败").append('\n')
                }
            }

            val env = NetEnv.probe(context)
            val detail = if (notes.isBlank()) {
                "没有配置任何更新地址。"
            } else {
                "已尝试 ${candidates.size} 个通道，其中 $reachableCount 个可连接：\n$notes"
            }
            val hint = NetEnv.updateAdvice(env)
            onUi { callback(Result.Failed(detail.trim(), hint)) }
        }
    }

    // ------------------------------------------------------------------
    // step 2: download (explicit user action)
    // ------------------------------------------------------------------

    fun download(
        context: Context,
        update: Result.Available,
        onProgress: (DownloadProgress) -> Unit,
        callback: (File?, String?) -> Unit
    ) {
        io.execute {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(dir, "app-${update.versionCode}.apk")
            if (target.exists()) target.delete()

            try {
                val isGithubApi = update.apkUrl.contains("api.github.com/repos/") &&
                        update.apkUrl.contains("/releases/assets/")
                val conn = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = 60000
                    instanceFollowRedirects = true
                    if (isGithubApi) {
                        setRequestProperty("Accept", "application/octet-stream")
                        setRequestProperty("User-Agent", "EQR6-App")
                    }
                    connect()
                }

                val code = conn.responseCode
                if (code !in 200..299) {
                    conn.disconnect()
                    onUi { callback(null, "服务器返回 HTTP $code\n通道：${update.source}") }
                    return@execute
                }

                val total = conn.contentLengthLong
                var done = 0L
                var lastPct = -1

                BufferedInputStream(conn.inputStream).use { input ->
                    FileOutputStream(target).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                            if (pct != lastPct) {
                                lastPct = pct
                                onUi { onProgress(DownloadProgress(done, total, pct)) }
                            }
                        }
                    }
                }
                conn.disconnect()

                if (target.length() <= 0) {
                    onUi { callback(null, "下载完成但文件为空（可能被拦截）") }
                    return@execute
                }
                onUi { callback(target, null) }
            } catch (e: Exception) {
                val msg = when {
                    e.message?.contains("timeout", true) == true ->
                        "下载超时。移动网络较慢时容易发生，建议连 WiFi 后重试。\n通道：${update.source}"
                    e.message?.contains("Unable to resolve host", true) == true ->
                        "无法解析下载地址的主机名。GitHub 通道可能需要科学上网。"
                    else -> "下载失败：${e.message ?: e.javaClass.simpleName}"
                }
                onUi { callback(null, msg) }
            }
        }
    }

    // ------------------------------------------------------------------
    // manifest fetching
    // ------------------------------------------------------------------

    private fun fetchManifest(url: String, apiToken: String?): String {
        val isGithubRelease =
            url.contains("github.com") && url.contains("/releases/latest/download/")
        if (!isGithubRelease) return httpGet(url)

        return try {
            httpGet(url)
        } catch (directError: Exception) {
            val apiUrl = url
                .replace("https://github.com/", "https://api.github.com/repos/")
            try {
                httpGet(apiUrl)
            } catch (apiError: Exception) {
                httpGet(resolveGithubRelease(apiUrl, apiToken))
            }
        }
    }

    private fun resolveGithubRelease(manifestUrl: String, apiToken: String?): String {
        val cut = manifestUrl.indexOf("/releases/")
        if (cut < 0) throw IllegalStateException("not a release URL")
        val apiRepoBase = manifestUrl.substring(0, cut)
        val wantName = manifestUrl.substringAfterLast('/')

        val releaseJson = JSONObject(apiGet(apiRepoBase + "/releases/latest", apiToken))
        val assets = releaseJson.optJSONArray("assets")
            ?: throw IllegalStateException("release has no assets")

        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == wantName) {
                val id = a.optLong("id", 0)
                if (id <= 0) throw IllegalStateException("asset has no id")
                return "$apiRepoBase/releases/assets/$id"
            }
        }
        throw IllegalStateException("asset $wantName not found in release")
    }

    private fun apiGet(url: String, apiToken: String?): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "EQR6-App")
            if (!apiToken.isNullOrBlank()) {
                setRequestProperty("Authorization", "token $apiToken")
            }
            connect()
        }
        return try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(url: String): String {
        val isGithubApi = url.contains("api.github.com/repos/") &&
                url.contains("/releases/assets/")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            if (isGithubApi) {
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", "EQR6-App")
            } else {
                setRequestProperty("Cache-Control", "no-cache")
            }
            connect()
        }
        return try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun isReachable(url: String): Boolean {
        return try {
            val u = URL(url)
            val port = if (u.port > 0) u.port else if (u.protocol == "https") 443 else 80
            NetEnv.tcpReachable(u.host, port)
        } catch (e: Exception) {
            false
        }
    }

    private fun hostOf(url: String): String = try {
        URL(url).host
    } catch (e: Exception) {
        url
    }

    private fun resolveRelative(base: String, rel: String): String {
        val cut = base.lastIndexOf('/')
        return if (cut >= 0) base.substring(0, cut + 1) + rel else rel
    }
}
