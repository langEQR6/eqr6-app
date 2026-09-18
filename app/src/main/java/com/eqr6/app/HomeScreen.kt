package com.eqr6.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * Home tab: server overview, quick tools, service snapshot.
 *
 * Also owns the Tailscale reminder banner. That banner is the app's answer to
 * "I forgot to switch Tailscale on": it appears only while Tailscale is down,
 * and tapping it brings the Tailscale app forward.
 */
class HomeScreen(context: Context) : LinearLayout(context), MainActivity.Refreshable {

    private val statusCard = LinearLayout(context)
    private val serviceBox = LinearLayout(context)
    private val tsBanner = TextView(context)
    private val headline = TextView(context)
    private val footer = TextView(context)

    private val memBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val memText = TextView(context)
    private val diskBox = LinearLayout(context)
    private val uptimeText = TextView(context)

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#F5F5F5"))

        val scroll = ScrollView(context)
        val inner = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(24))
        }
        scroll.addView(inner)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // ---------- title ----------
        val header = LinearLayout(context).apply { orientation = HORIZONTAL }
        header.addView(TextView(context).apply {
            text = "EQR6 工具箱"
            textSize = 22f
            setTextColor(Color.parseColor("#212121"))
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val refreshBtn = TextView(context).apply {
            text = "\u21BB 刷新"
            textSize = 14f
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { refresh() }
        }
        header.addView(refreshBtn)
        inner.addView(header)

        // ---------- tailscale banner ----------
        tsBanner.apply {
            textSize = 13f
            setTextColor(Color.parseColor("#5D4037"))
            setBackgroundColor(Color.parseColor("#FFF3E0"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = GONE
            setOnClickListener {
                if (TailscaleStatus.openApp(context)) {
                    Toast.makeText(context, "已在 Tailscale 里打开，请确认已连接", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "未安装 Tailscale", Toast.LENGTH_LONG).show()
                }
            }
        }
        inner.addView(tsBanner, marginParams(top = 12))

        // ---------- status card ----------
        statusCard.apply {
            orientation = VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        statusCard.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(TextView(context).apply { text = "\uD83D\uDDA5 EQR6 状态"; textSize = 17f })
            headline.apply { textSize = 13f; gravity = Gravity.END }
            addView(headline, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        })
        statusCard.addView(memText.apply {
            textSize = 13f
            setTextColor(Color.parseColor("#424242"))
            setPadding(0, dp(10), 0, dp(2))
        })
        statusCard.addView(memBar.apply { max = 100 })
        statusCard.addView(diskBox.apply {
            orientation = VERTICAL
            setPadding(0, dp(10), 0, 0)
        })
        statusCard.addView(uptimeText.apply {
            textSize = 13f
            setTextColor(Color.parseColor("#424242"))
            setPadding(0, dp(10), 0, 0)
        })
        inner.addView(statusCard, marginParams(top = 12))

        // ---------- quick tools ----------
        inner.addView(sectionLabel("\u5FEB\u6377\u5DE5\u5177"), marginParams(top = 18))
        val grid = LinearLayout(context).apply { orientation = VERTICAL }
        grid.addView(toolRow(
            tool("\uD83D\uDE80", "\u6253\u5F00 DSH") { openDsh() },
            tool("\uD83D\uDCC4", "\u770B\u65E5\u5FD7") { openLogsTab() }
        ))
        grid.addView(toolRow(
            tool("\u2699", "\u670D\u52A1\u7BA1\u7406") { openServicesTab() },
            tool("\uD83D\uDCE6", "\u7248\u672C\u7BA1\u7406") { showVersions() }
        ))
        inner.addView(grid, marginParams(top = 8))

        // ---------- service snapshot ----------
        inner.addView(sectionLabel("\u670D\u52A1\u5FEB\u7167"), marginParams(top = 18))
        serviceBox.apply {
            orientation = VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        inner.addView(serviceBox, marginParams(top = 8))

        // ---------- footer ----------
        footer.apply {
            textSize = 11f
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(0, dp(16), 0, 0)
        }
        inner.addView(footer)
    }

    // ------------------------------------------------------------------
    // layout helpers
    // ------------------------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun marginParams(top: Int = 0, bottom: Int = 0): LayoutParams = LayoutParams(
        LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
    ).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

    private fun sectionLabel(text: String) = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#757575"))
    }

    private fun toolRow(vararg views: View) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        for ((i, v) in views.withIndex()) {
            addView(v, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = if (i == 0) dp(6) else 0
                leftMargin = if (i == 1) dp(6) else 0
                topMargin = dp(6)
            })
        }
    }

    private fun tool(icon: String, label: String, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.WHITE)
        setPadding(dp(12), dp(16), dp(12), dp(16))
        addView(TextView(context).apply {
            text = icon
            textSize = 26f
            gravity = Gravity.CENTER
        })
        addView(TextView(context).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#212121"))
            setPadding(0, dp(6), 0, 0)
        })
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------------
    // behaviour
    // ------------------------------------------------------------------

    override fun refresh() {
        updateTailscaleBanner()

        headline.text = "\u2026"
        headline.setTextColor(Color.parseColor("#9E9E9E"))
        memText.text = "\u6B63\u5728\u8BFB\u53D6\u2026"
        diskBox.removeAllViews()
        serviceBox.removeAllViews()
        uptimeText.text = ""
        footer.text = ""

        ApiClient.get(context, "/api/status") { result ->
            when (result) {
                is ApiClient.Result.Ok -> renderStatus(result.json, result.base)
                is ApiClient.Result.Err -> renderError(result.message)
            }
        }
    }

    private fun updateTailscaleBanner() {
        val ts = TailscaleStatus.current(context)
        val show = !ts.connected && Prefs.shouldShowTailscaleReminder(context)
        tsBanner.visibility = if (show) VISIBLE else GONE
        if (show) {
            tsBanner.text = if (ts.installed) {
                "\u26A0 Tailscale \u672A\u5F00\u542F\n\u5728\u5916\u7F51\u8BBF\u95EE\u9700\u8981\u5B83\u3002\u70B9\u6B64\u6253\u5F00 Tailscale\u3002"
            } else {
                "\u26A0 \u672A\u68C0\u6D4B\u5230 Tailscale\n\u5B89\u88C5\u5E76\u767B\u5F55\u540E\u624D\u80FD\u5728\u5916\u7F51\u8BBF\u95EE EQR6\u3002"
            }
        }
    }

    private fun renderStatus(json: JSONObject, base: String) {
        headline.text = "\u25CF \u5728\u7EBF"
        headline.setTextColor(Color.parseColor("#2E7D32"))

        val mem = json.optJSONObject("memory") ?: JSONObject()
        if (mem.length() > 0) {
            val used = mem.optDouble("usedPercent", 0.0)
            memText.text = "\u5185\u5B58  \u5DF2\u7528 %.1f%%   (%.1f / %.1f GB)".format(
                used, mem.optDouble("totalGb") - mem.optDouble("freeGb"), mem.optDouble("totalGb")
            )
            memBar.progress = used.toInt()
        } else {
            memText.text = "\u5185\u5B58  \u8BFB\u53D6\u5931\u8D25"
        }

        diskBox.removeAllViews()
        val disks = json.optJSONArray("disks")
        if (disks != null) {
            for (i in 0 until disks.length()) {
                val d = disks.optJSONObject(i) ?: continue
                val used = d.optDouble("usedPercent", 0.0)
                val row = LinearLayout(context).apply { orientation = VERTICAL }
                row.addView(TextView(context).apply {
                    text = "\u78C1\u76D8 %s   %.1f%%   (%.1f / %.1f GB)".format(
                        d.optString("drive"), used,
                        d.optDouble("totalGb") - d.optDouble("freeGb"), d.optDouble("totalGb")
                    )
                    textSize = 13f
                    setTextColor(Color.parseColor("#424242"))
                })
                val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = used.toInt()
                }
                row.addView(bar)
                diskBox.addView(row, LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) })
            }
        }

        val up = json.optDouble("uptimeHours", -1.0)
        uptimeText.text = if (up >= 0) "\u5DF2\u5F00\u673A  %.1f \u5C0F\u65F6".format(up) else ""

        // ---- service snapshot ----
        serviceBox.removeAllViews()
        val svc = json.optJSONArray("services")
        if (svc != null) {
            for (i in 0 until svc.length()) {
                val s = svc.optJSONObject(i) ?: continue
                val running = s.optBoolean("running", false)
                serviceBox.addView(snapshotRow(labelOf(s.optString("name")), running))
            }
        }
        val wd = json.optJSONObject("watchdog")
        if (wd != null) {
            val ok = wd.optBoolean("ok", false)
            serviceBox.addView(snapshotRow("\u5065\u5EB7\u5DE1\u903B", ok,
                detail = wd.optString("last", "")))
        }

        footer.text = "\u6570\u636E\u6765\u6E90\uff1a${base.replace("http://", "")}\n\u65F6\u95F4\uff1a${json.optString("time")}"
    }

    private fun labelOf(name: String): String = when (name) {
        "sshd" -> "SSH \u670D\u52A1"
        "Tailscale" -> "Tailscale"
        "dsh-web" -> "DSH web"
        "app-server" -> "\u66F4\u65B0\u670D\u52A1"
        else -> name
    }

    private fun snapshotRow(label: String, ok: Boolean, detail: String = ""): View {
        val row = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val line = LinearLayout(context).apply { orientation = HORIZONTAL }
        line.addView(TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(Color.parseColor("#212121"))
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        line.addView(TextView(context).apply {
            text = if (ok) "\u25CF \u8FD0\u884C\u4E2D" else "\u25CF \u5F02\u5E38"
            textSize = 13f
            setTextColor(Color.parseColor(if (ok) "#2E7D32" else "#C62828"))
        })
        row.addView(line)
        if (detail.isNotBlank()) {
            row.addView(TextView(context).apply {
                text = detail
                textSize = 11f
                setTextColor(Color.parseColor("#757575"))
                setPadding(0, dp(2), 0, 0)
            })
        }
        return row
    }

    private fun renderError(message: String) {
        headline.text = "\u25CF \u79BB\u7EBF"
        headline.setTextColor(Color.parseColor("#C62828"))
        memText.text = "\u8FDE\u4E0D\u4E0A EQR6"
        memBar.progress = 0
        diskBox.removeAllViews()
        serviceBox.removeAllViews()
        serviceBox.addView(TextView(context).apply {
            text = buildErrorHint(message)
            textSize = 13f
            setTextColor(Color.parseColor("#C62828"))
            setPadding(0, dp(10), 0, dp(10))
        })
        uptimeText.text = ""
        footer.text = ""
    }

    private fun buildErrorHint(detail: String): String {
        val ts = TailscaleStatus.current(context)
        val sb = StringBuilder("\u53EF\u80FD\u539F\u56E0\uff1a\n")
        if (!ts.connected) {
            sb.append("  \u2022 Tailscale \u672A\u5F00\u542F\uFF08\u5728\u5916\u7F51\u65F6\u5FC5\u987B\u5F00\uFF09\n")
        }
        sb.append("  \u2022 \u670D\u52A1\u5668\u5730\u5740\u6216 API \u5BC6\u94A5\u4E0D\u5BF9\uff08\u53BB\u300C\u8BBE\u7F6E\u300D\u68C0\u67E5\uFF09\n")
        sb.append("  \u2022 EQR6 \u672A\u5F00\u673A\u6216\u670D\u52A1\u672A\u8FD0\u884C\n\n")
        sb.append(detail)
        return sb.toString()
    }

    // ---- tool actions ----

    /**
     * Open the DSH web UI on the phone.
     *
     * DSH listens on 127.0.0.1 only, so the phone must go through an SSH tunnel
     * rather than connecting to the server address directly. The URL and token
     * still come from the API, but the browser is pointed at the tunnel's local
     * end (127.0.0.1:3080).
     */
    private fun openDsh() {
        val progress = AlertDialog.Builder(context)
            .setTitle("\u6B63\u5728\u51C6\u5907 DSH\u2026")
            .setMessage("\u6B65\u9AA4\uff1a\u53D6\u4EE4\u724C \u2192 \u5EFA SSH \u96A7\u9053 \u2192 \u6253\u5F00\u6D4F\u89C8\u5668")
            .setCancelable(true)
            .create()
        progress.show()

        // 1. token + staleness from the API
        ApiClient.get(context, "/api/dsh") { result ->
            when (result) {
                is ApiClient.Result.Ok -> {
                    val json = result.json
                    val url = json.optString("url", "")
                    val stale = json.optBoolean("stale", false)
                    if (url.isBlank()) {
                        progress.dismiss()
                        AlertDialog.Builder(context)
                            .setTitle("\u6CA1\u6709\u53EF\u7528\u7684 DSH \u5730\u5740")
                            .setMessage(
                                "\u670D\u52A1\u5668\u4E0A\u8FD8\u6CA1\u6709\u4EE4\u724C\u6587\u4EF6\u3002\n\n" +
                                "\u5728 EQR6 \u4E0A\u8FD0\u884C\uff1a\n" +
                                "start-dsh-web.ps1\n\n" +
                                "\u7136\u540E\u91CD\u8BD5\u3002"
                            )
                            .setPositiveButton("\u597D", null)
                            .show()
                        return@get
                    }
                    // tunnel host: the address the API answered on, minus the port
                    val host = result.base.replace("http://", "").replace("https://", "")
                        .substringBefore(':')
                    if (stale) {
                        progress.dismiss()
                        AlertDialog.Builder(context)
                            .setTitle("\u4EE4\u724C\u53EF\u80FD\u5DF2\u5931\u6548")
                            .setMessage(
                                "\u670D\u52A1\u5668\u4E0A\u7684 DSH \u5DF2\u91CD\u542F\uFF0C\u4FDD\u5B58\u7684\u4EE4\u724C\u53EF\u80FD\u8FC7\u671F\u3002\n" +
                                "\u4ECD\u7136\u53EF\u4EE5\u8BD5\u4E00\u4E0B\u3002"
                            )
                            .setPositiveButton("\u4ECD\u8981\u6253\u5F00") { _, _ ->
                                startTunnelThenOpen(host, url, progress)
                            }
                            .setNegativeButton("\u53D6\u6D88", null)
                            .show()
                    } else {
                        startTunnelThenOpen(host, url, progress)
                    }
                }
                is ApiClient.Result.Err -> {
                    progress.dismiss()
                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startTunnelThenOpen(host: String, serverUrl: String, progress: AlertDialog) {
        Thread {
            try {
                if (!SshTunnel.isRunning()) {
                    SshTunnel.start(context, host)
                }
                // the tunnel end is always loopback on the phone
                val tokenMatch = Regex("token=([A-Za-z0-9_\\-]+)").find(serverUrl)
                val token = tokenMatch?.groupValues?.get(1)
                val phoneUrl = if (token != null) {
                    "http://127.0.0.1:${SshTunnel.LOCAL_PORT}/?token=$token"
                } else {
                    "http://127.0.0.1:${SshTunnel.LOCAL_PORT}/"
                }
                post {
                    progress.dismiss()
                    launchUrl(phoneUrl)
                }
            } catch (e: Exception) {
                val msg = SshTunnel.error() ?: (e.message ?: "\u672A\u77E5\u9519\u8BEF")
                post {
                    progress.dismiss()
                    AlertDialog.Builder(context)
                        .setTitle("SSH \u96A7\u9053\u5EFA\u7ACB\u5931\u8D25")
                        .setMessage(msg + "\n\n\u5982\u679C\u63D0\u793A\u8BA4\u8BC1\u5931\u8D25\uff0c" +
                                "\u8BF7\u5230\u300C\u8BBE\u7F6E\u300D\u9875\u590D\u5236\u624B\u673A\u516C\u94A5\u5E76\u88C5\u5230 EQR6\u3002")
                        .setPositiveButton("\u597D", null)
                        .show()
                }
            }
        }.start()
    }

    private fun launchUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Toast.makeText(context, "\u65E0\u6CD5\u6253\u5F00\u6D4F\u89C8\u5668\uff1a${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openLogsTab() {
        (context as? MainActivity)?.let { it.selectTab(1) }
    }

    private fun openServicesTab() {
        (context as? MainActivity)?.let { it.selectTab(2) }
    }

    private fun showVersions() {
        Toast.makeText(context, "\u6B63\u5728\u8BFB\u53D6\u7248\u672C\u2026", Toast.LENGTH_SHORT).show()
        ApiClient.get(context, "/api/versions") { result ->
            when (result) {
                is ApiClient.Result.Ok -> {
                    val sb = StringBuilder()
                    val cur = result.json.optJSONObject("current")
                    if (cur != null) {
                        sb.append("\u5F53\u524D\u7248\u672C\uff1a")
                            .append(cur.optString("versionName"))
                            .append(" (build ").append(cur.optInt("versionCode")).append(")\n\n")
                    }
                    sb.append("\u5386\u53F2\u7248\u672C\uff1a\n")
                    val hist = result.json.optJSONArray("history")
                    if (hist != null) {
                        for (i in 0 until minOf(hist.length(), 12)) {
                            val h = hist.optJSONObject(i) ?: continue
                            sb.append("  \u2022 ").append(h.optString("versionName"))
                                .append(" (build ").append(h.optInt("build")).append(")")
                                .append("  ").append(h.optString("sizeText"))
                                .append("  ").append(h.optString("created")).append('\n')
                        }
                    }
                    AlertDialog.Builder(context)
                        .setTitle("\u7248\u672C\u7BA1\u7406")
                        .setMessage(sb.toString())
                        .setPositiveButton("\u597D", null)
                        .show()
                }
                is ApiClient.Result.Err ->
                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
