package com.eqr6.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Describes the phone's current network situation in terms the user can act on.
 *
 * The point is to turn a bare failure into a recommendation:
 *   "you are on mobile data, Tailscale is off, and GitHub is unreachable -
 *    go indoors and use the LAN address"
 *
 * Detection is deliberately cheap and local: interface inspection plus short
 * TCP probes. Nothing here sends application-level traffic.
 */
object NetEnv {

    enum class Transport { WIFI, CELLULAR, ETHERNET, NONE }

    data class State(
        val transport: Transport,
        val tailscaleUp: Boolean,
        val tailscaleAddress: String?,
        val githubReachable: Boolean,
        val eqr6LanReachable: Boolean,
        val eqr6TailscaleReachable: Boolean
    ) {
        val onHomeLan: Boolean get() = transport == Transport.WIFI || transport == Transport.ETHERNET
    }

    private const val PROBE_MS = 2500

    fun transport(context: Context): Transport {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return Transport.NONE
            val caps = cm.getNetworkCapabilities(net) ?: return Transport.NONE
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
                else -> Transport.NONE
            }
        } catch (e: Exception) {
            Transport.NONE
        }
    }

    fun transportLabel(t: Transport): String = when (t) {
        Transport.WIFI -> "WiFi"
        Transport.CELLULAR -> "移动数据 (4G/5G)"
        Transport.ETHERNET -> "有线网络"
        Transport.NONE -> "无网络连接"
    }

    /** Short TCP connect test; true when something is listening on that port. */
    fun tcpReachable(host: String, port: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), PROBE_MS)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Everything the UI needs, gathered in one pass. Run off the UI thread. */
    fun probe(context: Context): State {
        val ts = TailscaleStatus.current(context)
        return State(
            transport = transport(context),
            tailscaleUp = ts.connected,
            tailscaleAddress = ts.selfAddress,
            githubReachable = tcpReachable("github.com", 443),
            eqr6LanReachable = tcpReachable("192.168.3.11", 8080),
            eqr6TailscaleReachable = tcpReachable("100.77.117.76", 8080)
        )
    }

    /**
     * The most useful single sentence for the current situation.
     * Used both by the connection error dialog and the update screen.
     */
    fun advice(s: State): String {
        if (s.transport == Transport.NONE) {
            return "手机没有网络。请先连上 WiFi 或打开移动数据。"
        }
        if (s.eqr6LanReachable) {
            return "你与 EQR6 在同一局域网，当前用的是最快的通道。"
        }
        if (s.eqr6TailscaleReachable) {
            return "已通过 Tailscale 连上 EQR6，通道正常。"
        }
        return when {
            !s.tailscaleUp && s.transport == Transport.CELLULAR ->
                "你在移动数据下，且 Tailscale 未开启。\n" +
                "→ 请打开 Tailscale；或回到室内连 WiFi（家里走局域网更快）。"

            !s.tailscaleUp ->
                "Tailscale 未开启，且局域网里也找不到 EQR6。\n" +
                "→ 请打开 Tailscale；确认 EQR6 已开机。"

            s.tailscaleUp && !s.eqr6TailscaleReachable ->
                "Tailscale 已开，但连不上 EQR6。\n" +
                "→ 可能是 EQR6 关机、DSH 服务未运行，或 Tailscale 掉线。\n" +
                "→ 回室内连 WiFi 试试，能连上说明是外网通道的问题。"

            else ->
                "暂时连不上 EQR6，请稍后重试或回室内检查。"
        }
    }

    /** Advice specific to over-the-air update checks. */
    fun updateAdvice(s: State): String {
        val sb = StringBuilder()
        if (s.eqr6LanReachable || s.eqr6TailscaleReachable) {
            sb.append("→ 你已能连上 EQR6，建议用局域网/Tailscale 通道更新（最快）。\n")
        } else if (!s.tailscaleUp) {
            sb.append("→ Tailscale 未开启，外部通道不可用。\n")
        }
        if (!s.githubReachable) {
            sb.append("→ 检测到 github.com 不可达")
            if (s.onHomeLan) {
                sb.append("，但你在家里，用局域网通道即可，不影响更新。\n")
            } else {
                sb.append("。GitHub 通道需要能访问 github.com，\n")
                sb.append("   请开启科学上网，或回到室内用局域网更新。\n")
            }
        } else {
            sb.append("→ github.com 可达，GitHub 通道可用。\n")
        }
        if (!s.eqr6LanReachable && !s.eqr6TailscaleReachable && !s.githubReachable) {
            sb.append("\n三条通道都不可用。最稳妥的做法：\n")
            sb.append("   回到室内，连上家里 WiFi，再点检查更新。")
        }
        return sb.toString().trim()
    }
}
