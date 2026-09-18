package com.eqr6.app

import android.content.Context
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The phone's SSH identity, generated on the device.
 *
 * WHY THE APP MAKES ITS OWN KEY
 * -----------------------------
 * No private key ever has to travel to the phone, and revocation is trivial:
 * delete one line from the server's administrators_authorized_keys.
 *
 * THE ED25519 PROBLEM
 * -------------------
 * The first version called KeyPairGenerator.getInstance("Ed25519") directly.
 * On this phone that throws "NOT initialized": Android's built-in Ed25519
 * support is incomplete and varies by OS version and vendor.
 *
 * So generation now walks a chain and reports which one worked:
 *   1. Ed25519 via BouncyCastle   (already bundled with sshj)
 *   2. Ed25519 via the platform   (works on newer Android)
 *   3. RSA 3072 via the platform  (available everywhere)
 *
 * Whichever succeeds determines the key type, and the public key line is
 * rendered in OpenSSH format for whichever type was produced.
 */
object SshKeys {

    private const val KEY_FILE = "id_ed25519"
    private const val KEY_FILE_RSA = "id_rsa"
    private const val ALG_FILE = "key_algorithm"

    data class Info(
        val algorithm: String,
        val publicLine: String,
        val detail: String
    )

    private fun keyFile(context: Context, alg: String) =
        File(context.filesDir, if (alg == "RSA") KEY_FILE_RSA else KEY_FILE)

    private fun algFile(context: Context) = File(context.filesDir, ALG_FILE)

    private fun currentAlg(context: Context): String? {
        val f = algFile(context)
        return if (f.exists()) f.readText().trim() else null
    }

    fun hasKey(context: Context): Boolean {
        val alg = currentAlg(context) ?: return false
        return keyFile(context, alg).exists()
    }

    fun deleteKey(context: Context) {
        File(context.filesDir, KEY_FILE).delete()
        File(context.filesDir, KEY_FILE_RSA).delete()
        File(context.filesDir, "$KEY_FILE.pub").delete()
        File(context.filesDir, "$KEY_FILE_RSA.pub").delete()
        algFile(context).delete()
    }

    fun readPublicLine(context: Context): String? {
        val alg = currentAlg(context) ?: return null
        val name = if (alg == "RSA") "$KEY_FILE_RSA.pub" else "$KEY_FILE.pub"
        val f = File(context.filesDir, name)
        return if (f.exists()) f.readText().trim() else null
    }

    /** Generate if absent; returns what was produced. Throws with a real reason. */
    fun ensureKey(context: Context): Info {
        if (hasKey(context)) {
            val alg = currentAlg(context)!!
            return Info(alg, readPublicLine(context) ?: "", "已存在")
        }
        return regenerate(context)
    }

    /**
     * Try every supported algorithm until one works.
     * The thrown message lists each attempt so the UI can show the real cause.
     */
    fun regenerate(context: Context): Info {
        deleteKey(context)

        val attempts = StringBuilder()

        // ---- 1. Ed25519 via BouncyCastle ----
        try {
            ensureBouncyCastle()
            val pair = genEd25519("BC")
            return storeEd25519(context, pair, "Ed25519 (BouncyCastle)")
        } catch (e: Throwable) {
            attempts.append("• Ed25519/BouncyCastle: ").append(describe(e)).append('\n')
        }

        // ---- 2. Ed25519 via the platform ----
        try {
            val pair = genEd25519(null)
            return storeEd25519(context, pair, "Ed25519 (系统)")
        } catch (e: Throwable) {
            attempts.append("• Ed25519/系统: ").append(describe(e)).append('\n')
        }

        // ---- 3. RSA 3072 via the platform (always available) ----
        try {
            val pair = genRsa(3072)
            return storeRsa(context, pair)
        } catch (e: Throwable) {
            attempts.append("• RSA/系统: ").append(describe(e)).append('\n')
            throw IllegalStateException("所有算法都失败：\n$attempts")
        }
    }

    private fun describe(e: Throwable): String {
        val msg = e.message ?: ""
        return if (msg.isBlank()) e.javaClass.simpleName else "${e.javaClass.simpleName}: $msg"
    }

    private fun ensureBouncyCastle() {
        // sshj ships BouncyCastle; register it if it is not already present
        if (Security.getProvider("BC") == null) {
            try {
                val cls = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
                val provider = cls.getDeclaredConstructor().newInstance()
                        as java.security.Provider
                Security.addProvider(provider)
            } catch (e: Throwable) {
                throw IllegalStateException("BouncyCastle 不可用: ${describe(e)}")
            }
        }
        if (Security.getProvider("BC") == null) {
            throw IllegalStateException("BouncyCastle 注册失败")
        }
    }

    private fun genEd25519(provider: String?): KeyPair {
        val gen = if (provider == null)
            KeyPairGenerator.getInstance("Ed25519")
        else
            KeyPairGenerator.getInstance("Ed25519", provider)
        gen.initialize(255)
        return gen.generateKeyPair()
    }

    private fun genRsa(bits: Int): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(bits)
        return gen.generateKeyPair()
    }

    // ------------------------------------------------------------------
    // storage
    // ------------------------------------------------------------------

    private fun storeEd25519(context: Context, pair: KeyPair, label: String): Info {
        val privB64 = Base64.getEncoder().encodeToString(pair.private.encoded)
        writePem(keyFile(context, "Ed25519"), privB64)
        val line = encodeEd25519OpenSsh(pair.public)
        File(context.filesDir, "$KEY_FILE.pub").writeText(line, Charsets.US_ASCII)
        algFile(context).writeText("Ed25519", Charsets.US_ASCII)
        return Info("Ed25519", line, "已生成（$label）")
    }

    private fun storeRsa(context: Context, pair: KeyPair): Info {
        val privB64 = Base64.getEncoder().encodeToString(pair.private.encoded)
        writePem(keyFile(context, "RSA"), privB64)
        val line = encodeRsaOpenSsh(pair.public as RSAPublicKey)
        File(context.filesDir, "$KEY_FILE_RSA.pub").writeText(line, Charsets.US_ASCII)
        algFile(context).writeText("RSA", Charsets.US_ASCII)
        return Info("RSA", line, "已生成（RSA 3072 回退，因为 Ed25519 在这台设备上不可用）")
    }

    private fun writePem(f: File, b64: String) {
        f.writeText(
            "-----BEGIN PRIVATE KEY-----\n" +
            b64.chunked(64).joinToString("\n") +
            "\n-----END PRIVATE KEY-----\n",
            Charsets.US_ASCII
        )
        try {
            f.setReadable(false, false)
            f.setReadable(true, true)
            f.setWritable(false, false)
            f.setWritable(true, true)
        } catch (e: Exception) {
            // best effort; app-private storage is already restricted
        }
    }

    // ------------------------------------------------------------------
    // sshj integration
    // ------------------------------------------------------------------

    fun keyProvider(context: Context): KeyProvider {
        val alg = currentAlg(context) ?: throw IllegalStateException("尚未生成密钥")
        val f = keyFile(context, alg)
        if (!f.exists()) throw IllegalStateException("密钥文件不存在")

        val der = pemToDer(f.readText())
        val kf = try {
            if (alg == "Ed25519" && Security.getProvider("BC") != null) {
                KeyFactory.getInstance("Ed25519", "BC")
            } else {
                KeyFactory.getInstance(alg)
            }
        } catch (e: Throwable) {
            KeyFactory.getInstance(alg)
        }
        val priv: PrivateKey = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(der))
        val pub: PublicKey = decodeOpenSsh(readPublicLine(context)
            ?: throw IllegalStateException("公钥缺失"))
        return KeyPairWrapper(KeyPair(pub, priv))
    }

    private fun pemToDer(pem: String): ByteArray {
        val b64 = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\n", "")
            .replace("\r", "")
            .trim()
        return Base64.getDecoder().decode(b64)
    }

    // ------------------------------------------------------------------
    // OpenSSH wire format
    // ------------------------------------------------------------------

    private fun encodeEd25519OpenSsh(pub: PublicKey): String {
        // Works for both BC and platform keys: ask for the X.509 SubjectPublicKeyInfo
        // and read the raw 32-byte key out of it, instead of relying on
        // EdECPublicKey (which BouncyCastle's key does not implement).
        val spki = pub.encoded
        // SubjectPublicKeyInfo for Ed25519 ends with a BIT STRING of 32 bytes
        val raw = extractEd25519Raw(spki)
        val algo = "ssh-ed25519".toByteArray(Charsets.US_ASCII)
        val out = java.io.ByteArrayOutputStream()
        writeString(out, algo)
        writeString(out, raw)
        return "ssh-ed25519 ${Base64.getEncoder().encodeToString(out.toByteArray())} eqr6-app-phone"
    }

    /**
     * SubjectPublicKeyInfo layout:
     *   SEQUENCE { SEQUENCE { OID 1.3.101.112 } BIT STRING (00 || 32 bytes) }
     * The 32-byte key is the tail; verifying the length keeps this honest.
     */
    private fun extractEd25519Raw(spki: ByteArray): ByteArray {
        // last 32 bytes of the DER blob are the key material for Ed25519
        if (spki.size < 32) throw IllegalStateException("公钥编码异常（长度 ${spki.size}）")
        return spki.copyOfRange(spki.size - 32, spki.size)
    }

    private fun encodeRsaOpenSsh(pub: RSAPublicKey): String {
        val algo = "ssh-rsa".toByteArray(Charsets.US_ASCII)
        val e = pub.publicExponent
        val n = pub.modulus
        val out = java.io.ByteArrayOutputStream()
        writeString(out, algo)
        writeString(out, unsignedBigEndian(e))
        writeString(out, unsignedBigEndian(n))
        return "ssh-rsa ${Base64.getEncoder().encodeToString(out.toByteArray())} eqr6-app-phone"
    }

    /** BigInteger.toByteArray() adds a leading 0x00 when the top bit is set. */
    private fun unsignedBigEndian(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
    }

    private fun decodeOpenSsh(line: String): PublicKey {
        val parts = line.trim().split(" ")
        require(parts.size >= 2) { "公钥格式错误" }
        val blob = Base64.getDecoder().decode(parts[1])
        val type = parts[0]

        var pos = 0
        val algoLen = readInt(blob, pos); pos += 4
        pos += algoLen

        return when (type) {
            "ssh-rsa" -> {
                val eLen = readInt(blob, pos); pos += 4
                val e = BigInteger(1, blob.copyOfRange(pos, pos + eLen)); pos += eLen
                val nLen = readInt(blob, pos); pos += 4
                val n = BigInteger(1, blob.copyOfRange(pos, pos + nLen))
                KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
            }
            "ssh-ed25519" -> {
                val kLen = readInt(blob, pos); pos += 4
                val raw = blob.copyOfRange(pos, pos + kLen)
                rebuildEd25519(raw)
            }
            else -> throw IllegalStateException("不支持的公钥类型: $type")
        }
    }

    /**
     * Rebuild an Ed25519 public key from its 32 raw bytes by wrapping them back
     * into a SubjectPublicKeyInfo, which avoids depending on EdECPublicKeySpec
     * (not implemented by every provider).
     */
    private fun rebuildEd25519(raw: ByteArray): PublicKey {
        val spki = ed25519Spki(raw)
        val spec = X509EncodedKeySpec(spki)
        try {
            if (Security.getProvider("BC") != null) {
                return KeyFactory.getInstance("Ed25519", "BC").generatePublic(spec)
            }
        } catch (e: Throwable) {
            // fall through to the platform
        }
        return KeyFactory.getInstance("Ed25519").generatePublic(spec)
    }

    /** DER for SubjectPublicKeyInfo wrapping a bare Ed25519 key. */
    private fun ed25519Spki(raw: ByteArray): ByteArray {
        require(raw.size == 32) { "Ed25519 公钥长度应为 32，实际 ${raw.size}" }
        // AlgorithmIdentifier: SEQUENCE { OID 1.3.101.112 }
        val algId = byteArrayOf(
            0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70
        )
        // BIT STRING: 03 21 00 <32 bytes>
        val bitString = ByteArray(2 + 1 + raw.size)
        bitString[0] = 0x03
        bitString[1] = (raw.size + 1).toByte()
        bitString[2] = 0x00
        System.arraycopy(raw, 0, bitString, 3, raw.size)

        val inner = algId + bitString
        val out = java.io.ByteArrayOutputStream()
        out.write(0x30)
        writeDerLength(out, inner.size)
        out.write(inner)
        return out.toByteArray()
    }

    private fun writeDerLength(out: java.io.ByteArrayOutputStream, len: Int) {
        if (len < 0x80) {
            out.write(len)
        } else if (len <= 0xFF) {
            out.write(0x81); out.write(len)
        } else {
            out.write(0x82)
            out.write((len shr 8) and 0xFF)
            out.write(len and 0xFF)
        }
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
}
