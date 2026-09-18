package com.eqr6.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * Services tab: scheduled tasks and system services.
 *
 * SAFETY DESIGN
 * -------------
 * The server refuses to stop SSH or Tailscale, and this screen does not even
 * render a stop button for them. Those two are the only route back into the
 * machine; disabling them remotely would leave the operator locked out with no
 * recovery path. They can be inspected and restarted, never stopped.
 *
 * Destructive actions are confirmed first, and the result is always shown.
 */
class ServicesScreen(context: Context) : LinearLayout(context), MainActivity.Refreshable {

    private val box = LinearLayout(context)
    private val statusLine = TextView(context)

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#F5F5F5"))

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        header.addView(TextView(context).apply {
            text = "服务"
            textSize = 18f
            setTextColor(Color.parseColor("#212121"))
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(context).apply {
            text = "\u21BB"
            textSize = 18f
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(dp(8), 0, dp(4), 0)
            setOnClickListener { refresh() }
        })
        addView(header)

        statusLine.apply {
            textSize = 11f
            setTextColor(Color.parseColor("#9E9E9E"))
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), 0, dp(14), dp(8))
        }
        addView(statusLine)

        val scroll = ScrollView(context)
        box.apply {
            orientation = VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(24))
        }
        scroll.addView(box)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun refresh() {
        box.removeAllViews()
        statusLine.text = "\u6B63\u5728\u8BFB\u53D6\u2026"

        ApiClient.get(context, "/api/services") { result ->
            box.removeAllViews()
            when (result) {
                is ApiClient.Result.Ok -> {
                    statusLine.text = "\u6765\u6E90\uff1a${result.base.replace("http://", "")}"

                    box.addView(sectionLabel("\u8BA1\u5212\u4EFB\u52A1"))
                    val tasks = result.json.optJSONArray("tasks")
                    if (tasks != null) {
                        for (i in 0 until tasks.length()) {
                            val t = tasks.optJSONObject(i) ?: continue
                            box.addView(taskCard(t))
                        }
                    }

                    box.addView(sectionLabel("\u7CFB\u7EDF\u670D\u52A1"), topGap(dp(18)))
                    val svcs = result.json.optJSONArray("services")
                    if (svcs != null) {
                        for (i in 0 until svcs.length()) {
                            val s = svcs.optJSONObject(i) ?: continue
                            box.addView(serviceCard(s))
                        }
                    }

                    box.addView(TextView(context).apply {
                        text = "\u6CE8\u610F\uff1a\u4E3A\u907F\u514D\u4F60\u5728\u5916\u9762\u65E0\u6CD5\u8FDE\u56DE\uff0c" +
                               "SSH \u4E0E Tailscale \u4E0D\u63D0\u4F9B\u505C\u6B62\u6309\u94AE\u3002"
                        textSize = 11f
                        setTextColor(Color.parseColor("#757575"))
                        setPadding(0, dp(18), 0, 0)
                    })
                }
                is ApiClient.Result.Err -> {
                    statusLine.text = ""
                    box.addView(TextView(context).apply {
                        text = "\u8BFB\u53D6\u5931\u8D25\uff1a\n${result.message}"
                        textSize = 13f
                        setTextColor(Color.parseColor("#C62828"))
                    })
                }
            }
        }
    }

    private fun sectionLabel(text: String) = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#757575"))
        setPadding(0, 0, 0, dp(8))
    }

    /** Layout params with a top gap, used instead of named arguments on addView. */
    private fun topGap(px: Int): LayoutParams =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = px
        }

    private fun taskCard(t: JSONObject): View {
        val name = t.optString("name")
        val label = t.optString("label")
        val state = t.optString("state")
        val running = state.equals("Running", true)

        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(8) }
        }
        card.addView(TextView(context).apply {
            text = label
            textSize = 15f
            setTextColor(Color.parseColor("#212121"))
        })
        card.addView(TextView(context).apply {
            text = "$name  \u00B7  $state"
            textSize = 11f
            setTextColor(Color.parseColor(if (running) "#2E7D32" else "#9E9E9E"))
            setPadding(0, dp(3), 0, dp(8))
        })

        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        row.addView(actionButton("\u7ACB\u5373\u8FD0\u884C") {
            confirm("\u7ACB\u5373\u8FD0\u884C $label \uff1f", null) {
                doAction("/api/services/task/$name/start", "$label \u5DF2\u542F\u52A8")
            }
        })
        row.addView(actionButton("\u505C\u6B62", danger = true) {
            confirm(
                "\u505C\u6B62 $label \uff1f",
                "\u505C\u6B62\u540E\u8BE5\u529F\u80FD\u5C06\u4E0D\u518D\u8FD0\u884C\uFF0C\u4E0B\u6B21\u5F00\u673A\u4ECD\u4F1A\u81EA\u52A8\u542F\u52A8\u3002"
            ) {
                doAction("/api/services/task/$name/stop", "$label \u5DF2\u505C\u6B62")
            }
        })
        card.addView(row)
        return card
    }

    private fun serviceCard(s: JSONObject): View {
        val name = s.optString("name")
        val label = s.optString("label")
        val status = s.optString("status")
        val running = status.equals("Running", true)
        val canStop = s.optBoolean("canStop", false)

        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(8) }
        }
        card.addView(TextView(context).apply {
            text = label
            textSize = 15f
            setTextColor(Color.parseColor("#212121"))
        })
        card.addView(TextView(context).apply {
            text = "$status"
            textSize = 11f
            setTextColor(Color.parseColor(if (running) "#2E7D32" else "#C62828"))
            setPadding(0, dp(3), 0, dp(8))
        })

        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        row.addView(actionButton("\u91CD\u542F") {
            confirm(
                "\u91CD\u542F $label \uff1f",
                if (name == "sshd" || name == "Tailscale")
                    "\u91CD\u542F\u671F\u95F4\u8FDC\u7A0B\u8FDE\u63A5\u4F1A\u77ED\u6682\u4E2D\u65AD\uFF0C\u51E0\u79D2\u540E\u6062\u590D\u3002"
                else null
            ) {
                doAction("/api/services/service/$name/restart", "$label \u5DF2\u91CD\u542F")
            }
        })
        // sshd and Tailscale deliberately get no stop button
        if (canStop) {
            row.addView(actionButton("\u505C\u6B62", danger = true) {
                confirm("\u505C\u6B62 $label \uff1f", null) {
                    doAction("/api/services/service/$name/stop", "$label \u5DF2\u505C\u6B62")
                }
            })
        }
        card.addView(row)
        return card
    }

    private fun actionButton(label: String, danger: Boolean = false, onClick: () -> Unit) =
        TextView(context).apply {
            text = label
            textSize = 13f
            setTextColor(Color.parseColor(if (danger) "#C62828" else "#1565C0"))
            setBackgroundColor(Color.parseColor(if (danger) "#FFEBEE" else "#E3F2FD"))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { onClick() }
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                .apply { rightMargin = dp(8) }
        }

    private fun confirm(title: String, message: String?, onYes: () -> Unit) {
        val b = AlertDialog.Builder(context).setTitle(title)
        if (message != null) b.setMessage(message)
        b.setPositiveButton("\u786E\u8BA4") { _, _ -> onYes() }
        b.setNegativeButton("\u53D6\u6D88", null)
        b.show()
    }

    private fun doAction(path: String, successMsg: String) {
        Toast.makeText(context, "\u6267\u884C\u4E2D\u2026", Toast.LENGTH_SHORT).show()
        ApiClient.post(context, path, null) { result ->
            when (result) {
                is ApiClient.Result.Ok -> {
                    val ok = result.json.optBoolean("ok", false)
                    if (ok) {
                        Toast.makeText(context, successMsg, Toast.LENGTH_SHORT).show()
                    } else {
                        AlertDialog.Builder(context)
                            .setTitle("\u64CD\u4F5C\u88AB\u62D2\u7EDD")
                            .setMessage(result.json.optString("error", result.json.optString("output", "\u672A\u77E5\u539F\u56E0")))
                            .setPositiveButton("\u597D", null)
                            .show()
                    }
                    refresh()
                }
                is ApiClient.Result.Err ->
                    Toast.makeText(context, "\u5931\u8D25\uff1a${result.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
