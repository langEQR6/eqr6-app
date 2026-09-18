package com.eqr6.app

import android.content.Context
import android.preference.PreferenceManager

/**
 * All persisted app settings in one place.
 *
 * Uses the framework SharedPreferences directly (no AndroidX) to keep the
 * project dependency-free.
 */
object Prefs {

    // OTA manifest location. Empty = try every candidate in order.
    const val MANIFEST_URL = "manifest_url"

    // Toolbox API
    const val API_BASE = "api_base"
    const val API_KEY = "api_key"

    // Tailscale reminder bookkeeping
    const val TS_REMINDER_DISMISSED_AT = "ts_reminder_dismissed_at"

    /** A month in milliseconds - how long a dismissed reminder stays quiet. */
    private const val REMINDER_SNOOZE_MS = 30L * 24 * 60 * 60 * 1000

    private fun sp(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    // ---- OTA ----
    fun manifestUrl(context: Context): String? =
        sp(context).getString(MANIFEST_URL, null)?.takeIf { it.isNotBlank() }

    fun setManifestUrl(context: Context, url: String?) {
        sp(context).edit().apply {
            if (url.isNullOrBlank()) remove(MANIFEST_URL) else putString(MANIFEST_URL, url.trim())
        }.apply()
    }

    // ---- API ----
    fun apiBase(context: Context): String =
        sp(context).getString(API_BASE, null)?.takeIf { it.isNotBlank() } ?: ApiClient.DEFAULT_BASE

    fun setApiBase(context: Context, value: String?) {
        sp(context).edit().apply {
            if (value.isNullOrBlank()) remove(API_BASE) else putString(API_BASE, value.trim())
        }.apply()
    }

    fun apiKey(context: Context): String =
        sp(context).getString(API_KEY, null)?.trim() ?: ""

    fun setApiKey(context: Context, value: String?) {
        sp(context).edit().apply {
            if (value.isNullOrBlank()) remove(API_KEY) else putString(API_KEY, value.trim())
        }.apply()
    }

    // ---- Tailscale reminder ----
    /** True when an "enable Tailscale" banner should be shown. */
    fun shouldShowTailscaleReminder(context: Context): Boolean {
        val at = sp(context).getLong(TS_REMINDER_DISMISSED_AT, 0L)
        return System.currentTimeMillis() - at > REMINDER_SNOOZE_MS
    }

    fun dismissTailscaleReminder(context: Context) {
        sp(context).edit().putLong(TS_REMINDER_DISMISSED_AT, System.currentTimeMillis()).apply()
    }
}
