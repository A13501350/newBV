package dev.frost819.newbv.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * AndroidKeyStore 密钥提供者。
 *
 * 仅负责生成/获取凭据加密密钥（AES-256 GCM），不参与加解密逻辑——
 * 加密行为见 [CredentialCipher]。密钥存储在 AndroidKeyStore 中不可导出；
 * 恢复出厂或清除应用数据会重置密钥，历史密文将解密失败并按未登录处理。
 *
 * 仅可在 Android 运行时使用（JVM 单元测试环境无 AndroidKeyStore，
 * 单测只覆盖 [CredentialCipher]，两者分文件即为此目的）。
 */
object KeystoreKeyProvider {
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"

    /** 凭据加密密钥在 AndroidKeyStore 中的别名。 */
    private const val KEY_ALIAS = "newbv.credential.auth"

    @Volatile
    private var cachedKey: SecretKey? = null

    /**
     * 获取（首次调用时生成）凭据加密密钥。
     *
     * 生成后缓存于进程内，避免重复访问 AndroidKeyStore。
     *
     * @return AES-256 GCM 密钥。
     * @throws Exception AndroidKeyStore 不可用（个别低端设备会抖动）时抛出，
     * 由调用方决定降级策略（本工程降级为明文持久化，不做启动拦截）。
     */
    fun getOrCreate(): SecretKey {
        cachedKey?.let { return it }
        return synchronized(this) {
            cachedKey?.let { return@synchronized it }

            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
                generator.init(
                    KeyGenParameterSpec
                        .Builder(
                            KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
                generator.generateKey()
            }
            val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
            entry.secretKey.also { cachedKey = it }
        }
    }
}
