package com.eqr6.app

import android.content.Context
import android.preference.PreferenceManager
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors

/**
 * Over-the-air update checker.
 *
 * The manifest is a small JSON document:
 *
 *   {
 *     "versionCode": 2,
 *     "versionName": "1.1",
 *     "apkUrl": "eqr6-app-release.apk",
 *     "sha256": "...",
 *     "notes": "what changed"
 *   }
 *
 * `apkUrl` may be absolute (https://...) or relative to the manifest URL.
 *
 * MULTIPLE CANDIDATE ADDRESSES
 * ----------------------------
 * The server is reachable two different ways depending on where the phone is:
 *
 *   at home            -> http://192.168.3.11:8080     (LAN, fast)
 *   anywhere else      -> http://100.77.117.76:8080    (Tailscale)
 *
 * We cannot know which one applies, and a wrong guess used to hang for the
 * full timeout. So each candidate is first probed with a short TCP connect
 * (2.5 s) and only the reachable ones are used. A user override can be saved
 * from the UI, which also covers a future GitHub-hosted manifest.
 */
object UpdateChecker {

    private val io = Executors.newSingleThreadExecutor()

    const val PREF_MANIFEST_URL = "manifest_url"

    /** LAN first (fast when at home), then Tailscale (works anywhere). */
    private val DEFAULT_CANDIDATES = listOf(
        "http://192.168.3.11:8080/version.json",
        "http://100.77.117.76:8080/version.json"
    )

    private const val PROBE_TIMEOUT_MS = 2500
    private const val CONNECT_TIMEOUT_MS = 12000
    private const val READ_TIMEOUT_MS = 20000

    sealed class Result {
        data class UpToDate(val version: String, val source: String) : Result()
        data class Available(
            val versionCode: Int,
            val version: String,
            val apkUrl: String,
            val notes: String,
            val source: String
        ) : Result()
        data class Failed(val reason: String) : Result()
    }

    /** The list the app should try, honouring a saved override. */
    fun candidateUrls(context: Context): List<String> {
        val saved = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_MANIFEST_URL, null)
        return if (!saved.isNullOrBlank()) listOf(saved) else DEFAULT_CANDIDATES
    }

    fun saveManifestUrl(context: Context, url: String?) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .apply {
                if (url.isNullOrBlank()) remove(PREF_MANIFEST_URL) else putString(PREF_MANIFEST_URL, url.trim())
            }
            .apply()
    }

    fun check(context: Context, callback: (Result) -> Unit) {
        io.execute {
            val candidates = candidateUrls(context)
            val notes = StringBuilder()

            for (url in candidates) {
                // fast reachability probe first: avoids waiting a full HTTP
                // timeout on an address that is simply not on this network
                if (!isReachable(url)) {
                    notes.append(hostOf(url)).append(": unreachable\n")
                    continue
                }
                try {
                    val json = JSONObject(httpGet(url))
                    val code = json.optInt("versionCode", 0)
                    val name = json.optString("versionName", "?")
                    val changeNotes = json.optString("notes", "")
                    val source = hostOf(url)

                    if (code <= BuildConfig.VERSION_CODE) {
                        callback(Result.UpToDate(BuildConfig.VERSION_NAME, source))
                        return@execute
                    }

                    val raw = json.optString("apkUrl", "")
                    if (raw.isBlank()) {
                        notes.append(source).append(": manifest has no apkUrl\n")
                        continue
                    }
                    val apkUrl = if (raw.startsWith("http")) raw else resolveRelative(url, raw)
                    callback(Result.Available(code, name, apkUrl, changeNotes, source))
                    return@execute
                } catch (e: Exception) {
                    notes.append(hostOf(url)).append(": ").append(e.message ?: "error").append('\n')
                }
            }

            callback(Result.Failed(notes.toString().trim().ifBlank { "no update server reachable" }))
        }
    }

    fun download(context: Context, update: Result.Available, callback: (File?) -> Unit) {
        io.execute {
            try {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val target = File(dir, "app-${update.versionCode}.apk")
                if (target.exists()) target.delete()

                val conn = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    connect()
                }
                if (conn.responseCode !in 200..299) {
                    callback(null)
                    return@execute
                }
                BufferedInputStream(conn.inputStream).use { input ->
                    FileOutputStream(target).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                        }
                    }
                }
                conn.disconnect()
                callback(if (target.length() > 0) target else null)
            } catch (e: Exception) {
                callback(null)
            }
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Quick TCP connect test so an offline address fails in ~2.5 s, not 12 s. */
    private fun isReachable(url: String): Boolean {
        return try {
            val u = URL(url)
            val port = if (u.port > 0) u.port else 80
            Socket().use { s ->
                s.connect(InetSocketAddress(u.host, port), PROBE_TIMEOUT_MS)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Cache-Control", "no-cache")
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
