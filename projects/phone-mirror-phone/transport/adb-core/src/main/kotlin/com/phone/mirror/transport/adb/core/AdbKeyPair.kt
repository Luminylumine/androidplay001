package com.phone.mirror.transport.adb.core

import java.util.Base64
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAKeyGenParameterSpec

/**
 * ADB AUTH RSA-2048 密钥对。
 *
 * **关键事实** (从 AOSP adb_auth_host.cpp / android_pubkey.cpp 精确抄出):
 *
 * 1. ADB public key 不是 X.509 SPKI，而是 Android 自定义 524-byte 结构
 *    ```
 *    +0x000  4  LE uint32  modulus_size_words = 64
 *    +0x004  4  LE uint32  n0inv = -N^-1 mod 2^32
 *    +0x008  256 bytes    modulus (LE, zero-padded)
 *    +0x108  256 bytes    rr = 2^4096 mod N (LE, zero-padded)
 *    +0x208  4  LE uint32  exponent (通常 65537)
 *    TOTAL = 524 bytes
 *    ```
 * 2. Legacy AUTH signature = 对 20-byte token 做 `RSA_sign(NID_sha1, token)`
 *    即: token 本身作为 SHA-1 DigestInfo 内容，不做二次 SHA1。
 *    SHA-1 DigestInfo DER prefix 固定 15 bytes: `30 21 30 09 06 05 2B 0E 03 02 1A 05 00 04 14`
 * 3. 用 `NONEwithRSA` + 手动 DigestInfo prefix 实现，**不能用 SHA1withRSA** (会 double-hash)。
 * 4. MVP 阶段用普通 KeyPairGenerator("RSA") 生成 exportable key (非 AndroidKeyStore)，
 *    存 PKCS#8 private key 到 app-private storage，后续再升级。
 */
class AdbKeyPair(
    /** Java RSA 密钥对 (exportable, MVP 阶段不存 AndroidKeyStore) */
    val keyPair: KeyPair,
    /** ADB 格式 Base64 public key (标准 Base64，700 chars，含一个 '=' padding) */
    val publicKeyBase64: String,
    /** 用于 AUTH/RSAPUBLICKEY 的 payload: base64 + ' ' + comment + '\0' */
    val authPayload: ByteArray,
) {
    companion object {
        /** RSA modulus 的 bit length —— ADB 只支持 2048 */
        private const val RSA_KEY_SIZE = 2048

        /** ADB 524-byte public key 中 modulus/rr 的固定字段宽度 */
        private const val MODULUS_FIELD_SIZE = 256

        /** SHA-1 DigestInfo 的固定 DER prefix —— 用于 NONEwithRSA 手动构造 */
        private val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
            0x30.toByte(), 0x21.toByte(),               // SEQUENCE (33 bytes content)
            0x30.toByte(), 0x09.toByte(),               // SEQUENCE (9 bytes content)
            0x06.toByte(), 0x05.toByte(),               // OID (5 bytes)
            0x2B.toByte(), 0x0E.toByte(), 0x03.toByte(), 0x02.toByte(), 0x1A.toByte(),  // OID: sha1WithRSAEncryption? NO, this is sha1
            0x05.toByte(), 0x00.toByte(),               // NULL
            0x04.toByte(), 0x14.toByte(),               // OCTET STRING (20 bytes = SHA-1 digest)
        )

        /**
         * 生成一个新的 RSA-2048 密钥对 + 编码为 ADB 格式。
         * MVP 用普通 KeyPairGenerator（非 AndroidKeyStore），exportable。
         */
        fun generate(comment: String = "phone-mirror-phone@android"): AdbKeyPair {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(
                RSAKeyGenParameterSpec(RSA_KEY_SIZE, RSAKeyGenParameterSpec.F4),
                java.security.SecureRandom(),
            )
            val kp = kpg.generateKeyPair()
            return fromKeyPair(kp, comment)
        }

        /**
         * 从已有的 KeyPair 构造 AdbKeyPair，编码 public key。
         */
        fun fromKeyPair(kp: KeyPair, comment: String = "phone-mirror-phone@android"): AdbKeyPair {
            val pub = kp.public as RSAPublicKey
            require(pub.modulus.bitLength() <= RSA_KEY_SIZE) {
                "RSA key must be <= $RSA_KEY_SIZE bits, got ${pub.modulus.bitLength()}"
            }

            val raw524 = encodeAdbPublicKey(pub)
            // 标准 Base64（带 padding）：524 字节 → ceil(524/3)*4 = 700 chars（含 1 个 '='）
            // 与 AOSP adb_auth_host 的 key 序列化一致；withoutPadding 会得到 699 chars
            val base64 = Base64.getEncoder().encodeToString(raw524)

            check(base64.length == 700) {
                "ADB public key Base64 should be 700 chars (with padding), got ${base64.length}"
            }

            // AUTH/RSAPUBLICKEY payload: "<base64> <comment>\0"
            val payload = "$base64 $comment\u0000".toByteArray(Charsets.UTF_8)

            return AdbKeyPair(kp, base64, payload)
        }

        /**
         * 编码 RSAPublicKey 为 524-byte Android 自定义格式。
         *
         * AOSP 参考: system/core/libcrypto_utils/android_pubkey.cpp
         */
        fun encodeAdbPublicKey(pub: RSAPublicKey): ByteArray {
            val modulus = pub.modulus
            val exponent = pub.publicExponent

            val two32 = BigInteger.ONE.shiftLeft(32)
            val mask32 = two32.subtract(BigInteger.ONE)

            // n0inv = -N^-1 mod 2^32
            val n0 = modulus.and(mask32)
            val n0inv = two32.subtract(n0.modInverse(two32)).and(mask32)

            // rr = R^2 mod N, R = 2^2048
            val r = BigInteger.ONE.shiftLeft(RSA_KEY_SIZE)
            val rr = r.multiply(r).mod(modulus)

            return ByteBuffer
                .allocate(524)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(64)                                                     // modulus_size_words = 2048/32
                .putInt(n0inv.toLong().toInt())                                 // n0inv LE
                .put(fixedLittleEndian(modulus, MODULUS_FIELD_SIZE))         // modulus LE 256 bytes
                .put(fixedLittleEndian(rr, MODULUS_FIELD_SIZE))              // rr LE 256 bytes
                .putInt(exponent.toInt())                                       // exponent LE
                .array()
        }

        /**
         * 把 BigInteger 固定宽度 LE 编码（正数，高位补零）。
         * BigInteger.toByteArray() 是 BE signed two's complement，可能带 0x00 sign byte。
         * 我们需要把它翻转成 LE，然后左补零到指定宽度。
         */
        private fun fixedLittleEndian(value: BigInteger, size: Int): ByteArray {
            var be = value.toByteArray()
            // 去掉 leading sign byte（正数时只是 0x00）
            if (be.size > 1 && be[0] == 0.toByte()) {
                be = be.copyOfRange(1, be.size)
            }
            require(be.size <= size) {
                "value too large: ${be.size} bytes > $size"
            }

            // flip BE -> LE
            val le = ByteArray(size)
            for (i in be.indices) {
                le[i] = be[be.lastIndex - i]
            }
            // 剩余高位保持 0x00
            return le
        }
    }

    /**
     * 用私钥对 ADB AUTH 的 20-byte token 签名。
     *
     * **精确语义**: `RSA_sign(NID_sha1, token)` —— token 本身被当作 SHA-1 digest。
     * 等价于: `NONEwithRSA(token + SHA1_DIGEST_INFO_PREFIX)` —— 手动拼 DigestInfo 后直接 RSA PKCS#1 v1.5。
     *
     * 绝对不能用 `SHA1withRSA`（会对 token 再做一次 SHA-1，导致 double-hash）。
     *
     * @param token20 adbd 发来的 20-byte AUTH token
     * @return RSA-2048 签名 (固定 256 bytes)
     */
    fun signAdbToken(token20: ByteArray): ByteArray {
        require(token20.size == 20) { "ADB AUTH token must be 20 bytes, got ${token20.size}" }

        val digestInfo = SHA1_DIGEST_INFO_PREFIX + token20

        val sig = Signature.getInstance("NONEwithRSA")
        sig.initSign(keyPair.private)
        sig.update(digestInfo)

        val out = sig.sign()
        require(out.size == 256) { "RSA-2048 signature must be 256 bytes, got ${out.size}" }
        return out
    }
}
