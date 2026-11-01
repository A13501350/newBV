package dev.frost819.newbv.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.data.security.CredentialCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * [Prefs] 凭据字段加密存储的单元测试。
 *
 * 使用内存生成的 AES 密钥注入 [Prefs]，验证凭据字段（sessData/biliJct 等）
 * 落盘为 `v1:` 密文、历史明文兼容读取、写入自然升级、坏密文按未登录降级、
 * 加密失败回退明文。使用临时文件创建真实 DataStore，不依赖 AndroidKeyStore。
 *
 * 使用 Robolectric 环境：加密失败回退路径会调用 android.util.Log 打日志。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefsCredentialStorageTest {
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var tempDir: File
    private lateinit var key: SecretKey

    @Before
    fun setUp() {
        tempDir =
            kotlin.io.path
                .createTempDirectory(prefix = "prefs_credential_test")
                .toFile()
        dataStore =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                produceFile = { File(tempDir, "Test.preferences_pb") },
            )
        key = KeyGenerator.getInstance("AES").apply { init(KEY_SIZE_BITS) }.generateKey()
        Prefs.resetForTesting()
    }

    @After
    fun tearDown() {
        Prefs.resetForTesting()
        tempDir.deleteRecursively()
    }

    // ===== 加密落盘与 round-trip =====

    @Test
    fun credentialFields_areEncryptedAtRest_andRoundTrip() =
        runBlocking {
            // Given
            Prefs.init(dataStore = dataStore, credentialCipher = CredentialCipher(key))

            // When
            Prefs.sessData = "secret-sessdata"
            Prefs.biliJct = "secret-jct"
            awaitAsyncWrite()

            // Then: 磁盘为 v1 密文，内存读取为明文
            val raw = dataStore.data.first()
            assertThat(raw[PrefKeys.sessData]).startsWith("v1:")
            assertThat(raw[PrefKeys.sessData]).isNotEqualTo("secret-sessdata")
            assertThat(Prefs.sessData).isEqualTo("secret-sessdata")
            assertThat(raw[PrefKeys.biliJct]).startsWith("v1:")
            assertThat(Prefs.biliJct).isEqualTo("secret-jct")
        }

    @Test
    fun allCredentialFields_areWrappedWithEncryption() =
        runBlocking {
            // Given
            Prefs.init(dataStore = dataStore, credentialCipher = CredentialCipher(key))

            // When
            Prefs.sid = "v-sid"
            Prefs.sessData = "v-sess"
            Prefs.biliJct = "v-jct"
            Prefs.uidCkMd5 = "v-md5"
            Prefs.accessToken = "v-access"
            Prefs.refreshToken = "v-refresh"
            Prefs.deviceCookies = "v-cookies"
            awaitAsyncWrite()

            // Then: 全部凭据键落盘为密文且读回原值
            val raw = dataStore.data.first()
            assertThat(raw[PrefKeys.sid]).startsWith("v1:")
            assertThat(raw[PrefKeys.sessData]).startsWith("v1:")
            assertThat(raw[PrefKeys.biliJct]).startsWith("v1:")
            assertThat(raw[PrefKeys.uidCkMd5]).startsWith("v1:")
            assertThat(raw[PrefKeys.accessToken]).startsWith("v1:")
            assertThat(raw[PrefKeys.refreshToken]).startsWith("v1:")
            assertThat(raw[PrefKeys.deviceCookies]).startsWith("v1:")
            assertThat(Prefs.sid).isEqualTo("v-sid")
            assertThat(Prefs.deviceCookies).isEqualTo("v-cookies")
        }

    @Test
    fun credential_writeSurvivesReinit() =
        runBlocking {
            // Given
            Prefs.init(dataStore = dataStore, credentialCipher = CredentialCipher(key))
            Prefs.accessToken = "token-secret"
            awaitAsyncWrite()

            // When: 重置并重新初始化（模拟应用重启，密钥来自 AndroidKeyStore 不变）
            Prefs.resetForTesting()
            Prefs.init(dataStore = dataStore, credentialCipher = CredentialCipher(key))

            // Then
            assertThat(Prefs.accessToken).isEqualTo("token-secret")
        }

    // ===== 历史明文兼容 =====

    @Test
    fun legacyPlaintext_isReadable_andUpgradedOnNextWrite() =
        runBlocking {
            // Given: 磁盘预置历史明文（升级前旧版本写入）
            dataStore.edit { it[PrefKeys.sessData] = "legacy-plain-sess" }

            // When
            Prefs.init(dataStore = dataStore, credentialCipher = CredentialCipher(key))

            // Then: 无 v1: 前缀按明文原样读取
            assertThat(Prefs.sessData).isEqualTo("legacy-plain-sess")

            // When: 下一次写入自然升级为密文
            Prefs.sessData = "legacy-plain-sess"
            awaitAsyncWrite()

            // Then
            assertThat(dataStore.data.first()[PrefKeys.sessData]).startsWith("v1:")
            assertThat(Prefs.sessData).isEqualTo("legacy-plain-sess")
        }

    @Test
    fun withoutCipher_credentialsPersistPlaintext() =
        runBlocking {
            // Given: 未注入加密器（Keystore 不可用降级 / JVM 单测环境）
            Prefs.init(dataStore = dataStore)

            // When
            Prefs.sessData = "plain-value"
            awaitAsyncWrite()

            // Then: 明文落盘且读取正常
            assertThat(dataStore.data.first()[PrefKeys.sessData]).isEqualTo("plain-value")
            assertThat(Prefs.sessData).isEqualTo("plain-value")
        }

    // ===== 坏密文降级 =====

    @Test
    fun undecryptableCiphertext_readsAsEmpty() =
        runBlocking {
            // Given: 密钥 A 加密落盘
            Prefs.init(dataStore = dataStore, credentialCipher = CredentialCipher(key))
            Prefs.sessData = "sess-of-key-a"
            awaitAsyncWrite()

            // When: 密钥被系统重置（重新初始化后使用新密钥 B）
            val newKey = KeyGenerator.getInstance("AES").apply { init(KEY_SIZE_BITS) }.generateKey()
            Prefs.resetForTesting()
            Prefs.init(dataStore = dataStore, credentialCipher = CredentialCipher(newKey))

            // Then: 解密失败返回空串，按未登录处理
            assertThat(Prefs.sessData).isEmpty()
        }

    // ===== 加密失败兜底 =====

    @Test
    fun encryptFailure_fallsBackToPlaintextPersistence() =
        runBlocking {
            // Given: 注入密钥不可用的加密器（模拟 AndroidKeyStore 故障）
            Prefs.init(dataStore = dataStore, credentialCipher = CredentialCipher(BrokenKey()))

            // When
            Prefs.sessData = "fallback-value"
            awaitAsyncWrite()

            // Then: 加密失败回退明文落盘，凭据不丢失
            assertThat(dataStore.data.first()[PrefKeys.sessData]).isEqualTo("fallback-value")
            assertThat(Prefs.sessData).isEqualTo("fallback-value")
        }

    /**
     * 等待异步写入完成。
     *
     * Prefs 的写入是异步的（launchPersist 在 IO 协程执行），
     * 需要短暂等待确保 DataStore 持久化完成。
     */
    private suspend fun awaitAsyncWrite() {
        delay(200)
    }

    /**
     * 编码长度非法的 AES 密钥，用于稳定触发加密失败。
     */
    private class BrokenKey : SecretKey {
        override fun getAlgorithm(): String = "AES"

        override fun getFormat(): String = "RAW"

        override fun getEncoded(): ByteArray = ByteArray(INVALID_KEY_LENGTH_BYTES)
    }

    private companion object {
        const val KEY_SIZE_BITS = 256
        const val INVALID_KEY_LENGTH_BYTES = 7
    }
}
