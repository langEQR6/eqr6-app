package com.eqr6.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * Log tab: browse files under D:\Server\logs and read them.
 *
 * Two views in one screen - a file list and a reader - swapped in place so the
 * bottom tab bar stays valid.
 *
 * Only the tail of a file is fetched (default 200 lines), which keeps large
 * logs such as the 215 KB Tailscale installer log from stalling the UI.
 */
class LogsScreen(context: Context) : LinearLayout(context), MainActivity.Refreshable {

    private val listScroll = ScrollView(context)
    private val listBox = LinearLayout(context)
    private val readerScroll = ScrollView(context)
    private val readerBox = LinearLayout(context)
    private val titleView = TextView(context)
    private val filterRow = LinearLayout(context)

    private var filter = "all"
    private var currentFile: String? = null
    private var onlyErrors = false
    private var lastContent = ""

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#F5F5F5"))

        // ---------- header ----------
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        titleView.apply {
            text = "日志"
            textSize = 18f
            setTextColor(Color.parseColor("#212121"))
        }
        header.addView(titleView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(context).apply {
            text = "\u21BB"
            textSize = 18f
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(dp(8), 0, dp(4), 0)
            setOnClickListener { refresh() }
        })
        addView(header)

        // ---------- category filter ----------
        filterRow.apply {
            orientation = HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        val filters = listOf(
            "all" to "\u5168\u90E8", "dsh" to "DSH", "watchdog" to "\u5DE1\u903B",
            "app" to "App", "setup" to "\u914D\u7F6E", "other" to "\u5176\u4ED6"
        )
        for ((key, label) in filters) {
            val chip = TextView(context).apply {
                text = label
                textSize = 12f
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { filter = key; refresh() }
                tag = key
            }
            filterRow.addView(chip)
        }
        addView(filterRow)

        // ---------- file list ----------
        listBox.apply {
            orientation = VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(24))
        }
        listScroll.addView(listBox)
        addView(listScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // ---------- reader ----------
        readerBox.apply {
            orientation = VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(24))
        }
        readerScroll.addView(readerBox)
        readerScroll.visibility = GONE
        addView(readerScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun refresh() {
        if (currentFile != null) {
            loadFile(currentFile!!)
        } else {
            loadList()
        }
    }

    private fun paintChips() {
        for (i in 0 until filterRow.childCount) {
            val tv = filterRow.getChildAt(i) as TextView
            val active = tv.tag == filter
            tv.setTextColor(Color.parseColor(if (active) "#FFFFFF" else "#616161"))
            tv.setBackgroundColor(Color.parseColor(if (active) "#1565C0" else "#EEEEEE"))
        }
    }

    // ------------------------------------------------------------------
    // file list
    // ------------------------------------------------------------------

    private fun loadList() {
        currentFile = null
        listScroll.visibility = VISIBLE
        readerScroll.visibility = GONE
        titleView.text = "日志"
        paintChips()

        listBox.removeAllViews()
        listBox.addView(TextView(context).apply {
            text = "\u6B63\u5728\u8BFB\u53D6\u2026"
            textSize = 13f
            setTextColor(Color.parseColor("#757575"))
            setPadding(0, dp(10), 0, 0)
        })

        ApiClient.get(context, "/api/logs") { result ->
            listBox.removeAllViews()
            when (result) {
                is ApiClient.Result.Ok -> {
                    val files = result.json.optJSONArray("files")
                    if (files == null || files.length() == 0) {
                        listBox.addView(emptyText("\u6CA1\u6709\u65E5\u5FD7\u6587\u4EF6"))
                        return@get
                    }
                    var shown = 0
                    for (i in 0 until files.length()) {
                        val f = files.optJSONObject(i) ?: continue
                        val cat = f.optString("category")
                        if (filter != "all" && cat != filter) continue
                        listBox.addView(fileRow(f))
                        shown++
                    }
                    if (shown == 0) listBox.addView(emptyText("\u8BE5\u5206\u7C7B\u4E0B\u6CA1\u6709\u6587\u4EF6"))
                    titleView.text = "\u65E5\u5FD7 ($shown)"
                }
                is ApiClient.Result.Err -> listBox.addView(emptyText(result.message, error = true))
            }
        }
    }

    private fun emptyText(msg: String, error: Boolean = false) = TextView(context).apply {
        text = msg
        textSize = 13f
        setTextColor(Color.parseColor(if (error) "#C62828" else "#757575"))
        setPadding(0, dp(16), 0, 0)
    }

    private fun fileRow(f: JSONObject): View {
        val name = f.optString("name")
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { openFile(name) }
        }
        card.addView(TextView(context).apply {
            text = name
            textSize = 14f
            setTextColor(Color.parseColor("#212121"))
            typeface = Typeface.MONOSPACE
        })
        card.addView(TextView(context).apply {
            text = "${f.optString("sizeText")}  \u00B7  ${f.optString("modified")}"
            textSize = 11f
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(0, dp(3), 0, 0)
        })
        return card.apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(8) }
        }
    }

    // ------------------------------------------------------------------
    // reader
    // ------------------------------------------------------------------

    private fun openFile(name: String) {
        currentFile = name
        onlyErrors = false
        loadFile(name)
    }

    private fun loadFile(name: String) {
        listScroll.visibility = GONE
        readerScroll.visibility = VISIBLE
        titleView.text = name

        readerBox.removeAllViews()

        // ---- toolbar ----
        val bar = LinearLayout(context).apply { orientation = HORIZONTAL }
        bar.addView(smallButton("\u2190 \u8FD4\u56DE") { loadList() })
        bar.addView(smallButton(if (onlyErrors) "\u770B\u5168\u90E8" else "\u53EA\u770B\u9519\u8BEF") {
            onlyErrors = !onlyErrors
            loadFile(name)
        })
        readerBox.addView(bar)

        val meta = TextView(context).apply {
            text = "\u6B63\u5728\u8BFB\u53D6\u2026"
            textSize = 11f
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(0, dp(8), 0, dp(8))
        }
        readerBox.addView(meta)

        val body = TextView(context).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#212121"))
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setTextIsSelectable(true)
        }
        readerBox.addView(body)

        val tail = if (onlyErrors) "&errors=1" else ""
        ApiClient.get(context, "/api/logs?file=$name&lines=300$tail") { result ->
            when (result) {
                is ApiClient.Result.Ok -> {
                    val content = result.json.optString("content")
                    lastContent = content
                    meta.text = "\u603B %d \u884C \u00B7 \u663E\u793A %d \u884C%s".format(
                        result.json.optInt("totalLines"),
                        result.json.optInt("returned"),
                        if (result.json.optBoolean("truncated")) " \u00B7 \u5DF2\u622A\u65AD" else ""
                    )
                    body.text = if (content.isBlank()) "(\u7A7A)" else highlight(content)
                }
                is ApiClient.Result.Err -> {
                    lastContent = ""
                    meta.text = ""
                    body.text = result.message
                    body.setTextColor(Color.parseColor("#C62828"))
                }
            }

            // ---- actions ----
            val actions = LinearLayout(context).apply {
                orientation = HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            actions.addView(smallButton("\u590D\u5236\u5168\u90E8") {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("log", lastContent))
                Toast.makeText(context, "\u5DF2\u590D\u5236", Toast.LENGTH_SHORT).show()
            })
            actions.addView(smallButton("\u5206\u4EAB") {
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, lastContent)
                }
                try {
                    context.startActivity(Intent.createChooser(i, "\u5206\u4EAB\u65E5\u5FD7"))
                } catch (e: Exception) {
                    Toast.makeText(context, "\u65E0\u6CD5\u5206\u4EAB", Toast.LENGTH_SHORT).show()
                }
            })
            readerBox.addView(actions)
        }
    }

    /**
     * Colours error and warning lines. Uses spans because a single TextView is
     * far cheaper than thousands of row views for a long log.
     */
    private fun highlight(text: String): CharSequence {
        val sb = android.text.SpannableStringBuilder(text)
        val lines = text.split('\n')
        var start = 0
        for (line in lines) {
            val upper = line.uppercase()
            val color = when {
                upper.contains("ERROR") || upper.contains("FAIL") -> "#C62828"
                upper.contains("WARN") -> "#EF6C00"
                upper.contains(" OK ") || upper.contains("] OK") -> "#2E7D32"
                else -> null
            }
            if (color != null) {
                sb.setSpan(
                    android.text.style.ForegroundColorSpan(Color.parseColor(color)),
                    start, start + line.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            start += line.length + 1
        }
        return sb
    }

    private fun smallButton(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 12f
        setTextColor(Color.parseColor("#1565C0"))
        setBackgroundColor(Color.parseColor("#E3F2FD"))
        setPadding(dp(12), dp(7), dp(12), dp(7))
        setOnClickListener { onClick() }
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            .apply { rightMargin = dp(8) }
    }

    /** Called by the system back button via MainActivity. */
    fun handleBack(): Boolean {
        return if (currentFile != null) {
            loadList()
            true
        } else false
    }
}
