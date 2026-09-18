package com.eqr6.app

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Settings tab: where the app finds EQR6, the API key, Tailscale state, and OTA.
 *
 * The server address may be left empty, meaning "try every known address":
 * LAN first (fast at home) and Tailscale after that (works anywhere).
 *
 * NOTE: framework calls like addView do not accept named arguments from Kotlin,
 * so spacing is applied with explicit LayoutParams rather than topMargin = ...
 */
class SettingsScreen(
    context: Context,
    private val onSettingsChanged: () -> Unit
) : LinearLayout(context), MainActivity.Refreshable {

    private val baseEdit: EditText
    private val keyEdit: EditText
    private val testResult: TextView
    private val tsBox = LinearLayout(context)
    private val sshBox = LinearLayout(context)
    private val versionText = TextView(context)
    private val updateStatus = TextView(context)

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

        inner.addView(TextView(context).apply {
            text = "设置"
            textSize = 22f
            setTextColor(Color.parseColor("#212121"))
        })

        // ---------------- server connection ----------------
        inner.addView(spacer(16))
        inner.addView(section("服务器连接"))

        val card = cardBox()
        card.addView(label("服务器地址"))

        baseEdit = EditText(context).apply {
            hint = "http://192.168.3.11:8080"
            textSize = 14f
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            val saved = Prefs.apiBase(context)
            setText(if (saved == ApiClient.DEFAULT_BASE) "" else saved)
        }
        card.addView(baseEdit)
        card.addView(hint("留空 = 自动：先试局域网，再试 Tailscale"))

        card.addView(spacer(14))
        card.addView(label("API 密钥"))

        keyEdit = EditText(context).apply {
            hint = "服务器上 api-key.txt 的内容"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setText(Prefs.apiKey(context))
        }
        card.addView(keyEdit)
        card.addView(hint("EQR6 上：D:\\Server\\stock\\eqr6-app\\api-key.txt"))

        card.addView(spacer(14))
        // created before the buttons below, which reference it from their handlers
        testResult = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#757575"))
            setPadding(0, dp(10), 0, 0)
        }

        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        row.addView(actionButton("保存", true) {
            saveEdits()
            Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
            onSettingsChanged()
            refresh()
        })
        row.addView(actionButton("测试连接", false) {
            saveEdits()
            testResult.text = "测试中…"
            testResult.setTextColor(Color.parseColor("#757575"))
            ApiClient.testConnection(context) { ok, msg ->
                testResult.text = if (ok) "✔ $msg" else "✖ $msg"
                testResult.setTextColor(Color.parseColor(if (ok) "#2E7D32" else "#C62828"))
                if (ok) onSettingsChanged()
            }
        })
        card.addView(row)
        card.addView(testResult)
        inner.addView(card, topGap(8))

        // ---------------- tailscale ----------------
        inner.addView(spacer(18))
        inner.addView(section("Tailscale"))
        tsBox.apply {
            orientation = VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        // ---------------- ssh key (for the DSH tunnel) ----------------
        inner.addView(spacer(18))
        inner.addView(section("SSH 密钥（用于打开 DSH）"))
        sshBox.apply {
            orientation = VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        inner.addView(tsBox, topGap(8))
        // placeholder for the SSH card, filled by refreshSshCard()
        inner.addView(sshBox, topGap(8))

        // ---------------- update ----------------
        inner.addView(spacer(18))
        inner.addView(section("更新"))

        val upCard = cardBox()
        versionText.apply {
            textSize = 14f
            setTextColor(Color.parseColor("#212121"))
        }
        upCard.addView(versionText)
        upCard.addView(spacer(12))
        upCard.addView(actionButton("检查更新", true) { checkUpdate() })
        updateStatus.apply {
            textSize = 11f
            setTextColor(Color.parseColor("#757575"))
            setPadding(0, dp(8), 0, 0)
        }
        upCard.addView(updateStatus)
        inner.addView(upCard, topGap(8))

        // ---------------- ota source ----------------
        inner.addView(spacer(18))
        inner.addView(section("更新服务器地址"))

        val otaCard = cardBox()
        val otaEdit = EditText(context).apply {
            hint = "留空 = 自动（局域网 → Tailscale → GitHub）"
            textSize = 13f
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(Prefs.manifestUrl(context) ?: "")
        }
        otaCard.addView(otaEdit)
        otaCard.addView(spacer(12))
        otaCard.addView(actionButton("保存更新地址", false) {
            Prefs.setManifestUrl(context, otaEdit.text.toString())
            Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
        })
        inner.addView(otaCard, topGap(8))

        // ---------------- about ----------------
        inner.addView(spacer(18))
        inner.addView(section("关于"))
        inner.addView(TextView(context).apply {
            text = "目标服务器：ptcg-1 (100.77.117.76)\n" +
                   "局域网：192.168.3.11\n\n" +
                   "所有请求仅经局域网或 Tailscale；" +
                   "服务器不对公网开放任何端口。"
            textSize = 12f
            setTextColor(Color.parseColor("#757575"))
        }, topGap(8))
    }

    // ------------------------------------------------------------------
    // small builders
    // ------------------------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun spacer(px: Int): TextView = TextView(context).apply {
        // an empty view used purely as vertical spacing
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(px))
    }

    private fun topGap(px: Int): LayoutParams =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(px)
        }

    private fun section(text: String) = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#757575"))
        setPadding(0, 0, 0, dp(8))
    }

    private fun label(text: String) = TextView(context).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.parseColor("#616161"))
        setPadding(0, 0, 0, dp(4))
    }

    private fun hint(text: String) = TextView(context).apply {
        this.text = text
        textSize = 11f
        setTextColor(Color.parseColor("#9E9E9E"))
        setPadding(0, dp(4), 0, 0)
    }

    private fun cardBox() = LinearLayout(context).apply {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)
        setPadding(dp(14), dp(14), dp(14), dp(14))
    }

    private fun actionButton(label: String, primary: Boolean, onClick: () -> Unit) =
        TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(Color.parseColor(if (primary) "#FFFFFF" else "#1565C0"))
            setBackgroundColor(Color.parseColor(if (primary) "#1565C0" else "#E3F2FD"))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { onClick() }
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                .apply { rightMargin = dp(8) }
        }

    // ------------------------------------------------------------------
    // behaviour
    // ------------------------------------------------------------------

    private fun saveEdits() {
        Prefs.setApiBase(context, baseEdit.text.toString())
        Prefs.setApiKey(context, keyEdit.text.toString())
    }

    private fun checkUpdate() {
        updateStatus.text = "检查中…"
        UpdateChecker.check(context) { result ->
            post {
                when (result) {
                    is UpdateChecker.Result.UpToDate ->
                        updateStatus.text = "已是最新版本 (${result.version}) · 来源：${result.source}"
                    is UpdateChecker.Result.Available ->
                        updateStatus.text =
                            "发现新版本 ${result.version} (build ${result.versionCode}) · 来源：${result.source}"
                    is UpdateChecker.Result.Failed ->
                        updateStatus.text = "检查失败：${result.reason}"
                }
            }
        }
    }

    override fun refresh() {
        versionText.text =
            "当前版本：${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
        updateTsCard()
        refreshSshCard()
    }

    /**
     * Shows the phone's own SSH public key.
     *
     * The user copies this one line into
     * C:\ProgramData\ssh\administrators_authorized_keys on EQR6 (with the ACL
     * left as SYSTEM + Administrators only). Once installed, the app can open
     * an SSH tunnel and therefore the DSH web UI.
     */
    private fun refreshSshCard() {
        sshBox.removeAllViews()

        if (!SshKeys.hasKey(context)) {
            sshBox.addView(TextView(context).apply {
                text = "还没有生成密钥。点下面的按钮生成一对。"
                textSize = 12f
                setTextColor(Color.parseColor("#616161"))
            })
            sshBox.addView(actionButton("生成密钥", true) {
                try {
                    SshKeys.ensureKey(context)
                    Toast.makeText(context, "已生成", Toast.LENGTH_SHORT).show()
                    refresh()
                } catch (e: Exception) {
                    Toast.makeText(context, "生成失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }, topGap(10))
            return
        }

        val pub = SshKeys.readPublicLine(context) ?: ""
        sshBox.addView(TextView(context).apply {
            text = "① 复制下面这一行（手机的公钥）"
            textSize = 12f
            setTextColor(Color.parseColor("#616161"))
        })
        sshBox.addView(TextView(context).apply {
            text = pub
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.parseColor("#212121"))
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setTextIsSelectable(true)
        }, topGap(6))

        sshBox.addView(TextView(context).apply {
            text = "② 在 EQR6 上执行（管理员 PowerShell）：\n" +
                   "Add-Content 'C:\\ProgramData\\ssh\\administrators_authorized_keys' " +
                   "'<粘贴公钥>'\n" +
                   "然后按文档修正该文件权限。"
            textSize = 11f
            setTextColor(Color.parseColor("#616161"))
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }, topGap(10))

        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        row.addView(actionButton("复制公钥", true) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("pubkey", pub))
            Toast.makeText(context, "公钥已复制", Toast.LENGTH_SHORT).show()
        })
        row.addView(actionButton("重新生成", false) {
            android.app.AlertDialog.Builder(context)
                .setTitle("重新生成密钥？")
                .setMessage("旧公钥会立即失效，需要重新装到 EQR6 上。")
                .setPositiveButton("重新生成") { _, _ ->
                    SshKeys.deleteKey(context)
                    SshKeys.ensureKey(context)
                    refresh()
                }
                .setNegativeButton("取消", null)
                .show()
        })
        sshBox.addView(row, topGap(10))

        sshBox.addView(TextView(context).apply {
            text = "隧道状态：" + if (SshTunnel.isRunning()) "✔ 已建立" else "未建立（打开 DSH 时会自动建立）"
            textSize = 11f
            setTextColor(Color.parseColor(
                if (SshTunnel.isRunning()) "#2E7D32" else "#9E9E9E"
            ))
        }, topGap(10))
    }

    private fun updateTsCard() {
        tsBox.removeAllViews()
        val ts = TailscaleStatus.current(context)

        tsBox.addView(TextView(context).apply {
            text = if (ts.connected) "状态：✔ 已连接" else "状态：✖ 未连接"
            textSize = 14f
            setTextColor(Color.parseColor(if (ts.connected) "#2E7D32" else "#C62828"))
        })

        if (ts.selfAddress != null) {
            tsBox.addView(TextView(context).apply {
                text = "本机 Tailscale IP：${ts.selfAddress}"
                textSize = 12f
                setTextColor(Color.parseColor("#616161"))
                setPadding(0, dp(4), 0, 0)
            })
        }
        tsBox.addView(TextView(context).apply {
            text = "EQR6：100.77.117.76"
            textSize = 12f
            setTextColor(Color.parseColor("#616161"))
            setPadding(0, dp(2), 0, 0)
        })

        if (!ts.connected) {
            tsBox.addView(TextView(context).apply {
                text = if (ts.installed)
                    "在外网访问 EQR6 需要 Tailscale。点下面的按钮打开它，再确认开关已开。"
                else
                    "未检测到 Tailscale 应用。请先安装并登录。"
                textSize = 12f
                setTextColor(Color.parseColor("#5D4037"))
                setBackgroundColor(Color.parseColor("#FFF3E0"))
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }, topGap(10))
        }

        tsBox.addView(actionButton("打开 Tailscale", true) {
            if (TailscaleStatus.openApp(context)) {
                Toast.makeText(context, "已打开，请确认已连接", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "未安装 Tailscale", Toast.LENGTH_LONG).show()
            }
        }, topGap(12))
    }
}
