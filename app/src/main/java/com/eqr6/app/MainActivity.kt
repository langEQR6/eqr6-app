package com.eqr6.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Minimal UI. Plain framework APIs only - no external libraries.
 *
 * Shows the installed version, lets the user check for updates, and exposes
 * the update-server address so switching hosts (LAN -> Tailscale -> GitHub)
 * never needs a rebuild.
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var serverEdit: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (resources.displayMetrics.density * 20).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // ---- title + version ----
        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
        })

        root.addView(TextView(this).apply {
            text = getString(R.string.version_label, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
            textSize = 15f
            setPadding(0, pad / 2, 0, pad)
        })

        // ---- version markers ----
        // Present so an over-the-air update is visually verifiable, not just a
        // changed number in a text field.
        root.addView(TextView(this).apply {
            text = getString(R.string.ota_test_marker)
            textSize = 15f
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(0, pad / 2, 0, 0)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.ota_marker_github)
            textSize = 15f
            setTextColor(Color.parseColor("#2E7D32"))
            setPadding(0, 0, 0, pad / 2)
        })

        // ---- check button ----
        val checkButton = Button(this).apply { text = getString(R.string.check_now) }
        root.addView(checkButton)

        // ---- status ----
        statusView = TextView(this).apply {
            text = getString(R.string.status_idle)
            textSize = 15f
            setPadding(0, pad, 0, pad)
        }
        root.addView(statusView)

        // ---- separator ----
        root.addView(TextView(this).apply {
            text = "\u2500".repeat(30)
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, pad / 2, 0, pad / 2)
        })

        // ---- update server override ----
        root.addView(TextView(this).apply {
            text = getString(R.string.server_label)
            textSize = 13f
            setTextColor(Color.DKGRAY)
        })

        serverEdit = EditText(this).apply {
            hint = getString(R.string.server_hint)
            textSize = 14f
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(UpdateChecker.candidateUrls(this@MainActivity).firstOrNull() ?: "")
        }
        // show the saved override if there is one, otherwise the first default
        val saved = android.preference.PreferenceManager
            .getDefaultSharedPreferences(this)
            .getString(UpdateChecker.PREF_MANIFEST_URL, null)
        serverEdit.setText(saved ?: "")
        root.addView(serverEdit)

        val saveButton = Button(this).apply { text = getString(R.string.server_save) }
        root.addView(saveButton)

        // ---- hint ----
        root.addView(TextView(this).apply {
            text = getString(R.string.probe_hint)
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, pad, 0, 0)
        })

        setContentView(ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        })

        checkButton.setOnClickListener { checkForUpdates(manual = true) }

        saveButton.setOnClickListener {
            val typed = serverEdit.text.toString().trim()
            UpdateChecker.saveManifestUrl(this, if (typed.isEmpty()) null else typed)
            Toast.makeText(
                this,
                if (typed.isEmpty()) getString(R.string.server_reset) else getString(R.string.server_saved),
                Toast.LENGTH_SHORT
            ).show()
        }

        // silent automatic check on launch
        checkForUpdates(manual = false)
    }

    private fun checkForUpdates(manual: Boolean) {
        statusView.setTextColor(Color.DKGRAY)
        statusView.text = getString(R.string.status_checking)

        UpdateChecker.check(this) { result ->
            runOnUiThread {
                when (result) {
                    is UpdateChecker.Result.UpToDate -> {
                        statusView.setTextColor(Color.parseColor("#2E7D32"))
                        statusView.text = getString(
                            R.string.status_uptodate, result.version, result.source
                        )
                    }
                    is UpdateChecker.Result.Available -> {
                        statusView.setTextColor(Color.parseColor("#1565C0"))
                        statusView.text = getString(
                            R.string.status_available, result.version, result.versionCode
                        )
                        promptInstall(result)
                    }
                    is UpdateChecker.Result.Failed -> {
                        statusView.setTextColor(Color.parseColor("#C62828"))
                        statusView.text = getString(R.string.status_failed, result.reason)
                        if (manual) {
                            Toast.makeText(this, result.reason, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    /**
     * Android refuses silent APK installs unless the app holds special
     * privileges. The normal non-root flow:
     *   1. user grants "install unknown apps" for this app once
     *   2. then we launch the system package installer
     */
    private fun promptInstall(update: UpdateChecker.Result.Available) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                Toast.makeText(this, getString(R.string.need_install_permission), Toast.LENGTH_LONG).show()
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                            .setData(Uri.parse("package:$packageName"))
                    )
                } catch (e: Exception) {
                    // the user can enable it manually
                }
                return
            }
        }
        UpdateChecker.download(this, update) { file ->
            runOnUiThread {
                if (file == null) {
                    statusView.setTextColor(Color.parseColor("#C62828"))
                    statusView.text = getString(R.string.status_download_failed)
                    return@runOnUiThread
                }
                statusView.setTextColor(Color.parseColor("#2E7D32"))
                statusView.text = getString(R.string.status_downloaded, file.name)
                launchInstaller(file)
            }
        }
    }

    private fun launchInstaller(apk: File) {
        try {
            // Android 7.0+ forbids handing a file:// URI to another app:
            // that threw FileUriExposedException on the first OTA attempt.
            // ApkProvider serves it as content:// instead.
            val uri: Uri = ApkProvider.uriFor(this, apk)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            statusView.setTextColor(Color.parseColor("#C62828"))
            statusView.text = getString(R.string.status_install_failed, e.message ?: "unknown")
        }
    }
}
