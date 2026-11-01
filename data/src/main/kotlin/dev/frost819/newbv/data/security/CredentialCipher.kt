package dev.frost819.newbv.data.security

import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 登录凭据本地持久化加密器（AES-GCM）。
 *
 * 密文格式 `v1:<iv>:<data>`，其中 iv 与密文数据均为 Base64（URL_SAFE）编码。
 * 解密兼容历史数据：不带 [PREFIX] 前缀的值视为历史明文原样返回，
 * 因此加密能力上线后旧数据仍可读，并在下一次写入时自然升级为密文。
 *
 * 与调用方的兜底约定：
 * - [encrypt] 失败返回 null，调用方保留明文落盘并打日志（凭据不因 Keystore 故障丢失）；
 * - [decrypt] 失败返回空串，由上层按"未登录"处理（Keystore 密钥被系统重置时密文整体不可解）；
 * - 空串原样通过（无内容可加密，也不产生密文前缀）。
 *
 * 纯 JVM 实现（不依赖 Android API），通过构造注入 [SecretKey] 即可在单元测试中复现。
 * 生产环境的密钥来自 [KeystoreKeyProvider]（AndroidKeyStore，不可导出）。
 *
 * @param key 用于加解密的 AES 密钥。
 */
class CredentialCipher(
    private val key: SecretKey,
) {
    /**
     * 加密明文值。
     *
     * @param value 明文值；空串原样返回（不产生密文）。
     * @return `v1:<iv>:<data>` 格式密文；加密失败（密钥不可用等）返回 null。
     */
    fun encrypt(value: String): String? {
        if (value.isEmpty()) return value
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            // GCM 加密必须每次使用新随机 IV（由 Cipher 自动生成），复用 IV 会破坏机密性
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            PREFIX + encode(cipher.iv) + SEPARATOR + encode(encrypted)
        }.getOrNull()
    }

    /**
     * 从落盘值还原明文。
     *
     * @param value 落盘值；不带 `v1:` 前缀的历史明文原样返回。
     * @return 明文值；密文格式非法、被篡改或密钥不可用时返回空串（按未登录处理）。
     */
    fun decrypt(value: String): String {
        if (!value.startsWith(PREFIX)) return value
        return runCatching {
            val parts = value.removePrefix(PREFIX).split(SEPARATOR, limit = 2)
            require(parts.size == 2) { "malformed ciphertext" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, decode(parts[0])))
            String(cipher.doFinal(decode(parts[1])), StandardCharsets.UTF_8)
        }.getOrElse { "" }
    }

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().encodeToString(bytes)

    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

    private companion object {
        /** 密文版本前缀，用于区分密文与历史明文。 */
        const val PREFIX = "v1:"

        /** iv 与密文数据的分隔符。 */
        const val SEPARATOR = ":"

        /** AES-GCM 变换（认证标签默认 128 位）。 */
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** GCM 认证标签长度（位）。 */
        const val TAG_LENGTH_BITS = 128
    }
}
