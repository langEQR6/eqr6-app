package com.eqr6.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Toolbox shell: four tabs along the bottom, one screen each.
 *
 * Plain framework views only - the project carries no external dependencies,
 * so the tab bar is a row of TextViews rather than a Material component.
 * Screens are built once and kept in memory; switching just toggles visibility,
 * which keeps tab changes instant and avoids re-fetching on every tap.
 */
class MainActivity : Activity() {

    private lateinit var content: FrameLayout
    private lateinit var tabViews: List<TextView>
    private lateinit var screens: List<View>

    private var current = 0

    companion object {
        private const val TAB_HOME = 0
        private const val TAB_LOGS = 1
        private const val TAB_SERVICES = 2
        private const val TAB_SETTINGS = 3

        private const val COLOR_ACTIVE = "#1565C0"
        private const val COLOR_INACTIVE = "#9E9E9E"
        private const val COLOR_BAR = "#FFFFFF"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        // ---- content area ----
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // ---- screens ----
        val home = HomeScreen(this)
        val logs = LogsScreen(this)
        val services = ServicesScreen(this)
        val settings = SettingsScreen(this, onSettingsChanged = {
            // addresses or key changed: refresh everything that talks to EQR6
            (home as? Refreshable)?.refresh()
            (services as? Refreshable)?.refresh()
        })

        screens = listOf(home, logs, services, settings)
        for (s in screens) {
            content.addView(s, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            s.visibility = View.GONE
        }

        // ---- tab bar ----
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor(COLOR_BAR))
            elevation = 8f
        }
        val tabs = listOf("首页" to "\uD83C\uDFE0", "日志" to "\uD83D\uDCC4",
                          "服务" to "\u2699", "设置" to "\uD83D\uDD27")
        val views = mutableListOf<TextView>()
        for ((i, t) in tabs.withIndex()) {
            val tv = TextView(this).apply {
                text = "${t.second}\n${t.first}"
                gravity = Gravity.CENTER
                textSize = 11f
                setPadding(0, 14, 0, 14)
                setOnClickListener { select(i) }
            }
            bar.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            views.add(tv)
        }
        tabViews = views
        root.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)

        select(TAB_HOME)

        // If the app is opened without a key yet, point the user at Settings.
        if (Prefs.apiKey(this).isBlank()) {
            select(TAB_SETTINGS)
        }
    }

    private fun select(index: Int) {
        current = index
        for ((i, s) in screens.withIndex()) {
            s.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        for ((i, t) in tabViews.withIndex()) {
            val active = i == index
            t.setTextColor(Color.parseColor(if (active) COLOR_ACTIVE else COLOR_INACTIVE))
            t.alpha = if (active) 1.0f else 0.7f
        }
        (screens[index] as? Refreshable)?.refresh()
    }

    /** Lets a screen jump to another tab (e.g. a Home card opening the log list). */
    fun selectTab(index: Int) {
        if (index in screens.indices) select(index)
    }

    override fun onResume() {
        super.onResume()
        // Tailscale may have been toggled while the app was in the background
        (screens.getOrNull(current) as? Refreshable)?.refresh()
    }

    /** Implemented by screens that need to re-read state when shown. */
    interface Refreshable {
        fun refresh()
    }
}
