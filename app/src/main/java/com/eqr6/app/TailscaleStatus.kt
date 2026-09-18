package com.eqr6.app

import android.content.Context
import android.content.Intent
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Detects whether Tailscale is up, and offers to open its app.
 *
 * HOW DETECTION WORKS
 * -------------------
 * Tailscale hands the device an address in 100.64.0.0/10 (CGNAT range). We scan
 * the local network interfaces for such an address. When it is present,
 * Tailscale is connected.
 *
 * This needs no permission and does not depend on any Tailscale API, which is
 * why the app can show a reminder without asking for anything.
 *
 * Opening the app: Android does not let one app flip another app's VPN switch,
 * so all we can do is bring Tailscale to the front and let the user tap. The
 * UI copy says exactly that rather than promising a one-tap fix.
 */
object TailscaleStatus {

    const val TAILSCALE_PACKAGE = "com.tailscale.ipn"

    data class State(
        val connected: Boolean,
        val selfAddress: String?,
        val installed: Boolean
    )

    /** True when an interface carries an address inside 100.64.0.0/10. */
    fun findTailscaleAddress(): String? {
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        if (isInCgnatRange(addr.hostAddress ?: continue)) {
                            return addr.hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // fall through - treated as "not connected"
        }
        return null
    }

    /**
     * 100.64.0.0/10 covers 100.64.0.0 - 100.127.255.255.
     * Second octet must be 64..127, first octet must be 100.
     */
    private fun isInCgnatRange(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        return a == 100 && b in 64..127
    }

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE) != null
    } catch (e: Exception) {
        false
    }

    fun current(context: Context): State {
        val addr = findTailscaleAddress()
        return State(
            connected = addr != null,
            selfAddress = addr,
            installed = isInstalled(context)
        )
    }

    /**
     * Bring the Tailscale app to the foreground so the user can switch it on.
     * Returns false when Tailscale is not installed.
     */
    fun openApp(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE)
            if (intent == null) {
                false
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
