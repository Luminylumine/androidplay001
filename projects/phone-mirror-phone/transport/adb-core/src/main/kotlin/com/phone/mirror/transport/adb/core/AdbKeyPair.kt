package com.phone.mirror.transport.adb.core

import java.security.KeyPair

/**
 * ADB AUTH 使用的 RSA 密钥对包装。
 *
 * ADB 认证流程：
 *  1. 客户端发 AUTH(type=1 SHA256) → 服务端发 AUTH(type=2 challenge)
 *  2. 客户端用私钥对 challenge 签名 → 返回 AUTH(type=3 signature)
 *  3. 若签名通过，服务端发 CNXN 完整握手
 *  4. 否则（新的 rsa 设备），客户端发 AUTH(type=4 public key) 让用户在设备上授权
 */
data class AdbKeyPair(
    /** 底层 Java [KeyPair]（包含公钥 + 私钥） */
    val keyPair: KeyPair,
    /** ADB 格式的 Base64 公钥 */
    val publicKeyBase64: String,
) {
    companion object {
        /** 从 KeyPair 构造，同时生成 ADB 公钥 Base64 */
        fun fromRsa(keyPair: KeyPair): AdbKeyPair {
            // TODO: 实现 ADB 格式公钥导出（20-byte header + 长度 + modulus + exponent）
            return AdbKeyPair(keyPair, "")
        }
    }

    /** 用私钥对 challenge 字节进行 PKCS1v15 SHA256 签名 */
    fun sign(challenge: ByteArray): ByteArray {
        // TODO: 实际签名逻辑，当前占位
        return challenge
    }
}
