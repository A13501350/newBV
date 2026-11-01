package dev.frost819.newbv.data.security

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * [CredentialCipher] 的单元测试。
 *
 * 使用内存生成的 AES 密钥（纯 JVM，不依赖 AndroidKeyStore）验证：
 * v1 密文格式、加解密 round-trip、历史明文兼容读取、坏密文降级返回空、
 * 密钥不可用时 encrypt 失败返回 null。
 */
class CredentialCipherTest {
    private lateinit var key: SecretKey
    private lateinit var cipher: CredentialCipher

    @BeforeEach
    fun setUp() {
        key = KeyGenerator.getInstance("AES").apply { init(KEY_SIZE_BITS) }.generateKey()
        cipher = CredentialCipher(key)
    }

    // ===== v1 密文格式 =====

    @Test
    fun `encrypt produces v1 prefixed format with 12 byte iv`() {
        // Given
        val plaintext = "SESSDATA-secret-value"

        // When
        val encrypted = requireNotNull(cipher.encrypt(plaintext))

        // Then
        assertThat(encrypted).startsWith(PREFIX_V1)
        val parts = encrypted.removePrefix(PREFIX_V1).split(":")
        assertThat(parts).hasSize(2)
        assertThat(Base64.getUrlDecoder().decode(parts[0])).hasLength(IV_LENGTH_BYTES)
        assertThat(parts[1]).isNotEmpty()
    }

    @Test
    fun `encrypt uses url safe base64 alphabet without plus or slash`() {
        // Given
        val plaintext = "value with 中文 and symbols !@#$%"

        // When
        val encrypted = requireNotNull(cipher.encrypt(plaintext))

        // Then
        val payload = encrypted.removePrefix(PREFIX_V1)
        assertThat(payload).doesNotContain("+")
        assertThat(payload).doesNotContain("/")
    }

    // ===== round-trip =====

    @Test
    fun `round trip restores original value`() {
        // Given
        val plaintext = "sessData=abc123; biliJct=xyz789; access_token=tok_中文✓"

        // When
        val decrypted = cipher.decrypt(requireNotNull(cipher.encrypt(plaintext)))

        // Then
        assertThat(decrypted).isEqualTo(plaintext)
    }

    @Test
    fun `round trip restores long value`() {
        // Given
        val plaintext = "x".repeat(4096)

        // When
        val decrypted = cipher.decrypt(requireNotNull(cipher.encrypt(plaintext)))

        // Then
        assertThat(decrypted).isEqualTo(plaintext)
    }

    @Test
    fun `each encryption uses fresh iv so ciphertexts differ`() {
        // Given
        val plaintext = "same-value"

        // When
        val first = requireNotNull(cipher.encrypt(plaintext))
        val second = requireNotNull(cipher.encrypt(plaintext))

        // Then: 随机 IV 使同一明文两次加密产生不同密文，但均可解回原值
        assertThat(first).isNotEqualTo(second)
        assertThat(cipher.decrypt(first)).isEqualTo(plaintext)
        assertThat(cipher.decrypt(second)).isEqualTo(plaintext)
    }

    // ===== 明文兼容读取 =====

    @Test
    fun `decrypt returns plaintext unchanged when prefix missing`() {
        // Given
        val legacyPlaintext = "legacy-plain-sessdata"

        // When
        val decrypted = cipher.decrypt(legacyPlaintext)

        // Then: 历史明文（无 v1: 前缀）原样返回
        assertThat(decrypted).isEqualTo(legacyPlaintext)
    }

    @Test
    fun `decrypt returns empty string for empty value`() {
        // Given
        val empty = ""

        // When
        val decrypted = cipher.decrypt(empty)

        // Then
        assertThat(decrypted).isEmpty()
    }

    @Test
    fun `encrypt passes empty string through without producing ciphertext`() {
        // Given
        val empty = ""

        // When
        val encrypted = cipher.encrypt(empty)

        // Then: 空串无内容可加密，原样返回且不带前缀
        assertThat(encrypted).isEmpty()
    }

    // ===== 坏密文降级 =====

    @Test
    fun `decrypt returns empty for tampered ciphertext`() {
        // Given
        val encrypted = requireNotNull(cipher.encrypt("secret"))
        val dataPart = encrypted.removePrefix(PREFIX_V1).split(":")[1]
        val tampered = dataPart.drop(1) + if (dataPart[0] == 'A') "B" else "A"

        // When
        val decrypted = cipher.decrypt(PREFIX_V1 + "AAAA" + ":" + tampered)

        // Then: 数据或认证标签被篡改时返回空串（按未登录处理）
        assertThat(decrypted).isEmpty()
    }

    @Test
    fun `decrypt returns empty for truncated value without data part`() {
        // Given
        val truncated = PREFIX_V1 + "AAAA"

        // When
        val decrypted = cipher.decrypt(truncated)

        // Then
        assertThat(decrypted).isEmpty()
    }

    @Test
    fun `decrypt returns empty for invalid base64 parts`() {
        // Given
        val malformed = PREFIX_V1 + "!!!invalid-iv!!!:!!!invalid-data!!!"

        // When
        val decrypted = cipher.decrypt(malformed)

        // Then
        assertThat(decrypted).isEmpty()
    }

    @Test
    fun `decrypt returns empty when ciphertext encrypted with different key`() {
        // Given
        val otherKey = KeyGenerator.getInstance("AES").apply { init(KEY_SIZE_BITS) }.generateKey()
        val otherCipher = CredentialCipher(otherKey)
        val encrypted = requireNotNull(otherCipher.encrypt("secret"))

        // When
        val decrypted = cipher.decrypt(encrypted)

        // Then: 密钥被系统重置（换密钥）时密文不可解，降级为空串
        assertThat(decrypted).isEmpty()
    }

    // ===== 密钥不可用兜底 =====

    @Test
    fun `encrypt returns null when key is unusable`() {
        // Given
        val brokenCipher = CredentialCipher(BrokenKey())

        // When
        val encrypted = brokenCipher.encrypt("secret")

        // Then: Cipher 初始化失败（密钥长度非法），调用方保留明文旧值
        assertThat(encrypted).isNull()
    }

    @Test
    fun `decrypt returns empty when key is unusable`() {
        // Given
        val brokenCipher = CredentialCipher(BrokenKey())

        // When
        val decrypted = brokenCipher.decrypt(PREFIX_V1 + "AAAA:AAAA")

        // Then
        assertThat(decrypted).isEmpty()
    }

    /**
     * 编码长度非法的 AES 密钥，用于稳定触发 [Cipher] 初始化失败。
     */
    private class BrokenKey : SecretKey {
        override fun getAlgorithm(): String = "AES"

        override fun getFormat(): String = "RAW"

        override fun getEncoded(): ByteArray = ByteArray(INVALID_KEY_LENGTH_BYTES)
    }

    private companion object {
        const val PREFIX_V1 = "v1:"
        const val KEY_SIZE_BITS = 256
        const val IV_LENGTH_BYTES = 12
        const val INVALID_KEY_LENGTH_BYTES = 7
    }
}
