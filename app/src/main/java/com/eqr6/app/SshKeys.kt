package com.eqr6.app

import android.content.Context
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.EdECPublicKey
import java.security.spec.EdECPoint
import java.util.Base64

/**
 * The phone's SSH identity, generated on the device.
 *
 * DESIGN DECISION
 * ---------------
 * The app generates its own keypair instead of using a private key copied from
 * a laptop. Two reasons:
 *
 *   1. No private key ever has to travel to the phone, and this app never has
 *      to handle somebody else's key material.
 *   2. Revocation is trivial: remove one line from the server's
 *      administrators_authorized_keys and this device is out.
 *
 * The private key is written into the app's private file storage
 * (/data/data/<pkg>/files), which on a non-rooted device is readable only by
 * this app. It is never written to external storage and never uploaded.
 *
 * Ed25519 is used because sshj/BouncyCastle supports it and Android 7+ ships
 * the JDK EdEC classes required to encode the key in OpenSSH format.
 */
object SshKeys {

    private const val KEY_FILE = "id_ed25519"

    private fun keyFile(context: Context) = File(context.filesDir, KEY_FILE)

    fun hasKey(context: Context): Boolean = keyFile(context).exists()

    fun deleteKey(context: Context) {
        keyFile(context).delete()
    }

    /**
     * Generate a keypair if absent. Returns the OpenSSH public key line that
     * must be added to the server.
     */
    fun ensureKey(context: Context): String {
        val f = keyFile(context)
        if (f.exists()) {
            return readPublicLine(context) ?: regenerate(context)
        }
        return regenerate(context)
    }

    private fun regenerate(context: Context): String {
        val gen = KeyPairGenerator.getInstance("Ed25519")
        val pair = gen.generateKeyPair()

        // private key: PKCS#8, base64, PEM-ish body; base64 is enough here
        val privB64 = Base64.getEncoder().encodeToString(pair.private.encoded)
        val f = keyFile(context)
        f.writeText(
            "-----BEGIN PRIVATE KEY-----\n" +
            privB64.chunked(64).joinToString("\n") +
            "\n-----END PRIVATE KEY-----\n",
            Charsets.US_ASCII
        )
        // owner-only; not strictly required inside app-private storage, but cheap
        try {
            f.setReadable(false, false)
            f.setReadable(true, true)
            f.setWritable(false, false)
            f.setWritable(true, true)
        } catch (e: Exception) {
            // best effort
        }

        val pubLine = encodeOpenSsh(pair.public)
        File(context.filesDir, "$KEY_FILE.pub").writeText(pubLine, Charsets.US_ASCII)
        return pubLine
    }

    fun readPublicLine(context: Context): String? {
        val f = File(context.filesDir, "$KEY_FILE.pub")
        return if (f.exists()) f.readText().trim() else null
    }

    /** KeyProvider for sshj authentication. */
    fun keyProvider(context: Context): KeyProvider {
        val f = keyFile(context)
        if (!f.exists()) throw IllegalStateException("no ssh key generated yet")

        val pem = f.readText()
        val b64 = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\n", "")
            .trim()
        val der = Base64.getDecoder().decode(b64)

        val spec = java.security.spec.PKCS8EncodedKeySpec(der)
        val kf = java.security.KeyFactory.getInstance("Ed25519")
        val priv: PrivateKey = kf.generatePrivate(spec)

        // sshj needs a KeyPair; rebuild the public half from the stored line
        val pub = decodeOpenSsh(readPublicLine(context) ?: throw IllegalStateException("public key missing"))
        return KeyPairWrapper(KeyPair(pub, priv))
    }

    // ------------------------------------------------------------------
    // OpenSSH wire format for an Ed25519 public key
    // ------------------------------------------------------------------

    private fun encodeOpenSsh(pub: PublicKey): String {
        val ed = pub as EdECPublicKey
        val point: EdECPoint = ed.point
        val y = point.y           // BigInteger, little-endian on the wire
        val le = toLittleEndian(y, 32)
        // the high bit of the last byte carries the x sign
        if (point.isXOdd) {
            le[31] = (le[31].toInt() or 0x80).toByte()
        }

        val algo = "ssh-ed25519".toByteArray(Charsets.US_ASCII)
        val out = java.io.ByteArrayOutputStream()
        writeString(out, algo)
        writeString(out, le)
        val b64 = Base64.getEncoder().encodeToString(out.toByteArray())
        return "ssh-ed25519 $b64 eqr6-app-phone"
    }

    private fun decodeOpenSsh(line: String): PublicKey {
        val parts = line.trim().split(" ")
        require(parts.size >= 2) { "malformed public key" }
        val blob = Base64.getDecoder().decode(parts[1])

        // blob = string "ssh-ed25519" | string key(32 bytes)
        var pos = 0
        val algoLen = readInt(blob, pos); pos += 4
        pos += algoLen
        val keyLen = readInt(blob, pos); pos += 4
        val keyBytes = blob.copyOfRange(pos, pos + keyLen)

        val le = keyBytes.copyOf()
        val xOdd = (le[31].toInt() and 0x80) != 0
        le[31] = (le[31].toInt() and 0x7F).toByte()
        val y = fromLittleEndian(le)

        val kf = java.security.KeyFactory.getInstance("Ed25519")
        val spec = java.security.spec.EdECPublicKeySpec(
            java.security.spec.NamedParameterSpec.ED25519,
            EdECPoint(xOdd, y)
        )
        return kf.generatePublic(spec)
    }

    private fun writeString(out: java.io.ByteArrayOutputStream, data: ByteArray) {
        out.write((data.size ushr 24) and 0xFF)
        out.write((data.size ushr 16) and 0xFF)
        out.write((data.size ushr 8) and 0xFF)
        out.write(data.size and 0xFF)
        out.write(data)
    }

    private fun readInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
        ((b[off + 1].toInt() and 0xFF) shl 16) or
        ((b[off + 2].toInt() and 0xFF) shl 8) or
        (b[off + 3].toInt() and 0xFF)

    private fun toLittleEndian(value: java.math.BigInteger, size: Int): ByteArray {
        val be = value.toByteArray()          // big-endian, possibly signed-padded
        val trimmed = if (be.size > size) be.copyOfRange(be.size - size, be.size) else be
        val out = ByteArray(size)
        for (i in trimmed.indices) {
            out[i] = trimmed[trimmed.size - 1 - i]
        }
        return out
    }

    private fun fromLittleEndian(le: ByteArray): java.math.BigInteger {
        val be = ByteArray(le.size)
        for (i in le.indices) {
            be[i] = le[le.size - 1 - i]
        }
        return java.math.BigInteger(1, be)
    }
}
