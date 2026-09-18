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

    /**
     * Ordered candidates.
     *
     *  1. LAN      - fastest when the phone is at home
     *  2. Tailscale- works anywhere, but only if the phone has Tailscale on
     *  3. GitHub   - works on ANY network with no VPN and no home server
     *
     * Each is probed with a short TCP connect first, so an unreachable entry
     * costs ~4 s rather than a full HTTP timeout.
     */
    private val DEFAULT_CANDIDATES = listOf(
        "http://192.168.3.11:8080/version.json",
        "http://100.77.117.76:8080/version.json",
        "https://github.com/langEQR6/eqr6-app/releases/latest/download/version.json"
    )

    private const val PROBE_TIMEOUT_MS = 4000
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
                    val source = hostOf(url)
                    val manifest = fetchManifest(url, null)
                    val json = JSONObject(manifest)
                    val code = json.optInt("versionCode", 0)
                    val name = json.optString("versionName", "?")
                    val changeNotes = json.optString("notes", "")

                    if (code <= BuildConfig.VERSION_CODE) {
                        callback(Result.UpToDate(BuildConfig.VERSION_NAME, source))
                        return@execute
                    }

                    var raw = json.optString("apkUrl", "")
                    if (raw.isBlank()) {
                        notes.append(source).append(": manifest has no apkUrl\n")
                        continue
                    }
                    // A relative apkUrl normally sits beside the manifest. For
                    // GitHub we resolve it through the API instead, because the
                    // github.com redirect is blocked here.
                    if (url.contains("/releases/") && url.contains("github.com")) {
                        try {
                            val base = url.substringBeforeLast('/')
                            raw = resolveGithubRelease("$base/$raw", null)
                        } catch (e: Exception) {
                            // fall back to the literal relative resolution
                        }
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

    /**
     * Fetch the manifest, with a China-friendly fallback for GitHub.
     *
     * A `github.com/.../releases/latest/download/...` URL is tried first
     * (works wherever github.com is reachable, e.g. with a VPN). If that
     * fails, we fall back to `api.github.com`, which is reachable from
     * mainland China and redirects asset downloads straight to the CDN.
     */
    private fun fetchManifest(url: String, apiToken: String?): String {
        val isGithubRelease = url.contains("github.com") && url.contains("/releases/latest/download/")
        if (!isGithubRelease) return httpGet(url)

        return try {
            httpGet(url)
        } catch (directError: Exception) {
            // rebuild the same request against api.github.com
            val apiUrl = url
                .replace("https://github.com/", "https://api.github.com/repos/")
                .replace("/releases/latest/download/", "/releases/latest/download/")
            try {
                httpGet(apiUrl)
            } catch (apiError: Exception) {
                // last resort: resolve the asset id via the releases endpoint
                val assetApi = resolveGithubRelease(apiUrl, apiToken)
                httpGet(assetApi)
            }
        }
    }

    fun download(context: Context, update: Result.Available, callback: (File?) -> Unit) {
        io.execute {
            try {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val target = File(dir, "app-${update.versionCode}.apk")
                if (target.exists()) target.delete()

                // The Accept header matters: a GitHub API asset URL returns
                // JSON metadata instead of the file unless it is set to
                // application/octet-stream. Hence the shared helper.
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
                if (conn.responseCode !in 200..299) {
                    conn.disconnect()
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

    /** Quick TCP connect test so an offline address fails fast, not after a full HTTP timeout. */
    private fun isReachable(url: String): Boolean {
        return try {
            val u = URL(url)
            // https defaults to 443, not 80 - getting this wrong made every
            // https candidate look unreachable.
            val port = if (u.port > 0) u.port else if (u.protocol == "https") 443 else 80
            Socket().use { s ->
                s.connect(InetSocketAddress(u.host, port), PROBE_TIMEOUT_MS)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun httpGet(url: String): String {
        // GitHub API asset downloads need the octet-stream Accept header to
        // return the file. They are followed automatically to the asset CDN.
        val isGithubApi = url.contains("api.github.com/repos/") && url.contains("/releases/assets/")
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

    /**
     * Resolve a version manifest from a GitHub release.
     *
     * WHY THIS IS NEEDED IN MAINLAND CHINA
     * ------------------------------------
     * `https://github.com/<o>/<r>/releases/latest/download/version.json`
     * is the obvious URL, but it redirects through github.com, which is
     * frequently blocked, so the redirect dies even though the file itself
     * lives on a reachable CDN (objects.githubusercontent.com).
     *
     * api.github.com, by contrast, is reachable. Asking the API for the
     * release's assets returns metadata directly, and fetching
     * `.../releases/assets/<id>` with Accept: application/octet-stream
     * redirects straight to the CDN.
     *
     * `apiToken` is optional: unauthenticated calls work for public repos.
     */
    private fun resolveGithubRelease(manifestUrl: String, apiToken: String?): String {
        val cut = manifestUrl.indexOf("/releases/")
        if (cut < 0) throw IllegalStateException("not a release URL")

        val apiRepoBase = manifestUrl.substring(0, cut)          // https://api.github.com/repos/O/R
        val wantName = manifestUrl.substringAfterLast('/')       // version.json

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
