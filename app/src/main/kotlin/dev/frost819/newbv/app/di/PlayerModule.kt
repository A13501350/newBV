package dev.frost819.newbv.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.frost819.newbv.app.util.MediaCodecVideoCapabilityProvider
import dev.frost819.newbv.app.util.VideoCapabilityProvider
import dev.frost819.newbv.data.datastore.Prefs
import dev.frost819.newbv.player.CdnSelector
import dev.frost819.newbv.player.OkHttpCdnSelector
import dev.frost819.newbv.player.OkHttpUtil
import dev.frost819.newbv.player.cache.PlayerMediaCache
import dev.frost819.newbv.player.impl.exo.ExoPlayerFactory
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * 播放器相关 Hilt 模块。
 *
 * 提供播放器工厂等播放器相关的依赖绑定。
 * VideoInfoRepository 已通过 `@Inject constructor` + `@Singleton` 自动绑定，无需在此声明。
 */
@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {
    /**
     * 提供播放器媒体请求与 CDN 测速共用的 OkHttpClient 单例（含自定义 SSL 配置）。
     *
     * 必须保持全进程唯一：OkHttpClient 的 ConnectionPool 是「每个 client 一个」
     * （默认 5 条空闲连接 / 5 分钟保活），且 HTTP/2 连接在池中保活期间会各占一个线程。
     * 若按播放器实例创建，每个播放会话都会新增一个连接池，进程线程数随播放轮次缓慢上升，
     * 同时每次进播放器都要重建 SSLContext/KeyStore。
     */
    @Provides
    @Singleton
    fun providePlayerHttpClient(
        @ApplicationContext context: Context,
    ): OkHttpClient = OkHttpUtil.generateCustomSslOkHttpClient(context)

    /**
     * 提供播放媒体缓存（Media3 SimpleCache 封装）单例。
     *
     * SimpleCache 必须全进程唯一：同一目录同时存在两个实例会因文件锁直接抛异常，
     * 且重复建索引浪费 I/O。容量档位（[Prefs.playbackCacheMb]，0 = 关闭）在每次
     * 创建播放器时惰性读取，变更在下一个播放会话生效。
     *
     * @param context 应用级 Context。
     */
    @Provides
    @Singleton
    fun providePlayerMediaCache(
        @ApplicationContext context: Context,
    ): PlayerMediaCache = PlayerMediaCache(context) { Prefs.playbackCacheMb }

    /**
     * 提供 [ExoPlayerFactory] 单例。
     *
     * 用于创建 Media3 ExoPlayer 实例。通过工厂模式抽象播放器创建，
     * 便于未来扩展其他播放器实现。
     */
    @Provides
    @Singleton
    fun provideExoPlayerFactory(
        playerHttpClient: OkHttpClient,
        mediaCache: PlayerMediaCache?,
    ): ExoPlayerFactory = ExoPlayerFactory(playerHttpClient, mediaCache)

    /**
     * 提供设备视频解码能力查询器。
     *
     * 基于 Media3 `MediaCodecVideoRenderer.supportsFormat` 实现，供播放选流时
     * 过滤超出本机解码能力的编码/画质组合。
     */
    @Provides
    @Singleton
    fun provideVideoCapabilityProvider(
        @ApplicationContext context: Context,
    ): VideoCapabilityProvider = MediaCodecVideoCapabilityProvider(context)

    /**
     * 提供 CDN 自动选择器。
     *
     * 复用 [providePlayerHttpClient] 的共享 client（内部会派生一个短超时的测速专用 client，
     * 但连接池与原 client 一致，避免额外连接池）对候选播放地址测速，
     * 结果按 host 缓存，供 [dev.frost819.newbv.app.viewmodel.player.PlayerViewModel] 选流使用。
     */
    @Provides
    @Singleton
    fun provideCdnSelector(playerHttpClient: OkHttpClient): CdnSelector = OkHttpCdnSelector(playerHttpClient)
}
