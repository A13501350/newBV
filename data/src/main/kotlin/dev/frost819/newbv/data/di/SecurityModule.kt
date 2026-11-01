package dev.frost819.newbv.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.frost819.newbv.data.security.CredentialCipher
import dev.frost819.newbv.data.security.KeystoreKeyProvider
import javax.inject.Singleton

/**
 * 安全层 Hilt 模块。
 *
 * 提供登录凭据加密器的单例绑定，密钥来自 AndroidKeyStore。
 */
@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {
    /**
     * 提供 [CredentialCipher] 单例。
     *
     * 密钥由 [KeystoreKeyProvider] 从 AndroidKeyStore 生成/获取；
     * 获取失败时由调用方降级（不在此处吞异常，避免掩盖密钥问题）。
     */
    @Provides
    @Singleton
    fun provideCredentialCipher(): CredentialCipher = CredentialCipher(KeystoreKeyProvider.getOrCreate())
}
