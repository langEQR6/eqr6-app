package com.eqr6.app

import android.content.Context
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.net.InetAddress
import java.net.ServerSocket

/**
 * A local SSH tunnel into EQR6.
 *
 * WHY THIS EXISTS
 * ---------------
 * DSH binds to 127.0.0.1 on the server. That is deliberate: it means the web UI
 * is unreachable from anywhere except the machine itself, which is exactly the
 * security posture the project wants. The phone therefore cannot simply open
 * http://100.77.117.76:3080 - the port is not listening on any external
 * interface.
 *
 * The supported way in is an SSH tunnel, which is what the laptop already does:
 *
 *     phone 127.0.0.1:3080  --ssh-->  server 127.0.0.1:3080
 *
 * After the tunnel is up, the phone browser opens http://127.0.0.1:3080 and the
 * request travels inside the encrypted SSH session. No port is exposed.
 *
 * The SSH connection itself runs over Tailscale, so it works on any network.
 */
object SshTunnel {

    const val LOCAL_PORT = 3080
    private const val REMOTE_PORT = 3080
    private const val SSH_PORT = 22
    private const val USER = "1"

    @Volatile private var ssh: SSHClient? = null
    @Volatile private var forwarder: LocalPortForwarder? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var listening = false
    @Volatile private var lastError: String? = null

    fun isRunning(): Boolean = listening && forwarder != null

    fun error(): String? = lastError

    /**
     * Open the tunnel if it is not already open.
     *
     * `host` is the server address the phone can actually reach (the Tailscale
     * address, or the LAN address when at home).
     *
     * Blocking - call from a background thread.
     */
    @Synchronized
    fun start(context: Context, host: String, port: Int = SSH_PORT) {
        if (isRunning()) return
        stopInternal()

        lastError = null

        if (!SshKeys.hasKey(context)) {
            SshKeys.ensureKey(context)
        }
        val provider = SshKeys.keyProvider(context)

        // WHY X25519 IS EXCLUDED ON ANDROID
        // ---------------------------------
        // sshj's default key-exchange list leads with BouncyCastle's X25519.
        // In bcprov every X25519 class lives under META-INF/versions/11/ (the
        // Java 9+ multi-release layout), which Android's D8/R8 does not process.
        // The packaged APK therefore contains no X25519 and the handshake dies
        // with:  no such algorithm: X25519 for provider BC
        //
        // The NIST curves are implemented in the Android platform provider and
        // in the ordinary part of BouncyCastle, so they work on a phone.
        //
        // Note: SSHClient has no config setter - the Config must be passed to
        // the constructor.
        val cfg = net.schmizz.sshj.DefaultConfig()
        cfg.setKeyExchangeFactories(cfg.keyExchangeFactories.filter {
            val n = it.name.uppercase()
            !n.contains("X25519") && !n.contains("CURVE25519") &&
            !n.contains("X448") && !n.contains("CURVE448")
        })

        val client = SSHClient(cfg)
        try {
            client.addHostKeyVerifier(PromiscuousVerifier())
            client.connectTimeout = 15000
            client.timeout = 15000
            client.connect(host, port)
            client.authPublickey(USER, provider)

            val serverSocket = ServerSocket(LOCAL_PORT, 8, InetAddress.getByName("127.0.0.1"))
            // Parameters is a TOP-LEVEL class in sshj 0.38.0 (the master branch
            // nests it, which is why the first attempt failed to compile)
            val params = Parameters(
                "127.0.0.1", LOCAL_PORT,
                "127.0.0.1", REMOTE_PORT
            )
            val fwd = client.newLocalPortForwarder(params, serverSocket)

            val t = Thread({
                try {
                    fwd.listen(Thread.currentThread())
                } catch (e: Exception) {
                    // normal on shutdown: the server socket is closed
                }
            }, "eqr6-ssh-forward")
            t.isDaemon = true
            t.start()

            ssh = client
            forwarder = fwd
            thread = t
            listening = true
        } catch (e: Exception) {
            listening = false
            lastError = describe(e)
            try { client.disconnect() } catch (ignored: Exception) {}
            throw e
        }
    }

    /** Close everything. Safe to call when not running. */
    @Synchronized
    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        listening = false
        try { forwarder?.close() } catch (e: Exception) {}
        try { thread?.interrupt() } catch (e: Exception) {}
        try { ssh?.disconnect() } catch (e: Exception) {}
        forwarder = null
        thread = null
        ssh = null
    }

    /** A message worth showing the user, not a raw stack trace. */
    private fun describe(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("Auth fail", true) || m.contains("auth", true) ->
                "认证失败：手机的公钥还没有装到 EQR6 上"
            m.contains("Connection refused", true) ->
                "连接被拒绝：EQR6 的 SSH 服务不可达（检查 Tailscale）"
            m.contains("timed out", true) || m.contains("timeout", true) ->
                "连接超时：确认 Tailscale 已开启，或与 EQR6 在同一局域网"
            m.contains("UnknownHost", true) ->
                "找不到主机：地址不正确"
            else -> m
        }
    }
}
